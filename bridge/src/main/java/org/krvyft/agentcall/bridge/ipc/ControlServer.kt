package org.krvyft.agentcall.bridge.ipc

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.core.protocol.RpcCodec
import org.krvyft.agentcall.core.protocol.RpcProtocolException

/**
 * 控制请求处理器。只在 [RolePolicy.authorize] 返回 true 后被调用。
 */
fun interface RpcHandler {
    fun handle(peer: PeerIdentity, request: RpcRequest): RpcResponse
}

/**
 * PCM 数据帧接收器。PCM 走独立 socket，绝不混入控制帧。
 *
 * 返回 false 表示本进程此刻不能接收（有界拒绝）；连接会被关闭，不阻塞电话 UI。
 */
fun interface PcmSink {
    fun accept(peer: PeerIdentity, payload: ByteArray): Boolean
}

/**
 * 特权运行容器的 abstract Unix socket 服务端（spec §6.4）。
 *
 * - **两条通道**：控制通道（[DEFAULT_CONTROL_SOCKET]，JSON 控制帧，最大 64 KiB）与
 *   PCM 通道（[DEFAULT_PCM_SOCKET]，原始 PCM 帧，最大 32 KiB）分开 bind；PCM 只在
 *   注入 [PcmSink] 时启用。两个通道都先用内核凭据核验对端。
 * - **有界资源**：最多 [MAX_CONNECTIONS] 条连接、[MAX_QUEUE_DEPTH] 个排队请求；
 *   连接/队列满时立即关闭新连接并计数，不阻塞 accept 线程，也不阻塞电话 UI。
 * - **校验顺序**：内核凭据 → 包名/签名/角色 → 帧长度 → 请求 ID/版本/会话 →
 *   [RolePolicy] 固定方法表。任一步失败都回错误帧后关闭连接。
 * - **错误内容**：只回机器可读错误码与固定短消息，异常消息只保留异常类名。
 */
class ControlServer(
    private val socketName: String = DEFAULT_CONTROL_SOCKET,
    private val pcmSocketName: String = DEFAULT_PCM_SOCKET,
    private val verifier: PeerVerifier,
    private val handler: RpcHandler,
    private val pcmSink: PcmSink? = null,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val maxQueueDepth: Int = MAX_QUEUE_DEPTH,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    workerCount: Int = WORKER_THREADS,
) : Closeable {

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val connectionSlots = Semaphore(maxConnections)
    private val activeConnections = AtomicInteger(0)
    private val rejectedConnections = AtomicLong(0)
    private val openSockets = ConcurrentHashMap.newKeySet<LocalSocket>()

    private val workers = ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxQueueDepth),
        ThreadFactory { runnable ->
            Thread(runnable, "agentcall-control-worker").apply { isDaemon = true }
        },
    )

    private var controlServerSocket: LocalServerSocket? = null
    private var pcmServerSocket: LocalServerSocket? = null
    private var controlAcceptThread: Thread? = null
    private var pcmAcceptThread: Thread? = null

    /** 当前活动连接数（含排队中的请求），用于 status 诊断。 */
    val activeConnectionCount: Int get() = activeConnections.get()

    /** 因连接数/队列饱和被拒绝的次数，用于 status 诊断。 */
    val rejectedConnectionCount: Long get() = rejectedConnections.get()

    /**
     * 绑定 abstract socket 并启动 accept 线程。绑定失败抛 [IOException]；
     * 调用方应记录机器可读错误并退出，而不是静默降级。
     */
    @Synchronized
    fun start() {
        if (started.get()) return
        try {
            // LocalServerSocket(String) 绑定 abstract 命名空间；客户端用
            // LocalSocketAddress(name, ABSTRACT) 连接同一个名字。
            val control = LocalServerSocket(socketName)
            controlServerSocket = control
            controlAcceptThread = daemonThread("agentcall-control-accept") { acceptLoop(control, isPcm = false) }
            val pcm = pcmSink
            if (pcm != null) {
                val pcmSocket = LocalServerSocket(pcmSocketName)
                pcmServerSocket = pcmSocket
                pcmAcceptThread = daemonThread("agentcall-pcm-accept") { acceptLoop(pcmSocket, isPcm = true) }
            }
            started.set(true)
        } catch (io: IOException) {
            close()
            throw io
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(controlServerSocket)
        closeQuietly(pcmServerSocket)
        controlServerSocket = null
        pcmServerSocket = null
        for (socket in openSockets) {
            closeQuietly(socket)
        }
        openSockets.clear()
        workers.shutdownNow()
        controlAcceptThread?.interrupt()
        pcmAcceptThread?.interrupt()
    }

    // ------------------------------------------------------------------ accept

    private fun acceptLoop(server: LocalServerSocket, isPcm: Boolean) {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (io: IOException) {
                if (closed.get()) return
                sleepQuietly()
                continue
            }
            if (!connectionSlots.tryAcquire()) {
                rejectedConnections.incrementAndGet()
                closeQuietly(socket)
                continue
            }
            activeConnections.incrementAndGet()
            openSockets.add(socket)
            try {
                workers.execute {
                    try {
                        if (isPcm) {
                            handlePcmConnection(socket)
                        } else {
                            handleControlConnection(socket)
                        }
                    } finally {
                        openSockets.remove(socket)
                        closeQuietly(socket)
                        activeConnections.decrementAndGet()
                        connectionSlots.release()
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                // 队列饱和：有界失败，不排队等待，也不影响接受中的连接。
                openSockets.remove(socket)
                closeQuietly(socket)
                activeConnections.decrementAndGet()
                connectionSlots.release()
                rejectedConnections.incrementAndGet()
            }
        }
    }

    // ------------------------------------------------------------------ 控制通道

    private fun handleControlConnection(socket: LocalSocket) {
        try {
            socket.setSoTimeout(readTimeoutMs)
            val identity = verifiedIdentity(socket) ?: return
            val request = when (val result = RpcCodec.readRequestFrame(socket.inputStream)) {
                is RpcCodec.RequestResult.Rejected -> {
                    writeError(socket, UNKNOWN_REQUEST_ID, result.code, result.message)
                    return
                }
                is RpcCodec.RequestResult.Accepted -> result.request
            }
            if (!RolePolicy.authorize(identity, request)) {
                writeError(
                    socket,
                    request.id,
                    ERROR_FORBIDDEN,
                    "role " + identity.role.name + " may not call " + request.method,
                )
                return
            }
            val response = try {
                handler.handle(identity, request)
            } catch (t: Exception) {
                // 不把异常文本回给对端：可能含路径或凭据。
                RpcResponse(id = request.id, error = RpcError(ERROR_INTERNAL, t.javaClass.simpleName))
            }
            writeResponse(socket, response)
        } catch (io: IOException) {
            // 单个连接失败只关闭该连接；accept 循环继续。
        }
    }

    // ------------------------------------------------------------------ PCM 通道

    private fun handlePcmConnection(socket: LocalSocket) {
        try {
            socket.setSoTimeout(readTimeoutMs)
            val identity = verifiedIdentity(socket) ?: return
            if (identity.role != PeerRole.APP) return
            val sink = pcmSink ?: return
            val input = socket.inputStream
            while (!closed.get()) {
                when (val frame = RpcCodec.readFrame(input)) {
                    is RpcCodec.FrameResult.Rejected -> return
                    is RpcCodec.FrameResult.Accepted -> {
                        if (frame.body.size > MAX_PCM_FRAME_BYTES) return
                        if (!sink.accept(identity, frame.body)) return
                    }
                }
            }
        } catch (io: IOException) {
            // 连接中断即结束；PCM 通道不阻塞控制通道。
        }
    }

    // ------------------------------------------------------------------ 凭据/输出

    private fun verifiedIdentity(socket: LocalSocket): PeerIdentity? {
        val credentials = try {
            socket.readPeerCredentials()
        } catch (io: IOException) {
            writeError(
                socket,
                UNKNOWN_REQUEST_ID,
                PeerVerifier.ERROR_CREDENTIALS_INVALID,
                "peer credentials unavailable",
            )
            return null
        }
        return when (val verification = verifier.verify(credentials)) {
            is PeerVerification.Rejected -> {
                writeError(socket, UNKNOWN_REQUEST_ID, verification.code, verification.message)
                null
            }
            is PeerVerification.Trusted -> verification.identity
        }
    }

    private fun writeError(socket: LocalSocket, id: String, code: String, message: String) {
        val safeMessage = message.take(MAX_ERROR_MESSAGE_CHARS)
        writeResponse(socket, RpcResponse(id = id, error = RpcError(code, safeMessage)))
    }

    private fun writeResponse(socket: LocalSocket, response: RpcResponse) {
        try {
            val frame = RpcCodec.encodeResponseFrame(response)
            val output = socket.outputStream
            output.write(frame)
            output.flush()
        } catch (io: IOException) {
            // 对端已断开。
        } catch (protocol: RpcProtocolException) {
            // 可疑/非法响应不写回：最多回一条固定内部错误。
            runCatching {
                val fallback = RpcCodec.encodeResponseFrame(
                    RpcResponse(response.id, error = RpcError(ERROR_INTERNAL, "response_encoding_failed")),
                )
                socket.outputStream.write(fallback)
                socket.outputStream.flush()
            }
        }
    }

    // ------------------------------------------------------------------ 线程/关闭

    private fun daemonThread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply {
            isDaemon = true
            start()
        }

    private fun sleepQuietly() {
        try {
            Thread.sleep(ACCEPT_RETRY_DELAY_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun closeQuietly(socket: LocalSocket?) {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // 关闭失败无需上报。
        }
    }

    private fun closeQuietly(socket: LocalServerSocket?) {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // 关闭失败无需上报。
        }
    }

    companion object {
        /** 控制通道 abstract socket 名（不带 NUL 前缀）。 */
        const val DEFAULT_CONTROL_SOCKET: String = "org.krvyft.agentcall.control"

        /** PCM 通道 abstract socket 名，与控制通道分开 bind。 */
        const val DEFAULT_PCM_SOCKET: String = "org.krvyft.agentcall.pcm"

        const val MAX_CONNECTIONS: Int = 8
        const val MAX_QUEUE_DEPTH: Int = 16
        const val WORKER_THREADS: Int = 2
        const val READ_TIMEOUT_MS: Int = 10_000
        const val MAX_PCM_FRAME_BYTES: Int = 32 * 1024
        const val MAX_ERROR_MESSAGE_CHARS: Int = 400

        /** 帧都没读出来时的响应 ID 占位符（不是对端声明的请求 ID）。 */
        const val UNKNOWN_REQUEST_ID: String = "unknown"

        const val ERROR_FORBIDDEN: String = "forbidden"
        const val ERROR_INTERNAL: String = "internal_error"

        private const val ACCEPT_RETRY_DELAY_MS: Long = 100L
    }
}
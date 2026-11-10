package org.krvyft.agentcall.bridge.ipc

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.krvyft.agentcall.bridge.audio.PcmLaneCodec
import org.krvyft.agentcall.bridge.audio.PcmLaneRead
import org.krvyft.agentcall.bridge.audio.PcmSessionHandler
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
 *   PCM 通道（[DEFAULT_PCM_SOCKET]，PCM lane 帧，最大 32 KiB）分开 bind；PCM 只在
 *   注入 [PcmSink]（单向丢帧诊断）或 [PcmSessionHandler]（双向会话中继，
 *   Task 7A2 的生产路径）时启用。两个通道都先用内核凭据核验对端。
 * - **有界资源**：控制通道最多 [MAX_CONNECTIONS] 条连接、[MAX_QUEUE_DEPTH] 个排队
 *   请求；PCM 通道有独立执行器与独立连接信号量，每条 PCM 连接独占一个线程且上限为
 *   [maxPcmConnections]（默认 [MAX_PCM_CONNECTIONS]）。PCM 是长生命周期读取循环，
 *   因此绝不与控制请求共用 worker 池：控制命令不会被 PCM 流饿死。任一 lane 满时
 *   立即关闭新连接并计数，不阻塞 accept 线程，也不阻塞电话 UI。
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
    private val pcmSession: PcmSessionHandler? = null,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val maxQueueDepth: Int = MAX_QUEUE_DEPTH,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    workerCount: Int = WORKER_THREADS,
    maxPcmConnections: Int = MAX_PCM_CONNECTIONS,
) : Closeable {

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)
    private val openSockets = ConcurrentHashMap.newKeySet<LocalSocket>()

    /** 控制/PCM 两队独立且有界的执行资源，PCM 不可能占用控制 worker。 */
    private val lanes = ConnectionLanes(
        controlWorkers = workerCount,
        controlQueueDepth = maxQueueDepth,
        maxControlConnections = maxConnections,
        maxPcmConnections = maxPcmConnections,
    )

    private var controlServerSocket: LocalServerSocket? = null
    private var pcmServerSocket: LocalServerSocket? = null
    private var controlAcceptThread: Thread? = null
    private var pcmAcceptThread: Thread? = null

    /** 当前活动连接数（含排队中的请求），用于 status 诊断。 */
    val activeConnectionCount: Int get() = activeConnections.get()

    /** 因连接数/队列饱和被拒绝的次数，用于 status 诊断。 */
    val rejectedConnectionCount: Long get() = lanes.rejectedSubmissions

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
            if (pcmSink != null || pcmSession != null) {
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

    /**
     * 停止接受新连接，并给已提交的请求一个很短的有界刷新窗口（例如 shutdown 响应），
     * 之后强制关闭残留连接与线程。重复调用安全。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(controlServerSocket)
        closeQuietly(pcmServerSocket)
        controlServerSocket = null
        pcmServerSocket = null
        lanes.shutdown()
        lanes.awaitTermination(CLOSE_GRACE_MS)
        // 宽限期后仍未结束的长生命周期 PCM 连接由关闭 socket 打破读取循环，随后中断线程。
        for (socket in openSockets) {
            closeQuietly(socket)
        }
        openSockets.clear()
        lanes.shutdownNow()
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
            activeConnections.incrementAndGet()
            openSockets.add(socket)
            val submitted = if (isPcm) {
                lanes.submitPcm { serveConnection(socket, isPcm = true) }
            } else {
                lanes.submitControl { serveConnection(socket, isPcm = false) }
            }
            if (!submitted) {
                // lane 关闭或槽位/队列饱和：有界失败，不排队等待，也不影响已接受的连接。
                openSockets.remove(socket)
                closeQuietly(socket)
                activeConnections.decrementAndGet()
            }
        }
    }

    /** 连接任务外壳：无论通道、成功或异常，都回收 socket/计数/连接槽（槽位由 lane 释放）。 */
    private fun serveConnection(socket: LocalSocket, isPcm: Boolean) {
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
            val sessionHandler = pcmSession
            if (sessionHandler != null) {
                // 首帧是会话打开握手（有界读取）；之后是长生命周期音频流，
                // 读取不再有 soTimeout：结束由会话停止/监督端 close/对端关闭驱动。
                val input = socket.inputStream
                val handshake = when (val read = PcmLaneCodec.readFrame(input)) {
                    is PcmLaneRead.Frame -> read.frame
                    // 连握手都不合法：直接关闭，不猜语义、不写任何可能被误解的响应。
                    else -> return
                }
                socket.setSoTimeout(0)
                sessionHandler.serve(identity, handshake, input, socket.outputStream) {
                    closeQuietly(socket)
                }
                return
            }
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

        /** PCM 通道的显式并发上限：每条连接独占一个线程，绝不占用控制 worker。 */
        const val MAX_PCM_CONNECTIONS: Int = 2

        const val READ_TIMEOUT_MS: Int = 10_000
        const val MAX_PCM_FRAME_BYTES: Int = 32 * 1024
        const val MAX_ERROR_MESSAGE_CHARS: Int = 400

        /** 帧都没读出来时的响应 ID 占位符（不是对端声明的请求 ID）。 */
        const val UNKNOWN_REQUEST_ID: String = "unknown"

        const val ERROR_FORBIDDEN: String = "forbidden"
        const val ERROR_INTERNAL: String = "internal_error"

        private const val ACCEPT_RETRY_DELAY_MS: Long = 100L

        /** 关闭前等待已提交请求写回响应的上限（本地 socket，不需要长等待）。 */
        private const val CLOSE_GRACE_MS: Long = 500L
    }
}

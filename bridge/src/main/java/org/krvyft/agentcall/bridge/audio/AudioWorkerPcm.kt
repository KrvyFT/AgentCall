package org.krvyft.agentcall.bridge.audio

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession

/** 工作进程侧一次已打开的音频会话：会话句柄 + 权威格式。 */
data class PcmOpenedAudio(
    val session: AudioSession,
    val format: AudioFormatSpec,
)

/** 工作进程当前已打开音频的窄来源；生产实现是 [AudioWorker.activeAudio]。 */
fun interface PcmAudioSessionSource {
    /** 只有该会话当前真的处于 OPEN 且匹配时才返回句柄，否则 null（fail closed）。 */
    fun opened(id: SessionId): PcmOpenedAudio?
}

/**
 * 音频工作进程侧的 PCM 服务（Task 7A2）。
 *
 * 一条链路 = 一个已 armed 的会话：
 * 1. 首帧必须是 open 握手（SessionId + 格式），并与 [AudioWorker] 当前真正打开的会话
 *    和 [AudioLeaseCheck] 的租约**同时**匹配；缺一即拒绝，绝不新开/重开音频；
 * 2. 下行 = 会话 [AudioSession.downlink] 的分片 → 二进制 PCM 帧（按 32 KiB 上限切分）；
 * 3. 上行 = 帧 → [AudioSession.writeUplink]，**该调用播放结束才返回**（core AudioBridge
 *    契约）；返回后才回 ack，因此 APP 的 writeUplink 是真正“播完才完成”；
 * 4. 本类绝不 stop 会话、绝不恢复麦克风：链路拆掉只结束本链路。
 */
class AudioPcmSessionService(
    private val sessions: PcmAudioSessionSource,
    private val lease: AudioLeaseCheck,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun serve(
        input: InputStream,
        output: OutputStream,
        setReadTimeoutMs: (Int) -> Unit = {},
        closeLink: () -> Unit = {},
    ) {
        val ioLock = Any()
        val stopped = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        fun write(type: Int, body: ByteArray): Boolean = synchronized(ioLock) {
            PcmLaneCodec.writeFrame(output, type, body)
        }

        fun writeControl(body: ByteArray): Boolean = write(PcmLaneType.CONTROL, body)

        fun reject(code: String, message: String) {
            writeControl(PcmLaneCodec.encodeError(code, message))
        }

        // 1. 有界读取握手。
        setReadTimeoutMs(AudioPcmProtocol.HANDSHAKE_TIMEOUT_MS)
        val first = PcmLaneCodec.readFrame(input)
        setReadTimeoutMs(0)
        val handshake = (first as? PcmLaneRead.Frame)
            ?.takeIf { it.frame.type == PcmLaneType.CONTROL }
            ?.let { PcmLaneCodec.decodeOpen(it.frame.body) }
        if (handshake == null) {
            val code = (first as? PcmLaneRead.Invalid)?.code ?: ERROR_HANDSHAKE_INVALID
            reject(code, "PCM link must start with an open handshake")
            runCatching { closeLink() }
            return
        }
        if (!lease.isAuthorized(handshake.session)) {
            reject(ERROR_NOT_ARMED, "no live audio lease for this session")
            runCatching { closeLink() }
            return
        }
        val opened = sessions.opened(handshake.session)
        if (opened == null) {
            reject(ERROR_NOT_OPEN, "audio session is not open yet")
            runCatching { closeLink() }
            return
        }
        if (opened.format != handshake.format) {
            reject(ERROR_FORMAT_MISMATCH, "requested format does not match the opened audio session")
            runCatching { closeLink() }
            return
        }
        if (!writeControl(PcmLaneCodec.encodeOpenOk(opened.format))) {
            runCatching { closeLink() }
            return
        }

        // 2. 下行泵：会话 downlink → 二进制 PCM 帧；会话结束时通知 APP 并结束链路。
        val downlink = scope.launch {
            try {
                opened.session.downlink.collect { chunk ->
                    if (stopped.get()) return@collect
                    var offset = 0
                    while (offset < chunk.data.size && !stopped.get()) {
                        val remaining = chunk.data.size - offset
                        // MAX_PCM_BYTES 已包含“帧长 = 类型 + 正文”的 -1；这里再取偶，
                        // 保证每个切片本身是合法 PCM16 且 body+1 不超过正文上限。
                        val count = minOf(PcmLaneCodec.MAX_PCM_BYTES, remaining) and 0x7FFFFFFE
                        if (count <= 0) break
                        val slice = chunk.data.copyOfRange(offset, offset + count)
                        if (!write(PcmLaneType.DOWNLINK, slice)) {
                            stopped.set(true)
                            closeLink()
                            return@collect
                        }
                        offset += count
                    }
                }
                if (!stopped.get()) {
                    writeControl(PcmLaneCodec.encodeClosed(REASON_SESSION_STOPPED))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AudioUnavailableException) {
                writeControl(PcmLaneCodec.encodeError(failure.code, failure.message ?: failure.code))
            } catch (t: Throwable) {
                writeControl(PcmLaneCodec.encodeError(ERROR_DOWNLINK_FAILED, "downlink ended unexpectedly"))
            } finally {
                stopped.set(true)
                runCatching { closeLink() }
            }
        }

        // 3. 上行泵：逐帧注入并等播放结束，之后才回 ack（顺序由本线程保证）。
        try {
            while (!stopped.get()) {
                when (val read = PcmLaneCodec.readFrame(input)) {
                    is PcmLaneRead.Frame -> when (read.frame.type) {
                        PcmLaneType.UPLINK -> {
                            val uplink = PcmLaneCodec.decodeUplink(read.frame.body)
                            if (uplink == null) {
                                reject(ERROR_FRAME_INVALID, "uplink frame must be sequence + PCM16 bytes")
                                break
                            }
                            val result = runCatching {
                                runBlocking {
                                    opened.session.writeUplink(PcmAudio(uplink.pcm, opened.format))
                                }
                            }
                            val failure = result.exceptionOrNull()
                            if (failure == null) {
                                if (!write(
                                        PcmLaneType.UPLINK_ACK,
                                        PcmLaneCodec.encodeAck(uplink.seq, true),
                                    )
                                ) {
                                    break
                                }
                            } else {
                                val code = (failure as? AudioUnavailableException)?.code ?: ERROR_UPLINK_FAILED
                                write(
                                    PcmLaneType.UPLINK_ACK,
                                    PcmLaneCodec.encodeAck(uplink.seq, false, code),
                                )
                                break
                            }
                        }
                        PcmLaneType.CONTROL -> {
                            if (PcmLaneCodec.decodeControl(read.frame.body) is PcmControl.Stop) {
                                break
                            }
                            reject(ERROR_CONTROL_INVALID, "unexpected control frame on the PCM link")
                            break
                        }
                        else -> {
                            reject(ERROR_FRAME_TYPE, "only control/uplink frames are accepted")
                            break
                        }
                    }
                    PcmLaneRead.Closed -> break
                    is PcmLaneRead.Invalid -> {
                        reject(read.code, "invalid PCM frame")
                        break
                    }
                }
            }
        } catch (io: IOException) {
            // 对端断开即结束本链路。
        } finally {
            stopped.set(true)
            downlink.cancel()
            scope.cancel()
            runCatching { closeLink() }
        }
    }

    companion object {
        const val ERROR_HANDSHAKE_INVALID: String = "audio_pcm_handshake_invalid"
        const val ERROR_NOT_ARMED: String = "audio_pcm_not_armed"
        const val ERROR_NOT_OPEN: String = "audio_pcm_not_open"
        const val ERROR_FORMAT_MISMATCH: String = "audio_pcm_format_mismatch"
        const val ERROR_FRAME_INVALID: String = "audio_pcm_frame_invalid"
        const val ERROR_FRAME_TYPE: String = "audio_pcm_frame_type_invalid"
        const val ERROR_CONTROL_INVALID: String = "audio_pcm_control_invalid"
        const val ERROR_UPLINK_FAILED: String = "audio_pcm_uplink_failed"
        const val ERROR_DOWNLINK_FAILED: String = "audio_pcm_downlink_failed"
        const val REASON_SESSION_STOPPED: String = "session_stopped"
    }
}

/**
 * 桥接进程侧的 PCM 链路客户端（root 私有 abstract socket，真机未验证）。
 *
 * 服务端 abstract 名字可以被其他进程抢先绑定，因此连接后先核对对端 uid 必须是 root，
 * 再发送任何内容；失败一律抛 [AudioPcmLinkException]，由调用方 fail closed。
 */
class LocalSocketPcmWorkerLink(
    private val socketName: String = AudioPcmProtocol.SOCKET_NAME,
) : PcmWorkerLink {

    private val socket = android.net.LocalSocket()

    fun connect() {
        try {
            socket.connect(
                android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT),
            )
            val serverUid = try {
                socket.peerCredentials?.uid
            } catch (io: IOException) {
                null
            }
            requireRootServer(serverUid)
            socket.soTimeout = AudioPcmProtocol.HANDSHAKE_TIMEOUT_MS
        } catch (error: AudioPcmLinkException) {
            runCatching { socket.close() }
            throw error
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw AudioPcmLinkException(ERROR_UNREACHABLE, "audio PCM server is unreachable", t)
        }
    }

    override val input: InputStream get() = socket.inputStream

    override val output: OutputStream get() = socket.outputStream

    override fun setReadTimeoutMs(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val ERROR_UNREACHABLE: String = "audio_pcm_worker_unavailable"
        const val ERROR_UNTRUSTED_SERVER: String = "audio_pcm_worker_untrusted"

        /**
         * 对端 uid 信任判定（纯函数，JVM 可测）：abstract socket 名字可能被非 root 进程
         * 抢先绑定，因此连接之后、发送任何字节之前必须核对对端是 uid 0，否则 fail closed。
         */
        internal fun requireRootServer(serverUid: Int?) {
            if (serverUid != AudioPcmProtocol.ROOT_UID) {
                throw AudioPcmLinkException(ERROR_UNTRUSTED_SERVER, "audio PCM server uid is not root")
            }
        }
    }
}

/**
 * 音频工作进程侧的 PCM lane 服务端（真机层、未验证）。
 *
 * - abstract Unix socket，不监听网络端口；只接受 uid 0 的对端；
 * - 同时服务 [maxConnections] 条链路（一通电话一条），槽位满即关闭新连接；
 * - 每条连接独占一个线程，握手有界超时，之后由会话结束/关闭 socket 终止；
 * - 分派走 [AudioPcmSessionService]（纯逻辑，已被 JVM 测试覆盖）。
 */
class AudioWorkerPcmSocketServer(
    private val service: AudioPcmSessionService,
    private val socketName: String = AudioPcmProtocol.SOCKET_NAME,
    private val maxConnections: Int = AudioPcmProtocol.MAX_CONNECTIONS,
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val slots = Semaphore(maxConnections)
    private val openSockets = ConcurrentHashMap.newKeySet<android.net.LocalSocket>()
    private var serverSocket: android.net.LocalServerSocket? = null
    private var acceptThread: Thread? = null

    /** 绑定并开始接受连接；绑定失败抛 [IOException]（调用方必须退出，不得假装就绪）。 */
    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val server = android.net.LocalServerSocket(socketName)
        serverSocket = server
        acceptThread = Thread({ acceptLoop(server) }, "agentcall-audio-pcm-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: android.net.LocalServerSocket) {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (io: IOException) {
                if (closed.get()) return
                sleepQuietly()
                continue
            }
            if (!slots.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            val uid = try {
                socket.peerCredentials?.uid
            } catch (io: IOException) {
                null
            }
            if (uid != AudioPcmProtocol.ROOT_UID) {
                slots.release()
                runCatching { socket.close() }
                continue
            }
            openSockets += socket
            Thread(
                {
                    try {
                        socket.soTimeout = AudioPcmProtocol.HANDSHAKE_TIMEOUT_MS
                        service.serve(
                            input = socket.inputStream,
                            output = socket.outputStream,
                            setReadTimeoutMs = { ms -> runCatching { socket.soTimeout = ms } },
                            closeLink = { runCatching { socket.close() } },
                        )
                    } catch (t: Throwable) {
                        // 单条链路失败只关闭这条连接，accept 循环继续。
                    } finally {
                        openSockets -= socket
                        runCatching { socket.close() }
                        slots.release()
                    }
                },
                "agentcall-audio-pcm-session",
            ).apply { isDaemon = true }.start()
        }
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(100L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (socket in openSockets) runCatching { socket.close() }
        openSockets.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }
}

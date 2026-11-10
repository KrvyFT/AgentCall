package org.krvyft.agentcall.bridge.audio

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.MonotonicClock

/** 桥接 → 音频工作进程 PCM 链路的固定名字与上限。 */
object AudioPcmProtocol {
    /**
     * root 私有的 abstract Unix socket 名（不带 NUL 前缀）。
     *
     * 与生命周期通道 [AudioRoleProtocol.SOCKET_NAME] 分开：角色通道是短请求一应答的
     * JSON 行协议，PCM 是长生命周期双向流，混用会让一条音频流占住生命周期请求。
     */
    const val SOCKET_NAME: String = "org.krvyft.agentcall.audio-pcm"

    /** 两个模块角色都以 root app_process 运行；只信任 uid 0 的对端。 */
    const val ROOT_UID: Int = AudioRoleProtocol.ROOT_UID

    /** 握手/首帧的读取上界（之后是长生命周期流，由会话结束/关闭 socket 终止）。 */
    const val HANDSHAKE_TIMEOUT_MS: Int = 5_000

    /** 工作进程侧同时服务的 PCM 链路上限（一通电话一条）。 */
    const val MAX_CONNECTIONS: Int = 2
}

/** PCM 链路错误（机器可读分类；调用方必须 fail closed）。 */
class AudioPcmLinkException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 会话归属判定：生产实现读 [org.krvyft.agentcall.bridge.supervision.Supervisor] 快照的
 * ownedSession；测试注入可变假实现。**只有**监督端当前持有/打开的会话才允许音频。
 */
fun interface PcmSessionOwner {
    fun owns(id: SessionId): Boolean
}

/** 桥接 → 音频工作进程的一条 PCM 链路（生产是 root 私有 abstract socket）。 */
interface PcmWorkerLink : Closeable {
    val input: InputStream

    val output: OutputStream

    /** 设置读取超时（0 = 无限）。默认无操作，测试假实现可忽略。 */
    fun setReadTimeoutMs(ms: Int) {}

    override fun close()
}

/** 建立到音频工作进程 PCM lane 的连接；失败抛异常（调用方按 worker 不可用 fail closed）。 */
fun interface PcmWorkerLinkFactory {
    fun connect(): PcmWorkerLink
}

/**
 * APP 的 PCM 会话连接处理器（由 [org.krvyft.agentcall.bridge.ipc.ControlServer] 在
 * 内核凭据 + package/签名核验之后调用）。
 *
 * [handshake] 是连接上的第一帧（已按 lane 上限读取）；后续帧由实现自己读取。
 * [closeConnection] 关闭底层 socket，用来打破实现侧正在阻塞的读取。
 */
fun interface PcmSessionHandler {
    fun serve(
        peer: PeerIdentity,
        handshake: PcmLaneFrame,
        input: InputStream,
        output: OutputStream,
        closeConnection: () -> Unit,
    )
}

/** 控制面 audio.channel 的打开结果。 */
sealed interface PcmOpenResult {
    data class Opened(val socketName: String, val format: AudioFormatSpec) : PcmOpenResult

    data class Rejected(val code: String, val message: String) : PcmOpenResult
}

/** 单会话绑定状态机。[BOUND] 可重试接入，[ATTACHED] 有活链路，[STALE] 需监督端显式释放。 */
enum class PcmRelayState {
    BOUND,
    ATTACHED,
    STALE,
}

/**
 * APP ↔ bridge ↔ audio worker 的 PCM 中继（Task 7A2）。
 *
 * 安全模型：
 * - **先控制后音频**：APP 先用控制通道 audio.channel 为某个 SessionId 绑定；只有监督端
 *   当前持有（[PcmSessionOwner]）的会话才允许绑定；
 * - **身份绑定**：PCM 连接的内核凭据 uid 必须与发起 audio.channel 的 APP 身份一致，
 *   否则拒绝（消息里自称的 uid 不参与判定）；
 * - **一连接一会话**：接通时用握手里的 SessionId 查绑定，之后帧里没有会话字段，
 *   A 的帧在结构上不可能进入 B 的 worker 链路或 APP 连接；
 * - **每次转发都复核归属**：归属消失后立即下行结束/上行拒绝，不等监督端通知；
 * - **有界**：帧 ≤ 32 KiB、上行队列 ≤ [UPLINK_QUEUE_CAPACITY] 帧、绑定 ≤ [MAX_SESSIONS]、
 *   接入期限 [ATTACH_DEADLINE_MS]；饱和立即以机器码失败，绝不阻塞控制面；
 * - **工作进程重启**：链路建立后异常断开 → STALE，拒绝悄悄重连，直到监督端显式
 *   [release]（例如人工交还/故障终止）。
 *
 * 本类**不**停止音频会话、**不**碰麦克风隔离：音频生命周期只属于监督端与工作进程。
 */
class AudioPcmRelay(
    private val owner: PcmSessionOwner,
    private val workerLinks: PcmWorkerLinkFactory,
    private val clock: MonotonicClock,
    /**
     * audio.channel 返回给 APP 的 PCM lane 名：**必须是 APP 可达的哪个 socket**，
     * 由调用方注入（生产装配点 [org.krvyft.agentcall.bridge.BridgeWiring]）。
     * 没有默认值：默认成 root 私有的 worker lane（[AudioPcmProtocol.SOCKET_NAME]）会让
     * APP 连到一个只接受 uid 0 的 socket，端到端音频直接死掉（评审 C1）。
     */
    private val socketName: String,
    private val maxSessions: Int = MAX_SESSIONS,
    private val uplinkQueueCapacity: Int = UPLINK_QUEUE_CAPACITY,
    private val attachDeadlineMs: Long = ATTACH_DEADLINE_MS,
) : PcmSessionHandler, Closeable {

    private class Binding(
        val session: SessionId,
        val uid: Int,
        val format: AudioFormatSpec,
    ) {
        var state: PcmRelayState = PcmRelayState.BOUND
        var attachDeadlineAtMs: Long = 0L
        var attaching: Boolean = false
        var closeHook: (() -> Unit)? = null
    }

    /** 一次接入的结果；决定 finally 里绑定怎么收尾。 */
    private enum class Outcome {
        /** 工作进程正常结束该会话（下行结束）：绑定整体移除。 */
        SESSION_ENDED,

        /** APP 主动停止/断开：回到 BOUND，允许同一身份在期限内重连。 */
        APP_DETACHED,

        /** 工作进程链路异常（重启/失联）：标记 STALE，必须由监督端显式释放。 */
        WORKER_LOST,
    }

    private val lock = Any()
    private val bindings = HashMap<SessionId, Binding>()
    private val closed = AtomicBoolean(false)

    // ------------------------------------------------------------------ 控制面

    /**
     * 为 [id] 绑定一条 APP PCM 会话。返回可连接的 socket 名与协商格式。
     *
     * 拒绝：监督端不持有该会话、另一 APP 身份已绑定、绑定已 STALE、已有活连接、
     * 容量已满、payload 格式非法（由调用方先校验）。
     */
    fun open(id: SessionId, peer: PeerIdentity, format: AudioFormatSpec): PcmOpenResult {
        if (peer.role != PeerRole.APP) {
            return PcmOpenResult.Rejected(ERROR_PEER_MISMATCH, "only the APP role may open the APP audio lane")
        }
        synchronized(lock) {
            if (closed.get()) {
                return PcmOpenResult.Rejected(ERROR_CLOSED, "bridge PCM relay is closed")
            }
            val now = clock.nowMs()
            sweepLocked(now)
            val existing = bindings[id]
            if (existing != null) {
                if (existing.uid != peer.uid) {
                    return PcmOpenResult.Rejected(ERROR_SESSION_DENIED, "session is bound to another APP identity")
                }
                // 绑定以第一次 audio.channel 的格式为准：改格式不是"重开"，而是换一条流。
                // fail closed（拒绝），而不是悄悄换成新格式让握手对不上。
                if (existing.format != format) {
                    return PcmOpenResult.Rejected(
                        ERROR_FORMAT_MISMATCH,
                        "session is already bound to a different audio format",
                    )
                }
                if (existing.attaching || existing.state == PcmRelayState.ATTACHED) {
                    return PcmOpenResult.Rejected(ERROR_ALREADY_ATTACHED, "session already has a live PCM connection")
                }
                if (existing.state == PcmRelayState.STALE) {
                    return PcmOpenResult.Rejected(ERROR_SESSION_STALE, "session audio must be released before reconnecting")
                }
                if (!owner.owns(id)) {
                    return PcmOpenResult.Rejected(ERROR_SESSION_NOT_OWNED, "supervisor does not own this session")
                }
                existing.attachDeadlineAtMs = now + attachDeadlineMs
                return PcmOpenResult.Opened(socketName, existing.format)
            }
            if (!owner.owns(id)) {
                return PcmOpenResult.Rejected(ERROR_SESSION_NOT_OWNED, "supervisor does not own this session")
            }
            if (bindings.size >= maxSessions) {
                return PcmOpenResult.Rejected(ERROR_CAPACITY, "too many audio sessions are bound")
            }
            bindings[id] = Binding(id, peer.uid, format).apply {
                attachDeadlineAtMs = now + attachDeadlineMs
            }
            return PcmOpenResult.Opened(socketName, format)
        }
    }

    /** 监督端显式释放（人工交还、故障终止、正常结束）：关闭活连接并丢弃队列。 */
    fun release(id: SessionId) {
        val hook: (() -> Unit)?
        synchronized(lock) {
            val binding = bindings.remove(id) ?: return
            hook = binding.closeHook
            binding.closeHook = null
        }
        runCatching { hook?.invoke() }
    }

    /** 当前绑定数（诊断/容量测试）。 */
    fun boundSessionCount(): Int = synchronized(lock) { bindings.size }

    fun sessionState(id: SessionId): PcmRelayState? = synchronized(lock) { bindings[id]?.state }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val hooks = synchronized(lock) {
            val collected = bindings.values.mapNotNull { it.closeHook }
            bindings.values.forEach { it.closeHook = null }
            bindings.clear()
            collected
        }
        for (hook in hooks) runCatching { hook() }
    }

    /**
     * 释放监督端已不再拥有的会话绑定（BOUND/ATTACHED/STALE 都算），并关闭活连接。
     *
     * 生产由监督轮询循环每轮调用（BridgeRuntime 的 poll 线程）：故障终止、断开确认、
     * 人工交还之后不依赖"下一次 audio.channel"来惰性回收，也不会让 4 条容量被已结束的
     * 通话长期占住。返回本次释放的绑定数。
     */
    fun sweepDisowned(): Int {
        val hooks = ArrayList<() -> Unit>()
        val removed = synchronized(lock) {
            val iterator = bindings.entries.iterator()
            var count = 0
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (owner.owns(entry.key)) continue
                entry.value.closeHook?.let(hooks::add)
                entry.value.closeHook = null
                iterator.remove()
                count++
            }
            count
        }
        for (hook in hooks) runCatching { hook() }
        return removed
    }

    // ------------------------------------------------------------------ PCM 连接

    override fun serve(
        peer: PeerIdentity,
        handshake: PcmLaneFrame,
        input: InputStream,
        output: OutputStream,
        closeConnection: () -> Unit,
    ) {
        val sink = AppSink(output)
        val request = if (handshake.type == PcmLaneType.CONTROL) PcmLaneCodec.decodeOpen(handshake.body) else null
        if (request == null) {
            sink.error(ERROR_HANDSHAKE_INVALID, "PCM connection must start with an open handshake")
            closeConnection()
            return
        }
        var selected: Binding? = null
        // 判定在锁内，I/O 在锁外：控制面（audio.channel/open）绝不等 APP 的输出流。
        val rejection = synchronized(lock) {
            val candidate = bindings[request.session]
            val failed = when {
                candidate == null -> ERROR_NOT_BOUND
                candidate.uid != peer.uid -> ERROR_PEER_MISMATCH
                candidate.state == PcmRelayState.STALE -> ERROR_SESSION_STALE
                candidate.attaching || candidate.state == PcmRelayState.ATTACHED -> ERROR_ALREADY_ATTACHED
                clock.nowMs() > candidate.attachDeadlineAtMs -> ERROR_BIND_EXPIRED
                candidate.format != request.format -> ERROR_FORMAT_MISMATCH
                !owner.owns(request.session) -> ERROR_SESSION_NOT_OWNED
                else -> null
            }
            if (failed == null) {
                candidate!!.attaching = true
                selected = candidate
            }
            failed
        }
        val binding = selected
        if (rejection != null || binding == null) {
            sink.error(rejection ?: ERROR_NOT_BOUND, "PCM attach rejected")
            closeConnection()
            return
        }

        val link = try {
            workerLinks.connect()
        } catch (t: Throwable) {
            val code = (t as? AudioPcmLinkException)?.code ?: ERROR_WORKER_UNAVAILABLE
            rebound(binding)
            sink.error(code, "audio worker PCM lane is unavailable")
            closeConnection()
            return
        }

        // 工作进程握手：open → open_ok / error。首帧读取有界，之后是长生命周期流。
        val handshakeWritten = try {
            link.setReadTimeoutMs(AudioPcmProtocol.HANDSHAKE_TIMEOUT_MS)
            PcmLaneCodec.writeFrame(
                link.output,
                PcmLaneType.CONTROL,
                PcmLaneCodec.encodeOpen(request.session, request.format),
            )
        } catch (t: Throwable) {
            false
        }
        if (!handshakeWritten) {
            runCatching { link.close() }
            failingWorker(binding, sink, closeConnection)
            return
        }
        val response = try {
            PcmLaneCodec.readFrame(link.input)
        } catch (t: Throwable) {
            PcmLaneRead.Invalid(ERROR_WORKER_LOST)
        } finally {
            runCatching { link.setReadTimeoutMs(0) }
        }
        val control = (response as? PcmLaneRead.Frame)
            ?.takeIf { it.frame.type == PcmLaneType.CONTROL }
            ?.let { PcmLaneCodec.decodeControl(it.frame.body) }
        when (control) {
            is PcmControl.OpenOk -> {
                if (control.format != request.format) {
                    runCatching { link.close() }
                    sink.error(ERROR_FORMAT_MISMATCH, "audio worker opened a different format")
                    markStale(binding, closeConnection)
                    return
                }
            }
            is PcmControl.Error -> {
                // 工作进程尚未打开该会话（例如 ACTIVE 还没到）：保持 BOUND，可重试。
                runCatching { link.close() }
                rebound(binding)
                sink.error(control.code, control.message)
                closeConnection()
                return
            }
            else -> {
                runCatching { link.close() }
                failingWorker(binding, sink, closeConnection)
                return
            }
        }

        val connection = Connection(
            session = request.session,
            binding = binding,
            link = link,
            sink = sink,
            queue = ArrayBlockingQueue(uplinkQueueCapacity),
            closeConnection = closeConnection,
        )
        // 握手期间监督端可能已经显式释放/关闭中继：不能再把链路登记为活连接。
        val stillBound = synchronized(lock) {
            if (closed.get() || bindings[request.session] !== binding) {
                binding.attaching = false
                false
            } else {
                binding.state = PcmRelayState.ATTACHED
                binding.attaching = false
                binding.closeHook = { connection.stop() }
                true
            }
        }
        if (!stillBound) {
            connection.stop()
            runCatching { closeConnection() }
            return
        }
        if (!sink.control(PcmLaneCodec.encodeOpenOk(request.format))) {
            connection.stop()
            finish(binding, request.session, connection.outcome.get(), connection.queue)
            runCatching { closeConnection() }
            return
        }
        val writer = Thread({ connection.writeUplink() }, "agentcall-pcm-uplink").apply {
            isDaemon = true
            start()
        }
        val reader = Thread({ connection.readWorker() }, "agentcall-pcm-downlink").apply {
            isDaemon = true
            start()
        }

        // 本线程负责 APP → 桥接方向（上行与控制帧），并负责整个连接的有界收尾。
        try {
            while (!connection.stopped.get()) {
                when (val read = PcmLaneCodec.readFrame(input)) {
                    is PcmLaneRead.Frame -> when (read.frame.type) {
                        PcmLaneType.UPLINK -> {
                            if (!owner.owns(request.session)) {
                                sink.error(ERROR_SESSION_NOT_OWNED, "supervisor no longer owns this session")
                                connection.outcome.set(Outcome.APP_DETACHED)
                                connection.stop()
                            } else if (PcmLaneCodec.decodeUplink(read.frame.body) == null) {
                                sink.error(ERROR_FRAME_INVALID, "uplink frame must be sequence + PCM16 bytes")
                                connection.stop()
                            } else if (!connection.queue.offer(read.frame.body)) {
                                sink.error(ERROR_UPLINK_SATURATED, "uplink queue is saturated")
                                connection.stop()
                            }
                        }
                        PcmLaneType.CONTROL -> {
                            if (PcmLaneCodec.decodeControl(read.frame.body) is PcmControl.Stop) {
                                sink.closed(REASON_APP_STOP)
                            } else {
                                sink.error(ERROR_CONTROL_INVALID, "unexpected control frame from APP")
                            }
                            connection.stop()
                        }
                        else -> {
                            sink.error(ERROR_FRAME_TYPE, "only control/uplink frames are accepted from APP")
                            connection.stop()
                        }
                    }
                    PcmLaneRead.Closed -> break
                    is PcmLaneRead.Invalid -> {
                        sink.error(read.code, "invalid PCM frame")
                        connection.outcome.set(Outcome.APP_DETACHED)
                        connection.stop()
                    }
                }
            }
        } catch (io: IOException) {
            // 对端断开即结束本连接。
        } finally {
            connection.stop()
            writer.interrupt()
            reader.interrupt()
            finish(binding, request.session, connection.outcome.get(), connection.queue)
            runCatching { closeConnection() }
        }
    }

    /** 工作进程在握手阶段就失联：标记 STALE（可能仍有一份活音频在旧进程里）。 */
    private fun failingWorker(binding: Binding, sink: AppSink, closeConnection: () -> Unit) {
        sink.error(ERROR_WORKER_LOST, "audio worker PCM lane ended")
        markStale(binding, closeConnection)
    }

    private fun markStale(binding: Binding, closeConnection: () -> Unit) {
        synchronized(lock) {
            binding.attaching = false
            if (bindings[binding.session] === binding) binding.state = PcmRelayState.STALE
        }
        closeConnection()
    }

    /** 一次已建立链路的双向泵：上行队列有界，下行逐帧转发并复核会话归属。 */
    private inner class Connection(
        val session: SessionId,
        val binding: Binding,
        val link: PcmWorkerLink,
        val sink: AppSink,
        val queue: ArrayBlockingQueue<ByteArray>,
        val closeConnection: () -> Unit,
    ) {
        val stopped = AtomicBoolean(false)
        val outcome = AtomicReference(Outcome.APP_DETACHED)

        fun stop() {
            stopped.set(true)
            runCatching { link.close() }
            runCatching { closeConnection() }
        }

        fun fault() {
            outcome.set(Outcome.WORKER_LOST)
            sink.error(ERROR_WORKER_LOST, "audio worker PCM lane ended")
            stop()
        }

        /** 上行泵：从有界队列取帧写工作进程；写失败即故障（不阻塞 APP 读取线程）。 */
        fun writeUplink() {
            try {
                while (!stopped.get()) {
                    val body = queue.poll(UPLINK_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    if (!PcmLaneCodec.writeFrame(link.output, PcmLaneType.UPLINK, body)) {
                        fault()
                        return
                    }
                }
            } catch (t: Throwable) {
                if (!stopped.get()) fault()
            }
        }

        /** 下行泵：工作进程 → APP；ack 只在播放完成后由工作进程发出，原样转发。 */
        fun readWorker() {
            try {
                while (!stopped.get()) {
                    when (val read = PcmLaneCodec.readFrame(link.input)) {
                        is PcmLaneRead.Frame -> when (read.frame.type) {
                            PcmLaneType.DOWNLINK -> {
                                if (!owner.owns(session)) {
                                    sink.closed(REASON_SESSION_RELEASED)
                                    outcome.set(Outcome.APP_DETACHED)
                                    stop()
                                    return
                                }
                                if (!sink.downlink(read.frame.body)) {
                                    stop()
                                    return
                                }
                            }
                            PcmLaneType.UPLINK_ACK -> {
                                if (!sink.ack(read.frame.body)) {
                                    stop()
                                    return
                                }
                            }
                            PcmLaneType.CONTROL -> {
                                when (PcmLaneCodec.decodeControl(read.frame.body)) {
                                    is PcmControl.Closed, is PcmControl.Error -> {
                                        sink.control(read.frame.body)
                                        outcome.set(Outcome.SESSION_ENDED)
                                    }
                                    else -> {
                                        fault()
                                        return
                                    }
                                }
                                stop()
                                return
                            }
                            else -> {
                                fault()
                                return
                            }
                        }
                        PcmLaneRead.Closed, is PcmLaneRead.Invalid -> {
                            fault()
                            return
                        }
                    }
                }
            } catch (t: Throwable) {
                if (!stopped.get()) fault()
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 完成绑定收尾；只有仍是同一条绑定时才改状态（防止被 release 覆盖）。 */
    private fun finish(
        binding: Binding,
        session: SessionId,
        outcome: Outcome,
        queue: ArrayBlockingQueue<ByteArray>,
    ) {
        queue.clear()
        synchronized(lock) {
            binding.closeHook = null
            binding.attaching = false
            if (bindings[session] !== binding) return
            when (outcome) {
                Outcome.SESSION_ENDED -> bindings.remove(session)
                Outcome.APP_DETACHED -> {
                    if (binding.state == PcmRelayState.ATTACHED) {
                        binding.state = PcmRelayState.BOUND
                    }
                    binding.attachDeadlineAtMs = clock.nowMs() + attachDeadlineMs
                }
                Outcome.WORKER_LOST -> binding.state = PcmRelayState.STALE
            }
        }
    }

    /** 工作进程握手被拒绝（尚未建立流）：保持 BOUND，允许在期限内重试。 */
    private fun rebound(binding: Binding) {
        synchronized(lock) {
            binding.attaching = false
            binding.attachDeadlineAtMs = clock.nowMs() + attachDeadlineMs
        }
    }

    private fun sweepLocked(now: Long) {
        val iterator = bindings.entries.iterator()
        while (iterator.hasNext()) {
            val (session, binding) = iterator.next()
            if (binding.attaching || binding.state == PcmRelayState.ATTACHED) continue
            val expired = binding.state == PcmRelayState.BOUND && now > binding.attachDeadlineAtMs
            // STALE 只在监督端确认不再持有该会话后才回收，绝不把“可能还活着”的会话放行。
            val staleWithoutOwner = binding.state == PcmRelayState.STALE && !owner.owns(session)
            if (expired || staleWithoutOwner) iterator.remove()
        }
    }

    /** APP 输出流的串行写入器；任何写入失败都让连接收尾。 */
    private class AppSink(private val output: OutputStream) {

        private val writeLock = Any()

        fun control(body: ByteArray): Boolean = write(PcmLaneType.CONTROL, body)

        fun downlink(body: ByteArray): Boolean = write(PcmLaneType.DOWNLINK, body)

        fun ack(body: ByteArray): Boolean = write(PcmLaneType.UPLINK_ACK, body)

        fun error(code: String, message: String) {
            control(PcmLaneCodec.encodeError(code, message))
        }

        fun closed(reason: String) {
            control(PcmLaneCodec.encodeClosed(reason))
        }

        private fun write(type: Int, body: ByteArray): Boolean = synchronized(writeLock) {
            PcmLaneCodec.writeFrame(output, type, body)
        }
    }

    companion object {
        /** 单条绑定的接入期限：控制面 open 之后必须在这个窗口内接上 PCM。 */
        const val ATTACH_DEADLINE_MS: Long = 10_000L

        /** 同时存在的会话绑定上限（PCM 连接上限另有 OS/lane 约束）。 */
        const val MAX_SESSIONS: Int = 4

        /**
         * 上行在途帧上限。16 帧 ≈ 512 KiB ≈ 16 s @16 kHz PCM16；
         * 7B 的 APP 必须按窗口发送并在 ack 上阻塞（播放完成契约）。
         */
        const val UPLINK_QUEUE_CAPACITY: Int = 16

        private const val UPLINK_POLL_MS: Long = 50L

        const val REASON_APP_STOP: String = "app_stop"
        const val REASON_SESSION_RELEASED: String = "session_released"

        const val ERROR_SESSION_NOT_OWNED: String = "audio_session_not_owned"
        const val ERROR_SESSION_DENIED: String = "audio_session_denied"
        const val ERROR_SESSION_STALE: String = "audio_pcm_session_stale"
        const val ERROR_NOT_BOUND: String = "audio_pcm_not_bound"
        const val ERROR_PEER_MISMATCH: String = "audio_pcm_peer_mismatch"
        const val ERROR_ALREADY_ATTACHED: String = "audio_pcm_already_attached"
        const val ERROR_BIND_EXPIRED: String = "audio_pcm_bind_expired"
        const val ERROR_CAPACITY: String = "audio_pcm_capacity"
        const val ERROR_CLOSED: String = "audio_pcm_closed"
        const val ERROR_HANDSHAKE_INVALID: String = "audio_pcm_handshake_invalid"
        const val ERROR_FORMAT_MISMATCH: String = "audio_pcm_format_mismatch"
        const val ERROR_WORKER_UNAVAILABLE: String = "audio_pcm_worker_unavailable"
        const val ERROR_WORKER_LOST: String = "audio_pcm_worker_lost"
        const val ERROR_UPLINK_SATURATED: String = "audio_pcm_uplink_saturated"
        const val ERROR_FRAME_INVALID: String = "audio_pcm_frame_invalid"
        const val ERROR_FRAME_TYPE: String = "audio_pcm_frame_type_invalid"
        const val ERROR_CONTROL_INVALID: String = "audio_pcm_control_invalid"
    }
}

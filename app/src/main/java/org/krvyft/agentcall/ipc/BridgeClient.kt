package org.krvyft.agentcall.ipc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.PcmEncoding
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.core.protocol.RpcCodec
import org.krvyft.agentcall.core.protocol.RpcProtocolException

/**
 * 控制面传输：一次控制请求/响应。
 *
 * 生产实现是 [LocalSocketControlTransport]（每个请求一条 LocalSocket，服务端是一请求一响应）；
 * JVM 测试注入假实现，直接观察 [RpcRequest] 的方法名与负载。客户端从不发送 uid、包名或
 * 角色：身份只由 bridge 从内核凭据核验，消息里自称的身份不参与判定。
 */
interface ControlTransport {
    /** 发送一条请求并返回对应响应；无法连接/读写时返回 null（调用方 fail closed）。 */
    suspend fun request(request: RpcRequest): RpcResponse?

    suspend fun close()
}

/** 一条已连接的 PCM lane（原始字节；成帧由 [BridgeClient] 负责）。 */
interface PcmLaneConnection {
    /** 写出完整一帧；失败抛异常。 */
    suspend fun write(bytes: ByteArray)

    /** 读取恰好 [length] 字节；对端关闭返回 null。 */
    suspend fun readExact(length: Int): ByteArray?

    suspend fun close()
}

/** 连接 APP 的 PCM lane（abstract socket 名由 audio.channel 返回）。 */
fun interface PcmLaneTransport {
    /** 连接失败返回 null（调用方 fail closed，只有 audio_pcm_not_open 才允许重试）。 */
    suspend fun open(socketName: String): PcmLaneConnection?
}

/**
 * 音频 lane 的机器可读失败。[code] 来自固定错误码表，绝不含号码、密钥或会话原值；
 * 调用方（ConversationEngine / SessionCoordinator）按 code 判定是否可重试或必须交还人工。
 */
class AudioLaneException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 人工交还（handover）的裁决；[NOT_OWNED] 表示监督端本来就不持有该会话。 */
enum class HandoverOutcome {
    CONFIRMED,
    NOT_OWNED,
    REVOKE_FAILED,
    RESTORE_FAILED,
    UNAVAILABLE,
}

/** handover 的结果。[code] 是机器码（例如 bridge 不可用时的固定错误码），不含敏感数据。 */
data class HandoverResult(
    val outcome: HandoverOutcome,
    val code: String? = null,
)

/**
 * APP 角色的本地桥接客户端（spec §6.4 / Task 7B1）。
 *
 * 职责与边界：
 * - **身份**：只调用 APP 固定方法表（session.request / session.heartbeat / session.command /
 *   config.read / config.secret.read / audio.channel / handover）。从不声称 uid/包名/角色；
 *   控制传输还要求服务端内核凭据是 root（见 [LocalSocketControlTransport]）。
 * - **接听裁决**：[send] 的 Answer 只走 **session.request**（监督端授权、取租约、隔离麦克风并
 *   下发接听），Hangup 只走 **session.command**。因此“session.request 在途中又发
 *   session.command(answer)”在结构上不可能发生——answer 从不作为命令发送。
 * - **心跳**：[heartbeatWhileLive] 在会话存活期间按 [heartbeatIntervalMs]（≤5000 ms）持续发送
 *   session.heartbeat；用注入的 [CoroutineScope] 与 [CoroutineDispatcher]，没有 GlobalScope。
 * - **密钥**：只有 [readSecret] 会读取密钥原值，且必须由 API 层显式调用；[readRedactedConfig]
 *   永远只返回脱敏配置。日志只写机器码。
 * - **音频**：audio.channel 只是绑定，随后连接 PCM lane 并重复同一 SessionId/格式做首帧握手；
 *   下行 0x02、上行 0x03（4 字节大端序号）、ACK 0x04。[AudioSession.writeUplink] 只有在对应
 *   ACK 到达（工作进程真的播放结束）后才返回；在途上行帧 ≤16（与 bridge 的
 *   UPLINK_QUEUE_CAPACITY 同值）；只对 audio_pcm_not_open 做有界接入重试（ACTIVE 前的正常窗口）；
 *   任何时刻最多一条 lane，打开新会话会关闭旧 lane，旧句柄的写入以
 *   [ERROR_AUDIO_LANE_SUPERSEDED] 失败，绝不跨会话复用。
 */
class BridgeClient(
    private val control: ControlTransport,
    private val pcm: PcmLaneTransport,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val attachRetryAttempts: Int = ATTACH_RETRY_ATTEMPTS,
    private val attachRetryDelayMs: Long = ATTACH_RETRY_DELAY_MS,
    private val logger: (String) -> Unit = {},
) : CallControl, AudioBridge {

    init {
        require(heartbeatIntervalMs in 1..HEARTBEAT_TIMEOUT_MS) {
            "heartbeat interval must stay within the 5000 ms timeout"
        }
        require(ackTimeoutMs > 0) { "ackTimeoutMs must be positive" }
        require(attachRetryAttempts > 0) { "attachRetryAttempts must be positive" }
        require(attachRetryDelayMs >= 0) { "attachRetryDelayMs must not be negative" }
    }

    private val requestCounter = AtomicLong(0)
    private val laneLock = Any()

    @Volatile
    private var currentLane: PcmAudioSession? = null

    @Volatile
    private var heartbeatFailures: Long = 0

    @Volatile
    private var protocolViolations: Long = 0

    /** 连续失败的心跳次数（诊断用；不代表会话一定失联）。 */
    val heartbeatFailureCount: Long get() = heartbeatFailures

    /** 错配的响应、非法帧等协议违规次数（诊断用）。 */
    val protocolViolationCount: Long get() = protocolViolations

    /** 最近一次成功心跳的注入时钟时刻（ms）；-1 表示还没有成功过。 */
    @Volatile
    var lastHeartbeatAtMs: Long = -1L
        private set

    // ------------------------------------------------------------------ 就绪/心跳

    /**
     * 与服务就绪握手：发一条只读的 config.read（APP 固定表内、无会话、无密钥）并确认收到
     * 合法响应。失败（对端未起、鉴权失败、响应错配）一律返回 false；不发出任何接听命令。
     */
    suspend fun ensureReady(): Boolean {
        val response = controlRequest(RolePolicy.METHOD_CONFIG_READ, null, "") ?: return false
        return response.error == null
    }

    /** 发送一次 session.heartbeat；只表达 APP 仍在看着这通电话，不改变监督阶段。 */
    suspend fun heartbeat(session: SessionId): Boolean {
        val response = controlRequest(RolePolicy.METHOD_SESSION_HEARTBEAT, session, "") ?: return false
        return response.error == null
    }

    /**
     * 在会话存活期间持续发送心跳，直到调用方取消子 Job。
     *
     * 间隔必须 ≤ [HEARTBEAT_TIMEOUT_MS]（5000 ms），否则监督端会先把 APP 判为失联并终止
     * 这通电话。全部执行在调用方注入的 [CoroutineScope] 中，不使用 GlobalScope。
     */
    suspend fun heartbeatWhileLive(session: SessionId) {
        while (true) {
            val startedAt = clock.nowMs()
            if (heartbeat(session)) {
                heartbeatFailures = 0
                lastHeartbeatAtMs = startedAt
            } else {
                heartbeatFailures += 1
                logger("bridge.heartbeat_failed")
            }
            // 以“本次发送开始时刻”为基准排下一次：单次请求耗时不会把间隔越推越远，
            // 保证相邻两次心跳的发送时刻差 ≤ heartbeatIntervalMs（≤5000 ms）。
            val remaining = heartbeatIntervalMs - (clock.nowMs() - startedAt)
            if (remaining > 0) delay(remaining)
        }
    }

    // ------------------------------------------------------------------ CallControl

    /**
     * 下发受限通话命令。
     *
     * - [CallCommand.Answer] → session.request（监督端授权/取租约/隔离/接听），**不是**命令；
     * - [CallCommand.Hangup] → session.command {"command":"hangup"}。
     */
    override suspend fun send(id: SessionId, command: CallCommand): CommandResult = when (command) {
        CallCommand.Answer -> requestAnswer(id)
        CallCommand.Hangup -> command(id, HANGUP)
    }

    /** 请求监督端为 [id] 准备接听并取租约；结果来自 HOOK 的真实执行结果。 */
    suspend fun requestAnswer(id: SessionId): CommandResult {
        val response = controlRequest(RolePolicy.METHOD_SESSION_REQUEST, id, "")
            ?: return CommandResult.Failed(ERROR_BRIDGE_UNAVAILABLE, "bridge control channel is unavailable")
        return decodeCommandResult(response)
    }

    /** 请求挂断 [id]（受限命令，由 HOOK 按会话/代次核验后执行）。 */
    suspend fun hangup(id: SessionId): CommandResult = command(id, HANGUP)

    private suspend fun command(id: SessionId, verb: String): CommandResult {
        val payload = "{\"command\":\"" + verb + "\"}"
        val response = controlRequest(RolePolicy.METHOD_SESSION_COMMAND, id, payload)
            ?: return CommandResult.Failed(ERROR_BRIDGE_UNAVAILABLE, "bridge control channel is unavailable")
        return decodeCommandResult(response)
    }

    // ------------------------------------------------------------------ 人工交还/配置

    /**
     * 人工交还：与监督端串行撤销 AI 所有权；[HandoverOutcome.CONFIRMED] 才表示麦克风与人工
     * 路由已经恢复。[REVOKE_FAILED]/[RESTORE_FAILED] 都不得显示“交还成功”。
     */
    suspend fun handover(id: SessionId): HandoverResult {
        val response = controlRequest(RolePolicy.METHOD_HANDOVER, id, "")
            ?: return HandoverResult(HandoverOutcome.UNAVAILABLE, ERROR_BRIDGE_UNAVAILABLE)
        val error = response.error
        if (error != null) {
            logger("bridge.handover_failed code=" + error.code)
            return HandoverResult(HandoverOutcome.UNAVAILABLE, error.code)
        }
        val json = parseObject(response.payload.toByteArray(Charsets.UTF_8))
            ?: return HandoverResult(HandoverOutcome.UNAVAILABLE, "handover_payload_invalid")
        val outcome = when (json.string("outcome")) {
            "confirmed" -> HandoverOutcome.CONFIRMED
            "not_owned" -> HandoverOutcome.NOT_OWNED
            "revoke_failed" -> HandoverOutcome.REVOKE_FAILED
            "restore_failed" -> HandoverOutcome.RESTORE_FAILED
            else -> HandoverOutcome.UNAVAILABLE
        }
        return HandoverResult(outcome, json.string("reason"))
    }

    /**
     * 读取脱敏配置（[RpcCodec.encodeRedacted] 的负载：公开配置 + 密钥状态，绝无密钥原值）。
     * @return 负载文本；失败返回 null。
     */
    suspend fun readRedactedConfig(): String? {
        val response = controlRequest(RolePolicy.METHOD_CONFIG_READ, null, "") ?: return null
        val error = response.error
        if (error != null) {
            logger("bridge.config_read_failed code=" + error.code)
            return null
        }
        return response.payload
    }

    /**
     * 读取某一项密钥原值。**只允许 API 层在真正装配远程客户端时显式调用**：返回值不得进入
     * 日志、UI、异常消息或普通存储。密钥未配置或读取失败返回 null。
     */
    suspend fun readSecret(kind: EndpointKind): String? {
        val payload = "{\"kind\":\"" + kind.name + "\"}"
        val response = controlRequest(RolePolicy.METHOD_CONFIG_SECRET_READ, null, payload) ?: return null
        if (response.error != null) return null
        val json = parseObject(response.payload.toByteArray(Charsets.UTF_8)) ?: return null
        if (json.string("kind") != kind.name) return null
        val value = json.get("value") ?: return null
        if (value.isJsonNull) return null
        return value.asStringOrNull()
    }

    // ------------------------------------------------------------------ AudioBridge

    /**
     * 为 [id] 打开双向音频：先 audio.channel 绑定，再连 PCM lane 做首帧握手。
     *
     * 打开新会话一定先关闭旧 lane（绝不跨会话复用），也绝不在失败时返回“无声成功”会话。
     * 只有 audio_pcm_not_open（工作进程还没有该会话的 OPEN 音频，通常在 ACTIVE 之前）会按
     * [attachRetryDelayMs] 有界重试；其余错误码立即失败。
     */
    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        closeCurrentLane(ERROR_AUDIO_LANE_SUPERSEDED)
        val socketName = openChannel(id, format)
        val lane = attach(id, format, socketName)
        synchronized(laneLock) { currentLane = lane }
        return lane
    }

    /** 关闭当前 lane 与控制传输；重复调用安全。 */
    suspend fun close() {
        closeCurrentLane(ERROR_AUDIO_LANE_STOPPED)
        runCatching { control.close() }
    }

    private suspend fun openChannel(id: SessionId, format: AudioFormatSpec): String {
        val payload = "{\"format\":{\"sampleRate\":" + format.sampleRate +
            ",\"channels\":" + format.channels +
            ",\"encoding\":\"" + format.encoding.name + "\"}}"
        val response = controlRequest(RolePolicy.METHOD_AUDIO_CHANNEL, id, payload)
            ?: throw AudioLaneException(ERROR_BRIDGE_UNAVAILABLE, "bridge audio channel is unavailable")
        val error = response.error
        if (error != null) {
            logger("bridge.audio_channel_failed code=" + error.code)
            throw AudioLaneException(error.code, error.message)
        }
        val json = parseObject(response.payload.toByteArray(Charsets.UTF_8))
            ?: throw AudioLaneException(ERROR_HANDSHAKE, "audio.channel response is malformed")
        if (json.boolean("opened") != true) {
            throw AudioLaneException(ERROR_HANDSHAKE, "bridge did not open the audio channel")
        }
        val socket = json.string("socket")?.takeIf { it.isNotEmpty() && it.length <= MAX_SOCKET_NAME_CHARS } 
            ?: throw AudioLaneException(ERROR_HANDSHAKE, "audio.channel socket name is invalid")
        val echoed = decodeFormat(json.get("format"))
            ?: throw AudioLaneException(ERROR_HANDSHAKE, "audio.channel format is invalid")
        if (echoed != format) {
            throw AudioLaneException(ERROR_FORMAT_MISMATCH, "bridge opened a different audio format")
        }
        return socket
    }

    /**
     * 连接 PCM lane 并完成 open/open_ok 握手。
     *
     * 每次尝试都是新连接（新的 frame 流），且握手重复同一 SessionId/格式；服务端绑定是
     * audio.channel 建立的，所以重试是“重新接入同一条绑定”，不是借用别的会话。
     */
    private suspend fun attach(id: SessionId, format: AudioFormatSpec, socketName: String): PcmAudioSession {
        val openFrame = encodeFrame(TYPE_CONTROL, encodeOpen(id, format))
            ?: throw AudioLaneException(ERROR_FRAME_TOO_LARGE, "PCM handshake exceeds the lane limit")
        var attempt = 0
        var lastCode: String = ERROR_AUDIO_PCM_NOT_OPEN
        while (true) {
            if (attempt > 0) {
                if (attempt >= attachRetryAttempts) {
                    throw AudioLaneException(lastCode, "audio worker did not open the session")
                }
                delay(attachRetryDelayMs)
            }
            attempt += 1
            val connection = pcm.open(socketName)
                ?: throw AudioLaneException(ERROR_BRIDGE_UNAVAILABLE, "PCM lane is unavailable")
            try {
                connection.write(openFrame)
                val frame = readFrame(connection)
                    ?: throw AudioLaneException(ERROR_HANDSHAKE, "PCM lane closed during the handshake")
                if (frame.type != TYPE_CONTROL) {
                    throw AudioLaneException(ERROR_HANDSHAKE, "PCM handshake must start with a control frame")
                }
                val control = parseObject(frame.body)
                    ?: throw AudioLaneException(ERROR_HANDSHAKE, "PCM handshake is not JSON")
                when (control.string("type")) {
                    "open_ok" -> {
                        val echoed = decodeFormat(control.get("format"))
                            ?: throw AudioLaneException(ERROR_HANDSHAKE, "PCM handshake format is invalid")
                        if (echoed != format) {
                            throw AudioLaneException(ERROR_FORMAT_MISMATCH, "audio worker opened a different format")
                        }
                        val lane = PcmAudioSession(id, format, connection)
                        lane.start()
                        return lane
                    }
                    "error" -> {
                        lastCode = control.string("code") ?: ERROR_HANDSHAKE
                        if (lastCode != ERROR_AUDIO_PCM_NOT_OPEN) {
                            throw AudioLaneException(lastCode, "PCM attach rejected")
                        }
                    }
                    else -> throw AudioLaneException(ERROR_HANDSHAKE, "PCM handshake was not open_ok")
                }
            } catch (cancelled: CancellationException) {
                runCatching { connection.close() }
                throw cancelled
            } catch (failure: AudioLaneException) {
                runCatching { connection.close() }
                throw failure
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                throw AudioLaneException(ERROR_LANE_FAILED, "PCM lane failed: " + failure.javaClass.simpleName)
            }
            runCatching { connection.close() }
        }
    }

    private suspend fun closeCurrentLane(code: String) {
        val lane = synchronized(laneLock) {
            val current = currentLane
            currentLane = null
            current
        } ?: return
        lane.closeFromClient(code)
    }

    private fun isCurrentLane(lane: PcmAudioSession): Boolean = synchronized(laneLock) { currentLane === lane }

    // ------------------------------------------------------------------ 控制请求

    private suspend fun controlRequest(method: String, session: SessionId?, payload: String): RpcResponse? {
        val request = RpcRequest(
            id = "app-" + requestCounter.incrementAndGet(),
            method = method,
            sessionId = session,
            payload = payload,
        )
        val response = try {
            withContext(ioDispatcher) { control.request(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        } ?: return null
        if (response.id != request.id || response.version != PROTOCOL_VERSION) {
            // 响应必须对应本请求；错配的负载绝不解析（可能是重放/串话）。
            protocolViolations += 1
            logger("bridge.response_mismatched")
            return null
        }
        return response
    }

    private fun decodeCommandResult(response: RpcResponse): CommandResult {
        val error = response.error
        if (error != null) return CommandResult.Failed(error.code, error.message)
        val json = parseObject(response.payload.toByteArray(Charsets.UTF_8))
            ?: return CommandResult.Failed(ERROR_INVALID_RESPONSE, "command result is not a JSON object")
        return when (json.string("outcome")) {
            "accepted" -> CommandResult.Accepted
            "rejected" -> {
                val reason = RejectReason.entries.firstOrNull { it.name == json.string("reason") }
                    ?: return CommandResult.Failed(ERROR_INVALID_RESPONSE, "unknown rejection reason")
                CommandResult.Rejected(reason)
            }
            "failed" -> CommandResult.Failed(
                json.string("code") ?: ERROR_COMMAND_FAILED,
                json.string("message") ?: "",
            )
            else -> CommandResult.Failed(ERROR_INVALID_RESPONSE, "command result is malformed")
        }
    }

    // ------------------------------------------------------------------ PCM 会话

    /**
     * 一条 PCM lane 上的 [AudioSession]。
     *
     * 下行只在读循环里投递（有界 Channel）；上行帧按 ≤16 窗口流水，并等齐每一帧的 ACK。
     * 任何非法帧/断链/超时都让会话以机器码失败，绝不静默继续。
     */
    private inner class PcmAudioSession(
        private val session: SessionId,
        private val format: AudioFormatSpec,
        private val connection: PcmLaneConnection,
    ) : AudioSession {

        private val downlinkChannel = Channel<PcmChunk>(DOWNLINK_BUFFER)
        override val downlink: Flow<PcmChunk> = downlinkChannel.receiveAsFlow()

        private val writeLock = Mutex()
        private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<PcmAck>>()
        private val nextSequence = AtomicInteger(0)
        private var readerJob: Job? = null

        @Volatile
        private var closed = false

        @Volatile
        private var failureCode: String? = null

        fun start() {
            readerJob = scope.launch(ioDispatcher) { readLoop() }
        }

        override suspend fun writeUplink(audio: PcmAudio) {
            if (!isCurrentLane(this)) {
                throw AudioLaneException(ERROR_AUDIO_LANE_SUPERSEDED, "audio lane belongs to a previous session")
            }
            if (closed) {
                throw AudioLaneException(failureCode ?: ERROR_AUDIO_LANE_STOPPED, "audio lane is closed")
            }
            if (audio.format != format) {
                throw AudioLaneException(ERROR_FORMAT_MISMATCH, "uplink format does not match the open lane")
            }
            val data = audio.data
            if (data.isEmpty()) return
            if (data.size % 2 != 0) {
                throw AudioLaneException(ERROR_FRAME_INVALID, "uplink PCM must be 16-bit aligned")
            }

            val inFlight = ArrayDeque<PendingAck>()
            val registered = ArrayList<Int>()
            var offset = 0
            try {
                while (offset < data.size || inFlight.isNotEmpty()) {
                    while (offset < data.size && inFlight.size < MAX_IN_FLIGHT_UPLINKS) {
                        val size = minOf(MAX_UPLINK_PCM_BYTES, data.size - offset)
                        val pcm = data.copyOfRange(offset, offset + size)
                        offset += size
                        val seq = nextSequence.getAndIncrement()
                        val deferred = CompletableDeferred<PcmAck>()
                        pendingAcks[seq] = deferred
                        registered += seq
                        inFlight.addLast(PendingAck(seq, deferred))
                        val body = encodeUplinkBody(seq, pcm)
                        try {
                            writeLock.withLock { writeFrame(connection, TYPE_UPLINK, body) }
                        } catch (failure: AudioLaneException) {
                            failLane(failure.code)
                            throw failure
                        }
                    }
                    val oldest = inFlight.removeFirst()
                    val ack = try {
                        withTimeout(ackTimeoutMs) { oldest.ack.await() }
                    } catch (timeout: TimeoutCancellationException) {
                        failLane(ERROR_ACK_TIMEOUT)
                        throw AudioLaneException(ERROR_ACK_TIMEOUT, "no playback ACK within " + ackTimeoutMs + " ms")
                    }
                    if (!ack.ok) {
                        val code = ack.code ?: ERROR_UPLINK_FAILED
                        failLane(code)
                        throw AudioLaneException(code, "audio worker rejected the uplink frame")
                    }
                }
            } finally {
                for (seq in registered) pendingAcks.remove(seq)
            }
        }

        override suspend fun stop() {
            if (closed) return
            if (isCurrentLane(this)) {
                // 主动结束只发 stop 控制帧；不解除麦克风隔离（隔离只属于监督端/工作进程）。
                runCatching { writeLock.withLock { writeFrame(connection, TYPE_CONTROL, ENCODE_STOP) } }
            }
            closeFromClient(ERROR_AUDIO_LANE_STOPPED)
        }

        suspend fun closeFromClient(code: String) {
            failLane(code)
            readerJob?.cancel()
            readerJob = null
        }

        private suspend fun readLoop() {
            var failure: String? = null
            try {
                while (!closed) {
                    val frame = readFrame(connection) ?: break
                    when (frame.type) {
                        TYPE_DOWNLINK -> {
                            if (closed) break
                            if (!downlinkChannel.trySend(PcmChunk(frame.body, format)).isSuccess) {
                                failure = ERROR_DOWNLINK_OVERFLOW
                                break
                            }
                        }
                        TYPE_ACK -> {
                            val ack = decodeAck(frame.body)
                            if (ack == null) {
                                failure = ERROR_ACK_INVALID
                                break
                            }
                            pendingAcks.remove(ack.seq)?.complete(ack)
                        }
                        TYPE_CONTROL -> {
                            val control = parseObject(frame.body)
                            when (control?.string("type")) {
                                "closed" -> break
                                "error" -> {
                                    failure = control.string("code") ?: ERROR_LANE_FAILED
                                    break
                                }
                                else -> {
                                    failure = ERROR_CONTROL_INVALID
                                    break
                                }
                            }
                        }
                        else -> {
                            failure = ERROR_FRAME_TYPE
                            break
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // 主动 stop/关闭：不是故障。
            } catch (protocol: AudioLaneException) {
                // 保留机器码（超长/截断/未知类型等），不降级成笼统失败。
                failure = protocol.code
            } catch (ignored: Throwable) {
                failure = ERROR_LANE_FAILED
            } finally {
                failLane(failure)
            }
        }

        /** 幂等收尾：关闭下行、唤醒所有等待 ACK 的上行、关闭连接。 */
        private suspend fun failLane(code: String?) {
            if (closed) return
            closed = true
            failureCode = code
            val downlinkFailure = code
                ?.takeIf { it != ERROR_AUDIO_LANE_STOPPED }
                ?.let { AudioLaneException(it, "audio lane ended") }
            downlinkChannel.close(downlinkFailure)
            val failure = AudioLaneException(code ?: ERROR_AUDIO_LANE_STOPPED, "audio uplink lane is closed")
            val snapshot = pendingAcks.values.toList()
            pendingAcks.clear()
            for (deferred in snapshot) deferred.completeExceptionally(failure)
            runCatching { connection.close() }
        }
    }

    private class PendingAck(val seq: Int, val ack: CompletableDeferred<PcmAck>)

    // ------------------------------------------------------------------ PCM 线格式

    private class LaneFrame(val type: Int, val body: ByteArray)

    private class PcmAck(val seq: Int, val ok: Boolean, val code: String?)

    /** 读一帧；EOF 返回 null，超长/截断/未知类型抛机器码异常（fail closed）。 */
    private suspend fun readFrame(connection: PcmLaneConnection): LaneFrame? {
        val header = connection.readExact(FRAME_HEADER_BYTES) ?: return null
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        // 长度在读取正文之前校验：绝不按声明长度预分配超大内存。
        if (length <= 1) throw AudioLaneException(ERROR_FRAME_LENGTH, "PCM frame length must be positive")
        if (length > MAX_BODY_BYTES) throw AudioLaneException(ERROR_FRAME_TOO_LARGE, "PCM frame exceeds the lane limit")
        val body = connection.readExact(length) ?: throw AudioLaneException(ERROR_FRAME_TRUNCATED, "PCM frame is truncated")
        val type = body[0].toInt() and 0xFF
        if (type !in TYPE_CONTROL..TYPE_ACK) throw AudioLaneException(ERROR_FRAME_TYPE, "unknown PCM frame type")
        return LaneFrame(type, body.copyOfRange(1, body.size))
    }

    private suspend fun writeFrame(connection: PcmLaneConnection, type: Int, body: ByteArray) {
        val frame = encodeFrame(type, body)
            ?: throw AudioLaneException(ERROR_FRAME_TOO_LARGE, "PCM frame exceeds the lane limit")
        try {
            connection.write(frame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw AudioLaneException(ERROR_WRITE_FAILED, "PCM lane write failed")
        }
    }

    private fun encodeFrame(type: Int, body: ByteArray): ByteArray? {
        if (type !in TYPE_CONTROL..TYPE_ACK) return null
        if (body.size + 1 > MAX_BODY_BYTES) return null
        val length = body.size + 1
        val frame = ByteArray(FRAME_HEADER_BYTES + length)
        frame[0] = (length ushr 24).toByte()
        frame[1] = (length ushr 16).toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = type.toByte()
        body.copyInto(frame, FRAME_HEADER_BYTES + 1)
        return frame
    }

    private fun encodeUplinkBody(seq: Int, pcm: ByteArray): ByteArray {
        val body = ByteArray(UPLINK_SEQ_BYTES + pcm.size)
        body[0] = (seq ushr 24).toByte()
        body[1] = (seq ushr 16).toByte()
        body[2] = (seq ushr 8).toByte()
        body[3] = seq.toByte()
        pcm.copyInto(body, UPLINK_SEQ_BYTES)
        return body
    }

    private fun encodeOpen(id: SessionId, format: AudioFormatSpec): ByteArray {
        val json = JsonObject()
        json.addProperty("type", "open")
        val session = JsonObject()
        session.addProperty("processEpoch", id.processEpoch)
        session.addProperty("callId", id.callId)
        json.add("sessionId", session)
        json.add("format", encodeFormat(format))
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeFormat(format: AudioFormatSpec): JsonObject = JsonObject().apply {
        addProperty("sampleRate", format.sampleRate)
        addProperty("channels", format.channels)
        addProperty("encoding", format.encoding.name)
    }

    /** 只接受协议固定格式：PCM16、单声道、8 kHz–192 kHz（与 bridge 的重采样器范围一致）。 */
    private fun decodeFormat(element: JsonElement?): AudioFormatSpec? {
        if (element == null || !element.isJsonObject) return null
        val json = element.asJsonObject
        if (json.keySet() != setOf("sampleRate", "channels", "encoding")) return null
        if (json.string("encoding") != PcmEncoding.PCM16.name) return null
        val sampleRate = json.int("sampleRate") ?: return null
        val channels = json.int("channels") ?: return null
        if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        if (channels != AudioFormatSpec.INTERNAL_CHANNELS) return null
        return AudioFormatSpec(sampleRate = sampleRate, channels = channels, encoding = PcmEncoding.PCM16)
    }

    private fun decodeAck(body: ByteArray): PcmAck? {
        val json = parseObject(body) ?: return null
        if (json.keySet() != ACK_KEYS && json.keySet() != ACK_WITH_CODE_KEYS) return null
        if (json.string("type") != "ack") return null
        val seq = json.int("seq") ?: return null
        if (seq < 0) return null
        val ok = json.boolean("ok") ?: return null
        return PcmAck(seq, ok, json.string("code"))
    }

    private fun parseObject(body: ByteArray): JsonObject? {
        if (body.isEmpty()) return null
        val element = runCatching { JsonParser.parseString(String(body, Charsets.UTF_8)) }.getOrNull()
            ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    companion object {
        /** 控制通道 abstract socket 名，与 bridge 的 ControlServer.DEFAULT_CONTROL_SOCKET 同步。 */
        const val CONTROL_SOCKET: String = "org.krvyft.agentcall.control"

        /** 服务端必须是 root（app_process 运行容器）；abstract 名字可能被抢绑，因此必须核验。 */
        const val ROOT_UID: Int = 0

        /** 心跳间隔：2000 ms，至少是 5000 ms 监督超时的一半余量。 */
        const val HEARTBEAT_INTERVAL_MS: Long = 2_000L

        /** 必须严格小于监督端的失联超时（[org.krvyft.agentcall.core.session.SessionLimits]）。 */
        const val HEARTBEAT_TIMEOUT_MS: Long = 5_000L

        /** 单帧上行 ACK 的等待上限；正常播放一帧 ≈1 s，留足余量。 */
        const val ACK_TIMEOUT_MS: Long = 30_000L

        /** 接入重试：只在工作进程回答 audio_pcm_not_open（ACTIVE 前）时有界重试。 */
        const val ATTACH_RETRY_ATTEMPTS: Int = 25
        const val ATTACH_RETRY_DELAY_MS: Long = 200L

        /** PCM lane 帧上限：4 字节长度 + 1 字节类型 + 正文，与 bridge 的 PcmLaneCodec 同值。 */
        const val FRAME_HEADER_BYTES: Int = 4
        const val MAX_FRAME_BYTES: Int = 32 * 1024
        const val MAX_BODY_BYTES: Int = MAX_FRAME_BYTES - FRAME_HEADER_BYTES
        const val UPLINK_SEQ_BYTES: Int = 4

        /**
         * 上行单帧 PCM 上限。帧长度字段覆盖“类型 + 正文”，正文是 4 字节序号 + PCM，
         * 所以 PCM ≤ MAX_BODY_BYTES - UPLINK_SEQ_BYTES - 1 = 32759，且必须是偶数（PCM16）
         * → 32758。这与 bridge 文档里的 32760 差 2 字节：32760 的帧长是 32765，
         * 会被 PcmLaneCodec.writeFrame 的 “body.size + 1 > MAX_BODY_BYTES” 拒绝。
         */
        const val MAX_UPLINK_PCM_BYTES: Int = ((MAX_BODY_BYTES - UPLINK_SEQ_BYTES - 1) / 2) * 2

        const val TYPE_CONTROL: Int = 0x01
        const val TYPE_DOWNLINK: Int = 0x02
        const val TYPE_UPLINK: Int = 0x03
        const val TYPE_ACK: Int = 0x04

        /** 上行在途帧上限，与 bridge 的 AudioPcmRelay.UPLINK_QUEUE_CAPACITY 同值。 */
        const val MAX_IN_FLIGHT_UPLINKS: Int = 16

        /** 下行有界缓冲：超出即失败（不静默丢音频、不无限占内存）。 */
        const val DOWNLINK_BUFFER: Int = 32

        const val MIN_SAMPLE_RATE: Int = 8_000
        const val MAX_SAMPLE_RATE: Int = 192_000

        const val MAX_SOCKET_NAME_CHARS: Int = 108

        const val HANGUP: String = "hangup"

        const val ERROR_BRIDGE_UNAVAILABLE: String = "bridge_unavailable"
        const val ERROR_INVALID_RESPONSE: String = "invalid_response"
        const val ERROR_COMMAND_FAILED: String = "command_failed"
        const val ERROR_HANDSHAKE: String = "audio_pcm_handshake_invalid"
        const val ERROR_AUDIO_PCM_NOT_OPEN: String = "audio_pcm_not_open"
        const val ERROR_FORMAT_MISMATCH: String = "audio_pcm_format_mismatch"
        const val ERROR_LANE_FAILED: String = "audio_pcm_lane_failed"
        const val ERROR_AUDIO_LANE_STOPPED: String = "audio_lane_stopped"
        const val ERROR_AUDIO_LANE_SUPERSEDED: String = "audio_lane_superseded"
        const val ERROR_FRAME_TOO_LARGE: String = "audio_pcm_frame_too_large"
        const val ERROR_FRAME_TRUNCATED: String = "audio_pcm_frame_truncated"
        const val ERROR_FRAME_TYPE: String = "audio_pcm_frame_type_invalid"
        const val ERROR_FRAME_LENGTH: String = "audio_pcm_frame_length_invalid"
        const val ERROR_FRAME_INVALID: String = "audio_pcm_frame_invalid"
        const val ERROR_CONTROL_INVALID: String = "audio_pcm_control_invalid"
        const val ERROR_DOWNLINK_OVERFLOW: String = "audio_pcm_downlink_overflow"
        const val ERROR_ACK_INVALID: String = "audio_pcm_ack_invalid"
        const val ERROR_ACK_TIMEOUT: String = "audio_pcm_ack_timeout"
        const val ERROR_UPLINK_FAILED: String = "audio_pcm_uplink_failed"
        const val ERROR_WRITE_FAILED: String = "audio_pcm_write_failed"

        private val ENCODE_STOP: ByteArray = "{\"type\":\"stop\"}".toByteArray(Charsets.UTF_8)

        private val ACK_KEYS = setOf("type", "seq", "ok")
        private val ACK_WITH_CODE_KEYS = setOf("type", "seq", "ok", "code")
    }
}

/**
 * 生产控制传输（薄实现）：每条请求一条 abstract [LocalSocket]，用 core 的 [RpcCodec] 成帧。
 *
 * 连接后先读内核凭据：服务端 uid 必须是 0（root app_process），否则不发送任何请求——
 * abstract 名字可能被普通应用抢绑，不能只凭名字信任服务端。客户端从不发送自己的 uid/
 * 包名/角色；身份由 bridge 从内核凭据核验。
 */
class LocalSocketControlTransport(
    private val socketName: String = BridgeClient.CONTROL_SOCKET,
    private val requestTimeoutMs: Int = REQUEST_TIMEOUT_MS,
) : ControlTransport {

    override suspend fun request(request: RpcRequest): RpcResponse? {
        val frame = try {
            RpcCodec.encodeRequestFrame(request)
        } catch (protocol: RpcProtocolException) {
            return null
        }
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            if (socket.peerCredentials?.uid != BridgeClient.ROOT_UID) return null
            socket.soTimeout = requestTimeoutMs
            socket.outputStream.write(frame)
            socket.outputStream.flush()
            when (val result = RpcCodec.readResponseFrame(socket.inputStream)) {
                is RpcCodec.ResponseResult.Rejected -> null
                is RpcCodec.ResponseResult.Accepted ->
                    if (result.response.id == request.id) result.response else null
            }
        } catch (io: IOException) {
            null
        } catch (failure: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    override suspend fun close() = Unit

    companion object {
        /** 控制请求超时：与 HOOK 侧一致，本地 socket 不该等更久。 */
        const val REQUEST_TIMEOUT_MS: Int = 2_000
    }
}

/**
 * 生产 PCM lane 传输（薄实现）：abstract [LocalSocket] + 阻塞 I/O，全部在注入的
 * [ioDispatcher] 上执行（[BridgeClient] 的 reader/writer 都在同一分派器上，绝不占用主线程）。
 */
class LocalSocketPcmLaneTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PcmLaneTransport {

    override suspend fun open(socketName: String): PcmLaneConnection? {
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            if (socket.peerCredentials?.uid != BridgeClient.ROOT_UID) {
                runCatching { socket.close() }
                null
            } else {
                socket.soTimeout = 0
                LocalSocketPcmLaneConnection(socket, ioDispatcher)
            }
        } catch (io: IOException) {
            runCatching { socket.close() }
            null
        } catch (failure: Exception) {
            runCatching { socket.close() }
            null
        }
    }
}

/** 阻塞读写的 LocalSocket 适配；close() 会打破正在阻塞的读取。 */
private class LocalSocketPcmLaneConnection(
    private val socket: LocalSocket,
    private val ioDispatcher: CoroutineDispatcher,
) : PcmLaneConnection {

    override suspend fun write(bytes: ByteArray) = withContext(ioDispatcher) {
        try {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        } catch (io: IOException) {
            throw AudioLaneException(BridgeClient.ERROR_WRITE_FAILED, "PCM lane write failed")
        }
    }

    override suspend fun readExact(length: Int): ByteArray? = withContext(ioDispatcher) {
        val buffer = ByteArray(length)
        var read = 0
        try {
            while (read < length) {
                val count = socket.inputStream.read(buffer, read, length - read)
                if (count < 0) return@withContext null
                read += count
            }
            buffer
        } catch (io: IOException) {
            null
        }
    }

    override suspend fun close() {
        runCatching { socket.close() }
    }
}

// --------------------------------------------------------------------------
// Json 小工具（只在本文件使用；严格类型，缺失/类型不符返回 null）
// --------------------------------------------------------------------------

private fun JsonObject.string(name: String): String? = get(name)?.asStringOrNull()

private fun JsonObject.int(name: String): Int? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
    val value = element.asDouble
    if (!value.isFinite() || value != Math.floor(value)) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}

private fun JsonObject.boolean(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
    return element.asBoolean
}

private fun JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

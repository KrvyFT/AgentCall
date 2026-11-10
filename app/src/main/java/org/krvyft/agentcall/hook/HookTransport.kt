package org.krvyft.agentcall.hook

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.core.protocol.RpcCodec
import org.krvyft.agentcall.core.protocol.RpcProtocolException

/**
 * HOOK 角色的本地桥接客户端（spec §6.4）。
 *
 * 只做五件事：
 * 1. 把注册表的快照（附动作来源/交还原因）转发给特权运行容器（`hook.snapshot`）；
 * 2. 把每条命令的实际结果回报给特权运行容器（`call.command.result`），
 *    这样 Task 7 的 CallControl.send 才能拿到真实的 [CommandResult]；
 * 3. 按固定间隔发送 `hook.heartbeat`；
 * 4. 从响应负载里接受**受限通话命令**（answer/hangup），交给注册表再次裁决；
 * 5. 有界队列 + 失败计数，任何回调路径都不阻塞电话 UI。
 *
 * 明确不做：不读取配置/API Key（固定出站方法表里没有 config.*）、不做 ASR/TTS、
 * 不发网络请求、不碰音频、不在 Xposed 回调里建立 socket。连接失败只计数并在下一次
 * 心跳/快照时重试，不抛异常到 Telecom 线程。
 *
 * 信任方向（spec §6.4）：bridge 核验 hook 的内核凭据，hook 也必须核验服务端。
 * 连接后先读 `LocalSocket.getPeerCredentials()`，只有 uid 0（root 运行容器）才发送
 * 请求；任何应用都能抢先绑定 abstract 名字，因此这一步不能省略。
 *
 * 协议说明：Task 3 的 [org.krvyft.agentcall.bridge.ipc.ControlServer] 是“一请求一响应”，
 * 服务端不会主动推送。因此命令随 `hook.snapshot`/`hook.heartbeat` 的响应负载下发，
 * 负载是逐行文本 `answer <callId>` / `hangup <callId>`（见 [HookWireFormat]）。
 * 该负载格式是 HOOK 角色的客户端契约，Task 7 在特权容器实现会话路由时必须遵守。
 *
 * @param processEpoch 与 [CallRegistry.processEpoch] 相同的本次进程代次。
 * @param liveSessions 当前存活会话；只对其中存在的 callId 接受命令。
 * @param onCommand 命令接收器（生产实现转给 [CallRegistry.command]），在传输线程调用。
 */
class HookTransport(
    private val processEpoch: String,
    private val liveSessions: () -> List<SessionId>,
    private val onCommand: (SessionId, CallCommand) -> Unit,
    private val socketName: String = DEFAULT_CONTROL_SOCKET,
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val requestTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    private val queueCapacity: Int = QUEUE_CAPACITY,
    private val clock: () -> Long = { System.nanoTime() },
) {

    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(heartbeatIntervalMs > 0) { "heartbeatIntervalMs must be positive" }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be positive" }
    }

    private sealed interface Outbound {
        data class Snapshot(
            val snapshot: CallSnapshot,
            val source: ActionSource,
            val handbackReason: String?,
        ) : Outbound

        data class CommandResult(val attempt: CommandAttempt) : Outbound
    }

    private val queue = ArrayBlockingQueue<Outbound>(queueCapacity)
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val droppedSnapshots = AtomicLong(0)
    private val droppedCommandResults = AtomicLong(0)
    private val transportFailures = AtomicLong(0)
    private val protocolViolations = AtomicLong(0)
    private val untrustedServers = AtomicLong(0)
    private val rejectedCommands = AtomicLong(0)
    private val requestCounter = AtomicLong(0)

    @Volatile
    private var worker: Thread? = null

    /** 因传输线程未启动或队列饱和被丢弃的**快照**数（有界失败，不阻塞调用方）。 */
    val droppedSnapshotCount: Long get() = droppedSnapshots.get()

    /**
     * 因传输线程未启动或队列饱和被丢弃的**命令结果**数（M5）。
     *
     * 与快照分开计数：命令结果丢失会让 CallControl.send 拿不到真实裁决，混进快照计数
     * 会掩盖这个更严重的故障。
     */
    val droppedCommandResultCount: Long get() = droppedCommandResults.get()

    /** 连接/读写失败次数。 */
    val transportFailureCount: Long get() = transportFailures.get()

    /** 非法响应、非法命令负载、非白名单方法等协议违规次数。 */
    val protocolViolationCount: Long get() = protocolViolations.get()

    /** 服务端内核凭据不是 root（可能被其它应用抢绑）而拒绝发送的次数。 */
    val untrustedServerCount: Long get() = untrustedServers.get()

    /** 收到的、但会话不存在或格式非法的命令数。 */
    val rejectedCommandCount: Long get() = rejectedCommands.get()

    /** 启动唯一的传输线程；重复调用无效。由 HookEntry 在安装 Hook 后调用一次。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        running.set(true)
        val thread = Thread(::loop, "agentcall-hook-transport").apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    /** 停止传输线程。回调路径不等待，避免阻塞 Telecom。 */
    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    /**
     * 快照转发入口：只做非阻塞 offer。队列满时丢弃并计数，绝不阻塞 Xposed 回调。
     *
     * @param source 触发本次快照的动作来源，供控制器区分模块动作与人工/系统动作。
     * @param handbackReason 非 null 表示这是一次交还给人工的通知（机器可读原因）。
     */
    fun enqueueSnapshot(
        snapshot: CallSnapshot,
        source: ActionSource = ActionSource.UNKNOWN,
        handbackReason: String? = null,
    ) {
        if (!running.get()) {
            // M5：启动前/停止后的入队不是“成功”，必须计入丢弃，否则诊断看起来是零丢失。
            droppedSnapshots.incrementAndGet()
            return
        }
        if (!queue.offer(Outbound.Snapshot(snapshot, source, handbackReason))) {
            droppedSnapshots.incrementAndGet()
        }
    }

    /** 命令结果转发入口：同样只做非阻塞 offer。 */
    fun enqueueCommandResult(attempt: CommandAttempt) {
        if (!running.get()) {
            droppedCommandResults.incrementAndGet()
            return
        }
        if (!queue.offer(Outbound.CommandResult(attempt))) {
            // M5：命令结果溢出绝不能记进快照计数。
            droppedCommandResults.incrementAndGet()
        }
    }

    // ------------------------------------------------------------------ 传输线程

    private fun loop() {
        val nextHeartbeat = HashMap<String, Long>()
        while (running.get()) {
            // 心跳先于出站队列：桥接卡住时一次 snapshot 请求最长会占用 REQUEST_TIMEOUT_MS，
            // 若把心跳排在它后面，连续卡顿会把“下一次心跳”推过 5000 ms 失联窗口。
            // 到期时间按发送**开始**时刻计算，请求耗时不会把节奏往后顺延（M5）。
            sendDueHeartbeats(nextHeartbeat)

            val outbound = try {
                queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                if (!running.get()) break
                continue
            }
            when (outbound) {
                is Outbound.Snapshot -> sendSnapshot(outbound)
                is Outbound.CommandResult -> sendCommandResult(outbound.attempt)
                null -> Unit
            }
        }
    }

    /** 对每个仍存活的本次进程代次会话发送到期心跳；到期时间以发送开始时刻为准。 */
    private fun sendDueHeartbeats(nextHeartbeat: HashMap<String, Long>) {
        val startedAt = clock()
        val sessions = try {
            liveSessions()
        } catch (failure: Throwable) {
            emptyList()
        }
        val liveCallIds = HashSet<String>()
        for (session in sessions) {
            // 只对本次进程代次的会话发心跳；旧代次绝不复活。
            if (session.processEpoch != processEpoch) continue
            liveCallIds += session.callId
            val due = nextHeartbeat[session.callId]
            if (due == null || startedAt >= due) {
                // 先记下一次到期再发：本次请求即使阻塞满超时，下一轮也会立即补发而不是再等一轮。
                nextHeartbeat[session.callId] = startedAt + heartbeatIntervalMs * NANOS_PER_MILLI
                sendHeartbeat(session)
            }
        }
        nextHeartbeat.keys.retainAll(liveCallIds)
    }

    private fun sendSnapshot(outbound: Outbound.Snapshot) {
        val response = request(
            method = RolePolicy.METHOD_HOOK_SNAPSHOT,
            session = outbound.snapshot.id,
            payload = HookWireFormat.encodeSnapshot(outbound.snapshot, outbound.source, outbound.handbackReason),
        )
        acceptCommands(response)
    }

    private fun sendCommandResult(attempt: CommandAttempt) {
        val response = request(
            method = RolePolicy.METHOD_CALL_COMMAND_RESULT,
            session = attempt.session,
            payload = HookWireFormat.encodeCommandResult(
                session = attempt.session,
                command = attempt.command,
                source = attempt.source,
                result = attempt.result,
                note = attempt.note,
            ),
        )
        // 服务端可以在任意响应里捎带命令。
        acceptCommands(response)
    }

    private fun sendHeartbeat(session: SessionId) {
        val response = request(method = RolePolicy.METHOD_HOOK_HEARTBEAT, session = session, payload = "")
        acceptCommands(response)
    }

    private fun request(method: String, session: SessionId, payload: String): RpcResponse? {
        if (!HookWireFormat.isAllowedOutboundMethod(method)) {
            // 固定出站方法表：配置/密钥/音频/会话方法在客户端就被拒绝，永不发出。
            protocolViolations.incrementAndGet()
            return null
        }
        val request = RpcRequest(
            id = "hook-" + requestCounter.incrementAndGet(),
            method = method,
            sessionId = session,
            payload = payload,
        )
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            // 先核验服务端，再发送任何快照/命令：abstract 名字可以被任意应用抢先绑定。
            if (!HookServerTrust.isTrustedUid(peerUidOf(socket))) {
                untrustedServers.incrementAndGet()
                return null
            }
            socket.soTimeout = requestTimeoutMs
            socket.outputStream.write(RpcCodec.encodeRequestFrame(request))
            socket.outputStream.flush()
            when (val result = RpcCodec.readResponseFrame(socket.inputStream)) {
                is RpcCodec.ResponseResult.Rejected -> {
                    protocolViolations.incrementAndGet()
                    null
                }
                is RpcCodec.ResponseResult.Accepted -> {
                    if (result.response.id != request.id) {
                        // 响应必须对应本请求；错配的负载绝不解析。
                        protocolViolations.incrementAndGet()
                        null
                    } else {
                        result.response
                    }
                }
            }
        } catch (io: IOException) {
            transportFailures.incrementAndGet()
            null
        } catch (protocol: RpcProtocolException) {
            protocolViolations.incrementAndGet()
            null
        } catch (failure: Exception) {
            // 传输线程不能因单个异常退出；未知异常按传输失败计数。
            transportFailures.incrementAndGet()
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun peerUidOf(socket: LocalSocket): Int? = try {
        socket.peerCredentials?.uid
    } catch (failure: Throwable) {
        // 凭据不可读等价于不可信；不发送任何请求。
        null
    }

    private fun acceptCommands(response: RpcResponse?) {
        if (response == null || response.error != null) return
        val parsed = HookWireFormat.parseCommands(response.payload)
        if (parsed.invalidLines > 0) protocolViolations.addAndGet(parsed.invalidLines.toLong())
        for (command in parsed.commands) {
            val session = SessionId(processEpoch, command.callId)
            val live = try {
                liveSessions().any { it == session }
            } catch (failure: Throwable) {
                false
            }
            if (!live) {
                rejectedCommands.incrementAndGet()
                continue
            }
            try {
                onCommand(session, command.verb.callCommand)
            } catch (failure: Throwable) {
                rejectedCommands.incrementAndGet()
            }
        }
    }

    companion object {
        /**
         * Task 3 的控制通道 abstract socket 名。app 不依赖 :bridge（依赖方向 app -> core），
         * 因此这里保留同名字面量；改动必须与
         * `org.krvyft.agentcall.bridge.ipc.ControlServer.DEFAULT_CONTROL_SOCKET` 同步。
         */
        const val DEFAULT_CONTROL_SOCKET: String = "org.krvyft.agentcall.control"

        /**
         * 心跳间隔。Task 2 的心跳超时是 5000 ms（SessionLimits），这里取一半留出
         * 至少一次重试余量；间隔必须在超时内，否则对端会把 Hook 判定为失联。
         */
        const val HEARTBEAT_INTERVAL_MS: Long = 2_000

        const val REQUEST_TIMEOUT_MS: Int = 2_000
        const val QUEUE_CAPACITY: Int = 16

        private const val POLL_INTERVAL_MS: Long = 200L
        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}

/**
 * 服务端信任判定：运行容器以 root app_process 启动，因此控制 socket 的服务端 uid 必须是 0。
 *
 * 只做纯判定，便于 JVM 测试；凭据读取在 [HookTransport.peerUidOf]。
 */
internal object HookServerTrust {

    /** 合法服务端 uid（root）。 */
    const val TRUSTED_UID: Int = 0

    fun isTrustedUid(uid: Int?): Boolean = uid == TRUSTED_UID
}

/**
 * HOOK 角色的线格式（纯 Kotlin，可 JVM 测试）。
 *
 * 出站方法白名单只有快照/心跳/命令结果：HOOK 角色在 Task 3 的固定表里也没有配置/密钥
 * 方法，客户端再加一道白名单，保证任何代码路径都不可能通过本传输读取 API Key。
 *
 * 命令负载（服务端 → Hook）是严格的行文本：
 * ```
 * answer <callId>
 * hangup <callId>
 * ```
 * 大小写敏感、单空格分隔、最多 8 条、总长 4096 字符；未知动词、非法 callId、
 * 多余令牌都只计非法行，绝不执行。快照负载是固定字段的 JSON 对象（转义完整），
 * 由 Task 7 在特权容器用 Gson 解析；`source` 与 `handbackReason` 让控制器区分
 * 模块动作、人工动作与来源不明的路由交还。
 */
internal object HookWireFormat {

    const val MAX_COMMAND_PAYLOAD_CHARS: Int = 4096
    const val MAX_COMMANDS: Int = 8
    private const val MAX_CALL_ID_CHARS: Int = 64

    private val ALLOWED_OUTBOUND_METHODS: Set<String> = setOf(
        RolePolicy.METHOD_HOOK_SNAPSHOT,
        RolePolicy.METHOD_HOOK_HEARTBEAT,
        RolePolicy.METHOD_CALL_COMMAND_RESULT,
    )

    fun isAllowedOutboundMethod(method: String): Boolean = method in ALLOWED_OUTBOUND_METHODS

    enum class Verb(val wireName: String, val callCommand: CallCommand) {
        ANSWER("answer", CallCommand.Answer),
        HANGUP("hangup", CallCommand.Hangup),
    }

    data class Command(val callId: String, val verb: Verb)

    data class ParsedCommands(val commands: List<Command>, val invalidLines: Int)

    fun parseCommands(payload: String): ParsedCommands {
        if (payload.isEmpty() || payload.length > MAX_COMMAND_PAYLOAD_CHARS) {
            return ParsedCommands(emptyList(), if (payload.isEmpty()) 0 else 1)
        }
        val commands = ArrayList<Command>(MAX_COMMANDS)
        var invalid = 0
        for (rawLine in payload.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (commands.size >= MAX_COMMANDS) {
                invalid += 1
                continue
            }
            val parts = line.split(' ')
            if (parts.size != 2) {
                invalid += 1
                continue
            }
            val verb = Verb.entries.firstOrNull { it.wireName == parts[0] }
            if (verb == null || !isValidCallId(parts[1])) {
                invalid += 1
                continue
            }
            commands += Command(callId = parts[1], verb = verb)
        }
        return ParsedCommands(commands = commands, invalidLines = invalid)
    }

    /**
     * 固定字段的 JSON 快照负载；不使用电话号码作为任何标识字段的来源。
     *
     * @param source 触发本次快照的动作来源，控制器据此区分模块动作与人工/系统动作。
     * @param handbackReason 非 null 表示本次快照是一次“交还人工”通知。
     */
    fun encodeSnapshot(
        snapshot: CallSnapshot,
        source: ActionSource = ActionSource.UNKNOWN,
        handbackReason: String? = null,
    ): String {
        val json = StringBuilder(256)
        json.append('{')
        appendStringField(json, "processEpoch", snapshot.id.processEpoch)
        appendStringField(json, "callId", snapshot.id.callId)
        appendStringField(json, "number", snapshot.number)
        appendStringField(json, "state", snapshot.state.name)
        appendStringField(json, "direction", snapshot.direction.name)
        appendStringField(json, "presentation", snapshot.presentation.name)
        appendStringField(json, "phoneAccount", snapshot.phoneAccount)
        json.append("\"isEmergency\":").append(snapshot.isEmergency).append(',')
        json.append("\"isConference\":").append(snapshot.isConference).append(',')
        json.append("\"activeCallCount\":").append(snapshot.activeCallCount).append(',')
        appendStringField(json, "source", source.name)
        appendStringField(json, "handbackReason", handbackReason)
        return finish(json)
    }

    /** 命令结果负载：让特权容器/Task 7 还原真实 [CommandResult]，而不是“已发出”。 */
    fun encodeCommandResult(
        session: SessionId,
        command: CallCommand,
        source: ActionSource,
        result: CommandResult,
        note: String = "",
    ): String {
        val json = StringBuilder(256)
        json.append('{')
        appendStringField(json, "processEpoch", session.processEpoch)
        appendStringField(json, "callId", session.callId)
        appendStringField(
            json,
            "command",
            when (command) {
                CallCommand.Answer -> "Answer"
                CallCommand.Hangup -> "Hangup"
            },
        )
        appendStringField(json, "source", source.name)
        when (result) {
            is CommandResult.Accepted -> {
                appendStringField(json, "outcome", "accepted")
                appendStringField(json, "reason", null)
                appendStringField(json, "code", null)
                appendStringField(json, "message", null)
            }
            is CommandResult.Rejected -> {
                appendStringField(json, "outcome", "rejected")
                appendStringField(json, "reason", result.reason.name)
                appendStringField(json, "code", null)
                appendStringField(json, "message", null)
            }
            is CommandResult.Failed -> {
                appendStringField(json, "outcome", "failed")
                appendStringField(json, "reason", null)
                appendStringField(json, "code", result.code)
                appendStringField(json, "message", result.message)
            }
        }
        appendStringField(json, "note", note)
        return finish(json)
    }

    private fun finish(json: StringBuilder): String {
        // appendStringField 总是留下一个尾部逗号；去掉它再闭合对象。
        if (json.length > 0 && json[json.length - 1] == ',') {
            json.setLength(json.length - 1)
        }
        json.append('}')
        return json.toString()
    }

    private fun isValidCallId(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_CALL_ID_CHARS &&
            value.none { it.isISOControl() || it.isWhitespace() }

    private fun appendStringField(json: StringBuilder, name: String, value: String?) {
        json.append('"').append(name).append("\":")
        if (value == null) {
            json.append("null")
        } else {
            appendQuoted(json, value)
        }
        json.append(',')
    }

    private fun appendQuoted(json: StringBuilder, value: String) {
        json.append('"')
        for (character in value) {
            when {
                character == '"' -> json.append("\\\"")
                character == '\\' -> json.append("\\\\")
                character == '\n' -> json.append("\\n")
                character == '\r' -> json.append("\\r")
                character == '\t' -> json.append("\\t")
                character.isISOControl() -> json.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                else -> json.append(character)
            }
        }
        json.append('"')
    }
}

package org.krvyft.agentcall.bridge.supervision

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.CallControl

/**
 * Hook 快照（hook.snapshot）固定字段 JSON 的消费侧（spec §6.4，任务 4 的线格式）。
 *
 * 强制会话字段与请求会话一致：别人的快照绝不能推动本监督端的状态机，也不能变成
 * APP 事件流里的条目。
 *
 * [Decoded.state] 与 [Decoded.handbackReason] 是监督端状态机的判定字段（严格解析）；
 * 其余字段是给 APP 事件流（Task 7C）的**观测值**：缺失或类型不符就是 null，绝不补
 * false/0/1 之类的默认值（spec §8“缺一项就保留原响铃”）。
 */
object HookSnapshotDecoder {

    /**
     * 一次已解码的快照。
     *
     * @property source HOOK 声明的动作来源（ActionSource：MODULE/USER/UNKNOWN）。桥接不
     *   依赖 app 模块的类型，因此只接受机器码形状的短字符串并原样透传。
     */
    data class Decoded(
        val state: CallState,
        val handbackReason: String?,
        val source: String? = null,
        val presentation: Presentation? = null,
        val direction: CallDirection? = null,
        val phoneAccount: String? = null,
        val isEmergency: Boolean? = null,
        val isConference: Boolean? = null,
        val activeCallCount: Int? = null,
        val number: String? = null,
    )

    private const val MAX_PAYLOAD_CHARS = 4096
    private const val MAX_SOURCE_CHARS = 32

    fun decode(expected: SessionId, payload: String): Decoded? {
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD_CHARS) return null
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (string(json, "processEpoch") != expected.processEpoch) return null
        if (string(json, "callId") != expected.callId) return null
        val state = CallState.entries.firstOrNull { it.name == string(json, "state") } ?: return null
        return Decoded(
            state = state,
            handbackReason = optionalString(json, "handbackReason"),
            source = decodeSource(json),
            presentation = Presentation.entries.firstOrNull { it.name == optionalString(json, "presentation") },
            direction = CallDirection.entries.firstOrNull { it.name == optionalString(json, "direction") },
            phoneAccount = optionalString(json, "phoneAccount"),
            isEmergency = optionalBoolean(json, "isEmergency"),
            isConference = optionalBoolean(json, "isConference"),
            activeCallCount = optionalPositiveInt(json, "activeCallCount"),
            number = optionalString(json, "number"),
        )
    }

    private fun string(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun optionalString(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    /** 动作来源只接受短机器码（不含控制字符/引号），未知值一律 null（不猜）。 */
    private fun decodeSource(json: JsonObject): String? {
        val value = optionalString(json, "source") ?: return null
        if (value.isEmpty() || value.length > MAX_SOURCE_CHARS) return null
        if (value.any { it.isISOControl() || it.isWhitespace() || it == '"' }) return null
        return value
    }

    private fun optionalBoolean(json: JsonObject, name: String): Boolean? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
        return element.asBoolean
    }

    /**
     * 活动通话数：模型契约要求至少 1（0 是异常快照）。非整数/小于 1 一律 null，
     * 绝不把异常值收敛成“1 路”从而放行第二路电话。
     */
    private fun optionalPositiveInt(json: JsonObject, name: String): Int? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val asDouble = element.asDouble
        val asInt = element.asInt
        if (!asDouble.isFinite() || asDouble != asInt.toDouble()) return null
        if (asInt < 1) return null
        return asInt
    }
}

/** Hook 命令结果（call.command.result）固定字段 JSON 的消费侧；只接受与请求会话一致的结果。 */
object HookCommandResultDecoder {

    data class Decoded(val command: CallCommand, val result: CommandResult)

    private const val MAX_PAYLOAD_CHARS = 2048

    fun decode(expected: SessionId, payload: String): Decoded? {
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD_CHARS) return null
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (string(json, "processEpoch") != expected.processEpoch) return null
        if (string(json, "callId") != expected.callId) return null
        val command = when (string(json, "command")) {
            "Answer" -> CallCommand.Answer
            "Hangup" -> CallCommand.Hangup
            else -> return null
        }
        val reason = optionalString(json, "reason")
        val code = optionalString(json, "code")
        val message = optionalString(json, "message")
        val result = when (string(json, "outcome")) {
            "accepted" -> {
                if (reason != null || code != null || message != null) return null
                CommandResult.Accepted
            }
            "rejected" -> {
                if (code != null || message != null) return null
                val reject = RejectReason.entries.firstOrNull { it.name == reason } ?: return null
                CommandResult.Rejected(reject)
            }
            "failed" -> {
                if (reason != null || code == null || message == null) return null
                if (code.isEmpty() || message.isEmpty()) return null
                CommandResult.Failed(code, message)
            }
            else -> return null
        }
        return Decoded(command, result)
    }

    private fun string(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun optionalString(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }
}

/**
 * 监督端的 Hook 命令结果/状态收件箱（有界），也是**唯一**的命令结果通路。
 *
 * 与任务 3 的 HookCommandRouter 分工：路由器只负责排队/下发与解析，结果一律写进本类。
 * 这样不会出现"路由器缓冲区 + 收件箱"两条互相抢结果、且其中一条没人消费的路。
 *
 * 三个正确性约束（评审 I2/I3）：
 * - **等待者互斥**：每个 (session, command) 同一时刻只有一个权威等待者。监督端
 *   （`session.request` 的 Answer、故障终止的 Hangup）优先于 APP：它可以抢占 APP 的
 *   等待者，被抢占方立刻得到 [AwaitOutcome.Superseded]（映射为 answer_in_flight /
 *   command_in_flight），
 *   不会出现"APP 抢走监督端的 Answer 结果 → 监督端误判超时 → 恢复麦克风"的路径。
 * - **结果带序号**：一次等待只接受"开始等待之后"记录的结果；开始等待时还会清掉该
 *   (session, command) 上残留的旧结果。上一次入队超时后迟到的结果不可能被下一次
 *   命令当成自己的成功。
 * - **prepareAnswer 窗口**：监督端准备接听期间 APP 不得发 Answer（[beginSupervisorAnswer]）。
 *
 * 残余限制：HOOK 的线格式里没有命令代次，若上一条命令仍在 HOOK 手里、其结果在本轮
 * 等待开始之后才回来，仍可能被本轮收下。这只能靠 HOOK 侧代次字段解决（不在本任务范围）。
 */
class HookCallInbox(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** 一次命令结果等待的凭据；由 [beginAppWait]/[beginSupervisorWait] 创建，必须 [endWait] 归还。 */
    class Wait internal constructor(
        internal val session: SessionId,
        internal val command: CallCommand,
        /** 本次等待开始时已记录的结果序号上界。 */
        internal val sinceSeq: Long,
        /** 监督端等待者优先；APP 等待者会被监督端抢占。 */
        internal val supervisor: Boolean,
    )

    /** APP 的等待能否开始。 */
    sealed interface AppWait {
        data class Started(val wait: Wait) : AppWait

        /** 监督端正在准备/执行同一条命令：APP 不得抢占它的结果。 */
        data object SupervisorInFlight : AppWait

        /** 同一 (session, command) 已有另一个 APP 等待者（或等待表已满）。 */
        data object WaiterInFlight : AppWait
    }

    /** 一次等待的结局；[Superseded] 必须按"结果不归我"立即失败，不能当成超时继续等。 */
    sealed interface AwaitOutcome {
        data class Delivered(val result: CommandResult) : AwaitOutcome

        data object TimedOut : AwaitOutcome

        data object Superseded : AwaitOutcome
    }

    private data class PendingResult(
        val session: SessionId,
        val command: CallCommand,
        val seq: Long,
        val result: CommandResult,
    )

    private data class Key(val session: SessionId, val command: CallCommand)

    private val lock = Any()
    private val states = LinkedHashMap<SessionId, CallState>()
    private val results = ArrayDeque<PendingResult>()
    private var resultSequence: Long = 0L
    private val claims = HashMap<Key, Wait>()

    /**
     * 正在准备接听的会话 → 并发请求计数。用计数而不是集合：两个并发 session.request
     * 中较快的那个返回后，不能把较慢那个的窗口一起清掉（否则 APP 的 answer 又会挤进来）。
     */
    private val supervisorAnswers = LinkedHashMap<SessionId, Int>()

    fun recordState(session: SessionId, state: CallState?) = synchronized(lock) {
        states.remove(session)
        if (state != null) {
            states[session] = state
            while (states.size > maxEntries) {
                val eldest = states.keys.first()
                states.remove(eldest)
            }
        }
    }

    fun state(session: SessionId): CallState? = synchronized(lock) { states[session] }

    fun recordResult(session: SessionId, command: CallCommand, result: CommandResult) = synchronized(lock) {
        resultSequence += 1
        results.addLast(PendingResult(session, command, resultSequence, result))
        while (results.size > maxEntries) {
            results.removeFirst()
        }
    }

    /** 当前缓冲的结果条数（诊断/测试；生产只由等待者取走）。 */
    fun pendingResultCount(): Int = synchronized(lock) { results.size }

    // ------------------------------------------------------------------ prepareAnswer 窗口

    /** 标记监督端开始为 [session] 准备接听：期间 APP 不得再发 Answer。 */
    fun beginSupervisorAnswer(session: SessionId) = synchronized(lock) {
        val count = supervisorAnswers.remove(session) ?: 0
        supervisorAnswers[session] = count + 1
    }

    fun endSupervisorAnswer(session: SessionId) {
        synchronized(lock) {
            val count = supervisorAnswers[session] ?: return
            if (count <= 1) {
                supervisorAnswers.remove(session)
            } else {
                supervisorAnswers[session] = count - 1
            }
        }
    }

    fun supervisorAnswerInFlight(session: SessionId): Boolean = synchronized(lock) {
        supervisorAnswers.containsKey(session)
    }

    // ------------------------------------------------------------------ 等待

    /** APP 请求等待一条命令结果；被拒绝时不建立任何等待状态。 */
    fun beginAppWait(session: SessionId, command: CallCommand): AppWait = synchronized(lock) {
        val key = Key(session, command)
        if (command == CallCommand.Answer && supervisorAnswers.containsKey(session)) {
            return AppWait.SupervisorInFlight
        }
        val existing = claims[key]
        if (existing != null) {
            return if (existing.supervisor) AppWait.SupervisorInFlight else AppWait.WaiterInFlight
        }
        if (claims.size >= maxEntries) return AppWait.WaiterInFlight
        drainStaleLocked(session, command)
        val wait = Wait(session, command, resultSequence, supervisor = false)
        claims[key] = wait
        AppWait.Started(wait)
    }

    /**
     * 监督端请求等待：**抢占**同一 (session, command) 的 APP 等待者（它会在下一次轮询
     * 得到 [AwaitOutcome.Superseded]）。必须在 enqueue 之前调用，序号上界才正确。
     */
    fun beginSupervisorWait(session: SessionId, command: CallCommand): Wait = synchronized(lock) {
        drainStaleLocked(session, command)
        val wait = Wait(session, command, resultSequence, supervisor = true)
        claims[Key(session, command)] = wait
        wait
    }

    /** 归还等待凭据；只有仍是当前权威等待者时才移除。 */
    fun endWait(wait: Wait) = synchronized(lock) {
        val key = Key(wait.session, wait.command)
        if (claims[key] === wait) claims.remove(key)
    }

    /** 等待一条属于本次入队的结果；超时/被抢占都返回对应的显式结局。 */
    suspend fun awaitResult(wait: Wait, timeoutMs: Long): AwaitOutcome {
        val key = Key(wait.session, wait.command)
        val deadlineNanos = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (true) {
            val delivered = synchronized(lock) {
                if (claims[key] !== wait) return AwaitOutcome.Superseded
                results.removeAll {
                    it.session == key.session && it.command == key.command && it.seq <= wait.sinceSeq
                }
                val index = results.indexOfFirst {
                    it.session == key.session && it.command == key.command && it.seq > wait.sinceSeq
                }
                if (index < 0) null else results.removeAt(index).result
            }
            if (delivered != null) return AwaitOutcome.Delivered(delivered)
            if (System.nanoTime() >= deadlineNanos) return AwaitOutcome.TimedOut
            delay(RESULT_POLL_INTERVAL_MS)
        }
    }

    /**
     * 清掉该 (session, command) 上残留的结果：等待凭据建立时，缓冲里的同类结果只可能
     * 属于上一次入队（本 (session, command) 没有其它权威等待者），不可能属于本次。
     */
    private fun drainStaleLocked(session: SessionId, command: CallCommand) {
        results.removeAll { it.session == session && it.command == command }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 64
        private const val NANOS_PER_MILLI: Long = 1_000_000L
        private const val RESULT_POLL_INTERVAL_MS: Long = 2L
    }
}

/**
 * 监督端的 [CallControl]：把受限命令排入 Hook 命令路由，并等待 Hook 回报的真实结果。
 *
 * - 排队失败（会话非法/队列饱和）→ 有界失败；
 * - 超时没有结果 → [ERROR_TIMEOUT]（不明确，由监督端 fail closed 处理，绝不假定成功）。
 */
class HookCommandCallControl(
    private val enqueue: (SessionId, CallCommand) -> Boolean,
    private val inbox: HookCallInbox,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : CallControl {

    override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
        // 先建立等待凭据（监督端优先），再入队：结果序号上界才早于本次入队。
        val wait = inbox.beginSupervisorWait(id, command)
        try {
            if (!enqueue(id, command)) {
                return CommandResult.Failed(ERROR_QUEUE, "hook command queue rejected the command")
            }
            return when (val outcome = inbox.awaitResult(wait, timeoutMs)) {
                is HookCallInbox.AwaitOutcome.Delivered -> outcome.result
                HookCallInbox.AwaitOutcome.TimedOut ->
                    CommandResult.Failed(ERROR_TIMEOUT, "no call command result within " + timeoutMs + " ms")
                HookCallInbox.AwaitOutcome.Superseded ->
                    CommandResult.Failed(ERROR_SUPERSEDED, "the command result was claimed by a newer waiter")
            }
        } finally {
            inbox.endWait(wait)
        }
    }

    companion object {
        /**
         * 等待 Hook 结果的硬上限，必须覆盖 Hook 的取命令间隔。
         *
         * Hook 只在它的下一次心跳响应里取走排队的命令（Task 4 线格式），心跳周期是
         * HookTransport.HEARTBEAT_INTERVAL_MS = 2000 ms：最坏情况下命令要等满一个
         * 2000 ms 周期才会被取走执行，再加一次结果上行的余量。上限取 4000 ms：
         * - > 2000 ms：不会每个 Answer 都在命令被取走前超时；
         * - <= FAILURE_HANDOFF_WINDOW_MS / 2（10000 ms / 2）：接听结果最迟 4 s 内确定，
         *   监督端仍有完整的人工接管窗口做核验/接管，等待本身不会把窗口吃掉。
         */
        const val DEFAULT_TIMEOUT_MS: Long = 4_000L
        const val ERROR_QUEUE: String = "hook_command_queue_full"
        const val ERROR_TIMEOUT: String = "hook_command_timeout"

        /** 等待凭据被更权威的等待者（例如更晚开始的接听）接管：结果不归本次调用。 */
        const val ERROR_SUPERSEDED: String = "hook_command_superseded"
    }
}

/** 用最近一次 Hook 快照的状态回答“这通电话现在是什么状态”；未知返回 null。 */
class SnapshotCallStateProbe(private val inbox: HookCallInbox) : CallStateProbe {
    override suspend fun state(id: SessionId): CallState? = inbox.state(id)
}

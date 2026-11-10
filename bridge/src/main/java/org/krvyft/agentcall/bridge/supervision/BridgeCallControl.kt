package org.krvyft.agentcall.bridge.supervision

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.CallControl

/**
 * Hook 快照（hook.snapshot）固定字段 JSON 的消费侧（spec §6.4，任务 4 的线格式）。
 *
 * 只解析监督端需要的字段（state、handbackReason），并强制会话字段与请求会话一致：
 * 别人的快照绝不能推动本监督端的状态机。
 */
object HookSnapshotDecoder {

    data class Decoded(val state: CallState, val handbackReason: String?)

    private const val MAX_PAYLOAD_CHARS = 4096

    fun decode(expected: SessionId, payload: String): Decoded? {
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD_CHARS) return null
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (string(json, "processEpoch") != expected.processEpoch) return null
        if (string(json, "callId") != expected.callId) return null
        val state = CallState.entries.firstOrNull { it.name == string(json, "state") } ?: return null
        return Decoded(state, optionalString(json, "handbackReason"))
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
 * 监督端的 Hook 结果/状态收件箱（有界）。
 *
 * 与任务 3 的 HookCommandRouter 分开：路由器仍然把结果交给任务 7 的调用方，本收件箱只
 * 保存监督端自己等待的那一条，避免互相取走对方的结果。
 */
class HookCallInbox(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private data class PendingResult(
        val session: SessionId,
        val command: CallCommand,
        val result: CommandResult,
    )

    private val lock = Any()
    private val states = LinkedHashMap<SessionId, CallState>()
    private val results = ArrayDeque<PendingResult>()

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
        results.addLast(PendingResult(session, command, result))
        while (results.size > maxEntries) {
            results.removeFirst()
        }
    }

    /** 等待一条匹配 (session, command) 的结果；超时返回 null（调用方必须按不明确处理）。 */
    suspend fun awaitResult(session: SessionId, command: CallCommand, timeoutMs: Long): CommandResult? {
        val deadlineNanos = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (true) {
            val matched = synchronized(lock) {
                val index = results.indexOfFirst { it.session == session && it.command == command }
                if (index < 0) {
                    null
                } else {
                    val entry = results.elementAt(index)
                    results.removeAt(index)
                    entry.result
                }
            }
            if (matched != null) return matched
            if (System.nanoTime() >= deadlineNanos) return null
            delay(RESULT_POLL_INTERVAL_MS)
        }
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
        if (!enqueue(id, command)) {
            return CommandResult.Failed(ERROR_QUEUE, "hook command queue rejected the command")
        }
        return inbox.awaitResult(id, command, timeoutMs)
            ?: CommandResult.Failed(ERROR_TIMEOUT, "no call command result within " + timeoutMs + " ms")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 2_000L
        const val ERROR_QUEUE: String = "hook_command_queue_full"
        const val ERROR_TIMEOUT: String = "hook_command_timeout"
    }
}

/** 用最近一次 Hook 快照的状态回答“这通电话现在是什么状态”；未知返回 null。 */
class SnapshotCallStateProbe(private val inbox: HookCallInbox) : CallStateProbe {
    override suspend fun state(id: SessionId): CallState? = inbox.state(id)
}

package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/**
 * 特权容器侧的 HOOK 命令路由（spec §6.4：HOOK 只接受受限通话命令）。
 *
 * Task 3 的控制通道是“一请求一响应”，服务端不能主动推送，因此：
 * - 待执行命令排入本路由，随下一次 `hook.snapshot`/`hook.heartbeat` 的响应负载下发；
 * - Hook 执行后回报 `call.command.result`，本路由只做**校验与解析**（[parseResult]），
 *   结果唯一的去路是监督端收件箱
 *   （[org.krvyft.agentcall.bridge.supervision.HookCallInbox]）。本类没有第二个结果缓冲区：
 *   任何"结果队列"都只会在没人消费时填满并开始拒绝真实结果；
 * - 队列按会话/总量双上限，满了有界失败，绝不无界堆积。
 *
 * 命令行的线格式与 app 端 `org.krvyft.agentcall.hook.HookWireFormat` 一致：
 * ```
 * answer <callId>
 * hangup <callId>
 * ```
 * 该格式是 HOOK 角色的跨模块契约，改动必须同时修改 app 侧（Task 9 应加一致性校验）。
 */
class HookCommandRouter(
    private val maxPendingPerSession: Int = MAX_PENDING_PER_SESSION,
    private val maxSessions: Int = MAX_SESSIONS,
) {

    init {
        require(maxPendingPerSession > 0) { "maxPendingPerSession must be positive" }
        require(maxSessions > 0) { "maxSessions must be positive" }
    }

    /** Hook 回报的一条命令结果；[source] 是 Hook 上报的动作来源（MODULE/USER/UNKNOWN）。 */
    data class HookCommandOutcome(
        val session: SessionId,
        val command: CallCommand,
        val source: String,
        val result: CommandResult,
        val note: String,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<SessionId, ArrayDeque<CallCommand>>()

    /** 为某个会话排入一条待执行命令。会话字段非法或队列饱和时返回 false。 */
    fun enqueueCommand(session: SessionId, command: CallCommand): Boolean = synchronized(lock) {
        if (!isWireSafeSession(session)) return false
        val queue = pending[session]
        if (queue == null) {
            if (pending.size >= maxSessions) return false
            pending[session] = ArrayDeque<CallCommand>().apply { addLast(command) }
            return true
        }
        if (queue.size >= maxPendingPerSession) return false
        queue.addLast(command)
        true
    }

    /**
     * 取走该会话所有待执行命令的行文本（空字符串表示没有命令）。每条命令最多下发一次。
     */
    fun drainCommandLines(session: SessionId): String = synchronized(lock) {
        val queue = pending.remove(session) ?: return ""
        buildString {
            for (command in queue) {
                append(wireVerb(command)).append(' ').append(session.callId).append('\n')
            }
        }
    }

    /** 该会话当前排队命令数（诊断用）。 */
    fun pendingCount(session: SessionId): Int = synchronized(lock) { pending[session]?.size ?: 0 }

    /**
     * 解析一条 `call.command.result`。负载必须是固定字段的 JSON，且其中的会话字段
     * 必须与请求的会话一致；任何不合法输入整体拒绝（返回 null），不猜测结果。
     *
     * 只解析、不存储：调用方必须把结果交给监督端收件箱（唯一结果通路），
     * 不存在"路由缓冲区 + 收件箱"两条互相抢结果的路。
     */
    fun parseResult(session: SessionId, payload: String): HookCommandOutcome? =
        synchronized(lock) { decodeResult(session, payload) }

    // ------------------------------------------------------------------ 解析

    private fun decodeResult(session: SessionId, payload: String): HookCommandOutcome? {
        if (payload.isEmpty() || payload.length > MAX_RESULT_PAYLOAD_CHARS) return null
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (json.keySet() != RESULT_KEYS) return null
        if (string(json, "processEpoch") != session.processEpoch) return null
        if (string(json, "callId") != session.callId) return null
        val command = when (string(json, "command")) {
            "Answer" -> CallCommand.Answer
            "Hangup" -> CallCommand.Hangup
            else -> return null
        }
        val source = when (string(json, "source")) {
            "MODULE", "USER", "UNKNOWN" -> string(json, "source")!!
            else -> return null
        }
        val note = string(json, "note") ?: return null
        val result = when (string(json, "outcome")) {
            "accepted" -> {
                if (string(json, "reason") != null || string(json, "code") != null || string(json, "message") != null) {
                    return null
                }
                CommandResult.Accepted
            }
            "rejected" -> {
                val reason = RejectReason.entries.firstOrNull { it.name == string(json, "reason") } ?: return null
                if (string(json, "code") != null || string(json, "message") != null) return null
                CommandResult.Rejected(reason)
            }
            "failed" -> {
                if (string(json, "reason") != null) return null
                val code = string(json, "code") ?: return null
                val message = string(json, "message") ?: return null
                if (code.isEmpty() || message.isEmpty()) return null
                CommandResult.Failed(code, message)
            }
            else -> return null
        }
        return HookCommandOutcome(
            session = session,
            command = command,
            source = source,
            result = result,
            note = note,
        )
    }

    private fun string(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun wireVerb(command: CallCommand): String = when (command) {
        CallCommand.Answer -> "answer"
        CallCommand.Hangup -> "hangup"
    }

    companion object {
        const val MAX_PENDING_PER_SESSION: Int = 8
        const val MAX_SESSIONS: Int = 64
        const val MAX_RESULT_PAYLOAD_CHARS: Int = 2048

        private const val MAX_SESSION_FIELD_CHARS: Int = 64

        /** 与 app 端 `HookWireFormat.encodeCommandResult` 完全一致的固定字段表。 */
        private val RESULT_KEYS = setOf(
            "processEpoch",
            "callId",
            "command",
            "source",
            "outcome",
            "reason",
            "code",
            "message",
            "note",
        )

        fun isWireSafeSession(session: SessionId): Boolean =
            isWireSafeToken(session.processEpoch) && isWireSafeToken(session.callId)

        private fun isWireSafeToken(value: String): Boolean =
            value.isNotEmpty() && value.length <= MAX_SESSION_FIELD_CHARS &&
                value.none { it.isISOControl() || it.isWhitespace() }
    }
}

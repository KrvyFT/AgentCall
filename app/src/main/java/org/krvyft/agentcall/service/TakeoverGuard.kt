package org.krvyft.agentcall.service

import java.io.ByteArrayOutputStream
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState

/** 人工接管请求的裁决结果。 */
sealed interface TakeoverDecision {
    /** 请求针对当前会话且状态可接管：可以投递 [org.krvyft.agentcall.core.model.SessionEvent.Takeover]。 */
    data object Accepted : TakeoverDecision

    /** 拒绝原因（机器可读短码，不含号码/会话原值）。 */
    data class Rejected(val code: String) : TakeoverDecision
}

/**
 * 人工接管裁决（Task 7B2，spec §6.2/§8）。
 *
 * 通知与页面里的接管入口绑定的是**生成它时的 SessionId**。会话已经切换（新 callId 或
 * 新 processEpoch）时，旧入口绝不能作用于新通话——这不是 UI 文案问题，而是命令目标问题。
 * 裁决只看会话身份与当前 reducer 状态；null 一律拒绝（fail closed）。
 */
object TakeoverGuard {

    const val ERROR_SESSION_REQUIRED: String = "takeover_session_required"
    const val ERROR_NO_ACTIVE_CALL: String = "takeover_no_active_call"
    const val ERROR_STALE_SESSION: String = "takeover_stale_session"
    const val ERROR_NOT_TAKEOVER_ABLE: String = "takeover_state_not_allowed"

    /** 仍可人工接管的状态：AI 已接管或正在接管的全部阶段；IDLE/ENDING/ENDED 不再接受。 */
    private val ALLOWED_STATES: Set<SessionState> = setOf(
        SessionState.ELIGIBLE,
        SessionState.PREPARING,
        SessionState.ANSWER_PENDING,
        SessionState.ACTIVE_AI,
        SessionState.HANDOFF,
    )

    fun evaluate(
        requested: SessionId?,
        active: SessionId?,
        state: SessionState?,
    ): TakeoverDecision {
        if (requested == null) return TakeoverDecision.Rejected(ERROR_SESSION_REQUIRED)
        if (active == null) return TakeoverDecision.Rejected(ERROR_NO_ACTIVE_CALL)
        if (requested != active) return TakeoverDecision.Rejected(ERROR_STALE_SESSION)
        if (state == null || state !in ALLOWED_STATES) {
            return TakeoverDecision.Rejected(ERROR_NOT_TAKEOVER_ABLE)
        }
        return TakeoverDecision.Accepted
    }
}

/**
 * 通知/页面接管入口绑定的会话目标。
 *
 * 序列化为固定形式的 URI（`agentcall://takeover/<processEpoch>/<callId>`），作为
 * PendingIntent 的 Intent data：不同会话得到不同的 PendingIntent 身份，旧通知的 action
 * 仍带着旧 SessionId，解码后由 [TakeoverGuard] 拒绝。
 *
 * 编码是保守的百分号编码；解析时逐段解码并校验（空白、控制字符、超长、空段一律拒绝），
 * 因此会话原值不能被注入成别的路径或额外字段。
 */
data class TakeoverTarget(val session: SessionId) {

    companion object {
        const val SCHEME: String = "agentcall"
        const val HOST: String = "takeover"
        const val MAX_FIELD_CHARS: Int = 64

        private const val PREFIX = SCHEME + "://" + HOST + "/"

        fun of(session: SessionId): TakeoverTarget = TakeoverTarget(session)

        /** 生成 Intent data 字符串；会话字段非法时返回空串（解析必然失败，绝不产生别的目标）。 */
        fun encode(target: TakeoverTarget): String {
            val epoch = encodeComponent(target.session.processEpoch) ?: return ""
            val callId = encodeComponent(target.session.callId) ?: return ""
            return PREFIX + epoch + "/" + callId
        }

        /** 解析 Intent data；任何不合法输入返回 null（调用方必须拒绝该次接管）。 */
        fun parse(data: String): SessionId? {
            if (data.isEmpty() || !data.startsWith(PREFIX)) return null
            val rest = data.substring(PREFIX.length)
            if (rest.count { it == '/' } != 1) return null
            val parts = rest.split('/')
            if (parts.size != 2) return null
            val epoch = decodeComponent(parts[0]) ?: return null
            val callId = decodeComponent(parts[1]) ?: return null
            return SessionId(processEpoch = epoch, callId = callId)
        }

        /**
         * PendingIntent 的 requestCode。身份以 Intent data 为准，这里只提供稳定、
         * 同一会话不变、不同会话不同的辅助值（避免每次刷新通知都产生新的 PendingIntent）。
         */
        fun requestCode(target: TakeoverTarget): Int =
            (target.session.processEpoch + "/" + target.session.callId).hashCode() and Int.MAX_VALUE

        private fun encodeComponent(value: String): String? {
            if (!isValidField(value)) return null
            val out = StringBuilder(value.length)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val unsigned = byte.toInt() and 0xFF
                val character = unsigned.toChar()
                if (character.isLetterOrDigit() || character == '-' || character == '_' || character == '.' || character == '~') {
                    out.append(character)
                } else {
                    out.append('%')
                    out.append(HEX[unsigned ushr 4])
                    out.append(HEX[unsigned and 0x0F])
                }
            }
            return out.toString()
        }

        private fun decodeComponent(value: String): String? {
            if (value.isEmpty()) return null
            val bytes = ByteArrayOutputStream(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character == '%') {
                    if (index + 2 >= value.length) return null
                    val high = hexValue(value[index + 1]) ?: return null
                    val low = hexValue(value[index + 2]) ?: return null
                    bytes.write((high shl 4) or low)
                    index += 3
                } else {
                    if (!(character.isLetterOrDigit() || character == '-' || character == '_' || character == '.' || character == '~')) {
                        return null
                    }
                    bytes.write(character.code)
                    index += 1
                }
            }
            val decoded = String(bytes.toByteArray(), Charsets.UTF_8)
            // 解码后必须是合法的会话字段，且重新编码后与原文一致（拒绝非规范百分号编码）。
            if (!isValidField(decoded)) return null
            if (encodeComponent(decoded) != value) return null
            return decoded
        }

        private fun isValidField(value: String): Boolean =
            value.isNotEmpty() &&
                value.length <= MAX_FIELD_CHARS &&
                value.none { it.isISOControl() || it.isWhitespace() }

        private fun hexValue(character: Char): Int? = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> null
        }

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}

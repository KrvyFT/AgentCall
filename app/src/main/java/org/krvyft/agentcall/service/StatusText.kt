package org.krvyft.agentcall.service

import android.content.res.Resources
import androidx.annotation.StringRes
import org.krvyft.agentcall.R
import org.krvyft.agentcall.core.model.SessionState

/**
 * 机器码 → 中文说明（UI 与通知共用）。
 *
 * 文案全部在 `res/values/strings.xml`（[StatusText] 只保存码 → 资源 id 的映射）；
 * 未知码原样显示——绝不把未知状态粉饰成“正常/已就绪”。
 */
object StatusText {

    /** 未就绪码 → 资源；未知码返回 null（调用方保留原始机器码）。 */
    @StringRes
    fun blockerRes(code: String): Int? = when (code) {
        BootstrapBlocker.BRIDGE_UNREACHABLE.code -> R.string.status_blocker_bridge_unreachable
        BootstrapBlocker.HOOK_NOT_CONNECTED.code -> R.string.status_blocker_hook_not_connected
        BootstrapBlocker.HOOK_PRESENCE_UNKNOWN.code -> R.string.status_blocker_hook_presence_unknown
        BootstrapBlocker.DIALER_NOT_DEFAULT.code -> R.string.status_blocker_dialer_not_default
        BootstrapBlocker.CONFIG_DISABLED.code -> R.string.status_blocker_config_disabled
        BootstrapBlocker.CONFIG_INVALID.code -> R.string.status_blocker_config_invalid
        BootstrapBlocker.CONFIG_VOICE_UNSUPPORTED.code -> R.string.status_blocker_config_voice_unsupported
        BootstrapBlocker.CALIBRATION_BLOCKED.code -> R.string.status_blocker_calibration_blocked
        BootstrapBlocker.NETWORK_UNREACHABLE.code -> R.string.status_blocker_network_unreachable
        else -> null
    }

    fun blocker(code: String, resources: Resources): String =
        blockerRes(code)?.let(resources::getString) ?: code

    @StringRes
    fun hookPresenceRes(presence: HookPresence): Int = when (presence) {
        HookPresence.PRESENT -> R.string.status_hook_present
        HookPresence.ABSENT -> R.string.status_hook_absent
        HookPresence.UNKNOWN -> R.string.status_hook_unknown
    }

    fun hookPresence(presence: HookPresence, resources: Resources): String =
        resources.getString(hookPresenceRes(presence))

    @StringRes
    fun sessionStateRes(state: SessionState?): Int = when (state) {
        null -> R.string.status_session_none
        SessionState.IDLE -> R.string.status_session_idle
        SessionState.ELIGIBLE -> R.string.status_session_eligible
        SessionState.PREPARING -> R.string.status_session_preparing
        SessionState.ANSWER_PENDING -> R.string.status_session_answer_pending
        SessionState.ACTIVE_AI -> R.string.status_session_active_ai
        SessionState.HANDOFF -> R.string.status_session_handoff
        SessionState.ENDING -> R.string.status_session_ending
        SessionState.ENDED -> R.string.status_session_ended
    }

    fun sessionState(state: SessionState?, resources: Resources): String =
        resources.getString(sessionStateRes(state))

    /**
     * 人工交还结果；未确认的撤销不得显示“已交还成功”。
     *
     * 未知码/空码返回 null/原码，调用方据此决定是否显示。
     */
    @StringRes
    fun handbackRes(code: String): Int? = when (code) {
        HandbackJournal.CONFIRMED -> R.string.status_handback_confirmed
        HandbackJournal.NOT_OWNED -> R.string.status_handback_not_owned
        HandbackJournal.UNCONFIRMED -> R.string.status_handback_unconfirmed
        else -> null
    }

    fun handback(code: String?, resources: Resources): String? {
        if (code == null) return null
        return handbackRes(code)?.let(resources::getString) ?: code
    }

    @StringRes
    fun takeoverRejectionRes(code: String): Int? = when (code) {
        TakeoverGuard.ERROR_SESSION_REQUIRED -> R.string.status_takeover_session_required
        TakeoverGuard.ERROR_NO_ACTIVE_CALL -> R.string.status_takeover_no_active_call
        TakeoverGuard.ERROR_STALE_SESSION -> R.string.status_takeover_stale_session
        TakeoverGuard.ERROR_NOT_TAKEOVER_ABLE -> R.string.status_takeover_state_not_allowed
        else -> null
    }

    fun takeoverRejection(code: String, resources: Resources): String =
        takeoverRejectionRes(code)?.let(resources::getString) ?: code
}

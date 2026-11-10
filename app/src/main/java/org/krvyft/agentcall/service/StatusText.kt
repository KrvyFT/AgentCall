package org.krvyft.agentcall.service

import org.krvyft.agentcall.core.model.SessionState

/**
 * 机器码 → 中文说明（UI 与通知共用）。
 *
 * 只翻译固定机器码；未知码原样显示——绝不把未知状态粉饰成“正常/已就绪”。
 */
object StatusText {

    fun blocker(code: String): String = when (code) {
        BootstrapBlocker.BRIDGE_UNREACHABLE.code -> "root 桥接不可达或 APP 未配对"
        BootstrapBlocker.HOOK_NOT_CONNECTED.code -> "Hook 心跳缺失（5000 ms 内没有心跳）"
        BootstrapBlocker.HOOK_PRESENCE_UNKNOWN.code -> "Hook 状态未知（桥接未向 APP 开放 status）"
        BootstrapBlocker.CONFIG_DISABLED.code -> "总开关关闭"
        BootstrapBlocker.CONFIG_INVALID.code -> "配置校验未通过"
        BootstrapBlocker.CALIBRATION_BLOCKED.code -> "校准未通过（无完整环境绑定的人工测试证据）"
        BootstrapBlocker.NETWORK_UNREACHABLE.code -> "网络不可用或未验证"
        else -> code
    }

    fun hookPresence(presence: HookPresence): String = when (presence) {
        HookPresence.PRESENT -> "在线"
        HookPresence.ABSENT -> "离线"
        HookPresence.UNKNOWN -> "未知"
    }

    fun sessionState(state: SessionState?): String = when (state) {
        null -> "无进行中的会话"
        SessionState.IDLE -> "空闲"
        SessionState.ELIGIBLE -> "符合接管条件"
        SessionState.PREPARING -> "准备接听（倒计时）"
        SessionState.ANSWER_PENDING -> "接听待确认"
        SessionState.ACTIVE_AI -> "AI 通话中"
        SessionState.HANDOFF -> "等待人工接管"
        SessionState.ENDING -> "正在结束"
        SessionState.ENDED -> "已结束"
    }

    /** 人工交还结果；未确认的撤销不得显示“已交还成功”。 */
    fun handback(code: String?): String? = when (code) {
        null -> null
        "handback_confirmed" -> "已确认撤销 AI 所有权并交还人工"
        "handback_not_owned" -> "监督端本不持有该会话，已交还人工"
        "handback_unconfirmed" -> "撤销未确认：请勿当作已交还"
        else -> code
    }

    fun takeoverRejection(code: String): String = when (code) {
        TakeoverGuard.ERROR_SESSION_REQUIRED -> "接管请求缺少会话标识，已拒绝"
        TakeoverGuard.ERROR_NO_ACTIVE_CALL -> "当前没有进行中的通话"
        TakeoverGuard.ERROR_STALE_SESSION -> "这个入口属于旧通话，不能控制当前通话"
        TakeoverGuard.ERROR_NOT_TAKEOVER_ABLE -> "当前会话状态不允许接管"
        else -> code
    }
}

package org.krvyft.agentcall.core.session

/**
 * 会话超时上限的唯一来源。
 *
 * 接听延迟与最长 AI 通话时长是可配置项，范围在 [org.krvyft.agentcall.core.model.AgentConfig]，
 * 由 [org.krvyft.agentcall.core.rules.ConfigValidator] 校验；心跳与故障接管窗口是协议常量，
 * 由本对象统一给出，避免任务 5/7 各写一份。
 */
object SessionLimits {

    /** APK/Hook 心跳中断多久后判定失联（spec §8：5000 ms）。 */
    const val HEARTBEAT_TIMEOUT_MS = 5_000L

    /** 音频建立失败后等待人工接管的窗口（spec §8：10000 ms），超时即终止 AI 通话。 */
    const val FAILURE_HANDOFF_WINDOW_MS = 10_000L
}

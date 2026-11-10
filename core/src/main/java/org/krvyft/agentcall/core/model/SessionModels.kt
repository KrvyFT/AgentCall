package org.krvyft.agentcall.core.model

/**
 * 会话状态路径：IDLE → ELIGIBLE → PREPARING → ANSWER_PENDING → ACTIVE_AI →
 * HANDOFF/ENDING → ENDED。
 */
enum class SessionState {
    IDLE,
    ELIGIBLE,
    PREPARING,
    ANSWER_PENDING,
    ACTIVE_AI,
    HANDOFF,
    ENDING,
    ENDED,
}

/**
 * 交还人工控制的原因。来源不明的路由变化按 [UNKNOWN] 保守交还。
 */
enum class TakeoverReason {
    /** 用户在 Google 电话界面自行接听。 */
    USER_ANSWERED,

    /** 用户通过 AgentCall 界面或通知主动接管。 */
    USER_REQUESTED,

    /** 系统音频路由变化，来源未知。 */
    ROUTE_CHANGED,

    /** 用户在 AI 通话中解除静音或切回听筒。 */
    MICROPHONE_UNMUTED,

    /** 无法归类的原因，按保守交还处理。 */
    UNKNOWN,
}

/**
 * 单写者 reducer 的输入事件。时间从外部 nowMs 参数传入，事件本身不读取系统时钟。
 */
sealed interface SessionEvent {
    /**
     * 外部观察到的通话快照变化（来自 Hook 的 Call 回调）。
     *
     * 事件携带 [CallSnapshot] 而不是 [SessionState]：会话状态由 reducer 单写者推导，
     * 外部事件只描述通话事实，不得直接改写 reducer 的状态。
     */
    data class StateChanged(val snapshot: CallSnapshot) : SessionEvent

    /** 驱动接听倒计时与最长时长检查的周期性事件。 */
    data object Tick : SessionEvent

    /** APK 服务 READY 握手结果变化；未 READY 不得自动接听。 */
    data class ReadyChanged(val ready: Boolean) : SessionEvent

    /** 人工接管或路由变化导致的交还。 */
    data class Takeover(val reason: TakeoverReason) : SessionEvent

    /** 某个对端心跳中断。 */
    data class PeerLost(val role: PeerRole) : SessionEvent
}

/**
 * reducer 输出的副作用描述。副作用由调用方执行，reducer 本身不接触 Android 或网络。
 */
sealed interface SessionEffect {
    /** 向 Google 电话进程下发限制性命令。 */
    data class SendCommand(val command: CallCommand) : SessionEffect

    /** 在接听命令执行前先取得会话租约。 */
    data class AcquireLease(val id: SessionId) : SessionEffect

    /** 撤销本会话的 AI 所有权，撤销确认前不得显示交还成功。 */
    data class RevokeLease(val id: SessionId) : SessionEffect

    /** 启动远程语音会话。 */
    data object StartConversation : SessionEffect

    /** 停止远程语音会话并取消其子 Job。 */
    data object StopConversation : SessionEffect

    /** 保持麦克风隔离（禁止环境麦克风冒充下行）。 */
    data object IsolateMicrophone : SessionEffect

    /** 在确认断开后恢复人工路由与麦克风。 */
    data class RestoreMicrophone(val id: SessionId) : SessionEffect

    /** 提示用户需要人工接管或说明故障。 */
    data class NotifyUser(val reason: String) : SessionEffect
}

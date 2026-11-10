package org.krvyft.agentcall.core.model

/**
 * 一次通话会话的稳定标识。
 *
 * [processEpoch] 标识 Google 电话进程的这一次重建，[callId] 是进程内随机分配的
 * 通话标识。两者都不是电话号码，任何模块都不得用号码充当会话身份。
 */
data class SessionId(
    val processEpoch: String,
    val callId: String,
)

/**
 * 通话方向。只有 [INCOMING] 且处于 RINGING 的通话可能被自动接管。
 */
enum class CallDirection {
    INCOMING,
    OUTGOING,
}

/**
 * 与 Telecom 对齐的通话状态。自动接听只接受 RINGING，其余状态不得补发接听命令。
 */
enum class CallState {
    RINGING,
    ACTIVE,
    HOLDING,
    DISCONNECTED,
}

/**
 * 主叫号码呈现类型。受限、未知或付费电话不按可识别号码处理，默认人工处理。
 */
enum class Presentation {
    ALLOWED,
    RESTRICTED,
    UNKNOWN,
    PAYPHONE,
}

/**
 * 某一时刻通话快照，供规则判断与状态机使用，不携带音频或会话租约。
 *
 * @property number 原始号码，仅用于名单规范化匹配；未知或受限时为 null。
 * @property phoneAccount 实际 PhoneAccount 标识，用于限定已校准的 SIM/路由。
 * @property isEmergency 系统可识别的紧急呼叫或紧急回拨。
 * @property isConference 会议通话。
 * @property activeCallCount 当前活动/保持/等待的通话数，大于 1 时不自动接管第二路。
 */
data class CallSnapshot(
    val id: SessionId,
    val number: String?,
    val state: CallState,
    val direction: CallDirection,
    val presentation: Presentation,
    val phoneAccount: String?,
    val isEmergency: Boolean = false,
    val isConference: Boolean = false,
    val activeCallCount: Int = 1,
)

/**
 * 允许下发给 Google 电话进程的限制性命令。正常接听/挂断总是针对原 Call 对象，
 * 不支持无选择的全局操作。
 */
sealed interface CallCommand {
    /** 接听当前 RINGING 的通话。 */
    data object Answer : CallCommand

    /** 挂断当前由模块持有租约的通话。 */
    data object Hangup : CallCommand
}

/**
 * [CallCommand] 的执行结果。拒绝原因是机器可读的，供状态机与诊断使用。
 */
sealed interface CommandResult {
    /** 命令已在目标 Call 对象上执行。 */
    data object Accepted : CommandResult

    /** 命令在核验阶段被拒绝，未对通话产生副作用。 */
    data class Rejected(val reason: RejectReason) : CommandResult

    /** 核验通过但执行失败，[code] 为机器可读错误码。 */
    data class Failed(val code: String, val message: String) : CommandResult
}

/**
 * 命令被拒绝的原因。
 */
enum class RejectReason {
    /** 会话代次与当前进程/通话不匹配。 */
    STALE_SESSION,

    /** 会话租约不属于调用方。 */
    NOT_OWNED,

    /** 目标通话已不处于可执行该命令的状态。 */
    NOT_RINGING,

    /** 目标 ROM 不提供该能力。 */
    UNSUPPORTED,
}

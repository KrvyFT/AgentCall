package org.krvyft.agentcall.hook

import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.SessionId

/**
 * Google 电话进程内的通话门面（纯 Kotlin，无 android import）。
 *
 * 作用是把“某个真实 [android.telecom.Call]”抽象成 [CallRegistry] 能驱动的对象：
 * 快照、回调注册、接听/挂断。生产实现是 [AndroidCallHandle]；测试用不触碰 Telecom
 * 的 fake 实现。会话身份由 [CallRegistry] 分配后经 [bindSession] 绑定，门面在真正
 * 执行 Telecom 动作前用它做代次核验。
 *
 * 约束：
 * - [identity] 是进程内随机令牌，**绝不是电话号码**；重复 observe 靠它去重。
 * - [answer]/[disconnect] 可以异步派发（生产实现切到拨号器主线程），返回的是
 *   “派发前核验结果”，主线程二次核验失败会以 [HandleEvent] 回报。
 * - 所有回调只能在 Telecom 回调线程上被调用；实现不得在其中做网络/IPC/阻塞操作。
 */
interface CallHandle {

    /** 进程内随机、稳定的门面标识；不得使用电话号码或号码派生值。 */
    val identity: String

    /** 当前时刻的纯数据快照；任何 Android 类型都必须在此转换完毕。 */
    fun snapshot(): HandleSnapshot

    /**
     * 由 [CallRegistry] 在 observe 时绑定本次会话。执行 Telecom 动作前实现必须核验
     * 该会话仍与当前进程代次一致；默认空实现供不持有 Telecom 对象的测试替身使用。
     */
    fun bindSession(id: SessionId) {
        // 纯测试替身可以忽略会话绑定。
    }

    fun addCallback(callback: CallCallback)

    fun removeCallback(callback: CallCallback)

    /** 接听当前通话；只有 RINGING 且会话/代次有效时才允许真正下发。 */
    fun answer(): CommandResult

    /** 挂断当前通话；只有模块自己接管的通话才允许真正下发。 */
    fun disconnect(): CommandResult
}

/** 通话变化回调。实现必须只做入队/状态更新，绝不阻塞 Telecom 线程。 */
fun interface CallCallback {
    fun onEvent(event: HandleEvent)
}

/**
 * 与 Android 解耦的通话快照。
 *
 * 安全字段（紧急、会议、活动通话数）没有默认值：映射层必须显式给出，避免漏填时
 * 把“紧急/会议/第二路”静默降级为可接管。
 */
data class HandleSnapshot(
    val number: String?,
    val state: CallState,
    val direction: CallDirection,
    val presentation: Presentation,
    val phoneAccount: String?,
    val isEmergency: Boolean,
    val isConference: Boolean,
    val activeCallCount: Int,
)

/** 通话变化事件。[source] 区分模块动作、人工动作与来源不明，供保守交还使用。 */
data class HandleEvent(
    val snapshot: HandleSnapshot,
    val source: ActionSource,
    val kind: EventKind,
    val reason: String = "",
    val destroyed: Boolean = false,
)

/** 事件种类。未知变化必须上报，不能替调用方猜测。 */
enum class EventKind {
    /** Telecom 状态变化（RINGING/ACTIVE/HOLDING/DISCONNECTED…）。 */
    STATE_CHANGED,

    /** Details 变化（号码、呈现、PhoneAccount 等）。 */
    DETAILS_CHANGED,

    /** 音频路由变化；来源不明时是保守交还的依据。 */
    ROUTE_CHANGED,

    /** Call 已销毁。 */
    DESTROYED,

    /**
     * Telecom 报告了一个未映射的瞬时/未知状态（NEW、DIALING、CONNECTING、
     * SELECT_PHONE_ACCOUNT、AUDIO_PROCESSING…）。注册表必须保留上一次已知状态，
     * 既不猜测成 ACTIVE，也不因此撤销待执行接听。
     */
    UNKNOWN_STATE,

    /** 模块动作在主线程二次核验时失败。 */
    ACTION_FAILED,
}

/** 动作来源。模块自己的操作必须标记为 [MODULE]，其余一律不当作模块所有。 */
enum class ActionSource {
    MODULE,
    USER,
    UNKNOWN,
}

/** 一条命令尝试的机器可读记录；由 [CallRegistry] 交给注入的 [CommandSink]。 */
data class CommandAttempt(
    val session: SessionId,
    val command: CallCommand,
    val source: ActionSource,
    val result: CommandResult,
    val note: String = "",
)

/**
 * 命令尝试接收器。生产实现只做有界入队/日志，绝不阻塞电话线程；测试用它断言
 * 实际发生了几次命令、结果和来源。
 */
fun interface CommandSink {
    fun onCommandAttempt(attempt: CommandAttempt)

    companion object {
        val NONE: CommandSink = CommandSink { }
    }
}

/**
 * 注册表生命周期观察者。默认空实现，便于只关心其中一部分的调用方。
 */
interface RegistryListener {

    /** 快照变化（含 observe 的初始快照）。 */
    fun onSnapshot(snapshot: CallSnapshot, source: ActionSource) {
        // 可选。
    }

    /** 会话被移除（通话结束、显式移除）。 */
    fun onSessionRemoved(session: SessionId) {
        // 可选。
    }

    /**
     * 会话被移除，并带上对应的门面，便于上层清理 Call→门面映射（否则门面会一直
     * 留在映射里直到 InCallService.onCallRemoved）。默认转调单参数版本。
     */
    fun onSessionRemoved(session: SessionId, handle: CallHandle) {
        onSessionRemoved(session)
    }

    /** 模块控制被保守交还人工；[reason] 是机器可读原因。 */
    fun onHandedBack(session: SessionId, source: ActionSource, reason: String) {
        // 可选。
    }

    companion object {
        val NONE: RegistryListener = object : RegistryListener {}
    }
}

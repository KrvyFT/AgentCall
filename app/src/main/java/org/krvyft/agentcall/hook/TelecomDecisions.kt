package org.krvyft.agentcall.hook

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/**
 * 一次模块动作认领的结果。
 *
 * @property source 本次 Telecom 状态变化的来源；只有“目标状态匹配 + 短窗口内”才是
 *   [ActionSource.MODULE]，其余一律不当作模块所有。
 * @property clearPending 是否应丢弃待认领标记（已消耗或已过期）。
 */
internal data class Claim(val source: ActionSource, val clearPending: Boolean)

/**
 * Telecom 状态与模块动作来源的纯逻辑（无 Android 调用，JVM 可测）。
 *
 * 关键规则（spec §6.1“未知状态不猜测”）：
 * - 只有 RINGING/HOLDING/ACTIVE/DISCONNECTED/DISCONNECTING 是已知状态；
 *   NEW、DIALING、CONNECTING、SELECT_PHONE_ACCOUNT、AUDIO_PROCESSING、PULLING_CALL、
 *   SIMULATED_RINGING 以及将来新增的未列状态一律返回 null，由调用方上报
 *   [EventKind.UNKNOWN_STATE]，**不得**猜成 ACTIVE。
 * - 快照侧映射保持保守：未知状态映射为 [CallState.ACTIVE]（不可接听），绝不猜成 RINGING。
 * - 模块动作只在目标状态真的到达时认领；中间经过瞬时状态不会丢掉认领，也不会把
 *   人工动作误判成模块动作（目标不匹配时返回 USER/UNKNOWN）。
 */
internal object TelecomDecisions {

    /** 模块动作与 Telecom 状态回调之间的认领窗口；过期后不再猜测来源。 */
    const val MODULE_ACTION_WINDOW_NS: Long = 5_000_000_000L

    /** 事件侧映射：未列出的瞬时/未知状态返回 null（不猜测）。 */
    fun mapEventState(telecomState: Int): CallState? = when (telecomState) {
        Call.STATE_RINGING -> CallState.RINGING
        Call.STATE_HOLDING -> CallState.HOLDING
        Call.STATE_ACTIVE -> CallState.ACTIVE
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallState.DISCONNECTED
        else -> null
    }

    /** 快照侧映射：未知状态按“不可接听”的 ACTIVE 保守处理。 */
    fun mapSnapshotState(telecomState: Int): CallState = mapEventState(telecomState) ?: CallState.ACTIVE

    /**
     * 认领本次状态变化的来源。
     *
     * @param pendingTargetState 模块动作期待的目标状态（接听 ACTIVE / 挂断 DISCONNECTED）
     * @param pendingAtNs 模块动作下发时刻（单调时钟）
     */
    fun claimSource(
        telecomState: Int,
        pendingTargetState: Int?,
        pendingAtNs: Long?,
        nowNs: Long,
    ): Claim {
        val mapped = mapEventState(telecomState)
        if (pendingTargetState == null || pendingAtNs == null) {
            return Claim(defaultSource(mapped), clearPending = false)
        }
        val target = mapEventState(pendingTargetState)
        val withinWindow = nowNs - pendingAtNs <= MODULE_ACTION_WINDOW_NS
        if (target != null && withinWindow && mapped == target) {
            return Claim(ActionSource.MODULE, clearPending = true)
        }
        if (!withinWindow) {
            return Claim(defaultSource(mapped), clearPending = true)
        }
        // 窗口内但目标状态尚未到达（例如中间的 AUDIO_PROCESSING）：保留认领，等待目标状态。
        return Claim(defaultSource(mapped), clearPending = false)
    }

    private fun defaultSource(mapped: CallState?): ActionSource =
        if (mapped == CallState.ACTIVE) ActionSource.USER else ActionSource.UNKNOWN
}

/**
 * 活动通话计数（M4 的纯逻辑）。
 *
 * service.calls 为空、全部断开或只看到 DISCONNECTING 时结果就是 0；注册表的安全门要求
 * 严格等于 1，因此 0 必然 fail closed。绝不能在调用方把 0 抬成 1。
 */
internal object CallCounts {

    fun activeCalls(telecomStates: List<Int>): Int =
        telecomStates.count { it != Call.STATE_DISCONNECTED && it != Call.STATE_DISCONNECTING }
}

/**
 * **活动**音频路由/端点的稳定指纹（纯字符串，便于 JVM 侧比较）。
 *
 * 框架会在接通、端点切换等时机重复回调同一个音频状态；只有活动路由/端点变化才是
 * “用户真的改了路由”。supportedRouteMask 与 isMuted 会随蓝牙可用性、静音开关等良性
 * 事件变化，**不参与**指纹（Task 11G 1）。任何字段不可读都返回 null（无法证明变化，
 * 注册表按 no-op 处理）。
 */
internal object RouteFingerprint {

    fun ofAudioState(state: CallAudioState?): String? {
        if (state == null) return null
        return try {
            audioFingerprint(state.route, state.isMuted, state.supportedRouteMask)
        } catch (failure: Throwable) {
            null
        }
    }

    /**
     * 纯字符串指纹（便于 JVM 测试）。
     *
     * 只有活动 [route] 参与：静音开关与 supportedRouteMask 会随蓝牙可用性等良性事件
     * 变化，把它们算进指纹会让一次无关事件被当成“用户换了路由”，从而把刚接通的 AI
     * 通话交还人工（Task 11G 1）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun audioFingerprint(route: Int, isMuted: Boolean, supportedRouteMask: Int): String =
        "audio:" + route

    fun ofEndpoint(endpoint: CallEndpoint?): String? {
        if (endpoint == null) return null
        return try {
            "endpoint:" + endpoint.identifier + ":" + endpoint.endpointType
        } catch (failure: Throwable) {
            null
        }
    }
}

/**
 * 接听/挂断下发前的两阶段核验（纯逻辑，JVM 可测）。
 *
 * 第一次核验在调用方线程，第二次在拨号器主线程真正调用 Telecom 之前。两次都使用同一
 * 判定：已撤销绑定、会话被移除/重新绑定（TOCTOU）、代次不符、状态不可读都 fail closed，
 * 绝不在核验失败时触碰 Telecom。
 */
internal object ActionPrecheck {

    const val CODE_STATE_UNAVAILABLE: String = "call_state_unavailable"
    const val MESSAGE_STATE_UNAVAILABLE: String = "telecom call state could not be read"

    /**
     * @param dispatchSession 命令被接受时绑定的会话（排队期间可能被撤销/重新绑定）
     * @param boundSession 当前绑定；注册表移除会话时会被清空
     * @param telecomState 当前 Telecom 状态；null 表示读取失败
     * @return null 表示可以继续；非 null 是同步拒绝/失败结果
     */
    fun check(
        requireRinging: Boolean,
        revoked: Boolean,
        dispatchSession: SessionId?,
        boundSession: SessionId?,
        processEpoch: String,
        telecomState: Int?,
    ): CommandResult? {
        if (revoked) return CommandResult.Rejected(RejectReason.STALE_SESSION)
        val bound = boundSession ?: return CommandResult.Rejected(RejectReason.STALE_SESSION)
        // 排队期间会话被移除或重新绑定：旧命令绝不能作用到新会话/已结束的通话。
        if (dispatchSession == null || dispatchSession != bound) {
            return CommandResult.Rejected(RejectReason.STALE_SESSION)
        }
        if (bound.processEpoch != processEpoch) {
            return CommandResult.Rejected(RejectReason.STALE_SESSION)
        }
        val state = telecomState
            ?: return CommandResult.Failed(CODE_STATE_UNAVAILABLE, MESSAGE_STATE_UNAVAILABLE)
        val mapped = TelecomDecisions.mapEventState(state)
        if (requireRinging) {
            if (mapped != CallState.RINGING) return CommandResult.Rejected(RejectReason.NOT_RINGING)
        } else if (mapped == CallState.DISCONNECTED) {
            return CommandResult.Rejected(RejectReason.NOT_RINGING)
        }
        return null
    }
}

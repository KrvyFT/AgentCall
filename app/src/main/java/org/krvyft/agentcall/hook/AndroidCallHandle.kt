package org.krvyft.agentcall.hook

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/**
 * [CallHandle] 在 `android.telecom.Call` 上的实现（spec §6.1）。
 *
 * 设计要点：
 * - **快照显式映射**：呈现类型、方向、状态、PhoneAccount、紧急/会议属性、活动通话数
 *   全部显式转换；安全字段没有默认值，缺 Details 时按“不可接管”保守取值。
 * - **主线程执行**：`answer`/`disconnect` 通过 [mainHandler] 切到拨号器主线程，
 *   并在真正调用 Telecom 前**再次**核验会话代次与通话状态；来电线程从不等待。
 * - **来源标记**：模块动作在下发前置位，对应状态变化被标记为 [ActionSource.MODULE]；
 *   其余 ACTIVE 变化视为人工接听，来源不明/保持等变化标记为 UNKNOWN，由注册表保守交还。
 * - **未知状态不猜测**：`onStateChanged` 只上报 Telecom 的真实状态映射，未知整数状态
 *   按非 RINGING 处理。Call.Callback 没有音频路由回调，EventKind.ROUTE_CHANGED 由后续
 *   音频层（Task 5）通过同一 HandleEvent 契约上报，注册表已按“来源不明保守交还”处理。
 *
 * 本类只持有单个 Call；会话身份与去重由 [CallRegistry] 负责。
 */
internal class AndroidCallHandle(
    private val call: Call,
    private val processEpoch: String,
    private val calls: () -> List<Call>,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val logger: (String) -> Unit = {},
) : CallHandle {

    override val identity: String = UUID.randomUUID().toString()

    private val callbacks = CopyOnWriteArrayList<CallCallback>()
    private val telecomCallbackRegistered = AtomicBoolean(false)

    @Volatile
    private var destroyed: Boolean = false

    @Volatile
    private var boundSession: SessionId? = null

    /** 只在主线程读写：模块动作的待认领标记。 */
    private var pendingAction: PendingAction? = null

    private data class PendingAction(val expectedCallState: Int, val atNs: Long)

    private fun callState(): Int = callStateOf(call)

    private fun callStateOf(call: Call): Int = call.details?.state ?: Call.STATE_NEW

    override fun bindSession(id: SessionId) {
        boundSession = id
    }

    override fun snapshot(): HandleSnapshot {
        val state = runCatching { callState() }.getOrDefault(Call.STATE_ACTIVE)
        if (destroyed) {
            return HandleSnapshot(
                number = null,
                state = CallState.DISCONNECTED,
                direction = CallDirection.OUTGOING,
                presentation = Presentation.UNKNOWN,
                phoneAccount = null,
                isEmergency = false,
                isConference = false,
                activeCallCount = 1,
            )
        }
        val details = runCatching { call.details }.getOrNull()
        val mappedState = mapState(state)
        val presentation = when (details?.handlePresentation) {
            TelecomManager.PRESENTATION_ALLOWED -> Presentation.ALLOWED
            TelecomManager.PRESENTATION_RESTRICTED -> Presentation.RESTRICTED
            TelecomManager.PRESENTATION_PAYPHONE -> Presentation.PAYPHONE
            else -> Presentation.UNKNOWN
        }
        val number = if (presentation == Presentation.ALLOWED) {
            details?.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val direction = when (details?.callDirection) {
            Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
            // DIRECTION_UNKNOWN 也按 OUTGOING 保守处理：只有确认入站才允许自动接管。
            else -> CallDirection.OUTGOING
        }
        val phoneAccount = details?.accountHandle?.let { handle ->
            handle.id ?: handle.componentName?.flattenToString()
        }
        val isEmergency = details != null && (
            details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
                details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
            )
        val isConference = details != null && (
            details.hasProperty(Call.Details.PROPERTY_CONFERENCE) ||
                details.hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE)
            )
        val activeCallCount = runCatching {
            calls().count { callStateOf(it) != Call.STATE_DISCONNECTED && callStateOf(it) != Call.STATE_DISCONNECTING }
        }.getOrDefault(2).coerceAtLeast(1)
        return HandleSnapshot(
            number = number,
            state = mappedState,
            direction = direction,
            presentation = presentation,
            phoneAccount = phoneAccount,
            isEmergency = isEmergency,
            isConference = isConference,
            activeCallCount = activeCallCount,
        )
    }

    override fun addCallback(callback: CallCallback) {
        if (destroyed) return
        if (!callbacks.contains(callback)) callbacks.add(callback)
        onMain { registerTelecomCallbackIfNeeded() }
    }

    override fun removeCallback(callback: CallCallback) {
        callbacks.remove(callback)
        if (callbacks.isEmpty()) {
            // 注册表可能在 Telecom 的回调分发中移除会话；unregister 必须排到当前分发之后，
            // 否则可能在框架遍历回调表时修改它。
            mainHandler.post { runCatching { unregisterTelecomCallbackIfNeeded() } }
        }
    }

    /**
     * 接听。返回 [CommandResult.Accepted] 表示“已排入主线程且派发前核验通过”；
     * 主线程二次核验失败会以 [EventKind.ACTION_FAILED] 上报，绝不在核验失败时调用 Telecom。
     */
    override fun answer(): CommandResult {
        precheck(requireRinging = true)?.let { return it }
        onMain {
            if (precheck(requireRinging = true) != null) {
                deliver(HandleEvent(snapshot(), ActionSource.UNKNOWN, EventKind.ACTION_FAILED, "answer_precheck_failed"))
                return@onMain
            }
            pendingAction = PendingAction(Call.STATE_ACTIVE, System.nanoTime())
            try {
                call.answer(0)
            } catch (failure: Throwable) {
                pendingAction = null
                deliver(HandleEvent(snapshot(), ActionSource.UNKNOWN, EventKind.ACTION_FAILED, failureCode("answer", failure)))
            }
        }
        return CommandResult.Accepted
    }

    /**
     * 挂断。与 [answer] 相同的两阶段核验；只对模块自己接管的通话下发（注册表已裁决，
     * 这里再做一次代次/状态防线）。
     */
    override fun disconnect(): CommandResult {
        precheck(requireRinging = false)?.let { return it }
        onMain {
            if (precheck(requireRinging = false) != null) {
                deliver(HandleEvent(snapshot(), ActionSource.UNKNOWN, EventKind.ACTION_FAILED, "disconnect_precheck_failed"))
                return@onMain
            }
            pendingAction = PendingAction(Call.STATE_DISCONNECTED, System.nanoTime())
            try {
                call.disconnect()
            } catch (failure: Throwable) {
                pendingAction = null
                deliver(HandleEvent(snapshot(), ActionSource.UNKNOWN, EventKind.ACTION_FAILED, failureCode("disconnect", failure)))
            }
        }
        return CommandResult.Accepted
    }

    // ------------------------------------------------------------------ 核验

    /** 返回 null 表示可以继续；非 null 是同步拒绝/失败结果。 */
    private fun precheck(requireRinging: Boolean): CommandResult? {
        if (destroyed) return CommandResult.Rejected(RejectReason.STALE_SESSION)
        val session = boundSession ?: return CommandResult.Rejected(RejectReason.STALE_SESSION)
        if (session.processEpoch != processEpoch) return CommandResult.Rejected(RejectReason.STALE_SESSION)
        val state = try {
            callState()
        } catch (failure: Throwable) {
            return CommandResult.Failed("call_state_unavailable", failure.javaClass.simpleName)
        }
        if (requireRinging && state != Call.STATE_RINGING) {
            return CommandResult.Rejected(RejectReason.NOT_RINGING)
        }
        if (!requireRinging && (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING)) {
            return CommandResult.Rejected(RejectReason.NOT_RINGING)
        }
        return null
    }

    // ------------------------------------------------------------------ Telecom 回调

    private val telecomCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            // 用回调给出的真实状态覆盖快照，避免 Call.getState() 与回调到达顺序不一致。
            val snapshot = snapshot().copy(state = mapState(state))
            deliver(
                HandleEvent(
                    snapshot = snapshot,
                    source = claimSourceForState(state),
                    kind = EventKind.STATE_CHANGED,
                    reason = "telecom_state_" + state,
                ),
            )
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            deliver(HandleEvent(snapshot(), ActionSource.UNKNOWN, EventKind.DETAILS_CHANGED, "details_changed"))
        }

        override fun onCallDestroyed(call: Call) {
            destroyed = true
            deliver(
                HandleEvent(
                    snapshot = snapshot().copy(state = CallState.DISCONNECTED),
                    source = ActionSource.UNKNOWN,
                    kind = EventKind.DESTROYED,
                    reason = "call_destroyed",
                    destroyed = true,
                ),
            )
        }

    }

    /**
     * 认领模块动作：只有“刚下发过动作 + 目标状态匹配 + 在短窗口内”才标记 MODULE。
     * 其余 ACTIVE 变化视为人工接听；其它状态/已过期标记为 UNKNOWN，不猜测。
     */
    private fun claimSourceForState(state: Int): ActionSource {
        val pending = pendingAction
        if (pending != null) {
            val withinWindow = System.nanoTime() - pending.atNs <= MODULE_ACTION_WINDOW_NS
            val matches = state == pending.expectedCallState || state == Call.STATE_DISCONNECTED
            if (withinWindow && matches) {
                pendingAction = null
                return ActionSource.MODULE
            }
            if (!withinWindow) pendingAction = null
        }
        return if (state == Call.STATE_ACTIVE) ActionSource.USER else ActionSource.UNKNOWN
    }

    private fun deliver(event: HandleEvent) {
        for (callback in callbacks) {
            try {
                callback.onEvent(event)
            } catch (failure: Throwable) {
                // 一个监听者失败不能影响其它监听者，更不能影响 Telecom。
                logger("AgentCall: call callback failed: " + failureCode("callback", failure))
            }
        }
    }

    // ------------------------------------------------------------------ 主线程/注册

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(block).onFailure { logger("AgentCall: main-thread action failed: " + it.javaClass.name) }
        } else {
            mainHandler.post {
                runCatching(block).onFailure { logger("AgentCall: main-thread action failed: " + it.javaClass.name) }
            }
        }
    }

    private fun registerTelecomCallbackIfNeeded() {
        if (destroyed || callbacks.isEmpty()) return
        if (!telecomCallbackRegistered.compareAndSet(false, true)) return
        try {
            // Handler 版本保证回调在拨号器主线程，队列本身也在注册时确定。
            call.registerCallback(telecomCallback, mainHandler)
        } catch (failure: Throwable) {
            telecomCallbackRegistered.set(false)
            logger("AgentCall: registerCallback failed: " + failureCode("register", failure))
        }
    }

    private fun unregisterTelecomCallbackIfNeeded() {
        if (!telecomCallbackRegistered.compareAndSet(true, false)) return
        try {
            call.unregisterCallback(telecomCallback)
        } catch (failure: Throwable) {
            logger("AgentCall: unregisterCallback failed: " + failureCode("unregister", failure))
        }
    }

    private fun failureCode(action: String, failure: Throwable): String =
        action + "_failed:" + failure.javaClass.simpleName

    private companion object {
        /** 模块动作与 Telecom 状态回调之间的认领窗口；过期后不再猜测来源。 */
        const val MODULE_ACTION_WINDOW_NS: Long = 5_000_000_000L
    }
}

/** Telecom 通话状态 → 共享契约状态。只有精确 RINGING 才映射为可接听。 */
private fun mapState(state: Int): CallState = when (state) {
    Call.STATE_RINGING -> CallState.RINGING
    Call.STATE_HOLDING -> CallState.HOLDING
    Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallState.DISCONNECTED
    else -> CallState.ACTIVE
}

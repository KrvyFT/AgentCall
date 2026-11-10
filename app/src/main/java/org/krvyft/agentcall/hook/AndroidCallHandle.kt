package org.krvyft.agentcall.hook

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.TelecomManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.SessionId

/**
 * [CallHandle] 在 `android.telecom.Call` 上的实现（spec §6.1）。
 *
 * 设计要点：
 * - **快照显式映射**：呈现类型、方向、状态、PhoneAccount、紧急/会议属性、活动通话数
 *   全部显式转换；安全字段没有默认值，缺 Details 时按“不可接管”保守取值。
 * - **主线程执行**：`answer`/`disconnect` 通过 [mainHandler] 切到拨号器主线程，
 *   并在真正调用 Telecom 前**再次**核验会话代次、绑定是否被撤销、通话状态；
 *   来电线程从不等待。判定逻辑在纯 [ActionPrecheck] 里，JVM 可测。
 * - **来源标记**：模块动作在下发前置位，只有“目标状态匹配 + 短窗口内”的状态变化被
 *   标记为 [ActionSource.MODULE]（纯 [TelecomDecisions.claimSource]）；其余 ACTIVE 变化
 *   视为人工接听，未知状态不猜测。
 * - **未知状态不猜测**：Telecom 的未映射瞬时状态（NEW/DIALING/CONNECTING/
 *   SELECT_PHONE_ACCOUNT/AUDIO_PROCESSING…）上报为 [EventKind.UNKNOWN_STATE]，
 *   注册表保留上一次已知状态，既不猜成 ACTIVE 也不误撤销待执行接听。
 * - **路由变化在本进程内上报**：Call.Callback 的音频路由回调是 @SystemApi（公开 SDK
 *   不可见但运行时存在），按 JVM 签名声明即可被框架虚分派；HookRuntime 另外 Hook 了
 *   InCallService 上的公开同名回调。两条路径都上报 [EventKind.ROUTE_CHANGED]，
 *   来源不明时由注册表保守交还，不依赖 Task 5 的桥接进程。
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

    /**
     * 会话绑定是否已被注册表撤销。撤销后排队中的 answer/disconnect 一律拒绝，
     * 绝不会在会话被移除后仍然调用 Telecom（TOCTOU）。
     */
    @Volatile
    private var revoked: Boolean = false

    /** 只在主线程读写：模块动作的待认领标记。 */
    private var pendingAction: PendingAction? = null

    private data class PendingAction(val expectedCallState: Int, val atNs: Long)

    private fun callState(): Int = callStateOf(call)

    private fun callStateOf(call: Call): Int = call.details?.state ?: Call.STATE_NEW

    override fun bindSession(id: SessionId) {
        boundSession = id
        revoked = false
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
        val mappedState = TelecomDecisions.mapSnapshotState(state)
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
            // 注册表移除会话后必须立刻撤销绑定：排队中的主线程动作不能落到已结束的会话上。
            revoked = true
            boundSession = null
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
        val dispatchSession = boundSession
        precheck(requireRinging = true, dispatchSession = dispatchSession)?.let { return it }
        onMain {
            if (precheck(requireRinging = true, dispatchSession = dispatchSession) != null) {
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
     * 这里再做一次代次/撤销/状态防线）。
     */
    override fun disconnect(): CommandResult {
        val dispatchSession = boundSession
        precheck(requireRinging = false, dispatchSession = dispatchSession)?.let { return it }
        onMain {
            if (precheck(requireRinging = false, dispatchSession = dispatchSession) != null) {
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

    /**
     * 音频路由变化上报入口：Call.Callback 的隐藏回调与 InCallService 的公开回调都走这里。
     * 模块从不主动改路由，因此来源一律是 [ActionSource.UNKNOWN]（来源不明 → 保守交还）。
     */
    internal fun notifyRouteChanged(reason: String) {
        if (destroyed) return
        deliver(HandleEvent(snapshot(), ActionSource.UNKNOWN, EventKind.ROUTE_CHANGED, reason))
    }

    // ------------------------------------------------------------------ 核验

    /** 返回 null 表示可以继续；非 null 是同步拒绝/失败结果。 */
    private fun precheck(requireRinging: Boolean, dispatchSession: SessionId?): CommandResult? {
        val state = try {
            callState()
        } catch (failure: Throwable) {
            null
        }
        return ActionPrecheck.check(
            requireRinging = requireRinging,
            revoked = revoked,
            dispatchSession = dispatchSession,
            boundSession = boundSession,
            processEpoch = processEpoch,
            telecomState = state,
        )
    }

    // ------------------------------------------------------------------ Telecom 回调

    private val telecomCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val claim = TelecomDecisions.claimSource(
                telecomState = state,
                pendingTargetState = pendingAction?.expectedCallState,
                pendingAtNs = pendingAction?.atNs,
                nowNs = System.nanoTime(),
            )
            if (claim.clearPending) pendingAction = null
            val mapped = TelecomDecisions.mapEventState(state)
            if (mapped == null) {
                // 未映射的瞬时状态：不猜测，交给注册表保留上一次已知状态。
                deliver(
                    HandleEvent(
                        snapshot = snapshot(),
                        source = claim.source,
                        kind = EventKind.UNKNOWN_STATE,
                        reason = "telecom_state_unknown_" + state,
                    ),
                )
                return
            }
            // 用回调给出的真实状态覆盖快照，避免 Call.getState() 与回调到达顺序不一致。
            deliver(
                HandleEvent(
                    snapshot = snapshot().copy(state = mapped),
                    source = claim.source,
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

        /**
         * 音频路由变化（API 23+）。该方法在公开 SDK 中是 @SystemApi，因此**不能**写
         * `override`；运行时父类存在同签名方法，按 JVM 签名声明即可被框架虚分派。
         */
        @Suppress("unused")
        fun onCallAudioStateChanged(call: Call?, audioState: CallAudioState?) {
            notifyRouteChanged("audio_route_changed")
        }

        /**
         * 当前通话端点变化（API 34+）。同为 @SystemApi，理由同上。
         */
        @Suppress("unused")
        fun onCallEndpointChanged(call: Call?, endpoint: CallEndpoint?) {
            notifyRouteChanged("call_endpoint_changed")
        }
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
}

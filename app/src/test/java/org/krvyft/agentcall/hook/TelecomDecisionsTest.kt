package org.krvyft.agentcall.hook

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/**
 * Telecom 状态映射、模块动作来源认领与两阶段核验的 JVM 测试。
 *
 * 这里断言的是从 AndroidCallHandle 抽出的纯逻辑：未知/瞬时状态不猜测、只有“目标状态
 * 匹配 + 短窗口内”的模块动作才认领 MODULE、核验失败绝不下发 Telecom 动作。
 */
class TelecomDecisionsTest {

    // ------------------------------------------------------------------ 状态映射

    @Test
    fun knownTelecomStatesMapToContractStates() {
        assertEquals(CallState.RINGING, TelecomDecisions.mapEventState(Call.STATE_RINGING))
        assertEquals(CallState.HOLDING, TelecomDecisions.mapEventState(Call.STATE_HOLDING))
        assertEquals(CallState.ACTIVE, TelecomDecisions.mapEventState(Call.STATE_ACTIVE))
        assertEquals(CallState.DISCONNECTED, TelecomDecisions.mapEventState(Call.STATE_DISCONNECTED))
        assertEquals(CallState.DISCONNECTED, TelecomDecisions.mapEventState(Call.STATE_DISCONNECTING))
    }

    @Test
    fun transientStatesAreNeverReportedAsActiveEvents() {
        val transient = intArrayOf(
            Call.STATE_NEW,
            Call.STATE_DIALING,
            Call.STATE_SELECT_PHONE_ACCOUNT,
            Call.STATE_CONNECTING,
            Call.STATE_PULLING_CALL,
            Call.STATE_AUDIO_PROCESSING,
            Call.STATE_SIMULATED_RINGING,
        )
        for (state in transient) {
            assertNull("state " + state + " must stay unknown", TelecomDecisions.mapEventState(state))
        }
    }

    @Test
    fun unlistedFutureStateIsTreatedAsUnknown() {
        assertNull(TelecomDecisions.mapEventState(9999))
        assertNull(TelecomDecisions.mapEventState(-1))
        // 快照侧仍然保守映射成 ACTIVE（不可接听），绝不猜成 RINGING。
        assertEquals(CallState.ACTIVE, TelecomDecisions.mapSnapshotState(9999))
        assertEquals(CallState.RINGING, TelecomDecisions.mapSnapshotState(Call.STATE_RINGING))
    }

    // ------------------------------------------------------------------ 来源认领

    @Test
    fun moduleAnswerIsClaimedOnlyForItsTargetStateInsideTheWindow() {
        val claim = TelecomDecisions.claimSource(
            telecomState = Call.STATE_ACTIVE,
            pendingTargetState = Call.STATE_ACTIVE,
            pendingAtNs = 1_000L,
            nowNs = 1_000L + 1_000_000L,
        )
        assertEquals(ActionSource.MODULE, claim.source)
        assertEquals(true, claim.clearPending)
    }

    @Test
    fun moduleHangupIsClaimedForDisconnected() {
        val claim = TelecomDecisions.claimSource(
            telecomState = Call.STATE_DISCONNECTED,
            pendingTargetState = Call.STATE_DISCONNECTED,
            pendingAtNs = 1_000L,
            nowNs = 2_000L,
        )
        assertEquals(ActionSource.MODULE, claim.source)
        assertEquals(true, claim.clearPending)
    }

    @Test
    fun interveningTransientStateKeepsTheModuleClaimPending() {
        val claim = TelecomDecisions.claimSource(
            telecomState = Call.STATE_AUDIO_PROCESSING,
            pendingTargetState = Call.STATE_ACTIVE,
            pendingAtNs = 1_000L,
            nowNs = 2_000L,
        )
        assertEquals(ActionSource.UNKNOWN, claim.source)
        assertEquals("an intervening transient state must not drop the module claim", false, claim.clearPending)
    }

    @Test
    fun userAnswerIsNotClaimedAsModuleWhenTheTargetDoesNotMatch() {
        // 模块下发的是挂断（目标 DISCONNECTED），此时到达的 ACTIVE 只能是人工接听。
        val claim = TelecomDecisions.claimSource(
            telecomState = Call.STATE_ACTIVE,
            pendingTargetState = Call.STATE_DISCONNECTED,
            pendingAtNs = 1_000L,
            nowNs = 2_000L,
        )
        assertEquals(ActionSource.USER, claim.source)
        assertEquals(false, claim.clearPending)
    }

    @Test
    fun expiredWindowDropsTheModuleClaim() {
        val claim = TelecomDecisions.claimSource(
            telecomState = Call.STATE_ACTIVE,
            pendingTargetState = Call.STATE_ACTIVE,
            pendingAtNs = 1_000L,
            nowNs = 1_000L + TelecomDecisions.MODULE_ACTION_WINDOW_NS + 1L,
        )
        assertEquals(ActionSource.USER, claim.source)
        assertEquals(true, claim.clearPending)
    }

    @Test
    fun withoutAPendingModuleActionOnlyActiveIsTreatedAsUser() {
        assertEquals(
            ActionSource.USER,
            TelecomDecisions.claimSource(Call.STATE_ACTIVE, null, null, 0L).source,
        )
        assertEquals(
            ActionSource.UNKNOWN,
            TelecomDecisions.claimSource(Call.STATE_HOLDING, null, null, 0L).source,
        )
        assertEquals(
            ActionSource.UNKNOWN,
            TelecomDecisions.claimSource(Call.STATE_AUDIO_PROCESSING, null, null, 0L).source,
        )
    }

    // ------------------------------------------------------------------ 两阶段核验

    @Test
    fun ringingAnswerForTheBoundSessionPasses() {
        assertNull(
            ActionPrecheck.check(
                requireRinging = true,
                revoked = false,
                dispatchSession = session,
                boundSession = session,
                processEpoch = EPOCH,
                telecomState = Call.STATE_RINGING,
            ),
        )
    }

    @Test
    fun revokedOrUnboundSessionIsStale() {
        assertEquals(
            rejected(RejectReason.STALE_SESSION),
            ActionPrecheck.check(true, true, session, session, EPOCH, Call.STATE_RINGING),
        )
        assertEquals(
            rejected(RejectReason.STALE_SESSION),
            ActionPrecheck.check(true, false, session, null, EPOCH, Call.STATE_RINGING),
        )
    }

    @Test
    fun sessionReboundAfterDispatchIsStale() {
        // TOCTOU：命令排队后被注册表移除/重新绑定，主线程核验必须拒绝，绝不触碰 Telecom。
        val rebound = session.copy(callId = "call-2")
        assertEquals(
            rejected(RejectReason.STALE_SESSION),
            ActionPrecheck.check(true, false, session, rebound, EPOCH, Call.STATE_RINGING),
        )
    }

    @Test
    fun staleProcessEpochIsStale() {
        val stale = session.copy(processEpoch = "epoch-from-a-dead-process")
        assertEquals(
            rejected(RejectReason.STALE_SESSION),
            ActionPrecheck.check(true, false, stale, stale, EPOCH, Call.STATE_RINGING),
        )
    }

    @Test
    fun unavailableCallStateFailsClosed() {
        assertEquals(
            CommandResult.Failed(
                ActionPrecheck.CODE_STATE_UNAVAILABLE,
                ActionPrecheck.MESSAGE_STATE_UNAVAILABLE,
            ),
            ActionPrecheck.check(true, false, session, session, EPOCH, null),
        )
    }

    @Test
    fun answerRequiresRingingAndHangupRequiresALiveCall() {
        assertEquals(
            rejected(RejectReason.NOT_RINGING),
            ActionPrecheck.check(true, false, session, session, EPOCH, Call.STATE_ACTIVE),
        )
        assertEquals(
            rejected(RejectReason.NOT_RINGING),
            ActionPrecheck.check(false, false, session, session, EPOCH, Call.STATE_DISCONNECTING),
        )
        assertNull(
            ActionPrecheck.check(false, false, session, session, EPOCH, Call.STATE_ACTIVE),
        )
    }

    private fun rejected(reason: RejectReason): CommandResult = CommandResult.Rejected(reason)

    private companion object {
        const val EPOCH = "epoch-1"
        val session = SessionId(processEpoch = EPOCH, callId = "call-1")
    }
}
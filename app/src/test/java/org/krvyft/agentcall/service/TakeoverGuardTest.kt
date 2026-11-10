package org.krvyft.agentcall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState

/**
 * 人工接管请求的裁决（Task 7B2）。
 *
 * 通知里的接管 action 绑定的是**生成该通知时的 SessionId**；旧通知绝不能控制新通话。
 * 裁决只看会话身份与当前状态，不依赖 UI 文案，也不读取任何号码。
 */
class TakeoverGuardTest {

    private val old = SessionId(processEpoch = "epoch-1", callId = "call-a")
    private val current = SessionId(processEpoch = "epoch-2", callId = "call-b")

    @Test
    fun takeoverOfTheCurrentSessionIsAccepted() {
        assertEquals(
            TakeoverDecision.Accepted,
            TakeoverGuard.evaluate(old, old, SessionState.ACTIVE_AI),
        )
        assertEquals(
            TakeoverDecision.Accepted,
            TakeoverGuard.evaluate(old, old, SessionState.PREPARING),
        )
        assertEquals(
            TakeoverDecision.Accepted,
            TakeoverGuard.evaluate(old, old, SessionState.HANDOFF),
        )
        assertEquals(
            TakeoverDecision.Accepted,
            TakeoverGuard.evaluate(old, old, SessionState.ELIGIBLE),
        )
    }

    @Test
    fun anOldNotificationCannotTakeOverANewerCall() {
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_STALE_SESSION),
            TakeoverGuard.evaluate(old, current, SessionState.ACTIVE_AI),
        )
    }

    @Test
    fun aNotificationWithoutASessionIsRejected() {
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_SESSION_REQUIRED),
            TakeoverGuard.evaluate(null, current, SessionState.ACTIVE_AI),
        )
    }

    @Test
    fun aNotificationForAnEndedCallIsRejected() {
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_NOT_TAKEOVER_ABLE),
            TakeoverGuard.evaluate(old, old, SessionState.ENDED),
        )
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_NOT_TAKEOVER_ABLE),
            TakeoverGuard.evaluate(old, old, SessionState.ENDING),
        )
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_NOT_TAKEOVER_ABLE),
            TakeoverGuard.evaluate(old, old, null),
        )
    }

    @Test
    fun aNotificationWithNoActiveCallIsRejected() {
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_NO_ACTIVE_CALL),
            TakeoverGuard.evaluate(old, null, SessionState.ACTIVE_AI),
        )
    }

    // ---------------------------------------------------------------- 通知载荷

    @Test
    fun takeoverTargetRoundTripsTheSessionIdentity() {
        val target = TakeoverTarget.of(old)
        val data = TakeoverTarget.encode(target)
        assertEquals(old, TakeoverTarget.parse(data))
        assertTrue(data.contains(old.processEpoch))
        assertTrue(data.contains(old.callId))
    }

    @Test
    fun differentSessionsProduceDifferentNotificationData() {
        val first = TakeoverTarget.encode(TakeoverTarget.of(old))
        val second = TakeoverTarget.encode(TakeoverTarget.of(current))
        assertNotEquals(first, second)
        assertNotEquals(TakeoverTarget.requestCode(TakeoverTarget.of(old)), TakeoverTarget.requestCode(TakeoverTarget.of(current)))
    }

    @Test
    fun theSameSessionProducesStableNotificationData() {
        assertEquals(TakeoverTarget.encode(TakeoverTarget.of(old)), TakeoverTarget.encode(TakeoverTarget.of(old)))
        assertEquals(TakeoverTarget.requestCode(TakeoverTarget.of(old)), TakeoverTarget.requestCode(TakeoverTarget.of(old)))
    }

    @Test
    fun malformedNotificationDataIsRejected() {
        assertNull(TakeoverTarget.parse(""))
        assertNull(TakeoverTarget.parse("agentcall://takeover"))
        assertNull(TakeoverTarget.parse("agentcall://takeover/epoch-1"))
        assertNull(TakeoverTarget.parse("agentcall://takeover/epoch-1/call-a/extra"))
        assertNull(TakeoverTarget.parse("http://takeover/epoch-1/call-a"))
        assertNull(TakeoverTarget.parse("agentcall://takeover//call-a"))
    }

    @Test
    fun controlCharactersAreRefusedInTheSessionIdentity() {
        val hostile = SessionId(processEpoch = "epoch\n1", callId = "call-a")
        assertNull(TakeoverTarget.parse(TakeoverTarget.encode(TakeoverTarget.of(hostile))))
    }

    @Test
    fun anOldNotificationTargetIsRejectedAgainstTheNewerActiveSession() {
        // 端到端：通知里存的 SessionId 解析出来后，必须与当前活动会话核验。
        val staleData = TakeoverTarget.encode(TakeoverTarget.of(old))
        val parsed = TakeoverTarget.parse(staleData)
        assertEquals(old, parsed)
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_STALE_SESSION),
            TakeoverGuard.evaluate(parsed, current, SessionState.ACTIVE_AI),
        )
    }
}

package org.krvyft.agentcall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.krvyft.agentcall.core.model.SessionId

/**
 * 人工交还结果的会话绑定（Task 7B2 FIX ROUND 1，spec §8）。
 *
 * 旧实现把 lastHandback 当成全局字符串，于是 A 通话的交还成功会显示在 B 通话的接管页上；
 * 这里锁定三条规则：
 * - 报告必须绑定产生它的 SessionId，别的会话永远读不到；
 * - 新会话的第一个状态就清掉上一通电话的残留；
 * - 撤销未确认必须表达为失败（不是成功，也不是“尚未提交接管”）。
 */
class HandbackJournalTest {

    private val sessionA = SessionId(processEpoch = "epoch-1", callId = "call-a")
    private val sessionB = SessionId(processEpoch = "epoch-2", callId = "call-b")

    @Test
    fun aHandbackReportIsNeverReadableFromAnotherSession() {
        val report = HandbackJournal.record(sessionA, RevokeOutcome.CONFIRMED)
        assertEquals(sessionA, report.session)
        assertEquals(HandbackJournal.CONFIRMED, report.code)
        assertSame(report, HandbackJournal.forSession(report, sessionA))
        assertNull(HandbackJournal.forSession(report, sessionB))
        assertNull(HandbackJournal.forSession(report, null))
    }

    @Test
    fun aNewSessionClearsThePreviousCallsOutcome() {
        val report = HandbackJournal.record(sessionA, RevokeOutcome.CONFIRMED)
        // 新会话 B 的第一个状态（ELIGIBLE）就清掉 A 的交还结果。
        assertNull(HandbackJournal.onStateChanged(report, sessionB))
        // 同一会话的后续状态（含 ENDED）保留结果，供接管页显示最终结论。
        assertSame(report, HandbackJournal.onStateChanged(report, sessionA))
        assertSame(report, HandbackJournal.onStateChanged(report, sessionA))
    }

    @Test
    fun outcomesMapToTheFourMachineCodes() {
        assertEquals(
            HandbackJournal.CONFIRMED,
            HandbackJournal.record(sessionA, RevokeOutcome.CONFIRMED).code,
        )
        assertEquals(
            HandbackJournal.NOT_OWNED,
            HandbackJournal.record(sessionA, RevokeOutcome.NOT_OWNED).code,
        )
        assertEquals(
            HandbackJournal.UNCONFIRMED,
            HandbackJournal.record(sessionA, RevokeOutcome.REVOKE_FAILED).code,
        )
        assertEquals(
            HandbackJournal.UNCONFIRMED,
            HandbackJournal.record(sessionA, RevokeOutcome.UNAVAILABLE).code,
        )
    }

    @Test
    fun aConfirmedHandbackIsOnlySuccessForItsOwnSession() {
        val report = HandbackJournal.record(sessionA, RevokeOutcome.CONFIRMED)
        val forA = HandbackJournal.outcomeFor(
            AgentCallStatus(activeSession = sessionA, lastHandback = report),
            sessionA,
        )
        assertEquals(HandbackOutcome.HandedBack(report), forA)

        // 旧页面的目标不是报告所属会话：绝不能显示上一通的“已交还成功”。
        val forB = HandbackJournal.outcomeFor(
            AgentCallStatus(activeSession = sessionB, lastHandback = report),
            sessionB,
        )
        assertEquals(HandbackOutcome.Pending, forB)
    }

    @Test
    fun aRevokeFailureIsUnconfirmedInsteadOfSuccessOrPending() {
        val report = HandbackJournal.record(sessionA, RevokeOutcome.REVOKE_FAILED)
        val outcome = HandbackJournal.outcomeFor(
            AgentCallStatus(activeSession = sessionA, lastHandback = report),
            sessionA,
        )
        assertEquals(HandbackOutcome.Unconfirmed(report), outcome)
        assertEquals(false, outcome is HandbackOutcome.HandedBack)
        assertEquals(false, outcome == HandbackOutcome.Pending)
    }

    @Test
    fun anErrorIsOnlyShownWhenNoHandbackBelongsToThisSession() {
        val report = HandbackJournal.record(sessionA, RevokeOutcome.CONFIRMED)
        val failed = HandbackJournal.outcomeFor(
            AgentCallStatus(activeSession = sessionB, lastHandback = report, lastError = "takeover_stale_session"),
            sessionB,
        )
        assertEquals(HandbackOutcome.Failed("takeover_stale_session"), failed)
        assertEquals(
            HandbackOutcome.Pending,
            HandbackJournal.outcomeFor(AgentCallStatus(activeSession = sessionB), sessionB),
        )
    }
}

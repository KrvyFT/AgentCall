package org.krvyft.agentcall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState

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
    fun anErrorIsOnlyShownForTheSessionItBelongsTo() {
        val report = HandbackJournal.record(sessionA, RevokeOutcome.CONFIRMED)
        // 上一通电话的撤销失败/音频失败：错误属于 A，B 的接管页绝不能显示。
        val previousError = AgentCallStatus(
            activeSession = sessionB,
            lastHandback = report,
            lastError = "handback_revoke_failed",
            lastErrorSession = sessionA,
        )
        assertEquals(HandbackOutcome.Pending, HandbackJournal.outcomeFor(previousError, sessionB))
        // 服务级错误没有会话归属：不能被当作某通电话的“接管失败”。
        assertEquals(
            HandbackOutcome.Pending,
            HandbackJournal.outcomeFor(
                AgentCallStatus(activeSession = sessionB, lastError = "bootstrap_failed"),
                sessionB,
            ),
        )
        // 本会话的错误仍然显示（撤销失败的报告本身走 Unconfirmed 分支，不会落到这里）。
        assertEquals(
            HandbackOutcome.Failed("takeover_stale_session"),
            HandbackJournal.outcomeFor(
                AgentCallStatus(
                    activeSession = sessionB,
                    lastError = "takeover_stale_session",
                    lastErrorSession = sessionB,
                ),
                sessionB,
            ),
        )
        assertEquals(
            HandbackOutcome.Pending,
            HandbackJournal.outcomeFor(AgentCallStatus(activeSession = sessionB), sessionB),
        )
    }

    @Test
    fun aNewCallClearsThePreviousCallsReportAndError() {
        val previous = AgentCallStatus(
            activeSession = sessionA,
            lastHandback = HandbackJournal.record(sessionA, RevokeOutcome.CONFIRMED),
            lastError = "handback_revoke_failed",
            lastErrorSession = sessionA,
        )

        val residue = HandbackJournal.residueOnStateChanged(previous, sessionB, SessionState.ELIGIBLE)

        assertNull(residue.report)
        assertNull(residue.error)
        assertNull(residue.errorSession)
    }

    @Test
    fun theSameCallsFinalOutcomeSurvivesItsOwnEndedState() {
        val report = HandbackJournal.record(sessionA, RevokeOutcome.REVOKE_FAILED)
        val status = AgentCallStatus(
            activeSession = sessionA,
            lastHandback = report,
            lastError = "handback_revoke_failed",
            lastErrorSession = sessionA,
        )

        val residue = HandbackJournal.residueOnStateChanged(status, sessionA, SessionState.ENDED)

        assertSame(report, residue.report)
        assertEquals("handback_revoke_failed", residue.error)
        assertEquals(sessionA, residue.errorSession)
    }

    @Test
    fun anotherSessionsErrorIsDroppedEvenMidCall() {
        val status = AgentCallStatus(
            activeSession = sessionB,
            lastError = "handback_revoke_failed",
            lastErrorSession = sessionA,
        )

        val residue = HandbackJournal.residueOnStateChanged(status, sessionB, SessionState.ACTIVE_AI)

        assertNull(residue.error)
        assertNull(residue.errorSession)
    }

    // ------------------------------------------- 事件流诊断 ≠ 交还失败（7B3 FIX ROUND 1）

    /**
     * IMPORTANT 3：轮询器/事件流的机器码（session_event_incomplete、bridge_unavailable、…）
     * 既不再写进 lastError，也绝不能被 [HandbackJournal.outcomeFor] 解释成“这通电话接管失败”
     * ——那会让接管页显示“交还失败: session_event_incomplete”，并在恢复后继续残留。
     */
    @Test
    fun anEventFeedDiagnosticIsNeverAHandbackFailure() {
        for (code in SessionEventPoller.DIAGNOSTIC_ONLY_CODES) {
            assertEquals(
                "poller code must not become a handback failure: " + code,
                HandbackOutcome.Pending,
                HandbackJournal.outcomeFor(
                    AgentCallStatus(
                        activeSession = sessionA,
                        lastError = code,
                        lastErrorSession = sessionA,
                    ),
                    sessionA,
                ),
            )
        }
        // 真正的交接错误仍然显示。
        assertEquals(
            HandbackOutcome.Failed("handback_revoke_failed"),
            HandbackJournal.outcomeFor(
                AgentCallStatus(
                    activeSession = sessionA,
                    lastError = "handback_revoke_failed",
                    lastErrorSession = sessionA,
                ),
                sessionA,
            ),
        )
    }

    /** 诊断码必须在状态变化时立即被丢弃（服务恢复后不得残留）。 */
    @Test
    fun aDiagnosticCodeIsDroppedFromTheResidueOnAnyStateChange() {
        val status = AgentCallStatus(
            activeSession = sessionA,
            lastError = SessionEventPoller.ERROR_POLL_FAILED,
        )

        val residue = HandbackJournal.residueOnStateChanged(status, sessionA, SessionState.ACTIVE_AI)

        assertNull(residue.error)
        assertNull(residue.errorSession)
    }
}

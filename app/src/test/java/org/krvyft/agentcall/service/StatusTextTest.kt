package org.krvyft.agentcall.service

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.krvyft.agentcall.core.model.SessionState

/**
 * 机器码 → 文案的资源映射（Task 7B2 FIX ROUND 1）。
 *
 * 旧实现把中文写死在 StatusText.kt 里，与“全部文案在 strings.xml”的报告声明不符。
 * 本测试锁定：每个已知机器码都有资源、未知码保持原文（绝不粉饰成“正常”）。
 */
class StatusTextTest {

    @Test
    fun everyBlockerCodeHasAStringResource() {
        for (blocker in BootstrapBlocker.entries) {
            assertNotNull(blocker.code, StatusText.blockerRes(blocker.code))
        }
        assertNull(StatusText.blockerRes("unknown_blocker"))
    }

    @Test
    fun everySessionStateAndTheNoSessionCaseHaveAResource() {
        for (state in SessionState.entries) {
            assertNotNull(state.name, StatusText.sessionStateRes(state))
        }
        assertNotNull(StatusText.sessionStateRes(null))
    }

    @Test
    fun everyHookPresenceHasAResource() {
        for (presence in HookPresence.entries) {
            assertNotNull(presence.name, StatusText.hookPresenceRes(presence))
        }
    }

    @Test
    fun handbackCodesMapAndUnknownCodesStayRaw() {
        assertNotNull(StatusText.handbackRes(HandbackJournal.CONFIRMED))
        assertNotNull(StatusText.handbackRes(HandbackJournal.NOT_OWNED))
        assertNotNull(StatusText.handbackRes(HandbackJournal.UNCONFIRMED))
        assertNull(StatusText.handbackRes("handback_unknown"))
        // 未确认必须有自己的资源，避免撤销失败被显示成成功。
        assertNotNull(StatusText.handbackRes(HandbackJournal.UNCONFIRMED))
    }

    @Test
    fun takeoverRejectionCodesMapAndUnknownCodesStayRaw() {
        val known = listOf(
            TakeoverGuard.ERROR_SESSION_REQUIRED,
            TakeoverGuard.ERROR_NO_ACTIVE_CALL,
            TakeoverGuard.ERROR_STALE_SESSION,
            TakeoverGuard.ERROR_NOT_TAKEOVER_ABLE,
        )
        for (code in known) assertNotNull(code, StatusText.takeoverRejectionRes(code))
        assertNull(StatusText.takeoverRejectionRes("takeover_other"))
    }
}

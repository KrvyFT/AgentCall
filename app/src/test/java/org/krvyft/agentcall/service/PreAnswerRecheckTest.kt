package org.krvyft.agentcall.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState

/**
 * 接听前的最后一次就绪复检（Task 7B2 FIX ROUND 1，spec §8）。
 *
 * READY 每 15 s 复检一次，3 s 倒计时可能在两次复检之间用旧事实到期；这里必须在倒计时
 * 末尾重新读一次**本地**事实，未就绪就在到期前投递 ReadyChanged(false)。
 * 复检只允许下发 false（true 只由就绪循环决定），且必须有界（探针挂起也不能拖过倒计时）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreAnswerRecheckTest {

    private val sessionA = SessionId(processEpoch = "epoch-1", callId = "call-a")
    private val sessionB = SessionId(processEpoch = "epoch-2", callId = "call-b")

    @Test
    fun anUnreadyRecheckIsPublishedBeforeTheAnswerDeadline() = runTest {
        val publishedAt = ArrayList<Long>()
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = { false },
            publish = { ready ->
                assertFalse(ready)
                publishedAt += testScheduler.currentTime
            },
        )
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(1, publishedAt.size)
        assertTrue("publishedAt=" + publishedAt, publishedAt[0] <= 3_000L)
        // 必须真的等到倒计时后段，而不是刚进 PREPARING 就复检。
        assertTrue("publishedAt=" + publishedAt, publishedAt[0] >= 2_000L)
    }

    @Test
    fun aReadyRecheckPublishesNothing() = runTest {
        val published = ArrayList<Boolean>()
        var probes = 0
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = {
                probes++
                true
            },
            publish = { published += it },
        )
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(1, probes)
        assertTrue(published.isEmpty())
    }

    @Test
    fun leavingPreparingCancelsThePendingRecheck() = runTest {
        var probes = 0
        val published = ArrayList<Boolean>()
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = {
                probes++
                false
            },
            publish = { published += it },
        )
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(1_000L)
        // 人工接管/断开来得及：状态先离开 PREPARING，旧复检必须作废。
        gate.onStateChanged(sessionA, SessionState.ANSWER_PENDING, answerDelayMs = 3_000L)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(0, probes)
        assertTrue(published.isEmpty())
    }

    @Test
    fun aHangingProbeFailsClosedWithinTheCountdown() = runTest {
        val publishedAt = ArrayList<Long>()
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = { awaitCancellation() },
            publish = { publishedAt += testScheduler.currentTime },
        )
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(1, publishedAt.size)
        assertTrue("publishedAt=" + publishedAt, publishedAt[0] <= 3_000L)
    }

    @Test
    fun aNewSessionSupersedesThePendingRecheck() = runTest {
        var publications = 0
        var probes = 0
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = {
                probes++
                false
            },
            publish = { publications++ },
        )
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(500L)
        gate.onStateChanged(sessionB, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(3_000L)
        runCurrent()

        // A 的复检被取消：只复检一次（B），只投递一次 false。
        assertEquals(1, probes)
        assertEquals(1, publications)
    }
}

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

    // ------------------------------------------------------------ 生产节奏：1 Hz Tick

    /**
     * SessionCoordinator 在 PREPARING 期间每秒调用一次 observer.onStateChanged（Tick），状态始终是
     * PREPARING。旧实现每次回调都 cancel + 重新安排，于是复检被无限推迟到倒计时结束之后
     * （3 s 倒计时本应在 2.25 s 复检，但 tick 每 1 s 重排一次），生产上永不触发。
     */
    @Test
    fun oneHertzTicksNeitherMoveNorRepeatTheRecheck() = runTest {
        val publishedAt = ArrayList<Long>()
        var probes = 0
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = {
                probes++
                false
            },
            publish = { publishedAt += testScheduler.currentTime },
        )
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)

        // 1000/2000 ms 的两次 1 Hz tick（reducer 的 Tick 也会触发 observer.onStateChanged）。
        advanceTimeBy(1_000L)
        runCurrent()
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(1_000L)
        runCurrent()
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        assertEquals("tick callbacks must not probe before the guard", 0, probes)

        // 3000 - 750 = 2250 ms：必须按原窗口触发，而不是被 tick 推到 4250 ms。
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(listOf(2_250L), publishedAt)

        // 触发之后的 tick 也不得再安排第二次复检。
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals("exactly one recheck per session", 1, probes)
        assertEquals(1, publishedAt.size)
    }

    /**
     * 配置热更新（revision 变化）不得移动已经安排的窗口：复检必须用本会话冻结的倒计时，
     * 而不是最新配置。否则 3 s 的真实到期会被推迟到 10 s 之后。
     */
    @Test
    fun aNewerConfigRevisionCannotMoveTheArmedCountdown() = runTest {
        val publishedAt = ArrayList<Long>()
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = { false },
            publish = { publishedAt += testScheduler.currentTime },
        )
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)

        advanceTimeBy(1_000L)
        runCurrent()
        // 服务刷新配置后传进来的是最新 revision（10000 ms），本会话仍然只倒计时 3000 ms。
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 10_000L)
        advanceTimeBy(1_000L)
        runCurrent()
        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 10_000L)

        advanceTimeBy(250L)
        runCurrent()
        assertEquals(listOf(2_250L), publishedAt)

        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals("the later revision must never arm a second window", 1, publishedAt.size)
    }

    /**
     * 真实倒计时区间（core 的 2000..30000 ms）：每个 delay 都必须在 delay - guard
     * 投递一次 false，且投递时刻仍在到期之前。
     */
    @Test
    fun everyRealisticCountdownPublishesAtItsGuard() = runTest {
        val delays = listOf(2_000L, 3_000L, 10_000L, 30_000L)
        for ((index, delay) in delays.withIndex()) {
            val publishedAt = ArrayList<Long>()
            val gate = PreAnswerRecheck(
                scope = this,
                isReady = { false },
                publish = { publishedAt += testScheduler.currentTime },
            )
            val startedAt = testScheduler.currentTime
            gate.onStateChanged(
                SessionId(processEpoch = "epoch-" + index, callId = "call-" + index),
                SessionState.PREPARING,
                answerDelayMs = delay,
            )
            advanceTimeBy(delay)
            runCurrent()

            assertEquals("delay=" + delay, listOf(startedAt + delay - 750L), publishedAt)
            assertTrue("delay=" + delay, publishedAt[0] <= startedAt + delay)
        }
    }

    /** ELIGIBLE 期间 READY 迟到、随后才进入 PREPARING：倒计时从进入 PREPARING 起算。 */
    @Test
    fun theCountdownIsArmedWhenPreparingStartsAfterEligible() = runTest {
        val publishedAt = ArrayList<Long>()
        val gate = PreAnswerRecheck(
            scope = this,
            isReady = { false },
            publish = { publishedAt += testScheduler.currentTime },
        )
        gate.onStateChanged(sessionA, SessionState.ELIGIBLE, answerDelayMs = 3_000L)
        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue("no recheck may run before PREPARING", publishedAt.isEmpty())

        gate.onStateChanged(sessionA, SessionState.PREPARING, answerDelayMs = 3_000L)
        advanceTimeBy(2_249L)
        runCurrent()
        assertTrue("must not fire early", publishedAt.isEmpty())
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf(3_750L), publishedAt)
    }
}

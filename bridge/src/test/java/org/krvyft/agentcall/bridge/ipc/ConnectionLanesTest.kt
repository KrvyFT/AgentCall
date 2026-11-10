package org.krvyft.agentcall.bridge.ipc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制面与 PCM 的隔离测试（spec §6.4：PCM 不得饿死控制命令）。
 *
 * PCM 是长生命周期连接（一条连接持续到通话结束，最长 180 s）；如果它和
 * 控制请求共用 worker 池，两条 PCM 流就会占满 2 个 worker，shutdown/人工交还
 * 等控制请求只能等到通话结束。这里用纯 JVM 证明两条 lane 的执行资源互不占用。
 */
class ConnectionLanesTest {

    private val lanes = ConnectionLanes(
        controlWorkers = 2,
        controlQueueDepth = 4,
        maxControlConnections = 4,
        maxPcmConnections = 2,
    )

    @After
    fun tearDown() {
        lanes.close()
    }

    @Test
    fun controlRequestIsServedWhilePcmConnectionsAreActive() {
        val pcmStarted = CountDownLatch(2)
        val releasePcm = CountDownLatch(1)
        repeat(2) {
            assertTrue(
                lanes.submitPcm {
                    pcmStarted.countDown()
                    releasePcm.await(10, TimeUnit.SECONDS)
                },
            )
        }
        assertTrue("PCM connections must start", pcmStarted.await(5, TimeUnit.SECONDS))

        val controlDone = CountDownLatch(1)
        assertTrue(lanes.submitControl { controlDone.countDown() })

        assertTrue(
            "control request must be served while PCM streams are active",
            controlDone.await(2, TimeUnit.SECONDS),
        )
        releasePcm.countDown()
    }

    @Test
    fun pcmLaneHasItsOwnBoundedCapacityAndDoesNotConsumeControlSlots() {
        val pcmStarted = CountDownLatch(2)
        val releasePcm = CountDownLatch(1)
        repeat(2) {
            assertTrue(
                lanes.submitPcm {
                    pcmStarted.countDown()
                    releasePcm.await(10, TimeUnit.SECONDS)
                },
            )
        }
        assertTrue(pcmStarted.await(5, TimeUnit.SECONDS))

        // PCM 达到显式上限：第 3 条立即有界失败，不排队等待。
        assertFalse(lanes.submitPcm { })
        assertEquals(1L, lanes.rejectedSubmissions)
        // 控制 lane 仍可接受并立即执行；PCM 上限没有占用控制槽位。
        val controlDone = CountDownLatch(1)
        assertTrue(lanes.submitControl { controlDone.countDown() })
        assertTrue(controlDone.await(2, TimeUnit.SECONDS))
        releasePcm.countDown()
    }

    @Test
    fun controlSubmissionAfterCloseIsBoundedFailure() {
        lanes.close()

        assertFalse(lanes.submitControl { })
        assertFalse(lanes.submitPcm { })
    }
}

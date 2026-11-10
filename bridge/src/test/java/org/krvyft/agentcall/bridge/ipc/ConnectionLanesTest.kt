package org.krvyft.agentcall.bridge.ipc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.protocol.RolePolicy

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
        assertTrue(submitControl { controlDone.countDown() })

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
        assertTrue(submitControl { controlDone.countDown() })
        assertTrue(controlDone.await(2, TimeUnit.SECONDS))
        releasePcm.countDown()
    }

    /**
     * 与 [ControlServer] 相同的方式接纳一条控制连接：占连接槽 → 在控制 worker 上执行 →
     * 任务结束（含异常）才归还连接槽。
     */
    private fun submitControl(task: () -> Unit): Boolean {
        if (!lanes.tryAdmitControl()) return false
        val submitted = lanes.submitControlTask {
            try {
                task()
            } finally {
                lanes.releaseControlSlot()
            }
        }
        if (!submitted) lanes.releaseControlSlot()
        return submitted
    }

    @Test
    fun controlSubmissionAfterCloseIsBoundedFailure() {
        lanes.close()

        assertFalse(submitControl { })
        assertFalse(lanes.submitPcm { })
        assertFalse(lanes.tryAdmitControl())
        assertFalse(lanes.tryAdmitCommand())
        assertFalse(lanes.submitCommandTask { })
    }

    /**
     * I1：session.command 的 4 s 等待跑在专用命令 lane 上。两个命令同时等待时，
     * HOOK 心跳（控制 lane）仍然必须被立即服务，否则命令永远等不到结果 → 必然超时。
     */
    @Test
    fun twoWaitingCommandsNeverStarveTheControlLane() {
        val commandStarted = CountDownLatch(2)
        val releaseCommands = CountDownLatch(1)
        val commandFinished = CountDownLatch(2)
        repeat(2) {
            assertTrue(lanes.tryAdmitCommand())
            assertTrue(
                lanes.submitCommandTask {
                    try {
                        commandStarted.countDown()
                        releaseCommands.await(10, TimeUnit.SECONDS)
                    } finally {
                        lanes.releaseCommandSlot()
                        commandFinished.countDown()
                    }
                },
            )
        }
        assertTrue("两个命令必须都已开始等待", commandStarted.await(5, TimeUnit.SECONDS))

        // HOOK 心跳 / 状态查询走控制 lane：两条命令在飞行时仍必须立即完成。
        repeat(4) {
            val controlDone = CountDownLatch(1)
            assertTrue("控制 lane 必须仍可接受连接", submitControl { controlDone.countDown() })
            assertTrue("控制请求被命令等待饿死", controlDone.await(2, TimeUnit.SECONDS))
        }

        // HOOK 取走命令并回报结果（用放行模拟）：两个命令都必须完成，而不是超时。
        releaseCommands.countDown()
        assertTrue("两个命令都必须收到结果", commandFinished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun commandLaneHasItsOwnExplicitBound() {
        repeat(ConnectionLanes.MAX_COMMAND_CONNECTIONS) {
            assertTrue("第 " + (it + 1) + " 条命令必须被接纳", lanes.tryAdmitCommand())
        }
        assertFalse("超过上限必须有界失败", lanes.tryAdmitCommand())
        repeat(ConnectionLanes.MAX_COMMAND_CONNECTIONS) { lanes.releaseCommandSlot() }
        assertTrue("归还后可以再次接纳", lanes.tryAdmitCommand())
        lanes.releaseCommandSlot()
    }

    @Test
    fun longWaitAppMethodsAreRoutedToTheCommandLane() {
        // 只有会同步等 HOOK 结果（最长 4 s）的 APP 方法离开控制 worker。
        assertTrue(usesCommandLane(RolePolicy.METHOD_SESSION_COMMAND))
        assertTrue(usesCommandLane(RolePolicy.METHOD_SESSION_REQUEST))
        assertFalse(usesCommandLane(RolePolicy.METHOD_SESSION_HEARTBEAT))
        assertFalse(usesCommandLane(RolePolicy.METHOD_HOOK_HEARTBEAT))
        assertFalse(usesCommandLane(RolePolicy.METHOD_HOOK_SNAPSHOT))
        assertFalse(usesCommandLane(RolePolicy.METHOD_STATUS))
        assertFalse(usesCommandLane(RolePolicy.METHOD_DIAGNOSE))
        assertFalse(usesCommandLane(RolePolicy.METHOD_HANDOVER))
    }
}

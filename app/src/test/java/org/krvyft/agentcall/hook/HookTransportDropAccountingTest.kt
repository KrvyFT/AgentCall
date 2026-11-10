package org.krvyft.agentcall.hook

import org.junit.Assert.assertEquals
import org.junit.Test
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.SessionId

/**
 * HookTransport 的丢弃计数（M5）——不启动传输线程，也不触碰 LocalSocket。
 *
 * 只验证“传输未运行时入队不是静默成功”和“命令结果溢出不会记进快照计数”这两个纯计数
 * 契约；真正的连接/信任逻辑在代码里由 [HookServerTrust] 的 JVM 测试覆盖。
 */
class HookTransportDropAccountingTest {

    private val session = SessionId(processEpoch = "epoch-1", callId = "call-a")

    private fun transport() = HookTransport(
        processEpoch = "epoch-1",
        liveSessions = { listOf(session) },
        onCommand = { _, _ -> },
    )

    @Test
    fun enqueueingBeforeStartCountsAsADroppedSnapshot() {
        val transport = transport()

        transport.enqueueSnapshot(snapshot())

        assertEquals(1L, transport.droppedSnapshotCount)
        assertEquals(0L, transport.droppedCommandResultCount)
    }

    @Test
    fun enqueueingACommandResultBeforeStartCountsSeparately() {
        val transport = transport()

        transport.enqueueCommandResult(
            CommandAttempt(
                session = session,
                command = CallCommand.Answer,
                source = ActionSource.MODULE,
                result = CommandResult.Accepted,
            ),
        )

        assertEquals(0L, transport.droppedSnapshotCount)
        assertEquals(1L, transport.droppedCommandResultCount)
    }

    private fun snapshot(): CallSnapshot = CallSnapshot(
        id = session,
        number = "+8613800000000",
        state = CallState.ACTIVE,
        direction = CallDirection.INCOMING,
        presentation = Presentation.ALLOWED,
        phoneAccount = "acct-1",
        isEmergency = false,
        isConference = false,
        activeCallCount = 1,
    )
}

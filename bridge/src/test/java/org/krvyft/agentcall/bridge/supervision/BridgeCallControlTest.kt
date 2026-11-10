package org.krvyft.agentcall.bridge.supervision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.session.SessionLimits

/**
 * 监督端与 Hook 命令/快照通道之间的桥接测试（C1：生产路径必须真的能把
 * Answer/Hangup 发到 Hook 并拿到真实结果，才能拥有/终止精确通话）。
 *
 * 线格式是 Task 4 Hook 固定字段 JSON 的消费侧；本类只解析自己需要的字段，
 * 并强制会话字段匹配请求会话（防止把别人的结果当成自己的）。
 */
class HookSnapshotDecoderTest {

    private val session = SessionId("epoch-1", "call-a")
    private val foreign = SessionId("epoch-2", "call-b")

    @Test
    fun decodesStateAndHandbackReasonForMatchingSession() {
        val decoded = HookSnapshotDecoder.decode(session, snapshotJson(session, "ACTIVE", null))

        assertEquals(CallState.ACTIVE, decoded!!.state)
        assertNull(decoded.handbackReason)

        val handback = HookSnapshotDecoder.decode(session, snapshotJson(session, "ACTIVE", "route_change"))
        assertEquals("route_change", handback!!.handbackReason)
    }

    @Test
    fun foreignOrMalformedSessionIsIgnored() {
        assertNull(HookSnapshotDecoder.decode(session, snapshotJson(foreign, "ACTIVE", null)))
        assertNull(HookSnapshotDecoder.decode(session, "{}"))
        assertNull(HookSnapshotDecoder.decode(session, snapshotJson(session, "NOT_A_STATE", null)))
    }

    private fun snapshotJson(id: SessionId, state: String, handback: String?): String =
        "{\"processEpoch\":\"" + id.processEpoch + "\",\"callId\":\"" + id.callId +
            "\",\"state\":\"" + state + "\",\"handbackReason\":" +
            (if (handback == null) "null" else "\"" + handback + "\"") + "}"
}

class HookCommandResultDecoderTest {

    private val session = SessionId("epoch-1", "call-a")
    private val foreign = SessionId("epoch-2", "call-b")

    @Test
    fun decodesAllResultKinds() {
        val accepted = HookCommandResultDecoder.decode(session, resultJson(session, "accepted", null, null, null))
        assertEquals(CallCommand.Answer, accepted!!.command)
        assertEquals(CommandResult.Accepted, accepted.result)

        val rejected = HookCommandResultDecoder.decode(
            session,
            resultJson(session, "rejected", "NOT_RINGING", null, null),
        )
        assertEquals(CommandResult.Rejected(RejectReason.NOT_RINGING), rejected!!.result)

        val failed = HookCommandResultDecoder.decode(
            session,
            resultJson(session, "failed", null, "hook_gone", "hook process is gone"),
        )
        assertEquals(CommandResult.Failed("hook_gone", "hook process is gone"), failed!!.result)
    }

    @Test
    fun foreignSessionResultIsIgnored() {
        assertNull(HookCommandResultDecoder.decode(session, resultJson(foreign, "accepted", null, null, null)))
    }

    private fun resultJson(
        id: SessionId,
        outcome: String,
        reason: String?,
        code: String?,
        message: String?,
    ): String {
        fun field(name: String, value: String?): String =
            "\"" + name + "\":" + (if (value == null) "null" else "\"" + value + "\"")
        return "{" + field("processEpoch", id.processEpoch) + "," +
            field("callId", id.callId) + "," +
            field("command", "Answer") + "," +
            field("source", "MODULE") + "," +
            field("outcome", outcome) + "," +
            field("reason", reason) + "," +
            field("code", code) + "," +
            field("message", message) + "}"
    }
}

class HookCallInboxTest {

    private val session = SessionId("epoch-1", "call-a")
    private val other = SessionId("epoch-1", "call-b")

    @Test
    fun awaitedResultIsMatchedBySessionAndCommand() = runBlocking {
        val inbox = HookCallInbox()
        val wait = waitFor(inbox, session, CallCommand.Answer)
        inbox.recordResult(other, CallCommand.Answer, CommandResult.Accepted)
        inbox.recordResult(session, CallCommand.Hangup, CommandResult.Accepted)
        inbox.recordResult(session, CallCommand.Answer, CommandResult.Rejected(RejectReason.NOT_RINGING))

        val outcome = inbox.awaitResult(wait, timeoutMs = 50)

        assertEquals(
            CommandResult.Rejected(RejectReason.NOT_RINGING),
            (outcome as HookCallInbox.AwaitOutcome.Delivered).result,
        )
        inbox.endWait(wait)
        // 匹配项被消费后不得重复返回。
        val second = waitFor(inbox, session, CallCommand.Answer)
        assertEquals(HookCallInbox.AwaitOutcome.TimedOut, inbox.awaitResult(second, timeoutMs = 10))
        inbox.endWait(second)
    }

    @Test
    fun missingResultTimesOutAsAmbiguous() = runBlocking {
        val inbox = HookCallInbox()
        val wait = waitFor(inbox, session, CallCommand.Hangup)
        assertEquals(HookCallInbox.AwaitOutcome.TimedOut, inbox.awaitResult(wait, timeoutMs = 20))
        inbox.endWait(wait)
    }

    /**
     * I3：上一次入队超时后才到的结果必须只属于上一次入队——新的等待者不能把它当成
     * 自己的 Accepted（否则会把一条根本没下发的命令报成成功）。
     */
    @Test
    fun resultsRecordedBeforeTheWaitAreStaleAndNeverDelivered() = runBlocking {
        val inbox = HookCallInbox()
        // 上一次等待已超时，结果现在才落进 inbox。
        inbox.recordResult(session, CallCommand.Answer, CommandResult.Accepted)

        val wait = waitFor(inbox, session, CallCommand.Answer)
        assertEquals(HookCallInbox.AwaitOutcome.TimedOut, inbox.awaitResult(wait, timeoutMs = 30))
        inbox.endWait(wait)
    }

    /**
     * I2：同一 (session, command) 的结果只能有一个权威等待者。监督端（session.request 的
     * Answer）可以抢占 APP 的等待者；被抢占方必须立刻得到 Superseded，而不是超时。
     */
    @Test
    fun supervisorWaitSupersedesTheAppWaiterForTheSameCommand() = runBlocking {
        val inbox = HookCallInbox()
        val appWait = waitFor(inbox, session, CallCommand.Answer)
        val supervisorWait = inbox.beginSupervisorWait(session, CallCommand.Answer)
        assertTrue(inbox.awaitResult(appWait, timeoutMs = 200) is HookCallInbox.AwaitOutcome.Superseded)
        inbox.endWait(appWait)

        inbox.recordResult(session, CallCommand.Answer, CommandResult.Accepted)
        val outcome = inbox.awaitResult(supervisorWait, timeoutMs = 200)
        assertEquals(CommandResult.Accepted, (outcome as HookCallInbox.AwaitOutcome.Delivered).result)
        inbox.endWait(supervisorWait)
    }

    @Test
    fun appAnswerWaitIsDeniedWhileASupervisorAnswerIsInFlight() {
        val inbox = HookCallInbox()
        inbox.beginSupervisorAnswer(session)
        assertTrue(inbox.supervisorAnswerInFlight(session))
        assertEquals(
            HookCallInbox.AppWait.SupervisorInFlight,
            inbox.beginAppWait(session, CallCommand.Answer),
        )
        inbox.endSupervisorAnswer(session)
        assertTrue(inbox.beginAppWait(session, CallCommand.Answer) is HookCallInbox.AppWait.Started)
    }

    private fun waitFor(
        inbox: HookCallInbox,
        session: SessionId,
        command: CallCommand,
    ): HookCallInbox.Wait {
        val started = inbox.beginAppWait(session, command)
        assertTrue("wait must start: " + started, started is HookCallInbox.AppWait.Started)
        return (started as HookCallInbox.AppWait.Started).wait
    }

    @Test
    fun recordedStateIsReadBack() {
        val inbox = HookCallInbox()
        inbox.recordState(session, CallState.ACTIVE)
        assertEquals(CallState.ACTIVE, inbox.state(session))
        assertNull(inbox.state(other))
    }
}

class HookCommandCallControlTest {

    private val session = SessionId("epoch-1", "call-a")

    /** 与 HookTransport.HEARTBEAT_INTERVAL_MS 对齐的取命令间隔。 */
    private val HOOK_PICKUP_MS: Long = 2_000L

    @Test
    fun queueSaturationIsAboundedFailure() = runBlocking {
        val control = HookCommandCallControl(
            enqueue = { _, _ -> false },
            inbox = HookCallInbox(),
            timeoutMs = 50,
        )

        val result = control.send(session, CallCommand.Answer)

        assertTrue(result is CommandResult.Failed)
        assertEquals(HookCommandCallControl.ERROR_QUEUE, (result as CommandResult.Failed).code)
    }

    @Test
    fun resultFedByHookCompletesSend() = runBlocking {
        val inbox = HookCallInbox()
        val control = HookCommandCallControl(
            enqueue = { id, _ ->
                inbox.recordResult(id, CallCommand.Answer, CommandResult.Accepted)
                true
            },
            inbox = inbox,
            timeoutMs = 100,
        )

        assertEquals(CommandResult.Accepted, control.send(session, CallCommand.Answer))
    }

    @Test
    fun missingHookResultFailsClosed() = runBlocking {
        val control = HookCommandCallControl(
            enqueue = { _, _ -> true },
            inbox = HookCallInbox(),
            timeoutMs = 20,
        )

        val result = control.send(session, CallCommand.Hangup)

        assertTrue(result is CommandResult.Failed)
        assertEquals(HookCommandCallControl.ERROR_TIMEOUT, (result as CommandResult.Failed).code)
    }

    /**
     * 新增重要缺陷 3：Hook 只在 2000 ms 心跳响应里取命令。等待上限必须大于取命令间隔，
     * 否则 Answer 每次都超时 → Failed → 人工接管窗口 → 10 秒后终止。
     */
    @Test
    fun commandWaitCoversTheHookHeartbeatPickupInterval() {
        val hookPickupMs = HOOK_PICKUP_MS
        assertTrue(
            "等待上限 " + HookCommandCallControl.DEFAULT_TIMEOUT_MS +
                " ms 必须大于 Hook 的 " + hookPickupMs + " ms 心跳取命令间隔",
            HookCommandCallControl.DEFAULT_TIMEOUT_MS > hookPickupMs,
        )
        assertTrue(
            "接听决策必须明显短于 " + SessionLimits.FAILURE_HANDOFF_WINDOW_MS + " ms 人工接管窗口",
            HookCommandCallControl.DEFAULT_TIMEOUT_MS <= SessionLimits.FAILURE_HANDOFF_WINDOW_MS / 2,
        )
    }

    @Test
    fun answerPickedUpOnTheNextHookHeartbeatStillCompletes() = runBlocking {
        val inbox = HookCallInbox()
        val enqueued = CompletableDeferred<SessionId>()
        val control = HookCommandCallControl(
            enqueue = { id, _ ->
                enqueued.complete(id)
                true
            },
            inbox = inbox,
        )
        // 假 Hook：命令进队列后要等一次心跳才取走并回报结果（真实上限 2000 ms）。
        val hook = launch(Dispatchers.Default) {
            val id = enqueued.await()
            delay(HOOK_PICKUP_MS)
            inbox.recordResult(id, CallCommand.Answer, CommandResult.Accepted)
        }

        val startedAt = System.nanoTime()
        val result = control.send(session, CallCommand.Answer)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        hook.join()

        assertEquals("一次心跳延迟内的结果不能被当成超时", CommandResult.Accepted, result)
        assertTrue(
            "必须真的等过一次 " + HOOK_PICKUP_MS + " ms 取命令间隔（实际 " + elapsedMs + " ms）",
            elapsedMs >= HOOK_PICKUP_MS - 100,
        )
    }

    @Test
    fun snapshotStateProbeReturnsLastKnownState() = runBlocking {
        val inbox = HookCallInbox()
        inbox.recordState(session, CallState.ACTIVE)
        val probe = SnapshotCallStateProbe(inbox)

        assertEquals(CallState.ACTIVE, probe.state(session))
        assertNull(probe.state(SessionId("epoch-1", "call-b")))
    }
}

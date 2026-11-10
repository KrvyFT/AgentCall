package org.krvyft.agentcall.bridge.supervision

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
        inbox.recordResult(other, CallCommand.Answer, CommandResult.Accepted)
        inbox.recordResult(session, CallCommand.Hangup, CommandResult.Accepted)
        inbox.recordResult(session, CallCommand.Answer, CommandResult.Rejected(RejectReason.NOT_RINGING))

        val result = inbox.awaitResult(session, CallCommand.Answer, timeoutMs = 50)

        assertEquals(CommandResult.Rejected(RejectReason.NOT_RINGING), result)
        assertNull("匹配项被消费后不得重复返回", inbox.awaitResult(session, CallCommand.Answer, timeoutMs = 10))
    }

    @Test
    fun missingResultTimesOutAsAmbiguous() = runBlocking {
        val inbox = HookCallInbox()
        assertNull(inbox.awaitResult(session, CallCommand.Hangup, timeoutMs = 20))
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

    @Test
    fun snapshotStateProbeReturnsLastKnownState() = runBlocking {
        val inbox = HookCallInbox()
        inbox.recordState(session, CallState.ACTIVE)
        val probe = SnapshotCallStateProbe(inbox)

        assertEquals(CallState.ACTIVE, probe.state(session))
        assertNull(probe.state(SessionId("epoch-1", "call-b")))
    }
}

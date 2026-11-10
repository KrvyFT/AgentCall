package org.krvyft.agentcall.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * HOOK 线格式的 JVM 测试：证明出站方法白名单不含任何配置/密钥方法、命令负载
 * 严格按受限语法解析、快照 JSON 的转义正确。传输线程本身依赖 android.net.LocalSocket，
 * 只能在设备上运行，本测试不伪造它。
 */
class HookWireFormatTest {

    @Test
    fun onlySnapshotAndHeartbeatAreAllowedOutboundMethods() {
        assertTrue(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_HOOK_SNAPSHOT))
        assertTrue(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_HOOK_HEARTBEAT))
        assertFalse(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_CONFIG_READ))
        assertFalse(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_CONFIG_SECRET_READ))
        assertFalse(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_CONFIG_WRITE))
        assertFalse(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_HOOK_SNAPSHOT + "-extra"))
        assertFalse(HookWireFormat.isAllowedOutboundMethod(""))
    }

    @Test
    fun restrictedCommandsAreParsedStrictly() {
        val parsed = HookWireFormat.parseCommands("answer call-1\nhangup call-2\n")

        assertEquals(0, parsed.invalidLines)
        assertEquals(2, parsed.commands.size)
        assertEquals(HookWireFormat.Verb.ANSWER, parsed.commands[0].verb)
        assertEquals("call-1", parsed.commands[0].callId)
        assertEquals(CallCommand.Answer, parsed.commands[0].verb.callCommand)
        assertEquals(HookWireFormat.Verb.HANGUP, parsed.commands[1].verb)
        assertEquals(CallCommand.Hangup, parsed.commands[1].verb.callCommand)
    }

    @Test
    fun invalidLinesAreCountedAndNeverExecuted() {
        val parsed = HookWireFormat.parseCommands(
            "answer call-1\n" +
                "ANSWER call-2\n" + // 大小写敏感
                "answer\n" + // 缺 callId
                "answer call-3 extra\n" + // 多余令牌
                "answer call 4\n" + // callId 含空格
                "reject call-5\n" + // 未列入固定命令表
                "hangup " + "a".repeat(65) + "\n" +
                "\n",
        )

        assertEquals(1, parsed.commands.size)
        assertEquals("call-1", parsed.commands[0].callId)
        assertEquals(6, parsed.invalidLines)
    }

    @Test
    fun commandBatchIsBounded() {
        val valid = (1..10).joinToString("\n") { "answer call-" + it }
        val parsed = HookWireFormat.parseCommands(valid)

        assertEquals(HookWireFormat.MAX_COMMANDS, parsed.commands.size)
        assertEquals(2, parsed.invalidLines)
    }

    @Test
    fun oversizedPayloadExecutesNothing() {
        val parsed = HookWireFormat.parseCommands("answer " + "a".repeat(HookWireFormat.MAX_COMMAND_PAYLOAD_CHARS))

        assertTrue(parsed.commands.isEmpty())
        assertEquals(1, parsed.invalidLines)
    }

    @Test
    fun callCommandResultIsAllowedOutboundWithoutOpeningConfigMethods() {
        assertTrue(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_CALL_COMMAND_RESULT))
        assertFalse(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_CONFIG_READ))
        assertFalse(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_CONFIG_SECRET_READ))
    }

    @Test
    fun snapshotPayloadCarriesActionSourceAndHandbackReason() {
        val snapshot = CallSnapshot(
            id = SessionId(processEpoch = "epoch-1", callId = "call-1"),
            number = "+8613800000000",
            state = CallState.ACTIVE,
            direction = CallDirection.INCOMING,
            presentation = Presentation.ALLOWED,
            phoneAccount = "SIM1",
            isEmergency = false,
            isConference = false,
            activeCallCount = 1,
        )

        val handedBack = HookWireFormat.encodeSnapshot(snapshot, ActionSource.UNKNOWN, "route_change")
        assertTrue(handedBack.contains("\"source\":\"UNKNOWN\""))
        assertTrue(handedBack.contains("\"handbackReason\":\"route_change\""))
        assertFalse(handedBack.contains("\n"))

        val moduleAction = HookWireFormat.encodeSnapshot(snapshot, ActionSource.MODULE)
        assertTrue(moduleAction.contains("\"source\":\"MODULE\""))
        assertTrue(moduleAction.contains("\"handbackReason\":null"))
    }

    @Test
    fun commandResultPayloadCarriesOutcomeSourceAndReason() {
        val session = SessionId(processEpoch = "epoch-1", callId = "call-1")

        val rejected = HookWireFormat.encodeCommandResult(
            session = session,
            command = CallCommand.Answer,
            source = ActionSource.MODULE,
            result = CommandResult.Rejected(RejectReason.NOT_OWNED),
            note = "not_owned",
        )
        assertTrue(rejected.contains("\"processEpoch\":\"epoch-1\""))
        assertTrue(rejected.contains("\"callId\":\"call-1\""))
        assertTrue(rejected.contains("\"command\":\"Answer\""))
        assertTrue(rejected.contains("\"source\":\"MODULE\""))
        assertTrue(rejected.contains("\"outcome\":\"rejected\""))
        assertTrue(rejected.contains("\"reason\":\"NOT_OWNED\""))
        assertTrue(rejected.contains("\"code\":null"))
        assertFalse(rejected.contains("\n"))

        val failed = HookWireFormat.encodeCommandResult(
            session = session,
            command = CallCommand.Hangup,
            source = ActionSource.UNKNOWN,
            result = CommandResult.Failed("disconnect_failed", "SECURITY"),
        )
        assertTrue(failed.contains("\"command\":\"Hangup\""))
        assertTrue(failed.contains("\"outcome\":\"failed\""))
        assertTrue(failed.contains("\"reason\":null"))
        assertTrue(failed.contains("\"code\":\"disconnect_failed\""))
        assertTrue(failed.contains("\"message\":\"SECURITY\""))

        val accepted = HookWireFormat.encodeCommandResult(
            session = session,
            command = CallCommand.Answer,
            source = ActionSource.MODULE,
            result = CommandResult.Accepted,
        )
        assertTrue(accepted.contains("\"outcome\":\"accepted\""))
        assertTrue(accepted.contains("\"reason\":null"))
    }

    @Test
    fun onlyRootServerUidIsTrustedOnTheAbstractSocket() {
        assertTrue(HookServerTrust.isTrustedUid(0))
        assertFalse(HookServerTrust.isTrustedUid(1000))
        assertFalse(HookServerTrust.isTrustedUid(10123))
        assertFalse(HookServerTrust.isTrustedUid(null))
    }

    @Test
    fun snapshotPayloadIsJsonWithEscapingAndExplicitSecurityFields() {
        val snapshot = CallSnapshot(
            id = SessionId(processEpoch = "epoch-1", callId = "call-1"),
            number = null,
            state = CallState.RINGING,
            direction = CallDirection.INCOMING,
            presentation = Presentation.ALLOWED,
            phoneAccount = "acct \"sim\"\n1",
            isEmergency = true,
            isConference = false,
            activeCallCount = 2,
        )

        val json = HookWireFormat.encodeSnapshot(snapshot)

        assertTrue(json.contains("\"processEpoch\":\"epoch-1\""))
        assertTrue(json.contains("\"callId\":\"call-1\""))
        assertTrue(json.contains("\"number\":null"))
        assertTrue(json.contains("\"state\":\"RINGING\""))
        assertTrue(json.contains("\"direction\":\"INCOMING\""))
        assertTrue(json.contains("\"presentation\":\"ALLOWED\""))
        assertTrue(json.contains("acct \\\"sim\\\"\\n1"))
        assertTrue(json.contains("\"isEmergency\":true"))
        assertTrue(json.contains("\"isConference\":false"))
        assertTrue(json.contains("\"activeCallCount\":2"))
        assertFalse("payload must stay single-line", json.contains("\n"))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }
}
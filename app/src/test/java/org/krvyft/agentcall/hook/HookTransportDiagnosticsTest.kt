package org.krvyft.agentcall.hook

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.core.protocol.RpcCodec

/**
 * Task 11Q：HOOK 传输诊断。
 *
 * 设备上 hookConnected=false 时日志只有“没有请求”，无法区分连不上、服务端不可信还是
 * 协议违规。每个失败分支都必须输出同一条机器码日志（限流、无负载/无会话 id）：
 * hook_transport connect_failed|server_untrusted|protocol_violation failures=<n>。
 */
class HookTransportDiagnosticsTest {

    private val logs = mutableListOf<String>()

    private fun transport(link: FakeHookSocketLink, clockNs: AtomicLong) = HookTransport(
        processEpoch = "epoch-1",
        liveSessions = { emptyList() },
        onCommand = { _, _ -> },
        socketFactory = { link },
        clock = { clockNs.get() },
        logger = { logs += it },
    )

    @Test
    fun connectFailureIsLoggedWithAMachineCode() {
        val transport = transport(FakeHookSocketLink(connectFailure = true), AtomicLong(0L))

        assertNull(transport.sendRequest(RolePolicy.METHOD_HOOK_PRESENCE, null, ""))

        assertEquals(1L, transport.transportFailureCount)
        assertEquals(listOf("hook_transport connect_failed failures=1"), logs)
    }

    @Test
    fun untrustedServerIsLoggedAndNothingIsWritten() {
        val link = FakeHookSocketLink(peerUidValue = 10_123)
        val transport = transport(link, AtomicLong(0L))

        assertNull(transport.sendRequest(RolePolicy.METHOD_HOOK_PRESENCE, null, ""))

        assertEquals(1L, transport.untrustedServerCount)
        assertTrue("不可信服务端绝不能收到请求帧", link.requests.isEmpty())
        assertEquals(listOf("hook_transport server_untrusted failures=1"), logs)
    }

    @Test
    fun protocolViolationIsLoggedWithAMachineCode() {
        val link = FakeHookSocketLink(
            responder = { RpcCodec.encodeResponseFrame(RpcResponse(id = "some-other-request")) },
        )
        val transport = transport(link, AtomicLong(0L))

        assertNull(transport.sendRequest(RolePolicy.METHOD_HOOK_PRESENCE, null, ""))

        assertEquals(1L, transport.protocolViolationCount)
        assertEquals(listOf("hook_transport protocol_violation failures=1"), logs)
    }

    @Test
    fun failureLoggingIsRateLimitedButTheCounterKeepsCounting() {
        val clockNs = AtomicLong(0L)
        val transport = transport(FakeHookSocketLink(connectFailure = true), clockNs)

        assertNull(transport.sendRequest(RolePolicy.METHOD_HOOK_PRESENCE, null, ""))
        assertNull(transport.sendRequest(RolePolicy.METHOD_HOOK_PRESENCE, null, ""))
        assertEquals("同一窗口只允许一条日志", 1, logs.size)
        assertEquals(2L, transport.transportFailureCount)

        clockNs.addAndGet(HookTransport.FAILURE_LOG_INTERVAL_MS * 1_000_000L + 1L)
        assertNull(transport.sendRequest(RolePolicy.METHOD_HOOK_PRESENCE, null, ""))

        assertEquals(
            listOf(
                "hook_transport connect_failed failures=1",
                "hook_transport connect_failed failures=3",
            ),
            logs,
        )
    }

    @Test
    fun successfulRequestLogsNothing() {
        val transport = transport(FakeHookSocketLink(), AtomicLong(0L))

        assertNotNull(transport.sendRequest(RolePolicy.METHOD_HOOK_PRESENCE, null, ""))

        assertTrue(logs.isEmpty())
        assertEquals(0L, transport.transportFailureCount)
        assertEquals(0L, transport.protocolViolationCount)
        assertEquals(0L, transport.untrustedServerCount)
    }
}

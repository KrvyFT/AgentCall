package org.krvyft.agentcall.hook

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * Task 11Q：空闲 HOOK 存在性。
 *
 * 旧实现只在有存活会话时发心跳，空闲 Hook 进程对桥接完全静默，readiness 永远看不到
 * hookConnected=true。现在传输线程必须每 ~2 s 发一条无会话、无负载的 hook.presence，
 * 同时保留原有的按会话心跳；不新增常驻线程。
 */
class HookTransportPresenceTest {

    @Test
    fun presenceIsSentAtTheIntervalWithZeroLiveSessions() {
        val clockNs = AtomicLong(1_000_000_000L)
        val links = CopyOnWriteArrayList<FakeHookSocketLink>()
        val transport = HookTransport(
            processEpoch = "epoch-1",
            liveSessions = { emptyList() },
            onCommand = { _, _ -> },
            socketFactory = { FakeHookSocketLink().also { links += it } },
            clock = { clockNs.get() },
        )

        transport.start()
        try {
            val first = awaitHookRequests(links, 1).requests.single()
            assertEquals(RolePolicy.METHOD_HOOK_PRESENCE, first.method)
            assertNull("presence 不携带会话", first.sessionId)
            assertEquals("presence 不携带负载", "", first.payload)

            // 没有会话也必须按固定间隔继续发：推进注入时钟，传输线程必须再发一条。
            clockNs.addAndGet(HookTransport.HEARTBEAT_INTERVAL_MS * 1_000_000L)
            val second = awaitHookRequests(links, 2).requests.single()
            assertEquals(RolePolicy.METHOD_HOOK_PRESENCE, second.method)
            assertNull(second.sessionId)

            // 时钟不前进就不应继续增长：节奏由注入时钟决定，单线程有界。
            Thread.sleep(500L)
            assertEquals("presence 必须按注入时钟的间隔发送", 2, links.size)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun presenceIsAllowedOutboundWithoutOpeningConfigMethods() {
        assertTrue(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_HOOK_PRESENCE))
        assertTrue(HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_HOOK_HEARTBEAT))
        assertTrue(!HookWireFormat.isAllowedOutboundMethod(RolePolicy.METHOD_CONFIG_READ))
    }
}

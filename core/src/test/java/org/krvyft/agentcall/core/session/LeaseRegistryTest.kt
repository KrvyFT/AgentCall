package org.krvyft.agentcall.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.fixtures.Fixtures
import org.krvyft.agentcall.core.model.CallCommand

/**
 * 单会话租约：每通电话至多一个租约，撤销后不能再生效或产生命令。
 */
class LeaseRegistryTest {

    private val registry = LeaseRegistry()
    private val id = Fixtures.sessionId

    @Test
    fun atMostOneLeasePerCall() {
        val first = registry.acquire(id)

        assertNotNull(first)
        assertTrue(registry.isLive(id))
        assertNull(registry.acquire(id))
    }

    @Test
    fun revokeLiveLeaseReturnsTrue() {
        registry.acquire(id)

        assertTrue(registry.revoke(id))
        assertFalse(registry.isLive(id))
    }

    @Test
    fun revokeUnknownLeaseReturnsFalse() {
        assertFalse(registry.revoke(id))
    }

    @Test
    fun revokeTwiceReturnsTrueOnlyOnce() {
        registry.acquire(id)

        assertTrue(registry.revoke(id))
        assertFalse(registry.revoke(id))
    }

    @Test
    fun liveLeaseAuthorizesCommand() {
        val lease = registry.acquire(id)!!

        assertEquals(CallCommand.Hangup, lease.authorize(CallCommand.Hangup))
    }

    @Test
    fun revokedLeaseCannotDisconnect() {
        val lease = registry.acquire(id)!!
        registry.revoke(id)

        assertNull(lease.authorize(CallCommand.Hangup))
        assertFalse(lease.isLive)
    }

    @Test
    fun revokedLeaseCannotBeReacquired() {
        registry.acquire(id)
        registry.revoke(id)

        // 撤销是终态：同一会话不能把旧租约“复活”绑定到新通话。
        assertNull(registry.acquire(id))
        assertFalse(registry.isLive(id))
    }

    @Test
    fun differentCallsHaveIndependentLeases() {
        val otherId = Fixtures.sessionId(callId = "call-b")

        assertNotNull(registry.acquire(id))
        assertNotNull(registry.acquire(otherId))
        assertTrue(registry.revoke(id))
        assertTrue(registry.isLive(otherId))
    }
}

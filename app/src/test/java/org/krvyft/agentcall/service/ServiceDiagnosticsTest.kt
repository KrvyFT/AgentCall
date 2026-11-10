package org.krvyft.agentcall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断码的**来源归属**（Task 7B3 FIX ROUND 2 / N4）。
 *
 * 轮询器与就绪循环都写诊断：一次“事件流恢复”只能清掉轮询器自己的那一条，
 * 不能顺手抹掉仍然成立的 bootstrap_failed（那会让轮询失败/就绪失败永久不可见）。
 */
class ServiceDiagnosticsTest {

    @Test
    fun aPollerRecoveryDoesNotClearAStillTrueBootstrapFailure() {
        val failed = ServiceDiagnostics.withBootstrapFailure(
            ServiceDiagnostics.withPollerCode(AgentCallStatus(), "bridge_unavailable"),
        )
        assertEquals(ServiceDiagnostics.BOOTSTRAP_FAILED, failed.bootstrapDiagnostic)
        assertEquals("bridge_unavailable", failed.pollerDiagnostic)
        assertTrue("a bootstrap failure must not claim readiness", !failed.ready)

        val recovered = ServiceDiagnostics.clearPollerCode(failed)

        assertEquals("the poller owns only its own slot", null, recovered.pollerDiagnostic)
        assertEquals(
            "a still-true bootstrap failure must stay visible",
            ServiceDiagnostics.BOOTSTRAP_FAILED,
            recovered.bootstrapDiagnostic,
        )
        assertEquals(ServiceDiagnostics.BOOTSTRAP_FAILED, ServiceDiagnostics.visible(recovered))
    }

    @Test
    fun bothSourcesAreVisibleAndEachClearsItsOwn() {
        val both = ServiceDiagnostics.withBootstrapFailure(
            ServiceDiagnostics.withPollerCode(AgentCallStatus(), "session_event_incomplete"),
        )
        assertEquals("session_event_incomplete", ServiceDiagnostics.visible(both))

        val bootRecovered = ServiceDiagnostics.clearBootstrapFailure(both)
        assertNull(bootRecovered.bootstrapDiagnostic)
        assertEquals(
            "a recovered bootstrap must not clear the poller's diagnostic",
            "session_event_incomplete",
            bootRecovered.pollerDiagnostic,
        )

        val allRecovered = ServiceDiagnostics.clearPollerCode(bootRecovered)
        assertNull(ServiceDiagnostics.visible(allRecovered))
    }

    @Test
    fun anEmptyStatusHasNoDiagnostic() {
        assertNull(ServiceDiagnostics.visible(AgentCallStatus()))
    }
}

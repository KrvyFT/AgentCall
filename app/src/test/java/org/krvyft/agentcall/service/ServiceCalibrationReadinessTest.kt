package org.krvyft.agentcall.service

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.BridgeStatusFacts
import org.krvyft.agentcall.ipc.ControlTimeouts
import org.krvyft.agentcall.ipc.ControlTransport

/**
 * Task 11H2：APP 就绪判定消费桥接校准授权（calibration.read）。
 *
 * 锁定的语义（docs/config-schema 的固定负载）：
 * - 只有 `available` && `supportedApiLevel` 受支持 && `verified` &&
 *   `environmentComplete` && `environmentMatchesEvidence` 才允许自动接听；
 * - 未知/缺失/传输失败/字段类型不符一律 false（fail closed），绝不缓存 true；
 * - 每次就绪复评都重新读取，桥接裁决变化立即反映到 READY；
 * - 本地五项证据仍经 core 的 CalibrationPolicy，但桥接裁决是桥接证据的权威。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceCalibrationReadinessTest {

    // ------------------------------------------------------------------ 事实为真

    @Test
    fun verifiedCompleteAndMatchingCalibrationMakesReadinessCalibrationFactTrue() = runTest {
        val transport = FakeCalibrationTransport { request ->
            RpcResponse(id = request.id, payload = viewJson())
        }
        val reader = BridgeCalibrationReader(transport, ioDispatcher = StandardTestDispatcher(testScheduler))
        val calibration = BridgeCalibrationProbe(
            readCalibration = reader::read,
            environment = { ENVIRONMENT },
            evidenceFor = { completeEvidence(it) },
        )

        val decision = bootstrap(calibration).evaluate()

        assertTrue(decision.ready)
        assertTrue("no calibration blocker when every bridge fact is true", decision.calibrationBlockers.isEmpty())
        assertEquals("exactly one calibration.read per readiness evaluation", 1, transport.calls.get())
        assertEquals(listOf("calibration.read"), transport.methods)
    }

    // ------------------------------------------------------------------ 桥接事实为假

    @Test
    fun verifiedButEnvironmentMismatchBlocksReadiness() = runTest {
        val calibration = probeFor(
            viewJson(environmentMatchesEvidence = false, blockers = listOf("ENVIRONMENT_MISMATCH")),
        )

        val decision = bootstrap(calibration).evaluate()

        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.CALIBRATION_BLOCKED), decision.blockers)
        assertTrue(
            "the reason must be visible to the UI",
            "calibration_environment_mismatch" in decision.calibrationBlockers,
        )
        assertTrue("calibration_environment_mismatch" in decision.statusCodes)
    }

    @Test
    fun incompleteEffectiveEnvironmentBlocksReadiness() = runTest {
        val calibration = probeFor(
            viewJson(environmentComplete = false, blockers = listOf("ENVIRONMENT_INCOMPLETE")),
        )

        val decision = bootstrap(calibration).evaluate()

        assertFalse(decision.ready)
        assertTrue("calibration_environment_incomplete" in decision.calibrationBlockers)
    }

    @Test
    fun unverifiedBridgeEvidenceBlocksReadinessEvenWithCompleteLocalEvidence() = runTest {
        val calibration = probeFor(
            viewJson(verified = false, blockers = listOf("DOWNLINK_NOT_RECOGNIZED")),
        )

        val decision = bootstrap(calibration).evaluate()

        assertFalse(decision.ready)
        assertTrue(
            "bridge step blockers are surfaced",
            "calibration_downlink_not_recognized" in decision.calibrationBlockers,
        )
    }

    @Test
    fun unsupportedApiLevelBlocksReadiness() = runTest {
        val calibration = probeFor(viewJson(supportedApiLevel = 36))

        val decision = bootstrap(calibration).evaluate()

        assertFalse(decision.ready)
        assertTrue("calibration_api_unsupported" in decision.calibrationBlockers)
    }

    // ------------------------------------------------------------------ 未知/缺失/失败

    @Test
    fun unwiredCalibrationStoreBlocksReadiness() = runTest {
        val calibration = probeFor("{\"available\":false}")

        val decision = bootstrap(calibration).evaluate()

        assertFalse(decision.ready)
        assertEquals(listOf("calibration_unavailable"), decision.calibrationBlockers)
    }

    @Test
    fun aMissingOrIllTypedFieldIsUnknownAndFailsClosed() = runTest {
        val missing = probeFor(
            "{\"available\":true,\"supportedApiLevel\":37," +
                "\"environmentComplete\":true,\"environmentMatchesEvidence\":true,\"blockers\":[]}",
        )
        assertFalse(bootstrap(missing).evaluate().ready)
        assertEquals(listOf("calibration_unavailable"), bootstrap(missing).evaluate().calibrationBlockers)

        val illTyped = probeFor(
            "{\"available\":true,\"supportedApiLevel\":37,\"verified\":\"yes\"," +
                "\"environmentComplete\":true,\"environmentMatchesEvidence\":true,\"blockers\":[]}",
        )
        assertFalse(bootstrap(illTyped).evaluate().ready)
        assertEquals(listOf("calibration_unavailable"), bootstrap(illTyped).evaluate().calibrationBlockers)
    }

    @Test
    fun aBridgeErrorResponseIsUnavailableAndNeverVerified() = runTest {
        val transport = FakeCalibrationTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "denied"))
        }
        val calibration = BridgeCalibrationProbe(
            readCalibration = BridgeCalibrationReader(transport, ioDispatcher = StandardTestDispatcher(testScheduler))::read,
            environment = { ENVIRONMENT },
            evidenceFor = { completeEvidence(it) },
        )

        val decision = bootstrap(calibration).evaluate()

        assertFalse(decision.ready)
        assertEquals(listOf("calibration_unavailable"), decision.calibrationBlockers)
    }

    @Test
    fun aTransportFailureFailsClosedWithoutCachingTrue() = runTest {
        var payload: String? = null
        val transport = FakeCalibrationTransport { request ->
            payload?.let { RpcResponse(id = request.id, payload = it) }
        }
        val calibration = BridgeCalibrationProbe(
            readCalibration = BridgeCalibrationReader(transport, ioDispatcher = StandardTestDispatcher(testScheduler))::read,
            environment = { ENVIRONMENT },
            evidenceFor = { completeEvidence(it) },
        )
        val bootstrap = bootstrap(calibration)

        assertFalse("transport failure is not verification", bootstrap.evaluate().ready)
        payload = viewJson()
        assertTrue("a later successful read may verify", bootstrap.evaluate().ready)
        payload = null
        assertFalse("the true verdict must not be cached across reads", bootstrap.evaluate().ready)
    }

    @Test
    fun aProbeTimeoutOrThrowFailsClosed() = runTest {
        val decision = bootstrap(CalibrationProbe { error("probe exploded") }).evaluate()

        assertFalse(decision.ready)
        assertEquals(listOf("calibration_unavailable"), decision.calibrationBlockers)
    }

    @Test
    fun theCalibrationReadUsesTheShortPerMethodBudget() {
        assertEquals(
            ControlTimeouts.SHORT_TIMEOUT_MS,
            ControlTimeouts.timeoutMsFor("calibration.read"),
        )
    }

    // ------------------------------------------------------------------ 刷新/其他门禁

    @Test
    fun everyReevaluationRefreshesAChangedBridgeVerdict() = runTest {
        var payload = viewJson()
        val transport = FakeCalibrationTransport { request ->
            RpcResponse(id = request.id, payload = payload)
        }
        val calibration = BridgeCalibrationProbe(
            readCalibration = BridgeCalibrationReader(transport, ioDispatcher = StandardTestDispatcher(testScheduler))::read,
            environment = { ENVIRONMENT },
            evidenceFor = { completeEvidence(it) },
        )
        val bootstrap = bootstrap(calibration)

        assertTrue(bootstrap.evaluate().ready)
        payload = viewJson(environmentMatchesEvidence = false, blockers = listOf("ENVIRONMENT_MISMATCH"))
        val changed = bootstrap.evaluate()
        assertFalse(changed.ready)
        assertTrue("calibration_environment_mismatch" in changed.calibrationBlockers)
        payload = viewJson()
        assertTrue("the verdict can recover", bootstrap.evaluate().ready)
        assertEquals(3, transport.calls.get())
    }

    @Test
    fun readinessStillFailsClosedWhenAnotherGateIsMissing() = runTest {
        val transport = FakeCalibrationTransport { request ->
            RpcResponse(id = request.id, payload = viewJson())
        }
        val calibration = BridgeCalibrationProbe(
            readCalibration = BridgeCalibrationReader(transport, ioDispatcher = StandardTestDispatcher(testScheduler))::read,
            environment = { ENVIRONMENT },
            evidenceFor = { completeEvidence(it) },
        )

        val decision = bootstrap(calibration, online = false).evaluate()

        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.NETWORK_UNREACHABLE), decision.blockers)
        assertTrue(
            "a verified calibration must not hide another missing gate",
            decision.calibrationBlockers.isEmpty(),
        )
    }

    @Test
    fun localEvidenceAloneCannotUnlockWhenTheBridgeSaysUnavailable() = runTest {
        val calibration = probeFor("{\"available\":false}")

        val decision = bootstrap(calibration).evaluate()

        assertFalse(decision.ready)
        assertEquals(listOf(BootstrapBlocker.CALIBRATION_BLOCKED), decision.blockers)
    }

    // ------------------------------------------------------------------ 负载解析

    @Test
    fun thePayloadParserRejectsNonObjectsAndUnavailableViewsCarryNoFacts() {
        assertTrue(parseBridgeCalibrationView(null) is BridgeRead.Unavailable)
        assertTrue(parseBridgeCalibrationView("not json") is BridgeRead.Unavailable)
        assertTrue(parseBridgeCalibrationView("[]") is BridgeRead.Unavailable)
        assertTrue(parseBridgeCalibrationView("{\"available\":\"false\"}") is BridgeRead.Unavailable)

        val unavailable = parseBridgeCalibrationView("{\"available\":false}")
        assertTrue(unavailable is BridgeRead.Available)
        assertEquals(false, (unavailable as BridgeRead.Available).value.available)
        assertFalse(unavailable.value.authorizesAutoAnswer)
    }

    // ------------------------------------------------------------------ 装配/辅助

    private fun TestScope.probeFor(payload: String): BridgeCalibrationProbe {
        val transport = FakeCalibrationTransport { request ->
            RpcResponse(id = request.id, payload = payload)
        }
        return BridgeCalibrationProbe(
            readCalibration = BridgeCalibrationReader(transport, ioDispatcher = StandardTestDispatcher(testScheduler))::read,
            environment = { ENVIRONMENT },
            evidenceFor = { completeEvidence(it) },
        )
    }

    private fun bootstrap(
        calibration: CalibrationProbe,
        online: Boolean = true,
    ): ServiceBootstrap = ServiceBootstrap(
        ReadinessProbes(
            bridge = BridgeProbe { true },
            hook = HookPresenceProbe { HookPresence.UNKNOWN },
            dialer = DialerDefaultRoleProbe { null },
            config = ConfigProbe { ConfigFacts(enabled = true, valid = true, voiceCapable = true) },
            calibration = calibration,
            network = NetworkProbe { online },
            live = BridgeLiveFactsProbe {
                BridgeRead.Available(
                    BridgeStatusFacts(
                        hookConnected = true,
                        dialerDefaultRole = true,
                        supervisionPhase = "IDLE",
                    ),
                )
            },
            supervision = SupervisionPhaseProbe { null },
        ),
        probeTimeoutMs = 1_000,
    )

    private fun viewJson(
        available: Boolean = true,
        supportedApiLevel: Int = 37,
        verified: Boolean = true,
        environmentComplete: Boolean = true,
        environmentMatchesEvidence: Boolean = true,
        blockers: List<String> = emptyList(),
    ): String {
        if (!available) return "{\"available\":false}"
        val encodedBlockers = blockers.joinToString(",") { "\"" + it + "\"" }
        return "{\"available\":true,\"supportedApiLevel\":" + supportedApiLevel +
            ",\"environment\":{},\"effectiveEnvironment\":{}" +
            ",\"environmentComplete\":" + environmentComplete +
            ",\"verified\":" + verified +
            ",\"blockers\":[" + encodedBlockers + "]" +
            ",\"environmentMatchesEvidence\":" + environmentMatchesEvidence +
            ",\"evidenceEnvironment\":null" +
            ",\"operatorAttestation\":{\"operatorAttested\":false,\"transport\":null,\"route\":null}" +
            ",\"steps\":{},\"calibratedAtMs\":1}"
    }

    private fun completeEvidence(environment: EnvironmentKey): CalibrationEvidence = CalibrationEvidence(
        environment = environment,
        downlinkRecognized = true,
        peerHeardSpeech = true,
        microphoneIsolated = true,
        manualRestoreVerified = true,
        processFailureVerified = true,
        calibratedAtMs = 1L,
    )

    private class FakeCalibrationTransport(
        private val handler: (RpcRequest) -> RpcResponse?,
    ) : ControlTransport {
        val methods = mutableListOf<String>()
        val calls = AtomicInteger()

        override suspend fun request(request: RpcRequest): RpcResponse? {
            methods += request.method
            calls.incrementAndGet()
            return handler(request)
        }

        override suspend fun close() = Unit
    }

    private companion object {
        val ENVIRONMENT = EnvironmentKey(
            apiLevel = 37,
            romFingerprint = "fp",
            kernelVersion = "6.1",
            dialerPackageVersion = "1",
            frameworkVersion = "82",
            bridgeVersion = "1.0",
            audioPolicyDigest = "digest",
            simCarrier = "carrier",
            transport = "CELLULAR",
            route = "EARPIECE",
        )
    }
}

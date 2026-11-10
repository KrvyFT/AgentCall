package org.krvyft.agentcall.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.ipc.BridgeClient
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.BridgeStatusFacts
import org.krvyft.agentcall.ipc.ControlTransport

/**
 * 就绪判定接真实 status/diagnose 负载的 JVM 测试（Task 7B3）。
 *
 * 负载逐字来自 Task 7C 的线格式文档。锁定的不变量：
 * - hookConnected/dialerDefaultRole/supervisionPhase 都来自桥接的真实 status，不再有
 *   “APP 读不到所以永远 unknown”的占位探针；status 不可用时仍是 UNKNOWN/null（fail closed）；
 * - 校准环境来自真实 diagnose 的 EnvironmentKey；环境不完整/不可用一律不允许自动接听；
 * - 桥接 CalibrationStore（prepareAnswer 的唯一权威）的裁决经 `calibration.read` 进入
 *   [BridgeCalibrationProbe]：只有桥接授权视图与本机证据同时成立才解锁 READY；
 *   桥接不可用/未验证时绝不伪造授权（Task 11H2）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceReadinessBridgeTest {

    // ------------------------------------------------------------------ live status

    @Test
    fun liveFactsComeFromTheRealStatusPayload() = runTest {
        val control = FakeTransport { request -> RpcResponse(id = request.id, payload = STATUS_OK) }
        val client = client(control)
        val probe = BridgeLiveFactsProbe { client.status() }

        val facts = probe.read()

        assertEquals(HookPresence.PRESENT, facts.hookPresence)
        assertEquals(true, facts.dialerDefaultRole)
        assertEquals("IDLE", facts.supervisionPhase)
        assertNull(facts.supervisionBlocked)
        assertTrue("a readable status is available even if a live field is unknown", facts.statusAvailable)
        assertEquals(listOf("status"), control.requests.map { it.method })
        client.close()
    }

    @Test
    fun unknownLiveValuesAreNeverPromotedToPresentOrDefault() = runTest {
        val client = client(FakeTransport { request ->
            RpcResponse(
                id = request.id,
                payload = "{\"hookConnected\":\"unknown\",\"dialerDefaultRole\":\"unknown\"," +
                    "\"supervisionPhase\":null}",
            )
        })
        val probe = BridgeLiveFactsProbe { client.status() }

        val facts = probe.read()

        assertEquals(HookPresence.UNKNOWN, facts.hookPresence)
        assertNull(facts.dialerDefaultRole)
        assertNull(facts.supervisionPhase)
        assertTrue("status was readable", facts.statusAvailable)
        client.close()
    }

    @Test
    fun forbiddenStatusFailsClosedInsteadOfLookingOnline() = runTest {
        val client = client(FakeTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "denied"))
        })
        val probe = BridgeLiveFactsProbe { client.status() }

        val facts = probe.read()

        assertEquals(HookPresence.UNKNOWN, facts.hookPresence)
        assertNull(facts.dialerDefaultRole)
        assertNull(facts.supervisionPhase)
        assertFalse(facts.statusAvailable)
        client.close()
    }

    // ------------------------------------------------------------------ bootstrap

    @Test
    fun bootstrapConsumesLiveFactsAndReportsTheObservedStatus() = runTest {
        val bootstrap = bootstrap(
            live = BridgeLiveFactsProbe {
                BridgeRead.Available(
                    BridgeStatusFacts(
                        hookConnected = true,
                        dialerDefaultRole = true,
                        supervisionPhase = "IDLE",
                    ),
                )
            },
        )

        val decision = bootstrap.evaluate()

        assertTrue(decision.ready)
        assertEquals(HookPresence.PRESENT, decision.hookPresence)
        assertEquals(true, decision.dialerDefaultRole)
        assertEquals("IDLE", decision.supervisionPhase)
    }

    @Test
    fun absentHookAndNonDefaultDialerKeepTheExistingBlockers() = runTest {
        val bootstrap = bootstrap(
            live = BridgeLiveFactsProbe {
                BridgeRead.Available(
                    BridgeStatusFacts(
                        hookConnected = false,
                        dialerDefaultRole = false,
                        supervisionPhase = "IDLE",
                    ),
                )
            },
        )

        val decision = bootstrap.evaluate()

        assertFalse(decision.ready)
        assertEquals(
            listOf(BootstrapBlocker.HOOK_NOT_CONNECTED, BootstrapBlocker.DIALER_NOT_DEFAULT),
            decision.blockers,
        )
        assertEquals(HookPresence.ABSENT, decision.hookPresence)
        assertEquals(false, decision.dialerDefaultRole)
    }

    @Test
    fun statusIsReadOncePerEvaluationThroughBridgeClient() = runTest {
        val control = FakeTransport { request -> RpcResponse(id = request.id, payload = STATUS_OK) }
        val client = client(control)
        val probe = BridgeLiveFactsProbe { client.status() }
        val bootstrap = bootstrap(live = probe)

        bootstrap.evaluate()

        assertEquals(1, control.requests.count { it.method == "status" })
        client.close()
    }

    // ------------------------------------------------------------------ diagnose/校准

    @Test
    fun diagnoseEnvironmentFeedsTheCalibrationProbe() = runTest {
        val control = FakeTransport { request ->
            when (request.method) {
                "diagnose" -> RpcResponse(id = request.id, payload = DIAGNOSE_OK)
                else -> RpcResponse(id = request.id, payload = STATUS_OK)
            }
        }
        val client = client(control)
        val environment = readEnvironment(client)
        assertNotNull(environment)
        val probe = BridgeCalibrationProbe(
            readCalibration = { BridgeRead.Available(calibrationVerified()) },
            environment = { environment },
            evidenceFor = { env -> completeEvidence(env) },
        )

        val assessment = probe.assess()

        assertTrue(assessment.allowed)
        assertEquals(emptyList<String>(), assessment.blockers)
        assertEquals(listOf("diagnose"), control.requests.map { it.method })
        client.close()
    }

    @Test
    fun missingDiagnoseEnvironmentBlocksCalibration() = runTest {
        val client = client(FakeTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "denied"))
        })
        val probe = BridgeCalibrationProbe(
            readCalibration = { BridgeRead.Available(calibrationVerified()) },
            environment = { readEnvironment(client) },
            evidenceFor = { env -> completeEvidence(env) },
        )
        val assessment = probe.assess()
        assertFalse(assessment.allowed)
        assertEquals(
            listOf(BridgeCalibrationProbe.CALIBRATION_ENVIRONMENT_UNKNOWN),
            assessment.blockers,
        )

        val noEvidence = BridgeCalibrationProbe(
            readCalibration = { BridgeRead.Available(calibrationVerified()) },
            environment = { COMPLETE_ENVIRONMENT },
            evidenceFor = { null },
        )
        val noEvidenceAssessment = noEvidence.assess()
        assertFalse(noEvidenceAssessment.allowed)
        assertEquals(
            listOf(BridgeCalibrationProbe.CALIBRATION_LOCAL_EVIDENCE_MISSING),
            noEvidenceAssessment.blockers,
        )
        client.close()
    }

    /**
     * 桥接裁决是桥接证据的权威：本机五项证据即使齐全，桥接不可用/未验证时仍必须
     * NOT_READY（calibration_not_verified），绝不伪造授权。
     */
    @Test
    fun unavailableBridgeCalibrationCannotBeReplacedByLocalEvidence() = runTest {
        val control = FakeTransport { request ->
            when (request.method) {
                "diagnose" -> RpcResponse(id = request.id, payload = DIAGNOSE_OK)
                else -> RpcResponse(id = request.id, payload = STATUS_OK)
            }
        }
        val client = client(control)
        val environment = readEnvironment(client)
        assertNotNull("the real diagnose environment must be readable", environment)
        val localOnly = BridgeCalibrationProbe(
            readCalibration = { BridgeRead.Available(calibrationVerified()) },
            environment = { environment },
            evidenceFor = { env -> completeEvidence(env) },
        )
        assertTrue("the local evidence itself is complete and bound", localOnly.assess().allowed)

        val productionCalibration = BridgeCalibrationProbe(
            readCalibration = { BridgeRead.Unavailable("forbidden") },
            environment = { environment },
            evidenceFor = { env -> completeEvidence(env) },
        )
        val bootstrap = bootstrap(
            live = BridgeLiveFactsProbe { client.status() },
            calibration = productionCalibration,
        )

        val decision = bootstrap.evaluate()

        assertFalse("a real device must stay NOT_READY here", decision.ready)
        assertEquals(listOf(BootstrapBlocker.CALIBRATION_BLOCKED), decision.blockers)
        assertEquals(
            listOf(BridgeCalibrationView.CALIBRATION_UNAVAILABLE),
            decision.calibrationBlockers,
        )
        assertEquals("IDLE", decision.supervisionPhase)
        client.close()
    }

    @Test
    fun aMissingVoiceEndpointStillBlocksIndependentlyOfCalibration() = runTest {
        val bootstrap = bootstrap(
            live = BridgeLiveFactsProbe {
                BridgeRead.Available(
                    BridgeStatusFacts(
                        hookConnected = true,
                        dialerDefaultRole = true,
                        supervisionPhase = "BLOCKED",
                    ),
                )
            },
            calibration = CalibrationProbe {
                CalibrationAssessment(
                    allowed = false,
                    blockers = listOf(BootstrapBlocker.CALIBRATION_BLOCKED.code),
                )
            },
            config = ConfigFacts(enabled = true, valid = true, voiceCapable = false),
        )
        val decision = bootstrap.evaluate()
        assertFalse(decision.ready)
        assertEquals(
            listOf(BootstrapBlocker.CONFIG_VOICE_UNSUPPORTED, BootstrapBlocker.CALIBRATION_BLOCKED),
            decision.blockers,
        )
        assertEquals("BLOCKED", decision.supervisionPhase)
    }

    // ------------------------------------------------------------------ 装配/辅助

    private fun TestScope.bootstrap(
        live: LiveFactsProbe,
        calibration: CalibrationProbe = CalibrationProbe { CalibrationAssessment(allowed = true) },
        config: ConfigFacts = ConfigFacts(enabled = true, valid = true, voiceCapable = true),
    ): ServiceBootstrap = ServiceBootstrap(
        ReadinessProbes(
            bridge = BridgeProbe { true },
            hook = HookPresenceProbe { HookPresence.UNKNOWN },
            dialer = DialerDefaultRoleProbe { null },
            config = ConfigProbe { config },
            calibration = calibration,
            network = NetworkProbe { true },
            live = live,
            supervision = SupervisionPhaseProbe { null },
        ),
        probeTimeoutMs = 1_000,
    )

    private suspend fun readEnvironment(client: BridgeClient): EnvironmentKey? =
        (client.diagnose() as? BridgeRead.Available)?.value

    private fun calibrationVerified(): BridgeCalibrationView = BridgeCalibrationView(
        available = true,
        supportedApiLevel = 37,
        verified = true,
        environmentComplete = true,
        environmentMatchesEvidence = true,
    )

    private fun completeEvidence(environment: EnvironmentKey): CalibrationEvidence = CalibrationEvidence(
        environment = environment,
        downlinkRecognized = true,
        peerHeardSpeech = true,
        microphoneIsolated = true,
        manualRestoreVerified = true,
        processFailureVerified = true,
        calibratedAtMs = 1L,
    )

    private fun TestScope.client(control: ControlTransport): BridgeClient = BridgeClient(
        control = control,
        pcm = { null },
        scope = this,
        clock = MonotonicClock { testScheduler.currentTime },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeTransport(
        private val handler: (RpcRequest) -> RpcResponse?,
    ) : ControlTransport {
        val requests = mutableListOf<RpcRequest>()

        override suspend fun request(request: RpcRequest): RpcResponse? {
            requests += request
            return handler(request)
        }

        override suspend fun close() = Unit
    }

    private companion object {
        val COMPLETE_ENVIRONMENT = EnvironmentKey(
            apiLevel = 37,
            romFingerprint = "fp",
            kernelVersion = "6.1",
            dialerPackageVersion = "1",
            frameworkVersion = "82",
            bridgeVersion = "1.0",
            audioPolicyDigest = "digest",
            simCarrier = "carrier",
            transport = "VoLTE",
            route = "handset",
        )

        const val STATUS_OK: String = "{\"bridgeVersion\":\"1.0\",\"protocolVersion\":1," +
            "\"revision\":3,\"enabled\":true,\"paired\":true,\"uptimeMs\":1," +
            "\"activeConnections\":0,\"rejectedConnections\":0," +
            "\"supervisionPhase\":\"IDLE\",\"supervisionOwned\":false," +
            "\"microphoneIsolated\":false,\"supervisionBlocked\":null," +
            "\"supervisionLastFault\":null,\"hookConnected\":true,\"dialerDefaultRole\":true}"

        const val DIAGNOSE_OK: String = "{\"environment\":{\"apiLevel\":37," +
            "\"romFingerprint\":\"fp\",\"kernelVersion\":\"6.1\"," +
            "\"dialerPackageVersion\":\"1\",\"frameworkVersion\":\"82\"," +
            "\"bridgeVersion\":\"1.0\",\"audioPolicyDigest\":\"digest\"," +
            "\"simCarrier\":\"carrier\",\"transport\":\"VoLTE\",\"route\":\"handset\"}," +
            "\"downlinkExtraction\":\"UNVERIFIED\",\"uplinkInjection\":\"UNVERIFIED\"," +
            "\"microphoneIsolation\":\"UNVERIFIED\",\"privilegedTermination\":\"UNVERIFIED\"," +
            "\"checkedAtMs\":1,\"notes\":\"audio:UNTESTED\"}"
    }
}
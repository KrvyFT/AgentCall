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
 * - **真机仍会 NOT_READY（calibration_not_verified）**：桥接 CalibrationStore（prepareAnswer
 *   的唯一权威）没有任何可读/可写通道，本机勾选不能解锁服务就绪。绝不伪造授权。
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
            environment = { environment },
            evidenceFor = { env -> completeEvidence(env) },
            bridgeCalibrationVerified = { true },
        )

        assertTrue(probe.canAutoAnswer())
        assertEquals(listOf("diagnose"), control.requests.map { it.method })
        client.close()
    }

    @Test
    fun missingDiagnoseEnvironmentBlocksCalibration() = runTest {
        val client = client(FakeTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "denied"))
        })
        val probe = BridgeCalibrationProbe(
            environment = { readEnvironment(client) },
            evidenceFor = { env -> completeEvidence(env) },
            bridgeCalibrationVerified = { true },
        )
        assertFalse(probe.canAutoAnswer())

        val incomplete = BridgeCalibrationProbe(
            environment = { readEnvironment(client) },
            evidenceFor = { null },
            bridgeCalibrationVerified = { true },
        )
        assertFalse(incomplete.canAutoAnswer())
        client.close()
    }

    /**
     * 真机阻塞：APK 本机五项证据即使齐全，桥接也没有任何读取/写入校准授权的通道，
     * 服务就绪必须保持 NOT_READY（calibration_not_verified），绝不伪造授权。
     */
    @Test
    fun localCalibrationEvidenceCannotUnlockReadinessWithoutABridgeWritePath() = runTest {
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
            environment = { environment },
            evidenceFor = { env -> completeEvidence(env) },
            bridgeCalibrationVerified = { true },
        )
        assertTrue("the local evidence itself is complete and bound", localOnly.canAutoAnswer())

        // 生产装配：bridgeCalibrationVerified 恒为 false（没有可读的桥接校准授权）。
        val productionCalibration = BridgeCalibrationProbe(
            environment = { environment },
            evidenceFor = { env -> completeEvidence(env) },
            bridgeCalibrationVerified = { false },
        )
        val bootstrap = bootstrap(
            live = BridgeLiveFactsProbe { client.status() },
            calibration = productionCalibration,
        )

        val decision = bootstrap.evaluate()

        assertFalse("a real device must stay NOT_READY here", decision.ready)
        assertEquals(listOf(BootstrapBlocker.CALIBRATION_BLOCKED), decision.blockers)
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
            calibration = CalibrationProbe { false },
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
        calibration: CalibrationProbe = CalibrationProbe { true },
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
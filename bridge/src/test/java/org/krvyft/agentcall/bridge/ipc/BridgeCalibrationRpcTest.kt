package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.config.CalibrationStore
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.core.model.ConfigPatch
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.SecretUpdate
import org.krvyft.agentcall.core.policy.CalibrationBlocker
import org.krvyft.agentcall.core.policy.CalibrationPolicy
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * B2：校准读写通道的线格式与安全边界。
 *
 * - 读取对 APP 只读开放，形状固定且不含任何密钥；
 * - 记录只属于 root（RolePolicyTest 覆盖角色表），请求只能提供五项人工测试事实与
 *   “预期环境”；证据环境/时间戳一律由服务端生成，未知/不一致环境拒绝；
 * - 五项全部通过后，CalibrationPolicy 才对该环境返回 true；环境变化立即失效。
 */
class BridgeCalibrationRpcTest {

    private lateinit var baseDir: File
    private lateinit var store: ConfigStore
    private lateinit var calibration: CalibrationStore
    private val cliPeer = PeerIdentity(uid = 0, role = PeerRole.CLI_ROOT)
    private val appPeer = PeerIdentity(
        uid = 10_001,
        role = PeerRole.APP,
        packageName = "org.krvyft.agentcall",
        signatureSha256 = "sig",
    )

    private val packages = object : PackageIdentitySource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = "sig"
        override fun versionName(packageName: String): String? = "1.0"
        override fun uidForPackage(packageName: String): Int? = 10_001
    }

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-calibration-rpc-").toFile()
        store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L })
        calibration = CalibrationStore(File(baseDir, "agentcall"), clockMs = { 12_345L })
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun environment(
        carrier: String = "Test Carrier",
        transport: String = "VoLTE",
        route: String = "SWB",
    ): EnvironmentKey = EnvironmentKey(
        apiLevel = 37,
        romFingerprint = "rom-fingerprint",
        kernelVersion = "6.1.0",
        dialerPackageVersion = "1.0",
        frameworkVersion = "xposed-82",
        bridgeVersion = "1.0",
        audioPolicyDigest = "digest",
        simCarrier = carrier,
        transport = transport,
        route = route,
    )

    private fun dispatcher(
        carrier: String = "Test Carrier",
        transport: String = "VoLTE",
        route: String = "SWB",
    ): ControlDispatcher = ControlDispatcher(
        store = store,
        packages = packages,
        probe = DeviceProbe(
            packages = packages,
            platform = object : PlatformFacts {
                override fun apiLevel(): Int = 37
                override fun romFingerprint(): String = "rom-fingerprint"
                override fun kernelVersion(): String = "6.1.0"
                override fun frameworkVersion(): String = "xposed-82"
                override fun audioPolicyDigest(): String = "digest"
                override fun simCarrier(): String = carrier
                override fun transport(): String = transport
                override fun route(): String = route
            },
            audioApiProbe = object : AudioApiProbe {
                override fun visibleMethods(): Set<String> = emptySet()
            },
            clockMs = { 0L },
            bridgeVersion = "1.0",
        ),
        shutdown = CountDownLatch(1),
        startedAtMs = 0L,
        calibration = calibration,
    )

    private fun request(method: String, payload: String = ""): RpcRequest =
        RpcRequest(id = "req-cal", method = method, payload = payload)

    private fun payloadJson(peer: PeerIdentity, method: String, payload: String = ""): JsonObject {
        val response = dispatcher().handle(peer, request(method, payload))
        assertNull("unexpected error: " + response.error, response.error)
        return JsonParser.parseString(response.payload).asJsonObject
    }

    private fun errorCode(method: String, payload: String = "", carrier: String = "Test Carrier"): String? =
        dispatcher(carrier = carrier).handle(cliPeer, request(method, payload)).error?.code

    private fun encodeEnvironment(environment: EnvironmentKey): JsonObject = JsonObject().apply {
        addProperty("apiLevel", environment.apiLevel)
        addProperty("romFingerprint", environment.romFingerprint)
        addProperty("kernelVersion", environment.kernelVersion)
        addProperty("dialerPackageVersion", environment.dialerPackageVersion)
        addProperty("frameworkVersion", environment.frameworkVersion)
        addProperty("bridgeVersion", environment.bridgeVersion)
        addProperty("audioPolicyDigest", environment.audioPolicyDigest)
        addProperty("simCarrier", environment.simCarrier)
        addProperty("transport", environment.transport)
        addProperty("route", environment.route)
    }

    private fun recordPayload(
        expected: EnvironmentKey? = environment(),
        steps: Map<String, Boolean> = ALL_STEP_KEYS.associateWith { true },
    ): String {
        val json = JsonObject()
        if (expected != null) json.add("expectedEnvironment", encodeEnvironment(expected))
        val stepsJson = JsonObject()
        steps.forEach { (name, passed) -> stepsJson.addProperty(name, passed) }
        json.add("steps", stepsJson)
        return json.toString()
    }

    // ------------------------------------------------------------------ record

    @Test
    fun recordRejectsMissingSteps() {
        val response = dispatcher().handle(
            cliPeer,
            request(
                RolePolicy.METHOD_CALIBRATION_RECORD,
                recordPayload(steps = ALL_STEP_KEYS.filter { it != "processFailureVerified" }.associateWith { true }),
            ),
        )

        assertEquals("payload_invalid", response.error?.code)
        assertNull("缺失步骤绝不能落盘", calibration.load())
    }

    @Test
    fun recordRejectsUnknownEnvironment() {
        assertEquals(
            "calibration_environment_unknown",
            errorCode(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayload(), carrier = PlatformFacts.UNKNOWN),
        )
        assertNull(calibration.load())
    }

    @Test
    fun recordRejectsStaleOrMismatchedEnvironment() {
        assertEquals(
            "calibration_environment_mismatch",
            errorCode(
                RolePolicy.METHOD_CALIBRATION_RECORD,
                recordPayload(expected = environment(carrier = "Stale Carrier")),
            ),
        )
        assertNull(calibration.load())

        // 预期环境里带 UNKNOWN 同样是“未知环境”，必须拒绝而不是当成通配。
        assertEquals(
            "calibration_environment_unknown",
            errorCode(
                RolePolicy.METHOD_CALIBRATION_RECORD,
                recordPayload(expected = environment(route = PlatformFacts.UNKNOWN)),
            ),
        )
        assertNull(calibration.load())
    }

    @Test
    fun recordWithAllFiveStepsAndValidEnvironmentEnablesAutoAnswer() {
        val response = dispatcher().handle(cliPeer, request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayload()))

        assertNull(response.error)
        val json = JsonParser.parseString(response.payload).asJsonObject
        assertTrue(json.get("recorded").asBoolean)
        assertTrue("五项全部通过后必须授权该环境", json.get("verified").asBoolean)
        assertEquals(12_345L, json.get("calibratedAtMs").asLong)
        assertEquals(0, json.getAsJsonArray("blockers").size())

        val stored = calibration.load()
        assertNotNull(stored)
        assertEquals("证据环境必须是服务端当前环境", environment(), stored!!.environment)
        assertEquals("时间戳必须由服务端生成", 12_345L, stored.calibratedAtMs)
        assertTrue(calibration.isVerified(environment()))
        assertTrue(CalibrationPolicy.canAutoAnswer(environment(), stored))
    }

    @Test
    fun environmentChangeInvalidatesTheRecordedEvidence() {
        dispatcher().handle(cliPeer, request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayload()))
        assertTrue(calibration.isVerified(environment()))

        // SIM 运营商变化后，旧证据不再适用于新环境。
        val changed = environment(carrier = "Other Carrier")
        assertFalse(calibration.isVerified(changed))
        val view = JsonParser.parseString(
            dispatcher(carrier = "Other Carrier").handle(
                appPeer,
                request(RolePolicy.METHOD_CALIBRATION_READ),
            ).payload,
        ).asJsonObject
        assertFalse(view.get("verified").asBoolean)
        val blockers = view.getAsJsonArray("blockers").map { it.asString }
        assertTrue(
            "环境变化必须显式报告 ENVIRONMENT_MISMATCH: " + blockers,
            blockers.contains(CalibrationBlocker.ENVIRONMENT_MISMATCH.name),
        )
    }

    // ------------------------------------------------------------------ read

    @Test
    fun calibrationReadHasAFixedShapeAndNeverExposesSecrets() {
        store.update(
            store.read().config.revision,
            ConfigPatch(secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace("sk-calibration-sentinel"))),
        )

        val view = payloadJson(appPeer, RolePolicy.METHOD_CALIBRATION_READ)

        assertEquals(
            setOf(
                "supportedApiLevel",
                "environment",
                "environmentComplete",
                "verified",
                "blockers",
                "evidenceEnvironment",
                "environmentMatchesEvidence",
                "steps",
                "calibratedAtMs",
            ),
            view.keySet(),
        )
        assertEquals(37, view.get("supportedApiLevel").asInt)
        assertTrue(view.get("environmentComplete").asBoolean)
        assertFalse(view.get("verified").asBoolean)
        assertTrue(view.get("evidenceEnvironment").isJsonNull)
        assertFalse(view.get("environmentMatchesEvidence").asBoolean)
        assertEquals(
            setOf(
                "downlinkRecognized",
                "peerHeardSpeech",
                "microphoneIsolated",
                "manualRestoreVerified",
                "processFailureVerified",
            ),
            view.getAsJsonObject("steps").keySet(),
        )
        assertEquals(
            setOf(
                "apiLevel",
                "romFingerprint",
                "kernelVersion",
                "dialerPackageVersion",
                "frameworkVersion",
                "bridgeVersion",
                "audioPolicyDigest",
                "simCarrier",
                "transport",
                "route",
            ),
            view.getAsJsonObject("environment").keySet(),
        )
        assertFalse("校准视图绝不含密钥", view.toString().contains("sk-calibration-sentinel"))
    }

    @Test
    fun calibrationReadReflectsRecordedStepsAndVerification() {
        dispatcher().handle(cliPeer, request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayload()))

        val view = payloadJson(appPeer, RolePolicy.METHOD_CALIBRATION_READ)

        assertTrue(view.get("verified").asBoolean)
        assertTrue(view.get("environmentMatchesEvidence").asBoolean)
        assertNotNull(view.get("evidenceEnvironment"))
        assertEquals(12_345L, view.get("calibratedAtMs").asLong)
        val steps = view.getAsJsonObject("steps")
        for (key in ALL_STEP_KEYS) {
            assertTrue("step " + key + " must be verified", steps.get(key).asBoolean)
        }
        assertEquals(environment(), decodeEnvironment(view.getAsJsonObject("environment")))
    }

    private fun decodeEnvironment(json: JsonObject): EnvironmentKey = EnvironmentKey(
        apiLevel = json.get("apiLevel").asInt,
        romFingerprint = json.get("romFingerprint").asString,
        kernelVersion = json.get("kernelVersion").asString,
        dialerPackageVersion = json.get("dialerPackageVersion").asString,
        frameworkVersion = json.get("frameworkVersion").asString,
        bridgeVersion = json.get("bridgeVersion").asString,
        audioPolicyDigest = json.get("audioPolicyDigest").asString,
        simCarrier = json.get("simCarrier").asString,
        transport = json.get("transport").asString,
        route = json.get("route").asString,
    )

    companion object {
        private val ALL_STEP_KEYS = setOf(
            "downlinkRecognized",
            "peerHeardSpeech",
            "microphoneIsolated",
            "manualRestoreVerified",
            "processFailureVerified",
        )
    }
}

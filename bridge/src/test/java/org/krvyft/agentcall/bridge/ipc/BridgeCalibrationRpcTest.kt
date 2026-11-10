package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
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
import org.krvyft.agentcall.bridge.AgentCallCli
import org.krvyft.agentcall.bridge.CliTransport
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.isCalibrationAuthorizedFor
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
    private val hookPeer = PeerIdentity(
        uid = 20_002,
        role = PeerRole.HOOK,
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
    ): String = recordPayloadWithAttestation(expected = expected, steps = steps, attestation = null)

    private fun attestationPayload(
        transport: String = "CELLULAR",
        route: String = "EARPIECE",
        manualTestCompleted: Boolean = true,
    ): JsonObject = JsonObject().apply {
        addProperty("transport", transport)
        addProperty("route", route)
        addProperty("manualTestCompleted", manualTestCompleted)
    }

    private fun recordPayloadWithAttestation(
        attestation: JsonObject? = attestationPayload(),
        expected: EnvironmentKey? = null,
        steps: Map<String, Boolean> = ALL_STEP_KEYS.associateWith { true },
    ): String {
        val json = JsonObject()
        if (expected != null) json.add("expectedEnvironment", encodeEnvironment(expected))
        val stepsJson = JsonObject()
        steps.forEach { (name, passed) -> stepsJson.addProperty(name, passed) }
        json.add("steps", stepsJson)
        if (attestation != null) json.add("operatorAttestation", attestation)
        return json.toString()
    }

    private fun readView(
        peer: PeerIdentity,
        transport: String = "VoLTE",
        route: String = "SWB",
        carrier: String = "Test Carrier",
    ): JsonObject {
        val response = dispatcher(carrier = carrier, transport = transport, route = route)
            .handle(peer, request(RolePolicy.METHOD_CALIBRATION_READ))
        assertNull("unexpected error: " + response.error, response.error)
        return JsonParser.parseString(response.payload).asJsonObject
    }

    private fun targetEnvironment(transport: String, route: String): EnvironmentKey =
        environment().copy(transport = transport, route = route)

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

    /**
     * Task 11D2a：新增的固定 CLI 命令 `calibrate` 只是 stdin 到既有 CLI_ROOT
     * `calibration.record` handler 的传输层；服务端校验（缺步骤、未知/不一致环境、
     * APP/HOOK 禁止）必须仍然生效。
     */
    @Test
    fun calibrateCliStillAppliesServerValidation() {
        val current = dispatcher()
        val transport = CliTransport { request -> current.handle(cliPeer, request) }

        fun calibrate(payload: String): Pair<Int, String> {
            val stdout = ByteArrayOutputStream()
            val exit = AgentCallCli.run(
                arrayOf("calibrate"),
                ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)),
                PrintStream(stdout, true, Charsets.UTF_8.name()),
                transport,
            )
            return exit to stdout.toString(Charsets.UTF_8.name())
        }

        val missingSteps = calibrate("{\"steps\":{\"downlinkRecognized\":true}}")
        assertEquals(AgentCallCli.EXIT_SERVER_ERROR, missingSteps.first)
        assertTrue(missingSteps.second.contains("\"payload_invalid\""))
        assertNull("缺失步骤绝不能落盘", calibration.load())

        val unknown = calibrate(recordPayload(expected = environment(carrier = PlatformFacts.UNKNOWN)))
        assertEquals(AgentCallCli.EXIT_SERVER_ERROR, unknown.first)
        assertTrue(unknown.second.contains("\"calibration_environment_unknown\""))

        val mismatch = calibrate(recordPayload(expected = environment(carrier = "Stale Carrier")))
        assertEquals(AgentCallCli.EXIT_SERVER_ERROR, mismatch.first)
        assertTrue(mismatch.second.contains("\"calibration_environment_mismatch\""))
        assertNull(calibration.load())

        // 角色拒绝发生在 CLI 能到达 handler 之前（固定方法表）。
        val recordRequest = request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayload())
        assertFalse(RolePolicy.authorize(appPeer, recordRequest))
        assertFalse(
            RolePolicy.authorize(
                PeerIdentity(uid = 20_002, role = PeerRole.HOOK, packageName = "org.krvyft.agentcall", signatureSha256 = "sig"),
                recordRequest,
            ),
        )

        // 合法负载通过 CLI 后仍然落盘并返回机器可读结果。
        val accepted = calibrate(recordPayload())
        assertEquals(AgentCallCli.EXIT_OK, accepted.first)
        assertTrue(accepted.second.contains("\"recorded\":true"))
        assertNotNull(calibration.load())
    }

    // ------------------------------------------------- 操作员背书（Task 11E / C1）

    /**
     * C1：桥接观测不到承载与音频路由（PlatformFacts 默认 UNKNOWN），root 操作员在
     * calibration.record 里为这两项背书。服务端仍然自己生成其余全部环境字段与时间戳。
     */
    @Test
    fun rootAttestationSuppliesOnlyTheTwoFactsTheBridgeCannotObserve() {
        val observed = targetEnvironment(PlatformFacts.UNKNOWN, PlatformFacts.UNKNOWN)
        val response = dispatcher(transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN)
            .handle(cliPeer, request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayloadWithAttestation()))

        assertNull("unexpected error: " + response.error, response.error)
        val json = JsonParser.parseString(response.payload).asJsonObject
        assertTrue(json.get("recorded").asBoolean)
        assertTrue("背书后的完整环境必须能通过 CalibrationPolicy", json.get("verified").asBoolean)
        val attestation = json.getAsJsonObject("operatorAttestation")
        assertTrue(attestation.get("operatorAttested").asBoolean)
        assertEquals("CELLULAR", attestation.get("transport").asString)
        assertEquals("EARPIECE", attestation.get("route").asString)

        val stored = calibration.load()
        assertNotNull(stored)
        assertEquals(
            "其余字段仍由服务端构建，背书的 transport/route 进入证据环境",
            observed.copy(transport = "CELLULAR", route = "EARPIECE"),
            stored!!.environment,
        )
        assertEquals("时间戳仍由服务端生成", 12_345L, stored.calibratedAtMs)
        assertTrue(CalibrationPolicy.canAutoAnswer(stored.environment, stored))
        assertTrue(
            "自动接听必须用观测环境 + 背书的有效环境判定",
            isCalibrationAuthorizedFor(calibration, observed),
        )
        assertFalse(
            "CalibrationPolicy 本身不变：原始 UNKNOWN 环境仍然不能自动接听",
            CalibrationPolicy.canAutoAnswer(observed, stored),
        )
    }

    /**
     * C1：calibration.read 必须把背书字段标成 operator_attested，绝不能让 UI/文档把
     * 操作员背书当成桥接实测；观测环境与有效环境分开可见。
     */
    @Test
    fun calibrationReadMarksAttestedFieldsInsteadOfPresentingThemAsMeasured() {
        dispatcher(transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN).handle(
            cliPeer,
            request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayloadWithAttestation()),
        )

        val view = readView(appPeer, transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN)

        assertTrue(view.get("verified").asBoolean)
        assertTrue(view.get("environmentMatchesEvidence").asBoolean)
        assertEquals(
            "environment 只报告桥接实测（这里观测不到）",
            PlatformFacts.UNKNOWN,
            view.getAsJsonObject("environment").get("transport").asString,
        )
        assertEquals(PlatformFacts.UNKNOWN, view.getAsJsonObject("environment").get("route").asString)
        assertEquals(
            "effectiveEnvironment 才是判定用的环境（含背书）",
            "CELLULAR",
            view.getAsJsonObject("effectiveEnvironment").get("transport").asString,
        )
        assertEquals("EARPIECE", view.getAsJsonObject("effectiveEnvironment").get("route").asString)
        val attestation = view.getAsJsonObject("operatorAttestation")
        assertTrue(attestation.get("operatorAttested").asBoolean)
        assertEquals("CELLULAR", attestation.get("transport").asString)
        assertEquals("EARPIECE", attestation.get("route").asString)
    }

    @Test
    fun unpresentedFactsAreNeverMarkedOperatorAttested() {
        val view = readView(appPeer, transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN)

        assertFalse(view.getAsJsonObject("operatorAttestation").get("operatorAttested").asBoolean)
        assertTrue(view.getAsJsonObject("operatorAttestation").get("transport").isJsonNull)
        assertTrue(view.getAsJsonObject("operatorAttestation").get("route").isJsonNull)
        assertFalse("观测环境缺字段时 environmentComplete 必须为 false", view.get("environmentComplete").asBoolean)
        assertFalse(view.get("verified").asBoolean)
    }

    @Test
    fun recordWithoutAttestationStaysClosedWhenTheBridgeCannotObserveTransportOrRoute() {
        assertEquals(
            "calibration_environment_unknown",
            dispatcher(transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN)
                .handle(cliPeer, request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayload()))
                .error?.code,
        )
        assertNull(calibration.load())

        assertEquals(
            "只缺一项同样必须失败关闭",
            "calibration_environment_unknown",
            dispatcher(transport = PlatformFacts.UNKNOWN, route = "SWB")
                .handle(cliPeer, request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayload()))
                .error?.code,
        )
        assertNull(calibration.load())
    }

    @Test
    fun onlyCliRootCanAttest() {
        for (peer in listOf(appPeer, hookPeer)) {
            val response = dispatcher(transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN)
                .handle(peer, request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayloadWithAttestation()))
            assertEquals("背书只能由 CLI_ROOT 发起", "forbidden", response.error?.code)
        }
        assertNull("APP/HOOK 的背书绝不能落盘", calibration.load())
    }

    @Test
    fun recordRejectsAttestationsOutsideTheFixedVocabulary() {
        val invalid = listOf(
            attestationPayload(transport = "VoLTE"),
            attestationPayload(transport = "cell"),
            attestationPayload(transport = ""),
            attestationPayload(route = "SWB"),
            attestationPayload(route = "speaker"),
            attestationPayload(manualTestCompleted = false),
            JsonObject().apply {
                addProperty("transport", "CELLULAR")
                addProperty("route", "EARPIECE")
            },
            attestationPayload().apply { addProperty("extra", "x") },
            JsonObject().apply {
                addProperty("transport", "CELLULAR")
                addProperty("route", "EARPIECE")
                addProperty("manualTestCompleted", "true")
            },
        )
        for (attestation in invalid) {
            val response = dispatcher(transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN)
                .handle(
                    cliPeer,
                    request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayloadWithAttestation(attestation = attestation)),
                )
            assertEquals("payload_invalid", response.error?.code)
            assertNull("被拒绝的背书绝不能落盘", calibration.load())
        }
    }

    @Test
    fun attestedEvidenceInvalidatesWhenTheDeviceEnvironmentChanges() {
        dispatcher(transport = PlatformFacts.UNKNOWN, route = PlatformFacts.UNKNOWN).handle(
            cliPeer,
            request(RolePolicy.METHOD_CALIBRATION_RECORD, recordPayloadWithAttestation()),
        )
        val observed = targetEnvironment(PlatformFacts.UNKNOWN, PlatformFacts.UNKNOWN)
        assertTrue(isCalibrationAuthorizedFor(calibration, observed))

        val changedCarrier = observed.copy(simCarrier = "Other Carrier")
        assertFalse(isCalibrationAuthorizedFor(calibration, changedCarrier))
        val view = readView(
            appPeer,
            transport = PlatformFacts.UNKNOWN,
            route = PlatformFacts.UNKNOWN,
            carrier = "Other Carrier",
        )
        assertFalse(view.get("verified").asBoolean)
        assertTrue(
            "环境变化必须显式报告 ENVIRONMENT_MISMATCH",
            view.getAsJsonArray("blockers").map { it.asString }
                .contains(CalibrationBlocker.ENVIRONMENT_MISMATCH.name),
        )
        assertFalse(
            "陈旧环境绝不能继续显示成 operator_attested",
            view.getAsJsonObject("operatorAttestation").get("operatorAttested").asBoolean,
        )

        assertFalse(
            "桥接开始观测到不同的已知承载时，旧背书必须失效",
            isCalibrationAuthorizedFor(calibration, targetEnvironment("VOWIFI", PlatformFacts.UNKNOWN)),
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
                "available",
                "supportedApiLevel",
                "environment",
                "effectiveEnvironment",
                "environmentComplete",
                "verified",
                "blockers",
                "evidenceEnvironment",
                "environmentMatchesEvidence",
                "operatorAttestation",
                "steps",
                "calibratedAtMs",
            ),
            view.keySet(),
        )
        assertTrue(view.get("available").asBoolean)
        assertEquals(
            setOf("operatorAttested", "transport", "route"),
            view.getAsJsonObject("operatorAttestation").keySet(),
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

    /**
     * C1：APP 侧的 calibration.read 目前没有生产消费者。为了让已有 UI 探针不改 RPC 面
     * 就能读到同样的数据，status/diagnose 内嵌**完全相同**的校准视图（含背书标记）。
     */
    @Test
    fun statusAndDiagnoseEmbedTheExactCalibrationReadView() {
        val current = dispatcher()

        fun call(peer: PeerIdentity, method: String): JsonObject {
            val response = current.handle(peer, request(method))
            assertNull("unexpected error: " + response.error, response.error)
            return JsonParser.parseString(response.payload).asJsonObject
        }

        val read = call(appPeer, RolePolicy.METHOD_CALIBRATION_READ)
        val status = call(appPeer, RolePolicy.METHOD_STATUS)
        val diagnose = call(appPeer, RolePolicy.METHOD_DIAGNOSE)

        assertEquals(
            "status.calibration 必须与 calibration.read 完全一致",
            read,
            status.getAsJsonObject("calibration"),
        )
        assertEquals(
            "diagnose.calibration 必须与 calibration.read 完全一致",
            read,
            diagnose.getAsJsonObject("calibration"),
        )
        assertTrue(status.getAsJsonObject("calibration").get("available").asBoolean)
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

package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.ipc.PackageIdentitySource
import org.krvyft.agentcall.core.policy.CalibrationBlocker
import org.krvyft.agentcall.core.policy.CalibrationPolicy

/**
 * B2：DeviceProbe 的环境事实必须来自平台读到的真实值；平台不能提供时保持显式 UNKNOWN，
 * 绝不按“通常值”猜测。CalibrationPolicy 能否满足完全取决于这些字段是否真实。
 */
class DeviceProbeEnvironmentFactsTest {

    private val packages = object : PackageIdentitySource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = "sig"
        override fun versionName(packageName: String): String? = "1.0"
        override fun uidForPackage(packageName: String): Int? = 10_001
    }

    private fun platform(
        simCarrier: String? = null,
        transport: String? = null,
        route: String? = null,
    ): PlatformFacts = object : PlatformFacts {
        override fun apiLevel(): Int = 37
        override fun romFingerprint(): String = "rom-fingerprint"
        override fun kernelVersion(): String = "6.1.0"
        override fun frameworkVersion(): String = "xposed-82"
        override fun audioPolicyDigest(): String = "digest"

        override fun simCarrier(): String = simCarrier ?: PlatformFacts.UNKNOWN
        override fun transport(): String = transport ?: PlatformFacts.UNKNOWN
        override fun route(): String = route ?: PlatformFacts.UNKNOWN
    }

    private fun probe(platform: PlatformFacts) = DeviceProbe(
        packages = packages,
        platform = platform,
        audioApiProbe = object : AudioApiProbe {
            override fun visibleMethods(): Set<String> = emptySet()
        },
        clockMs = { 0L },
        bridgeVersion = "1.0",
    )

    @Test
    fun environmentCarriesOnlyPlatformFactsAndExplicitUnknowns() {
        val environment = probe(platform()).inspect().environment

        assertEquals(37, environment.apiLevel)
        assertEquals("rom-fingerprint", environment.romFingerprint)
        assertEquals("6.1.0", environment.kernelVersion)
        assertEquals("1.0", environment.dialerPackageVersion)
        assertEquals("xposed-82", environment.frameworkVersion)
        assertEquals("1.0", environment.bridgeVersion)
        assertEquals("digest", environment.audioPolicyDigest)
        assertEquals("平台不给事实时必须显式 UNKNOWN", PlatformFacts.UNKNOWN, environment.simCarrier)
        assertEquals(PlatformFacts.UNKNOWN, environment.transport)
        assertEquals(PlatformFacts.UNKNOWN, environment.route)

        // 不完整的环境绝不能满足校准策略（不是猜一个默认值让它通过）。
        assertTrue(
            CalibrationBlocker.ENVIRONMENT_INCOMPLETE in CalibrationPolicy.blockers(
                environment,
                org.krvyft.agentcall.core.model.CalibrationEvidence(environment = environment),
            ),
        )
    }

    @Test
    fun platformProvidedCarrierTransportAndRouteAreReportedVerbatim() {
        val environment = probe(
            platform(simCarrier = "Test Carrier", transport = "VoLTE", route = "SWB"),
        ).inspect().environment

        assertEquals("Test Carrier", environment.simCarrier)
        assertEquals("VoLTE", environment.transport)
        assertEquals("SWB", environment.route)
    }

    @Test
    fun androidPlatformFactsReportsUnknownWhenTheSystemServiceIsUnavailable() {
        // JVM 单元测试没有运行中的 Android 框架：真实实现必须返回显式 UNKNOWN 而不是抛错。
        assertEquals(PlatformFacts.UNKNOWN, AndroidPlatformFacts().simCarrier())
    }

    // ------------------------------------------------- Task 11H1：真实框架/运营商来源

    @Test
    fun frameworkVersionIsObservedFromRootSystemPropertiesBeforeTheDirectClassSignal() {
        val facts = AndroidPlatformFacts(
            properties = SystemPropertySource { name ->
                when (name) {
                    "ro.lsposed.version" -> "1.9.2"
                    "gsm.sim.operator.alpha" -> "Property Carrier"
                    else -> null
                }
            },
            telephonyCarrier = { null },
            xposedBridgeVersion = { "82" },
        )

        assertEquals("root 属性是目标栈上的真实事实，优先于直接类检查", "1.9.2", facts.frameworkVersion())
        assertEquals("SIM 运营商可从根进程系统属性观测", "Property Carrier", facts.simCarrier())
    }

    @Test
    fun frameworkVersionFallsBackToTheDirectClassCheckAndThenUnknown() {
        assertEquals(
            "直接类检查仍是一个信号",
            "82",
            AndroidPlatformFacts(
                properties = SystemPropertySource.NONE,
                telephonyCarrier = { null },
                xposedBridgeVersion = { "82" },
            ).frameworkVersion(),
        )
        assertEquals(
            "两个来源都没有时必须是显式 UNKNOWN",
            PlatformFacts.UNKNOWN,
            AndroidPlatformFacts(
                properties = SystemPropertySource.NONE,
                telephonyCarrier = { null },
                xposedBridgeVersion = { null },
            ).frameworkVersion(),
        )
    }

    @Test
    fun blankOversizedControlCharacterOrUnknownPropertyValuesAreNotFacts() {
        for (value in listOf("", "   ", "1.9\n2", "x".repeat(129), PlatformFacts.UNKNOWN)) {
            val facts = AndroidPlatformFacts(
                properties = SystemPropertySource { if (it == "ro.vector.version") value else null },
                telephonyCarrier = { null },
                xposedBridgeVersion = { null },
            )
            assertEquals(
                "无效属性值必须保持 UNKNOWN：" + value,
                PlatformFacts.UNKNOWN,
                facts.frameworkVersion(),
            )
        }
    }

    @Test
    fun simCarrierFallsBackToTheSimOperatorPropertyWhenTelephonyIsUnavailable() {
        val facts = AndroidPlatformFacts(
            properties = SystemPropertySource { name ->
                if (name == "gsm.sim.operator.alpha") "SIM Carrier" else null
            },
            telephonyCarrier = { null },
            xposedBridgeVersion = { null },
        )

        assertEquals("SIM Carrier", facts.simCarrier())
    }
}

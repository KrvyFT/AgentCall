package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ------------------------------------- Task 11I：直接类检查的真实返回类型

    @Test
    fun directClassCheckAcceptsTheIntReturnedByRealLsposedAndEdxposed() {
        assertEquals(
            "真实 LSPosed/EdXposed 的 getXposedVersion() 返回 int，必须归一化成有界字符串",
            "82",
            reflectiveXposedBridgeVersion(IntVersionXposedBridge::class.java.name),
        )
    }

    @Test
    fun directClassCheckStillAcceptsAStringVersion() {
        assertEquals("82", reflectiveXposedBridgeVersion(StringVersionXposedBridge::class.java.name))
    }

    @Test
    fun directClassCheckKeepsTheUnknownFallbackForUnsupportedReturnTypes() {
        assertNull(reflectiveXposedBridgeVersion(UnsupportedVersionXposedBridge::class.java.name))
        assertNull(reflectiveXposedBridgeVersion("de.robv.android.xposed.DoesNotExist"))
    }

    // ------------------------------------- Task 11J：只接受整数正数 + 有界字符串

    @Test
    fun directClassCheckAcceptsTheLongReturnedBySomeBranches() {
        assertEquals(
            "long 返回值同样是整数版本，必须归一化成有界字符串",
            "99",
            reflectiveXposedBridgeVersion(LongVersionXposedBridge::class.java.name),
        )
    }

    @Test
    fun directClassCheckRejectsNonIntegralNegativeZeroAndOverflowNumbers() {
        for (className in listOf(
            DoubleVersionXposedBridge::class.java.name,
            NaNDoubleVersionXposedBridge::class.java.name,
            NegativeIntVersionXposedBridge::class.java.name,
            ZeroIntVersionXposedBridge::class.java.name,
            NegativeLongVersionXposedBridge::class.java.name,
            OverflowLongVersionXposedBridge::class.java.name,
        )) {
            assertNull(
                "Double/NaN/负数/零/溢出都不是可观测的整数版本，必须保持 UNKNOWN：" + className,
                reflectiveXposedBridgeVersion(className),
            )
        }
    }

    @Test
    fun directClassCheckKeepsStringVersionsBounded() {
        assertEquals(
            "合法的旧分支 String 版本仍被接受",
            "1.9.2",
            reflectiveXposedBridgeVersion(BoundedStringVersionXposedBridge::class.java.name),
        )
        for (className in listOf(
            OversizedStringVersionXposedBridge::class.java.name,
            ControlCharacterStringVersionXposedBridge::class.java.name,
            UnknownStringVersionXposedBridge::class.java.name,
            BlankStringVersionXposedBridge::class.java.name,
        )) {
            assertNull(
                "超长/控制字符/UNKNOWN/空白字符串都不是可观测事实，必须保持 UNKNOWN：" + className,
                reflectiveXposedBridgeVersion(className),
            )
        }
    }
}

/** 测试替身：真实 LSPosed/EdXposed 的 XposedBridge.getXposedVersion() 返回 int。 */
class IntVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Int = 82
    }
}

/** 测试替身：个别旧分支返回 String。 */
class StringVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): String = "82"
    }
}

/** 测试替身：不受支持的返回类型必须保持 UNKNOWN，而不是抛错。 */
class UnsupportedVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Boolean = true
    }
}

/** 测试替身：少数分支的版本号是 long；整数且为正时同样可接受。 */
class LongVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Long = 99L
    }
}

/** 测试替身：Double 不是整数版本（82.9 会被截断成 82），必须保持 UNKNOWN。 */
class DoubleVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Double = 82.9
    }
}

/** 测试替身：NaN 不是观测事实（toLong() 会给 0），必须保持 UNKNOWN。 */
class NaNDoubleVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Double = Double.NaN
    }
}

/** 测试替身：负数不是版本，必须保持 UNKNOWN。 */
class NegativeIntVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Int = -1
    }
}

/** 测试替身：零不是版本，必须保持 UNKNOWN。 */
class ZeroIntVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Int = 0
    }
}

/** 测试替身：负 long 不是版本，必须保持 UNKNOWN。 */
class NegativeLongVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Long = -82L
    }
}

/** 测试替身：超出 Int.MAX_VALUE 的 long 不是可接受的版本（不饱和），必须保持 UNKNOWN。 */
class OverflowLongVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): Long = Int.MAX_VALUE.toLong() + 1L
    }
}

/** 测试替身：合法的旧分支 String 版本。 */
class BoundedStringVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): String = "1.9.2"
    }
}

/** 测试替身：超过观测上限（128 字符）的字符串不是事实。 */
class OversizedStringVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): String = "8".repeat(129)
    }
}

/** 测试替身：含控制字符的字符串不是事实。 */
class ControlCharacterStringVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): String = "82\n83"
    }
}

/** 测试替身：显式 UNKNOWN 不是事实。 */
class UnknownStringVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): String = "UNKNOWN"
    }
}

/** 测试替身：空白字符串不是事实。 */
class BlankStringVersionXposedBridge {
    companion object {
        @JvmStatic
        fun getXposedVersion(): String = "   "
    }
}

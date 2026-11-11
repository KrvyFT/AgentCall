package org.krvyft.agentcall.bridge.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 11Q-b：设备（XQ-DQ72 / API 37）实测的三条环境事实。
 *
 * 1. 框架版本来自 `/data/adb/modules/zygisk_vector/module.prop` 的 `version=`
 *    行（本 ROM 的 Vector 模块目录名不在旧候选列表里）；
 * 2. SIM 运营商的系统属性是逗号分隔的多 SIM 列表
 *    （`China Unicom,China Unicom`），线上不能出现纯标点 `","`；
 * 3. 全部来源都读不到时仍是显式 UNKNOWN，绝不伪造。
 *
 * 每个 module.prop 路径都用临时目录覆盖（JVM 测试不依赖真机 /data）。
 */
class DeviceProbeModuleAndCarrierFactsTest {

    private companion object {
        const val DEVICE_FRAMEWORK_VERSION = "v2.2 (3080-88f8e1fa-JingMatrix-Vector)"

        /** 生产候选路径（顺序即优先级）：本 ROM 的 zygisk_vector 必须在内。 */
        val EXPECTED_MODULE_PROP_PATHS = listOf(
            "/data/adb/modules/zygisk_vector/module.prop",
            "/data/adb/modules/zygisk_lsposed/module.prop",
            "/data/adb/modules/lsposed/module.prop",
            "/data/adb/modules/vector/module.prop",
            "/data/adb/modules/edxposed/module.prop",
        )
    }

    private val tempRoots = mutableListOf<File>()

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempRoots += it }

    private fun moduleProp(
        moduleName: String,
        versionLine: String,
        root: File? = null,
    ): File {
        val dir = File(root ?: tempRoot("agentcall-module-"), moduleName)
        dir.mkdirs()
        val prop = File(dir, "module.prop")
        prop.writeText(
            "id=" + moduleName + "\n" +
                "name=" + moduleName + "\n" +
                versionLine + "\n",
        )
        return prop
    }

    private fun facts(
        properties: SystemPropertySource = SystemPropertySource.NONE,
        telephonyCarrier: () -> String? = { null },
        xposedBridgeVersion: () -> String? = { null },
        metadataPaths: List<String> = emptyList(),
    ): AndroidPlatformFacts = AndroidPlatformFacts(
        properties = properties,
        telephonyCarrier = telephonyCarrier,
        xposedBridgeVersion = xposedBridgeVersion,
        frameworkMetadataPaths = metadataPaths,
    )

    private fun missingPaths(): List<String> {
        val root = tempRoot("agentcall-no-modules-")
        return EXPECTED_MODULE_PROP_PATHS.map { File(root, it.removePrefix("/").replace('/', '_')).path }
    }

    // ------------------------------------------- B：frameworkVersion 的模块目录

    @Test
    fun productionMetadataPathsCoverTheDeviceModuleLocations() {
        assertEquals(EXPECTED_MODULE_PROP_PATHS, AndroidPlatformFacts.FRAMEWORK_METADATA_PATHS)
    }

    @Test
    fun deviceFrameworkVersionComesFromTheZygiskVectorModuleProp() {
        val prop = moduleProp("zygisk_vector", "version=" + DEVICE_FRAMEWORK_VERSION)

        assertEquals(
            "设备实测的 Vector 模块版本必须被读出（不能截断、不能改写）",
            DEVICE_FRAMEWORK_VERSION,
            facts(metadataPaths = listOf(prop.path)).frameworkVersion(),
        )
    }

    @Test
    fun everyKnownFrameworkModuleDirectoryYieldsItsVersion() {
        for (modulePath in EXPECTED_MODULE_PROP_PATHS) {
            val moduleName = modulePath.split('/').let { it[it.size - 2] }
            val version = "v2.2 (3080-88f8e1fa-JingMatrix-" + moduleName + ")"
            val prop = moduleProp(moduleName, "version=" + version)

            assertEquals(
                "候选路径必须覆盖 " + modulePath,
                version,
                facts(metadataPaths = listOf(prop.path)).frameworkVersion(),
            )
        }
    }

    @Test
    fun missingModuleDirectoriesFallBackToTheClassCheckAndThenUnknown() {
        assertEquals(
            "模块目录缺失时直接类检查仍是一个信号",
            "82",
            facts(metadataPaths = missingPaths(), xposedBridgeVersion = { "82" }).frameworkVersion(),
        )
        assertEquals(
            PlatformFacts.UNKNOWN,
            facts(metadataPaths = missingPaths()).frameworkVersion(),
        )
    }

    @Test
    fun firstNonBlankBoundedVersionWinsAndBlankOrOversizedValuesFallThrough() {
        val root = tempRoot("agentcall-module-order-")
        val blankFirst = moduleProp("zygisk_vector", "version=", root)
        val second = moduleProp("zygisk_lsposed", "version=" + DEVICE_FRAMEWORK_VERSION, root)

        assertEquals(
            "空白 version 不是事实，必须继续看下一个路径",
            DEVICE_FRAMEWORK_VERSION,
            facts(metadataPaths = listOf(blankFirst.path, second.path)).frameworkVersion(),
        )

        val oversizedFirst = moduleProp("zygisk_vector", "version=" + "9".repeat(200), root)
        assertEquals(
            "超长 version 不能被截断成事实",
            DEVICE_FRAMEWORK_VERSION,
            facts(metadataPaths = listOf(oversizedFirst.path, second.path)).frameworkVersion(),
        )

        val validFirst = moduleProp("zygisk_vector", "version=" + DEVICE_FRAMEWORK_VERSION, root)
        assertEquals(
            "第一个有界有效值优先",
            DEVICE_FRAMEWORK_VERSION,
            facts(metadataPaths = listOf(validFirst.path, second.path)).frameworkVersion(),
        )
    }

    @Test
    fun systemPropertyStillWinsOverModuleProp() {
        val prop = moduleProp("zygisk_vector", "version=" + DEVICE_FRAMEWORK_VERSION)

        assertEquals(
            "1.9.2",
            facts(
                properties = SystemPropertySource { name ->
                    if (name == "ro.lsposed.version") "1.9.2" else null
                },
                metadataPaths = listOf(prop.path),
            ).frameworkVersion(),
        )
    }

    // ------------------------------------------- C：simCarrier 的逗号列表

    @Test
    fun deviceCommaSeparatedSimOperatorAlphaBecomesTheCarrierName() {
        assertEquals(
            "设备实测 gsm.sim.operator.alpha=China Unicom,China Unicom 必须变成 China Unicom",
            "China Unicom",
            facts(
                properties = SystemPropertySource { name ->
                    if (name == "gsm.sim.operator.alpha") "China Unicom,China Unicom" else null
                },
            ).simCarrier(),
        )
    }

    @Test
    fun commaListWhitespaceAndSurroundingPunctuationAreNormalized() {
        assertEquals(
            "China Unicom",
            facts(
                properties = SystemPropertySource { name ->
                    if (name == "gsm.sim.operator.alpha") "  China Unicom , China Unicom  " else null
                },
            ).simCarrier(),
        )
        assertEquals(
            "China Unicom",
            facts(
                properties = SystemPropertySource { name ->
                    if (name == "gsm.sim.operator.alpha") "(China Unicom),China Unicom" else null
                },
            ).simCarrier(),
        )
        assertEquals(
            "China Unicom",
            facts(
                properties = SystemPropertySource { name ->
                    if (name == "gsm.sim.operator.alpha") ",China Unicom,China Unicom" else null
                },
            ).simCarrier(),
        )
    }

    @Test
    fun telephonyValueIsNormalizedWithTheSameRules() {
        assertEquals(
            "China Unicom",
            facts(telephonyCarrier = { "China Unicom,China Unicom" }).simCarrier(),
        )
    }

    @Test
    fun punctuationOnlySourceFallsThroughToTheNextSource() {
        assertEquals(
            "纯标点的属性不是事实，必须继续看下一个来源",
            "China Unicom",
            facts(
                properties = SystemPropertySource { name ->
                    when (name) {
                        "gsm.sim.operator.alpha" -> ","
                        "gsm.sim.operator.default-name" -> "China Unicom"
                        else -> null
                    }
                },
            ).simCarrier(),
        )
        assertEquals(
            "纯标点的 TelephonyManager 值也要落到属性来源",
            "China Unicom",
            facts(
                properties = SystemPropertySource { name ->
                    if (name == "gsm.sim.operator.alpha") "China Unicom" else null
                },
                telephonyCarrier = { " , " },
            ).simCarrier(),
        )
    }

    @Test
    fun emptyAndPunctuationOnlyValuesUltimatelyStayUnknown() {
        assertEquals(
            PlatformFacts.UNKNOWN,
            facts(
                properties = SystemPropertySource { name ->
                    when (name) {
                        "gsm.sim.operator.alpha" -> ","
                        "gsm.sim.operator.default-name" -> "..."
                        "ro.cdma.home.operator.alpha" -> " , ; "
                        else -> null
                    }
                },
                telephonyCarrier = { "" },
            ).simCarrier(),
        )
    }

    @Test
    fun plainCarrierNameIsReportedVerbatim() {
        assertEquals(
            "SIM Carrier",
            facts(
                properties = SystemPropertySource { name ->
                    if (name == "gsm.sim.operator.alpha") "SIM Carrier" else null
                },
            ).simCarrier(),
        )
        assertNull(carrierFact(" , "))
        assertEquals("China Unicom", carrierFact("China Unicom,China Unicom"))
    }
}

package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * shell 回退路径的纯解析测试（JVM，无 Android 依赖）。
 *
 * `pm list packages -U` 与 `dumpsys package <pkg>` 的真实输出形态是设备事实，
 * 这里用固定样本覆盖 uid 归属、共享 uid、损坏行、缺失字段与非法值。解析器只返回
 * 它能证明的事实，绝不猜测：没有 uid 的行不归属任何 uid，没有 versionName 的包
 * 返回 null（由调用方写 UNKNOWN）。
 */
class PackageShellParsingTest {

    private val pmList = listOf(
        "package:com.google.android.dialer uid:10123",
        "package:org.krvyft.agentcall uid:10124",
        "package:com.android.shared uid:1000",
        "package:com.android.shared2 uid:1000",
        "package:com.example.broken",
        "not a package line",
        "package:com.example.other uid:1001",
        "package: uid:1002",
    ).joinToString("\n")

    @Test
    fun pmListAttributesOnlyPackagesOwnedByTheRequestedUid() {
        assertEquals(listOf("com.google.android.dialer"), PackageShellParsing.packagesForUid(pmList, 10123))
        assertEquals(listOf("com.example.other"), PackageShellParsing.packagesForUid(pmList, 1001))
        assertEquals(emptyList<String>(), PackageShellParsing.packagesForUid(pmList, 9999))
    }

    @Test
    fun sharedUidListsEveryOwnedPackageAndKeepsOutputOrder() {
        assertEquals(
            listOf("com.android.shared", "com.android.shared2"),
            PackageShellParsing.packagesForUid(pmList, 1000),
        )
    }

    @Test
    fun malformedLinesAreIgnoredInsteadOfBeingAttributedToAUid() {
        // 没有 uid: 的行、空包名、非 package: 行都不得产生事实。
        assertEquals(emptyList<String>(), PackageShellParsing.packagesForUid("package:com.example.broken", 1003))
        assertEquals(emptyList<String>(), PackageShellParsing.packagesForUid("package:com.foo uid:abc", 1003))
        assertEquals(emptyList<String>(), PackageShellParsing.packagesForUid("garbage", 1003))
    }

    @Test
    fun duplicatePackageLinesAreDeduplicated() {
        val duplicated = "package:com.foo uid:1000\npackage:com.foo uid:1000"

        assertEquals(listOf("com.foo"), PackageShellParsing.packagesForUid(duplicated, 1000))
    }

    @Test
    fun negativeUidNeverMatchesAnOutputLine() {
        assertEquals(emptyList<String>(), PackageShellParsing.packagesForUid(pmList, -1))
    }

    @Test
    fun versionNameAndUserIdComeFromTheSinglePackageDump() {
        val dump = listOf(
            "Packages:",
            "  Package [com.google.android.dialer] (abc):",
            "    userId=10123",
            "    pkg=Package{123 com.google.android.dialer}",
            "    versionCode=123456",
            "    versionName=104.0.5112.79",
            "    signatures=[deadbeef]",
        ).joinToString("\n")

        assertEquals("104.0.5112.79", PackageShellParsing.versionName(dump))
        assertEquals(10123, PackageShellParsing.userId(dump))
    }

    @Test
    fun missingMalformedOrExplicitNullFactsStayNull() {
        assertNull(PackageShellParsing.versionName(""))
        assertNull(PackageShellParsing.versionName("  versionName="))
        assertNull(PackageShellParsing.versionName("  versionName=null"))
        assertNull(PackageShellParsing.versionName("  versionCode=1"))
        assertNull(PackageShellParsing.versionName("  versionName=" + "9".repeat(200)))
        assertNull(PackageShellParsing.userId("  userId=abc"))
        assertNull(PackageShellParsing.userId("  versionName=1.0"))
    }

    @Test
    fun resolveActivityBriefOutputYieldsTheHandlerPackage() {
        val brief = listOf(
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true",
            "com.google.android.dialer/.ui.DialtactsActivity",
        ).joinToString("\n")

        assertEquals("com.google.android.dialer", PackageShellParsing.dialHandlerPackage(brief))
        assertEquals(
            "com.google.android.dialer",
            PackageShellParsing.dialHandlerPackage("com.google.android.dialer/.ui.DialtactsActivity"),
        )
    }

    @Test
    fun resolveActivityWithoutComponentYieldsNull() {
        assertNull(PackageShellParsing.dialHandlerPackage("No activity found"))
        assertNull(PackageShellParsing.dialHandlerPackage(""))
        assertNull(PackageShellParsing.dialHandlerPackage("priority=0\nfoo bar"))
    }

    @Test
    fun onlyAnchoredFieldLinesAreParsed() {
        // 不能把日志/注释里出现的 versionName= 当成包事实（只接受行首字段）。
        assertNull(PackageShellParsing.versionName("note: versionName=9.9"))

        val indented = "    versionName=7.7"
        assertEquals("7.7", PackageShellParsing.versionName(indented))
    }
}

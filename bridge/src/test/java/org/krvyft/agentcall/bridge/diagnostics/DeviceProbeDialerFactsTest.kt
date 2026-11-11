package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.ipc.PackageFacts
import org.krvyft.agentcall.bridge.ipc.PackageFactsSource

/**
 * DeviceProbe 的拨号器事实必须来自分层 PackageFactsSource（Task 11N）。
 *
 * 在只有 shell 回退的 ROM 上，拨号器版本名仍可观测，签名显式 UNKNOWN；诊断报告
 * 必须同时保留 provider 码，便于设备日志/诊断页定位走的是哪条获取路径。
 */
class DeviceProbeDialerFactsTest {

    private class FakeFacts(
        private val facts: PackageFacts,
    ) : PackageFactsSource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = facts.signatureSha256
        override fun versionName(packageName: String): String? = facts.versionName
        override fun uidForPackage(packageName: String): Int? = facts.uid
        override fun dialerPackageFacts(): PackageFacts = facts
    }

    private fun probe(facts: PackageFacts, provider: String?) = DeviceProbe(
        packages = FakeFacts(facts),
        platform = object : PlatformFacts {
            override fun apiLevel(): Int = 37
            override fun romFingerprint(): String = "test"
            override fun kernelVersion(): String = "test"
            override fun frameworkVersion(): String = "UNKNOWN"
            override fun audioPolicyDigest(): String = "test"
        },
        audioApiProbe = object : AudioApiProbe {
            override fun visibleMethods(): Set<String> = emptySet()
        },
        clockMs = { 0L },
        bridgeVersion = "1.0",
        packageProvider = provider,
    )

    @Test
    fun dialerVersionAndSignatureComeFromThePackageFactsSource() {
        val report = probe(
            PackageFacts("com.google.android.dialer", "104.0", "c".repeat(64), 10_123),
            provider = "binder",
        ).inspect()

        assertEquals("104.0", report.environment.dialerPackageVersion)
        assertTrue(report.notes.contains("dialerVersion=104.0"))
        assertTrue(report.notes.contains("dialerSignatureSha256=" + "c".repeat(64)))
        assertTrue("provider 码必须进入诊断 notes：" + report.notes, report.notes.contains("packageProvider=binder"))
    }

    @Test
    fun unknownSignatureOnTheShellPathIsReportedAsExplicitUnknown() {
        val report = probe(
            PackageFacts("com.google.android.dialer", "104.0", null, 10_123),
            provider = "shell",
        ).inspect()

        assertEquals("104.0", report.environment.dialerPackageVersion)
        assertTrue(report.notes.contains("dialerSignatureSha256=UNKNOWN"))
        assertTrue(report.notes.contains("packageProvider=shell"))
    }
}

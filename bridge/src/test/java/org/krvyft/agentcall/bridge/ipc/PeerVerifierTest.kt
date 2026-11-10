package org.krvyft.agentcall.bridge.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.config.PairingRecord
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole

/**
 * 身份判定核心的纯 JVM 测试（spec §6.4）。
 *
 * [PackageIdentitySource] 是纯接口，测试用 fake 控制“某个 uid 拥有哪个包、签名和版本”，
 * 从而覆盖 uid → 包 → 签名/版本 → 配对记录的全部拒绝分支。真实内核凭据由
 * androidTest/PeerCredentialsTest 在设备上验证；两者证明的东西不同，不能互相替代。
 *
 * 消息内容不参与身份：verify 只接收内核凭据，配对记录里的 uid 也不能被其他 uid 冒用。
 */
class PeerVerifierTest {

    private val appPackage = "org.krvyft.agentcall"
    private val dialerPackage = "com.google.android.dialer"
    private val appUid = 10_123
    private val dialerUid = 10_124
    private val appSignature = "a".repeat(64)
    private val dialerSignature = "b".repeat(64)

    private class FakePackageIdentity : PackageIdentitySource {
        val packagesByUid = mutableMapOf<Int, List<String>>()
        val signatures = mutableMapOf<String, String>()
        val versions = mutableMapOf<String, String>()

        override fun packagesForUid(uid: Int): List<String> = packagesByUid[uid].orEmpty()

        override fun signatureSha256(packageName: String): String? = signatures[packageName]

        override fun versionName(packageName: String): String? = versions[packageName]

        override fun uidForPackage(packageName: String): Int? =
            packagesByUid.entries.firstOrNull { packageName in it.value }?.key
    }

    private fun installed(): FakePackageIdentity = FakePackageIdentity().apply {
        packagesByUid[appUid] = listOf(appPackage)
        packagesByUid[dialerUid] = listOf(dialerPackage)
        signatures[appPackage] = appSignature
        signatures[dialerPackage] = dialerSignature
        versions[appPackage] = "1.0"
        versions[dialerPackage] = "100.0"
    }

    private fun record(): PairingRecord = PairingRecord(
        appPackage = appPackage,
        appUid = appUid,
        appSignatureSha256 = appSignature,
        appVersionName = "1.0",
        dialerPackage = dialerPackage,
        dialerUid = dialerUid,
        dialerSignatureSha256 = dialerSignature,
        dialerVersionName = "100.0",
        pairedAtMs = 1_700_000_000_000L,
    )

    private fun verify(uid: Int, packages: PackageIdentitySource, pairing: PairingRecord? = record()): PeerVerification =
        PeerVerifier(packages) { pairing }.verify(PeerCredentials(uid = uid, pid = 4321))

    private fun rejected(verification: PeerVerification, expectedCode: String) {
        assertTrue("expected rejection, got " + verification, verification is PeerVerification.Rejected)
        assertEquals(expectedCode, (verification as PeerVerification.Rejected).code)
    }

    private fun identity(verification: PeerVerification): PeerIdentity {
        assertTrue("expected trust, got " + verification, verification is PeerVerification.Trusted)
        return (verification as PeerVerification.Trusted).identity
    }

    @Test
    fun rootUidIsCliRootWithoutPairing() {
        val identity = identity(verify(uid = 0, packages = installed(), pairing = null))

        assertEquals(PeerRole.CLI_ROOT, identity.role)
        assertEquals(0, identity.uid)
    }

    @Test
    fun unpairedPeerIsRejected() {
        rejected(verify(uid = appUid, packages = installed(), pairing = null), PeerVerifier.ERROR_UNPAIRED)
    }

    @Test
    fun unknownUidIsRejected() {
        rejected(verify(uid = 20_000, packages = installed()), PeerVerifier.ERROR_UNKNOWN_UID)
    }

    @Test
    fun uidOwningAnotherPackageIsRejected() {
        val packages = installed().apply {
            packagesByUid[appUid] = listOf("org.example.other")
            signatures["org.example.other"] = appSignature
            versions["org.example.other"] = "1.0"
        }

        rejected(verify(uid = appUid, packages = packages), PeerVerifier.ERROR_UNTRUSTED)
    }

    @Test
    fun signatureMismatchIsRejected() {
        val packages = installed().apply { signatures[appPackage] = "c".repeat(64) }

        rejected(verify(uid = appUid, packages = packages), PeerVerifier.ERROR_UNTRUSTED)
    }

    @Test
    fun unresolvedSignatureIsRejected() {
        val packages = installed().apply { signatures.remove(appPackage) }

        rejected(verify(uid = appUid, packages = packages), PeerVerifier.ERROR_UNTRUSTED)
    }

    @Test
    fun versionChangeSincePairingIsRejected() {
        // 签名未变但版本变了：原配对证据不再覆盖当前二进制，必须重新配对。
        val packages = installed().apply { versions[appPackage] = "2.0" }

        rejected(verify(uid = appUid, packages = packages), PeerVerifier.ERROR_VERSION_MISMATCH)
    }

    @Test
    fun uidInThePairingRecordCannotBeClaimedByAnotherUid() {
        // fake 让另一个 uid 也“拥有”同名包且签名/版本一致；配对记录里的 uid 仍必须匹配，
        // 因为 uid 是内核对端凭据，不是消息自称。
        val packages = installed().apply { packagesByUid[99_999] = listOf(appPackage) }

        rejected(verify(uid = 99_999, packages = packages), PeerVerifier.ERROR_UID_MISMATCH)
    }

    @Test
    fun pairedAppIsTrustedWithKernelUid() {
        val identity = identity(verify(uid = appUid, packages = installed()))

        assertEquals(PeerRole.APP, identity.role)
        assertEquals(appUid, identity.uid)
        assertEquals(appPackage, identity.packageName)
        assertEquals(appSignature, identity.signatureSha256)
        assertFalse(identity.uid == 0)
    }

    @Test
    fun pairedDialerIsHook() {
        val identity = identity(verify(uid = dialerUid, packages = installed()))

        assertEquals(PeerRole.HOOK, identity.role)
        assertEquals(dialerUid, identity.uid)
        assertEquals(dialerPackage, identity.packageName)
    }

    @Test
    fun signatureComparisonIsCaseInsensitive() {
        val packages = installed().apply { signatures[appPackage] = appSignature.uppercase() }

        assertEquals(PeerRole.APP, identity(verify(uid = appUid, packages = packages)).role)
    }

    @Test
    fun unknownVersionMatchesUnknownRecordedVersion() {
        // PackageManager 可能返回 null versionName；配对时记录 UNKNOWN，连接时仍为 UNKNOWN 不误拒。
        val packages = installed().apply { versions.remove(appPackage) }
        val pairing = record().copy(appVersionName = ConfigStore.UNKNOWN_VERSION)

        val identity = identity(verify(uid = appUid, packages = packages, pairing = pairing))

        assertEquals(PeerRole.APP, identity.role)
        assertNotNull(identity.packageName)
    }
}

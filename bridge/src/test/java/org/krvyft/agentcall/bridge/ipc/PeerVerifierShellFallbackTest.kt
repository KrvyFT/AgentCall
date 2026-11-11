package org.krvyft.agentcall.bridge.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.config.PairingRecord
import org.krvyft.agentcall.core.model.PeerRole

/**
 * shell 回退 provider 的 fail-closed 契约（JVM）。
 *
 * shell 能证明 uid→包与版本，但拿不到签名证书：此时连接核验必须拒绝（显式
 * “签名无法解析”），而不是把未知签名当成“与配对记录一致”。uid 0 的 CLI_ROOT
 * 不依赖包签名，仍是唯一不受影响的 lane。
 */
class PeerVerifierShellFallbackTest {

    private val uid = 10_123
    private val packageName = "org.krvyft.agentcall"

    private object ShellFacts : PackageFactsSource {
        override fun packagesForUid(uid: Int): List<String> = if (uid == 10_123) listOf("org.krvyft.agentcall") else emptyList()
        override fun signatureSha256(packageName: String): String? = null
        override fun versionName(packageName: String): String? = "1.0"
        override fun uidForPackage(packageName: String): Int? = 10_123
    }

    private fun pairing(): PairingRecord = PairingRecord(
        appPackage = packageName,
        appUid = uid,
        appSignatureSha256 = "a".repeat(64),
        appVersionName = "1.0",
        dialerPackage = PeerVerifier.DIALER_PACKAGE,
        dialerUid = 10_124,
        dialerSignatureSha256 = "b".repeat(64),
        dialerVersionName = "100.0",
        pairedAtMs = 1_700_000_000_000L,
    )

    @Test
    fun unavailableSignatureOnTheShellPathIsRejectedInsteadOfTrusted() {
        val verification = PeerVerifier(ShellFacts) { pairing() }
            .verify(PeerCredentials(uid = uid, pid = 4321))

        assertTrue("shell 路径的未知签名绝不能被信任", verification is PeerVerification.Rejected)
        assertEquals(PeerVerifier.ERROR_UNTRUSTED, (verification as PeerVerification.Rejected).code)
    }

    @Test
    fun rootUidStillUsesTheKernelCredentialLaneWithoutPackageFacts() {
        val verification = PeerVerifier(ShellFacts) { null }
            .verify(PeerCredentials(uid = 0, pid = 1))

        assertTrue(verification is PeerVerification.Trusted)
        assertEquals(PeerRole.CLI_ROOT, (verification as PeerVerification.Trusted).identity.role)
    }

    @Test
    fun shellDialerFactsExposeVersionButNoSignature() {
        val facts = ShellFacts.dialerPackageFacts()

        assertEquals("1.0", facts.versionName)
        assertEquals(null, facts.signatureSha256)
    }
}

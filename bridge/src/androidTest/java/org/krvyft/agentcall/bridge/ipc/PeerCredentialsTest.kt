package org.krvyft.agentcall.bridge.ipc

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.krvyft.agentcall.bridge.config.PairingRecord
import org.krvyft.agentcall.core.model.PeerRole

/**
 * 真机凭据验证（spec §6.4）：本机无测试设备，本文件只编译、不执行，
 * 因此不能用它声称“内核对端凭据验证成功”。
 *
 * 能证明的事：同一进程通过 abstract Unix socket 连接后，服务端能从
 * LocalSocket#getPeerCredentials 读到真实 uid/pid；没有配对记录时即便是同 UID
 * 也不能成为 APP/HOOK；配对记录只放行签名匹配的那个包。
 */
@RunWith(AndroidJUnit4::class)
class PeerCredentialsTest {

    @Test
    fun kernelCredentialsIdentifyTheConnectingProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val address = "org.krvyft.agentcall.test.creds." + UUID.randomUUID().toString()
        // LocalServerSocket(String) 绑定 abstract 命名空间。
        val server = LocalServerSocket(address)
        val client = LocalSocket()
        try {
            client.connect(LocalSocketAddress(address, LocalSocketAddress.Namespace.ABSTRACT))
            val accepted = server.accept()
            try {
                val credentials = accepted.readPeerCredentials()

                // UID/PID 来自内核，不是测试里传入的任何字符串。
                assertEquals(Process.myUid(), credentials.uid)
                assertEquals(Process.myPid(), credentials.pid)

                // 未配对：同 UID 也不能凭连接自称 APP/HOOK。
                val unpaired = PeerVerifier(AndroidPackageIdentitySource(context.packageManager)) { null }
                val verification = unpaired.verify(credentials)
                assertTrue(verification is PeerVerification.Rejected)
                assertEquals(
                    PeerVerifier.ERROR_UNPAIRED,
                    (verification as PeerVerification.Rejected).code,
                )
            } finally {
                accepted.close()
            }
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun pairingRecordGrantsOnlyTheSignedPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = AndroidPackageIdentitySource(context.packageManager)
        val ownSignature = packages.signatureSha256(context.packageName)
        assertTrue("target package signature must be resolvable", ownSignature != null)

        val record = PairingRecord(
            appPackage = context.packageName,
            appSignatureSha256 = ownSignature!!,
            dialerPackage = PeerVerifier.DIALER_PACKAGE,
            dialerSignatureSha256 = "0".repeat(64),
            pairedAtMs = 0L,
        )
        val verifier = PeerVerifier(packages) { record }

        val trusted = verifier.verify(PeerCredentials(uid = Process.myUid(), pid = Process.myPid()))
        assertTrue(trusted is PeerVerification.Trusted)
        val identity = (trusted as PeerVerification.Trusted).identity
        assertEquals(PeerRole.APP, identity.role)
        assertEquals(context.packageName, identity.packageName)
        assertEquals(ownSignature, identity.signatureSha256)

        // 另一个 uid 即使共享同一份配对记录也不会被信任。
        val unknownUid = verifier.verify(PeerCredentials(uid = 20_000, pid = 1))
        assertTrue(unknownUid is PeerVerification.Rejected)
    }

    @Test
    fun pairingForAnotherPackageDoesNotTrustThisUid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = AndroidPackageIdentitySource(context.packageManager)
        val ownSignature = packages.signatureSha256(context.packageName)
        assertTrue(ownSignature != null)

        val record = PairingRecord(
            appPackage = "org.example.not.installed",
            appSignatureSha256 = ownSignature!!,
            dialerPackage = PeerVerifier.DIALER_PACKAGE,
            dialerSignatureSha256 = "0".repeat(64),
            pairedAtMs = 0L,
        )
        val verifier = PeerVerifier(packages) { record }

        val verification = verifier.verify(PeerCredentials(uid = Process.myUid(), pid = Process.myPid()))

        assertTrue(verification is PeerVerification.Rejected)
        assertEquals(PeerVerifier.ERROR_UNTRUSTED, (verification as PeerVerification.Rejected).code)
    }
}
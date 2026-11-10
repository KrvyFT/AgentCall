package org.krvyft.agentcall.bridge.ipc

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import java.io.IOException
import java.security.MessageDigest
import org.krvyft.agentcall.bridge.config.PairingRecord
import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole

/**
 * 内核给出的对端凭据。由 LocalSocket#getPeerCredentials 提供，不来自消息内容。
 */
data class PeerCredentials(val uid: Int, val pid: Int)

/** 凭据核验结论；拒绝时 [PeerVerification.Rejected.code] 是机器可读错误码。 */
sealed interface PeerVerification {
    data class Trusted(val identity: PeerIdentity) : PeerVerification

    data class Rejected(val code: String, val message: String) : PeerVerification
}

/**
 * 包名/签名/版本查询。生产实现使用系统 PackageManager；仪器测试注入真实
 * PackageManager，JVM 测试不应伪造内核凭据。
 */
interface PackageIdentitySource {
    /** 该 UID 拥有的已安装包；共享 UID 可能有多个。 */
    fun packagesForUid(uid: Int): List<String>

    /** 包签名证书的 SHA-256 小写十六进制；无法解析返回 null。 */
    fun signatureSha256(packageName: String): String?

    /** 包版本名；无法解析返回 null。 */
    fun versionName(packageName: String): String?

    /** 包当前安装所属的 Linux uid；未安装或无法解析返回 null。配对时记录，连接时复核。 */
    fun uidForPackage(packageName: String): Int?
}

/** [PackageIdentitySource] 的 Android 实现（PackageManager）。 */
class AndroidPackageIdentitySource(private val packageManager: PackageManager) : PackageIdentitySource {

    override fun packagesForUid(uid: Int): List<String> =
        packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    override fun signatureSha256(packageName: String): String? {
        val info = try {
            packageManager.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)
        } catch (missing: PackageManager.NameNotFoundException) {
            return null
        }
        val signingInfo = info.signingInfo ?: return null
        val signer = signingInfo.apkContentsSigners?.firstOrNull() ?: return null
        return PeerVerifier.sha256Hex(signer.toByteArray())
    }

    override fun versionName(packageName: String): String? {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            return null
        }
        return info.versionName
    }

    override fun uidForPackage(packageName: String): Int? {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            return null
        }
        val applicationInfo = info.applicationInfo ?: return null
        return applicationInfo.uid
    }

    private companion object {
        /** PackageManager.GET_SIGNING_CERTIFICATES 的字面值，避免 API 级别分支。 */
        const val GET_SIGNING_CERTIFICATES: Int = 0x08000000
    }
}

/**
 * app_process 运行容器里的系统 PackageManager。
 *
 * 单独进程由 root 启动，没有 Application/Context；这里走
 * ActivityThread.systemMain() + getSystemContext()。解析失败返回 null，
 * 调用方必须拒绝启动（fail closed），不得退化成“信任所有对端”。
 *
 * 未在真机验证：不同 ROM 上该反射路径与隐藏 API 策略需实测。
 */
object RootFramework {
    fun packageManager(): PackageManager? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
        systemContext.packageManager
    } catch (t: Throwable) {
        null
    }
}

/**
 * 用内核凭据 + 配对记录判定对端角色（spec §6.4）。
 *
 * 判定顺序：uid 0 → CLI_ROOT；其余 uid 必须由 PackageManager 解析出包名，
 * 且包名、配对时记录的 uid、签名摘要与版本名全部匹配配对记录才能成为 APP 或 HOOK。
 * 占用共享 UID 的其他进程即使能连上 socket，也因包名/uid/签名不匹配被拒绝。
 * 消息里自称的 uid/角色/签名完全不参与判定。
 *
 * 版本判定是显式的 fail-closed 取舍：配对记录里的版本是授权锚定证据的一部分
 * （spec §6.4 “包名、用户 ID、签名及版本在配对时记录并核验”）。签名未变的包升级后
 * 会得到 [ERROR_VERSION_MISMATCH]，必须重新执行 root CLI 配对；不静默接受未核验的
 * 新二进制。旧配对文件缺少 uid/版本字段按损坏处理（重新配对），绝不降级成只比包名。
 */
class PeerVerifier(
    private val packages: PackageIdentitySource,
    private val pairing: () -> PairingRecord?,
) {

    fun verify(credentials: PeerCredentials): PeerVerification {
        if (credentials.uid < 0) {
            return PeerVerification.Rejected(ERROR_CREDENTIALS_INVALID, "peer uid is invalid")
        }
        if (credentials.uid == 0) {
            return PeerVerification.Trusted(
                PeerIdentity(uid = 0, role = PeerRole.CLI_ROOT, protocolVersion = PROTOCOL_VERSION),
            )
        }
        val record = pairing() ?: return PeerVerification.Rejected(ERROR_UNPAIRED, "bridge is not paired")
        val candidates = packages.packagesForUid(credentials.uid)
        if (candidates.isEmpty()) {
            return PeerVerification.Rejected(ERROR_UNKNOWN_UID, "uid is not owned by an installed package")
        }
        for (packageName in candidates) {
            if (packageName == record.appPackage) {
                return verifyPairedLane(
                    uid = credentials.uid,
                    expectedUid = record.appUid,
                    packageName = packageName,
                    expectedSignature = record.appSignatureSha256,
                    expectedVersion = record.appVersionName,
                    role = PeerRole.APP,
                )
            }
            if (packageName == record.dialerPackage) {
                return verifyPairedLane(
                    uid = credentials.uid,
                    expectedUid = record.dialerUid,
                    packageName = packageName,
                    expectedSignature = record.dialerSignatureSha256,
                    expectedVersion = record.dialerVersionName,
                    role = PeerRole.HOOK,
                )
            }
        }
        return PeerVerification.Rejected(ERROR_UNTRUSTED, "uid/package does not match the pairing record")
    }

    /**
     * 一个已配对 lane 的完整核验：签名 → uid → 版本。任何一项不符都拒绝，
     * 错误码区分“包/签名不可信”“uid 已不属于该包”“配对后版本变了”。
     */
    private fun verifyPairedLane(
        uid: Int,
        expectedUid: Int,
        packageName: String,
        expectedSignature: String,
        expectedVersion: String,
        role: PeerRole,
    ): PeerVerification {
        val signature = packages.signatureSha256(packageName)
            ?: return PeerVerification.Rejected(ERROR_UNTRUSTED, "package signature could not be resolved")
        if (!signatureMatches(signature, expectedSignature)) {
            return PeerVerification.Rejected(ERROR_UNTRUSTED, "package signature does not match the pairing record")
        }
        if (uid != expectedUid) {
            // 卸载重装会换 uid：同一包名也不再是配对时的那份身份。
            return PeerVerification.Rejected(ERROR_UID_MISMATCH, "uid no longer belongs to the paired package")
        }
        val version = packages.versionName(packageName) ?: UNKNOWN_VERSION
        if (version != expectedVersion) {
            return PeerVerification.Rejected(ERROR_VERSION_MISMATCH, "package version changed since pairing; re-pair required")
        }
        return PeerVerification.Trusted(
            PeerIdentity(
                uid = uid,
                role = role,
                packageName = packageName,
                signatureSha256 = signature,
                protocolVersion = PROTOCOL_VERSION,
            ),
        )
    }

    private fun signatureMatches(actual: String, expected: String): Boolean =
        MessageDigest.isEqual(
            actual.lowercase().toByteArray(Charsets.UTF_8),
            expected.lowercase().toByteArray(Charsets.UTF_8),
        )

    companion object {
        /** Google 电话包名：只作为目标常量，不作为本工程 namespace。 */
        const val DIALER_PACKAGE: String = "com.google.android.dialer"

        /** AgentCall APK 包名（配对时的默认 APP 候选）。 */
        const val AGENTCALL_PACKAGE: String = "org.krvyft.agentcall"

        /** 配对记录缺少可解析的版本名时的显式占位（与 [org.krvyft.agentcall.bridge.config.ConfigStore.UNKNOWN_VERSION] 一致）。 */
        private const val UNKNOWN_VERSION: String = "UNKNOWN"

        const val ERROR_CREDENTIALS_INVALID: String = "peer_credentials_invalid"
        const val ERROR_UNPAIRED: String = "peer_unpaired"
        const val ERROR_UNKNOWN_UID: String = "peer_unknown_uid"
        const val ERROR_UNTRUSTED: String = "peer_untrusted"

        /** 内核 uid 已不属于配对时记录的 uid（卸载重装/包被替换）。 */
        const val ERROR_UID_MISMATCH: String = "peer_uid_mismatch"

        /** 包版本与配对记录不一致：必须重新配对。 */
        const val ERROR_VERSION_MISMATCH: String = "peer_version_mismatch"

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val builder = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return builder.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}

/**
 * 从已接受的本地 socket 读取内核凭据；读取失败视为凭据无效，绝不回退到消息自述。
 */
fun LocalSocket.readPeerCredentials(): PeerCredentials {
    val credentials = try {
        getPeerCredentials()
    } catch (io: IOException) {
        throw IOException("peer credentials unavailable", io)
    } ?: throw IOException("peer credentials unavailable")
    return PeerCredentials(uid = credentials.uid, pid = credentials.pid)
}

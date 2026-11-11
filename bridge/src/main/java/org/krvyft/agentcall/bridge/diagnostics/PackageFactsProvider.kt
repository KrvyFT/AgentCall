package org.krvyft.agentcall.bridge.diagnostics

import android.content.Intent
import android.net.Uri
import org.krvyft.agentcall.bridge.ipc.AndroidPackageIdentitySource
import org.krvyft.agentcall.bridge.ipc.PackageFacts
import org.krvyft.agentcall.bridge.ipc.PackageFactsSource
import org.krvyft.agentcall.bridge.ipc.PackageProviderKind
import org.krvyft.agentcall.bridge.ipc.PeerVerifier
import org.krvyft.agentcall.bridge.ipc.RootFramework

/** 一个候选获取路径：工厂返回 null/抛错即视为该层不可用。 */
data class PackageProviderCandidate(
    val kind: PackageProviderKind,
    val create: () -> PackageFactsSource?,
)

/** 已解析的 provider：设备日志只需要 [kind]，运行期只需要 [source]。 */
data class ResolvedPackageFacts(
    val kind: PackageProviderKind,
    val source: PackageFactsSource,
)

/**
 * 分层 provider 选择（Task 11N）与缓存。
 *
 * 顺序是安全契约：先沿用 ActivityThread 系统 Context；失败后依次尝试 AppGlobals 与
 * ServiceManager + IPackageManager 的 binder 路径；只有全部 binder 路径都失败才退到
 * shell 事实源（签名不可验证，连接核验会 fail closed）。选择结果只写一次
 * `package_provider=<code>`，每次失败写 `package_provider_attempt=<code> result=unavailable`，
 * 设备日志据此可以直接判断走的是哪条路径。
 *
 * 纯 JVM：候选工厂是普通 lambda，选择/缓存/日志顺序都有单元测试；Android 构造函数
 * 只在诊断/连接真正调用时才会触达。
 */
class PackageProviderChain(
    private val candidates: List<PackageProviderCandidate>,
    private val log: (String) -> Unit = {},
) {
    @Volatile
    private var resolved: ResolvedPackageFacts? = null

    fun resolve(): ResolvedPackageFacts? {
        resolved?.let { return it }
        synchronized(this) {
            resolved?.let { return it }
            for (candidate in candidates) {
                val source = try {
                    candidate.create()
                } catch (t: Throwable) {
                    null
                }
                if (source == null) {
                    log("package_provider_attempt=" + candidate.kind.wireCode + " result=unavailable")
                    continue
                }
                val result = ResolvedPackageFacts(candidate.kind, source)
                resolved = result
                log("package_provider=" + candidate.kind.wireCode)
                return result
            }
            log(
                "package_provider_unavailable candidates=" +
                    candidates.joinToString(",") { it.kind.wireCode },
            )
            return null
        }
    }
}

/**
 * 生产 provider 链（每进程只解析一次）。
 *
 * 首层与旧实现完全一致（ActivityThread.systemMain + getSystemContext），因此已经工作的
 * ROM 行为不变；后面的 binder 路径解决本 ROM 上 systemMain 不可用、直接拿 PackageManager
 * 失败的问题。日志走 stderr，由 service.sh 收进 service.log。
 */
object AndroidPackageFactsProvider {

    private val candidates = listOf(
        PackageProviderCandidate(PackageProviderKind.CONTEXT) {
            RootFramework.packageManager()?.let { AndroidPackageIdentitySource(it) }
        },
        PackageProviderCandidate(PackageProviderKind.APP_GLOBALS) {
            IPackageManagerFactsSource.fromAppGlobals()
        },
        PackageProviderCandidate(PackageProviderKind.BINDER) {
            IPackageManagerFactsSource.fromServiceManager()
        },
        PackageProviderCandidate(PackageProviderKind.SHELL) {
            ShellPackageFactsSource()
        },
    )

    /** 生产顺序（测试可读，不触发任何 Android 构造）。 */
    val candidateKinds: List<PackageProviderKind> = candidates.map { it.kind }

    private val chain = PackageProviderChain(
        candidates = candidates,
        log = { System.err.println(it) },
    )

    /** 首次调用解析并缓存；全部失败返回 null（调用方 fail closed 退出）。 */
    fun resolve(): ResolvedPackageFacts? = chain.resolve()
}

/**
 * 通过 IPackageManager binder 读取包事实（隐藏 API 反射）。
 *
 * **设备未验证**：不同 ROM/Android 版本的 IPackageManager 方法签名与隐藏 API 策略
 * 不同；本类只用反射，任何失败都返回 null/空，由链继续退到下一层，绝不伪造事实。
 *
 * - getPackagesForUid(int) → String[]
 * - getPackageInfo(String, long|int flags, int userId) → PackageInfo
 * - PackageInfo.signingInfo.apkContentsSigners（API 28+），退化到 signatures
 * - GET_SIGNING_CERTIFICATES = 0x08000000（与 Context 实现一致的字面值）
 */
internal class IPackageManagerFactsSource private constructor(
    private val packageManager: Any,
) : PackageFactsSource {

    override fun packagesForUid(uid: Int): List<String> {
        if (uid < 0) return emptyList()
        val packages = invoke("getPackagesForUid", arrayOf<Class<*>>(Integer.TYPE), uid) as? Array<*>
            ?: return emptyList()
        return packages.mapNotNull { it as? String }.filter { it.isNotEmpty() }
    }

    override fun signatureSha256(packageName: String): String? =
        packageFacts(packageName).signatureSha256

    override fun versionName(packageName: String): String? =
        packageFacts(packageName).versionName

    override fun uidForPackage(packageName: String): Int? =
        packageFacts(packageName).uid

    override fun dialHandlerPackage(): String? {
        for (intent in dialIntents()) {
            resolveHandler(intent)?.let { return it }
        }
        return null
    }

    /**
     * IPackageManager.resolveIntent 的反射封装（设备未验证）：API 33+ flags 是 long，
     * 旧版本是 int；ResolveInfo.activityInfo.packageName 是唯一可用的处理器事实。
     */
    private fun resolveHandler(intent: Intent): String? {
        val resolved = invoke(
            "resolveIntent",
            arrayOf<Class<*>>(Intent::class.java, String::class.java, java.lang.Long.TYPE, Integer.TYPE),
            intent,
            null,
            MATCH_DEFAULT_ONLY.toLong(),
            0,
        ) ?: invoke(
            "resolveIntent",
            arrayOf<Class<*>>(Intent::class.java, String::class.java, Integer.TYPE, Integer.TYPE),
            intent,
            null,
            MATCH_DEFAULT_ONLY,
            0,
        )
        val activityInfo = resolved?.let { field(it, "activityInfo") } ?: return null
        return field(activityInfo, "packageName") as? String
    }

    override fun packageFacts(packageName: String): PackageFacts {
        val info = packageInfo(packageName) ?: return PackageFacts(packageName)
        return PackageFacts(
            packageName = packageName,
            versionName = field(info, "versionName") as? String,
            signatureSha256 = signatureSha256(info),
            uid = (field(info, "applicationInfo")?.let { field(it, "uid") }) as? Int,
        )
    }

    /** API 33+ 的 flags 是 long，旧版本是 int：两种签名都试，方法不存在就返回 null。 */
    private fun packageInfo(packageName: String): Any? {
        val longFlags = invoke(
            "getPackageInfo",
            arrayOf<Class<*>>(String::class.java, java.lang.Long.TYPE, Integer.TYPE),
            packageName,
            GET_SIGNING_CERTIFICATES.toLong(),
            0,
        )
        if (longFlags != null) return longFlags
        return invoke(
            "getPackageInfo",
            arrayOf<Class<*>>(String::class.java, Integer.TYPE, Integer.TYPE),
            packageName,
            GET_SIGNING_CERTIFICATES,
            0,
        )
    }

    private fun signatureSha256(info: Any): String? {
        val signingInfo = field(info, "signingInfo")
        if (signingInfo != null) {
            val signers = (call(signingInfo, "getApkContentsSigners") as? Array<*>)
                ?: (field(signingInfo, "apkContentsSigners") as? Array<*>)
            return sha256OfFirstSigner(signers)
        }
        // API 28 之前没有 signingInfo：退回已废弃的 signatures 字段。
        return sha256OfFirstSigner(field(info, "signatures") as? Array<*>)
    }

    private fun sha256OfFirstSigner(signers: Array<*>?): String? {
        val first = signers?.firstOrNull() ?: return null
        val bytes = when (first) {
            is ByteArray -> first
            else -> call(first, "toByteArray") as? ByteArray
        } ?: return null
        return PeerVerifier.sha256Hex(bytes)
    }

    private fun invoke(name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? = try {
        packageManager.javaClass.getMethod(name, *parameterTypes).invoke(packageManager, *args)
    } catch (t: Throwable) {
        null
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? = try {
        target.javaClass.getMethod(name, *parameterTypes(args)).invoke(target, *args)
    } catch (t: Throwable) {
        null
    }

    private fun parameterTypes(args: Array<out Any?>): Array<Class<*>> =
        args.map { argument -> (argument?.javaClass ?: Any::class.java) as Class<*> }.toTypedArray()

    private fun field(target: Any, name: String): Any? = try {
        target.javaClass.getField(name).get(target)
    } catch (t: Throwable) {
        null
    }

    companion object {
        /** PackageManager.GET_SIGNING_CERTIFICATES 的字面值，避免 API 级别分支。 */
        private const val GET_SIGNING_CERTIFICATES: Int = 0x08000000

        /** PackageManager.MATCH_DEFAULT_ONLY。 */
        private const val MATCH_DEFAULT_ONLY: Int = 0x00010000

        private fun dialIntents(): List<Intent> {
            val withData = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "", null))
                .addCategory(Intent.CATEGORY_DEFAULT)
            val withoutData = Intent(Intent.ACTION_DIAL)
                .addCategory(Intent.CATEGORY_DEFAULT)
            return listOf(withData, withoutData)
        }

        /** android.app.AppGlobals.getPackageManager()：直接返回 IPackageManager，无需 systemMain。 */
        fun fromAppGlobals(): PackageFactsSource? = try {
            val manager = Class.forName("android.app.AppGlobals")
                .getMethod("getPackageManager")
                .invoke(null)
            manager?.let { IPackageManagerFactsSource(it) }
        } catch (t: Throwable) {
            null
        }

        /** ServiceManager 直取 "package" binder + IPackageManager$Stub.asInterface。 */
        fun fromServiceManager(): PackageFactsSource? = try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "package")
            if (binder == null) {
                null
            } else {
                val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
                val manager = stub
                    .getMethod("asInterface", Class.forName("android.os.IBinder"))
                    .invoke(null, binder)
                manager?.let { IPackageManagerFactsSource(it) }
            }
        } catch (t: Throwable) {
            null
        }
    }
}

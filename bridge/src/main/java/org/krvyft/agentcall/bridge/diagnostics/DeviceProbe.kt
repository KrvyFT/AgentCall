package org.krvyft.agentcall.bridge.diagnostics

import android.os.Build
import java.io.File
import java.security.MessageDigest
import org.krvyft.agentcall.bridge.ipc.PackageIdentitySource
import org.krvyft.agentcall.bridge.ipc.PeerVerifier
import org.krvyft.agentcall.core.model.CapabilityReport
import org.krvyft.agentcall.core.model.CapabilityStatus
import org.krvyft.agentcall.core.model.EnvironmentKey

/**
 * 只读平台事实，便于在没有设备时隔离 Android API 与诊断逻辑。
 *
 * SIM 运营商默认是显式 [UNKNOWN]：平台只有在确实能读到事实时才返回字符串，绝不按
 * “通常值”猜测。B2 允许真机通过 TelephonyManager 提供 SIM 运营商。承载/路由没有任何
 * 观测路径（本接口的默认实现即 UNKNOWN，生产实现也不覆盖），因此始终为 UNKNOWN，
 * 只能在 calibrate 时由 root 操作员背书；CalibrationPolicy 会因此 fail closed。
 */
interface PlatformFacts {
    fun apiLevel(): Int
    fun romFingerprint(): String
    fun kernelVersion(): String
    fun frameworkVersion(): String
    fun audioPolicyDigest(): String

    fun simCarrier(): String = UNKNOWN

    fun transport(): String = UNKNOWN

    fun route(): String = UNKNOWN

    companion object {
        /** 显式未知占位，与 [org.krvyft.agentcall.core.policy.CalibrationPolicy.UNKNOWN_VALUE] 一致。 */
        const val UNKNOWN: String = "UNKNOWN"
    }
}

/**
 * 只读系统属性来源（Task 11H1）。生产实现读取 root app_process 的
 * `android.os.SystemProperties`；读不到/不可用时返回 null，绝不返回猜测值。
 * 测试注入假实现，覆盖 LSPosed/Vector 在目标栈上写下的版本属性。
 */
fun interface SystemPropertySource {
    fun get(name: String): String?

    companion object {
        /** 没有任何属性源（不可用环境/测试）。 */
        val NONE: SystemPropertySource = SystemPropertySource { null }
    }
}

/**
 * 用反射读系统属性。桥接是 root `app_process`，不经应用进程的隐藏 API 策略；
 * 读取失败（类不存在/方法被拒）一律当作“没有这个事实”。
 */
class AndroidSystemProperties : SystemPropertySource {
    override fun get(name: String): String? = try {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val value = runCatching {
            systemProperties.getMethod("get", String::class.java).invoke(null, name)
        }.getOrNull() ?: runCatching {
            systemProperties.getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "")
        }.getOrNull()
        value as? String
    } catch (t: Throwable) {
        null
    }
}

/**
 * [PlatformFacts] 的真实 Android 实现：全部来自系统属性/文件，不伪造能力。
 *
 * 框架版本与 SIM 运营商有多条真实来源（Task 11H1）：bridge 的 `app_process` 不加载
 * Xposed，直接类检查通常只能得到 UNKNOWN；因此先读目标栈上真实存在的系统属性
 * （LSPosed/Vector 守护进程写入）与 manager metadata，再退回直接类检查。SIM 运营商
 * 先读 TelephonyManager，读不到时退回系统属性。所有值都经过
 * [boundedObservedFact] 校验，空格/超长/控制字符/显式 UNKNOWN 一律视为未知。
 */
class AndroidPlatformFacts(
    private val properties: SystemPropertySource = AndroidSystemProperties(),
    /** SIM 运营商首选来源；生产是 TelephonyManager，测试可注入。 */
    private val telephonyCarrier: () -> String? = { systemSimCarrier() },
    /** 直接类检查（XposedBridge）作为框架版本的补充信号；测试可注入。 */
    private val xposedBridgeVersion: () -> String? = { reflectiveXposedBridgeVersion() },
) : PlatformFacts {

    override fun apiLevel(): Int = Build.VERSION.SDK_INT

    override fun romFingerprint(): String = Build.FINGERPRINT

    override fun kernelVersion(): String = System.getProperty("os.version") ?: UNKNOWN

    /**
     * 框架版本（Task 11H1）：目标栈上的真实来源优先——LSPosed/Vector 会通过 root
     * 守护进程写系统属性，部分安装器/manager 只在 `module.prop` 里留下版本；
     * 最后才退回直接类检查（bridge app_process 不加载 Xposed，通常不可用）。
     * 观测到的已知值永远优先于 CLI_ROOT 背书。
     */
    override fun frameworkVersion(): String {
        for (key in FRAMEWORK_VERSION_PROPERTIES) {
            boundedObservedFact(properties.get(key))?.let { return it }
        }
        boundedObservedFact(managerMetadataVersion())?.let { return it }
        return boundedObservedFact(xposedBridgeVersion()) ?: UNKNOWN
    }

    /**
     * SIM 运营商名（B2）：只读 TelephonyManager 的公开运营商名，读不到时退回
     * telephony 栈写入的系统属性（`gsm.sim.operator.alpha`）。不使用网络运营商
     * 冒充 SIM 运营商；全部来源都空时保持 UNKNOWN。
     */
    override fun simCarrier(): String {
        boundedObservedFact(telephonyCarrier())?.let { return it }
        for (key in SIM_CARRIER_PROPERTIES) {
            boundedObservedFact(properties.get(key))?.let { return it }
        }
        return UNKNOWN
    }

    /** manager metadata：`/data/adb/modules/<name>/module.prop` 的 `version=` 行（root 可读）。 */
    private fun managerMetadataVersion(): String? {
        for (path in FRAMEWORK_METADATA_PATHS) {
            val file = File(path)
            if (!file.isFile || file.length() > MAX_METADATA_BYTES) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val version = text.lineSequence()
                .firstOrNull { it.startsWith(METADATA_VERSION_PREFIX) }
                ?.substring(METADATA_VERSION_PREFIX.length)
            boundedObservedFact(version)?.let { return it }
        }
        return null
    }

    override fun audioPolicyDigest(): String {
        for (path in AUDIO_POLICY_PATHS) {
            val file = File(path)
            if (!file.isFile) continue
            val digest = try {
                MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            } catch (t: Throwable) {
                continue
            }
            val builder = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return builder.toString()
        }
        return UNKNOWN
    }

    private companion object {
        const val UNKNOWN = "UNKNOWN"
        const val HEX = "0123456789abcdef"
        const val MAX_METADATA_BYTES = 64L * 1024L
        const val METADATA_VERSION_PREFIX = "version="
        val AUDIO_POLICY_PATHS = listOf(
            "/vendor/etc/audio_policy_configuration.xml",
            "/system/etc/audio_policy_configuration.xml",
        )

        /**
         * LSPosed/Vector/EdXposed 在 root 守护进程里写入的版本属性。顺序固定：
         * 先读当前栈最可能存在的键，再退回旧分支；读到第一个有界有效值即返回。
         */
        val FRAMEWORK_VERSION_PROPERTIES = listOf(
            "ro.lsposed.version",
            "persist.sys.lsposed.version",
            "ro.vector.version",
            "persist.sys.vector.version",
            "ro.edxposed.version",
            "persist.sys.edxposed.version",
        )

        /** manager/安装器只留下 module.prop 时的兜底来源（root 可读的固定路径）。 */
        val FRAMEWORK_METADATA_PATHS = listOf(
            "/data/adb/modules/lsposed/module.prop",
            "/data/adb/modules/zygisk_lsposed/module.prop",
            "/data/adb/modules/vector/module.prop",
        )

        /** telephony 栈写入的 SIM 运营商属性；绝不是网络运营商。 */
        val SIM_CARRIER_PROPERTIES = listOf(
            "gsm.sim.operator.alpha",
            "gsm.sim.operator.default-name",
            "ro.cdma.home.operator.alpha",
        )
    }
}

/** 观测事实的长度上限：足够容纳版本/运营商名，又不会把任意 blob 写进证据。 */
private const val MAX_OBSERVED_FACT_CHARS: Int = 128

/** LSPosed/EdXposed/Vector 的 XposedBridge 类名；测试用替身类名覆盖反射路径。 */
private const val XPOSED_BRIDGE_CLASS: String = "de.robv.android.xposed.XposedBridge"

/**
 * 观测事实的有界校验（Task 11H1）：trim 后必须非空、≤ [MAX_OBSERVED_FACT_CHARS]、
 * 不含控制字符、且不是显式 UNKNOWN。任何不合格的值都返回 null（保持未知），
 * 绝不截断或猜测。
 */
private fun boundedObservedFact(value: String?): String? {
    val trimmed = value?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed == PlatformFacts.UNKNOWN) return null
    if (trimmed.length > MAX_OBSERVED_FACT_CHARS) return null
    if (trimmed.any { it.isISOControl() }) return null
    return trimmed
}

/** 直接类检查（只是一个信号；bridge app_process 通常不加载 Xposed）。 */
internal fun reflectiveXposedBridgeVersion(
    className: String = XPOSED_BRIDGE_CLASS,
): String? = try {
    val xposedBridge = Class.forName(className)
    val method = xposedBridge.getMethod("getXposedVersion")
    method.isAccessible = true
    boundedObservedFact(xposedVersionFact(method.invoke(null)))
} catch (t: Throwable) {
    null
}

/**
 * LSPosed/EdXposed 的 `getXposedVersion()` 返回 int，个别旧分支返回 String；统一成
 * 十进制字符串再交给 [boundedObservedFact] 做有界校验。其他返回类型视为未知。
 */
private fun xposedVersionFact(value: Any?): String? = when (value) {
    is String -> value
    is Number -> value.toLong().toString()
    else -> null
}

/** TelephonyManager 的 SIM 运营商名；系统服务不可达时返回 null（由属性来源兜底）。 */
private fun systemSimCarrier(): String? = try {
    val activityThreadClass = Class.forName("android.app.ActivityThread")
    val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
    val systemContext = activityThreadClass.getMethod("getSystemContext")
        .invoke(activityThread) as android.content.Context
    val manager = systemContext.getSystemService(android.content.Context.TELEPHONY_SERVICE)
        as? android.telephony.TelephonyManager
    manager?.simOperatorName?.takeIf { it.isNotBlank() }
} catch (t: Throwable) {
    null
}

/** 受限音频 API 的存在性探测；方法可见不等于可用，更不等于对端能收到语音。 */
interface AudioApiProbe {
    fun visibleMethods(): Set<String>
}

/** 用反射检查 AudioManager 上的受限方法；不调用、不申请权限、不改配置。 */
class ReflectiveAudioApiProbe : AudioApiProbe {

    override fun visibleMethods(): Set<String> = try {
        val audioManager = Class.forName("android.media.AudioManager")
        val methodNames = audioManager.methods.map { it.name }.toSet()
        HIDDEN_AUDIO_METHODS.filter { it in methodNames }.toSet()
    } catch (t: Throwable) {
        emptySet()
    }

    companion object {
        /** spec §9 首选的三个受限方法；存在性单独报告，隔离/注入均不据此宣称可用。 */
        const val DOWNLINK_METHOD: String = "getCallDownlinkExtractionAudioRecord"
        const val UPLINK_METHOD: String = "getCallUplinkInjectionAudioTrack"
        const val INTERCEPTABLE_METHOD: String = "isPstnCallAudioInterceptable"

        val HIDDEN_AUDIO_METHODS = listOf(DOWNLINK_METHOD, UPLINK_METHOD, INTERCEPTABLE_METHOD)
    }
}

/**
 * 设备能力诊断（spec §9/§12）：只报告真实观测到的 ROM/SDK/内核/Google 电话版本与签名、
 * 受限方法是否存在。
 *
 * 明确边界：
 * - 音频（下行识别、上行注入、麦克风隔离）与权限未经真机测试，状态一律停在
 *   NOT_DETECTED，notes 标记 UNTESTED——本类没有任何路径会写 VERIFIED。
 * - SELinux 可达性、特权终止未在本任务验证，notes 标记 UNVERIFIED。
 * - SIM 运营商由 TelephonyManager/telephony 属性观测；承载/路由没有观测路径，始终写
 *   UNKNOWN，只能由 root 操作员背书，不猜默认值。
 */
class DeviceProbe(
    private val packages: PackageIdentitySource,
    private val platform: PlatformFacts = AndroidPlatformFacts(),
    private val audioApiProbe: AudioApiProbe = ReflectiveAudioApiProbe(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val bridgeVersion: String,
    private val dialerPackage: String = PeerVerifier.DIALER_PACKAGE,
) {

    fun inspect(): CapabilityReport {
        val visibleMethods = audioApiProbe.visibleMethods()
        val dialerVersion = packages.versionName(dialerPackage) ?: UNKNOWN
        val dialerSignature = packages.signatureSha256(dialerPackage) ?: UNKNOWN
        val environment = EnvironmentKey(
            apiLevel = platform.apiLevel(),
            romFingerprint = platform.romFingerprint(),
            kernelVersion = platform.kernelVersion(),
            dialerPackageVersion = dialerVersion,
            frameworkVersion = platform.frameworkVersion(),
            bridgeVersion = bridgeVersion,
            audioPolicyDigest = platform.audioPolicyDigest(),
            // B2：平台能给出事实就上报，给不出就保持显式 UNKNOWN（绝不猜）。
            simCarrier = platform.simCarrier(),
            transport = platform.transport(),
            route = platform.route(),
        )
        val hiddenApi = if (visibleMethods.isEmpty()) {
            "NONE"
        } else {
            visibleMethods.sorted().joinToString("|")
        }
        val notes = "audio:UNTESTED permissions:UNTESTED selinux:UNVERIFIED " +
            "dialerPackage=" + dialerPackage +
            " dialerVersion=" + dialerVersion +
            " dialerSignatureSha256=" + dialerSignature +
            " hiddenApi=" + hiddenApi
        return CapabilityReport(
            environment = environment,
            downlinkExtraction = visibleStatus(visibleMethods, ReflectiveAudioApiProbe.DOWNLINK_METHOD),
            uplinkInjection = visibleStatus(visibleMethods, ReflectiveAudioApiProbe.UPLINK_METHOD),
            // 隔离与特权终止没有真机证据：永远不能报“可用/已验证”。
            microphoneIsolation = CapabilityStatus.NOT_DETECTED,
            privilegedTermination = CapabilityStatus.NOT_DETECTED,
            checkedAtMs = clockMs(),
            notes = notes,
        )
    }

    private fun visibleStatus(visibleMethods: Set<String>, method: String): CapabilityStatus =
        if (method in visibleMethods) CapabilityStatus.INTERFACE_VISIBLE else CapabilityStatus.NOT_DETECTED

    private companion object {
        const val UNKNOWN = "UNKNOWN"
    }
}

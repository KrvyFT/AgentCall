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
 * SIM/承载/路由三项默认是显式 [UNKNOWN]：平台只有在确实能读到事实时才返回字符串，
 * 绝不按“通常值”猜测。B2 允许真机通过 TelephonyManager 提供 SIM 运营商；通话承载与
 * 路由必须来自通话上下文，拿不到就保持 UNKNOWN（CalibrationPolicy 会因此 fail closed）。
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

/** [PlatformFacts] 的真实 Android 实现：全部来自系统属性/文件，不伪造能力。 */
class AndroidPlatformFacts : PlatformFacts {

    override fun apiLevel(): Int = Build.VERSION.SDK_INT

    override fun romFingerprint(): String = Build.FINGERPRINT

    override fun kernelVersion(): String = System.getProperty("os.version") ?: UNKNOWN

    override fun frameworkVersion(): String = try {
        val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
        val method = xposedBridge.getMethod("getXposedVersion")
        method.isAccessible = true
        (method.invoke(null) as? String) ?: UNKNOWN
    } catch (t: Throwable) {
        // 特权进程不加载 Xposed：未安装/不在作用域都必须如实报告未知。
        UNKNOWN
    }

    /**
     * SIM 运营商名（B2）：只读 TelephonyManager 的公开运营商名，读不到返回 UNKNOWN。
     * 不使用网络运营商冒充 SIM 运营商；两者都空时保持 UNKNOWN。
     */
    override fun simCarrier(): String = try {
        val manager = systemTelephonyManager() ?: return UNKNOWN
        val sim = manager.simOperatorName?.trim().orEmpty()
        if (sim.isNotEmpty()) sim else UNKNOWN
    } catch (t: Throwable) {
        UNKNOWN
    }

    private fun systemTelephonyManager(): android.telephony.TelephonyManager? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as android.content.Context
        systemContext.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
    } catch (t: Throwable) {
        null
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
        val AUDIO_POLICY_PATHS = listOf(
            "/vendor/etc/audio_policy_configuration.xml",
            "/system/etc/audio_policy_configuration.xml",
        )
    }
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
 * - SIM/承载/路由需要通话上下文，未取得时写 UNKNOWN，不猜默认值。
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

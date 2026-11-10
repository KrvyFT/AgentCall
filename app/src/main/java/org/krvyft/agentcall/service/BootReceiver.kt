package org.krvyft.agentcall.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 开机/升级后的服务启动裁决（I4）。
 *
 * 只裁决“广播动作 + 本机用户意图”，不触碰 Android 运行时，因此可在 JVM 单测里穷举。
 */
enum class BootStartDecision {
    START,
    /** 用户从未显式启动，或最后一次操作是“停止”：尊重用户意愿，不在开机时拉起服务。 */
    SKIP_DISABLED,
    /** 不是开机/升级广播（本 receiver 理论上不会收到，防御性忽略）。 */
    SKIP_UNSUPPORTED_ACTION,
}

/**
 * 启动策略（纯 Kotlin）。
 *
 * 总开关（桥接 AgentConfig.enabled）不在广播里读取：receiver 运行在受限的短生命周期
 * 里，没有 root 桥接的同步读取通道。这里只恢复“用户上次显式启动过且没有停止”的本机
 * 意图；服务启动后仍要走完整的就绪握手并读取桥接配置，总开关关闭时保持 NOT_READY、
 * 不自动接听。因此“开机启动”不等于“跳过总开关”。
 */
internal object BootStartPolicy {

    const val ACTION_BOOT_COMPLETED: String = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED: String = "android.intent.action.MY_PACKAGE_REPLACED"

    fun decide(action: String?, autoStartEnabled: Boolean): BootStartDecision = when {
        action != ACTION_BOOT_COMPLETED && action != ACTION_MY_PACKAGE_REPLACED ->
            BootStartDecision.SKIP_UNSUPPORTED_ACTION
        !autoStartEnabled -> BootStartDecision.SKIP_DISABLED
        else -> BootStartDecision.START
    }
}

/**
 * 本机“自动启动”偏好：记录用户是否显式启动过服务并尚未停止。
 *
 * 它只是用户意图，不是就绪/总开关；桥接配置仍是自动接听的唯一权威。只保存一个布尔值，
 * 不含号码、密钥或会话数据。备份规则已排除整个 SharedPreferences 文件。
 */
object AutoStartPreference {

    const val PREFS_NAME: String = "agentcall.autostart"
    private const val KEY_ENABLED: String = "enabled"

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}

/**
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED 接收器（I4）。
 *
 * 为什么需要它：manifest 里的服务 exported=false，只有用户点“启动”、通知或显式
 * startService 才能拉起。重启后没有任何组件会启动它，轮询器/就绪握手/自动接听全部
 * 缺席，spec §12 的锁屏/重启握手不可达。这里按用户本机的自动启动偏好恢复服务；服务
 * 仍保持非导出，仍从零做就绪握手。
 *
 * Android 12+ 的前台服务后台启动限制对 BOOT_COMPLETED/MY_PACKAGE_REPLACED 有豁免；
 * Android 15+ 对开机启动的限制只覆盖 dataSync/camera/mediaPlayback/phoneCall/
 * mediaProjection/microphone 类型，本服务声明的 specialUse 不在其中。即便如此仍捕获
 * ForegroundServiceStartNotAllowedException：系统策略/OEM 更严格时只记录机器码，绝不
 * 崩溃或降级成无类型常驻服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val enabled = AutoStartPreference.isEnabled(context)
        when (BootStartPolicy.decide(intent?.action, enabled)) {
            BootStartDecision.START -> startService(context)
            BootStartDecision.SKIP_DISABLED -> Log.i(TAG, "boot_start_skipped_disabled")
            BootStartDecision.SKIP_UNSUPPORTED_ACTION -> Log.i(TAG, "boot_start_skipped_action")
        }
    }

    private fun startService(context: Context) {
        try {
            ContextCompat.startForegroundService(context, AgentCallService.startIntent(context))
            Log.i(TAG, "boot_start_requested")
        } catch (notAllowed: ForegroundServiceStartNotAllowedException) {
            // 系统/OEM 拒绝后台 FGS 启动：服务保持未运行，下次用户手动启动仍可用。
            Log.w(TAG, "boot_start_not_allowed")
        } catch (failure: Exception) {
            Log.w(TAG, "boot_start_failed " + failure.javaClass.simpleName)
        }
    }

    private companion object {
        const val TAG = "AgentCallBoot"
    }
}

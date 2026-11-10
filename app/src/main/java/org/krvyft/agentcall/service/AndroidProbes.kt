package org.krvyft.agentcall.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.krvyft.agentcall.core.policy.CalibrationPolicy

/**
 * HOOK 存在性探针：读桥接 `status.hookConnected`。
 *
 * 桥接把 `status` 只开放给 CLI_ROOT，APP 调用得到 `forbidden`；此时返回
 * [HookPresence.UNKNOWN]（就绪判定会拒绝自动接听），绝不把“看不到”当成“在线”。
 */
class BridgeHookPresenceProbe(
    private val diagnostics: BridgeDiagnostics,
) : HookPresenceProbe {

    override suspend fun presence(): HookPresence = when (val result = diagnostics.status()) {
        is BridgeStatusResult.Available -> result.status.hookConnected
        is BridgeStatusResult.Unavailable -> HookPresence.UNKNOWN
    }
}

/**
 * Google 电话默认角色探针：读桥接 `status.dialerDefaultRole`（Task 7B2 FIX ROUND 1，spec §8）。
 *
 * status 不可用/字段缺失时返回 null——就绪判定把 null 当作“不是默认拨号器”，保留原响铃。
 */
class BridgeDialerRoleProbe(
    private val diagnostics: BridgeDiagnostics,
) : DialerDefaultRoleProbe {

    override suspend fun isDefault(): Boolean? = when (val result = diagnostics.status()) {
        is BridgeStatusResult.Available -> result.status.dialerDefaultRole
        is BridgeStatusResult.Unavailable -> null
    }
}

/**
 * 校准探针：桥接 diagnose 的 EnvironmentKey + 本机人工测试记录 + core 的 [CalibrationPolicy]。
 *
 * 环境不可用（APP 无权调用 diagnose 或负载不完整）时直接 false：没有完整环境绑定的证据
 * 不能解锁自动接听（spec §9）。
 */
class BridgeDiagnoseCalibrationProbe(
    private val diagnostics: BridgeDiagnostics,
    private val evidence: CalibrationEvidenceStore,
) : CalibrationProbe {

    override suspend fun canAutoAnswer(): Boolean {
        val environment = when (val result = diagnostics.environment()) {
            is EnvironmentResult.Available -> result.environment
            is EnvironmentResult.Unavailable -> return false
        }
        val evidence = evidence.evidenceFor(environment) ?: return false
        return CalibrationPolicy.canAutoAnswer(environment, evidence)
    }
}

/**
 * 网络可达性：必须同时具备 INTERNET 与 VALIDATED 能力。
 *
 * 只检查“已连接”会把没有默认路由的 Wi-Fi（强制门户/受限网络）当成可用，导致接听后
 * 远程 API 必然失败；VALIDATED 是系统实际验证过互联网可达的保守信号。
 */
class AndroidNetworkProbe(context: Context) : NetworkProbe {

    private val appContext: Context = context.applicationContext

    override fun isOnline(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

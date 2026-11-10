package org.krvyft.agentcall.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络可达性：必须同时具备 INTERNET 与 VALIDATED 能力。
 *
 * 只检查“已连接”会把没有默认路由的 Wi-Fi（强制门户/受限网络）当成可用，导致接听后
 * 远程 API 必然失败；VALIDATED 是系统实际验证过互联网可达的保守信号。
 *
 * 说明（M7）：status 派生探针统一由 [BridgeLiveFactsProbe] 实现，校准由
 * [BridgeCalibrationProbe] 实现；旧的三份单事实探针（BridgeHookPresenceProbe /
 * BridgeDialerRoleProbe / BridgeDiagnoseCalibrationProbe）没有生产调用方，已删除，
 * 避免留下“看起来在用”的死代码。
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

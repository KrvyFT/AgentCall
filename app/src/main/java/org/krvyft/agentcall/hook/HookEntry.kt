package org.krvyft.agentcall.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 传统 Xposed 入口（API 82）。
 *
 * 框架由用户安装的 LSPosed/Vector 在目标进程内按 assets/xposed_init 的类名反射实例化，
 * 因此本类必须 public、可无参构造，且只接受 com.google.android.dialer 进程。
 * 不注入 system_server，不触碰其他包；Xposed API 仅 compileOnly，绝不随 APK 打包。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 包名不匹配立即返回：system_server 及其他进程一律不加载。
        if (lpparam.packageName != TARGET_PACKAGE) return

        val inCallService = try {
            Class.forName(IN_CALL_SERVICE_CLASS, false, lpparam.classLoader)
        } catch (error: Throwable) {
            XposedBridge.log("AgentCall: cannot resolve $IN_CALL_SERVICE_CLASS in $TARGET_PACKAGE: $error")
            return
        }
        installHooks(inCallService)
    }

    /**
     * TODO(PART B): 用 ServiceDiscovery.resolve 在本进程 InCallService 实现及其覆写回调上安装
     * 发现/注册接线，并接入 CallRegistry.observe；当前只记录入口已就绪。
     */
    private fun installHooks(inCallService: Class<*>) {
        XposedBridge.log("AgentCall: hook entry loaded in $TARGET_PACKAGE (${inCallService.name}); discovery/registry wiring pending PART B")
    }

    private companion object {
        const val TARGET_PACKAGE = "com.google.android.dialer"
        const val IN_CALL_SERVICE_CLASS = "android.telecom.InCallService"
    }
}

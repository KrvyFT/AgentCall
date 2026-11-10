package org.krvyft.agentcall.hook

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.telecom.InCallService
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 动态发现 Google 电话真实使用的 InCallService 子类及其**实际覆写**的回调（spec §6.1）。
 *
 * 为什么不能只 Hook 基类：子类完全可以覆写 `onCallAdded` 且不调用 `super`，
 * 此时基类方法不会执行，只 Hook 基类等于永远看不到来电。因此对每个已声明的
 * InCallService 组件，沿类继承链向上找到每个回调的“最派生实现”，只 Hook 该实现。
 *
 * 回退与去重规则：
 * - **每个组件独立回退**：某个组件没有覆写某个回调时，该组件回退到 [InCallService] 的
 *   基类实现；不能因为别的组件覆写过就跳过它，否则继承基类回调的组件永远不会被观察。
 * - 同一个 [Method]（声明类 + 名称 + 参数）只返回一次，父子类链共享实现时不会重复 Hook。
 * - 重复注册同一组件、非本包组件、非 InCallService 子类都按集合语义去重/忽略。
 *
 * 回调集合除 onCallAdded/onCallRemoved 外还包含公开的音频路由回调
 * （onCallAudioStateChanged / onCallEndpointChanged），用于在本进程内实现保守交还。
 *
 * 本类只负责“选中方法”，不安装 Hook；安装与异常隔离见 [HookSafety] 和 HookEntry。
 */
object ServiceDiscovery {

    /** 唯一允许 Hook 的包名。 */
    const val DIALER_PACKAGE: String = "com.google.android.dialer"

    private const val IN_CALL_SERVICE_CLASS = "android.telecom.InCallService"

    /**
     * 需要观察的 InCallService 回调（名称 + 唯一参数类型）。
     *
     * `onCallAdded`/`onCallRemoved` 用于发现/移除会话；`onCallAudioStateChanged`（API 23+）
     * 与 `onCallEndpointChanged`（API 34+）是**公开 SDK** 的音频路由回调，用在本进程内
     * 实现“系统路由变更来源不明时保守交还”，不依赖桥接进程。
     */
    private val CALLBACKS = listOf(
        CallbackSpec("onCallAdded", "android.telecom.Call"),
        CallbackSpec("onCallRemoved", "android.telecom.Call"),
        CallbackSpec("onCallAudioStateChanged", "android.telecom.CallAudioState"),
        CallbackSpec("onCallEndpointChanged", "android.telecom.CallEndpoint"),
    )

    /**
     * 解析要 Hook 的方法集合。
     *
     * @param loader 目标进程的 ClassLoader（Google 电话进程）
     * @param services PackageManager 查到的候选组件；只使用其包名限定的 [ServiceInfo.name]
     * @param targetPackage 只解析该包名声明的组件；默认 Google 电话
     * @return 去重后的“最派生覆写方法/基类回退方法”集合；无法解析或没有候选时为空白集合
     */
    fun resolve(
        loader: ClassLoader,
        services: List<ServiceInfo>,
        targetPackage: String = DIALER_PACKAGE,
    ): Set<Method> = resolveCandidates(
        loader = loader,
        candidates = services.map { ServiceCandidate(name = it.name, packageName = it.packageName) },
        targetPackage = targetPackage,
    )

    /**
     * [resolve] 的纯逻辑入口（不构造 [ServiceInfo]，JVM 可测）。
     *
     * 每个组件独立解析：某个组件没有覆写某个回调时，**该组件**回退到基类实现，
     * 而不是因为别的组件覆写过就跳过它。同一个 [Method] 由集合去重，因此父子类
     * 共享实现、重复注册同一组件、多个组件继承基类都不会重复 Hook。
     */
    internal fun resolveCandidates(
        loader: ClassLoader,
        candidates: List<ServiceCandidate>,
        targetPackage: String = DIALER_PACKAGE,
    ): Set<Method> {
        val base = loadClass(loader, IN_CALL_SERVICE_CLASS) ?: return emptySet()
        val serviceClasses = LinkedHashSet<Class<*>>()
        for (candidate in candidates) {
            // 包名必须匹配：只 Hook 本包声明的组件，不凭消息/名字信任其它包。
            if (candidate.packageName != targetPackage) continue
            val name = candidate.name ?: continue
            val type = loadClass(loader, name) ?: continue
            if (type != base && base.isAssignableFrom(type)) {
                serviceClasses += type
            }
        }
        if (serviceClasses.isEmpty()) return emptySet()

        val resolved = LinkedHashSet<Method>()
        for (spec in CALLBACKS) {
            val parameterClass = loadClass(loader, spec.parameterType) ?: continue
            val baseMethod = base.declaredMethods.firstOrNull { matches(it, spec.name, parameterClass) }
            for (serviceClass in serviceClasses) {
                val method = effectiveOverride(serviceClass, spec.name, base, parameterClass) ?: baseMethod
                if (method != null) resolved += method
            }
        }
        return resolved
    }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        try {
            Class.forName(name, false, loader)
        } catch (missing: Throwable) {
            null
        }

    private fun effectiveOverride(
        serviceClass: Class<*>,
        callback: String,
        base: Class<*>,
        parameterClass: Class<*>,
    ): Method? {
        var current: Class<*>? = serviceClass
        while (current != null && current != base && base.isAssignableFrom(current)) {
            current.declaredMethods.firstOrNull { matches(it, callback, parameterClass) }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun matches(method: Method, callback: String, parameterClass: Class<*>): Boolean =
        method.name == callback &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == parameterClass
}

/** 一个候选 InCallService 组件的最小描述（[ServiceInfo] 的纯 Kotlin 投影）。 */
internal data class ServiceCandidate(val name: String?, val packageName: String?)

/** 回调名称与参数类型，避免按名字猜测参数。 */
internal data class CallbackSpec(val name: String, val parameterType: String)

/**
 * Hook 回调的异常隔离（spec §6.1“保留原方法执行”）。
 *
 * Xposed 本身的 before-hook 抛异常会记录后继续执行原方法；这里再显式收口，
 * 使“我们的回调抛出任何 Throwable 都不吞掉 Google 电话原行为”成为可测试的契约，
 * 而不依赖框架版本的具体行为。日志调用本身也不允许把异常带出去。
 */
object HookSafety {

    /**
     * 执行一段 Hook 逻辑，捕获所有 Throwable 并记录。
     *
     * @return true 表示 Hook 逻辑正常完成；false 表示已捕获异常（原方法仍必须执行）
     */
    fun guard(label: String, logger: (String) -> Unit = {}, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (failure: Throwable) {
            runCatching {
                logger(
                    "AgentCall: hook callback '" + label + "' failed: " +
                        failure.javaClass.name + ": " + (failure.message ?: "no message"),
                )
            }
            false
        }
}

/**
 * 只在 Google 电话进程内使用的 PackageManager 适配器。
 *
 * 传统 Xposed 的 handleLoadPackage 可能早于 Application 创建，因此按顺序尝试：
 * 1. Xposed 提供的 `android.app.AndroidAppHelper.currentApplication()`（反射，避免 compileOnly 硬绑定）；
 * 2. `ActivityThread.currentActivityThread().getSystemContext()`（系统 Context 通常更早可用）。
 *
 * 任一成功即可查询本包声明的 InCallService；全部失败返回 null，由 HookEntry 等待
 * `Instrumentation.callApplicationOnCreate` 后再安装，绝不猜测类名。
 */
internal object AndroidServiceCatalog {

    fun packageManager(): PackageManager? =
        packageManagerFromApplication() ?: packageManagerFromSystemContext()

    fun inCallServices(packageManager: PackageManager, packageName: String): List<ServiceInfo> {
        val intent = Intent(InCallService.SERVICE_INTERFACE)
        val resolved = try {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } catch (failure: Throwable) {
            return emptyList()
        }
        return resolved.mapNotNull { it.serviceInfo }.filter { it.packageName == packageName }
    }

    private fun packageManagerFromApplication(): PackageManager? = try {
        val helper = Class.forName("android.app.AndroidAppHelper")
        val application = helper.getMethod("currentApplication").invoke(null) as? Context
        application?.packageManager
    } catch (missing: Throwable) {
        null
    }

    private fun packageManagerFromSystemContext(): PackageManager? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as? Context
        systemContext?.packageManager
    } catch (missing: Throwable) {
        null
    }
}
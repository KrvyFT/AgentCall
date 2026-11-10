package org.krvyft.agentcall.hook

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed before-hook 的适配层：把 `MethodHookParam` 的三要素解包后交给 [onCallback]，
 * 并把任何 Throwable 收口在 [HookSafety.guard] 内。
 *
 * 本类**不引用任何 Xposed 类型**，因此“回调抛异常既不传播、也不阻止原方法执行”
 * 的契约可以在 JVM 里验证；HookEntry 只保留最小的 Xposed 胶水代码。
 *
 * @param onCallback 真正的回调处理（只做内存状态更新/入队，绝不阻塞 Telecom 线程）
 * @param logger 异常日志出口；日志自身失败也不能冒泡
 */
internal class HookCallbackAdapter(
    private val onCallback: (methodName: String, thisObject: Any, args: Array<Any?>?) -> Unit,
    private val logger: (String) -> Unit = {},
) {

    /**
     * @return true 表示我们的逻辑正常完成；false 表示异常已被隔离（原方法仍必须执行）
     */
    fun before(methodName: String, thisObject: Any?, args: Array<Any?>?): Boolean =
        HookSafety.guard("InCallService." + methodName, logger) {
            val target = thisObject ?: return@guard
            onCallback(methodName, target, args)
        }
}

/**
 * 进程级安装闸门。
 *
 * 传统 Xposed 可能对同一进程多次调用 `handleLoadPackage`（多个组件、框架重入、
 * 作用域刷新），而每次调用都可能实例化一个新的入口对象。闸门必须是**进程级静态**状态，
 * 不能是入口的实例字段，否则会重复安装 Hook / 重复创建传输线程。
 */
internal object HookInstallGuard {

    private val claimed = AtomicBoolean(false)

    /** @return true 表示本次调用赢得了安装权；之后的所有调用都返回 false */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** 仅测试用：重置进程级闸门，避免测试之间相互影响。 */
    internal fun resetForTest() {
        claimed.set(false)
    }
}

package org.krvyft.agentcall.hook

import android.app.Application
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.SessionId

/**
 * 传统 Xposed 入口（API 82）。
 *
 * 框架由用户安装的 LSPosed/Vector 在目标进程内按 assets/xposed_init 的类名反射实例化，
 * 因此本类必须 public、可无参构造，且只接受 com.google.android.dialer 进程。
 * 不注入 system_server，不触碰其他包；Xposed API 仅 compileOnly，绝不随 APK 打包。
 *
 * 接线：
 * 1. 通过 PackageManager 找到本进程声明的 InCallService 组件，[ServiceDiscovery] 解析出
 *    真实覆写的 `onCallAdded`/`onCallRemoved` 与公开的音频路由回调（不依赖基类空实现）；
 * 2. 每个回调的 before-hook 经 [HookCallbackAdapter] 只做入队/内存状态更新，包在
 *    [HookSafety] 里，抛异常也不影响 Google 电话原方法；
 * 3. 每个新 Call 建一个 [AndroidCallHandle]，交给 [CallRegistry] 分配随机会话身份；
 * 4. [HookTransport] 以 HOOK 角色转发快照（含动作来源/交还原因）、心跳与命令结果，
 *    只接受受限通话命令。
 *
 * 若 handleLoadPackage 时还拿不到 PackageManager（常见于 Application 创建之前），
 * 则挂 `Instrumentation.callApplicationOnCreate` 等待 Context 就绪后再安装；
 * 不猜测 Google 电话的私有类名。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 包名不匹配立即返回：system_server 及其他进程一律不加载。
        if (lpparam.packageName != TARGET_PACKAGE) return
        // 进程级闸门：同一进程重复 handleLoadPackage 不得重复安装 Hook/传输线程。
        if (!HookInstallGuard.claim()) return
        var runtime: HookRuntime? = null
        try {
            val installed = HookRuntime(lpparam.classLoader)
            runtime = installed
            // 只有立即安装或延迟安装至少挂上一个 Hook 才保持闸门占用；零成功（包括
            // hookMethod 全部失败）会释放闸门，允许下一次入口重试（M6、Task 11G 3）。
            if (!holdGuardAfterInstallAttempt(installed::installFromPackageManager, installed::deferUntilApplicationCreated)) {
                installed.stop()
            }
        } catch (failure: Throwable) {
            // 安装异常不是“已安装”：允许下一次入口重试（M6）。
            HookInstallGuard.release()
            runtime?.stop()
            logToFramework("hook entry failed: " + failure.javaClass.name + ": " + (failure.message ?: "no message"))
        }
    }
}

/**
 * 单个 Hook 方法的安装记账（Task 11G 3）。
 *
 * 与 Xposed 解耦（hook 动作由 lambda 注入），因此“零成功必须释放闸门、失败方法必须
 * 可重试”的契约可在 JVM 里验证。只有 [hook] 真正返回后才把方法记为已安装。
 */
internal class HookMethodInstaller(
    private val hook: (Method) -> Unit,
    private val logger: (String) -> Unit = {},
) {

    private val installedMethods: MutableSet<Method> = HashSet()

    /** 已确认安装成功的 hook 数量。 */
    fun installedCount(): Int = synchronized(installedMethods) { installedMethods.size }

    /**
     * 尝试安装 [methods] 中尚未确认成功的方法。
     *
     * @return true 当且仅当调用后至少有一个方法处于已安装状态（含之前成功的）。
     *   零成功时调用方必须释放闸门，否则本进程再也不能重试。
     */
    fun install(methods: Collection<Method>): Boolean {
        val fresh = synchronized(installedMethods) { methods.filter { it !in installedMethods } }
        var installedNow = 0
        for (method in fresh) {
            try {
                hook(method)
                // 只有 hookMethod 真正返回后才记账：失败的方法留在候选集里，下一次可重试。
                synchronized(installedMethods) { installedMethods.add(method) }
                installedNow += 1
            } catch (failure: Throwable) {
                logger(
                    "cannot hook " + method.declaringClass.name + "#" + method.name +
                        ": " + failure.javaClass.name,
                )
            }
        }
        val installedAny = synchronized(installedMethods) { installedMethods.isNotEmpty() }
        logger(
            "installed " + installedNow + " of " + fresh.size + " hook(s): " +
                fresh.joinToString(",") { it.declaringClass.simpleName + "#" + it.name },
        )
        return installedAny
    }
}

/**
 * 入口级安装尝试（Task 11G 3 / M6）：立即安装失败时回退到“Application 创建后再安装”。
 *
 * @return true 表示本次入口至少安装了一个 hook（即时或延迟路径），闸门应保持占用；
 *   false 表示零 hook，已释放闸门，下一次 handleLoadPackage 可以重试。
 */
internal fun holdGuardAfterInstallAttempt(
    installNow: () -> Boolean,
    defer: () -> Boolean,
): Boolean {
    val installed = installNow() || defer()
    if (!installed) HookInstallGuard.release()
    return installed
}

/** 单个 Google 电话进程的 Hook 运行期装配；持有注册表、传输与 Call→门面映射。 */
private class HookRuntime(private val loader: ClassLoader) {

    private val handles = Collections.synchronizedMap(IdentityHashMap<Call, AndroidCallHandle>())

    /** 方法级安装记账：零成功时调用方必须释放闸门（Task 11G 3）。 */
    private val installer = HookMethodInstaller(
        hook = { method -> XposedBridge.hookMethod(method, hookCallbackFor(method.name)) },
        logger = ::log,
    )

    private val adapter = HookCallbackAdapter(onCallback = ::onServiceCallback, logger = ::log)

    private val registry: CallRegistry = CallRegistry(
        listener = object : RegistryListener {
            override fun onSnapshot(snapshot: CallSnapshot, source: ActionSource) {
                // 快照附带动作来源，控制器才能区分模块动作与人工/系统动作。
                transport?.enqueueSnapshot(snapshot, source)
            }

            override fun onSessionRemoved(session: SessionId, handle: CallHandle) {
                forgetHandle(handle)
                log("session removed callId=" + session.callId)
            }

            override fun onHandedBack(session: SessionId, source: ActionSource, reason: String) {
                // 交还人工必须让控制器看见，不能只写日志：随下一次快照带上机器可读原因。
                val snapshot = registry.snapshot(session)
                if (snapshot != null) {
                    transport?.enqueueSnapshot(snapshot, source, reason)
                }
                log("handed back callId=" + session.callId + " source=" + source.name + " reason=" + reason)
            }
        },
        sink = CommandSink { attempt -> onCommandAttempt(attempt) },
    )

    private var transport: HookTransport? = null

    /** InCallService 最近一次公开音频状态/端点的指纹；作为新门面的初始基线。 */
    @Volatile
    private var serviceRouteFingerprint: String? = null

    init {
        transport = HookTransport(
            processEpoch = registry.processEpoch,
            liveSessions = { registry.liveSessions() },
            onCommand = { session, command -> dispatchCommand(session, command) },
            // 传输诊断走现有框架日志（XposedBridge -> logcat），设备上才能看到
            // hook_transport connect_failed/server_untrusted/protocol_violation。
            logger = ::log,
        )
    }

    /**
     * 立即安装路径：系统 Context 可用时查询本包声明的 InCallService 并安装 Hook。
     * 返回 false 表示暂时无法发现（Context 未就绪或没有组件），由调用方延迟安装。
     */
    fun installFromPackageManager(): Boolean {
        val packageManager = AndroidServiceCatalog.packageManager()
        if (packageManager == null) {
            log("PackageManager unavailable; deferring discovery")
            return false
        }
        val services = AndroidServiceCatalog.inCallServices(packageManager, TARGET_PACKAGE)
        if (services.isEmpty()) {
            log("no InCallService component declared in " + TARGET_PACKAGE)
            return false
        }
        val methods = ServiceDiscovery.resolve(loader, services, TARGET_PACKAGE)
        if (methods.isEmpty()) {
            log("InCallService callbacks could not be resolved")
            return false
        }
        // 返回“是否至少有一个 hook 成功”：零成功时入口会回退到延迟安装，最终仍零成功
        // 则释放闸门，而不是假装已安装（Task 11G 3）。
        return install(methods)
    }

    /**
     * Context 尚未就绪时的唯一延迟路径：`Instrumentation.callApplicationOnCreate` 之后
     * 进程一定已有 Application，可安全查询 PackageManager。只安装一次（集合去重）。
     */
    fun deferUntilApplicationCreated(): Boolean {
        try {
            val instrumentation = Class.forName("android.app.Instrumentation", false, loader)
            val method = instrumentation.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookSafety.guard("install-after-application", ::log) {
                            val application = param.args?.firstOrNull() as? Application ?: return@guard
                            val services = AndroidServiceCatalog.inCallServices(application.packageManager, TARGET_PACKAGE)
                            val methods = ServiceDiscovery.resolve(loader, services, TARGET_PACKAGE)
                            if (methods.isEmpty()) {
                                log("no InCallService callbacks discovered after Application creation")
                                // 延迟安装最终失败：释放闸门，允许后续入口再试一次（M6）。
                                HookInstallGuard.release()
                                stop()
                            } else if (!install(methods)) {
                                // 发现到了方法但一个都没挂上：同样是零 hook，必须释放闸门
                                // 并停掉空转的传输线程（Task 11G 3）。
                                log("no InCallService hook could be installed after Application creation")
                                HookInstallGuard.release()
                                stop()
                            }
                        }
                    }
                },
            )
            log("deferred discovery until Application creation")
            return true
        } catch (failure: Throwable) {
            log("cannot defer discovery: " + failure.javaClass.name + ": " + (failure.message ?: "no message"))
            return false
        }
    }

    // ------------------------------------------------------------------ 安装

    /** @return true 当且仅当当前至少有一个 hook 已成功安装。 */
    private fun install(methods: Set<Method>): Boolean {
        // 先起传输线程再安装 Hook：避免第一个 onCallAdded 的快照落在“线程尚未启动”的窗口里。
        transport?.start()
        return installer.install(methods)
    }

    /** 零 hook 的失败路径：停掉空转的传输线程，避免重试时叠加第二条（Task 11G 3）。 */
    fun stop() {
        transport?.stop()
    }

    private fun hookCallbackFor(methodName: String): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // 只读取参数并入队/更新内存；异常被适配器隔离，原方法继续执行。
            adapter.before(methodName, param.thisObject, param.args)
        }
    }

    // ------------------------------------------------------------------ 回调

    private fun onServiceCallback(methodName: String, thisObject: Any, args: Array<Any?>?) {
        val service = thisObject as? InCallService ?: return
        when (methodName) {
            "onCallAdded" -> (args?.firstOrNull() as? Call)?.let { onCallAdded(service, it) }
            "onCallRemoved" -> (args?.firstOrNull() as? Call)?.let { onCallRemoved(it) }
            // 公开 SDK 的音频路由回调：在本进程内上报，不依赖桥接进程（Task 5）。
            "onCallAudioStateChanged" -> {
                val fingerprint = RouteFingerprint.ofAudioState(args?.firstOrNull() as? CallAudioState)
                if (fingerprint != null) serviceRouteFingerprint = fingerprint
                notifyRouteChanged(methodName, fingerprint)
            }
            "onCallEndpointChanged" -> {
                val fingerprint = RouteFingerprint.ofEndpoint(args?.firstOrNull() as? CallEndpoint)
                if (fingerprint != null) serviceRouteFingerprint = fingerprint
                notifyRouteChanged(methodName, fingerprint)
            }
        }
    }

    private fun onCallAdded(service: InCallService, call: Call) {
        val handle = synchronized(handles) {
            handles[call] ?: AndroidCallHandle(
                call = call,
                processEpoch = registry.processEpoch,
                calls = { service.calls },
                fallbackRouteFingerprint = {
                    serviceRouteFingerprint
                        ?: runCatching { RouteFingerprint.ofAudioState(service.callAudioState) }.getOrNull()
                        ?: runCatching { RouteFingerprint.ofEndpoint(service.currentCallEndpoint) }.getOrNull()
                },
                logger = ::log,
            ).also { handles[call] = it }
        }
        val session = registry.observe(handle)
        val snapshot = registry.snapshot(session)
        log("observed callId=" + session.callId + " state=" + snapshot?.state + " direction=" + snapshot?.direction)
    }

    private fun onCallRemoved(call: Call) {
        val handle = synchronized(handles) { handles.remove(call) } ?: return
        val session = registry.sessionOf(handle)
        if (registry.remove(handle)) {
            log("call removed callId=" + (session?.callId ?: "?"))
        }
    }

    /**
     * InCallService 的音频路由回调是**服务级**的（参数里没有 Call），无法直接归属到某一路
     * 通话。
     *
     * I2(a)：只有恰好一路存活门面时才把它转给该门面；多路（会议/第二路响铃）时无法判定
     * 受影响的是哪一路，忽略本次服务级事件而不是广播给所有门面（广播会把无关的 AI 通话
     * 误交还）。单路时注册表还会按指纹过滤重复上报。
     */
    private fun notifyRouteChanged(methodName: String, fingerprint: String?) {
        val current = synchronized(handles) { handles.values.toList() }
        if (current.size > 1) {
            log("route event ignored: " + current.size + " live calls, affected call unknown")
            return
        }
        val handle = current.singleOrNull() ?: return
        HookSafety.guard("route-change", ::log) {
            handle.notifyRouteChanged(methodName, fingerprint)
        }
    }

    private fun forgetHandle(handle: CallHandle) {
        synchronized(handles) {
            val iterator = handles.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.identity == handle.identity) iterator.remove()
            }
        }
    }

    private fun dispatchCommand(session: SessionId, command: CallCommand) {
        val result = registry.command(session, command)
        if (result !is CommandResult.Accepted) {
            log("command " + command + " rejected for callId=" + session.callId + ": " + result)
        }
    }

    /**
     * 命令尝试的注入 sink：转发给特权容器（`call.command.result`），让 Task 7 的
     * CallControl.send 拿到真实 CommandResult。这里只做非阻塞入队，不发起 IPC。
     */
    private fun onCommandAttempt(attempt: CommandAttempt) {
        transport?.enqueueCommandResult(attempt)
        if (attempt.result !is CommandResult.Accepted) {
            log(
                "command " + attempt.command + " not accepted for callId=" + attempt.session.callId +
                    " source=" + attempt.source.name + " result=" + attempt.result,
            )
        }
    }

    private fun log(message: String) {
        logToFramework(message)
    }
}

private const val TARGET_PACKAGE = ServiceDiscovery.DIALER_PACKAGE

private fun logToFramework(message: String) {
    try {
        XposedBridge.log("AgentCall: " + message)
    } catch (ignored: Throwable) {
        // 日志不可用时也不能影响 Hook 路径。
    }
}

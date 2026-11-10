package org.krvyft.agentcall.hook

import android.app.Application
import android.telecom.Call
import android.telecom.InCallService
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.SessionId

/**
 * 传统 Xposed 入口（API 82）。
 *
 * 框架由用户安装的 LSPosed/Vector 在目标进程内按 assets/xposed_init 的类名反射实例化，
 * 因此本类必须 public、可无参构造，且只接受 com.google.android.dialer 进程。
 * 不注入 system_server，不触碰其他包；Xposed API 仅 compileOnly，绝不随 APK 打包。
 *
 * PART B 接线：
 * 1. 通过 PackageManager 找到本进程声明的 InCallService 组件，[ServiceDiscovery] 解析出
 *    真实覆写的 `onCallAdded`/`onCallRemoved`（不依赖基类空实现）；
 * 2. 每个回调的 before-hook 只做入队/内存状态更新，包在 [HookSafety] 里，抛异常也
 *    不影响 Google 电话原方法；
 * 3. 每个新 Call 建一个 [AndroidCallHandle]，交给 [CallRegistry] 分配随机会话身份；
 * 4. [HookTransport] 以 HOOK 角色转发快照/心跳，只接受受限通话命令。
 *
 * 若 handleLoadPackage 时还拿不到 PackageManager（常见于 Application 创建之前），
 * 则挂 `Instrumentation.callApplicationOnCreate` 等待 Context 就绪后再安装；
 * 不猜测 Google 电话的私有类名。
 */
class HookEntry : IXposedHookLoadPackage {

    private val initialized = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 包名不匹配立即返回：system_server 及其他进程一律不加载。
        if (lpparam.packageName != TARGET_PACKAGE) return
        if (!initialized.compareAndSet(false, true)) return
        try {
            val installed = HookRuntime(lpparam.classLoader)
            if (!installed.installFromPackageManager()) {
                installed.deferUntilApplicationCreated()
            }
        } catch (failure: Throwable) {
            logToFramework("hook entry failed: " + failure.javaClass.name + ": " + (failure.message ?: "no message"))
        }
    }
}

/** 单个 Google 电话进程的 Hook 运行期装配；持有注册表、传输与 Call→门面映射。 */
private class HookRuntime(private val loader: ClassLoader) {

    private val handles = Collections.synchronizedMap(IdentityHashMap<Call, AndroidCallHandle>())
    private val installedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())

    private val registry = CallRegistry(
        listener = object : RegistryListener {
            override fun onSnapshot(snapshot: org.krvyft.agentcall.core.model.CallSnapshot, source: ActionSource) {
                transport?.enqueueSnapshot(snapshot)
            }

            override fun onSessionRemoved(session: SessionId) {
                log("session removed callId=" + session.callId)
            }

            override fun onHandedBack(session: SessionId, source: ActionSource, reason: String) {
                log("handed back callId=" + session.callId + " source=" + source.name + " reason=" + reason)
            }
        },
        sink = CommandSink { attempt -> onCommandAttempt(attempt) },
    )

    private var transport: HookTransport? = null

    init {
        transport = HookTransport(
            processEpoch = registry.processEpoch,
            liveSessions = { registry.liveSessions() },
            onCommand = { session, command -> dispatchCommand(session, command) },
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
        val methods = ServiceDiscovery.resolve(loader, services)
        if (methods.isEmpty()) {
            log("InCallService callbacks could not be resolved")
            return false
        }
        install(methods)
        return true
    }

    /**
     * Context 尚未就绪时的唯一延迟路径：`Instrumentation.callApplicationOnCreate` 之后
     * 进程一定已有 Application，可安全查询 PackageManager。只安装一次（集合去重）。
     */
    fun deferUntilApplicationCreated() {
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
                            val methods = ServiceDiscovery.resolve(loader, services)
                            if (methods.isEmpty()) {
                                log("no InCallService callbacks discovered after Application creation")
                            } else {
                                install(methods)
                            }
                        }
                    }
                },
            )
            log("deferred discovery until Application creation")
        } catch (failure: Throwable) {
            log("cannot defer discovery: " + failure.javaClass.name + ": " + (failure.message ?: "no message"))
        }
    }

    // ------------------------------------------------------------------ 安装

    private fun install(methods: Set<Method>) {
        val fresh = synchronized(installedMethods) { methods.filter { installedMethods.add(it) } }
        if (fresh.isEmpty()) return
        for (method in fresh) {
            try {
                XposedBridge.hookMethod(method, hookCallbackFor(method.name))
            } catch (failure: Throwable) {
                log("cannot hook " + method.declaringClass.name + "#" + method.name + ": " + failure.javaClass.name)
            }
        }
        transport?.start()
        log(
            "installed " + fresh.size + " hook(s): " +
                fresh.joinToString(",") { it.declaringClass.simpleName + "#" + it.name },
        )
    }

    private fun hookCallbackFor(methodName: String): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // 只读取参数并入队/更新内存；异常被隔离，原方法继续执行。
            HookSafety.guard("InCallService." + methodName, ::log) {
                onServiceCallback(methodName, param.thisObject, param.args)
            }
        }
    }

    // ------------------------------------------------------------------ 回调

    private fun onServiceCallback(methodName: String, thisObject: Any?, args: Array<Any?>?) {
        val service = thisObject as? InCallService ?: return
        val call = args?.firstOrNull() as? Call ?: return
        when (methodName) {
            "onCallAdded" -> onCallAdded(service, call)
            "onCallRemoved" -> onCallRemoved(call)
        }
    }

    private fun onCallAdded(service: InCallService, call: Call) {
        val handle = synchronized(handles) {
            handles[call] ?: AndroidCallHandle(
                call = call,
                processEpoch = registry.processEpoch,
                calls = { service.calls },
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

    private fun dispatchCommand(session: SessionId, command: CallCommand) {
        val result = registry.command(session, command)
        if (result !is CommandResult.Accepted) {
            log("command " + command + " rejected for callId=" + session.callId + ": " + result)
        }
    }

    /**
     * 命令尝试的注入 sink：只记录非 Accepted 结果，避免每通电话刷日志。这里不阻塞、
     * 不发起 IPC；真正的命令结果转发属 Task 7 的会话路由。
     */
    private fun onCommandAttempt(attempt: CommandAttempt) {
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

private const val TARGET_PACKAGE = "com.google.android.dialer"

private fun logToFramework(message: String) {
    try {
        XposedBridge.log("AgentCall: " + message)
    } catch (ignored: Throwable) {
        // 日志不可用时也不能影响 Hook 路径。
    }
}

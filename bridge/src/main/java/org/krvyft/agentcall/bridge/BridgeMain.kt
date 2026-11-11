package org.krvyft.agentcall.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krvyft.agentcall.bridge.audio.AndroidAudioPlatform
import org.krvyft.agentcall.bridge.audio.AndroidBootId
import org.krvyft.agentcall.bridge.audio.AudioPcmRelay
import org.krvyft.agentcall.bridge.audio.LocalSocketAudioRoleTransport
import org.krvyft.agentcall.bridge.audio.LocalSocketPcmWorkerLink
import org.krvyft.agentcall.bridge.audio.PcmLaneCodec
import org.krvyft.agentcall.bridge.audio.PcmOpenResult
import org.krvyft.agentcall.bridge.audio.PcmSessionOwner
import org.krvyft.agentcall.bridge.audio.PcmWorkerLinkFactory
import org.krvyft.agentcall.bridge.audio.RemoteSupervisedAudio
import org.krvyft.agentcall.bridge.config.CalibrationStep
import org.krvyft.agentcall.bridge.config.CalibrationStore
import org.krvyft.agentcall.bridge.config.CalibrationStoreException
import org.krvyft.agentcall.bridge.config.ConfigErrorCode
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.config.ConfigStoreException
import org.krvyft.agentcall.bridge.diagnostics.AndroidDialerDefaultRoleProbe
import org.krvyft.agentcall.bridge.diagnostics.AndroidPlatformFacts
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.DialerDefaultRole
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.bridge.diagnostics.ReflectiveAudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.AndroidPackageFactsProvider
import org.krvyft.agentcall.bridge.ipc.ControlServer
import org.krvyft.agentcall.bridge.ipc.HookCommandRouter
import org.krvyft.agentcall.bridge.ipc.PackageFactsSource
import org.krvyft.agentcall.bridge.ipc.PeerVerifier
import org.krvyft.agentcall.bridge.ipc.RootFramework
import org.krvyft.agentcall.bridge.ipc.RpcHandler
import org.krvyft.agentcall.bridge.supervision.AutoAnswerAuthorization
import org.krvyft.agentcall.bridge.supervision.CallStateProbe
import org.krvyft.agentcall.bridge.supervision.EmergencyCallControl
import org.krvyft.agentcall.bridge.supervision.HookCallInbox
import org.krvyft.agentcall.bridge.supervision.HookCommandCallControl
import org.krvyft.agentcall.bridge.supervision.HookSnapshotDecoder
import org.krvyft.agentcall.bridge.supervision.ReflectiveTelecomTermination
import org.krvyft.agentcall.bridge.supervision.SessionEventBus
import org.krvyft.agentcall.bridge.supervision.ShutdownResult
import org.krvyft.agentcall.bridge.supervision.SnapshotCallStateProbe
import org.krvyft.agentcall.bridge.supervision.SupervisionGraph
import org.krvyft.agentcall.bridge.supervision.SupervisionPlatform
import org.krvyft.agentcall.bridge.supervision.SupervisionStateStore
import org.krvyft.agentcall.bridge.supervision.Supervisor
import org.krvyft.agentcall.bridge.supervision.SupervisorProductionGraph
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.bridge.supervision.SupervisorRpcPort
import org.krvyft.agentcall.bridge.supervision.TakeoverOutcome
import org.krvyft.agentcall.bridge.supervision.TerminationOutcome
import org.krvyft.agentcall.bridge.supervision.TerminationPermission
import org.krvyft.agentcall.bridge.supervision.UnverifiedCallIdentityVerifier
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.policy.CalibrationBlocker
import org.krvyft.agentcall.core.policy.CalibrationPolicy
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.core.protocol.RpcCodec
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 特权运行容器版本。容器由 app_process 加载，没有 AndroidManifest 版本可用，
 * 因此版本由 :bridge 构建脚本从 :app 的 defaultConfig 生成为 BuildConfig.VERSION_NAME
 * （单一版本来源）；这里不再保存任何字面版本。
 */
internal object BridgeInfo {
    val VERSION: String = BuildConfig.VERSION_NAME

    /**
     * 本桥接进程的随机身份（每进程生成一次）。
     *
     * **这是 APP 的重启信标**（7B3 FIX ROUND 2）：uptimeMs 是墙钟差，系统时间回拨会让它
     * 变负（客户端判负载非法而停摆）或变小（误判重启而重放旧 handback）。进程 id 与时间
     * 无关：同一进程恒定，重启必然换新值。不要用它做任何访问控制/配对判断。
     */
    val PROCESS_ID: String = java.util.UUID.randomUUID().toString()
}

/**
 * 特权运行容器的进程入口（app_process 按类名加载）。
 *
 * 只做四件事：解析配置目录、解析包事实 provider、启动 [ControlServer] 与监督角色、
 * 等待 shutdown。任何不可用条件都以机器可读错误码退出，绝不降级为“信任所有对端”。
 */
object BridgeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            BridgeRuntime.run(args)
        } catch (t: Throwable) {
            System.err.println(machineErrorJson("bridge_start_failed", t.javaClass.simpleName))
            1
        }
        if (exitCode != 0) {
            System.exit(exitCode)
        }
    }
}

/**
 * 运行期装配。与 [BridgeMain] 分开，便于把可测试的配置/诊断/协议层和
 * 只能在真机/app_process 里成立的 Android 装配区分开。
 *
 * 本容器同时承载**监督角色**（spec §6.3：监督进程与音频工作进程分离）：监督状态机、
 * 启动恢复与周期轮询都在这里构造并运行；音频工作角色由 AudioWorkerMain 以独立
 * app_process 运行，两者只通过 root 私有的角色通道交互。
 */
internal object BridgeRuntime {

    /** args[0] 可选：配置主副本目录；只允许 [ConfigStore.DEFAULT_ROOT] 及其子目录。 */
    fun run(args: Array<String>): Int {
        val root = try {
            resolveConfigRoot(args)
        } catch (error: ConfigStoreException) {
            System.err.println(machineErrorJson(error.code, error.message ?: error.code))
            return 1
        }

        // 包事实来源分层获取（Task 11N）：context → appglobals → binder → shell 回退。
        // 没有任何 provider 才失败退出；shell 路径签名不可验证，连接核验会 fail closed，
        // 绝不降级为“信任消息自述”。选择结果由链输出 package_provider=<code>。
        val packageProvider = AndroidPackageFactsProvider.resolve()
        if (packageProvider == null) {
            System.err.println(
                machineErrorJson(
                    "package_manager_unavailable",
                    "no package facts provider is available (context/appglobals/binder/shell)",
                ),
            )
            return 1
        }
        val packages: PackageFactsSource = packageProvider.source

        val store = try {
            ConfigStore(root)
        } catch (error: ConfigStoreException) {
            System.err.println(machineErrorJson(error.code, error.message ?: error.code))
            return 1
        }
        val calibration = try {
            CalibrationStore(root)
        } catch (error: org.krvyft.agentcall.bridge.config.CalibrationStoreException) {
            System.err.println(machineErrorJson(error.code, error.message ?: error.code))
            return 1
        }

        // 默认拨号器事实：系统 RoleManager（权威）+ ACTION_DIAL 默认处理器（负证据）。
        // 两者都是本地查询；不可读时探测返回 UNKNOWN，status 线上值是字符串 "unknown"。
        // 默认处理器事实也走分层 provider：context 路径用 PackageManager，binder/shell
        // 路径用各自的反射/命令；读不到时探测保持 UNKNOWN（不猜默认拨号器）。
        val dialerDefaultRole = AndroidDialerDefaultRoleProbe(
            dialHandler = { packages.dialHandlerPackage() },
            roleManager = RootFramework.roleManager(),
            log = { System.err.println(it) },
        )

        val verifier = PeerVerifier(packages) { runCatching { store.readPairing() }.getOrNull() }
        val probe = DeviceProbe(
            packages = packages,
            platform = AndroidPlatformFacts(),
            audioApiProbe = ReflectiveAudioApiProbe(),
            bridgeVersion = BridgeInfo.VERSION,
            packageProvider = packageProvider.kind.wireCode,
            log = { System.err.println(it) },
        )
        // 环境在进程生命周期内固定（ROM/框架/桥接不变），只采集一次用于校准授权。
        val environment = probe.inspect().environment

        // 监督角色：生产图由唯一装配点构造（C1）；训练/测试不得另写一套逻辑。
        val hookCommands = HookCommandRouter()
        val inbox = HookCallInbox()
        // PCM 中继必须先于监督图存在（轮询的旁路清理要引用它），它的 owner 谓词读监督端
        // 实时快照；读取只发生在 [supervisionRef] 赋值之后（dispatcher/轮询线程都在其后）。
        var supervisionRef: SupervisionGraph? = null
        val pcmRelay = BridgeWiring.audioPcmRelay(
            owner = PcmSessionOwner { id -> supervisionRef?.supervisor?.snapshot()?.ownedSession == id },
            workerLinks = PcmWorkerLinkFactory { LocalSocketPcmWorkerLink().also { it.connect() } },
            clock = AndroidAudioPlatform.clock(),
            // I3：APP 下行写卡死按音频通路故障处理，监督端据此停音频/开人工接管窗口。
            onAppLaneFault = { id, code ->
                supervisionRef?.let { graph ->
                    runBlocking { graph.supervisor.onAudioFailure(id, code) }
                }
            },
        )
        val supervision = buildSupervision(
            root = root,
            hookCommands = hookCommands,
            inbox = inbox,
            calibration = calibration,
            environment = environment,
            // 每轮轮询前释放监督端已不再拥有的 APP 音频绑定（断开/故障终止/交还）。
            beforePoll = { pcmRelay.sweepDisowned() },
        )
        supervisionRef = supervision
        val bootOutcome = runBlocking { supervision.recovery.recover() }
        supervision.supervisor.start()

        val shutdown = CountDownLatch(1)
        // 每进程一份的 APP 事件流（Task 7C）：由 HOOK 快照路径与监督端交还/断开事实喂入，
        // 只有 APP 能轮询；容量固定，慢消费者不会让内存增长。
        val sessionEvents = SessionEventBus()
        var server: ControlServer? = null
        val supervisionRpc = SupervisorRpcPort(supervision.supervisor, inbox)
        val pollRunning = AtomicBoolean(true)
        val pollThread = Thread(
            { runBlocking { supervision.loop.run { !pollRunning.get() } } },
            "agentcall-supervision-poll",
        ).apply {
            isDaemon = true
            start()
        }
        // 控制面 shutdown 与进程退出共用同一次优雅关闭：先终止/交还通话、确认后再恢复麦克风。
        val lifecycle = BridgeLifecycle(
            supervisor = supervision.supervisor,
            pcmRelay = pcmRelay,
            controlServer = { server },
            pollRunning = pollRunning,
            pollThread = pollThread,
        )
        val dispatcher = ControlDispatcher(
            store = store,
            packages = packages,
            probe = probe,
            // 授权、calibration.read、status、diagnose 必须使用同一份进程启动环境。
            environment = { environment },
            shutdown = shutdown,
            supervision = supervisionRpc,
            hookCommands = hookCommands,
            // 与监督端共享同一份收件箱：APP 的 session.command 只能等这条结果通路，
            // 不存在第二条并行结果路径（C1 唯一装配点）。
            hookInbox = inbox,
            connectionStats = {
                val current = server
                if (current == null) 0 to 0L else current.activeConnectionCount to current.rejectedConnectionCount
            },
            dialerDefaultRole = dialerDefaultRole,
            audioPcm = pcmRelay,
            sessionEvents = sessionEvents,
            calibration = calibration,
            lifecycle = lifecycle,
            // 本进程的关闭码优先；进程退出后从监督状态文件恢复的上一次关闭码兜底。
            shutdownCode = { lifecycle.shutdownCode ?: supervision.previousShutdownCode },
        )

        // PCM 通道由 ControlServer 独立 bind（PCM lane）；音频工作角色负责真实 PCM
        // 生产/消费（AudioWorkerMain 的 AudioWorkerPcmSocketServer）。
        val controlServer = ControlServer(
            verifier = verifier,
            handler = dispatcher,
            pcmSession = pcmRelay,
            // 与 audio.channel 返回给 APP 的名字同源：两处都由 BridgeWiring 决定。
            pcmSocketName = BridgeWiring.pcmSocketName(),
        )
        server = controlServer
        try {
            controlServer.start()
        } catch (io: IOException) {
            System.err.println(machineErrorJson("socket_bind_failed", io.javaClass.simpleName))
            pollRunning.set(false)
            pollThread.interrupt()
            return 1
        }

        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    // SIGTERM/模块停用：优雅关闭会先终止或交还拥有的通话，确认后才恢复麦克风。
                    runBlocking { lifecycle.shutdown() }
                    lifecycle.closeResources()
                },
                "agentcall-bridge-shutdown",
            ),
        )
        val ready = JsonObject()
        ready.addProperty("state", "ready")
        ready.addProperty("socket", ControlServer.DEFAULT_CONTROL_SOCKET)
        ready.addProperty("version", BridgeInfo.VERSION)
        ready.addProperty("supervisionBoot", bootOutcome.name)
        ready.addProperty("supervisionPhase", supervision.supervisor.snapshot().phase.name)
        ready.addProperty("supervisionBlocked", supervision.supervisor.snapshot().blockedReason)
        System.out.println(ready.toString())

        try {
            shutdown.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // 控制面 shutdown 已经跑过时直接复用结论；资源在这里关闭（响应已写回）。
            runBlocking { lifecycle.shutdown() }
            lifecycle.closeResources()
        }
        return 0
    }

    /**
     * 监督角色的 Android 生产装配。
     *
     * 安全取舍（spec §8）：
     * - 特权 Telecom 身份核验默认 [UnverifiedCallIdentityVerifier] 不可用，因此
     *   [EmergencyCallControl.blocksAutoAnswer] 为 true，自动接听保持关闭，直到真机
     *   验证了按身份终止的路径；
     * - 校准授权来自 [CalibrationStore]，五项人工测试 + 环境绑定缺一不可；
     * - 音频端口是独立音频工作进程的代理，监督进程不持有任何麦克风/音频句柄。
     */
    internal fun buildSupervision(
        root: File,
        hookCommands: HookCommandRouter,
        inbox: HookCallInbox,
        calibration: CalibrationStore,
        environment: EnvironmentKey,
        /** 每轮轮询前的旁路清理（生产：释放已不归监督端的 APP 音频绑定）。 */
        beforePoll: () -> Unit = {},
    ): SupervisionGraph {
        val clock: MonotonicClock = AndroidAudioPlatform.clock()
        val stateStore = SupervisionStateStore(root)
        val audio = RemoteSupervisedAudio(LocalSocketAudioRoleTransport(), clock)
        val calls = HookCommandCallControl(
            enqueue = { id, command -> hookCommands.enqueueCommand(id, command) },
            inbox = inbox,
        )
        val emergency = EmergencyCallControl(
            permissions = TerminationPermission { android.os.Process.myUid() == 0 },
            identities = UnverifiedCallIdentityVerifier,
            termination = ReflectiveTelecomTermination(),
        )
        // 环境仍是服务端构建；transport/route 桥接从不观测（始终 UNKNOWN），只能由 root 背书（C1）。
        val authorization = AutoAnswerAuthorization {
            isCalibrationAuthorizedFor(calibration, environment)
        }
        return SupervisorProductionGraph.build(
            object : SupervisionPlatform {
                override fun clock(): MonotonicClock = clock
                override fun supervisedAudio() = audio
                override fun calls() = calls
                override fun emergency() = emergency
                override fun authorization() = authorization
                override fun stateStore() = stateStore
                override fun bootId(): String? = AndroidBootId.current()
                // 接听结果不明确时必须能核验真实状态：生产来源是 Hook 快照收件箱。
                override fun callState(): CallStateProbe = SnapshotCallStateProbe(inbox)
            },
            beforePoll = beforePoll,
        )
    }
}

/** 优雅关闭端口：控制面 shutdown 与进程退出共用同一次关闭（幂等）。 */
fun interface BridgeShutdownPort {
    suspend fun shutdown(): ShutdownResult
}

/**
 * 桥接进程的优雅关闭协调器（Task 11D2a，spec §8）。
 *
 * 顺序：停轮询 → [Supervisor.shutdown]（有界终止/交还 + 断开确认 + 允许时才恢复）→
 * 关闭资源。优雅关闭与资源关闭分开：控制面 `shutdown` 必须先把机器可读结果写回客户端，
 * 之后 main 的 finally 才关闭 ControlServer（它自带有限刷新宽限）。
 *
 * 崩溃/SIGKILL 不经过本类：隔离可能保留，只有优雅关闭路径会终止/交还并恢复麦克风。
 */
internal class BridgeLifecycle(
    private val supervisor: Supervisor,
    private val pcmRelay: Closeable,
    private val controlServer: () -> Closeable?,
    private val pollRunning: AtomicBoolean,
    private val pollThread: Thread,
    private val log: (String) -> Unit = { System.err.println(it) },
) : BridgeShutdownPort {

    private val mutex = Mutex()
    private var graceful: ShutdownResult? = null
    private val resourcesClosed = AtomicBoolean(false)

    @Volatile
    private var lastCode: String? = null

    /** `status.shutdownCode`：最近一次优雅关闭的机器码；未关闭/干净关闭为 null。 */
    val shutdownCode: String? get() = lastCode

    override suspend fun shutdown(): ShutdownResult = mutex.withLock {
        graceful ?: performGracefulShutdown()
    }

    private suspend fun performGracefulShutdown(): ShutdownResult {
        pollRunning.set(false)
        pollThread.interrupt()
        val result = try {
            supervisor.shutdown()
        } catch (t: Throwable) {
            // 关闭路径自身异常必须留下机器码；按“隔离可能仍在”失败关闭。
            ShutdownResult(
                ownedSession = null,
                termination = null,
                ownershipResolved = false,
                disconnected = false,
                microphoneIsolated = true,
                code = SHUTDOWN_FAILED,
            )
        }
        lastCode = result.code
        log(
            "{\"event\":\"bridge_shutdown\",\"clean\":" + result.clean +
                ",\"owned\":" + (result.ownedSession != null) +
                ",\"termination\":" + (result.termination?.name ?: "null") +
                ",\"disconnected\":" + result.disconnected +
                ",\"microphoneIsolated\":" + result.microphoneIsolated +
                ",\"code\":" + (result.code?.let { JsonPrimitive(it).toString() } ?: "null") + "}",
        )
        return result
    }

    /** 关闭 PCM/socket/监督角色；在 shutdown 响应写回之后调用。重复调用安全。 */
    fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        try {
            supervisor.close()
        } catch (t: Throwable) {
            log("{\"event\":\"bridge_shutdown_close_failed\",\"component\":\"supervisor\"}")
        }
        try {
            pcmRelay.close()
        } catch (t: Throwable) {
            log("{\"event\":\"bridge_shutdown_close_failed\",\"component\":\"pcm\"}")
        }
        try {
            controlServer()?.close()
        } catch (t: Throwable) {
            log("{\"event\":\"bridge_shutdown_close_failed\",\"component\":\"control\"}")
        }
    }

    companion object {
        /** 监督角色关闭抛异常：没有可信结论，按失败关闭上报。 */
        const val SHUTDOWN_FAILED: String = "shutdown_failed"
    }
}

/**
 * APP 音频 lane 的唯一生产装配点。
 *
 * audio.channel 返回给 APP 的 socket 名必须**就是** [ControlServer] 真正 bind 的 APP lane
 * 名字（[ControlServer.DEFAULT_PCM_SOCKET] = `org.krvyft.agentcall.pcm`）。桥接 ↔ 音频工作
 * 进程之间另有一条 root 私有的 lane（[AudioPcmProtocol.SOCKET_NAME] =
 * `org.krvyft.agentcall.audio-pcm`），它只接受 uid 0，APP 连不上——绝不能把它写进
 * audio.channel 的响应（评审 C1：7B 会照着连，然后被拒）。
 *
 * 装配点集中在这里，测试断言的是生产装配的字面值，而不是两处共用同一个常量
 * （共用常量会让"两边一起写错"永远通过）。
 */
internal object BridgeWiring {

    /** APP 可达的 PCM lane 名（ControlServer 在 binding 时使用的那个）。 */
    fun pcmSocketName(): String = ControlServer.DEFAULT_PCM_SOCKET

    fun audioPcmRelay(
        owner: PcmSessionOwner,
        workerLinks: PcmWorkerLinkFactory,
        clock: MonotonicClock,
        /** I3：APP 音频通路故障上报（生产接监督端 onAudioFailure）。 */
        onAppLaneFault: (SessionId, String) -> Unit = { _, _ -> },
    ): AudioPcmRelay = AudioPcmRelay(
        owner = owner,
        workerLinks = workerLinks,
        clock = clock,
        socketName = pcmSocketName(),
        onAppLaneFault = onAppLaneFault,
    )
}

/**
 * 解析并限制生产入口的配置根。
 *
 * 运行容器由 root app_process 启动，argv 不能把明文密钥主副本指到任意目录（例如
 * 非 POSIX 的共享/可移动目录）。只接受 [ConfigStore.DEFAULT_ROOT] 及其规范化后的
 * 子目录，其余抛 [ConfigErrorCode.CONFIG_ROOT_REJECTED] 并退出，不创建任何目录。
 *
 * 测试需要临时目录时走 [ConfigStore] 的构造参数（文档化的 test-only 注入点），
 * 不通过本入口，因此生产路径没有“测试开关”可被 argv 触发。
 */
internal fun resolveConfigRoot(args: Array<String>): File {
    val configured = args.firstOrNull() ?: ConfigStore.DEFAULT_ROOT
    val allowed = try {
        File(ConfigStore.DEFAULT_ROOT).canonicalFile
    } catch (io: IOException) {
        throw ConfigStoreException(ConfigErrorCode.CONFIG_ROOT_REJECTED, "config root could not be resolved", io)
    }
    val candidate = try {
        File(configured).canonicalFile
    } catch (io: IOException) {
        throw ConfigStoreException(ConfigErrorCode.CONFIG_ROOT_REJECTED, "config root could not be resolved", io)
    }
    val withinAllowedRoot = candidate == allowed || candidate.toPath().startsWith(allowed.toPath())
    if (!withinAllowedRoot) {
        throw ConfigStoreException(
            ConfigErrorCode.CONFIG_ROOT_REJECTED,
            "config root must stay under " + ConfigStore.DEFAULT_ROOT,
        )
    }
    return candidate
}

/**
 * 固定命令表的分发器。鉴权已在 [ControlServer] 用 [RolePolicy] 完成；
 * 这里按方法名分派，未知方法返回机器可读错误。
 *
 * 密钥只经 config.secret.read（仅 APP）返回；其余方法一律返回脱敏配置。
 * 任何错误消息都不拼接请求负载或密钥。
 *
 * 监督接入（C1）：
 * - 任何已鉴权的 APP/HOOK 请求都按其 sessionId 记心跳（按会话归属，I1）；
 * - session.request → 监督端准备接听；session.heartbeat → 只记录 APP 存在性；
 *   session.command → 受限命令排入 HOOK 路由并在 4000 ms 内等真实结果；handover → 人工交还；
 * - hook.snapshot → 更新真实通话状态并推动 ACTIVE/断开/交还；
 * - call.command.result → 同时喂给监督端的命令结果收件箱。
 */
internal class ControlDispatcher(
    private val store: ConfigStore,
    private val packages: PackageFactsSource,
    private val probe: DeviceProbe,
    /**
     * 判定/校准使用的当前环境。生产装配注入**进程启动时采集一次**的环境：自动接听授权、
     * calibration.read、status、diagnose 必须看到同一份事实；默认值只服务于测试构造点。
     */
    private val environment: () -> EnvironmentKey = { probe.inspect().environment },
    private val shutdown: CountDownLatch,
    private val supervision: SupervisionRpcPort = SupervisionRpcPort(),
    private val startedAtMs: Long = System.currentTimeMillis(),
    /**
     * 本进程的重启信标（每进程随机 id，见 [BridgeInfo.PROCESS_ID]）。
     *
     * 只用于让 APP 发现“桥接换进程了”；不是会话身份，也不参与鉴权。
     */
    private val processId: String = BridgeInfo.PROCESS_ID,
    private val connectionStats: () -> Pair<Int, Long> = { 0 to 0L },
    private val hookCommands: HookCommandRouter = HookCommandRouter(),
    /**
     * 监督端与 HOOK 结果共用的收件箱。
     *
     * 生产装配**必须**注入 [BridgeRuntime.buildSupervision] 使用的同一实例，绝不能新建
     * 第二个收件箱（否则 APP 的 session.command 会等待一条永远不会有结果的并行队列）。
     * 默认值只服务于不经过 APP 命令路径的构造点（诊断/测试）；唯一生产装配点已显式注入。
     */
    private val hookInbox: HookCallInbox = HookCallInbox(),
    /** 默认拨号器探测：默认 fail closed 为未知，生产装配注入 Android 实现。 */
    private val dialerDefaultRole: () -> DialerDefaultRole = { DialerDefaultRole.UNKNOWN },
    /**
     * APP 音频通道的中继。默认 null = 未接线（audio.channel 明确返回
     * method_not_implemented，绝不假装成功）；生产装配由 BridgeRuntime 注入。
     */
    private val audioPcm: AudioPcmRelay? = null,
    /**
     * APP 轮询的会话事件流（Task 7C）。**每进程一份**：生产装配由 [BridgeRuntime] 显式
     * 注入同一个实例；本分派器是唯一的写入者（HOOK 快照路径 + 监督端交还/断开事实）
     * 与唯一读取者（session.events）。默认值只服务于不经事件通道的测试构造点。
     */
    private val sessionEvents: SessionEventBus = SessionEventBus(),
    /**
     * session.command 等待 HOOK 结果的硬上限。默认就是与监督端同源的
     * [APP_COMMAND_TIMEOUT_MS]（4000 ms）；显式参数只用于测试缩短等待。
     */
    private val commandTimeoutMs: Long = APP_COMMAND_TIMEOUT_MS,
    /**
     * 校准证据存储（B2）。默认 null = 未接线：calibration.read/record 返回
     * [ERROR_CALIBRATION_UNAVAILABLE]，绝不假装已验证。生产装配注入真实实例。
     */
    private val calibration: CalibrationStore? = null,
    /**
     * 优雅关闭端口（Task 11D2a）。默认 null = 未接线：`shutdown` 只回旧的
     * `{"stopping":true}` 并触发进程退出，不伪造关闭结论（测试/旧装配）。
     */
    private val lifecycle: BridgeShutdownPort? = null,
    /** `status.shutdownCode` 来源；未接线恒 null。 */
    private val shutdownCode: () -> String? = { null },
) : RpcHandler {

    /**
     * 为某个 HOOK 会话排入一条受限通话命令；随该会话下一次 hook.snapshot/hook.heartbeat
     * 的响应负载下发。Task 7 的 CallControl.send 经此进入 Hook，不在 bridge 内直接操作 Telecom。
     * @return false 表示会话字段非法或队列饱和（有界失败）
     */
    fun enqueueHookCommand(session: SessionId, command: CallCommand): Boolean =
        hookCommands.enqueueCommand(session, command)

    override fun handle(peer: PeerIdentity, request: RpcRequest): RpcResponse = try {
        // 任何已鉴权请求都证明该角色此刻活着；存活按会话归属记录（I1）。
        request.sessionId?.let { supervision.heartbeat(it, peer.role) }
        when (request.method) {
            RolePolicy.METHOD_STATUS -> ok(request, statusPayload())
            RolePolicy.METHOD_CONFIG_READ -> ok(request, RpcCodec.encodeRedacted(store.read()))
            RolePolicy.METHOD_CONFIG_SECRET_READ -> secretRead(request)
            RolePolicy.METHOD_CONFIG_WRITE -> configWrite(request)
            RolePolicy.METHOD_PAIR -> pair(request)
            RolePolicy.METHOD_DIAGNOSE -> ok(request, diagnosePayload())
            RolePolicy.METHOD_SHUTDOWN -> shutdownResponse(request)
            RolePolicy.METHOD_SESSION_REQUEST -> sessionRequest(request)
            RolePolicy.METHOD_SESSION_HEARTBEAT -> sessionHeartbeat(request)
            RolePolicy.METHOD_SESSION_COMMAND -> sessionCommand(request)
            RolePolicy.METHOD_SESSION_EVENTS -> sessionEventsPoll(request)
            RolePolicy.METHOD_HANDOVER -> handover(request)
            RolePolicy.METHOD_HOOK_SNAPSHOT,
            RolePolicy.METHOD_HOOK_HEARTBEAT,
            -> hookCommandLines(request)
            RolePolicy.METHOD_CALL_COMMAND_RESULT -> hookCommandResult(request)
            RolePolicy.METHOD_AUDIO_CHANNEL -> audioChannel(request, peer)
            RolePolicy.METHOD_CALIBRATION_READ -> calibrationRead(request)
            RolePolicy.METHOD_CALIBRATION_RECORD -> calibrationRecord(request, peer)
            else -> failure(request, "method_not_implemented", "method is not in the fixed command table")
        }
    } catch (error: ConfigStoreException) {
        // ConfigStoreException.message 只含公开字段与错误码。
        failure(request, error.code, error.message ?: error.code)
    } catch (error: CalibrationStoreException) {
        // 校准文件损坏/更新 schema/磁盘失败：机器码 + 固定短消息，不含路径。
        failure(request, error.code, error.message ?: error.code)
    } catch (t: Throwable) {
        // 其它异常的文本可能含路径：只回类名。config-set 的反射/校验 Throwable 有
        // 专用机器码，绝不回 internal_error。
        if (request.method == RolePolicy.METHOD_CONFIG_WRITE) {
            failure(request, ERROR_CONFIG_VALIDATION_UNAVAILABLE, "config validation could not be completed")
        } else {
            failure(request, "internal_error", t.javaClass.simpleName)
        }
    }

    /**
     * shutdown：先执行一次有界的优雅关闭（终止/交还拥有的通话、必要时恢复麦克风），
     * 再把机器可读结果写进响应并触发进程退出（main 的 finally 负责关闭资源）。
     * 未接线时保持旧的 ack 形状，绝不伪造“干净关闭”。
     */
    private fun shutdownResponse(request: RpcRequest): RpcResponse {
        val result = lifecycle?.let { runBlocking { it.shutdown() } }
        shutdown.countDown()
        if (result == null) return ok(request, "{\"stopping\":true}")
        val json = JsonObject()
        json.addProperty("stopping", true)
        json.addProperty("clean", result.clean)
        json.addProperty("shutdownCode", result.code)
        json.add("supervision", encodeShutdownResult(result))
        return ok(request, json.toString())
    }

    /** 关闭裁决的固定机器可读形状（不含会话内容/路径）。 */
    private fun encodeShutdownResult(result: ShutdownResult): JsonObject = JsonObject().apply {
        addProperty("clean", result.clean)
        addProperty("ownedSession", result.ownedSession != null)
        val termination = result.termination
        if (termination == null) {
            add("termination", JsonNull.INSTANCE)
        } else {
            addProperty("termination", termination.name)
        }
        addProperty("ownershipResolved", result.ownershipResolved)
        addProperty("disconnected", result.disconnected)
        addProperty("microphoneIsolated", result.microphoneIsolated)
        if (result.code == null) add("code", JsonNull.INSTANCE) else addProperty("code", result.code)
    }

    /**
     * session.request：APP 请求为一通已通过自身预检的电话建立 AI 会话。
     * 监督端仍会重新检查心跳/校准/独立终止能力；拒绝时明确返回原因。
     */
    private fun sessionRequest(request: RpcRequest): RpcResponse {
        val session = request.sessionId
            ?: return failure(request, "payload_invalid", "session.request must carry a session id")
        if (request.payload.isNotEmpty() && request.payload.length > MAX_SESSION_REQUEST_CHARS) {
            return failure(request, "payload_invalid", "session.request payload is too large")
        }
        // I2：整个 prepareAnswer 窗口内 APP 都不许再发 Answer——否则两条等待会抢同一条
        // 结果，监督端可能把"结果被抢走"误判成超时并进入不明确的接管路径。
        hookInbox.beginSupervisorAnswer(session)
        try {
            val result = runBlocking { supervision.prepareAnswer(session) }
            return ok(request, encodeCommandResult(result))
        } finally {
            hookInbox.endSupervisorAnswer(session)
        }
    }

    /**
     * session.heartbeat：APP 仍在看着这通电话的存在性信号（只到监督端，不看通话状态）。
     *
     * 绝不触发 prepareAnswer、不改监督阶段、不排任何命令：显式按 [PeerRole.APP] 记录
     * presence，是“谁活着”的证据，不是“该接听”的指令。会话 ID 不合法时直接拒绝。
     */
    private fun sessionHeartbeat(request: RpcRequest): RpcResponse {
        val session = request.sessionId
            ?: return failure(request, ERROR_SESSION_REQUIRED, "session.heartbeat must carry a session id")
        if (!HookCommandRouter.isWireSafeSession(session)) {
            return failure(request, ERROR_SESSION_REQUIRED, "session id is invalid")
        }
        supervision.heartbeat(session, PeerRole.APP)
        val json = JsonObject()
        json.addProperty("acknowledged", true)
        return ok(request, json.toString())
    }

    /**
     * session.command：APP 请求一条受限通话命令（answer/hangup）。
     *
     * 命令进入生产 [HookCommandRouter]（与监督端同一个实例），随 HOOK 下一次
     * hook.snapshot/hook.heartbeat 的响应下发；本方法在 [APP_COMMAND_TIMEOUT_MS] 内等待
     * 同一份 [HookCallInbox] 里的真实 [CommandResult]。排队失败与超时都 fail closed，
     * 绝不编造 Accepted、也绝不由 bridge 自己操作 Telecom。
     */
    private fun sessionCommand(request: RpcRequest): RpcResponse {
        val session = request.sessionId
            ?: return failure(request, ERROR_SESSION_REQUIRED, "session.command must carry a session id")
        if (!HookCommandRouter.isWireSafeSession(session)) {
            return failure(request, ERROR_SESSION_REQUIRED, "session id is invalid")
        }
        val command = when (val decoded = decodeAppCommand(request.payload)) {
            is AppCommandDecode.Supported -> decoded.command
            AppCommandDecode.Unsupported -> return failure(
                request,
                ERROR_UNSUPPORTED_COMMAND,
                "command is not in the restricted command set",
            )
            AppCommandDecode.Invalid -> return failure(
                request,
                ERROR_PAYLOAD_INVALID,
                "session.command payload must be {\"command\":\"answer\"|\"hangup\"}",
            )
        }
        // I2/I3：先建立（或抢不到）一个专属等待凭据，再入队。监督端正在接听时 APP 的
        // answer 直接 answer_in_flight；同一个 (session, command) 也绝不允许两个 APP 等待者。
        val wait = when (val started = hookInbox.beginAppWait(session, command)) {
            is HookCallInbox.AppWait.Started -> started.wait
            HookCallInbox.AppWait.SupervisorInFlight -> return failure(
                request,
                inFlightCode(command),
                "the supervisor is already waiting for this command result",
            )
            HookCallInbox.AppWait.WaiterInFlight -> return failure(
                request,
                inFlightCode(command),
                "another APP request is already waiting for this command result",
            )
        }
        try {
            if (!hookCommands.enqueueCommand(session, command)) {
                return failure(request, ERROR_COMMAND_NOT_QUEUED, "hook command queue rejected the command")
            }
            return when (val outcome = runBlocking { hookInbox.awaitResult(wait, commandTimeoutMs) }) {
                is HookCallInbox.AwaitOutcome.Delivered -> ok(request, encodeCommandResult(outcome.result))
                HookCallInbox.AwaitOutcome.TimedOut -> failure(
                    request,
                    ERROR_COMMAND_TIMEOUT,
                    "no hook command result within " + commandTimeoutMs + " ms",
                )
                HookCallInbox.AwaitOutcome.Superseded -> failure(
                    request,
                    inFlightCode(command),
                    "the supervisor took over this command result",
                )
            }
        } finally {
            hookInbox.endWait(wait)
        }
    }

    /**
     * audio.channel：APP 为某一 SessionId 申请 PCM lane 绑定（Task 7A2）。
     *
     * 只做“登记 + 授权判定”，不碰音频：真正建立链路要求 APP 随后连接 PCM socket 并
     * 在握手里重复同一 SessionId/格式。会话必须由监督端当前持有，且同一个 APP 身份
     * 才能接入；响应只含公开字段（socket 名/格式），不含号码或密钥。
     */
    private fun audioChannel(request: RpcRequest, peer: PeerIdentity): RpcResponse {
        val session = request.sessionId
            ?: return failure(request, ERROR_SESSION_REQUIRED, "audio.channel must carry a session id")
        if (!HookCommandRouter.isWireSafeSession(session)) {
            return failure(request, ERROR_SESSION_REQUIRED, "session id is invalid")
        }
        val relay = audioPcm
            ?: return failure(request, "method_not_implemented", "APP audio lane is not wired")
        val format = PcmLaneCodec.decodeFormatRequest(request.payload.toByteArray(Charsets.UTF_8))
            ?: return failure(
                request,
                ERROR_PAYLOAD_INVALID,
                "audio.channel payload must be {\"format\":{\"sampleRate\",\"channels\",\"encoding\"}}",
            )
        return when (val result = relay.open(session, peer, format)) {
            is PcmOpenResult.Opened -> {
                val json = JsonObject()
                json.addProperty("opened", true)
                json.addProperty("socket", result.socketName)
                json.add("format", PcmLaneCodec.encodeFormatJson(result.format))
                ok(request, json.toString())
            }
            is PcmOpenResult.Rejected -> failure(request, result.code, result.message)
        }
    }

    // ------------------------------------------------------------------ 校准（B2）

    /**
     * calibration.read：只读校准视图。
     *
     * 环境是与自动接听授权同源的进程环境（服务端事实，不是请求内容）；证据来自
     * [CalibrationStore]。负载只有环境字段、五项布尔事实、服务端时间戳、
     * [CalibrationPolicy] 的未满足项与 operator_attested 背书标记，绝不含密钥、号码或
     * 配置内容。
     */
    private fun calibrationRead(request: RpcRequest): RpcResponse {
        val store = calibration
            ?: return failure(request, ERROR_CALIBRATION_UNAVAILABLE, "calibration store is not wired")
        val evidence = try {
            store.load()
        } catch (error: CalibrationStoreException) {
            return failure(request, error.code, error.message ?: error.code)
        }
        return ok(request, calibrationView(environment(), evidence).toString())
    }

    /**
     * calibration.record：root 操作员为**当前服务端环境**背书五项人工测试事实。
     *
     * 请求只能提供五项布尔、可选的“预期环境”（仅用于一致性核验）与可选的
     * operatorAttestation。证据环境与时间戳一律由桥接进程生成：环境来自服务端采集
     * （transport/route 桥接从不观测、只能由 root 背书；frameworkVersion/simCarrier 有真实
     * 观测源，只在观测不到时才由背书填补），
     * 时间戳由 [CalibrationStore] 的时钟写入。未知/不一致环境、缺失步骤、非法背书一律
     * 拒绝；APP/HOOK 在 [RolePolicy] 层就已被拒绝，本方法再按角色二次拒绝。
     */
    private fun calibrationRecord(request: RpcRequest, peer: PeerIdentity): RpcResponse {
        // 方法表已把该方法限定给 CLI_ROOT；这里是服务端第二道判定（APP/HOOK 绝不背书）。
        if (peer.role != PeerRole.CLI_ROOT) {
            return failure(request, ControlServer.ERROR_FORBIDDEN, "calibration.record is restricted to the root CLI")
        }
        val store = calibration
            ?: return failure(request, ERROR_CALIBRATION_UNAVAILABLE, "calibration store is not wired")
        val decoded = decodeCalibrationRecord(request.payload)
            ?: return failure(
                request,
                ERROR_PAYLOAD_INVALID,
                "calibration.record payload must carry exactly the five boolean manual-test steps and an optional operatorAttestation",
            )
        val observed = environment()
        val effective = applyAttestation(observed, decoded.attestation)
        if (!isCompleteEnvironment(effective)) {
            return failure(
                request,
                ERROR_CALIBRATION_ENV_UNKNOWN,
                "current environment has unknown facts; calibration must not be bound to it",
            )
        }
        val expected = decoded.expectedEnvironment
        if (expected != null) {
            if (!isCompleteEnvironment(expected)) {
                return failure(
                    request,
                    ERROR_CALIBRATION_ENV_UNKNOWN,
                    "expected environment has unknown facts",
                )
            }
            if (expected != effective) {
                return failure(
                    request,
                    ERROR_CALIBRATION_ENV_MISMATCH,
                    "expected environment does not match the current device environment",
                )
            }
        }
        var evidence: CalibrationEvidence? = null
        for ((step, passed) in decoded.steps) {
            evidence = store.record(step, passed, effective)
        }
        return ok(request, encodeCalibrationRecordResult(evidence!!, observed, effective))
    }

    /** 当前环境能否作为校准绑定的可复现事实（任何字段 UNKNOWN/空白都不行）。 */
    private fun isCompleteEnvironment(environment: EnvironmentKey): Boolean =
        CalibrationBlocker.ENVIRONMENT_INCOMPLETE !in
            CalibrationPolicy.blockers(environment, CalibrationEvidence(environment = environment))

    /**
     * 校准视图（calibration.read 与内嵌到 status/diagnose 的**同一份**负载）。
     *
     * 固定键：
     * - available：校准存储是否接线；status/diagnose 未接线时只给 `{"available":false}`；
     * - environment：服务端可观测环境（桥接读不到的就是 UNKNOWN）；
     * - effectiveEnvironment：应用 root 背书后的判定环境；
     * - environmentComplete / verified / blockers / environmentMatchesEvidence；
     * - evidenceEnvironment：证据里持久化的环境（可能含背书值）；
     * - operatorAttestation：{operatorAttested, transport, route, frameworkVersion, simCarrier}，
     *   明确标记每个被 root 操作员背书的字段（观测到的事实绝不标成背书）；
     * - steps / calibratedAtMs。
     */
    private fun calibrationView(observed: EnvironmentKey, evidence: CalibrationEvidence?): JsonObject {
        val evidenceEnvironment = evidence?.environment
        val effective = effectiveEnvironment(observed, evidenceEnvironment)
        val judgedEvidence = evidence ?: CalibrationEvidence(environment = effective)
        val blockers = CalibrationPolicy.blockers(effective, judgedEvidence)
        val json = JsonObject()
        json.addProperty("available", true)
        json.addProperty("supportedApiLevel", CalibrationPolicy.SUPPORTED_API_LEVEL)
        json.add("environment", encodeEnvironmentKey(observed))
        json.add("effectiveEnvironment", encodeEnvironmentKey(effective))
        json.addProperty("environmentComplete", isCompleteEnvironment(effective))
        json.addProperty("verified", evidence != null && CalibrationPolicy.canAutoAnswer(effective, judgedEvidence))
        json.add("blockers", JsonArray().apply { blockers.forEach { add(JsonPrimitive(it.name)) } })
        json.addProperty("environmentMatchesEvidence", evidence != null && effective == evidenceEnvironment)
        if (evidence == null) {
            json.add("evidenceEnvironment", JsonNull.INSTANCE)
        } else {
            json.add("evidenceEnvironment", encodeEnvironmentKey(evidence.environment))
        }
        json.add("operatorAttestation", encodeOperatorAttestation(attestedFacts(observed, evidenceEnvironment)))
        json.add("steps", encodeCalibrationSteps(evidence))
        json.addProperty("calibratedAtMs", evidence?.calibratedAtMs ?: 0L)
        return json
    }

    private fun encodeCalibrationRecordResult(
        evidence: CalibrationEvidence,
        observed: EnvironmentKey,
        effective: EnvironmentKey,
    ): String {
        val blockers = CalibrationPolicy.blockers(effective, evidence)
        val json = JsonObject()
        json.addProperty("recorded", true)
        json.add("environment", encodeEnvironmentKey(effective))
        json.add("operatorAttestation", encodeOperatorAttestation(attestedFacts(observed, effective)))
        json.add("steps", encodeCalibrationSteps(evidence))
        json.addProperty("verified", CalibrationPolicy.canAutoAnswer(effective, evidence))
        json.add("blockers", JsonArray().apply { blockers.forEach { add(JsonPrimitive(it.name)) } })
        json.addProperty("calibratedAtMs", evidence.calibratedAtMs)
        return json.toString()
    }

    private fun encodeOperatorAttestation(attested: AttestedFacts): JsonObject = JsonObject().apply {
        addProperty("operatorAttested", attested.operatorAttested)
        if (attested.transport == null) add("transport", JsonNull.INSTANCE) else addProperty("transport", attested.transport)
        if (attested.route == null) add("route", JsonNull.INSTANCE) else addProperty("route", attested.route)
        if (attested.frameworkVersion == null) {
            add("frameworkVersion", JsonNull.INSTANCE)
        } else {
            addProperty("frameworkVersion", attested.frameworkVersion)
        }
        if (attested.simCarrier == null) add("simCarrier", JsonNull.INSTANCE) else addProperty("simCarrier", attested.simCarrier)
    }

    /**
     * status/diagnose 内嵌的校准视图；未接线时保持固定形状 `{"available":false}`，
     * 读取失败只报告不可用，绝不能拖垮 status 或伪造已验证。
     */
    private fun calibrationPayload(): JsonObject {
        val store = calibration ?: return JsonObject().apply { addProperty("available", false) }
        return try {
            calibrationView(environment(), store.load())
        } catch (error: CalibrationStoreException) {
            JsonObject().apply {
                addProperty("available", false)
                addProperty("error", error.code)
            }
        }
    }

    private fun diagnosePayload(): String {
        val json = JsonParser.parseString(RpcCodec.encodeCapabilityReport(probe.inspect())).asJsonObject
        json.add("calibration", calibrationPayload())
        return json.toString()
    }

    private fun encodeCalibrationSteps(evidence: CalibrationEvidence?): JsonObject = JsonObject().apply {
        addProperty("downlinkRecognized", evidence?.downlinkRecognized ?: false)
        addProperty("peerHeardSpeech", evidence?.peerHeardSpeech ?: false)
        addProperty("microphoneIsolated", evidence?.microphoneIsolated ?: false)
        addProperty("manualRestoreVerified", evidence?.manualRestoreVerified ?: false)
        addProperty("processFailureVerified", evidence?.processFailureVerified ?: false)
    }

    private fun encodeEnvironmentKey(environment: EnvironmentKey): JsonObject = JsonObject().apply {
        addProperty("apiLevel", environment.apiLevel)
        addProperty("romFingerprint", environment.romFingerprint)
        addProperty("kernelVersion", environment.kernelVersion)
        addProperty("dialerPackageVersion", environment.dialerPackageVersion)
        addProperty("frameworkVersion", environment.frameworkVersion)
        addProperty("bridgeVersion", environment.bridgeVersion)
        addProperty("audioPolicyDigest", environment.audioPolicyDigest)
        addProperty("simCarrier", environment.simCarrier)
        addProperty("transport", environment.transport)
        addProperty("route", environment.route)
    }

    /** handover：APP 报告人工接管；监督端先撤销所有权再恢复麦克风。 */
    private fun handover(request: RpcRequest): RpcResponse {
        val session = request.sessionId
            ?: return failure(request, "payload_invalid", "handover must carry a session id")
        val outcome = runBlocking { supervision.onManualTakeover(session) }
        // 所有权已撤销（确认交还/恢复失败）就不能再保留 APP 的音频中继：关连接、丢队列。
        if (outcome is TakeoverOutcome.Confirmed || outcome is TakeoverOutcome.RestoreFailed) {
            audioPcm?.release(session)
        }
        val json = JsonObject()
        json.addProperty("sessionMatched", outcome != TakeoverOutcome.NotOwned)
        when (outcome) {
            TakeoverOutcome.Confirmed -> json.addProperty("outcome", "confirmed")
            TakeoverOutcome.NotOwned -> json.addProperty("outcome", "not_owned")
            is TakeoverOutcome.RevokeFailed -> {
                json.addProperty("outcome", "revoke_failed")
                json.addProperty("reason", outcome.reason)
            }
            is TakeoverOutcome.RestoreFailed -> {
                json.addProperty("outcome", "restore_failed")
                json.addProperty("reason", outcome.reason)
            }
        }
        return ok(request, json.toString())
    }

    /**
     * session.events：APP 轮询会话事件流（Task 7C）。
     *
     * 只读、无会话参数、单次最多 [SessionEventBus.MAX_BATCH] 条。负载
     * `{"sinceSeq":N}`（可省略/空负载 = 0）；游标超前返回机器码
     * [ERROR_CURSOR_AHEAD]，其余一律是可用批次（陈旧游标不报错）。
     */
    private fun sessionEventsPoll(request: RpcRequest): RpcResponse {
        val sinceSeq = when (val decoded = decodeSinceSeq(request.payload)) {
            is SinceSeqDecode.Cursor -> decoded.sinceSeq
            SinceSeqDecode.Invalid -> return failure(
                request,
                ERROR_PAYLOAD_INVALID,
                "session.events payload must be {\"sinceSeq\":<long>}",
            )
        }
        return when (val poll = sessionEvents.poll(sinceSeq)) {
            is SessionEventBus.Poll.Available -> ok(request, SessionEventBus.encode(poll.batch))
            SessionEventBus.Poll.CursorAhead -> failure(
                request,
                ERROR_CURSOR_AHEAD,
                "sinceSeq is ahead of the newest recorded event",
            )
        }
    }

    /**
     * 解析 `{"sinceSeq":N}`：空负载/`{}` = 0；除此之外键集合必须是 `{"sinceSeq"}`，
     * 值必须是非负**整数**（小数、字符串、null、负数、未知键都整体拒绝）。
     */
    private fun decodeSinceSeq(payload: String): SinceSeqDecode {
        if (payload.isEmpty()) return SinceSeqDecode.Cursor(0L)
        if (payload.length > MAX_SESSION_EVENTS_PAYLOAD_CHARS) return SinceSeqDecode.Invalid
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull()
            ?: return SinceSeqDecode.Invalid
        if (!parsed.isJsonObject) return SinceSeqDecode.Invalid
        val json = parsed.asJsonObject
        if (!json.keySet().all { it == SESSION_EVENTS_CURSOR_KEY }) return SinceSeqDecode.Invalid
        if (!json.has(SESSION_EVENTS_CURSOR_KEY)) return SinceSeqDecode.Cursor(0L)
        val element = json.get(SESSION_EVENTS_CURSOR_KEY)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return SinceSeqDecode.Invalid
        val asDouble = element.asDouble
        val asLong = element.asLong
        if (!asDouble.isFinite() || asDouble != asLong.toDouble()) return SinceSeqDecode.Invalid
        if (asLong < 0L) return SinceSeqDecode.Invalid
        return SinceSeqDecode.Cursor(asLong)
    }

    /** calibration.record 的结构化解码结果；null/异常一律整体拒绝。 */
    private data class CalibrationRecordPayload(
        val steps: Map<CalibrationStep, Boolean>,
        val expectedEnvironment: EnvironmentKey?,
        /** 可选的 root 操作员背书；null 表示没有提供，必须由桥接观测补齐环境。 */
        val attestation: OperatorAttestation?,
    )

    /**
     * 解析 calibration.record 负载：只允许 steps（五项、全部布尔）、可选的
     * expectedEnvironment（十个环境字段）与可选的 operatorAttestation
     * （transport/route + manualTestCompleted 必填，frameworkVersion/simCarrier 可选）。
     * 多键/缺键/错误类型/词表外取值/非法自由文本都整体拒绝。
     */
    private fun decodeCalibrationRecord(payload: String): CalibrationRecordPayload? {
        if (payload.isEmpty() || payload.length > MAX_CALIBRATION_PAYLOAD_CHARS) return null
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (!json.keySet().all {
                it == CALIBRATION_STEPS_KEY ||
                    it == CALIBRATION_ENVIRONMENT_FIELD ||
                    it == CALIBRATION_ATTESTATION_FIELD
            }
        ) {
            return null
        }
        val stepsJson = json.get(CALIBRATION_STEPS_KEY)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        if (stepsJson.keySet() != CALIBRATION_STEP_KEYS.keys) return null
        val steps = LinkedHashMap<CalibrationStep, Boolean>()
        for ((key, step) in CALIBRATION_STEP_KEYS) {
            val element = stepsJson.get(key) ?: return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
            steps[step] = element.asBoolean
        }
        val expectedElement = json.get(CALIBRATION_ENVIRONMENT_FIELD)
        val expected = if (expectedElement == null) {
            null
        } else {
            if (!expectedElement.isJsonObject) return null
            decodeEnvironmentKey(expectedElement.asJsonObject) ?: return null
        }
        val attestationElement = json.get(CALIBRATION_ATTESTATION_FIELD)
        val attestation = if (attestationElement == null) {
            null
        } else {
            decodeOperatorAttestation(attestationElement) ?: return null
        }
        return CalibrationRecordPayload(steps, expected, attestation)
    }

    /**
     * 操作员背书（Task 11E / 11H1）：transport/route 必填且必须是固定词表；
     * manualTestCompleted 必须是 JSON true；frameworkVersion/simCarrier 可选，出现时
     * 必须是 [isAttestableFreeFormFact] 认可的有界字符串（空/超长/控制字符/UNKNOWN 都拒绝）。
     * 其余一切形状都 payload_invalid。
     */
    private fun decodeOperatorAttestation(element: JsonElement): OperatorAttestation? {
        if (!element.isJsonObject) return null
        val json = element.asJsonObject
        if (!CALIBRATION_ATTESTATION_KEYS.containsAll(json.keySet())) return null
        if (!json.keySet().containsAll(CALIBRATION_ATTESTATION_REQUIRED_KEYS)) return null
        val transport = calibrationAttestationString(json, "transport") ?: return null
        val route = calibrationAttestationString(json, "route") ?: return null
        if (transport !in ATTESTABLE_TRANSPORTS || route !in ATTESTABLE_ROUTES) return null
        val manual = json.get(CALIBRATION_ATTESTATION_MANUAL_TEST)
        if (manual == null || !manual.isJsonPrimitive || !manual.asJsonPrimitive.isBoolean) return null
        if (!manual.asBoolean) return null
        val frameworkVersion = optionalAttestationFact(json, CALIBRATION_ATTESTATION_FRAMEWORK) ?: return null
        val simCarrier = optionalAttestationFact(json, CALIBRATION_ATTESTATION_SIM_CARRIER) ?: return null
        return OperatorAttestation(
            transport = transport,
            route = route,
            frameworkVersion = frameworkVersion.value,
            simCarrier = simCarrier.value,
        )
    }

    /**
     * 可选背书字段的三态解码：字段缺失 = 未背书（value=null）；字段存在且合法 =
     * value；字段存在但类型/格式不合法 = null（调用方整体拒绝）。
     */
    private data class OptionalAttestationFact(val value: String?)

    private fun optionalAttestationFact(json: JsonObject, name: String): OptionalAttestationFact? {
        val element = json.get(name) ?: return OptionalAttestationFact(value = null)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val value = element.asString
        if (!isAttestableFreeFormFact(value)) return null
        return OptionalAttestationFact(value = value)
    }

    private fun calibrationAttestationString(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val value = element.asString
        if (value.isEmpty() || value.length > MAX_CALIBRATION_ENV_CHARS) return null
        return value
    }

    /** 只接受恰好十个环境字段；字符串有长度上限，API 等级必须是整数。 */
    private fun decodeEnvironmentKey(json: JsonObject): EnvironmentKey? {
        if (json.keySet() != CALIBRATION_ENVIRONMENT_KEYS) return null
        val apiLevel = calibrationInt(json, "apiLevel") ?: return null
        return EnvironmentKey(
            apiLevel = apiLevel,
            romFingerprint = calibrationString(json, "romFingerprint") ?: return null,
            kernelVersion = calibrationString(json, "kernelVersion") ?: return null,
            dialerPackageVersion = calibrationString(json, "dialerPackageVersion") ?: return null,
            frameworkVersion = calibrationString(json, "frameworkVersion") ?: return null,
            bridgeVersion = calibrationString(json, "bridgeVersion") ?: return null,
            audioPolicyDigest = calibrationString(json, "audioPolicyDigest") ?: return null,
            simCarrier = calibrationString(json, "simCarrier") ?: return null,
            transport = calibrationString(json, "transport") ?: return null,
            route = calibrationString(json, "route") ?: return null,
        )
    }

    private fun calibrationString(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val value = element.asString
        if (value.length > MAX_CALIBRATION_ENV_CHARS) return null
        return value
    }

    private fun calibrationInt(json: JsonObject, name: String): Int? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val value = element.asDouble
        if (!value.isFinite() || value != Math.floor(value)) return null
        if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
        return value.toInt()
    }

    /** "结果归属"拒绝码：Answer 与 Hangup 各自机器可读，7B 可据此区分重试策略。 */
    private fun inFlightCode(command: CallCommand): String = when (command) {
        CallCommand.Answer -> ERROR_ANSWER_IN_FLIGHT
        CallCommand.Hangup -> ERROR_COMMAND_IN_FLIGHT
    }

    private fun encodeCommandResult(result: CommandResult): String {
        val json = JsonObject()
        when (result) {
            is CommandResult.Accepted -> json.addProperty("outcome", "accepted")
            is CommandResult.Rejected -> {
                json.addProperty("outcome", "rejected")
                json.addProperty("reason", result.reason.name)
            }
            is CommandResult.Failed -> {
                json.addProperty("outcome", "failed")
                json.addProperty("code", result.code)
                json.addProperty("message", result.message.take(MAX_ERROR_TEXT_CHARS))
            }
        }
        return json.toString()
    }

    /**
     * hook.snapshot/hook.heartbeat 的响应：带回该会话的待执行命令行（可能为空）。
     * 命令不在响应里执行，Hook 收到后仍要按自己的会话/代次/状态裁决。
     */
    private fun hookCommandLines(request: RpcRequest): RpcResponse {
        val session = request.sessionId
            ?: return failure(request, "payload_invalid", "hook calls must carry a session id")
        if (request.method == RolePolicy.METHOD_HOOK_SNAPSHOT) {
            feedSnapshot(session, request.payload)
        }
        return ok(request, hookCommands.drainCommandLines(session))
    }

    private fun feedSnapshot(session: SessionId, payload: String) {
        val decoded = HookSnapshotDecoder.decode(session, payload) ?: return
        // 事件先入队，再做监督迁移：seq 顺序就是真实事实的到达顺序（无补发、无重排）。
        // 只记录已解码的真实快照——伪造/损坏/别人的快照在 decode 处就被丢弃。
        sessionEvents.recordSnapshot(session, decoded)
        supervision.recordSnapshotState(session, decoded.state)
        if (decoded.handbackReason != null) {
            val outcome = runBlocking { supervision.onManualTakeover(session) }
            // 监督端的上交还结果也是 APP 必须看见的事实：撤销/恢复失败绝不能显示成成功。
            sessionEvents.recordHandback(
                session,
                decoded.state,
                decoded.handbackReason,
                handbackOutcomeCode(outcome),
            )
            // 监督端已撤销所有权（确认交还/恢复失败）：APP 的音频绑定必须立刻释放。
            if (outcome is TakeoverOutcome.Confirmed || outcome is TakeoverOutcome.RestoreFailed) {
                audioPcm?.release(session)
            }
            return
        }
        when (decoded.state) {
            CallState.ACTIVE -> runBlocking { supervision.onCallActive(session) }
            CallState.DISCONNECTED -> {
                runBlocking { supervision.onCallDisconnected(session) }
                // 断开确认即"非所有权"：不需要等下一次 audio.channel/轮询才回收绑定。
                sessionEvents.recordDisconnect(session, decoded.state)
                audioPcm?.release(session)
            }
            else -> Unit
        }
    }

    private fun handbackOutcomeCode(outcome: TakeoverOutcome): String = when (outcome) {
        TakeoverOutcome.Confirmed -> SessionEventBus.OUTCOME_HANDBACK_CONFIRMED
        TakeoverOutcome.NotOwned -> SessionEventBus.OUTCOME_HANDBACK_NOT_OWNED
        is TakeoverOutcome.RevokeFailed -> SessionEventBus.OUTCOME_HANDBACK_REVOKE_FAILED
        is TakeoverOutcome.RestoreFailed -> SessionEventBus.OUTCOME_HANDBACK_RESTORE_FAILED
    }

    /** call.command.result：解析并暂存真实结果，供监督端与 APP 侧 CallControl 取走。 */
    private fun hookCommandResult(request: RpcRequest): RpcResponse {
        val session = request.sessionId
            ?: return failure(request, "payload_invalid", "hook calls must carry a session id")
        // 唯一解析点（路由器固定字段校验）→ 唯一结果通路（监督端收件箱）。
        // 没有第二个结果缓冲区：超过任意条数也不会开始拒绝真实结果（评审 I4）。
        val outcome = hookCommands.parseResult(session, request.payload)
            ?: return failure(request, "payload_invalid", "command result payload is invalid")
        supervision.recordCommandResult(session, outcome.command, outcome.result)
        return ok(request, "")
    }

    private fun statusPayload(): String {
        val redacted = store.read()
        val pairing = runCatching { store.readPairing() }.getOrNull()
        val (active, rejected) = connectionStats()
        val state = supervision.status()
        val json = JsonObject()
        json.addProperty("bridgeVersion", BridgeInfo.VERSION)
        json.addProperty("protocolVersion", PROTOCOL_VERSION)
        json.addProperty("revision", redacted.config.revision)
        json.addProperty("enabled", redacted.config.enabled)
        json.addProperty("paired", pairing != null)
        // uptimeMs 只作展示（WebUI 用）；重启检测只认每进程随机的 processId。
        json.addProperty("uptimeMs", System.currentTimeMillis() - startedAtMs)
        json.addProperty("processId", processId)
        json.addProperty("activeConnections", active)
        json.addProperty("rejectedConnections", rejected)
        json.addProperty("supervisionPhase", state?.phase)
        json.addProperty("supervisionOwned", state?.owned ?: false)
        json.addProperty("microphoneIsolated", state?.microphoneIsolated ?: false)
        json.addProperty("supervisionBlocked", state?.blockedReason)
        json.addProperty("supervisionLastFault", state?.lastFault)
        // Task 11D2a：优雅关闭的机器码（null = 未发生/干净关闭）；关闭响应同样携带。
        json.addProperty("shutdownCode", shutdownCode())
        // 阶段 B 概览实时字段：确定为 boolean，无法可靠确定时写字符串 "unknown"。
        addLiveStatus(json, "hookConnected", state?.hookConnected)
        addLiveStatus(json, "dialerDefaultRole", inspectDialerDefaultRole())
        // Task 11E / C1：APP 侧 calibration.read 没有生产消费者；内嵌同一份校准视图
        // （与 calibration.read 完全一致，含 operatorAttestation 背书标记）供已有 UI 探针使用。
        json.add("calibration", calibrationPayload())
        return json.toString()
    }

    /** 探测失败一律当作未知：status 不能因为一次异常而消失或编造结论。 */
    private fun inspectDialerDefaultRole(): Boolean? = try {
        dialerDefaultRole.invoke().definite
    } catch (t: Throwable) {
        DialerDefaultRole.UNKNOWN.definite
    }

    /**
     * status 的三态实时值：true/false 写布尔值，null 写字符串 "unknown"。
     * WebUI 只按真实布尔渲染，缺失/未知都显示“未知（后端未提供/无法确定）”。
     */
    private fun addLiveStatus(json: JsonObject, key: String, value: Boolean?) {
        if (value == null) {
            json.addProperty(key, UNKNOWN_STATUS_VALUE)
        } else {
            json.addProperty(key, value)
        }
    }

    /** 只有 APP 角色能到达这里（RolePolicy 固定表）；HOOK/CLI 都无法读取密钥。 */
    private fun secretRead(request: RpcRequest): RpcResponse {
        val kind = decodeSecretKind(request.payload)
            ?: return failure(request, "payload_invalid", "kind must be one of ASR, LLM, TTS")
        val value = store.readSecret(kind)
        val json = JsonObject()
        json.addProperty("kind", kind.name)
        if (value == null) {
            json.add("value", com.google.gson.JsonNull.INSTANCE)
        } else {
            json.addProperty("value", value)
        }
        return ok(request, json.toString())
    }

    private fun configWrite(request: RpcRequest): RpcResponse {
        val write = RpcCodec.decodeConfigWrite(request.payload)
            ?: return failure(request, "payload_invalid", "config patch payload is invalid")
        return try {
            ok(request, RpcCodec.encodeRedacted(store.update(write.expectedRevision, write.patch)))
        } catch (error: ConfigStoreException) {
            // 保留既有机器码（冲突/字段非法/磁盘失败）。
            throw error
        } catch (t: Throwable) {
            // 隐藏 API/反射/校验依赖抛出的 Throwable（含 Error）绝不能让保存变成
            // internal_error：只回固定机器码，日志只含类名，不含负载/路径。
            System.err.println(
                "config_write_failed code=" + ERROR_CONFIG_VALIDATION_UNAVAILABLE +
                    " cause=" + t.javaClass.simpleName,
            )
            failure(
                request,
                ERROR_CONFIG_VALIDATION_UNAVAILABLE,
                "config validation could not be completed",
            )
        }
    }

    /**
     * 配对只接受 CLI_ROOT（RolePolicy 已保证）。签名摘要由本进程重新解析，
     * 不接受消息里自带的签名或角色，普通请求不能自注册信任。
     */
    private fun pair(request: RpcRequest): RpcResponse {
        val packageName = decodePairPackage(request.payload)
            ?: return failure(request, "payload_invalid", "packageName is invalid")
        val appSignature = packages.signatureSha256(packageName)
            ?: return failure(request, "pairing_package_unknown", "package signature could not be resolved")
        val appUid = packages.uidForPackage(packageName)
            ?: return failure(request, "pairing_package_unknown", "package uid could not be resolved")
        val dialerSignature = packages.signatureSha256(PeerVerifier.DIALER_PACKAGE)
            ?: return failure(request, "pairing_dialer_unknown", "dialer signature could not be resolved")
        val dialerUid = packages.uidForPackage(PeerVerifier.DIALER_PACKAGE)
            ?: return failure(request, "pairing_dialer_unknown", "dialer uid could not be resolved")
        val record = store.pair(
            appPackage = packageName,
            appUid = appUid,
            appSignatureSha256 = appSignature,
            appVersionName = packages.versionName(packageName) ?: ConfigStore.UNKNOWN_VERSION,
            dialerPackage = PeerVerifier.DIALER_PACKAGE,
            dialerUid = dialerUid,
            dialerSignatureSha256 = dialerSignature,
            dialerVersionName = packages.versionName(PeerVerifier.DIALER_PACKAGE) ?: ConfigStore.UNKNOWN_VERSION,
        )
        val json = JsonObject()
        json.addProperty("appPackage", record.appPackage)
        json.addProperty("appUid", record.appUid)
        json.addProperty("appSignatureSha256", record.appSignatureSha256)
        json.addProperty("appVersionName", record.appVersionName)
        json.addProperty("dialerPackage", record.dialerPackage)
        json.addProperty("dialerUid", record.dialerUid)
        json.addProperty("dialerSignatureSha256", record.dialerSignatureSha256)
        json.addProperty("dialerVersionName", record.dialerVersionName)
        json.addProperty("pairedAtMs", record.pairedAtMs)
        return ok(request, json.toString())
    }

    private fun decodeSecretKind(payload: String): EndpointKind? {
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (json.keySet() != setOf("kind")) return null
        val kindElement = json.get("kind") ?: return null
        if (!kindElement.isJsonPrimitive || !kindElement.asJsonPrimitive.isString) return null
        val name = kindElement.asString
        return EndpointKind.entries.firstOrNull { it.name == name }
    }

    private fun decodePairPackage(payload: String): String? {
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        if (json.keySet() != setOf("packageName")) return null
        val element = json.get("packageName") ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val name = element.asString
        if (name.length !in 3..128 || !PACKAGE_PATTERN.matches(name)) return null
        return name
    }

    /**
     * 解析 APP 命令负载：**只**接受单键对象 {"command":"answer"|"hangup"}。
     *
     * 结构不合法（非 JSON、非对象、多键/少键、非字符串）整体拒绝且不入队；结构合法但
     * 动词不在受限表里返回 unsupported_command，同样不入队。没有默认值、没有大小写
     * 兼容、没有别名：线格式越窄，越不可能把未知动作误当成 Answer/Hangup。
     */
    private fun decodeAppCommand(payload: String): AppCommandDecode {
        if (payload.isEmpty() || payload.length > MAX_APP_COMMAND_CHARS) return AppCommandDecode.Invalid
        val parsed = runCatching { JsonParser.parseString(payload) }.getOrNull()
            ?: return AppCommandDecode.Invalid
        if (!parsed.isJsonObject) return AppCommandDecode.Invalid
        val json = parsed.asJsonObject
        if (json.keySet() != APP_COMMAND_KEYS) return AppCommandDecode.Invalid
        val element = json.get("command") ?: return AppCommandDecode.Invalid
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return AppCommandDecode.Invalid
        return when (element.asString) {
            "answer" -> AppCommandDecode.Supported(CallCommand.Answer)
            "hangup" -> AppCommandDecode.Supported(CallCommand.Hangup)
            else -> AppCommandDecode.Unsupported
        }
    }

    private fun ok(request: RpcRequest, payload: String): RpcResponse =
        RpcResponse(id = request.id, payload = payload)

    private fun failure(request: RpcRequest, code: String, message: String): RpcResponse =
        RpcResponse(
            id = request.id,
            error = RpcError(code, message.take(ControlServer.MAX_ERROR_MESSAGE_CHARS)),
        )

    /** session.events 游标的解析结果：合法（缺省 0）或整体非法。 */
    private sealed interface SinceSeqDecode {
        data class Cursor(val sinceSeq: Long) : SinceSeqDecode

        data object Invalid : SinceSeqDecode
    }

    /** session.command 负载的解析结果：合法受限命令、结构合法但不受支持、结构非法。 */
    private sealed interface AppCommandDecode {
        data class Supported(val command: CallCommand) : AppCommandDecode

        data object Unsupported : AppCommandDecode

        data object Invalid : AppCommandDecode
    }

    internal companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        const val MAX_SESSION_REQUEST_CHARS: Int = 1024
        const val MAX_ERROR_TEXT_CHARS: Int = 200
        const val MAX_APP_COMMAND_CHARS: Int = 128

        /** config-set 的反射/校验依赖抛 Throwable 时的固定机器码（绝不回 internal_error）。 */
        const val ERROR_CONFIG_VALIDATION_UNAVAILABLE: String = "config_validation_unavailable"

        /** APP session.command 的唯一线格式字段表：多一个键就是 payload_invalid。 */
        val APP_COMMAND_KEYS = setOf("command")

        /** APP session.events 的唯一负载键；空负载/`{}` 等价于 sinceSeq=0。 */
        const val SESSION_EVENTS_CURSOR_KEY: String = "sinceSeq"

        /** 事件游标负载的长度上限：只可能是 {"sinceSeq":<long>}。 */
        const val MAX_SESSION_EVENTS_PAYLOAD_CHARS: Int = 128

        /**
         * 游标晚于本进程已记录的最大 seq：调用方状态来自别的进程/已损坏。必须与
         * “暂时没有新事件”区分开（后者是 200 + 空 events 批次）。
         */
        const val ERROR_CURSOR_AHEAD: String = "cursor_ahead"

        /** APP 未携带会话 ID 或会话 ID 不是可上线 token 时的机器可读错误。 */
        const val ERROR_SESSION_REQUIRED: String = "session_required"

        /** 负载不是固定字段表/受限命令时的机器可读错误（与既有 RPC 错误码同名）。 */
        const val ERROR_PAYLOAD_INVALID: String = "payload_invalid"

        /**
         * 监督端正在准备/等待同一条 Answer 的结果：APP 的 answer 被拒绝（不入队）。
         * 这是"结果归属"仲裁，不是超时，绝不能把监督端的结果让给 APP。
         */
        const val ERROR_ANSWER_IN_FLIGHT: String = "answer_in_flight"

        /** 同一 (session, command) 已有另一个 APP 等待者：有界拒绝，不入队。 */
        const val ERROR_COMMAND_IN_FLIGHT: String = "command_in_flight"

        /** 命令结构合法，但不在 APP 允许的受限命令表里。 */
        const val ERROR_UNSUPPORTED_COMMAND: String = "unsupported_command"

        /** 命令没能进入 HOOK 队列（会话非法或队列饱和）：有界失败，绝不假装已下发。 */
        const val ERROR_COMMAND_NOT_QUEUED: String = "command_not_queued"

        /** 校准存储未接线（默认构造点）：读取/记录都明确失败，绝不假装已验证。 */
        const val ERROR_CALIBRATION_UNAVAILABLE: String = "calibration_unavailable"

        /** 当前环境含 UNKNOWN/空字段：不能作为可复现的校准绑定。 */
        const val ERROR_CALIBRATION_ENV_UNKNOWN: String = "calibration_environment_unknown"

        /** 请求携带的预期环境与当前设备环境不一致（陈旧/别的设备）。 */
        const val ERROR_CALIBRATION_ENV_MISMATCH: String = "calibration_environment_mismatch"

        const val MAX_CALIBRATION_PAYLOAD_CHARS: Int = 8 * 1024

        const val MAX_CALIBRATION_ENV_CHARS: Int = 512

        const val CALIBRATION_STEPS_KEY: String = "steps"

        const val CALIBRATION_ENVIRONMENT_FIELD: String = "expectedEnvironment"

        /** root 操作员背书（C1）：桥接从不观测的 transport/route + 必须为 true 的人工测试标记。 */
        const val CALIBRATION_ATTESTATION_FIELD: String = "operatorAttestation"

        const val CALIBRATION_ATTESTATION_MANUAL_TEST: String = "manualTestCompleted"

        /** 背书可选的自由文本字段（有界格式校验；观测值已知时永远不生效）。 */
        const val CALIBRATION_ATTESTATION_FRAMEWORK: String = "frameworkVersion"
        const val CALIBRATION_ATTESTATION_SIM_CARRIER: String = "simCarrier"

        /** 新背书词表允许的键；transport/route/manualTestCompleted 仍为必填。 */
        val CALIBRATION_ATTESTATION_REQUIRED_KEYS: Set<String> =
            setOf("transport", "route", CALIBRATION_ATTESTATION_MANUAL_TEST)

        val CALIBRATION_ATTESTATION_KEYS: Set<String> = CALIBRATION_ATTESTATION_REQUIRED_KEYS + setOf(
            CALIBRATION_ATTESTATION_FRAMEWORK,
            CALIBRATION_ATTESTATION_SIM_CARRIER,
        )

        /** 自由文本背书值的长度上限（与诊断层的观测上限对齐）。 */
        const val MAX_ATTESTED_FACT_CHARS: Int = 128

        /** 线格式步骤名 → 存储步骤；顺序固定，写入后得到完整证据。 */
        val CALIBRATION_STEP_KEYS: Map<String, CalibrationStep> = linkedMapOf(
            "downlinkRecognized" to CalibrationStep.DOWNLINK_RECOGNIZED,
            "peerHeardSpeech" to CalibrationStep.PEER_HEARD_SPEECH,
            "microphoneIsolated" to CalibrationStep.MICROPHONE_ISOLATED,
            "manualRestoreVerified" to CalibrationStep.MANUAL_RESTORE,
            "processFailureVerified" to CalibrationStep.PROCESS_FAILURE,
        )

        val CALIBRATION_ENVIRONMENT_KEYS: Set<String> = setOf(
            "apiLevel",
            "romFingerprint",
            "kernelVersion",
            "dialerPackageVersion",
            "frameworkVersion",
            "bridgeVersion",
            "audioPolicyDigest",
            "simCarrier",
            "transport",
            "route",
        )

        /** 4000 ms 内没有 HOOK 回报结果：结果不明确，fail closed。 */
        const val ERROR_COMMAND_TIMEOUT: String = "command_timeout"

        /**
         * APP 等待 HOOK 命令结果的硬上限，与监督端的
         * [HookCommandCallControl.DEFAULT_TIMEOUT_MS] 同源（4000 ms）。
         *
         * Hook 只在它的下一次心跳响应里取走命令，心跳周期 2000 ms；4000 ms 既覆盖
         * 最坏取命令延迟，又不超过 10000 ms 人工接管窗口的一半。
         */
        const val APP_COMMAND_TIMEOUT_MS: Long = HookCommandCallControl.DEFAULT_TIMEOUT_MS

        /** 无法在特权进程可靠确定时的显式未知值（不是布尔 false）。 */
        const val UNKNOWN_STATUS_VALUE: String = "unknown"
    }
}

/** 机器可读错误 JSON（stdout/stderr 都用同一形状，绝不含密钥）。 */
internal fun machineErrorJson(code: String, message: String): String {
    val error = JsonObject()
    error.addProperty("code", code)
    error.addProperty("message", message)
    val root = JsonObject()
    root.add("error", error)
    return root.toString()
}


// ---------------------------------------------------------------- 校准环境/背书（Task 11E / C1）

/**
 * root 操作员为桥接观测不到的事实背书（Task 11E / 11H1）：
 * - transport/route 用固定词表（[ATTESTABLE_TRANSPORTS]/[ATTESTABLE_ROUTES]）；
 * - frameworkVersion/simCarrier 用有界校验的自由文本（[isAttestableFreeFormFact]）；
 * - 任何字段都只在观测值为 UNKNOWN 时生效，观测到的已知值永远优先。
 */
internal data class OperatorAttestation(
    val transport: String,
    val route: String,
    val frameworkVersion: String? = null,
    val simCarrier: String? = null,
)

/** 校准视图里被标记为 operator_attested 的事实。 */
internal data class AttestedFacts(
    val operatorAttested: Boolean,
    val transport: String?,
    val route: String?,
    val frameworkVersion: String? = null,
    val simCarrier: String? = null,
) {
    companion object {
        val NONE: AttestedFacts = AttestedFacts(
            operatorAttested = false,
            transport = null,
            route = null,
            frameworkVersion = null,
            simCarrier = null,
        )
    }
}

/** 允许的承载词表；UNKNOWN 合法，但无法满足 CalibrationPolicy 的完整性要求（继续 fail closed）。 */
internal val ATTESTABLE_TRANSPORTS: Set<String> = setOf("CELLULAR", "VOWIFI", PlatformFacts.UNKNOWN)

/** 允许的音频路由词表；UNKNOWN 同上。 */
internal val ATTESTABLE_ROUTES: Set<String> =
    setOf("EARPIECE", "SPEAKER", "HEADSET", "BLUETOOTH", PlatformFacts.UNKNOWN)

/**
 * 背书只填补仍为 UNKNOWN 的字段：观测值是已知事实时永远以观测值为准，背书不能覆盖。
 * transport/route 桥接从不观测，始终依赖背书；frameworkVersion/simCarrier 有观测源，
 * 只在观测不到时采用背书（Task 11H1 / 11J）。
 */
internal fun applyAttestation(observed: EnvironmentKey, attestation: OperatorAttestation?): EnvironmentKey {
    if (attestation == null) return observed
    return observed.copy(
        transport = observed.transport.attestedWhenUnknown(attestation.transport),
        route = observed.route.attestedWhenUnknown(attestation.route),
        frameworkVersion = observed.frameworkVersion.attestedWhenUnknown(attestation.frameworkVersion),
        simCarrier = observed.simCarrier.attestedWhenUnknown(attestation.simCarrier),
    )
}

private fun String.attestedWhenUnknown(attested: String?): String =
    if (this == PlatformFacts.UNKNOWN && attested != null) attested else this

/**
 * 证据环境是否属于当前观测设备：除四个可背书字段（transport/route/frameworkVersion/
 * simCarrier）外的字段必须完全一致；观测不到的可背书字段允许证据使用背书值，
 * 桥接能观测到的字段则必须相等（Task 11H1：框架版本/SIM 运营商与承载/路由同一规则）。
 */
internal fun environmentMatchesObserved(observed: EnvironmentKey, evidence: EnvironmentKey): Boolean {
    if (observed.apiLevel != evidence.apiLevel) return false
    if (observed.romFingerprint != evidence.romFingerprint) return false
    if (observed.kernelVersion != evidence.kernelVersion) return false
    if (observed.dialerPackageVersion != evidence.dialerPackageVersion) return false
    if (observed.bridgeVersion != evidence.bridgeVersion) return false
    if (observed.audioPolicyDigest != evidence.audioPolicyDigest) return false
    if (!observed.frameworkVersion.matchesWhenObserved(evidence.frameworkVersion)) return false
    if (!observed.simCarrier.matchesWhenObserved(evidence.simCarrier)) return false
    if (!observed.transport.matchesWhenObserved(evidence.transport)) return false
    if (!observed.route.matchesWhenObserved(evidence.route)) return false
    return true
}

private fun String.matchesWhenObserved(evidence: String): Boolean =
    this == PlatformFacts.UNKNOWN || this == evidence

/** 判定环境：证据与观测设备匹配时采用证据环境（含背书），否则只用观测事实。 */
internal fun effectiveEnvironment(
    observed: EnvironmentKey,
    evidenceEnvironment: EnvironmentKey?,
): EnvironmentKey =
    if (evidenceEnvironment != null && environmentMatchesObserved(observed, evidenceEnvironment)) {
        evidenceEnvironment
    } else {
        observed
    }

/**
 * 视图里的背书标记：只有“观测不到 + 证据值是合法已知值”才算 operator_attested。
 * Task 11H1 起 frameworkVersion/simCarrier 与 transport/route 同样标记，绝不把
 * 操作员背书呈现成桥接实测。
 */
internal fun attestedFacts(observed: EnvironmentKey, evidenceEnvironment: EnvironmentKey?): AttestedFacts {
    if (evidenceEnvironment == null || !environmentMatchesObserved(observed, evidenceEnvironment)) {
        return AttestedFacts.NONE
    }
    val transport = evidenceEnvironment.transport.takeIf { value ->
        observed.transport == PlatformFacts.UNKNOWN &&
            value in ATTESTABLE_TRANSPORTS &&
            value != PlatformFacts.UNKNOWN
    }
    val route = evidenceEnvironment.route.takeIf { value ->
        observed.route == PlatformFacts.UNKNOWN &&
            value in ATTESTABLE_ROUTES &&
            value != PlatformFacts.UNKNOWN
    }
    val frameworkVersion = evidenceEnvironment.frameworkVersion.takeIf { value ->
        observed.frameworkVersion == PlatformFacts.UNKNOWN && isAttestableFreeFormFact(value)
    }
    val simCarrier = evidenceEnvironment.simCarrier.takeIf { value ->
        observed.simCarrier == PlatformFacts.UNKNOWN && isAttestableFreeFormFact(value)
    }
    return AttestedFacts(
        operatorAttested = transport != null || route != null || frameworkVersion != null || simCarrier != null,
        transport = transport,
        route = route,
        frameworkVersion = frameworkVersion,
        simCarrier = simCarrier,
    )
}

/**
 * 自由文本背书值的有界校验（Task 11H1）：非空、≤ [ControlDispatcher.MAX_ATTESTED_FACT_CHARS]、
 * 前后无空白、无控制字符、且不是显式 UNKNOWN。用于 frameworkVersion/simCarrier，
 * 也用于把持久证据里已有的值标记成 operator_attested 前的复核。
 */
internal fun isAttestableFreeFormFact(value: String): Boolean {
    if (value.isEmpty() || value.length > ControlDispatcher.MAX_ATTESTED_FACT_CHARS) return false
    if (value != value.trim()) return false
    if (value == PlatformFacts.UNKNOWN) return false
    if (value.any { it.isISOControl() }) return false
    return true
}

/**
 * 自动接听授权（[CalibrationPolicy] 本身不变）：判定环境 = 观测环境 + root 背书；
 * 存储读取/解析失败一律 fail closed。
 */
internal fun isCalibrationAuthorizedFor(store: CalibrationStore, observed: EnvironmentKey): Boolean = try {
    val evidence = store.load()
    if (evidence == null) {
        false
    } else {
        CalibrationPolicy.canAutoAnswer(effectiveEnvironment(observed, evidence.environment), evidence)
    }
} catch (t: CalibrationStoreException) {
    false
}


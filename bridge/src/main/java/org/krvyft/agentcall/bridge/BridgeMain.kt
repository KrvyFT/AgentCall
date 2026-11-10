package org.krvyft.agentcall.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
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
import org.krvyft.agentcall.bridge.config.CalibrationStore
import org.krvyft.agentcall.bridge.config.ConfigErrorCode
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.config.ConfigStoreException
import org.krvyft.agentcall.bridge.diagnostics.AndroidDialerDefaultRoleProbe
import org.krvyft.agentcall.bridge.diagnostics.AndroidPlatformFacts
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.DialerDefaultRole
import org.krvyft.agentcall.bridge.diagnostics.ReflectiveAudioApiProbe
import org.krvyft.agentcall.bridge.ipc.AndroidPackageIdentitySource
import org.krvyft.agentcall.bridge.ipc.ControlServer
import org.krvyft.agentcall.bridge.ipc.HookCommandRouter
import org.krvyft.agentcall.bridge.ipc.PackageIdentitySource
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
import org.krvyft.agentcall.bridge.supervision.SnapshotCallStateProbe
import org.krvyft.agentcall.bridge.supervision.SupervisionGraph
import org.krvyft.agentcall.bridge.supervision.SupervisionPlatform
import org.krvyft.agentcall.bridge.supervision.SupervisionStateStore
import org.krvyft.agentcall.bridge.supervision.Supervisor
import org.krvyft.agentcall.bridge.supervision.SupervisorProductionGraph
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.bridge.supervision.SupervisorRpcPort
import org.krvyft.agentcall.bridge.supervision.TakeoverOutcome
import org.krvyft.agentcall.bridge.supervision.TerminationPermission
import org.krvyft.agentcall.bridge.supervision.UnverifiedCallIdentityVerifier
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
 * 只做四件事：解析配置目录、取系统 PackageManager、启动 [ControlServer] 与监督角色、
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

        // 没有系统 PackageManager 就无法核验包名/签名：必须失败退出，而不是信任消息自述。
        val packageManager = RootFramework.packageManager()
        if (packageManager == null) {
            System.err.println(
                machineErrorJson("package_manager_unavailable", "system PackageManager is unavailable in this process"),
            )
            return 1
        }
        val packages: PackageIdentitySource = AndroidPackageIdentitySource(packageManager)

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
        val dialerDefaultRole = AndroidDialerDefaultRoleProbe(packageManager, RootFramework.roleManager())

        val verifier = PeerVerifier(packages) { runCatching { store.readPairing() }.getOrNull() }
        val probe = DeviceProbe(
            packages = packages,
            platform = AndroidPlatformFacts(),
            audioApiProbe = ReflectiveAudioApiProbe(),
            bridgeVersion = BridgeInfo.VERSION,
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
        val dispatcher = ControlDispatcher(
            store = store,
            packages = packages,
            probe = probe,
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
                    pollRunning.set(false)
                    pollThread.interrupt()
                    runBlocking {
                        supervision.supervisor.shutdown()
                        supervision.supervisor.close()
                    }
                    pcmRelay.close()
                    controlServer.close()
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
            pollRunning.set(false)
            pollThread.interrupt()
            runBlocking {
                supervision.supervisor.shutdown()
                supervision.supervisor.close()
            }
            pcmRelay.close()
            controlServer.close()
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
        val authorization = AutoAnswerAuthorization { calibration.isVerified(environment) }
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
    ): AudioPcmRelay = AudioPcmRelay(
        owner = owner,
        workerLinks = workerLinks,
        clock = clock,
        socketName = pcmSocketName(),
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
    private val packages: PackageIdentitySource,
    private val probe: DeviceProbe,
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
            RolePolicy.METHOD_DIAGNOSE -> ok(request, RpcCodec.encodeCapabilityReport(probe.inspect()))
            RolePolicy.METHOD_SHUTDOWN -> {
                shutdown.countDown()
                ok(request, "{\"stopping\":true}")
            }
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
            else -> failure(request, "method_not_implemented", "method is not in the fixed command table")
        }
    } catch (error: ConfigStoreException) {
        // ConfigStoreException.message 只含公开字段与错误码。
        failure(request, error.code, error.message ?: error.code)
    } catch (t: Exception) {
        // 其它异常的文本可能含路径：只回类名。
        failure(request, "internal_error", t.javaClass.simpleName)
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
        // 阶段 B 概览实时字段：确定为 boolean，无法可靠确定时写字符串 "unknown"。
        addLiveStatus(json, "hookConnected", state?.hookConnected)
        addLiveStatus(json, "dialerDefaultRole", inspectDialerDefaultRole())
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
        return ok(request, RpcCodec.encodeRedacted(store.update(write.expectedRevision, write.patch)))
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

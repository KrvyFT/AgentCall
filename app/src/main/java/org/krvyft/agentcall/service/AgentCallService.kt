package org.krvyft.agentcall.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.ai.ApiKeyProvider
import org.krvyft.agentcall.ai.ConversationEngine
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.SessionEvent
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState
import org.krvyft.agentcall.core.model.TakeoverReason
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.ConversationPort
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.ipc.BridgeClient
import org.krvyft.agentcall.ipc.HandoverOutcome
import org.krvyft.agentcall.ipc.HandoverResult
import org.krvyft.agentcall.ipc.LocalSocketControlTransport
import org.krvyft.agentcall.ipc.LocalSocketPcmLaneTransport

/**
 * APK 服务状态（进程内共享给 Views；只含机器码/会话身份，不含号码或密钥）。
 */
data class AgentCallStatus(
    val serviceRunning: Boolean = false,
    /** 前台服务是否真的按声明类型起来了；false 时永不自动接听。 */
    val foregroundActive: Boolean = false,
    /** 前台类型不可用时的机器码（NOT_READY 原因）。 */
    val foregroundError: String? = null,
    val notificationPermissionGranted: Boolean = false,
    /** 五项就绪事实全部满足；只有 true 时协调器才可能自动接听。 */
    val ready: Boolean = false,
    /** 未就绪的固定机器码列表（顺序稳定）。 */
    val blockers: List<String> = emptyList(),
    /** HOOK 心跳存在性三态。 */
    val hookPresence: HookPresence = HookPresence.UNKNOWN,
    /** Google 电话默认角色；null 表示桥接无法确定。 */
    val dialerDefaultRole: Boolean? = null,
    /** 桥接是否向 APP 提供了 status（当前角色表不允许，恒 false 也可能表示不可达）。 */
    val statusAvailable: Boolean = false,
    /** 最近一次影响安全的错误机器码（触发/音频/撤销失败等）。 */
    val lastError: String? = null,
    /** 当前协调器跟踪的会话；无会话为 null。 */
    val activeSession: SessionId? = null,
    val sessionState: SessionState? = null,
    /** 人工交还结果的机器码；只有 handback_confirmed / handback_not_owned 表示成功。 */
    val lastHandback: String? = null,
)

/**
 * 进程内服务状态总线（UI 与通知共用）。
 *
 * 只保存脱敏事实；不持久化，不写磁盘。服务停止后回到默认值。
 */
object AgentCallRuntime {

    private val state = MutableStateFlow(AgentCallStatus())

    val status: StateFlow<AgentCallStatus> = state.asStateFlow()

    internal fun update(transform: (AgentCallStatus) -> AgentCallStatus) {
        state.update(transform)
    }

    /** 服务启动时清空上一轮的就绪/会话事实（避免重启后沿用旧 READY）。 */
    internal fun resetForStart(notificationPermissionGranted: Boolean) {
        state.value = AgentCallStatus(
            serviceRunning = true,
            notificationPermissionGranted = notificationPermissionGranted,
        )
    }

    internal fun reset() {
        state.value = AgentCallStatus()
    }
}

/**
 * APK 前台服务（Task 7B2，spec §6.2/§8/§11）。
 *
 * ## 前台服务类型
 * 本应用不是默认拨号器，也没有 MANAGE_OWN_CALLS，因此不能使用 phoneCall 类型
 * （spec §11 明确禁止伪用只授予默认拨号器的资格）。这里使用 Android 真实提供的
 * specialUse 类型（FOREGROUND_SERVICE_SPECIAL_USE + manifest 的
 * PROPERTY_SPECIAL_USE_FGS_SUBTYPE）：它不要求任何角色，也不冒充系统通话；代价是
 * 需要 Play 声明（本工程侧载，不涉及）。
 *
 * 若系统拒绝该类型（权限/声明/设备策略），服务不运行：记录
 * foreground_service_type_unavailable、报告 NOT_READY，然后立即 stopSelf()，绝不降级成
 * 没有类型的常驻服务，也绝不自动接听。
 *
 * ## 就绪与自动接听
 * - 服务启动请求本身不是就绪：每次循环都调用 [ServiceBootstrap.evaluate] 重新探测五项事实，
 *   任一缺失就把 ReadyChanged(false) 投给协调器（保留原响铃）；
 * - 当前桥接不向 APP 开放 status/diagnose，也不存在 HOOK→APP 快照通道，因此生产环境
 *   的判定会是 NOT_READY（见 7B2 报告的“未验证/阻塞”）。这是 fail closed，不是假成功。
 *
 * ## 生命周期
 * - [BridgeClient] 与 [SessionCoordinator] 懒创建、在 [onDestroy] 关闭；没有 GlobalScope；
 * - 进程被杀后系统按 START_STICKY 重启：重新创建一切并重新做就绪握手（不会继承旧 READY）；
 * - 通知与协调器 observer 只传机器码/会话身份，不传号码/密钥。
 */
class AgentCallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopping = AtomicBoolean(false)
    private val bootstrapWakeups = Channel<Unit>(Channel.CONFLATED)

    private var callNotification: CallNotification? = null
    private var notificationManager: NotificationManager? = null

    private var bridge: BridgeClient? = null
    private var diagnostics: BridgeDiagnostics? = null
    private var coordinator: SessionCoordinator? = null
    private var bootstrapJob: Job? = null

    @Volatile
    private var foregroundStarted: Boolean = false

    @Volatile
    private var lastConfigRevision: Long = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        val permissionGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        AgentCallRuntime.resetForStart(notificationPermissionGranted = permissionGranted)
        callNotification = CallNotification(this).also { it.ensureChannel() }
        notificationManager = getSystemService(NotificationManager::class.java)
        log("service_created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping.get()) return START_NOT_STICKY
        if (!startInForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                log("service_stop_requested")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TAKEOVER -> handleTakeover(intent)
            ACTION_START, ACTION_RETRY, null -> {
                ensureRuntime()
                // 立即触发一次判定；重启（intent == null）时同样重新握手。
                bootstrapWakeups.trySend(Unit)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        log("service_destroyed")
        bootstrapJob?.cancel()
        bootstrapJob = null
        coordinator?.close()
        coordinator = null
        val client = bridge
        bridge = null
        if (client != null) {
            // close() 只关 PCM lane 与控制传输，不取消外部 scope；收尾时有界同步等待。
            runBlocking {
                withContext(NonCancellable) {
                    withTimeoutOrNull(CLIENT_CLOSE_TIMEOUT_MS) { runCatching { client.close() } }
                }
            }
        }
        diagnostics?.let { runCatching { runBlocking { it.close() } } }
        diagnostics = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
        scope.cancel()
        AgentCallRuntime.reset()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 前台服务

    private fun startInForeground(): Boolean {
        if (foregroundStarted) return true
        val notification = callNotification ?: return false
        val status = AgentCallRuntime.status.value
        return try {
            startForeground(
                CallNotification.NOTIFICATION_ID,
                notification.foreground(status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            foregroundStarted = true
            AgentCallRuntime.update { it.copy(foregroundActive = true, foregroundError = null) }
            true
        } catch (failure: Exception) {
            // 类型不可用（无权/声明不符/设备策略）：不运行、不自动接听。
            log("foreground_start_failed " + failure.javaClass.simpleName)
            AgentCallRuntime.update {
                it.copy(
                    foregroundActive = false,
                    foregroundError = ERROR_FOREGROUND_TYPE,
                    ready = false,
                )
            }
            // 满足 startForegroundService 的契约后再停止：不让系统抛 RemoteServiceException。
            runCatching { startForeground(CallNotification.NOTIFICATION_ID, notification.foreground(status)) }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            false
        }
    }

    // ------------------------------------------------------------------ 运行时装配

    @Synchronized
    private fun ensureRuntime() {
        if (coordinator != null) return
        val client = BridgeClient(
            control = LocalSocketControlTransport(),
            pcm = LocalSocketPcmLaneTransport(),
            scope = scope,
            logger = ::log,
        )
        val diagnosticsClient = BridgeDiagnostics()
        val evidence = CalibrationEvidenceStore(this)
        val probes = ReadinessProbes(
            bridge = BridgeProbe { client.ensureReady() },
            hook = BridgeHookPresenceProbe(diagnosticsClient),
            config = RedactedConfigProbe { client.readRedactedConfig() },
            calibration = BridgeDiagnoseCalibrationProbe(diagnosticsClient, evidence),
            network = AndroidNetworkProbe(this),
        )
        val serviceBootstrap = ServiceBootstrap(probes, logger = ::log)
        val sessionCoordinator = SessionCoordinator(
            config = AgentConfig(),
            calls = client,
            // TTS 供应商采样率（常见 24 kHz）在写入上行前转换为 PCM lane 的 16 kHz。
            audio = ResamplingAudioBridge(client),
            conversation = BridgeConversation(client, ::log),
            clock = MonotonicClock { System.nanoTime() / 1_000_000L },
            revoker = OwnershipRevoker { id -> client.handover(id).toRevokeOutcome() },
            heartbeat = HeartbeatPump { id -> client.heartbeatWhileLive(id) },
            observer = serviceObserver,
            dispatcher = Dispatchers.Default,
        )
        sessionCoordinator.start()
        bridge = client
        diagnostics = diagnosticsClient
        coordinator = sessionCoordinator
        bootstrapJob = scope.launch { bootstrapLoop(serviceBootstrap, diagnosticsClient) }
        log("runtime_created")
    }

    /**
     * 就绪循环：探测 → 广播 READY → 刷新配置/诊断状态 → 等待下一次唤起或固定间隔。
     *
     * 循环永不自称就绪：ready 只来自 [ServiceBootstrap.evaluate]。
     */
    private suspend fun bootstrapLoop(
        serviceBootstrap: ServiceBootstrap,
        diagnosticsClient: BridgeDiagnostics,
    ) {
        while (scope.isActive && !stopping.get()) {
            try {
                evaluateOnce(serviceBootstrap, diagnosticsClient)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                log("bootstrap_failed " + failure.javaClass.simpleName)
                AgentCallRuntime.update { it.copy(ready = false, lastError = "bootstrap_failed") }
                coordinator?.onEvent(SessionEvent.ReadyChanged(false))
            }
            withTimeoutOrNull(READY_RECHECK_INTERVAL_MS) { bootstrapWakeups.receive() }
        }
    }

    private suspend fun evaluateOnce(
        serviceBootstrap: ServiceBootstrap,
        diagnosticsClient: BridgeDiagnostics,
    ) {
        val decision = serviceBootstrap.evaluate()
        AgentCallRuntime.update {
            it.copy(
                ready = decision.ready,
                blockers = decision.codes,
                foregroundActive = foregroundStarted,
            )
        }
        coordinator?.onEvent(SessionEvent.ReadyChanged(decision.ready))
        refreshStatusFacts(diagnosticsClient)
        refreshConfig()
        updateNotification()
    }

    /** 把 status 的只读事实放进 UI 状态；不可用时保持 UNKNOWN，绝不伪造成“在线”。 */
    private suspend fun refreshStatusFacts(diagnosticsClient: BridgeDiagnostics) {
        when (val result = diagnosticsClient.status()) {
            is BridgeStatusResult.Available -> AgentCallRuntime.update {
                it.copy(
                    statusAvailable = true,
                    hookPresence = result.status.hookConnected,
                    dialerDefaultRole = result.status.dialerDefaultRole,
                )
            }
            is BridgeStatusResult.Unavailable -> AgentCallRuntime.update {
                it.copy(
                    statusAvailable = false,
                    hookPresence = HookPresence.UNKNOWN,
                    dialerDefaultRole = null,
                )
            }
        }
    }

    /**
     * 配置热更新：只在 revision 变化时把新配置交给协调器；总开关关闭会立即作用于进行中的通话。
     */
    private suspend fun refreshConfig() {
        val client = bridge ?: return
        val payload = client.readRedactedConfig() ?: return
        val config = parseRedactedAgentConfig(payload) ?: return
        if (config.revision == lastConfigRevision) return
        lastConfigRevision = config.revision
        coordinator?.onConfigChanged(config)
    }

    // ------------------------------------------------------------------ 接管

    /**
     * 通知/页面接管入口：目标会话来自 Intent data（由 [CallNotification] 绑定）。
     *
     * 旧通知携带旧 SessionId → [TakeoverGuard] 拒绝；绝不把旧入口作用到当前通话。
     */
    private fun handleTakeover(intent: Intent) {
        val target = intent.dataString?.let(TakeoverTarget::parse)
        val sessionCoordinator = coordinator
        val decision = TakeoverGuard.evaluate(
            requested = target,
            active = sessionCoordinator?.activeSession,
            state = sessionCoordinator?.state,
        )
        when (decision) {
            TakeoverDecision.Accepted -> {
                log("takeover_accepted")
                AgentCallRuntime.update { it.copy(lastError = null, lastHandback = null) }
                sessionCoordinator?.onEvent(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED))
            }
            is TakeoverDecision.Rejected -> {
                log("takeover_rejected code=" + decision.code)
                AgentCallRuntime.update { it.copy(lastError = decision.code) }
            }
        }
        updateNotification()
    }

    // ------------------------------------------------------------------ 通知/观察者

    private fun updateNotification() {
        val manager = notificationManager ?: return
        val notifications = callNotification ?: return
        if (!foregroundStarted) return
        // 通知权限被拒时只能更新前台服务通知（startForeground 已生效），普通 notify 直接跳过。
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val status = AgentCallRuntime.status.value
        val session = status.activeSession
        val notification = if (session != null) {
            notifications.call(status, session)
        } else {
            notifications.foreground(status)
        }
        runCatching { manager.notify(CallNotification.NOTIFICATION_ID, notification) }
    }

    /**
     * 协调器观察者：只更新脱敏状态并异步刷新通知。
     *
     * 回调运行在协调器的串行消费者上，绝不能在这里阻塞或反向调用协调器；通知刷新放到服务
     * scope 上执行。
     */
    private val serviceObserver: SessionObserver = object : SessionObserver() {
        override fun onStateChanged(id: SessionId, state: SessionState) {
            AgentCallRuntime.update {
                if (state == SessionState.ENDED) {
                    it.copy(activeSession = null, sessionState = state)
                } else {
                    it.copy(activeSession = id, sessionState = state)
                }
            }
            scope.launch { updateNotification() }
        }

        override fun onHandedBack(id: SessionId, outcome: RevokeOutcome) {
            val code = when (outcome) {
                RevokeOutcome.CONFIRMED -> "handback_confirmed"
                RevokeOutcome.NOT_OWNED -> "handback_not_owned"
                RevokeOutcome.REVOKE_FAILED, RevokeOutcome.UNAVAILABLE -> "handback_unconfirmed"
            }
            AgentCallRuntime.update { it.copy(lastHandback = code) }
            scope.launch { updateNotification() }
        }

        override fun onNotify(id: SessionId, reason: String) {
            AgentCallRuntime.update { it.copy(lastError = reason) }
            scope.launch { updateNotification() }
        }

        override fun onDiagnostic(code: String, id: SessionId?) {
            // 只把安全相关失败暴露给 UI；普通生命周期诊断不覆盖 lastError。
            if (code.startsWith("audio_") || code.startsWith("handback_")) {
                AgentCallRuntime.update { it.copy(lastError = code) }
            }
        }
    }

    private fun log(code: String) {
        // 只写固定机器码；绝不写负载、号码、密钥。
        Log.i(TAG, code)
    }

    companion object {
        private const val TAG = "AgentCallService"

        const val ACTION_START: String = "org.krvyft.agentcall.action.START"
        const val ACTION_STOP: String = "org.krvyft.agentcall.action.STOP"
        const val ACTION_RETRY: String = "org.krvyft.agentcall.action.RETRY"
        const val ACTION_TAKEOVER: String = "org.krvyft.agentcall.action.TAKEOVER"

        /** 前台服务类型不可用（与 lint/设备事实对应的机器码）。 */
        const val ERROR_FOREGROUND_TYPE: String = "foreground_service_type_unavailable"

        /** 就绪复检间隔：锁屏/网络/进程变化后最迟 15 s 内反映到 READY。 */
        const val READY_RECHECK_INTERVAL_MS: Long = 15_000L

        /** 关闭 BridgeClient 的收尾上限（PCM lane 关闭是本地操作）。 */
        const val CLIENT_CLOSE_TIMEOUT_MS: Long = 2_000L

        /** 供 UI 启动/重试服务的便捷入口。 */
        fun startIntent(context: Context, retry: Boolean = false): Intent =
            Intent(context, AgentCallService::class.java)
                .setAction(if (retry) ACTION_RETRY else ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AgentCallService::class.java).setAction(ACTION_STOP)
    }
}

/**
 * 生产语音会话：每通电话开始时才从桥接读取三项密钥并装配 [ConversationEngine]。
 *
 * 密钥只在局部变量里短暂存在，绝不写入日志/存储；ConversationEngine.create 已经对
 * “端点缺失或非 HTTPS”返回 null（保持人工处理）。
 */
private class BridgeConversation(
    private val client: BridgeClient,
    private val logger: (String) -> Unit,
) : ConversationPort {

    override suspend fun run(id: SessionId, audio: AudioSession, config: AgentConfig) {
        val keys = HashMap<EndpointKind, String?>()
        for (kind in EndpointKind.entries) {
            keys[kind] = client.readSecret(kind)
        }
        val engine = ConversationEngine.create(
            config = config,
            apiKeys = ApiKeyProvider { kind -> keys[kind] },
            logger = logger,
        ) ?: return
        engine.run(id, audio, config)
    }
}

/** bridge.handover 的裁决 → 协调器的撤销结果；只有 CONFIRMED/NOT_OWNED 允许恢复声音。 */
private fun HandoverResult.toRevokeOutcome(): RevokeOutcome = when (outcome) {
    HandoverOutcome.CONFIRMED -> RevokeOutcome.CONFIRMED
    HandoverOutcome.NOT_OWNED -> RevokeOutcome.NOT_OWNED
    HandoverOutcome.REVOKE_FAILED, HandoverOutcome.RESTORE_FAILED -> RevokeOutcome.REVOKE_FAILED
    HandoverOutcome.UNAVAILABLE -> RevokeOutcome.UNAVAILABLE
}

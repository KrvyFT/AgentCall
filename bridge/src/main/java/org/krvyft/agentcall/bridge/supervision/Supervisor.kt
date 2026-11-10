package org.krvyft.agentcall.bridge.supervision

import java.io.Closeable
import kotlinx.coroutines.runBlocking
import org.krvyft.agentcall.bridge.audio.AudioUnavailableException
import org.krvyft.agentcall.bridge.audio.BridgeProcessRole
import org.krvyft.agentcall.bridge.audio.ProcessFaultDecision
import org.krvyft.agentcall.bridge.audio.SupervisedAudio
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.core.session.LeaseRegistry
import org.krvyft.agentcall.core.session.SessionLimits

/** 监督端会话阶段；独立于 APK 的 SessionState，只描述特权侧所有权与终止进度。 */
enum class SupervisorPhase {
    IDLE,
    /** 已取得租约并完成隔离，接听命令待发。 */
    ARMED,
    /** 接听命令已下发，等待 ACTIVE。 */
    ANSWER_PENDING,
    ACTIVE,
    /** 音频失败，等待 10 秒人工接管。 */
    HANDOFF_PENDING,
    /** 已发出故障终止，等待断开确认。 */
    TERMINATING,
    ENDED,
    /** 无法确认终止/隔离/恢复：阻止新的 AI 会话。 */
    BLOCKED,
}

/** 监督端执行过/建议的副作用，供诊断与测试断言顺序。 */
sealed interface SupervisorAction {
    /** 停止音频但保留麦克风隔离。 */
    data class StopAudioKeepingIsolation(val id: SessionId, val reason: String) : SupervisorAction

    /** 提示用户必须在 [windowMs] 内人工接管。 */
    data class PromptManualTakeover(val id: SessionId, val windowMs: Long) : SupervisorAction

    /** 针对精确会话下发的终止动作；[path] 区分 Hook 与独立特权 Telecom。 */
    data class TerminateCall(
        val id: SessionId,
        val reason: String,
        val path: TerminationPath,
    ) : SupervisorAction

    /** 已确认断开/接管，恢复人工路由与麦克风。 */
    data class RestoreMicrophone(val id: SessionId) : SupervisorAction

    /** 失败无法闭环：禁止新的 AI 会话。 */
    data class BlockNewSessions(val reason: String) : SupervisorAction
}

/** 故障终止实际走的路径。 */
enum class TerminationPath {
    HOOK,
    PRIVILEGED_TELECOM,
    NONE,
}

/** 终止尝试的机器可读结果。 */
enum class TerminationOutcome {
    /** 不是本监督端拥有的会话。 */
    NOT_OWNED,

    /** 租约已被撤销：不得再下发挂断，保持隔离等待断开确认。 */
    NO_LEASE,

    HOOK_ACCEPTED,
    TELECOM_ACCEPTED,

    /** 没有权限/无法核对身份：明确不支持，保持隔离并阻止新会话。 */
    TELECOM_UNSUPPORTED,

    /** Hook 与特权路径都未能确认终止。 */
    FAILED,
}

data class TerminationResult(
    val outcome: TerminationOutcome,
    val actions: List<SupervisorAction>,
)

/** 人工交还的裁决。[RevokeFailed]/[RestoreFailed] 都不代表交还成功。 */
sealed interface TakeoverOutcome {
    data object Confirmed : TakeoverOutcome
    data class RevokeFailed(val reason: String) : TakeoverOutcome
    data class RestoreFailed(val reason: String) : TakeoverOutcome
    data object NotOwned : TakeoverOutcome
}

/** 自动接听是否被校准/部署策略授权；默认拒绝，生产装配注入真实校准检查。 */
fun interface AutoAnswerAuthorization {
    fun isAuthorized(): Boolean
}

/** 监督端状态快照（spec §8：重启/诊断时可见）。 */
data class SupervisorSnapshot(
    val role: BridgeProcessRole,
    val phase: SupervisorPhase,
    val ownedSession: SessionId?,
    val microphoneIsolated: Boolean,
    val peersAlive: Map<PeerRole, Boolean>,
    val calibrationAuthorized: Boolean,
    val privilegedTerminationAvailable: Boolean,
    val restartAttempts: Int,
    val blockedReason: String?,
    val lastFault: String?,
    val updatedAtMs: Long,
)

/**
 * 监督角色（独立 app_process，spec §6.3/§8）。
 *
 * 强制顺序与边界：
 * - 接听前：心跳/校准/独立终止能力全部就绪 → 取得租约 → 隔离麦克风 → 才发 Answer；
 * - 音频失败：先停音频、保持隔离，开 10000 ms 人工接管窗口；窗口到期才故障终止；
 * - 心跳失效（默认 5000 ms）：先停音频并保持隔离，再只终止自己租约下的精确会话；
 * - 撤销租约后绝不下发挂断（不能杀用户已接管的电话）；
 * - Hook 挂断失败时走独立特权 Telecom 路径；身份/权限不可用则明确不支持并阻止新会话；
 * - 断开确认前绝不恢复麦克风；[stopKeepingIsolation] 不会解除隔离；
 * - 音频工作进程重启有界，超预算即故障终止。
 *
 * 本类不执行全局 endCall，也不接受没有 [SessionId] 的终止请求。
 */
class Supervisor(
    private val clock: MonotonicClock,
    private val audio: SupervisedAudio,
    private val calls: CallControl,
    private val emergency: EmergencyCallControl,
    private val leases: LeaseRegistry = LeaseRegistry(),
    private val authorization: AutoAnswerAuthorization = AutoAnswerAuthorization { false },
    private val heartbeatTimeoutMs: Long = SessionLimits.HEARTBEAT_TIMEOUT_MS,
    private val handoffWindowMs: Long = SessionLimits.FAILURE_HANDOFF_WINDOW_MS,
    val role: BridgeProcessRole = BridgeProcessRole.SUPERVISOR,
) : Closeable {

    init {
        require(heartbeatTimeoutMs > 0L) { "heartbeatTimeoutMs must be positive" }
        require(handoffWindowMs > 0L) { "handoffWindowMs must be positive" }
    }

    private data class PeerPresence(val sessionId: SessionId, val atMs: Long)

    private data class StateView(
        val phase: SupervisorPhase,
        val owned: SessionId?,
        val blocked: String?,
        val fault: String?,
    )

    private val lock = Any()
    private var started = false
    private var phase = SupervisorPhase.IDLE
    private var ownedSession: SessionId? = null
    private var handoffDeadlineMs: Long? = null
    private var blockedReason: String? = null
    private var lastFault: String? = null
    private val heartbeats = LinkedHashMap<PeerRole, PeerPresence>()
    private val lostRoles = mutableSetOf<PeerRole>()

    /** 幂等的监督角色启动入口；由 app_process 容器调用。 */
    fun start() {
        synchronized(lock) { started = true }
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    /**
     * 记录一次对端心跳。只有 APP/HOOK 参与监督；CLI_ROOT 不是心跳角色。
     *
     * @param id 心跳所属会话（监督端只信任与租约/精确通话核验配合的会话标识）。
     */
    fun heartbeat(id: SessionId, role: PeerRole, atMs: Long = clock.nowMs()) {
        if (role != PeerRole.APP && role != PeerRole.HOOK) return
        synchronized(lock) {
            started = true
            heartbeats[role] = PeerPresence(id, atMs)
            lostRoles.remove(role)
        }
    }

    /** 该角色当前是否在 [heartbeatTimeoutMs] 内报告过心跳（未报告过一律 false）。 */
    fun peerAlive(role: PeerRole, atMs: Long = clock.nowMs()): Boolean {
        val presence = synchronized(lock) { heartbeats[role] } ?: return false
        return atMs - presence.atMs <= heartbeatTimeoutMs
    }

    /** 自动接听就绪：双端心跳、校准授权、独立终止能力、未被阻止。 */
    fun isReady(atMs: Long = clock.nowMs()): Boolean =
        peerAlive(PeerRole.APP, atMs) &&
            peerAlive(PeerRole.HOOK, atMs) &&
            authorization.isAuthorized() &&
            !emergency.blocksAutoAnswer() &&
            synchronized(lock) { blockedReason == null }

    /**
     * 接听前准备：就绪检查 → 取租约 → 隔离麦克风 → 下发 Answer。
     * 任一步失败都不会自动接听，也不会留下有效租约。
     */
    suspend fun prepareAnswer(id: SessionId): CommandResult {
        if (!isReady()) return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        leases.acquire(id) ?: return CommandResult.Rejected(RejectReason.NOT_OWNED)

        if (!audio.isolateMicrophone()) {
            leases.revoke(id)
            synchronized(lock) { blockedReason = "microphone_isolation_unavailable" }
            return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        }

        synchronized(lock) {
            ownedSession = id
            phase = SupervisorPhase.ARMED
            lastFault = null
        }

        val result = try {
            calls.send(id, CallCommand.Answer)
        } catch (t: Throwable) {
            CommandResult.Failed("answer_failed", t.javaClass.simpleName)
        }
        if (result !is CommandResult.Accepted) {
            leases.revoke(id)
            audio.stopKeepingIsolation()
            // AI 从未接听该通话，恢复麦克风不涉及 AI 通话；失败也必须清空所有权。
            audio.restoreMicrophone()
            synchronized(lock) {
                ownedSession = null
                phase = SupervisorPhase.IDLE
                handoffDeadlineMs = null
            }
            return result
        }
        synchronized(lock) { phase = SupervisorPhase.ANSWER_PENDING }
        return result
    }

    /**
     * ACTIVE 后打开真实双向流。打开失败走音频故障路径（保持隔离 + 10 秒窗口），
     * 绝不退回环境麦克风。
     */
    suspend fun onCallActive(
        id: SessionId,
        format: AudioFormatSpec = AudioFormatSpec(),
    ): CommandResult {
        if (synchronized(lock) { ownedSession } != id) {
            return CommandResult.Rejected(RejectReason.STALE_SESSION)
        }
        if (!leases.isLive(id)) {
            audio.stopKeepingIsolation()
            return CommandResult.Rejected(RejectReason.NOT_OWNED)
        }
        return try {
            audio.open(id, format)
            synchronized(lock) { phase = SupervisorPhase.ACTIVE }
            CommandResult.Accepted
        } catch (error: AudioUnavailableException) {
            onAudioFailure(id, error.code)
            CommandResult.Failed(error.code, error.message ?: error.code)
        } catch (t: Throwable) {
            onAudioFailure(id, "audio_open_failed")
            CommandResult.Failed("audio_open_failed", t.javaClass.simpleName)
        }
    }

    /**
     * 音频建立/运行失败：先停音频，保持隔离；无法维持隔离时立即终止；否则开人工接管窗口。
     */
    suspend fun onAudioFailure(id: SessionId, code: String): List<SupervisorAction> {
        if (synchronized(lock) { ownedSession } != id) return emptyList()
        val actions = mutableListOf<SupervisorAction>()
        val stillIsolated = audio.microphoneIsolated
        audio.stopKeepingIsolation()
        actions += SupervisorAction.StopAudioKeepingIsolation(id, code)
        synchronized(lock) { lastFault = code }

        if (!stillIsolated) {
            synchronized(lock) { blockedReason = "microphone_isolation_lost" }
            actions += SupervisorAction.BlockNewSessions("microphone_isolation_lost")
            actions += terminateOwned(id, "microphone_isolation_lost").actions
            return actions
        }

        synchronized(lock) {
            phase = SupervisorPhase.HANDOFF_PENDING
            handoffDeadlineMs = clock.nowMs() + handoffWindowMs
        }
        actions += SupervisorAction.PromptManualTakeover(id, handoffWindowMs)
        return actions
    }

    /**
     * 人工接管：撤销 AI 所有权 → 停音频 → 恢复麦克风。撤销失败不报成功。
     * 会话已不在本监督端名下时返回 [TakeoverOutcome.NotOwned]。
     */
    suspend fun onManualTakeover(id: SessionId): TakeoverOutcome {
        val current = synchronized(lock) { ownedSession }
        if (current == null || current != id) return TakeoverOutcome.NotOwned

        val live = leases.isLive(id)
        if (live && !leases.revoke(id)) {
            return TakeoverOutcome.RevokeFailed("lease_revoke_failed")
        }

        audio.stopKeepingIsolation()
        val restored = audio.restoreMicrophone()
        synchronized(lock) {
            ownedSession = null
            handoffDeadlineMs = null
            phase = if (restored) SupervisorPhase.IDLE else SupervisorPhase.BLOCKED
            if (!restored) blockedReason = "microphone_restore_failed"
        }
        return if (restored) {
            TakeoverOutcome.Confirmed
        } else {
            TakeoverOutcome.RestoreFailed("microphone_restore_failed")
        }
    }

    /**
     * 通话断开确认回调。只有精确拥有的会话才会停音频并恢复麦克风；外部会话一律忽略。
     */
    suspend fun onCallDisconnected(id: SessionId): List<SupervisorAction> {
        if (synchronized(lock) { ownedSession } != id) return emptyList()
        val actions = mutableListOf<SupervisorAction>()
        audio.stopKeepingIsolation()
        actions += SupervisorAction.StopAudioKeepingIsolation(id, "call_disconnected")
        leases.revoke(id)
        val restored = audio.restoreMicrophone()
        synchronized(lock) {
            ownedSession = null
            handoffDeadlineMs = null
            phase = if (restored) SupervisorPhase.ENDED else SupervisorPhase.BLOCKED
            if (!restored) blockedReason = "microphone_restore_failed"
        }
        actions += if (restored) {
            SupervisorAction.RestoreMicrophone(id)
        } else {
            SupervisorAction.BlockNewSessions("microphone_restore_failed")
        }
        return actions
    }

    /**
     * 只终止本监督端租约下的精确会话。
     *
     * 顺序：停音频（保持隔离）→ 验证租约 → Hook 挂断 → 失败则独立特权 Telecom 终止。
     * 租约已撤销时不发任何命令；这是“用户已接管/已撤销”时必须遵守的边界。
     */
    suspend fun terminateOwned(id: SessionId, reason: String): TerminationResult {
        if (synchronized(lock) { ownedSession } != id) {
            return TerminationResult(TerminationOutcome.NOT_OWNED, emptyList())
        }
        val actions = mutableListOf<SupervisorAction>()
        audio.stopKeepingIsolation()
        actions += SupervisorAction.StopAudioKeepingIsolation(id, reason)
        synchronized(lock) {
            lastFault = reason
            phase = SupervisorPhase.TERMINATING
            handoffDeadlineMs = null
        }

        if (!leases.isLive(id)) {
            return TerminationResult(TerminationOutcome.NO_LEASE, actions)
        }

        val hookResult = try {
            calls.send(id, CallCommand.Hangup)
        } catch (t: Throwable) {
            CommandResult.Failed("hangup_failed", t.javaClass.simpleName)
        }
        if (hookResult is CommandResult.Accepted) {
            leases.revoke(id)
            actions += SupervisorAction.TerminateCall(id, reason, TerminationPath.HOOK)
            return TerminationResult(TerminationOutcome.HOOK_ACCEPTED, actions)
        }

        val emergencyResult = try {
            emergency.disconnectOwned(id)
        } catch (t: Throwable) {
            CommandResult.Failed("telecom_termination_failed", t.javaClass.simpleName)
        }
        return when (emergencyResult) {
            is CommandResult.Accepted -> {
                leases.revoke(id)
                actions += SupervisorAction.TerminateCall(id, reason, TerminationPath.PRIVILEGED_TELECOM)
                TerminationResult(TerminationOutcome.TELECOM_ACCEPTED, actions)
            }
            is CommandResult.Rejected -> {
                synchronized(lock) {
                    phase = SupervisorPhase.BLOCKED
                    blockedReason = "privileged_termination_unsupported"
                }
                actions += SupervisorAction.BlockNewSessions("privileged_termination_unsupported")
                TerminationResult(TerminationOutcome.TELECOM_UNSUPPORTED, actions)
            }
            else -> {
                synchronized(lock) {
                    phase = SupervisorPhase.BLOCKED
                    blockedReason = "termination_unconfirmed"
                }
                actions += SupervisorAction.BlockNewSessions("termination_unconfirmed")
                TerminationResult(TerminationOutcome.FAILED, actions)
            }
        }
    }

    /**
     * 周期性故障检查：人工接管窗口到期、已报告过的对端心跳失效。
     * 未报告过心跳的对端不会被“判定失联”（那是未就绪，不是故障）。
     */
    suspend fun poll(nowMs: Long = clock.nowMs()): List<SupervisorAction> {
        val actions = mutableListOf<SupervisorAction>()
        val snapshot = synchronized(lock) {
            Triple(phase, handoffDeadlineMs, ownedSession)
        }
        val currentPhase = snapshot.first
        val deadline = snapshot.second
        val id = snapshot.third
        if (currentPhase == SupervisorPhase.HANDOFF_PENDING && deadline != null && id != null && nowMs >= deadline) {
            actions += terminateOwned(id, "manual_takeover_window_expired").actions
        }

        val roles = synchronized(lock) { heartbeats.keys.toList() }
        for (peerRole in roles) {
            val handled = synchronized(lock) { peerRole in lostRoles }
            if (handled) continue
            if (peerAlive(peerRole, nowMs)) continue
            synchronized(lock) {
                lostRoles += peerRole
                lastFault = "peer_lost_" + peerRole.name
            }
            val owned = synchronized(lock) { ownedSession }
            if (owned != null) {
                actions += terminateOwned(owned, "peer_lost_" + peerRole.name).actions
            }
        }
        return actions
    }

    /**
     * 音频工作进程故障：有界重启；预算耗尽则故障终止当前拥有的会话。
     * 监督进程自身的重启由模块 service.sh 负责，这里返回 [ProcessFaultDecision.NotManaged]。
     */
    suspend fun onProcessFault(role: BridgeProcessRole, code: String): ProcessFaultDecision {
        if (role != BridgeProcessRole.AUDIO_WORKER || this.role != BridgeProcessRole.SUPERVISOR) {
            return ProcessFaultDecision.NotManaged
        }
        val decision = audio.onProcessFault(code, clock.nowMs())
        if (decision is ProcessFaultDecision.RestartExhausted) {
            val owned = synchronized(lock) { ownedSession }
            if (owned != null) {
                terminateOwned(owned, "audio_restart_exhausted")
            }
        }
        return decision
    }

    fun snapshot(nowMs: Long = clock.nowMs()): SupervisorSnapshot {
        val state = synchronized(lock) {
            StateView(phase, ownedSession, blockedReason, lastFault)
        }
        return SupervisorSnapshot(
            role = role,
            phase = state.phase,
            ownedSession = state.owned,
            microphoneIsolated = audio.microphoneIsolated,
            peersAlive = mapOf(
                PeerRole.APP to peerAlive(PeerRole.APP, nowMs),
                PeerRole.HOOK to peerAlive(PeerRole.HOOK, nowMs),
            ),
            calibrationAuthorized = authorization.isAuthorized(),
            privilegedTerminationAvailable = !emergency.blocksAutoAnswer(),
            restartAttempts = audio.snapshot().restartAttempts,
            blockedReason = state.blocked,
            lastFault = state.fault,
            updatedAtMs = nowMs,
        )
    }

    /**
     * 关闭监督角色：停音频、撤销租约，但**不恢复麦克风**。
     * 模块停用必须先显式终止或交还（由调用方在关闭前完成），不能静默开麦。
     */
    override fun close() {
        runBlocking {
            val id = synchronized(lock) { ownedSession }
            if (id != null) {
                audio.stopKeepingIsolation()
                leases.revoke(id)
            }
        }
        synchronized(lock) {
            ownedSession = null
            handoffDeadlineMs = null
            phase = SupervisorPhase.ENDED
            started = false
        }
    }
}

package org.krvyft.agentcall.bridge.supervision

import java.io.Closeable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.bridge.audio.AudioUnavailableException
import org.krvyft.agentcall.bridge.audio.BridgeProcessRole
import org.krvyft.agentcall.bridge.audio.IsolationProbe
import org.krvyft.agentcall.bridge.audio.ProcessFaultDecision
import org.krvyft.agentcall.bridge.audio.SupervisedAudio
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
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
    /** 音频失败/接听结果不明确，等待 10 秒人工接管。 */
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

/**
 * 优雅关闭（spec §8）的机器可读裁决。
 *
 * 只有 [code] == null 才是干净关闭。未确认断开（[SHUTDOWN_CALL_UNRESOLVED]）与
 * 恢复失败（[SHUTDOWN_RESTORE_FAILED]）都必须保留隔离并显式上报，绝不能静默成功。
 */
data class ShutdownResult(
    val ownedSession: SessionId?,
    /** 尝试过的终止路径结果；没有拥有的会话或期限前未完成终止时为 null。 */
    val termination: TerminationOutcome?,
    /** 已取得“通话断开”或“AI 已不拥有该通话（租约撤销/非本端）”的事实。 */
    val ownershipResolved: Boolean,
    /** 是否取得真实的“通话已断开”事实（不只是租约撤销）。 */
    val disconnected: Boolean,
    /** 关闭后系统隔离读回：true 表示麦克风仍被隔离（失败关闭）。 */
    val microphoneIsolated: Boolean,
    /** null = 干净关闭；否则是稳定的机器码。 */
    val code: String?,
) {
    val clean: Boolean get() = code == null
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
    /** 监督端自己持有的音频工作进程重启预算用量（不随 worker 重启清零）。 */
    val restartAttempts: Int,
    val blockedReason: String?,
    val lastFault: String?,
    val updatedAtMs: Long,
)

/**
 * 监督角色（spec §6.3/§8）。
 *
 * 强制顺序与边界：
 * - 接听前：心跳/校准/独立终止能力全部就绪 → 取得租约 → 隔离麦克风并读回 → 才发 Answer；
 * - 接听结果不明确（Failed）：先查询真实通话状态；只有确认未接听/已断开才恢复麦克风，
 *   确认 ACTIVE 则终止精确会话，无法核验则保持隔离并开人工接管窗口；
 * - 音频失败：先停音频、保持隔离，开 10000 ms 人工接管窗口；窗口到期才故障终止；
 * - 心跳失效（默认 5000 ms）按 SessionId 归属判定：外来会话心跳不能给拥有的旧通话续命；
 * - 撤销租约后绝不下发挂断（不能杀用户已接管的电话）；
 * - 只有 ANSWER_PENDING/ARMED 允许打开音频；BLOCKED/TERMINATING 之后迟到的 ACTIVE
 *   一律拒绝，绝不覆盖阻塞状态；
 * - 断开确认前绝不恢复麦克风；[stopKeepingIsolation] 不会解除隔离；
 * - 优雅关闭（[shutdown]）：先终止/交还拥有的通话并等待确认断开（租约撤销/非本端也算
 *   已不归 AI），确认后才恢复麦克风；期限内无法确认则保持隔离并以
 *   [SHUTDOWN_CALL_UNRESOLVED] 失败关闭。崩溃/SIGKILL 不走该路径；
 * - 隔离状态每轮轮询/打开前都读回系统状态，读回失败立即停止 AI 并阻止新会话；
 * - 音频工作进程重启预算在本监督端（进程重启不清零），耗尽即故障终止。
 *
 * 并发模型：字段由 [stateMonitor] 保护；所有挂起迁移由 [transitions] 串行化（I4）。
 * [transitions] 不是状态锁：授权/Telecom 检查在状态锁之外执行（M1），
 * 只有纯状态读写与端口调用发生在 monitor 之外/之内按各自 KDoc 说明。
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
    private val callState: CallStateProbe = CallStateProbe { null },
    private val stateSink: (SupervisionPersistedState) -> Unit = {},
    private val bootId: () -> String? = { null },
    /** 构建时从持久状态恢复的粘性所有权标记（生产装配点负责读取，见 [SupervisorProductionGraph]）。 */
    initialOwnershipMayBeLive: Boolean = false,
    maxRestarts: Int = RestartBudget.DEFAULT_MAX_RESTARTS,
    restartWindowMs: Long = RestartBudget.DEFAULT_WINDOW_MS,
) : Closeable {

    init {
        require(heartbeatTimeoutMs > 0L) { "heartbeatTimeoutMs must be positive" }
        require(handoffWindowMs > 0L) { "handoffWindowMs must be positive" }
    }

    private data class StateView(
        val phase: SupervisorPhase,
        val owned: SessionId?,
        val blocked: String?,
        val fault: String?,
    )

    private val stateMonitor = Any()
    private val transitions = Mutex()
    private val restartBudget = RestartBudget(maxRestarts, restartWindowMs)
    private val pendingActions = ArrayDeque<SupervisorAction>()

    private var started = false
    private var phase = SupervisorPhase.IDLE
    private var ownedSession: SessionId? = null
    private var handoffDeadlineMs: Long? = null
    private var blockedReason: String? = null
    private var lastFault: String? = null

    /**
     * 最近一次由 [onCallDisconnected] 确认断开的会话。
     *
     * 优雅关闭用它在删除 [ownedSession] 之后仍然能区分“确认断开”与“只是租约被撤销”；
     * 它只保存一个会话，避免无界增长。
     */
    private var lastConfirmedDisconnect: SessionId? = null

    /**
     * 启动恢复/隔离读回的可重试未验证状态（评审 I1）。
     *
     * 工作进程晚启动或瞬时不可达时，启动恢复不在原地做 3 次 500 ms 重试后永久 BLOCKED，
     * 而是登记一个总期限，由 [poll] 在期限内继续尝试恢复与读回；期限内只有“确定未隔离”
     * 或“工作进程明确错误”才立即 BLOCK。超期仍未取得证据才 fail closed。
     */
    private var recoveryDeadlineMs: Long? = null

    /** 下一次恢复重试时刻；避免 500 ms 轮询无限重复同一次角色通道往返。 */
    private var recoveryRetryAtMs: Long? = null

    /**
     * 粘性所有权标记（修复轮 2 / 新增重要缺陷 2）。
     *
     * “本监督端曾经取得过通话所有权，且尚未确认断开/未接听/交还”。它必须与
     * [ownedSession] 分开持久化：block()、进程关闭、以及重启后的新进程都会把
     * [ownedSession] 写成 null，但可能仍有泄漏的音频工作进程持有那通电话和麦克风隔离。
     * 只有确认非所有权（断开确认、确认未接听、成功交还）才允许清除。
     */
    private var ownershipMayBeLive: Boolean = initialOwnershipMayBeLive

    /**
     * 每个心跳角色最近一次按会话记录的存活时间（LRU 有界）。
     *
     * I1：存活判定必须绑定 SessionId；旧实现只按角色记录，APP 进程重建后新代次的
     * 心跳会让旧会话永远“活着”，该拥有的通话永远不会超时。
     */
    private val presence = LinkedHashMap<PeerRole, LinkedHashMap<SessionId, Long>>()
    private val lostRoles = mutableSetOf<PeerRole>()

    /** 幂等的监督角色启动入口；由 app_process 容器在启动恢复之后调用。 */
    fun start() {
        synchronized(stateMonitor) { started = true }
    }

    fun isStarted(): Boolean = synchronized(stateMonitor) { started }

    /**
     * 记录一次对端心跳。只有 APP/HOOK 参与监督；CLI_ROOT 不是心跳角色。
     *
     * @param id 心跳所属会话；存活只对同一 SessionId 生效。
     */
    fun heartbeat(id: SessionId, role: PeerRole, atMs: Long = clock.nowMs()) {
        if (role != PeerRole.APP && role != PeerRole.HOOK) return
        synchronized(stateMonitor) {
            started = true
            val byId = presence.getOrPut(role) { LinkedHashMap() }
            byId.remove(id)
            byId[id] = atMs
            while (byId.size > MAX_PRESENCE_SESSIONS) {
                val eldest = byId.keys.first()
                byId.remove(eldest)
            }
            lostRoles.remove(role)
        }
    }

    /** 该角色当前是否有任一会话在 [heartbeatTimeoutMs] 内报告过心跳（诊断/就绪预检用）。 */
    fun peerAlive(role: PeerRole, atMs: Long = clock.nowMs()): Boolean = synchronized(stateMonitor) {
        presence[role]?.values?.any { atMs - it <= heartbeatTimeoutMs } == true
    }

    /** 只有与 [id] 完全同一会话的心跳才算拥有通话的存活证据（I1）。 */
    fun peerAliveFor(id: SessionId, role: PeerRole, atMs: Long = clock.nowMs()): Boolean =
        synchronized(stateMonitor) {
            val at = presence[role]?.get(id) ?: return false
            atMs - at <= heartbeatTimeoutMs
        }

    /** 拥有的通话在当前是否仍被该角色自己的心跳支撑。 */
    fun ownedPeerAlive(role: PeerRole, atMs: Long = clock.nowMs()): Boolean {
        val id = synchronized(stateMonitor) { ownedSession } ?: return false
        return peerAliveFor(id, role, atMs)
    }

    /** 就绪（角色级）：双端任一当前会话心跳、校准授权、独立终止能力、未被阻止。 */
    fun isReady(atMs: Long = clock.nowMs()): Boolean {
        val startedNow = synchronized(stateMonitor) { started }
        if (!startedNow) return false
        if (synchronized(stateMonitor) { blockedReason != null }) return false
        if (!peerAlive(PeerRole.APP, atMs) || !peerAlive(PeerRole.HOOK, atMs)) return false
        // M1：授权/Telecom 检查不在状态锁内执行。
        if (!authorization.isAuthorized()) return false
        return !emergency.blocksAutoAnswer()
    }

    /** 针对候选会话的就绪：必须是该 SessionId 自己的 APP/HOOK 心跳（I1）。 */
    fun isReadyFor(id: SessionId, atMs: Long = clock.nowMs()): Boolean {
        val startedNow = synchronized(stateMonitor) { started }
        if (!startedNow) return false
        if (synchronized(stateMonitor) { blockedReason != null }) return false
        if (!peerAliveFor(id, PeerRole.APP, atMs) || !peerAliveFor(id, PeerRole.HOOK, atMs)) return false
        // M1：授权/Telecom 检查不在状态锁内执行。
        if (!authorization.isAuthorized()) return false
        return !emergency.blocksAutoAnswer()
    }

    /**
     * 接听前准备：就绪（按会话心跳）→ 取租约 → 隔离并读回 → 下发 Answer。
     * 任一步失败都不会自动接听，也不会留下有效租约。
     */
    suspend fun prepareAnswer(id: SessionId): CommandResult {
        val reusable = synchronized(stateMonitor) {
            ownedSession == null && (phase == SupervisorPhase.IDLE || phase == SupervisorPhase.ENDED)
        }
        if (!reusable) return CommandResult.Rejected(RejectReason.NOT_OWNED)
        // M1：校准授权/Telecom 能力检查在状态锁与迁移串行化之外执行，绝不占用状态锁。
        if (!isReadyFor(id)) return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        return transitions.withLock { prepareAnswerLocked(id) }
    }

    private suspend fun prepareAnswerLocked(id: SessionId): CommandResult {
        // 取得串行化后只复核内存状态；外部授权检查已在上方无锁完成。
        val stillReusable = synchronized(stateMonitor) {
            ownedSession == null && (phase == SupervisorPhase.IDLE || phase == SupervisorPhase.ENDED)
        }
        if (!stillReusable) return CommandResult.Rejected(RejectReason.NOT_OWNED)
        if (synchronized(stateMonitor) { !started || blockedReason != null }) {
            return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        }
        if (leases.acquire(id) == null) return CommandResult.Rejected(RejectReason.NOT_OWNED)

        if (!audio.isolateMicrophone()) {
            leases.revoke(id)
            block(BLOCKER_ISOLATION_UNAVAILABLE)
            return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        }
        // I7：隔离成功也要读回系统状态，不能只信返回值/缓存。
        // I1：不可达不等于“隔离丢失”，只阻止本次自动接听（可重试），不把它当成永久阻塞证据。
        when (val probe = probeIsolationSafe()) {
            is IsolationProbe.Verified -> if (!probe.isolated) {
                leases.revoke(id)
                audio.stopKeepingIsolation()
                block(BLOCKER_ISOLATION_LOST)
                return CommandResult.Rejected(RejectReason.UNSUPPORTED)
            }
            is IsolationProbe.WorkerError -> {
                leases.revoke(id)
                audio.stopKeepingIsolation()
                block(BLOCKER_ISOLATION_UNAVAILABLE)
                return CommandResult.Rejected(RejectReason.UNSUPPORTED)
            }
            is IsolationProbe.Unverified -> {
                // 没有系统事实：撤销租约、保持隔离意图、不开音频；轮询会在总期限内重试并清除阻塞。
                leases.revoke(id)
                audio.stopKeepingIsolation()
                block(BLOCKER_ISOLATION_UNAVAILABLE)
                return CommandResult.Rejected(RejectReason.UNSUPPORTED)
            }
        }

        synchronized(stateMonitor) {
            ownedSession = id
            ownershipMayBeLive = true
            phase = SupervisorPhase.ARMED
            handoffDeadlineMs = null
            lastFault = null
        }
        persist()

        val result = sendAnswer(id)
        return when (result) {
            is CommandResult.Accepted -> {
                synchronized(stateMonitor) { phase = SupervisorPhase.ANSWER_PENDING }
                persist()
                result
            }
            is CommandResult.Rejected -> {
                // 契约保证 Rejected 没有副作用：可以确定未接听，恢复麦克风。
                releaseAfterConfirmedNonAnswer(id)
                result
            }
            is CommandResult.Failed -> {
                // 响应丢失/执行失败：不明确，先核验真实状态再决定（I2）。
                handleAmbiguousAnswer(id, result)
                result
            }
        }
    }

    private suspend fun sendAnswer(id: SessionId): CommandResult = try {
        calls.send(id, CallCommand.Answer)
    } catch (t: Throwable) {
        CommandResult.Failed("answer_failed", t.javaClass.simpleName)
    }

    /**
     * 接听结果不明确。/Rejected/ 是确定性的“未执行”；/Failed/ 必须查询真实状态：
     * - RINGING/DISCONNECTED：确认未接听或已断开 → 恢复麦克风；
     * - ACTIVE/HOLDING：电话可能已被 AI 接起 → 立即终止精确会话，保持隔离；
     * - null：无法核验 → 保持隔离与所有权，进入人工接管窗口（到期故障终止）。
     */
    private suspend fun handleAmbiguousAnswer(id: SessionId, failure: CommandResult.Failed) {
        val state = try {
            callState.state(id)
        } catch (t: Throwable) {
            null
        }
        when (state) {
            CallState.RINGING, CallState.DISCONNECTED -> releaseAfterConfirmedNonAnswer(id)
            CallState.ACTIVE, CallState.HOLDING -> {
                enterHandoff(id, failure.code)
                terminateOwnedLocked(id, "answer_ambiguous")
            }
            null -> enterHandoff(id, failure.code)
        }
    }

    private suspend fun releaseAfterConfirmedNonAnswer(id: SessionId) {
        audio.stopKeepingIsolation()
        leases.revoke(id)
        val restored = try {
            audio.restoreMicrophone()
        } catch (t: Throwable) {
            false
        }
        synchronized(stateMonitor) {
            ownedSession = null
            // 确认未接听（Rejected / RINGING / DISCONNECTED）是“非所有权”证据。
            ownershipMayBeLive = false
            handoffDeadlineMs = null
            if (restored) {
                phase = SupervisorPhase.IDLE
            } else {
                phase = SupervisorPhase.BLOCKED
                blockedReason = BLOCKER_RESTORE_FAILED
            }
        }
        persist()
    }

    private fun enterHandoff(id: SessionId, code: String) {
        synchronized(stateMonitor) {
            if (ownedSession != id) return
            phase = SupervisorPhase.HANDOFF_PENDING
            handoffDeadlineMs = clock.nowMs() + handoffWindowMs
            lastFault = code
        }
        pendingActions.addLast(SupervisorAction.PromptManualTakeover(id, handoffWindowMs))
        persist()
    }

    /**
     * ACTIVE 后打开真实双向流。只有 ANSWER_PENDING/ARMED 允许打开；重复 ACTIVE 幂等，
     * BLOCKED/TERMINATING/HANDOFF_PENDING 之后迟到的 ACTIVE 一律拒绝（I3）。
     */
    suspend fun onCallActive(
        id: SessionId,
        format: AudioFormatSpec = AudioFormatSpec(),
    ): CommandResult = transitions.withLock {
        val currentPhase = synchronized(stateMonitor) { phase }
        val owned = synchronized(stateMonitor) { ownedSession }
        if (owned != id) return CommandResult.Rejected(RejectReason.STALE_SESSION)
        if (currentPhase == SupervisorPhase.ACTIVE) return CommandResult.Accepted
        if (currentPhase != SupervisorPhase.ANSWER_PENDING && currentPhase != SupervisorPhase.ARMED) {
            return CommandResult.Rejected(RejectReason.STALE_SESSION)
        }
        if (!leases.isLive(id)) {
            audio.stopKeepingIsolation()
            return CommandResult.Rejected(RejectReason.NOT_OWNED)
        }
        // I7：打开前必须读回系统隔离状态。
        // I1：只有“确定未隔离”或工作进程明确错误才是 BLOCK 证据；不可达只是本次不能打开。
        when (val probe = probeIsolationSafe()) {
            is IsolationProbe.Verified -> if (!probe.isolated) {
                audio.stopKeepingIsolation()
                block(BLOCKER_ISOLATION_LOST)
                terminateOwnedLocked(id, BLOCKER_ISOLATION_LOST)
                return CommandResult.Failed(BLOCKER_ISOLATION_LOST, "microphone isolation read-back failed")
            }
            is IsolationProbe.WorkerError -> {
                audio.stopKeepingIsolation()
                block(BLOCKER_ISOLATION_LOST)
                terminateOwnedLocked(id, BLOCKER_ISOLATION_LOST)
                return CommandResult.Failed(probe.code, "microphone isolation could not be verified")
            }
            is IsolationProbe.Unverified -> {
                // 没有系统事实：不开音频，也不把一次瞬时不可达当成隔离丢失。
                audio.stopKeepingIsolation()
                return CommandResult.Failed(ERROR_ISOLATION_UNVERIFIED, "microphone isolation could not be verified")
            }
        }
        return try {
            audio.open(id, format)
            synchronized(stateMonitor) { phase = SupervisorPhase.ACTIVE }
            persist()
            CommandResult.Accepted
        } catch (error: AudioUnavailableException) {
            onAudioFailureLocked(id, error.code)
            CommandResult.Failed(error.code, error.message ?: error.code)
        } catch (t: Throwable) {
            onAudioFailureLocked(id, "audio_open_failed")
            CommandResult.Failed("audio_open_failed", t.javaClass.simpleName)
        }
    }

    /**
     * 音频建立/运行失败：先停音频，保持隔离；隔离已丢失时立即终止并阻止新会话；
     * 否则开人工接管窗口。
     */
    suspend fun onAudioFailure(id: SessionId, code: String): List<SupervisorAction> =
        transitions.withLock { onAudioFailureLocked(id, code) }

    private suspend fun onAudioFailureLocked(id: SessionId, code: String): List<SupervisorAction> {
        if (synchronized(stateMonitor) { ownedSession } != id) return emptyList()
        val actions = mutableListOf<SupervisorAction>()
        // I7：读回系统状态，不用缓存布尔值。
        // I1：工作进程不可达没有隔离事实，不能据此 block + 终止；保持隔离意图并开人工接管窗口。
        val probe = probeIsolationSafe()
        audio.stopKeepingIsolation()
        actions += SupervisorAction.StopAudioKeepingIsolation(id, code)
        synchronized(stateMonitor) { lastFault = code }

        val isolationLost = probe is IsolationProbe.Verified && !probe.isolated
        val workerError = probe is IsolationProbe.WorkerError
        if (isolationLost || workerError) {
            block(BLOCKER_ISOLATION_LOST)
            actions += SupervisorAction.BlockNewSessions(BLOCKER_ISOLATION_LOST)
            actions += terminateOwnedLocked(id, BLOCKER_ISOLATION_LOST).actions
            return actions
        }

        synchronized(stateMonitor) {
            phase = SupervisorPhase.HANDOFF_PENDING
            handoffDeadlineMs = clock.nowMs() + handoffWindowMs
        }
        persist()
        actions += SupervisorAction.PromptManualTakeover(id, handoffWindowMs)
        return actions
    }

    /**
     * 人工接管：撤销 AI 所有权 → 停音频 → 恢复麦克风。撤销失败不报成功。
     * 会话已不在本监督端名下时返回 [TakeoverOutcome.NotOwned]。
     */
    suspend fun onManualTakeover(id: SessionId): TakeoverOutcome = transitions.withLock {
        val current = synchronized(stateMonitor) { ownedSession }
        if (current == null || current != id) return TakeoverOutcome.NotOwned

        val live = leases.isLive(id)
        if (live && !leases.revoke(id)) {
            return TakeoverOutcome.RevokeFailed("lease_revoke_failed")
        }

        audio.stopKeepingIsolation()
        val restored = try {
            audio.restoreMicrophone()
        } catch (t: Throwable) {
            false
        }
        synchronized(stateMonitor) {
            ownedSession = null
            // 只有确认交还成功（麦克风已恢复并读回）才是“非所有权”证据。
            if (restored) ownershipMayBeLive = false
            handoffDeadlineMs = null
            phase = if (restored) SupervisorPhase.IDLE else SupervisorPhase.BLOCKED
            if (!restored) blockedReason = BLOCKER_RESTORE_FAILED
        }
        persist()
        return if (restored) {
            TakeoverOutcome.Confirmed
        } else {
            TakeoverOutcome.RestoreFailed(BLOCKER_RESTORE_FAILED)
        }
    }

    /**
     * 通话断开确认回调。只有精确拥有的会话才会停音频并恢复麦克风；外部会话一律忽略。
     */
    suspend fun onCallDisconnected(id: SessionId): List<SupervisorAction> = transitions.withLock {
        if (synchronized(stateMonitor) { ownedSession } != id) return emptyList()
        synchronized(stateMonitor) { lastConfirmedDisconnect = id }
        val actions = mutableListOf<SupervisorAction>()
        audio.stopKeepingIsolation()
        actions += SupervisorAction.StopAudioKeepingIsolation(id, "call_disconnected")
        leases.revoke(id)
        val restored = try {
            audio.restoreMicrophone()
        } catch (t: Throwable) {
            false
        }
        synchronized(stateMonitor) {
            ownedSession = null
            // 断开是已核验的“非所有权”证据：即使恢复失败也清除标记（通话确实没了）。
            ownershipMayBeLive = false
            handoffDeadlineMs = null
            phase = if (restored) SupervisorPhase.ENDED else SupervisorPhase.BLOCKED
            if (!restored) blockedReason = BLOCKER_RESTORE_FAILED
        }
        persist()
        actions += if (restored) {
            SupervisorAction.RestoreMicrophone(id)
        } else {
            SupervisorAction.BlockNewSessions(BLOCKER_RESTORE_FAILED)
        }
        return actions
    }

    /**
     * 只终止本监督端租约下的精确会话。
     *
     * 顺序：停音频（保持隔离）→ 验证租约 → Hook 挂断 → 失败则独立特权 Telecom 终止。
     * 租约已撤销时不发任何命令；这是用户已接管/已撤销时必须遵守的边界。
     */
    suspend fun terminateOwned(id: SessionId, reason: String): TerminationResult =
        transitions.withLock { terminateOwnedLocked(id, reason) }

    private suspend fun terminateOwnedLocked(id: SessionId, reason: String): TerminationResult {
        if (synchronized(stateMonitor) { ownedSession } != id) {
            return TerminationResult(TerminationOutcome.NOT_OWNED, emptyList())
        }
        val actions = mutableListOf<SupervisorAction>()
        audio.stopKeepingIsolation()
        actions += SupervisorAction.StopAudioKeepingIsolation(id, reason)
        synchronized(stateMonitor) {
            lastFault = reason
            phase = SupervisorPhase.TERMINATING
            handoffDeadlineMs = null
        }
        persist()

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
            persist()
            return TerminationResult(TerminationOutcome.HOOK_ACCEPTED, actions)
        }

        // M1：Telecom 检查/调用不持有状态 monitor；只由 transitions 串行化。
        val emergencyResult = try {
            emergency.disconnectOwned(id)
        } catch (t: Throwable) {
            CommandResult.Failed("telecom_termination_failed", t.javaClass.simpleName)
        }
        return when (emergencyResult) {
            is CommandResult.Accepted -> {
                leases.revoke(id)
                actions += SupervisorAction.TerminateCall(id, reason, TerminationPath.PRIVILEGED_TELECOM)
                persist()
                TerminationResult(TerminationOutcome.TELECOM_ACCEPTED, actions)
            }
            is CommandResult.Rejected -> {
                block(BLOCKER_PRIVILEGED_TERMINATION)
                actions += SupervisorAction.BlockNewSessions(BLOCKER_PRIVILEGED_TERMINATION)
                TerminationResult(TerminationOutcome.TELECOM_UNSUPPORTED, actions)
            }
            else -> {
                block(BLOCKER_TERMINATION_UNCONFIRMED)
                actions += SupervisorAction.BlockNewSessions(BLOCKER_TERMINATION_UNCONFIRMED)
                TerminationResult(TerminationOutcome.FAILED, actions)
            }
        }
    }

    /**
     * 周期性故障检查：人工接管窗口到期、拥有的会话心跳失效、ACTIVE 期间隔离读回。
     * 同一轮最多终止同一 SessionId 一次（M4）。
     */
    suspend fun poll(nowMs: Long = clock.nowMs()): List<SupervisorAction> = transitions.withLock {
        val actions = mutableListOf<SupervisorAction>()
        drainPendingInto(actions)
        // I1：启动恢复的未验证/可恢复阻塞在轮询里继续重试；只有系统证据才允许清除阻塞。
        actions += reconcileRecoveryLocked(nowMs)
        // 音频工作进程故障的生产来源：每轮取走一条并计入本监督端的重启预算。
        var audioFault = takeAudioFault()
        while (audioFault != null) {
            val fault = onProcessFaultLocked(BridgeProcessRole.AUDIO_WORKER, audioFault, nowMs)
            actions += fault.second
            audioFault = takeAudioFault()
        }
        val handled = mutableSetOf<SessionId>()

        val handoff = synchronized(stateMonitor) {
            Triple(phase, handoffDeadlineMs, ownedSession)
        }
        if (handoff.first == SupervisorPhase.HANDOFF_PENDING &&
            handoff.second != null &&
            handoff.third != null &&
            nowMs >= handoff.second!!
        ) {
            val id = handoff.third!!
            if (handled.add(id)) {
                actions += terminateOwnedLocked(id, "manual_takeover_window_expired").actions
            }
        }

        // I7：拥有的通话在 ACTIVE/等待阶段每轮都读回系统隔离状态。
        val liveOwner = synchronized(stateMonitor) { phase to ownedSession }
        val isolatedOwner = liveOwner.second
        if (isolatedOwner != null &&
            isolatedOwner !in handled &&
            liveOwner.first in AUDIO_OWNER_PHASES
        ) {
            // I1：只有“确定未隔离”或工作进程明确错误才 block + 终止；不可达留给重启预算。
            val probe = probeIsolationSafe()
            val isolationLost = probe is IsolationProbe.Verified && !probe.isolated
            val workerError = probe is IsolationProbe.WorkerError
            if (isolationLost || workerError) {
                audio.stopKeepingIsolation()
                block(BLOCKER_ISOLATION_LOST)
                actions += SupervisorAction.BlockNewSessions(BLOCKER_ISOLATION_LOST)
                if (handled.add(isolatedOwner)) {
                    actions += terminateOwnedLocked(isolatedOwner, BLOCKER_ISOLATION_LOST).actions
                }
            }
        }

        // I1：只检查拥有会话自己的心跳；外来会话心跳不参与。
        val owned = synchronized(stateMonitor) { ownedSession }
        if (owned != null && owned !in handled) {
            for (peerRole in HEARTBEAT_ROLES) {
                if (synchronized(stateMonitor) { peerRole in lostRoles }) continue
                if (peerAliveFor(owned, peerRole, nowMs)) continue
                synchronized(stateMonitor) {
                    lostRoles += peerRole
                    lastFault = "peer_lost_" + peerRole.name
                }
                if (handled.add(owned)) {
                    actions += terminateOwnedLocked(owned, "peer_lost_" + peerRole.name).actions
                }
                break
            }
        }
        return actions
    }

    private fun drainPendingInto(actions: MutableList<SupervisorAction>) {
        while (true) {
            val next = pendingActions.removeFirstOrNull() ?: return
            actions += next
        }
    }

    /**
     * 音频工作进程故障：重启预算在本监督端（I5）；预算耗尽则故障终止当前拥有的会话。
     * 监督进程自身的重启由模块 service.sh 负责，这里返回 [ProcessFaultDecision.NotManaged]。
     */
    suspend fun onProcessFault(
        role: BridgeProcessRole,
        code: String,
        nowMs: Long = clock.nowMs(),
    ): ProcessFaultDecision = transitions.withLock {
        onProcessFaultLocked(role, code, nowMs).first
    }

    /** 取走一条音频工作进程故障；取不到或端口异常返回 null（不能把轮询打断）。 */
    private fun takeAudioFault(): String? = try {
        audio.takeProcessFault()
    } catch (t: Throwable) {
        null
    }

    /** I1：隔离读回的三态封装；端口抛异常按“未验证”处理，绝不冒充“已解除隔离”。 */
    private suspend fun probeIsolationSafe(): IsolationProbe = try {
        audio.probeIsolation()
    } catch (t: Throwable) {
        IsolationProbe.Unverified(t.javaClass.simpleName)
    }

    /**
     * I1：可恢复阻塞的轮询重试。
     *
     * 只在“没有拥有的会话且粘性所有权标记为 false”时尝试恢复麦克风并读回；任何情况下都
     * 绝不解除可能仍活着的 AI 通话隔离。清除阻塞的唯一依据是系统事实“确定未隔离”
     * （[IsolationProbe.Verified] false），或恢复请求本身的成功应答；不可达/非法响应只会
     * 在期限内继续重试，超期才 fail closed。
     */
    private suspend fun reconcileRecoveryLocked(nowMs: Long): List<SupervisorAction> {
        val deadline = synchronized(stateMonitor) {
            val reason = blockedReason
            if (reason == null || reason !in RECOVERY_RETRY_BLOCKERS) return emptyList()
            if (ownedSession != null || ownershipMayBeLive) return emptyList()
            val retryAt = recoveryRetryAtMs
            if (retryAt != null && nowMs < retryAt) return emptyList()
            recoveryDeadlineMs
        }

        val restored = try {
            audio.restoreMicrophone()
        } catch (t: Throwable) {
            false
        }
        val probe = probeIsolationSafe()
        val verified = probe as? IsolationProbe.Verified
        val definitelyNotIsolated = verified != null && !verified.isolated
        // 恢复请求的成功应答本身就是工作进程对“已解除隔离”的确认（ok=true 且 isolated=false）。
        val restoredResponseEvidence = restored && probe is IsolationProbe.Unverified
        if (definitelyNotIsolated || restoredResponseEvidence) {
            synchronized(stateMonitor) {
                blockedReason = null
                recoveryDeadlineMs = null
                recoveryRetryAtMs = null
                if (phase == SupervisorPhase.BLOCKED) phase = SupervisorPhase.IDLE
            }
            persist()
            return emptyList()
        }
        if (verified != null && verified.isolated) {
            val expired = deadline != null && nowMs >= deadline
            val transitioned = synchronized(stateMonitor) {
                recoveryRetryAtMs = nowMs + RECOVERY_RETRY_INTERVAL_MS
                if (expired && blockedReason != SupervisorBootRecovery.BLOCK_STILL_ISOLATED) {
                    blockedReason = SupervisorBootRecovery.BLOCK_STILL_ISOLATED
                    phase = SupervisorPhase.BLOCKED
                    true
                } else {
                    false
                }
            }
            if (transitioned) persist()
            return if (transitioned) {
                listOf(SupervisorAction.BlockNewSessions(SupervisorBootRecovery.BLOCK_STILL_ISOLATED))
            } else {
                emptyList()
            }
        }
        if (probe is IsolationProbe.WorkerError) {
            // 工作进程明确回报错误：这是确定证据，保持/进入 BLOCKED。
            return enterRecoveryBlock(nowMs, SupervisorBootRecovery.BLOCK_RESTORE_FAILED)
        }
        val expired = deadline != null && nowMs >= deadline
        if (!expired) {
            synchronized(stateMonitor) { recoveryRetryAtMs = nowMs + RECOVERY_RETRY_INTERVAL_MS }
            return emptyList()
        }
        return enterRecoveryBlock(nowMs, SupervisorBootRecovery.BLOCK_RESTORE_FAILED)
    }

    /**
     * 进入/维持恢复类 BLOCKED：只有状态真的改变才产生新的 BlockNewSessions 动作，
     * 避免 1 s 重试把同一次阻塞每轮都当成新事件上报。
     */
    private fun enterRecoveryBlock(nowMs: Long, reason: String): List<SupervisorAction> {
        val changed = synchronized(stateMonitor) {
            recoveryRetryAtMs = nowMs + RECOVERY_RETRY_INTERVAL_MS
            if (phase == SupervisorPhase.BLOCKED && blockedReason == reason) {
                false
            } else {
                blockedReason = reason
                phase = SupervisorPhase.BLOCKED
                true
            }
        }
        if (!changed) return emptyList()
        persist()
        return listOf(SupervisorAction.BlockNewSessions(reason))
    }

    /**
     * 已持有 [transitions] 时的故障裁决。返回裁决与被触发的终止动作，供
     * [poll] 把生产路径的故障直接变成监督动作；[onProcessFault] 是它的挂起入口。
     */
    private suspend fun onProcessFaultLocked(
        role: BridgeProcessRole,
        code: String,
        nowMs: Long,
    ): Pair<ProcessFaultDecision, List<SupervisorAction>> {
        if (role != BridgeProcessRole.AUDIO_WORKER || this.role != BridgeProcessRole.SUPERVISOR) {
            return ProcessFaultDecision.NotManaged to emptyList()
        }
        // I1：没有拥有的通话时，启动/恢复探测的瞬时不可达不是“重启”，绝不占用预算；
        // 否则工作进程晚启动会把预算打满，第一通真实通话的首次故障就直接被终止。
        val owned = synchronized(stateMonitor) { ownedSession }
        if (owned == null) {
            synchronized(stateMonitor) { lastFault = code }
            persist()
            return ProcessFaultDecision.RestartAllowed(restartBudget.used(nowMs)) to emptyList()
        }
        val decision = restartBudget.record(nowMs)
        synchronized(stateMonitor) { lastFault = code }
        val actions = if (decision is ProcessFaultDecision.RestartExhausted) {
            terminateOwnedLocked(owned, "audio_restart_exhausted").actions
        } else {
            persist()
            emptyList()
        }
        return decision to actions
    }

    fun snapshot(nowMs: Long = clock.nowMs()): SupervisorSnapshot {
        val state = synchronized(stateMonitor) {
            StateView(phase, ownedSession, blockedReason, lastFault)
        }
        // M1：授权/Telecom 检查不在状态锁内。
        val authorized = authorization.isAuthorized()
        val privilegedAvailable = !emergency.blocksAutoAnswer()
        return SupervisorSnapshot(
            role = role,
            phase = state.phase,
            ownedSession = state.owned,
            microphoneIsolated = audio.microphoneIsolated,
            peersAlive = mapOf(
                PeerRole.APP to peerAlive(PeerRole.APP, nowMs),
                PeerRole.HOOK to peerAlive(PeerRole.HOOK, nowMs),
            ),
            calibrationAuthorized = authorized,
            privilegedTerminationAvailable = privilegedAvailable,
            restartAttempts = restartBudget.used(nowMs),
            blockedReason = state.blocked,
            lastFault = state.fault,
            updatedAtMs = nowMs,
        )
    }

    /**
     * 启动恢复失败时由运行时调用：阻止新的 AI 会话，保留隔离，等待人工处理。
     *
     * I1：隔离/恢复类阻塞不是“永久死刑”。登记总期限与下次重试时刻，由 [poll] 继续
     * 尝试恢复麦克风并读回系统状态；只有系统证据“确定未隔离”才清除阻塞。
     */
    fun block(reason: String) {
        synchronized(stateMonitor) {
            blockedReason = reason
            phase = SupervisorPhase.BLOCKED
            if (reason in RECOVERY_RETRY_BLOCKERS) {
                if (recoveryDeadlineMs == null) {
                    recoveryDeadlineMs = clock.nowMs() + SupervisorBootRecovery.DEFAULT_RECOVERY_DEADLINE_MS
                }
                recoveryRetryAtMs = clock.nowMs() + RECOVERY_RETRY_INTERVAL_MS
            } else {
                recoveryDeadlineMs = null
                recoveryRetryAtMs = null
            }
        }
        persist()
    }

    /**
     * I1：启动恢复未取得隔离证据时的可重试登记（不是 [block]）。
     *
     * 自动接听在未验证期间保持关闭（[isReady] 因 blockedReason 非空返回 false），但不会
     * 永久禁用模块：轮询在 [deadlineAtMs] 前继续重试恢复/读回，取得“确定未隔离”证据后
     * 自动清除阻塞并回到 IDLE。
     */
    fun beginRecovery(reason: String, deadlineAtMs: Long) {
        synchronized(stateMonitor) {
            blockedReason = reason
            recoveryDeadlineMs = deadlineAtMs
            recoveryRetryAtMs = clock.nowMs()
        }
        persist()
    }

    /**
     * 优雅关闭（挂起）：先终止/交还拥有的通话，只有确认断开（或租约已撤销、AI 不再拥有）
     * 才恢复麦克风，然后收束内存状态。
     *
     * 期限 [deadlineMs]（默认 [SHUTDOWN_TERMINATION_DEADLINE_MS]）覆盖“终止命令 + 等待
     * 断开事实”。期限内无法确认时保持隔离、以 [SHUTDOWN_CALL_UNRESOLVED] 失败关闭；
     * 恢复失败时进入 BLOCKED 并上报 [SHUTDOWN_RESTORE_FAILED]。崩溃/SIGKILL 不经过这里，
     * 隔离可能保留，只能由下一次启动恢复。
     */
    suspend fun shutdown(deadlineMs: Long = SHUTDOWN_TERMINATION_DEADLINE_MS): ShutdownResult {
        require(deadlineMs > 0L) { "deadlineMs must be positive" }
        val id = synchronized(stateMonitor) { ownedSession }
        if (id == null) {
            // 没有拥有的通话：绝不下发终止，也不恢复麦克风（隔离状态原样保留）。
            markClosed()
            return ShutdownResult(
                ownedSession = null,
                termination = null,
                ownershipResolved = false,
                disconnected = false,
                microphoneIsolated = audio.microphoneIsolated,
                code = null,
            )
        }

        var termination: TerminationOutcome? = null
        val resolved = withTimeoutOrNull(deadlineMs) {
            val result = terminateOwned(id, SHUTDOWN_REASON)
            termination = result.outcome
            when (result.outcome) {
                // 租约已撤销 / 已不归本监督端：AI 不再拥有该通话，可以恢复麦克风。
                TerminationOutcome.NOT_OWNED, TerminationOutcome.NO_LEASE -> true
                // 命令被接受/终止失败都必须等到真实的断开事实，不能把“已下发”当成“已断开”。
                else -> awaitConfirmedDisconnect(id)
            }
        } ?: false

        // 期限边界上可能刚好到达断开事实：有事实就按已解析处理（宁可恢复，不可误报未解决）。
        val resolvedNow = resolved || confirmedDisconnect(id)
        val disconnected = resolvedNow && confirmedDisconnect(id)
        if (resolvedNow) {
            val restored = restoreMicrophoneVerified()
            if (restored) {
                finishResolvedShutdown(disconnected)
                return ShutdownResult(
                    ownedSession = id,
                    termination = termination,
                    ownershipResolved = true,
                    disconnected = disconnected,
                    microphoneIsolated = false,
                    code = null,
                )
            }
            finishRestoreFailedShutdown(disconnected)
            return ShutdownResult(
                ownedSession = id,
                termination = termination,
                ownershipResolved = true,
                disconnected = disconnected,
                microphoneIsolated = true,
                code = SHUTDOWN_RESTORE_FAILED,
            )
        }

        // 期限已到仍无法确认：保持隔离、失败关闭；绝不能以干净关闭收尾。
        synchronized(stateMonitor) {
            lastFault = SHUTDOWN_CALL_UNRESOLVED
            handoffDeadlineMs = null
            phase = SupervisorPhase.ENDED
            started = false
        }
        persist()
        return ShutdownResult(
            ownedSession = id,
            termination = termination,
            ownershipResolved = false,
            disconnected = false,
            microphoneIsolated = audio.microphoneIsolated,
            code = SHUTDOWN_CALL_UNRESOLVED,
        )
    }

    /** 在关闭期限内等待真实断开事实（HOOK 快照、[onCallDisconnected]，或状态探针）。 */
    private suspend fun awaitConfirmedDisconnect(id: SessionId): Boolean {
        while (true) {
            if (confirmedDisconnect(id)) return true
            delay(SHUTDOWN_DISCONNECT_POLL_MS)
        }
    }

    /**
     * 真实断开/所有权交还事实：已记录的断开回调、所有权已被移走（人工交还），或通话状态
     * 读回 DISCONNECTED。读不到状态只代表“未确认”，绝不能当成已断开。
     */
    private suspend fun confirmedDisconnect(id: SessionId): Boolean {
        if (synchronized(stateMonitor) { lastConfirmedDisconnect == id || ownedSession != id }) return true
        val state = try {
            callState.state(id)
        } catch (t: Throwable) {
            null
        }
        return state == CallState.DISCONNECTED
    }

    /**
     * 恢复麦克风并读回。系统已确认未隔离时直接 no-op（不重复下发 restore）。
     *
     * 三态证据与 [reconcileRecoveryLocked] 同源：[IsolationProbe.Verified] 的读回是唯一
     * 硬证据；工作进程不可达时只有 restore 自身成功应答可作为证据，明确错误则失败关闭。
     */
    private suspend fun restoreMicrophoneVerified(): Boolean {
        val before = probeIsolationSafe()
        if (before is IsolationProbe.Verified && !before.isolated) return true
        val restored = try {
            audio.restoreMicrophone()
        } catch (t: Throwable) {
            false
        }
        return when (val after = probeIsolationSafe()) {
            is IsolationProbe.Verified -> !after.isolated
            is IsolationProbe.WorkerError -> false
            is IsolationProbe.Unverified -> restored
        }
    }

    /** 已确认非 AI 所有且麦克风已读回恢复：收束状态（确认断开才清除粘性标记）。 */
    private fun finishResolvedShutdown(confirmedDisconnected: Boolean) {
        synchronized(stateMonitor) {
            ownedSession = null
            handoffDeadlineMs = null
            if (confirmedDisconnected) ownershipMayBeLive = false
            phase = SupervisorPhase.ENDED
            started = false
        }
        persist()
    }

    /** 通话已不是 AI 的但麦克风恢复失败：BLOCKED + 明确机器码，等待下一次恢复重试。 */
    private fun finishRestoreFailedShutdown(confirmedDisconnected: Boolean) {
        synchronized(stateMonitor) {
            ownedSession = null
            handoffDeadlineMs = null
            if (confirmedDisconnected) ownershipMayBeLive = false
            lastFault = SHUTDOWN_RESTORE_FAILED
            blockedReason = SHUTDOWN_RESTORE_FAILED
            phase = SupervisorPhase.BLOCKED
            started = false
        }
        persist()
    }

    /**
     * 关闭监督角色。**不执行 runBlocking**（M8）：在受限期协程线程上阻塞等待挂起音频操作
     * 可能死锁。调用方应先调用 [shutdown] 完成终止/交还与（允许时的）恢复；本方法只收束
     * 内存状态并撤销租约，**绝不恢复麦克风**。
     *
     * 崩溃/SIGKILL 只会走进程消失路径（不经过本方法）：隔离可能保留到重启，
     * 只有 [shutdown] 的优雅路径会终止/交还并恢复。
     */
    override fun close() {
        val id = synchronized(stateMonitor) { ownedSession }
        if (id != null) {
            leases.revoke(id)
        }
        markClosed()
    }

    private fun markClosed() {
        synchronized(stateMonitor) {
            ownedSession = null
            handoffDeadlineMs = null
            phase = SupervisorPhase.ENDED
            started = false
        }
        persist()
    }

    private fun persist() {
        val state = synchronized(stateMonitor) {
            SupervisionPersistedState(
                phase = phase,
                ownedSession = ownedSession,
                microphoneIsolated = audio.microphoneIsolated,
                blockedReason = blockedReason,
                lastFault = lastFault,
                updatedAtMs = clock.nowMs(),
                bootId = bootId(),
                ownedCallMayBeLive = ownershipMayBeLive,
            )
        }
        try {
            stateSink(state)
        } catch (t: Throwable) {
            // 状态写不进去就不能保证重启后 fail closed：阻止新会话。
            synchronized(stateMonitor) {
                if (blockedReason == null) {
                    blockedReason = BLOCKER_STATE_WRITE_FAILED
                    phase = SupervisorPhase.BLOCKED
                }
            }
        }
    }

    companion object {
        /** 每个心跳角色最多记住多少个会话（有界，防止伪造会话撑爆内存）。 */
        const val MAX_PRESENCE_SESSIONS: Int = 8

        private val HEARTBEAT_ROLES = listOf(PeerRole.APP, PeerRole.HOOK)

        /** 这些阶段拥有通话且预期隔离；每轮都会读回系统隔离状态。 */
        private val AUDIO_OWNER_PHASES = setOf(
            SupervisorPhase.ARMED,
            SupervisorPhase.ANSWER_PENDING,
            SupervisorPhase.ACTIVE,
            SupervisorPhase.HANDOFF_PENDING,
        )

        /** 隔离读回不可达：本次不能打开音频，但不是“隔离丢失”的证据。 */
        const val ERROR_ISOLATION_UNVERIFIED: String = "microphone_isolation_unverified"

        /** 可恢复阻塞的轮询重试间隔；与 500 ms 轮询解耦，避免无限重复角色通道往返。 */
        internal const val RECOVERY_RETRY_INTERVAL_MS: Long = 1_000L

        /**
         * 可以由“系统确认未隔离”清除的阻塞集合。
         *
         * 终止未确认、特权终止不可用、状态写失败等阻塞绝不在其中：它们与麦克风隔离
         * 无关，不能用一次隔离读回就当成已解决。
         */
        internal val RECOVERY_RETRY_BLOCKERS: Set<String> = setOf(
            BLOCKER_ISOLATION_UNAVAILABLE,
            BLOCKER_ISOLATION_LOST,
            BLOCKER_RESTORE_FAILED,
            SupervisorBootRecovery.BLOCK_RESTORE_FAILED,
            SupervisorBootRecovery.BLOCK_STILL_ISOLATED,
            SHUTDOWN_RESTORE_FAILED,
        )

        const val BLOCKER_ISOLATION_UNAVAILABLE: String = "microphone_isolation_unavailable"
        const val BLOCKER_ISOLATION_LOST: String = "microphone_isolation_lost"
        const val BLOCKER_RESTORE_FAILED: String = "microphone_restore_failed"
        const val BLOCKER_PRIVILEGED_TERMINATION: String = "privileged_termination_unsupported"
        const val BLOCKER_TERMINATION_UNCONFIRMED: String = "termination_unconfirmed"
        const val BLOCKER_STATE_WRITE_FAILED: String = "supervision_state_write_failed"

        /** 优雅关闭的终止 + 断开确认总期限（spec §8；模块停用不能无限等待）。 */
        const val SHUTDOWN_TERMINATION_DEADLINE_MS: Long = 5_000L

        /** 关闭等待期间检查断开事实的间隔。 */
        internal const val SHUTDOWN_DISCONNECT_POLL_MS: Long = 50L

        /** 终止已尝试但期限内无法确认断开：保持隔离、失败关闭。 */
        const val SHUTDOWN_CALL_UNRESOLVED: String = "shutdown_call_unresolved"

        /** 已确认非 AI 所有但麦克风恢复失败：BLOCKED，显式上报。 */
        const val SHUTDOWN_RESTORE_FAILED: String = "shutdown_microphone_restore_failed"

        /** 关闭路径使用的故障原因码（写入 lastFault/状态文件）。 */
        private const val SHUTDOWN_REASON: String = "bridge_shutdown"
    }
}
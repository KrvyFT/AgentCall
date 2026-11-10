package org.krvyft.agentcall.core.session

import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.SessionEffect
import org.krvyft.agentcall.core.model.SessionEvent
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState
import org.krvyft.agentcall.core.rules.RuleMatcher

/**
 * 单写者会话状态机：输入事件与 nowMs，输出声明式副作用，不读取系统时钟、不接触 Android。
 *
 * 状态路径按 spec §8：IDLE → ELIGIBLE → PREPARING → ANSWER_PENDING → ACTIVE_AI →
 * HANDOFF/ENDING → ENDED。几处刻意的取舍：
 *
 * - 构造时冻结一份 [AgentConfig] 快照：热更新只对下一通电话生效（下一通由调用方用新配置
 *   新建 reducer）；只有“总开关被关闭”通过 [onConfigChanged] 立即作用于本会话。
 * - 名单命中后先进入 ELIGIBLE；拿到 APK READY 握手后在同一次裁决里走完 PREPARING 准备步骤
 *   （声明 AcquireLease/IsolateMicrophone）并进入 ANSWER_PENDING，倒计时从这一刻起算。
 *   准备副作用必须按顺序先于 SendCommand(Answer) 执行（spec §8）。
 * - 最长 AI 通话时长从第一次观察到 ACTIVE 起算，且只终止仍归 AI 的通话；用户已接管
 *   （HANDOFF）或租约已撤销的会话不再挂断。
 * - ACTIVE/HOLDING 等快照只描述事实；reducer 自行推导状态，外部不得直接改写。
 *
 * 副作用列表是有序的：调用方必须按顺序执行，租约撤销必须晚于需要租约的挂断命令。
 */
class SessionReducer(
    val id: SessionId,
    private val config: AgentConfig,
) {

    var state: SessionState = SessionState.IDLE
        private set

    private var ready = false
    private var eligible = false
    private var answerStartedAt: Long? = null
    private var answerIssued = false
    private var activeSince: Long? = null

    /** AI 是否仍拥有这通电话（已接听且未交还）。决定超时/故障能否挂断。 */
    private var aiOwnsCall = false
    private var leaseHeld = false
    private var isolated = false
    private var conversationRunning = false
    private var audioFailureAt: Long? = null

    private val lastHeartbeatAt = mutableMapOf<PeerRole, Long>()
    private val lostPeers = mutableSetOf<PeerRole>()

    /**
     * 处理一个事件。[nowMs] 是由调用方提供的单调时间；reducer 不读取任何时钟。
     *
     * 传入的 [SessionEvent.StateChanged] 快照必须与本会话 [id] 一致，旧进程代次或别的
     * callId 的快照会被忽略（stale 快照不产生任何命令）。
     */
    fun reduce(event: SessionEvent, nowMs: Long): List<SessionEffect> = when (event) {
        is SessionEvent.StateChanged -> onStateChanged(event.snapshot, nowMs)
        SessionEvent.Tick -> onTick(nowMs)
        is SessionEvent.ReadyChanged -> onReadyChanged(event.ready, nowMs)
        is SessionEvent.Takeover -> onTakeover()
        is SessionEvent.PeerLost -> {
            lostPeers += event.role
            onPeerLost()
        }
    }

    /**
     * 记录某个对端的心跳。只有记录过的对端才会在 [SessionEvent.Tick] 里按
     * [SessionLimits.HEARTBEAT_TIMEOUT_MS] 判定失联。
     */
    fun heartbeat(role: PeerRole, nowMs: Long) {
        lastHeartbeatAt[role] = nowMs
    }

    /**
     * 音频建立失败：停止 AI、保持隔离、提示人工接管，并在
     * [SessionLimits.FAILURE_HANDOFF_WINDOW_MS] 内等待接管，超时由 Tick 终止 AI 通话。
     *
     * 只在 AI 仍拥有通话时有效；重复上报是幂等的。
     */
    fun onAudioFailure(nowMs: Long): List<SessionEffect> {
        if (state == SessionState.IDLE || state == SessionState.ENDED || state == SessionState.ENDING) {
            return emptyList()
        }
        if (!aiOwnsCall) return emptyList()
        if (audioFailureAt != null) return emptyList()

        val effects = mutableListOf<SessionEffect>()
        if (conversationRunning) {
            effects += SessionEffect.StopConversation
            conversationRunning = false
        }
        if (!isolated) {
            effects += SessionEffect.IsolateMicrophone
            isolated = true
        }
        effects += SessionEffect.NotifyUser(NOTIFY_AUDIO_FAILURE)
        audioFailureAt = nowMs
        state = SessionState.HANDOFF
        return effects
    }

    /**
     * 配置热更新。只有“总开关关闭”立即作用于本会话：终止仍由 AI 托管的通话，或撤销
     * 待执行的接听；其余字段只影响下一通电话（下一通使用新的配置快照）。
     */
    fun onConfigChanged(next: AgentConfig): List<SessionEffect> {
        if (state == SessionState.IDLE || state == SessionState.ENDED || state == SessionState.ENDING) {
            return emptyList()
        }
        if (next.enabled) return emptyList()
        return if (aiOwnsCall) terminateOwnedCall() else handBack()
    }

    private fun onStateChanged(snapshot: CallSnapshot, nowMs: Long): List<SessionEffect> {
        // 旧进程代次/其它 callId 的快照与本次会话无关，绝不能触发命令。
        if (snapshot.id != id) return emptyList()
        if (state == SessionState.ENDED) return emptyList()
        return when (snapshot.state) {
            CallState.RINGING -> onRinging(snapshot, nowMs)
            CallState.ACTIVE -> onActive(nowMs)
            CallState.HOLDING -> onHolding()
            CallState.DISCONNECTED -> onDisconnected()
        }
    }

    private fun onRinging(snapshot: CallSnapshot, nowMs: Long): List<SessionEffect> {
        // 重复 RINGING 回调（父/子类重复 Hook）不得重置倒计时或重复准备。
        if (state != SessionState.IDLE) return emptyList()
        if (!RuleMatcher.matches(config, snapshot)) return emptyList()

        eligible = true
        state = SessionState.ELIGIBLE
        if (!ready) return emptyList()
        return startCountdown(nowMs)
    }

    private fun onReadyChanged(ready: Boolean, nowMs: Long): List<SessionEffect> {
        this.ready = ready
        if (!ready) return emptyList()
        if (state != SessionState.ELIGIBLE || !eligible) return emptyList()
        return startCountdown(nowMs)
    }

    /** 走完 PREPARING 准备步骤（声明租约与麦克风隔离）并进入接听倒计时；基准点是本次 nowMs。 */
    private fun startCountdown(nowMs: Long): List<SessionEffect> {
        state = SessionState.ANSWER_PENDING
        answerStartedAt = nowMs
        leaseHeld = true
        isolated = true
        return listOf(
            SessionEffect.AcquireLease(id),
            SessionEffect.IsolateMicrophone,
        )
    }

    private fun onActive(nowMs: Long): List<SessionEffect> {
        if (state == SessionState.IDLE || state == SessionState.HANDOFF || state == SessionState.ENDING) {
            return emptyList()
        }
        if (state == SessionState.ACTIVE_AI) return emptyList()
        if (state == SessionState.ANSWER_PENDING && answerIssued) {
            state = SessionState.ACTIVE_AI
            if (activeSince == null) activeSince = nowMs
            if (conversationRunning) return emptyList()
            conversationRunning = true
            return listOf(SessionEffect.StartConversation)
        }
        // 本会话没有发出过接听命令却观察到 ACTIVE：用户先接听，交还人工。
        return handBack()
    }

    private fun onHolding(): List<SessionEffect> {
        // 通话被置为保持：已不再处于 RINGING，来源也不在本模块控制内，保守交还。
        if (state != SessionState.ELIGIBLE &&
            state != SessionState.ANSWER_PENDING &&
            state != SessionState.ACTIVE_AI
        ) {
            return emptyList()
        }
        return handBack()
    }

    private fun onDisconnected(): List<SessionEffect> {
        if (state == SessionState.ENDED) return emptyList()

        val effects = mutableListOf<SessionEffect>()
        if (conversationRunning) {
            effects += SessionEffect.StopConversation
            conversationRunning = false
        }
        if (leaseHeld) {
            effects += SessionEffect.RevokeLease(id)
            leaseHeld = false
        }
        if (isolated) {
            // 确认断开后才恢复人工路由与麦克风。
            effects += SessionEffect.RestoreMicrophone(id)
            isolated = false
        }
        aiOwnsCall = false
        audioFailureAt = null
        state = SessionState.ENDED
        return effects
    }

    private fun onTakeover(): List<SessionEffect> {
        if (state == SessionState.IDLE || state == SessionState.ENDED || state == SessionState.ENDING) {
            return emptyList()
        }
        // 人工接管一律按保守交还处理：取消待执行命令、撤销所有权、恢复人工音频。
        return handBack()
    }

    private fun onTick(nowMs: Long): List<SessionEffect> {
        if (state == SessionState.IDLE || state == SessionState.ENDED) return emptyList()

        // 1. 心跳失联优先于其它截止时间：先停音频、再故障终止自己持有租约的通话。
        val timedOut = lastHeartbeatAt.entries
            .firstOrNull { (role, at) -> role !in lostPeers && nowMs - at >= SessionLimits.HEARTBEAT_TIMEOUT_MS }
            ?.key
        if (timedOut != null) {
            lostPeers += timedOut
            return onPeerLost()
        }

        // 2. 音频故障后的人工接管窗口到期：仍未接管则终止 AI 通话。
        val failureAt = audioFailureAt
        if (failureAt != null && aiOwnsCall &&
            nowMs - failureAt >= SessionLimits.FAILURE_HANDOFF_WINDOW_MS
        ) {
            return terminateOwnedCall()
        }

        // 3. 最长 AI 通话时长：只终止仍归 AI 的通话，交还人工的不受影响。
        if (state == SessionState.ACTIVE_AI && aiOwnsCall) {
            val since = activeSince
            if (since != null && nowMs - since >= config.maxCallDurationMs) {
                return terminateOwnedCall()
            }
        }

        // 4. 接听倒计时到期：所有前置条件仍成立才发出唯一一次接听命令。
        if (state == SessionState.ANSWER_PENDING && !answerIssued && ready) {
            val startedAt = answerStartedAt
            if (startedAt != null && nowMs - startedAt >= config.answerDelayMs) {
                answerIssued = true
                aiOwnsCall = true
                return listOf(SessionEffect.SendCommand(CallCommand.Answer))
            }
        }

        return emptyList()
    }

    private fun onPeerLost(): List<SessionEffect> {
        if (state == SessionState.IDLE || state == SessionState.ENDED || state == SessionState.ENDING) {
            return emptyList()
        }
        // AI 已接听：停止音频并故障终止这通精确通话；还没接听则交还人工、保留原响铃。
        return if (aiOwnsCall) terminateOwnedCall() else handBack()
    }

    /**
     * 终止仍归 AI 的通话：停音频 → 下发挂断 → 撤销租约（挂断命令在租约仍有效时产生）。
     * 麦克风保持隔离，等 DISCONNECTED 快照确认后再恢复。
     */
    private fun terminateOwnedCall(): List<SessionEffect> {
        val effects = mutableListOf<SessionEffect>()
        if (conversationRunning) {
            effects += SessionEffect.StopConversation
            conversationRunning = false
        }
        effects += SessionEffect.SendCommand(CallCommand.Hangup)
        aiOwnsCall = false
        if (leaseHeld) {
            effects += SessionEffect.RevokeLease(id)
            leaseHeld = false
        }
        state = SessionState.ENDING
        return effects
    }

    /** 交还人工：取消 AI 音频与所有权，撤销租约后恢复人工路由。不产生任何通话命令。 */
    private fun handBack(): List<SessionEffect> {
        val effects = mutableListOf<SessionEffect>()
        if (conversationRunning) {
            effects += SessionEffect.StopConversation
            conversationRunning = false
        }
        aiOwnsCall = false
        audioFailureAt = null
        if (leaseHeld) {
            effects += SessionEffect.RevokeLease(id)
            leaseHeld = false
        }
        if (isolated) {
            effects += SessionEffect.RestoreMicrophone(id)
            isolated = false
        }
        state = SessionState.HANDOFF
        return effects
    }

    private companion object {
        const val NOTIFY_AUDIO_FAILURE = "audio_failure"
    }
}

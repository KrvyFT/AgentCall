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
 * - 名单命中后先进入 ELIGIBLE（名单命中但 APK READY 尚未到达的稳定停留态）。
 * - READY 确认后进入 PREPARING：这是接听前的准备/倒计时窗口。进入时按 spec §8 声明
 *   AcquireLease 与 IsolateMicrophone，倒计时基准点就是进入 PREPARING 的 nowMs；窗口内
 *   到期才下发唯一一次 SendCommand(Answer) 并转入 ANSWER_PENDING。
 * - ANSWER_PENDING 是 Answer 命令已下发、尚未观察到结果的窗口；ACTIVE 快照到达后才打开
 *   实际双向流（StartConversation）。
 * - 最长 AI 通话时长从第一次观察到 ACTIVE 起算，且只终止仍归 AI 的通话；用户已接管
 *   （HANDOFF）或租约已撤销的会话不再挂断。
 * - ACTIVE/HOLDING 等快照只描述事实；reducer 自行推导状态，外部不得直接改写。
 *
 * 总开关关闭（[onConfigChanged]）立即生效于每个状态：IDLE 时只置停用闩锁（没有通话要交还，
 * 之后任何 RINGING 都不得接管）；ELIGIBLE/PREPARING 撤销待执行接听并交还人工；ANSWER_PENDING
 * /ACTIVE_AI 终止自己已持有的通话。
 *
 * 已知失联的对端（[SessionEvent.PeerLost] 或心跳超时）会阻止接管：只要 [lostPeers] 非空，
 * 新的 RINGING 不会进入 ELIGIBLE，倒计时也不会下发 Answer；[heartbeat] 收到该角色的新心跳
 * 即视为恢复（证明对端重新在线）。
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

    /** 总开关关闭的闩锁：关闭立即生效，之后本会话不得再接管任何来电。 */
    private var disabled = false

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
     *
     * 新心跳同时意味着该角色恢复在线：如果它此前被 [SessionEvent.PeerLost] 或心跳超时标记为
     * 失联，这里解除标记，之后的 RINGING 才能重新进入接管流程。
     */
    fun heartbeat(role: PeerRole, nowMs: Long) {
        lastHeartbeatAt[role] = nowMs
        lostPeers -= role
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
     * 会话/远程 API 失败（ASR、LLM、TTS 超时或错误）：按 spec §8 立即终止仍归 AI 的通话。
     *
     * 与 [onAudioFailure] 不同，这里没有 [SessionLimits.FAILURE_HANDOFF_WINDOW_MS] 人工接管
     * 窗口：先按需求发出故障提示（是否有有效上行与缓存提示由调用方判定，不承诺无声通路能播放），
     * 再停音频、下发挂断并撤销租约，麦克风保持隔离直到 DISCONNECTED 确认。重复上报是幂等的。
     */
    fun onApiFailure(): List<SessionEffect> {
        if (!aiOwnsCall) return emptyList()
        return terminateOwnedCall(NOTIFY_API_FAILURE)
    }

    /**
     * 配置热更新。只有“总开关关闭”立即作用于本会话，其余字段只影响下一通电话（下一通使用
     * 新的配置快照）。
     *
     * 关闭在每个状态都有明确行为：
     * - IDLE：只置停用闩锁，不产生副作用（此刻没有通话要交还）；之后到达的 RINGING 不得接管。
     * - ELIGIBLE：交还人工（没有租约/隔离需要撤销），停在 HANDOFF。
     * - PREPARING：撤销待执行接听、撤销租约并恢复麦克风，停在 HANDOFF。
     * - ANSWER_PENDING / ACTIVE_AI（已持有通话）：立即终止仍归 AI 的通话并撤销租约。
     * - HANDOFF：仍持有通话（音频故障的人工接管窗口）则立即终止，不等待窗口到期；已交还则无副作用。
     * - ENDED / ENDING：只置闩锁，不重复终止。
     */
    fun onConfigChanged(next: AgentConfig): List<SessionEffect> {
        if (next.enabled) return emptyList()
        disabled = true
        if (state == SessionState.IDLE || state == SessionState.ENDED || state == SessionState.ENDING) {
            return emptyList()
        }
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
        // 总开关已关闭，或仍有对端处于失联未恢复：缺一项就保留原响铃，不参与接听。
        if (disabled || lostPeers.isNotEmpty()) return emptyList()
        if (!RuleMatcher.matches(config, snapshot)) return emptyList()

        eligible = true
        state = SessionState.ELIGIBLE
        if (!ready) return emptyList()
        return enterPreparing(nowMs)
    }

    private fun onReadyChanged(ready: Boolean, nowMs: Long): List<SessionEffect> {
        this.ready = ready
        if (!ready) return emptyList()
        if (state != SessionState.ELIGIBLE || !eligible) return emptyList()
        if (disabled || lostPeers.isNotEmpty()) return emptyList()
        return enterPreparing(nowMs)
    }

    /**
     * 进入 PREPARING 准备/倒计时窗口：进入时先准备好接听前置条件（租约与麦克风隔离），
     * 倒计时基准点是本次 nowMs；到期后才在 [onTick] 里下发 Answer 并转入 ANSWER_PENDING。
     */
    private fun enterPreparing(nowMs: Long): List<SessionEffect> {
        state = SessionState.PREPARING
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
            state != SessionState.PREPARING &&
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
        if (state == SessionState.IDLE ||
            state == SessionState.ENDED ||
            state == SessionState.ENDING
        ) {
            return emptyList()
        }

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

        // 4. PREPARING 倒计时到期：所有前置条件仍成立才发出唯一一次接听命令。
        if (state == SessionState.PREPARING && !answerIssued && ready &&
            !disabled && lostPeers.isEmpty()
        ) {
            val startedAt = answerStartedAt
            if (startedAt != null && nowMs - startedAt >= config.answerDelayMs) {
                answerIssued = true
                aiOwnsCall = true
                state = SessionState.ANSWER_PENDING
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
     * 终止仍归 AI 的通话：先给可选故障提示，再停音频 → 下发挂断 → 撤销租约
     * （挂断命令在租约仍有效时产生）。麦克风保持隔离，等 DISCONNECTED 快照确认后再恢复。
     */
    private fun terminateOwnedCall(notify: String? = null): List<SessionEffect> {
        val effects = mutableListOf<SessionEffect>()
        if (notify != null) {
            effects += SessionEffect.NotifyUser(notify)
        }
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
        const val NOTIFY_API_FAILURE = "api_failure"
    }
}

package org.krvyft.agentcall.bridge.supervision

import java.util.concurrent.ConcurrentHashMap
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.session.SessionLimits

/**
 * 每个角色最近一次出现时刻（Task 11Q）。
 *
 * 与 [Supervisor] 的按会话心跳分开：HOOK 的空闲存在性没有 SessionId，也不属于任何通话的
 * 存活判定。这里只回答“这个角色在窗口内出现过吗”，不改变所有权、阶段或租约。
 * 并发安全：控制面 worker 可能并发写入，读取发生在 status 投影。
 */
internal class RolePresenceWindow(
    private val clockMs: () -> Long,
    private val windowMs: Long = SessionLimits.HEARTBEAT_TIMEOUT_MS,
) {
    init {
        require(windowMs > 0L) { "windowMs must be positive" }
    }

    private val lastSeenMs = ConcurrentHashMap<PeerRole, Long>()

    /** 记录一次该角色出现（任何已鉴权请求都算，payload/session 不参与）。 */
    fun note(role: PeerRole) {
        lastSeenMs[role] = clockMs()
    }

    /** 该角色是否在窗口内出现过；从未出现或超过窗口都是 false（确定的不存在）。 */
    fun isPresent(role: PeerRole): Boolean {
        val at = lastSeenMs[role] ?: return false
        val elapsed = clockMs() - at
        // 时钟回拨不产生“未来时间”的假存在性。
        return elapsed >= 0L && elapsed <= windowMs
    }
}

/** 控制面诊断用的监督状态（脱敏，不含 PCM/号码）。 */
data class SupervisionRpcStatus(
    val phase: String,
    val owned: Boolean,
    val microphoneIsolated: Boolean,
    val blockedReason: String?,
    val lastFault: String?,
    /**
     * HOOK 角色是否在存在性窗口（[RolePresenceWindow] / SessionLimits 的
     * HEARTBEAT_TIMEOUT_MS，默认 5000 ms）内出现过。
     *
     * Task 11Q 起来源是**任何已鉴权 HOOK 请求**的最近时刻（含无会话的 hook.presence），
     * 不再依赖“有存活会话才发心跳”。只反映真实存在性，不发起任何远程调用，也不改变
     * 监督所有权/阶段。
     */
    val hookConnected: Boolean,
)

/**
 * 控制面接入监督角色的窄接口。
 *
 * 默认实现全部 fail closed（拒绝/不动作），方便控制面单元测试注入；生产实现是
 * [SupervisorRpcPort]，由 BridgeRuntime 唯一装配（C1）。
 */
open class SupervisionRpcPort {

    open fun heartbeat(id: SessionId, role: PeerRole) = Unit

    /**
     * 记录一次角色出现（Task 11Q）：由控制面在**每个已鉴权请求**上调用，HOOK 的
     * hook.presence 因此没有会话也能刷新存在性。默认 no-op（未接线/测试端口不需要）。
     */
    open fun notePresence(role: PeerRole) = Unit

    open suspend fun prepareAnswer(id: SessionId): CommandResult =
        CommandResult.Rejected(RejectReason.UNSUPPORTED)

    open suspend fun onCallActive(id: SessionId): CommandResult =
        CommandResult.Rejected(RejectReason.STALE_SESSION)

    open suspend fun onCallDisconnected(id: SessionId) = Unit

    open suspend fun onManualTakeover(id: SessionId): TakeoverOutcome = TakeoverOutcome.NotOwned

    open fun recordSnapshotState(id: SessionId, state: CallState?) = Unit

    open fun recordCommandResult(id: SessionId, command: CallCommand, result: CommandResult) = Unit

    open fun status(): SupervisionRpcStatus? = null
}

/** [SupervisionRpcPort] 的生产实现：把控制面请求交给真实 [Supervisor] 与 [HookCallInbox]。 */
class SupervisorRpcPort(
    private val supervisor: Supervisor,
    private val inbox: HookCallInbox,
    clockMs: () -> Long = { System.currentTimeMillis() },
) : SupervisionRpcPort() {

    /** HOOK 空闲存在性窗口；只用于 status 投影，不参与监督状态机。 */
    private val presence = RolePresenceWindow(clockMs)

    override fun heartbeat(id: SessionId, role: PeerRole) = supervisor.heartbeat(id, role)

    override fun notePresence(role: PeerRole) = presence.note(role)

    override suspend fun prepareAnswer(id: SessionId): CommandResult = supervisor.prepareAnswer(id)

    override suspend fun onCallActive(id: SessionId): CommandResult = supervisor.onCallActive(id)

    override suspend fun onCallDisconnected(id: SessionId) {
        supervisor.onCallDisconnected(id)
    }

    override suspend fun onManualTakeover(id: SessionId): TakeoverOutcome = supervisor.onManualTakeover(id)

    override fun recordSnapshotState(id: SessionId, state: CallState?) = inbox.recordState(id, state)

    override fun recordCommandResult(id: SessionId, command: CallCommand, result: CommandResult) =
        inbox.recordResult(id, command, result)

    override fun status(): SupervisionRpcStatus {
        val snapshot = supervisor.snapshot()
        return SupervisionRpcStatus(
            phase = snapshot.phase.name,
            owned = snapshot.ownedSession != null,
            microphoneIsolated = snapshot.microphoneIsolated,
            blockedReason = snapshot.blockedReason,
            lastFault = snapshot.lastFault,
            // Task 11Q：任何 HOOK 请求（含无会话的 hook.presence）都刷新存在性；
            // 不再要求“有存活会话”，否则空闲 Hook 永远显示未连接。
            hookConnected = presence.isPresent(PeerRole.HOOK),
        )
    }
}

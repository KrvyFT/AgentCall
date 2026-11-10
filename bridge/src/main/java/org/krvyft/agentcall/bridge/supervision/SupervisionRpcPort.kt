package org.krvyft.agentcall.bridge.supervision

import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/** 控制面诊断用的监督状态（脱敏，不含 PCM/号码）。 */
data class SupervisionRpcStatus(
    val phase: String,
    val owned: Boolean,
    val microphoneIsolated: Boolean,
    val blockedReason: String?,
    val lastFault: String?,
)

/**
 * 控制面接入监督角色的窄接口。
 *
 * 默认实现全部 fail closed（拒绝/不动作），方便控制面单元测试注入；生产实现是
 * [SupervisorRpcPort]，由 BridgeRuntime 唯一装配（C1）。
 */
open class SupervisionRpcPort {

    open fun heartbeat(id: SessionId, role: PeerRole) = Unit

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
) : SupervisionRpcPort() {

    override fun heartbeat(id: SessionId, role: PeerRole) = supervisor.heartbeat(id, role)

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
        )
    }
}

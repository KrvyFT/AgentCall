package org.krvyft.agentcall.bridge.supervision

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.audio.AudioFailureCode
import org.krvyft.agentcall.bridge.audio.AudioLeaseCheck
import org.krvyft.agentcall.bridge.audio.AudioUnavailableException
import org.krvyft.agentcall.bridge.audio.AudioWorker
import org.krvyft.agentcall.bridge.audio.BridgeProcessRole
import org.krvyft.agentcall.bridge.audio.MicrophoneIsolation
import org.krvyft.agentcall.bridge.audio.ProcessFaultDecision
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.core.session.LeaseRegistry
import org.krvyft.agentcall.core.session.SessionLimits

/**
 * 监督端故障路径测试（spec §8）。
 *
 * 全部使用注入的虚拟时钟与假音频/通话/Telecom 端口：没有设备时不能证明真实特权终止，
 * 但可以证明监督端的顺序与边界——先停音频、保持隔离、只终止自己持有租约的精确通话，
 * 断开确认前不恢复麦克风。
 */
class SupervisorTest {

    private val session = SessionId("epoch-1", "call-a")
    private val other = SessionId("epoch-1", "call-b")
    private val format = AudioFormatSpec(16_000)

    private inner class Fixture(
        permissionGranted: Boolean = true,
        identityAvailable: Boolean = true,
        authorized: Boolean = true,
        stateSink: (SupervisionPersistedState) -> Unit = {},
    ) {
        val clock = FakeClock()
        val leases = LeaseRegistry()
        val isolation = FakeIsolation()
        val bridge = FakeBridge()
        val calls = FakeCallControl()
        val permission = FakePermission(permissionGranted)
        val identity = FakeIdentity(
            available = identityAvailable,
            verified = VerifiedCallIdentity(session, "telecom-call-1"),
        )
        val termination = FakeTermination()
        val emergency = EmergencyCallControl(permission, identity, termination)
        val worker = AudioWorker(bridge, isolation, clock, AudioLeaseCheck { leases.isLive(it) })
        val supervisor = Supervisor(
            clock = clock,
            audio = worker,
            calls = calls,
            emergency = emergency,
            leases = leases,
            authorization = AutoAnswerAuthorization { authorized },
            role = BridgeProcessRole.SUPERVISOR,
            stateSink = stateSink,
        )

        fun heartbeat() {
            // 心跳时间必须来自注入时钟：写死 0L 会让窗口测试在 5000 ms 后同时触发
            // “心跳失效”终止路径，掩盖它真正要验证的人工接管窗口。
            val now = clock.nowMs()
            supervisor.heartbeat(session, PeerRole.APP, now)
            supervisor.heartbeat(session, PeerRole.HOOK, now)
        }
    }

    @Test
    fun prepareAnswerRequiresBothPeerHeartbeatsAndAuthorization() = runBlocking {
        val f = Fixture()
        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), f.supervisor.prepareAnswer(session))

        f.supervisor.heartbeat(session, PeerRole.APP, 0L)
        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), f.supervisor.prepareAnswer(session))

        f.supervisor.heartbeat(session, PeerRole.HOOK, 0L)
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))
        assertTrue(f.leases.isLive(session))
        assertTrue(f.worker.microphoneIsolated)

        val denied = Fixture(authorized = false)
        denied.heartbeat()
        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), denied.supervisor.prepareAnswer(session))
        assertTrue(denied.calls.commands.isEmpty())
    }

    @Test
    fun heartbeatTimeoutTerminatesOnlyTheOwnedCall() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))
        assertEquals(listOf(session to CallCommand.Answer), f.calls.commands)

        f.clock.advance(SessionLimits.HEARTBEAT_TIMEOUT_MS + 1)
        val actions = f.supervisor.poll()

        assertTrue(actions.any { it is SupervisorAction.TerminateCall })
        assertEquals(
            listOf(session to CallCommand.Answer, session to CallCommand.Hangup),
            f.calls.commands,
        )
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)
        assertEquals(SupervisorPhase.TERMINATING, f.supervisor.snapshot().phase)
        assertTrue(actions.any { it is SupervisorAction.StopAudioKeepingIsolation })
    }

    @Test
    fun terminatingCallCannotBeReplacedByAnotherAnswer() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))

        f.clock.advance(SessionLimits.HEARTBEAT_TIMEOUT_MS + 1)
        f.supervisor.poll()
        assertEquals(SupervisorPhase.TERMINATING, f.supervisor.snapshot().phase)

        // 上一通话尚未确认断开：新通话不得覆盖所有权，也不得产生 Answer。
        val replacement = f.supervisor.prepareAnswer(other)
        assertEquals(CommandResult.Rejected(RejectReason.NOT_OWNED), replacement)
        assertFalse(f.calls.commands.any { it.first == other })
        assertEquals(session, f.supervisor.snapshot().ownedSession)
        assertTrue(f.worker.microphoneIsolated)

        // 旧通话确认断开后，麦克风恢复且所有权清空。
        val disconnected = f.supervisor.onCallDisconnected(session)
        assertTrue(disconnected.any { it is SupervisorAction.RestoreMicrophone })
        assertFalse(f.worker.microphoneIsolated)
    }

    @Test
    fun closedSupervisorIsNotReady() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))

        f.supervisor.close()

        assertFalse(f.supervisor.isReady())
        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), f.supervisor.prepareAnswer(other))
    }

    @Test
    fun revokedLeaseCannotDisconnect() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))
        assertTrue(f.leases.revoke(session))

        val result = f.supervisor.terminateOwned(session, "test")

        assertEquals(TerminationOutcome.NO_LEASE, result.outcome)
        assertFalse(f.calls.commands.any { it.second == CallCommand.Hangup })
        assertTrue(f.worker.microphoneIsolated)

        // 心跳失效也不能补发挂断：租约已撤销。
        f.clock.advance(SessionLimits.HEARTBEAT_TIMEOUT_MS + 1)
        f.supervisor.poll()
        assertFalse(f.calls.commands.any { it.second == CallCommand.Hangup })
        assertEquals(0, f.isolation.restoreCalls)
    }

    @Test
    fun audioFailureKeepsMicrophoneIsolated() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))
        f.bridge.failOpen = true

        val active = f.supervisor.onCallActive(session, format)
        assertTrue(active is CommandResult.Failed)
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)
        assertFalse(f.calls.commands.any { it.second == CallCommand.Hangup })

        // 10000 ms 人工接管窗口内不终止。每轮 poll 前刷新对端心跳：心跳失效是另一个
        // 独立终止触发条件（spec §8），不能让它抢在窗口到期前误触发。
        f.clock.advance(SessionLimits.FAILURE_HANDOFF_WINDOW_MS - 1)
        f.heartbeat()
        assertFalse(f.supervisor.poll().any { it is SupervisorAction.TerminateCall })
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)

        f.clock.advance(2)
        f.heartbeat()
        val expired = f.supervisor.poll()
        assertTrue(expired.any { it is SupervisorAction.TerminateCall })
        assertTrue(f.calls.commands.any { it.second == CallCommand.Hangup })

        // 已发出挂断但尚未确认断开：仍然隔离，不能恢复麦克风。
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)

        val disconnected = f.supervisor.onCallDisconnected(session)
        assertTrue(disconnected.any { it is SupervisorAction.RestoreMicrophone })
        assertFalse(f.worker.microphoneIsolated)
        assertEquals(1, f.isolation.restoreCalls)
    }

    @Test
    fun manualTakeoverDuringFailureWindowRestoresMicrophoneWithoutHangup() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        f.bridge.failOpen = true
        f.supervisor.onCallActive(session, format)
        f.clock.advance(9_000)

        val outcome = f.supervisor.onManualTakeover(session)

        assertEquals(TakeoverOutcome.Confirmed, outcome)
        assertEquals(1, f.isolation.restoreCalls)
        assertFalse(f.worker.microphoneIsolated)
        assertFalse(f.calls.commands.any { it.second == CallCommand.Hangup })

        // 接管后窗口到期也不得再终止（AI 已不拥有该通话）。
        f.clock.advance(SessionLimits.FAILURE_HANDOFF_WINDOW_MS)
        f.supervisor.poll()
        assertFalse(f.calls.commands.any { it.second == CallCommand.Hangup })
    }

    @Test
    fun emergencyFallbackIsUsedWhenHookHangupFails() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        f.calls.hangupResult = CommandResult.Failed("hook_unreachable", "hook process is gone")

        f.clock.advance(SessionLimits.HEARTBEAT_TIMEOUT_MS + 1)
        f.supervisor.poll()

        assertEquals(1, f.termination.calls.size)
        assertEquals(session, f.termination.calls[0].id)
        assertEquals(SupervisorPhase.TERMINATING, f.supervisor.snapshot().phase)
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)
    }

    @Test
    fun blockedEmergencyPathBlocksAutoAnswerBeforeIsolationOrLease() = runBlocking {
        val f = Fixture(permissionGranted = false)
        f.heartbeat()

        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), f.supervisor.prepareAnswer(session))
        assertTrue(f.calls.commands.isEmpty())
        assertEquals(0, f.isolation.isolateCalls)
        assertFalse(f.leases.isLive(session))
        assertFalse(f.supervisor.isReady())
    }

    @Test
    fun failedAnswerRestoresMicrophoneAndReleasesLease() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.calls.answerResult = CommandResult.Rejected(RejectReason.NOT_RINGING)

        assertEquals(CommandResult.Rejected(RejectReason.NOT_RINGING), f.supervisor.prepareAnswer(session))

        assertFalse(f.leases.isLive(session))
        assertEquals(1, f.isolation.restoreCalls)
        assertFalse(f.worker.microphoneIsolated)
        assertFalse(f.calls.commands.any { it.second == CallCommand.Hangup })
    }

    @Test
    fun foreignDisconnectNeverRestoresOwnedMicrophone() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        assertEquals(CommandResult.Accepted, f.supervisor.onCallActive(session, format))

        val actions = f.supervisor.onCallDisconnected(other)

        assertTrue(actions.isEmpty())
        assertEquals(0, f.isolation.restoreCalls)
        assertTrue(f.worker.microphoneIsolated)
        assertTrue(f.leases.isLive(session))
    }

    @Test
    fun terminateOwnedIgnoresForeignSessions() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)

        val result = f.supervisor.terminateOwned(other, "foreign")

        assertEquals(TerminationOutcome.NOT_OWNED, result.outcome)
        assertTrue(f.calls.commands.none { it.second == CallCommand.Hangup })
    }

    @Test
    fun audioProcessFaultRestartsAreBoundedThenTerminate() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)

        repeat(3) { index ->
            assertEquals(
                ProcessFaultDecision.RestartAllowed(index + 1),
                f.supervisor.onProcessFault(BridgeProcessRole.AUDIO_WORKER, "audio_worker_died"),
            )
        }
        val exhausted = f.supervisor.onProcessFault(BridgeProcessRole.AUDIO_WORKER, "audio_worker_died")

        assertEquals(ProcessFaultDecision.RestartExhausted(3), exhausted)
        assertTrue(f.calls.commands.any { it.second == CallCommand.Hangup })
        assertTrue(f.worker.microphoneIsolated)
    }

    /**
     * 新增重要缺陷 2（监督端职责）：所有权标记必须独立于 ownedSession 持久化，
     * block()/shutdown() 都不能把它清掉；只有确认断开/确认未接听/确认交还才允许清除。
     */
    @Test
    fun ownershipMarkerIsStickyAcrossBlockAndShutdown() = runBlocking {
        val states = mutableListOf<SupervisionPersistedState>()
        val f = Fixture(stateSink = { states += it })
        f.heartbeat()
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))
        assertTrue("取得所有权后必须持久化“可能仍拥有通话”标记", states.last().ownedCallMayBeLive)

        f.supervisor.block("test_block")
        assertTrue("block() 不得清掉所有权标记", states.last().ownedCallMayBeLive)

        f.supervisor.shutdown()
        assertTrue("关闭进程同样不能假定通话已结束", states.last().ownedCallMayBeLive)
    }

    @Test
    fun ownershipMarkerIsClearedOnlyByConfirmedDisconnect() = runBlocking {
        val states = mutableListOf<SupervisionPersistedState>()
        val f = Fixture(stateSink = { states += it })
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        assertEquals(CommandResult.Accepted, f.supervisor.onCallActive(session, format))

        f.supervisor.onCallDisconnected(session)

        assertFalse("确认断开后必须清除所有权标记", states.last().ownedCallMayBeLive)
    }

    @Test
    fun audioStopNeverRestoresMicrophoneByItself() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)

        f.worker.stopKeepingIsolation()

        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)
    }

    /**
     * 阶段 B：控制面 status 的 hookConnected 必须来自真实的 HOOK 心跳存在性，
     * 而不是固定值；窗口是 SessionLimits.HEARTBEAT_TIMEOUT_MS（5000 ms）。
     */
    @Test
    fun rpcStatusReportsHookPresenceOnlyWithinTheHeartbeatWindow() {
        val f = Fixture()
        val port = SupervisorRpcPort(f.supervisor, HookCallInbox())

        assertFalse("没有任何心跳时必须报未连接", port.status().hookConnected)

        f.supervisor.heartbeat(session, PeerRole.APP, f.clock.nowMs())
        assertFalse("只有 APP 心跳不能把 Hook 标成已连接", port.status().hookConnected)

        f.supervisor.heartbeat(session, PeerRole.HOOK, f.clock.nowMs())
        assertTrue(port.status().hookConnected)

        f.clock.advance(SessionLimits.HEARTBEAT_TIMEOUT_MS + 1)
        assertFalse("超过 5000 ms 窗口后必须回落为未连接", port.status().hookConnected)
    }
}

/**
 * 独立特权终止路径的身份/权限裁决测试：不能核对身份或缺少权限时，必须明确不支持，
 * 并且该状态阻止自动接听。
 */
class EmergencyCallControlTest {

    private val session = SessionId("epoch-1", "call-a")
    private val other = SessionId("epoch-1", "call-b")

    private fun control(
        granted: Boolean = true,
        available: Boolean = true,
        verified: VerifiedCallIdentity? = VerifiedCallIdentity(session, "telecom-call-1"),
        termination: FakeTermination = FakeTermination(),
    ): Triple<EmergencyCallControl, FakePermission, FakeTermination> {
        val permission = FakePermission(granted)
        val identity = FakeIdentity(available, verified)
        return Triple(EmergencyCallControl(permission, identity, termination), permission, termination)
    }

    @Test
    fun missingPermissionReturnsUnsupportedAndBlocksAutoAnswer() = runBlocking {
        val termination = FakeTermination()
        val (emergency, _, _) = control(granted = false, termination = termination)

        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), emergency.disconnectOwned(session))
        assertTrue(termination.calls.isEmpty())
        assertTrue(emergency.blocksAutoAnswer())
    }

    @Test
    fun unverifiableIdentityReturnsUnsupportedAndBlocksAutoAnswer() = runBlocking {
        val termination = FakeTermination()
        val (emergency, _, _) = control(available = false, termination = termination)

        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), emergency.disconnectOwned(session))
        assertTrue(termination.calls.isEmpty())
        assertTrue(emergency.blocksAutoAnswer())
    }

    @Test
    fun mismatchedIdentityIsRejectedWithoutTermination() = runBlocking {
        val termination = FakeTermination()
        val (emergency, _, _) = control(
            verified = VerifiedCallIdentity(other, "telecom-call-2"),
            termination = termination,
        )

        assertEquals(CommandResult.Rejected(RejectReason.STALE_SESSION), emergency.disconnectOwned(session))
        assertTrue(termination.calls.isEmpty())
    }

    @Test
    fun verifiedIdentityTerminatesExactlyTheOwnedCall() = runBlocking {
        val termination = FakeTermination()
        val (emergency, _, _) = control(termination = termination)

        assertEquals(CommandResult.Accepted, emergency.disconnectOwned(session))
        assertEquals(listOf(VerifiedCallIdentity(session, "telecom-call-1")), termination.calls)
        assertFalse(emergency.blocksAutoAnswer())
    }
}

// ---------------------------------------------------------------------------- 测试替身

private class FakeClock(var now: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = now

    fun advance(byMs: Long) {
        now += byMs
    }
}

private class FakeIsolation : MicrophoneIsolation {
    var isolated = false
    var isolateCalls = 0
    var restoreCalls = 0

    override suspend fun isolate(): Boolean {
        isolateCalls++
        isolated = true
        return true
    }

    override suspend fun restore(): Boolean {
        restoreCalls++
        isolated = false
        return true
    }

    override fun isIsolated(): Boolean = isolated
}

private class FakeBridge : AudioBridge {
    var failOpen = false
    var openCalls = 0

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        openCalls++
        if (failOpen) {
            throw AudioUnavailableException(
                code = "audio_downlink_unsupported",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "no call extraction downlink",
            )
        }
        return FakeSession()
    }
}

private class FakeSession : AudioSession {
    var stopped = false

    override val downlink: Flow<PcmChunk> = emptyFlow()

    override suspend fun writeUplink(audio: PcmAudio) = Unit

    override suspend fun stop() {
        stopped = true
    }
}

private class FakeCallControl : CallControl {
    val commands = mutableListOf<Pair<SessionId, CallCommand>>()
    var answerResult: CommandResult = CommandResult.Accepted
    var hangupResult: CommandResult = CommandResult.Accepted

    override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
        commands += id to command
        return when (command) {
            CallCommand.Answer -> answerResult
            CallCommand.Hangup -> hangupResult
        }
    }
}

private class FakePermission(var granted: Boolean) : TerminationPermission {
    override fun isGranted(): Boolean = granted
}

private class FakeIdentity(
    var available: Boolean,
    var verified: VerifiedCallIdentity?,
) : CallIdentityVerifier {
    override fun isAvailable(): Boolean = available

    override suspend fun verify(id: SessionId): VerifiedCallIdentity? = if (available) verified else null
}

private class FakeTermination : PrivilegedTermination {
    val calls = mutableListOf<VerifiedCallIdentity>()

    override suspend fun disconnect(verified: VerifiedCallIdentity): CommandResult {
        calls += verified
        return CommandResult.Accepted
    }
}

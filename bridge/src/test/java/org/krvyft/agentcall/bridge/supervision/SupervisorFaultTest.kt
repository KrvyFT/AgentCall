package org.krvyft.agentcall.bridge.supervision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 * Task 5 修复轮 1 的故障路径测试（spec §8）。
 *
 * 这些测试只断言安全边界，不伪造设备证据：
 * - 心跳按 SessionId 归属，外来会话心跳不能把已拥有的旧通话“续命”；
 * - 接听结果不明确时不得恢复麦克风、不得丢弃所有权；
 * - 迟到的 ACTIVE / 重复 ACTIVE 不得重新打开音频或覆盖终止/阻塞状态；
 * - 撤销与终止串行化，撤销后绝不补发挂断；
 * - ACTIVE 期间隔离被外部解除时必须 fail closed；
 * - 同一轮 poll 不得重复终止同一会话。
 */
class SupervisorFaultTest {

    private val session = SessionId("epoch-1", "call-a")
    private val foreign = SessionId("epoch-2", "call-b")
    private val format = AudioFormatSpec(16_000)

    private inner class Fixture(
        permissionGranted: Boolean = true,
        authorized: Boolean = true,
        callControl: CallControl? = null,
    ) {
        val clock = FaultClock()
        val leases = LeaseRegistry()
        val isolation = FaultIsolation()
        val bridge = FaultBridge()
        val calls = FaultCallControl()
        val activeCalls: CallControl = callControl ?: calls
        val permission = FaultPermission(permissionGranted)
        val identity = FaultIdentity(verified = VerifiedCallIdentity(session, "telecom-call-1"))
        val termination = FaultTermination()
        val emergency = EmergencyCallControl(permission, identity, termination)
        val worker = AudioWorker(bridge, isolation, clock, AudioLeaseCheck { leases.isLive(it) })
        val supervisor = Supervisor(
            clock = clock,
            audio = worker,
            calls = activeCalls,
            emergency = emergency,
            leases = leases,
            authorization = AutoAnswerAuthorization { authorized },
            role = BridgeProcessRole.SUPERVISOR,
        )

        fun heartbeat(id: SessionId = session) {
            val now = clock.nowMs()
            supervisor.heartbeat(id, PeerRole.APP, now)
            supervisor.heartbeat(id, PeerRole.HOOK, now)
        }

    }

    @Test
    fun foreignSessionHeartbeatDoesNotKeepOwnedCallAlive() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        assertEquals(CommandResult.Accepted, f.supervisor.prepareAnswer(session))

        // 拥有的通话心跳停在 0 ms；另一个会话在 4000 ms 报告心跳。
        f.clock.advance(4_000)
        f.heartbeat(foreign)
        f.clock.advance(1_500)

        val actions = f.supervisor.poll()

        assertTrue(
            "已拥有的会话超过 5000 ms 没有自己的心跳时必须终止，不能被外来会话心跳续命",
            actions.any { it is SupervisorAction.TerminateCall },
        )
        assertEquals(listOf(session to CallCommand.Hangup), f.calls.commands.filter { it.second == CallCommand.Hangup })
        assertTrue(f.worker.microphoneIsolated)
    }

    @Test
    fun ambiguousAnswerFailureKeepsIsolationAndOwnership() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.calls.answerResult = CommandResult.Failed("answer_timeout", "no result from hook")

        val result = f.supervisor.prepareAnswer(session)

        assertTrue(result is CommandResult.Failed)
        // 响应丢失时无法证明未接听：必须保持隔离与所有权，等待核验/人工接管。
        assertEquals(0, f.isolation.restoreCalls)
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(session, f.supervisor.snapshot().ownedSession)
        assertEquals(SupervisorPhase.HANDOFF_PENDING, f.supervisor.snapshot().phase)
        assertTrue(f.leases.isLive(session))

        // 窗口到期仍终止精确会话；断开确认前不恢复。
        f.clock.advance(SessionLimits.FAILURE_HANDOFF_WINDOW_MS + 1)
        f.heartbeat()
        f.supervisor.poll()
        assertEquals(0, f.isolation.restoreCalls)
        assertTrue(f.calls.commands.any { it.second == CallCommand.Hangup })
    }

    @Test
    fun lateActiveAfterBlockedNeverReopensAudio() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        f.bridge.failOpen = true
        f.supervisor.onCallActive(session, format)
        // 终止失败（Hook 挂断失败 + 特权 Telecom 未授权）→ BLOCKED，但所有权仍被持有。
        f.calls.hangupResult = CommandResult.Failed("hook_unreachable", "hook process is gone")
        f.permission.granted = false
        f.clock.advance(SessionLimits.FAILURE_HANDOFF_WINDOW_MS + 1)
        f.heartbeat()
        f.supervisor.poll()
        assertEquals(SupervisorPhase.BLOCKED, f.supervisor.snapshot().phase)
        val opensBefore = f.bridge.openCalls

        val late = f.supervisor.onCallActive(session, format)

        assertTrue("BLOCKED 之后迟到的 ACTIVE 必须被拒绝", late is CommandResult.Rejected)
        assertEquals("迟到的 ACTIVE 不得重新打开音频", opensBefore, f.bridge.openCalls)
        assertTrue(f.worker.microphoneIsolated)
    }

    @Test
    fun duplicateActiveDoesNotReopenAudio() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        assertEquals(CommandResult.Accepted, f.supervisor.onCallActive(session, format))
        assertEquals(1, f.bridge.openCalls)

        val duplicate = f.supervisor.onCallActive(session, format)

        assertEquals(CommandResult.Accepted, duplicate)
        assertEquals("重复 ACTIVE 不得再次打开音频（避免句柄泄漏）", 1, f.bridge.openCalls)
    }

    @Test
    fun takeoverIsSerializedWithInFlightAnswer() = runBlocking {
        val calls = BlockingCallControl()
        val f = Fixture(callControl = calls)
        f.heartbeat()
        val gate = CompletableDeferred<Unit>()
        calls.answerGate = gate

        var answerResult: CommandResult? = null
        val prepare = launch(Dispatchers.Default) { answerResult = f.supervisor.prepareAnswer(session) }
        calls.answerEntered.await()
        val takeover = async(Dispatchers.Default) { f.supervisor.onManualTakeover(session) }
        delay(300)

        assertFalse("人工接管必须等待进行中的接听命令完成，不能与其交错", takeover.isCompleted)

        gate.complete(Unit)
        prepare.join()
        val outcome = takeover.await()

        assertEquals(CommandResult.Accepted, answerResult)
        assertEquals(TakeoverOutcome.Confirmed, outcome)
        val snapshot = f.supervisor.snapshot()
        assertEquals(SupervisorPhase.IDLE, snapshot.phase)
        assertNull(snapshot.ownedSession)
        assertEquals(1, f.isolation.restoreCalls)
        assertFalse(f.leases.isLive(session))
    }

    @Test
    fun externalIsolationLossDuringActiveFailsClosed() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        assertEquals(CommandResult.Accepted, f.supervisor.onCallActive(session, format))
        assertEquals(SupervisorPhase.ACTIVE, f.supervisor.snapshot().phase)

        // 系统侧隔离被别人解除，缓存的布尔值不会知道；必须重新读回。
        f.isolation.isolated = false

        val actions = f.supervisor.poll()

        assertTrue(
            "隔离读回失败必须阻止继续并终止 AI 通话",
            actions.any { it is SupervisorAction.BlockNewSessions },
        )
        assertTrue(actions.any { it is SupervisorAction.TerminateCall })
        assertEquals(0, f.isolation.restoreCalls)
        assertFalse(f.supervisor.isReady())
    }

    @Test
    fun handoffExpiryTerminatesOwnedCallOnlyOncePerPoll() = runBlocking {
        val f = Fixture()
        f.heartbeat()
        f.supervisor.prepareAnswer(session)
        f.bridge.failOpen = true
        f.supervisor.onCallActive(session, format)
        // Hook 挂断失败、特权终止未授权：终止不确认，所有权与租约保留。
        f.calls.hangupResult = CommandResult.Failed("hook_unreachable", "hook process is gone")
        f.permission.granted = false
        f.clock.advance(SessionLimits.HEARTBEAT_TIMEOUT_MS + 1)

        val actions = f.supervisor.poll()

        assertEquals(
            "同一轮 poll 只能下发一次挂断",
            1,
            f.calls.commands.count { it.second == CallCommand.Hangup },
        )
        assertTrue("终止未确认时必须阻止新会话", actions.any { it is SupervisorAction.BlockNewSessions })
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)
    }
}

/** 特权 Telecom 终止适配测试（I6）。 */
class PrivilegedTerminationFailClosedTest {

    private val session = SessionId("epoch-1", "call-a")

    @Test
    fun globalEndCallIsNeverInvokedAndUnsupportedIsReturned() = runBlocking {
        var providerCalls = 0
        val telecom = RecordingTelecom()
        val termination = ReflectiveTelecomTermination {
            providerCalls++
            telecom
        }

        val result = termination.disconnect(VerifiedCallIdentity(session, "telecom-call-1"))

        assertEquals(CommandResult.Rejected(RejectReason.UNSUPPORTED), result)
        assertEquals("不得调用全局 endCall 或任何 Telecom 终止入口", 0, providerCalls)
        assertEquals(0, telecom.endCallCalls)
    }

    private class RecordingTelecom {
        var endCallCalls = 0

        fun endCall() {
            endCallCalls++
        }
    }
}

// ---------------------------------------------------------------------------- 测试替身

private class FaultClock(var now: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = now

    fun advance(byMs: Long) {
        now += byMs
    }
}

private class FaultIsolation : MicrophoneIsolation {
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

private class FaultBridge : AudioBridge {
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
        return FaultSession()
    }
}

private class FaultSession : AudioSession {
    override val downlink: Flow<PcmChunk> = emptyFlow()

    override suspend fun writeUplink(audio: PcmAudio) = Unit

    override suspend fun stop() = Unit
}

private class FaultCallControl : CallControl {
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

private class BlockingCallControl : CallControl {
    val commands = mutableListOf<Pair<SessionId, CallCommand>>()
    var answerResult: CommandResult = CommandResult.Accepted
    var hangupResult: CommandResult = CommandResult.Accepted
    var answerGate: CompletableDeferred<Unit>? = null
    val answerEntered = CompletableDeferred<Unit>()

    override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
        commands += id to command
        if (command == CallCommand.Answer) {
            answerEntered.complete(Unit)
            answerGate?.await()
            return answerResult
        }
        return hangupResult
    }
}

private class FaultPermission(var granted: Boolean) : TerminationPermission {
    override fun isGranted(): Boolean = granted
}

private class FaultIdentity(var verified: VerifiedCallIdentity?) : CallIdentityVerifier {
    override fun isAvailable(): Boolean = true

    override suspend fun verify(id: SessionId): VerifiedCallIdentity? = verified
}

private class FaultTermination : PrivilegedTermination {
    val calls = mutableListOf<VerifiedCallIdentity>()

    override suspend fun disconnect(verified: VerifiedCallIdentity): CommandResult {
        calls += verified
        return CommandResult.Accepted
    }
}

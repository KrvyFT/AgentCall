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
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
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
 * I2：接听结果不明确（Failed，不是确定性 Rejected）时，必须用可核验的通话状态决定：
 * 只有确认“未接听/已断开”才恢复麦克风；确认 ACTIVE 则终止精确拥有的会话；无法核验时
 * 保持隔离并进入人工接管窗口。
 */
class SupervisorAmbiguousAnswerTest {

    private val session = SessionId("epoch-1", "call-a")
    private val format = AudioFormatSpec(16_000)

    private inner class Fixture(
        private val state: (SessionId) -> CallState?,
        restoreSucceeds: Boolean = true,
    ) {
        val clock = AmbClock()
        val leases = LeaseRegistry()
        val isolation = AmbIsolation(restoreSucceeds)
        val bridge = AmbBridge()
        val calls = AmbCalls()
        val emergency = EmergencyCallControl(
            AmbPermission(true),
            AmbIdentity(session),
            AmbTermination(),
        )
        val worker = AudioWorker(bridge, isolation, clock, AudioLeaseCheck { leases.isLive(it) })
        val supervisor = Supervisor(
            clock = clock,
            audio = worker,
            calls = calls,
            emergency = emergency,
            leases = leases,
            authorization = AutoAnswerAuthorization { true },
            role = BridgeProcessRole.SUPERVISOR,
            callState = CallStateProbe { id -> state(id) },
        )

        fun heartbeat() {
            supervisor.heartbeat(session, PeerRole.APP, clock.nowMs())
            supervisor.heartbeat(session, PeerRole.HOOK, clock.nowMs())
        }

        fun ambiguousAnswer() {
            calls.answerResult = CommandResult.Failed("answer_timeout", "no result from hook")
        }
    }

    @Test
    fun confirmedRingingAfterAmbiguousFailureRestoresMicrophone() = runBlocking {
        val f = Fixture(state = { CallState.RINGING })
        f.heartbeat()
        f.ambiguousAnswer()

        val result = f.supervisor.prepareAnswer(session)

        assertTrue(result is CommandResult.Failed)
        assertEquals(1, f.isolation.restoreCalls)
        assertFalse(f.worker.microphoneIsolated)
        assertNull(f.supervisor.snapshot().ownedSession)
        assertEquals(SupervisorPhase.IDLE, f.supervisor.snapshot().phase)
        assertFalse(f.leases.isLive(session))
    }

    @Test
    fun confirmedDisconnectedAfterAmbiguousFailureRestoresMicrophone() = runBlocking {
        val f = Fixture(state = { CallState.DISCONNECTED })
        f.heartbeat()
        f.ambiguousAnswer()

        f.supervisor.prepareAnswer(session)

        assertEquals(1, f.isolation.restoreCalls)
        assertNull(f.supervisor.snapshot().ownedSession)
    }

    @Test
    fun confirmedActiveAfterAmbiguousFailureTerminatesOwnedCallAndKeepsIsolation() = runBlocking {
        val f = Fixture(state = { CallState.ACTIVE })
        f.heartbeat()
        f.ambiguousAnswer()

        f.supervisor.prepareAnswer(session)

        assertEquals(listOf(session to CallCommand.Hangup), f.calls.commands.filter { it.second == CallCommand.Hangup })
        assertTrue(f.worker.microphoneIsolated)
        assertEquals("ACTIVE 说明响应丢失后电话可能已被 AI 接起，绝不能恢复麦克风", 0, f.isolation.restoreCalls)
    }

    @Test
    fun unknownStateAfterAmbiguousFailureKeepsIsolationAndOwnership() = runBlocking {
        val f = Fixture(state = { null })
        f.heartbeat()
        f.ambiguousAnswer()

        f.supervisor.prepareAnswer(session)

        assertEquals(session, f.supervisor.snapshot().ownedSession)
        assertEquals(SupervisorPhase.HANDOFF_PENDING, f.supervisor.snapshot().phase)
        assertTrue(f.worker.microphoneIsolated)
        assertEquals(0, f.isolation.restoreCalls)
    }

    @Test
    fun restoreFailureAfterConfirmedNonAnswerBlocksNewSessions() = runBlocking {
        val f = Fixture(state = { CallState.RINGING }, restoreSucceeds = false)
        f.heartbeat()
        f.ambiguousAnswer()

        f.supervisor.prepareAnswer(session)

        assertEquals(SupervisorPhase.BLOCKED, f.supervisor.snapshot().phase)
        assertFalse(f.supervisor.isReady())
        assertFalse(f.leases.isLive(session))
        assertNull(f.supervisor.snapshot().ownedSession)
    }
}

/** I7：隔离状态必须每次读回系统状态，不能只信自己缓存的布尔值。 */
class AudioIsolationReadbackTest {

    private val session = SessionId("epoch-1", "call-a")
    private val format = AudioFormatSpec(16_000)

    @Test
    fun verifyIsolationReadsSystemStateNotCachedFlag() = runBlocking {
        val isolation = ReadbackIsolation()
        val worker = AudioWorker(ReadbackBridge(), isolation, ReadbackClock(), AudioLeaseCheck { true })
        assertTrue(worker.isolateMicrophone())
        assertTrue(worker.microphoneIsolated)

        isolation.isolated = false

        assertFalse("缓存值为 true 时也必须以系统读回为准", worker.verifyIsolation())
        assertFalse(worker.microphoneIsolated)
    }

    @Test
    fun openRefusesWhenIsolationWasRemovedExternally() = runBlocking {
        val isolation = ReadbackIsolation()
        val bridge = ReadbackBridge()
        val worker = AudioWorker(bridge, isolation, ReadbackClock(), AudioLeaseCheck { true })
        assertTrue(worker.isolateMicrophone())
        isolation.isolated = false

        try {
            worker.open(session, format)
            org.junit.Assert.fail("系统隔离已被解除时不得打开通话音频")
        } catch (expected: AudioUnavailableException) {
            assertEquals("audio_not_isolated", expected.code)
        }
        assertEquals(0, bridge.openCalls)
    }

    private class ReadbackIsolation : MicrophoneIsolation {
        var isolated = false
        override suspend fun isolate(): Boolean {
            isolated = true
            return true
        }

        override suspend fun restore(): Boolean {
            isolated = false
            return true
        }

        override fun isIsolated(): Boolean = isolated
    }

    private class ReadbackClock : MonotonicClock {
        override fun nowMs(): Long = 0L
    }

    private class ReadbackBridge : AudioBridge {
        var openCalls = 0

        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
            openCalls++
            return object : AudioSession {
                override val downlink: Flow<PcmChunk> = emptyFlow()
                override suspend fun writeUplink(audio: PcmAudio) = Unit
                override suspend fun stop() = Unit
            }
        }
    }
}

// ---------------------------------------------------------------------------- 测试替身

private class AmbClock : MonotonicClock {
    var now = 0L
    override fun nowMs(): Long = now
}

private class AmbIsolation(private val restoreSucceeds: Boolean) : MicrophoneIsolation {
    var isolated = false
    var restoreCalls = 0

    override suspend fun isolate(): Boolean {
        isolated = true
        return true
    }

    override suspend fun restore(): Boolean {
        restoreCalls++
        if (restoreSucceeds) isolated = false
        return restoreSucceeds
    }

    override fun isIsolated(): Boolean = isolated
}

private class AmbBridge : AudioBridge {
    var openCalls = 0

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        openCalls++
        return object : AudioSession {
            override val downlink: Flow<PcmChunk> = emptyFlow()
            override suspend fun writeUplink(audio: PcmAudio) = Unit
            override suspend fun stop() = Unit
        }
    }
}

private class AmbCalls : CallControl {
    val commands = mutableListOf<Pair<SessionId, CallCommand>>()
    var answerResult: CommandResult = CommandResult.Accepted

    override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
        commands += id to command
        return if (command == CallCommand.Answer) answerResult else CommandResult.Accepted
    }
}

private class AmbPermission(private var granted: Boolean) : TerminationPermission {
    override fun isGranted(): Boolean = granted
}

private class AmbIdentity(private val session: SessionId) : CallIdentityVerifier {
    override fun isAvailable(): Boolean = true
    override suspend fun verify(id: SessionId): VerifiedCallIdentity? = VerifiedCallIdentity(session)
}

private class AmbTermination : PrivilegedTermination {
    override suspend fun disconnect(verified: VerifiedCallIdentity): CommandResult = CommandResult.Accepted
}
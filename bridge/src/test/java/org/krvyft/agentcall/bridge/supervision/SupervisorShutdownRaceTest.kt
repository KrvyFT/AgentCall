package org.krvyft.agentcall.bridge.supervision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.audio.AudioFailureCode
import org.krvyft.agentcall.bridge.audio.AudioUnavailableException
import org.krvyft.agentcall.bridge.audio.IsolationProbe
import org.krvyft.agentcall.bridge.audio.SupervisedAudio
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * Task 11E / I3：Supervisor.shutdown 与 onAppLaneFault → onAudioFailure 的竞态。
 *
 * 关闭把恢复麦克风放在迁移锁里，并先置 shuttingDown：关闭期间到达的音频故障必须被忽略，
 * 进行中的音频故障必须先完成。两种顺序下都绝不允许 restore 之后又被 stop/隔离覆盖。
 */
class SupervisorShutdownRaceTest {

    private val session = SessionId("epoch-1", "call-a")

    /**
     * 带闸门的音频端口：只阻塞第一次读回/恢复，用来稳定复现两种到达顺序。
     */
    private class GatedAudio : SupervisedAudio {
        @Volatile
        var isolated: Boolean = true

        @Volatile
        var blockProbe: Boolean = false

        @Volatile
        var blockRestore: Boolean = false

        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        var stops: Int = 0
        var restores: Int = 0

        override val microphoneIsolated: Boolean get() = isolated

        private suspend fun gate(flag: Boolean) {
            if (!flag) return
            blocked.complete(Unit)
            release.await()
        }

        override suspend fun isolateMicrophone(): Boolean {
            isolated = true
            return true
        }

        override suspend fun verifyIsolation(): Boolean = isolated

        override suspend fun probeIsolation(): IsolationProbe {
            gate(blockProbe)
            return IsolationProbe.Verified(isolated)
        }

        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
            throw AudioUnavailableException(
                code = "audio_downlink_unsupported",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "test audio never opens",
            )
        }

        override suspend fun stopKeepingIsolation() {
            stops++
        }

        override suspend fun restoreMicrophone(): Boolean {
            gate(blockRestore)
            restores++
            isolated = false
            return true
        }
    }

    private class Calls : CallControl {
        override suspend fun send(id: SessionId, command: CallCommand): CommandResult =
            CommandResult.Accepted
    }

    private fun supervisor(audio: GatedAudio): Supervisor = Supervisor(
        clock = object : MonotonicClock {
            override fun nowMs(): Long = 0L
        },
        audio = audio,
        calls = Calls(),
        emergency = EmergencyCallControl(
            TerminationPermission { true },
            object : CallIdentityVerifier {
                override fun isAvailable(): Boolean = true
                override suspend fun verify(id: SessionId): VerifiedCallIdentity =
                    VerifiedCallIdentity(id, "telecom-1")
            },
            PrivilegedTermination { CommandResult.Accepted },
        ),
        authorization = AutoAnswerAuthorization { true },
        callState = CallStateProbe { CallState.DISCONNECTED },
    )

    private suspend fun Supervisor.own() {
        heartbeat(session, PeerRole.APP, 0L)
        heartbeat(session, PeerRole.HOOK, 0L)
        assertEquals(CommandResult.Accepted, prepareAnswer(session))
    }

    /** 顺序 A：关闭已经进入恢复，迟到的音频故障不得再隔离/改写阶段。 */
    @Test
    fun lateAudioFailureCannotOverrideRestore() = runBlocking {
        val audio = GatedAudio()
        val supervisor = supervisor(audio)
        supervisor.own()
        audio.blockRestore = true

        val shutdown = async(Dispatchers.Default) { supervisor.shutdown(deadlineMs = 2_000) }
        withTimeout(5_000) { audio.blocked.await() }

        val failure = async(Dispatchers.Default) { supervisor.onAudioFailure(session, "late_fault") }
        delay(100)
        assertFalse(
            "音频故障处理不得在关闭恢复期间拿到迁移锁",
            failure.isCompleted,
        )

        audio.release.complete(Unit)

        val result = withTimeout(5_000) { shutdown.await() }
        val actions = withTimeout(5_000) { failure.await() }

        assertTrue("恢复成功后必须干净关闭", result.clean)
        assertFalse("恢复之后绝不允许再被隔离", audio.isolated)
        assertTrue("关闭期间的音频故障必须被忽略，实际动作: " + actions, actions.isEmpty())
        assertEquals("关闭只能执行一次恢复", 1, audio.restores)
    }

    /** 顺序 B：音频故障先持锁，关闭必须等它结束之后才终止/恢复。 */
    @Test
    fun inFlightAudioFailureIsSerializedBeforeShutdownRestore() = runBlocking {
        val audio = GatedAudio()
        val supervisor = supervisor(audio)
        supervisor.own()
        audio.blockProbe = true

        val failure = async(Dispatchers.Default) { supervisor.onAudioFailure(session, "audio_fault") }
        withTimeout(5_000) { audio.blocked.await() }

        val shutdown = async(Dispatchers.Default) { supervisor.shutdown(deadlineMs = 2_000) }
        delay(100)
        assertFalse(
            "关闭必须等待进行中的音频故障处理，不能并发恢复",
            shutdown.isCompleted,
        )

        audio.release.complete(Unit)

        val actions = withTimeout(5_000) { failure.await() }
        val result = withTimeout(5_000) { shutdown.await() }

        assertTrue(
            "进行中的音频故障必须完整执行（先保持隔离并开人工接管窗口）",
            actions.any { it is SupervisorAction.PromptManualTakeover },
        )
        assertTrue("随后关闭必须干净收尾", result.clean)
        assertFalse("最终关闭必须把麦克风恢复", audio.isolated)
        assertTrue("音频故障至少停过一次音频", audio.stops >= 1)
    }
}

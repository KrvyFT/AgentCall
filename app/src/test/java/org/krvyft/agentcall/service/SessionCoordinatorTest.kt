package org.krvyft.agentcall.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.SessionEvent
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState
import org.krvyft.agentcall.core.model.TakeoverReason
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.ConversationPort
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * APK 侧会话协调器的 JVM 测试（虚拟调度器 + 假端口）。
 *
 * 四个行为各自断言**副作用顺序**与**目标 SessionId**，而不只看 UI 文案或布尔值：
 * - startRequestIsNotReady：服务未 READY 时不得发出任何接听/会话请求，READY 后才按新一轮
 *   倒计时发起唯一一次 session.request；
 * - takeoverRevokesBeforeRestore：先取消 AI 会话，再与监督端串行撤销所有权，确认后才恢复
 *   声音；并且人工接管绝不挂断电话；
 * - oldApiResponseIgnored：旧 SessionId/旧代次的迟到命令结果不得打开音频、启动会话或作用到
 *   新通话；
 * - manualCallCannotBeDisconnectedByTimeout：人工接管后的通话即使超过 180 s 上限也不得挂断。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {

    private val sessionA = SessionId(processEpoch = "epoch-1", callId = "call-a")
    private val sessionB = SessionId(processEpoch = "epoch-2", callId = "call-b")

    private val config = AgentConfig(
        enabled = true,
        blacklist = listOf("+8613800000000"),
        enabledPhoneAccounts = listOf("acct-1"),
        answerDelayMs = 3_000,
        maxCallDurationMs = 180_000,
    )

    private fun ringing(session: SessionId): CallSnapshot = snapshot(session, CallState.RINGING)

    private fun active(session: SessionId): CallSnapshot = snapshot(session, CallState.ACTIVE)

    private fun snapshot(session: SessionId, state: CallState): CallSnapshot = CallSnapshot(
        id = session,
        number = "+8613800000000",
        state = state,
        direction = CallDirection.INCOMING,
        presentation = Presentation.ALLOWED,
        phoneAccount = "acct-1",
        isEmergency = false,
        isConference = false,
        activeCallCount = 1,
    )

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun startRequestIsNotReady() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val coordinator = coordinator(calls, audio, conversation, FakeRevoker(events), RecordingObserver(events))
        coordinator.start()
        try {
            // 服务尚未 READY：名单命中只停在 ELIGIBLE，不能有任何接听/会话副作用。
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            advance(5_000)
            assertEquals(SessionState.ELIGIBLE, coordinator.state)
            assertTrue("no command may target a call before READY", calls.sent.isEmpty())
            assertTrue("no audio/conversation may start before READY", events.isEmpty())

            // READY 之后才进入 PREPARING；倒计时仍是完整的一段，不能立刻接听。
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            runCurrent()
            assertEquals(SessionState.PREPARING, coordinator.state)
            assertTrue("READY alone must not answer", calls.sent.isEmpty())

            advance(3_100)
            assertEquals(
                "exactly one session.request answer must target session A",
                listOf(SentCommand(sessionA, CallCommand.Answer)),
                calls.sent,
            )
            // 尚未收到 ACTIVE 快照：不得打开音频或启动远程会话。
            assertTrue(events.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun takeoverRevokesBeforeRestore() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val revoker = FakeRevoker(events)
        val observer = RecordingObserver(events)
        val coordinator = coordinator(calls, audio, conversation, revoker, observer)
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(100)

            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertEquals(listOf("audio.opened:call-a", "conversation.started:call-a"), events)
            assertEquals(listOf(SentCommand(sessionA, CallCommand.Answer)), calls.sent)

            coordinator.onEvent(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED))
            advance(100)

            assertEquals(
                "cancel conversation, revoke ownership, then restore sound",
                listOf(
                    "audio.opened:call-a",
                    "conversation.started:call-a",
                    "conversation.cancelled:call-a",
                    "revoked:call-a",
                    "audio.stopped:call-a",
                    "restored:call-a",
                ),
                events,
            )
            assertEquals(SessionState.HANDOFF, coordinator.state)
            assertEquals(
                "manual takeover must never hang up the call",
                listOf(SentCommand(sessionA, CallCommand.Answer)),
                calls.sent,
            )
            assertEquals(listOf(sessionA), observer.handedBack)
            assertEquals(listOf(RevokeOutcome.CONFIRMED), observer.outcomes)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun oldApiResponseIgnored() = runTest {
        val events = mutableListOf<String>()
        val lateAnswer = CompletableDeferred<CommandResult>()
        val calls = FakeCallControl(lateAnswer = lateAnswer)
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val coordinator = coordinator(calls, audio, conversation, FakeRevoker(events), RecordingObserver(events))
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            assertEquals(listOf(SentCommand(sessionA, CallCommand.Answer)), calls.sent)

            // A 的接听结果迟迟未回；此时进程重建后的新会话 B 到达，A 必须被退役。
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionB)))
            advance(100)
            assertEquals(sessionB, coordinator.activeSession)
            assertEquals(SessionState.PREPARING, coordinator.state)
            assertTrue("no audio for a stale session", events.isEmpty())

            // A 的迟到 Accepted 绝不能被当成 B 的接听成功。
            lateAnswer.complete(CommandResult.Accepted)
            advance(100)
            assertTrue("a stale answer result must not open audio", events.isEmpty())
            assertEquals(sessionB, coordinator.activeSession)
            assertEquals(SessionState.PREPARING, coordinator.state)

            // B 自己仍按正常倒计时接听一次；旧结果没有产生任何第二条命令。
            advance(3_100)
            assertEquals(
                listOf(
                    SentCommand(sessionA, CallCommand.Answer),
                    SentCommand(sessionB, CallCommand.Answer),
                ),
                calls.sent,
            )
            assertTrue("stale command result must not start a conversation", events.isEmpty())
            assertEquals(SessionState.ANSWER_PENDING, coordinator.state)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun manualCallCannotBeDisconnectedByTimeout() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val coordinator = coordinator(calls, audio, conversation, FakeRevoker(events), RecordingObserver(events))
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(100)
            assertEquals(SessionState.ACTIVE_AI, coordinator.state)

            coordinator.onEvent(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED))
            advance(100)
            assertEquals(SessionState.HANDOFF, coordinator.state)

            // 用户继续通话（人工控制）：再次观察到 ACTIVE，并且远超 180 s 上限。
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(200_000)

            assertEquals(SessionState.HANDOFF, coordinator.state)
            assertEquals(
                "a handed-back call must never receive a timeout hangup",
                listOf(SentCommand(sessionA, CallCommand.Answer)),
                calls.sent,
            )
            assertEquals(
                "audio must not be reopened for a manual call",
                listOf(
                    "audio.opened:call-a",
                    "conversation.started:call-a",
                    "conversation.cancelled:call-a",
                    "revoked:call-a",
                    "audio.stopped:call-a",
                    "restored:call-a",
                ),
                events,
            )
        } finally {
            coordinator.close()
        }
    }

    // ------------------------------------------------------------------ 装配

    private fun TestScope.coordinator(
        calls: CallControl,
        audio: AudioBridge,
        conversation: ConversationPort,
        revoker: OwnershipRevoker,
        observer: SessionObserver,
    ): SessionCoordinator = SessionCoordinator(
        config = config,
        calls = calls,
        audio = audio,
        conversation = conversation,
        clock = MonotonicClock { testScheduler.currentTime },
        revoker = revoker,
        observer = observer,
        dispatcher = StandardTestDispatcher(testScheduler),
        tickIntervalMs = 1_000,
    )

    // ------------------------------------------------------------------ 假实现

    private data class SentCommand(val id: SessionId, val command: CallCommand)

    private class FakeCallControl(
        private val lateAnswer: CompletableDeferred<CommandResult>? = null,
    ) : CallControl {
        val sent = mutableListOf<SentCommand>()

        override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
            val answerCount = sent.count { it.command == CallCommand.Answer }
            sent += SentCommand(id, command)
            if (lateAnswer != null && command == CallCommand.Answer && answerCount == 0) {
                // 模拟结果迟到：命令已发出，但结果要等测试显式放行。
                return withContext(NonCancellable) { lateAnswer.await() }
            }
            return CommandResult.Accepted
        }
    }

    private class FakeAudioBridge(private val events: MutableList<String>) : AudioBridge {
        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
            events += "audio.opened:" + id.callId
            return FakeAudioSession(id, events)
        }
    }

    private class FakeAudioSession(
        private val session: SessionId,
        private val events: MutableList<String>,
    ) : AudioSession {
        override val downlink: Flow<PcmChunk> = emptyFlow()

        override suspend fun writeUplink(audio: PcmAudio) {
            events += "audio.uplink:" + session.callId
        }

        override suspend fun stop() {
            events += "audio.stopped:" + session.callId
        }
    }

    private class FakeConversation(private val events: MutableList<String>) : ConversationPort {
        override suspend fun run(id: SessionId, audio: AudioSession, config: AgentConfig) {
            events += "conversation.started:" + id.callId
            try {
                awaitCancellation()
            } finally {
                events += "conversation.cancelled:" + id.callId
            }
        }
    }

    private class FakeRevoker(
        private val events: MutableList<String>,
        private val outcome: RevokeOutcome = RevokeOutcome.CONFIRMED,
    ) : OwnershipRevoker {
        override suspend fun revoke(id: SessionId): RevokeOutcome {
            events += "revoked:" + id.callId
            return outcome
        }
    }

    private class RecordingObserver(private val events: MutableList<String>) : SessionObserver() {
        val handedBack = mutableListOf<SessionId>()
        val outcomes = mutableListOf<RevokeOutcome>()

        override fun onHandedBack(id: SessionId, outcome: RevokeOutcome) {
            handedBack += id
            outcomes += outcome
            events += "restored:" + id.callId
        }
    }
}

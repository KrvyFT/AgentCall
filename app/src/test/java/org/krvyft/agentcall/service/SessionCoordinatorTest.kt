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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.krvyft.agentcall.core.session.LeaseRegistry

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

    private fun disconnected(session: SessionId): CallSnapshot = snapshot(session, CallState.DISCONNECTED)

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

    // ------------------------------------------------------------------ 撤销未确认：不得显示交还成功（IMPORTANT 3）

    /**
     * 撤销失败（bridge 回 revoke_failed）时：不停止本地音频、不广播交还、状态留在 HANDOFF。
     * spec §8：撤销失败不得显示交还成功。
     */
    @Test
    fun revokeFailedNeverShowsHandbackSuccess() = runTest {
        assertRevokeFailureKeepsHandoff(RevokeOutcome.REVOKE_FAILED)
    }

    /** 撤销不可用（bridge 不可达）与失败同等对待：保持隔离、只诊断。 */
    @Test
    fun revokeUnavailableNeverShowsHandbackSuccess() = runTest {
        assertRevokeFailureKeepsHandoff(RevokeOutcome.UNAVAILABLE)
    }

    /**
     * 在途的 session.request（最长 4000 ms，客户端预算更长）还没落地时，撤销必须等它；
     * 等满 commandSettleTimeoutMs 仍不落地就放弃撤销（不抢在授权之前），绝不恢复声音。
     */
    @Test
    fun unsettledAnswerAbortsTheHandbackInsteadOfRacingIt() = runTest {
        val events = mutableListOf<String>()
        val lateAnswer = CompletableDeferred<CommandResult>()
        val calls = FakeCallControl(lateAnswer = lateAnswer)
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
            assertEquals(listOf(SentCommand(sessionA, CallCommand.Answer)), calls.sent)

            // 接听结果迟迟不回；此时用户接管：撤销必须等这条在途请求。
            coordinator.onEvent(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED))
            advance(100)
            assertTrue("the revoke must wait for the in-flight answer", revoker.revoked.isEmpty())

            advance(SessionCoordinator.COMMAND_SETTLE_TIMEOUT_MS + 200)
            assertEquals(SessionState.HANDOFF, coordinator.state)
            assertTrue("the revoke must never race the in-flight authorization", revoker.revoked.isEmpty())
            assertTrue("no handback may be reported", observer.handedBack.isEmpty())
            assertTrue(
                "no local audio may be stopped without a confirmed revoke",
                events.none { it.startsWith("audio.") || it.startsWith("restored") || it.startsWith("conversation.") },
            )
            assertTrue(
                "the unsettled command must be diagnosed, got " + observer.diagnostics,
                "handback_command_unsettled" in observer.diagnostics,
            )
        } finally {
            coordinator.close()
        }
    }

    // ------------------------------------------------------------------ 安全输入不可丢（IMPORTANT 5）

    /**
     * 有界队列饱和时（消费端正卡在撤销等待里），Takeover/断开通话这类安全输入绝不丢弃：
     * 丢掉一条断开通话会让状态永远停在 HANDOFF，丢掉 Takeover 会让用户以为接管了却没人撤权。
     */
    @Test
    fun safetyInputsAreNeverDroppedUnderQueueSaturation() = runTest {
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val revoker = FakeRevoker(events, gate = gate)
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

            // 人工接管：消费端卡在撤销等待里（gate 未放行）。
            coordinator.onEvent(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED))
            runCurrent()
            assertEquals(listOf(sessionA), revoker.revoked)

            // 灌满有界队列：只有 Tick 允许被丢弃。
            repeat(200) { coordinator.onEvent(SessionEvent.Tick) }
            assertTrue("the bounded queue must actually be saturated", coordinator.droppedInputCount > 0)

            // 饱和期间到达的安全输入。
            coordinator.onEvent(SessionEvent.StateChanged(disconnected(sessionA)))

            gate.complete(Unit)
            advance(100)

            assertTrue(
                "a disconnected call must still end after saturation",
                observer.states.contains(sessionA to SessionState.ENDED),
            )
            assertNull("the session must be retired after ENDED", coordinator.activeSession)
        } finally {
            coordinator.close()
        }
    }

    // ------------------------------------------------------------------ 配置热更新（IMPORTANT 6）

    /**
     * 总开关在通话中被关闭：同一条串行消费者立即让 reducer 生效——取消 AI 会话、下发挂断、
     * 撤销所有权（ENDING），而不是等 180 s 上限或重建协调器。
     */
    @Test
    fun masterSwitchOffTerminatesAnActiveCallImmediately() = runTest {
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

            coordinator.onConfigChanged(config.copy(enabled = false))
            advance(100)

            assertEquals("the master switch must end the AI call", SessionState.ENDING, coordinator.state)
            assertEquals(
                "answer once, then exactly one hangup",
                listOf(
                    SentCommand(sessionA, CallCommand.Answer),
                    SentCommand(sessionA, CallCommand.Hangup),
                ),
                calls.sent,
            )
            assertTrue("the AI conversation must stop", "conversation.cancelled:call-a" in events)
            assertEquals(listOf(sessionA), revoker.revoked)
            assertTrue(
                "the microphone stays isolated until the disconnect is confirmed",
                events.none { it.startsWith("audio.stopped") },
            )

            // 真实断开确认之后才恢复人工路由并结束。
            coordinator.onEvent(SessionEvent.StateChanged(disconnected(sessionA)))
            advance(100)
            assertEquals(listOf(sessionA), observer.handedBack)
            assertTrue("audio must be released after the disconnect", events.contains("audio.stopped:call-a"))
            assertTrue(observer.states.contains(sessionA to SessionState.ENDED))
        } finally {
            coordinator.close()
        }
    }

    // ------------------------------------------------------------------ 关闭语义/ENDED 去重（minors）

    /** close() 必须停掉当前音频、撤销本地租约，并拒绝关闭后的 start()/onEvent()。 */
    @Test
    fun closeStopsAudioRevokesTheLeaseAndRejectsLateStart() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val leases = LeaseRegistry()
        val coordinator = coordinator(
            calls, audio, conversation, FakeRevoker(events), RecordingObserver(events), leases = leases,
        )
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(100)
            assertTrue(leases.isLive(sessionA))

            coordinator.close()
            runCurrent()

            assertTrue("close() must stop the live audio session", events.contains("audio.stopped:call-a"))
            assertFalse("close() must revoke the local lease", leases.isLive(sessionA))
            assertNull(coordinator.activeSession)

            val before = events.size
            coordinator.start()
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionB)))
            advance(5_000)
            assertEquals("a closed coordinator must stay closed", before, events.size)
            assertNull(coordinator.activeSession)
        } finally {
            coordinator.close()
        }
    }

    /** ENDED 只广播一次：reduceAndApply 与 retire 都不得各发一遍。 */
    @Test
    fun disconnectedCallEndsExactlyOnce() = runTest {
        val events = mutableListOf<String>()
        val observer = RecordingObserver(events)
        val coordinator = coordinator(
            FakeCallControl(), FakeAudioBridge(events), FakeConversation(events), FakeRevoker(events), observer,
        )
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(100)

            coordinator.onEvent(SessionEvent.StateChanged(disconnected(sessionA)))
            advance(100)

            val ended = observer.states.filter { it.first == sessionA && it.second == SessionState.ENDED }
            assertEquals("ENDED must be broadcast exactly once, got " + observer.states, 1, ended.size)
            assertNull(coordinator.activeSession)
        } finally {
            coordinator.close()
        }
    }

    private fun TestScope.assertRevokeFailureKeepsHandoff(outcome: RevokeOutcome) {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val revoker = FakeRevoker(events, outcome = outcome)
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

            coordinator.onEvent(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED))
            advance(100)

            assertEquals(
                "the AI conversation must stop and be revoked, but nothing may be restored",
                listOf(
                    "audio.opened:call-a",
                    "conversation.started:call-a",
                    "conversation.cancelled:call-a",
                    "revoked:call-a",
                ),
                events,
            )
            assertEquals("the call stays handed off", SessionState.HANDOFF, coordinator.state)
            assertTrue("no handback may be reported", observer.handedBack.isEmpty())
            assertTrue("no handback outcome may be published", observer.outcomes.isEmpty())
            assertTrue(
                "the blocked restore must be diagnosed, got " + observer.diagnostics,
                "handback_restore_blocked" in observer.diagnostics,
            )
            assertTrue("manual takeover must never hang up", calls.sent.none { it.command == CallCommand.Hangup })
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
        leases: LeaseRegistry = LeaseRegistry(),
        heartbeat: HeartbeatPump = HeartbeatPump { },
    ): SessionCoordinator = SessionCoordinator(
        config = config,
        calls = calls,
        audio = audio,
        conversation = conversation,
        clock = MonotonicClock { testScheduler.currentTime },
        leases = leases,
        revoker = revoker,
        heartbeat = heartbeat,
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
        private val gate: CompletableDeferred<Unit>? = null,
    ) : OwnershipRevoker {
        val revoked = mutableListOf<SessionId>()

        override suspend fun revoke(id: SessionId): RevokeOutcome {
            revoked += id
            events += "revoked:" + id.callId
            gate?.await()
            return outcome
        }
    }

    private class RecordingObserver(private val events: MutableList<String>) : SessionObserver() {
        val handedBack = mutableListOf<SessionId>()
        val outcomes = mutableListOf<RevokeOutcome>()
        val states = mutableListOf<Pair<SessionId, SessionState>>()
        val diagnostics = mutableListOf<String>()

        override fun onStateChanged(id: SessionId, state: SessionState) {
            states += id to state
        }

        override fun onHandedBack(id: SessionId, outcome: RevokeOutcome) {
            handedBack += id
            outcomes += outcome
            events += "restored:" + id.callId
        }

        override fun onDiagnostic(code: String, id: SessionId?) {
            diagnostics += code
        }
    }
}

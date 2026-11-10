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

            // A 的接听结果迟迟未回；此时第二路来电 B 到达。B 绝不能顶掉仍在 ANSWER_PENDING
            // 的 A（FIX ROUND 1：只有 IDLE/ENDED 的会话才允许被新来电替换）。
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionB)))
            advance(100)
            assertEquals("the pending call must not be replaced", sessionA, coordinator.activeSession)
            assertEquals(SessionState.ANSWER_PENDING, coordinator.state)
            assertTrue("no audio for a stale session", events.isEmpty())

            // A 真正断开：会话结束（撤销必须等在途接听落地），迟到结果被忽略而不是当成成功。
            coordinator.onEvent(SessionEvent.StateChanged(disconnected(sessionA)))
            advance(100)
            lateAnswer.complete(CommandResult.Accepted)
            advance(100)
            assertNull("the ended session must be retired", coordinator.activeSession)
            assertTrue(
                "a late answer result must not open audio or start a conversation, got " + events,
                events.none { it.startsWith("audio.opened") || it.startsWith("conversation.") },
            )

            // 之后 B 才成为当前会话；A 的迟到 Accepted 已不可能被当成 B 的接听成功。
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionB)))
            advance(100)
            assertEquals(sessionB, coordinator.activeSession)
            assertEquals(SessionState.PREPARING, coordinator.state)
            advance(100)
            assertTrue(
                "a stale answer result must not open audio, got " + events,
                events.none { it.startsWith("audio.opened") || it.startsWith("conversation.") },
            )
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
            assertTrue(
                "stale command result must not start a conversation, got " + events,
                events.none { it.startsWith("audio.opened") || it.startsWith("conversation.") },
            )
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
            // 撤销没能发起 = 未确认：必须以独立的交还结果到达 UI，绝不能消失或显示成功。
            assertEquals(listOf(sessionA), observer.handedBack)
            assertEquals(listOf(RevokeOutcome.UNAVAILABLE), observer.outcomes)
            assertTrue(
                "no local audio may be stopped without a confirmed revoke",
                events.none { it.startsWith("audio.") || it.startsWith("restored") || it.startsWith("conversation.") },
            )
            assertEquals("unconfirmed:call-a", events.last())
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
                    "unconfirmed:call-a",
                ),
                events,
            )
            assertEquals("the call stays handed off", SessionState.HANDOFF, coordinator.state)
            // 撤销未确认现在走交还结果：UI 能显示独立的“交还未确认”，而不是“尚未提交接管”。
            assertEquals(listOf(sessionA), observer.handedBack)
            assertEquals(listOf(outcome), observer.outcomes)
            assertTrue(
                "a revoke failure may never be reported as success",
                observer.outcomes.none { it == RevokeOutcome.CONFIRMED || it == RevokeOutcome.NOT_OWNED },
            )
            val report = HandbackJournal.record(sessionA, outcome)
            assertEquals(
                HandbackOutcome.Unconfirmed(report),
                HandbackJournal.outcomeFor(AgentCallStatus(activeSession = sessionA, lastHandback = report), sessionA),
            )
            assertTrue(
                "the blocked restore must be diagnosed, got " + observer.diagnostics,
                "handback_restore_blocked" in observer.diagnostics,
            )
            assertTrue("manual takeover must never hang up", calls.sent.none { it.command == CallCommand.Hangup })
        } finally {
            coordinator.close()
        }
    }

    // ------------------------------------------------------------------ 冻结的倒计时（item 1）

    /**
     * 接听前复检必须用本会话在创建 reducer 时冻结的倒计时，而不是最新配置 revision：
     * 铃响期间配置把 3 s 改成 10 s，当前会话仍按 3 s 到期。
     */
    @Test
    fun theActiveSessionKeepsItsFrozenAnswerDelayAcrossConfigUpdates() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            FakeCallControl(),
            FakeAudioBridge(events),
            FakeConversation(events),
            FakeRevoker(events),
            RecordingObserver(events),
        )
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            advance(100)
            assertEquals(3_000L, coordinator.currentAnswerDelayMs)

            coordinator.onConfigChanged(config.copy(answerDelayMs = 10_000))
            advance(100)
            assertEquals(
                "the countdown frozen for this session must win",
                3_000L,
                coordinator.currentAnswerDelayMs,
            )

            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(100)
            assertEquals(SessionState.PREPARING, coordinator.state)
            assertEquals(3_000L, coordinator.currentAnswerDelayMs)
        } finally {
            coordinator.close()
        }
    }

    // ------------------------------------------- 会话绑定/第二路响铃（7B3 FIX ROUND 1）

    /**
     * IMPORTANT 1：事件流里的 handback 绑定会话。别的/上一通电话的交还事实只记诊断，
     * 绝不撤销、不恢复、不停当前通话的音频——旧实现把它作用到“当前会话”上。
     */
    @Test
    fun aTakeoverForAnotherSessionNeverRevokesTheCurrentCall() = runTest {
        val events = mutableListOf<String>()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val revoker = FakeRevoker(events)
        val observer = RecordingObserver(events)
        val coordinator = coordinator(FakeCallControl(), audio, conversation, revoker, observer)
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(100)
            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertEquals(listOf("audio.opened:call-a", "conversation.started:call-a"), events)

            coordinator.onEvent(SessionEvent.Takeover(TakeoverReason.UNKNOWN, sessionB))
            advance(100)

            assertEquals("a foreign handback must not touch the current call", SessionState.ACTIVE_AI, coordinator.state)
            assertEquals(listOf("audio.opened:call-a", "conversation.started:call-a"), events)
            assertTrue("a foreign handback must never revoke", revoker.revoked.isEmpty())
            assertTrue("a foreign handback must not be reported", observer.handedBack.isEmpty())
            assertTrue(
                "the ignored handback must be diagnosed, got " + observer.diagnostics,
                "stale_takeover_ignored" in observer.diagnostics,
            )

            // 本会话的 handback 仍然生效（绑定只过滤“别人的”）。
            coordinator.onEvent(SessionEvent.Takeover(TakeoverReason.UNKNOWN, sessionA))
            advance(100)
            assertEquals(SessionState.HANDOFF, coordinator.state)
            assertEquals(listOf(sessionA), revoker.revoked)
        } finally {
            coordinator.close()
        }
    }

    /**
     * IMPORTANT 4：第二路来电在 AI 通话期间到达时，**绝不能退役当前会话**（那会停掉音频/
     * 会话并取消 APP 心跳，且不发 handover）。新来电继续响铃给用户，APP 不为它建会话。
     */
    @Test
    fun aSecondIncomingRingingNeverRetiresTheOwnedCallOrAnswersIt() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val heartbeat = CountingHeartbeat()
        val observer = RecordingObserver(events)
        val coordinator = coordinator(
            calls,
            audio,
            conversation,
            FakeRevoker(events),
            observer,
            heartbeat = heartbeat,
        )
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(100)
            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertTrue("the APP heartbeat must be running", heartbeat.beats > 0)

            // 第二路来电（不同 callId）在 AI 通话期间到达。
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionB)))
            advance(5_000)

            assertEquals("the AI call must keep owning the session", sessionA, coordinator.activeSession)
            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertEquals(
                "the current call keeps audio and conversation",
                listOf("audio.opened:call-a", "conversation.started:call-a"),
                events,
            )
            assertTrue(
                "the APP heartbeat must keep running, got " + heartbeat.beats,
                heartbeat.beats >= 5,
            )
            assertEquals(
                "no second call may be answered",
                listOf(SentCommand(sessionA, CallCommand.Answer)),
                calls.sent,
            )
            assertTrue(
                "the ignored incoming call must be diagnosed, got " + observer.diagnostics,
                "incoming_call_ignored" in observer.diagnostics,
            )
        } finally {
            coordinator.close()
        }
    }

    /** 当前会话只是 IDLE（RINGING 没命中名单）时，新来电仍可替换——旧行为不能退化。 */
    @Test
    fun anIdleSessionIsStillReplaceableByANewIncomingCall() = runTest {
        val events = mutableListOf<String>()
        val other = config.copy(blacklist = listOf("+8613800000000"))
        val coordinator = SessionCoordinator(
            config = other,
            calls = FakeCallControl(),
            audio = FakeAudioBridge(events),
            conversation = FakeConversation(events),
            clock = MonotonicClock { testScheduler.currentTime },
            revoker = FakeRevoker(events),
            observer = RecordingObserver(events),
            dispatcher = StandardTestDispatcher(testScheduler),
            tickIntervalMs = 1_000,
        )
        coordinator.start()
        try {
            // 名单外的号码：RINGING 不命中，当前会话停在 IDLE。
            coordinator.onEvent(SessionEvent.StateChanged(snapshot(sessionA, CallState.RINGING).copy(number = "+8613900000000")))
            advance(100)
            assertEquals(SessionState.IDLE, coordinator.state)

            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionB)))
            advance(100)

            assertEquals("an IDLE session must not block the next call", sessionB, coordinator.activeSession)
            assertEquals(SessionState.ELIGIBLE, coordinator.state)
        } finally {
            coordinator.close()
        }
    }

    /**
     * IMPORTANT 2：监督端 `call_disconnected` 映射成的 [SessionEvent.SessionEnded] 必须结束
     * 会话（HOOK 失联/故障时可能永远没有完整 DISCONNECTED 快照）：不挂断、不伪造快照、
     * 不向 bridge 发 handover，但释放本地租约/音频/隔离并退役会话。
     */
    @Test
    fun aSupervisorSessionEndedEndsTheOwnedCallWithoutHangup() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val revoker = FakeRevoker(events)
        val observer = RecordingObserver(events)
        val leases = LeaseRegistry()
        val heartbeat = CountingHeartbeat()
        val coordinator = coordinator(
            calls, audio, conversation, revoker, observer, leases = leases, heartbeat = heartbeat,
        )
        coordinator.start()
        try {
            coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
            advance(100)
            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertTrue(leases.isLive(sessionA))
            assertTrue(heartbeat.beats > 0)

            coordinator.onEvent(SessionEvent.SessionEnded(sessionA))
            advance(100)

            assertEquals(SessionState.ENDED, observer.states.last { it.first == sessionA }.second)
            assertNull("an ended session must be retired", coordinator.activeSession)
            assertEquals(
                "a call that no longer exists must never receive a Hangup",
                listOf(SentCommand(sessionA, CallCommand.Answer)),
                calls.sent,
            )
            assertTrue("the local audio must be released", events.contains("audio.stopped:call-a"))
            assertTrue("the AI conversation must stop", events.contains("conversation.cancelled:call-a"))
            assertTrue("the local lease must be revoked", !leases.isLive(sessionA))
            assertTrue(
                "the supervisor already owns termination: no handover RPC may be sent",
                revoker.revoked.isEmpty(),
            )
            assertEquals(listOf(sessionA), observer.handedBack)
            assertEquals(listOf(RevokeOutcome.NOT_OWNED), observer.outcomes)
            // 退役后心跳 Job 随 callJob 取消：不再有新心跳。
            val beatsAfterEnd = heartbeat.beats
            advance(5_000)
            assertEquals("the heartbeat must stop with the session", beatsAfterEnd, heartbeat.beats)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun aSupervisorSessionEndedForAnotherSessionIsIgnored() = runTest {
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

            coordinator.onEvent(SessionEvent.SessionEnded(sessionB))
            advance(100)

            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertEquals(sessionA, coordinator.activeSession)
            assertTrue(
                "the ignored session end must be diagnosed, got " + observer.diagnostics,
                "stale_session_ended_ignored" in observer.diagnostics,
            )
        } finally {
            coordinator.close()
        }
    }

    /**
     * IMPORTANT 1（续）：监督端的 handback 结果语义必须保留——
     * `revoke_failed` 走 handover RPC 重试；`not_owned`/`confirmed` 不必再发 RPC；
     * `restore_failed` 绝不能显示为“已交还成功”。
     */
    @Test
    fun supervisorHandbackOutcomesChangeTheRevokePathAndTheReportedOutcome() = runTest {
        // revoke_failed：所有权可能仍在监督端 → 必须重试 handover。
        val retryEvents = mutableListOf<String>()
        val retryObserver = RecordingObserver(retryEvents)
        val retryRevoker = FakeRevoker(retryEvents)
        val retry = coordinator(
            FakeCallControl(), FakeAudioBridge(retryEvents), FakeConversation(retryEvents), retryRevoker, retryObserver,
        )
        retry.start()
        try {
            activeAi(retry)
            retry.onSupervisorHandback(sessionA, SupervisorHandback.REVOKE_FAILED)
            advance(100)
            assertEquals(listOf(sessionA), retryRevoker.revoked)
            assertEquals(SessionState.HANDOFF, retry.state)
            assertEquals(listOf(RevokeOutcome.CONFIRMED), retryObserver.outcomes)
        } finally {
            retry.close()
        }

        // not_owned：监督端本来就不持有 → 不发多余的 handover RPC。
        val notOwnedEvents = mutableListOf<String>()
        val notOwnedObserver = RecordingObserver(notOwnedEvents)
        val notOwnedRevoker = FakeRevoker(notOwnedEvents)
        val notOwned = coordinator(
            FakeCallControl(), FakeAudioBridge(notOwnedEvents), FakeConversation(notOwnedEvents),
            notOwnedRevoker, notOwnedObserver,
        )
        notOwned.start()
        try {
            activeAi(notOwned)
            notOwned.onSupervisorHandback(sessionA, SupervisorHandback.NOT_OWNED)
            advance(100)
            assertTrue("the supervisor already released it", notOwnedRevoker.revoked.isEmpty())
            assertEquals(listOf(RevokeOutcome.NOT_OWNED), notOwnedObserver.outcomes)
        } finally {
            notOwned.close()
        }

        // restore_failed：撤销可能已成功，但人工音频没恢复 → 绝不能报告“已交还成功”。
        val restoreEvents = mutableListOf<String>()
        val restoreObserver = RecordingObserver(restoreEvents)
        val restoreRevoker = FakeRevoker(restoreEvents)
        val restore = coordinator(
            FakeCallControl(), FakeAudioBridge(restoreEvents), FakeConversation(restoreEvents),
            restoreRevoker, restoreObserver,
        )
        restore.start()
        try {
            activeAi(restore)
            restore.onSupervisorHandback(sessionA, SupervisorHandback.RESTORE_FAILED)
            advance(100)
            assertTrue("a failed restore needs no revoke retry", restoreRevoker.revoked.isEmpty())
            assertEquals(
                "the final report must be unconfirmed, got " + restoreObserver.outcomes,
                RevokeOutcome.REVOKE_FAILED,
                restoreObserver.outcomes.last(),
            )
            val report = HandbackJournal.record(sessionA, restoreObserver.outcomes.last())
            assertEquals(
                HandbackOutcome.Unconfirmed(report),
                HandbackJournal.outcomeFor(AgentCallStatus(activeSession = sessionA, lastHandback = report), sessionA),
            )
        } finally {
            restore.close()
        }
    }

    @Test
    fun aSupervisorHandbackForAnotherSessionIsIgnored() = runTest {
        val events = mutableListOf<String>()
        val revoker = FakeRevoker(events)
        val observer = RecordingObserver(events)
        val coordinator = coordinator(
            FakeCallControl(), FakeAudioBridge(events), FakeConversation(events), revoker, observer,
        )
        coordinator.start()
        try {
            activeAi(coordinator)

            coordinator.onSupervisorHandback(sessionB, SupervisorHandback.REVOKE_FAILED)
            advance(100)

            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertTrue("a foreign handback must never revoke the current call", revoker.revoked.isEmpty())
            assertTrue(
                "the ignored handback must be diagnosed, got " + observer.diagnostics,
                "stale_supervisor_handback_ignored" in observer.diagnostics,
            )
        } finally {
            coordinator.close()
        }
    }

    // ------------------------------------- 桥接代次变化：本地结束（7B3 FOLLOW-UP）

    /**
     * 裁定（7B3 FOLLOW-UP）：桥接重启（代次变化）时，仍归协调器的会话必须**本地结束一次**
     * ——复用 SessionEnded 语义（StopConversation → RevokeLease → RestoreMicrophone →
     * ENDED），绝不发挂断、绝不向 bridge 发 handover、绝不伪造 DISCONNECTED 快照。
     * 同一次重启被多轮轮询观测到（重复通知）时，只允许结束一次。
     */
    @Test
    fun repeatedBridgeRestartSignalsEndTheOwnedCallOnlyOnceWithoutHangup() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = FakeAudioBridge(events)
        val conversation = FakeConversation(events)
        val revoker = FakeRevoker(events)
        val observer = RecordingObserver(events)
        val leases = LeaseRegistry()
        val coordinator = coordinator(calls, audio, conversation, revoker, observer, leases = leases)
        coordinator.start()
        try {
            activeAi(coordinator)
            assertTrue(leases.isLive(sessionA))

            // 多轮轮询可能重复报告同一次代次变化：只有第一份通知生效。
            coordinator.onBridgeRestarted()
            coordinator.onBridgeRestarted()
            coordinator.onBridgeRestarted()
            advance(100)

            assertEquals(
                "local end: stop conversation → revoke (local) → restore, never Hangup",
                listOf(
                    "audio.opened:call-a",
                    "conversation.started:call-a",
                    "conversation.cancelled:call-a",
                    "audio.stopped:call-a",
                    "restored:call-a",
                ),
                events,
            )
            assertNull("the stale session must be retired", coordinator.activeSession)
            assertFalse("the local end must release the lease", leases.isLive(sessionA))
            assertEquals(
                "ENDED must be broadcast exactly once",
                1,
                observer.states.count { it.first == sessionA && it.second == SessionState.ENDED },
            )
            assertEquals(listOf(sessionA), observer.handedBack)
            assertEquals(listOf(RevokeOutcome.NOT_OWNED), observer.outcomes)
            assertTrue(
                "a bridge restart must never hang up the call",
                calls.sent.none { it.command == CallCommand.Hangup },
            )
            assertTrue(
                "the supervisor owns termination: no handover RPC may be sent",
                revoker.revoked.isEmpty(),
            )
            assertTrue(
                "the local end must be diagnosed, got " + observer.diagnostics,
                "bridge_restart_session_ended" in observer.diagnostics,
            )
        } finally {
            coordinator.close()
        }
    }

    /** 协调器没有会话时，代次变化是空操作：不产生状态、音频、命令或租约副作用。 */
    @Test
    fun aBridgeRestartWithoutAnOwnedSessionDoesNothing() = runTest {
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val observer = RecordingObserver(events)
        val coordinator = coordinator(
            calls,
            FakeAudioBridge(events),
            FakeConversation(events),
            FakeRevoker(events),
            observer,
        )
        coordinator.start()
        try {
            coordinator.onBridgeRestarted()
            advance(100)

            assertNull(coordinator.activeSession)
            assertTrue("no owned session: no state may be broadcast", observer.states.isEmpty())
            assertTrue("no owned session: no audio/conversation/revoke may happen", events.isEmpty())
            assertTrue("no owned session: no command may be sent", calls.sent.isEmpty())
            assertTrue("no owned session: nothing to diagnose", observer.diagnostics.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    /** 让协调器进入 A 的 ACTIVE_AI（音频/会话已开）。 */
    private suspend fun TestScope.activeAi(coordinator: SessionCoordinator) {
        coordinator.onEvent(SessionEvent.StateChanged(ringing(sessionA)))
        coordinator.onEvent(SessionEvent.ReadyChanged(true))
        advance(3_100)
        coordinator.onEvent(SessionEvent.StateChanged(active(sessionA)))
        advance(100)
        assertEquals(SessionState.ACTIVE_AI, coordinator.state)
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

    /** 记录心跳次数的泵：第二路响铃/会话结束时用它证明心跳没被取消/被取消。 */
    private class CountingHeartbeat : HeartbeatPump {
        var beats = 0

        override suspend fun run(id: SessionId) {
            while (true) {
                beats += 1
                kotlinx.coroutines.delay(1_000)
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
            // 只有已确认的撤销才代表“恢复人工声音”；未确认是独立状态，绝不能混成 restored。
            events += if (outcome == RevokeOutcome.CONFIRMED || outcome == RevokeOutcome.NOT_OWNED) {
                "restored:" + id.callId
            } else {
                "unconfirmed:" + id.callId
            }
        }

        override fun onDiagnostic(code: String, id: SessionId?) {
            diagnostics += code
        }
    }
}

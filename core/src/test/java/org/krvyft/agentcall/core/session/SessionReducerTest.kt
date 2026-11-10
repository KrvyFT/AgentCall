package org.krvyft.agentcall.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.fixtures.Fixtures
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.SessionEffect
import org.krvyft.agentcall.core.model.SessionEvent
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState
import org.krvyft.agentcall.core.model.TakeoverReason

/**
 * 单写者 reducer 的行为测试：只断言副作用描述与状态，不接触 Android、时钟或线程。
 *
 * 计时全部由 nowMs 参数驱动；每个用例使用新的 reducer，避免共享计时状态。
 */
class SessionReducerTest {

    private val id = Fixtures.sessionId

    private fun startedReducer(
        answerDelayMs: Int = AgentConfig.DEFAULT_ANSWER_DELAY_MS,
        maxCallDurationMs: Int = AgentConfig.DEFAULT_MAX_CALL_DURATION_MS,
        id: SessionId = Fixtures.sessionId,
    ): SessionReducer {
        val reducer = SessionReducer(
            id = id,
            config = Fixtures.config(
                answerDelayMs = answerDelayMs,
                maxCallDurationMs = maxCallDurationMs,
            ),
        )
        reducer.reduce(SessionEvent.ReadyChanged(ready = true), 0L)
        reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing(id)), 0L)
        return reducer
    }

    /** 从 0 ms 起算的一通可接管来电，在 [nowMs] 处单次 Tick 产生的接听命令。 */
    private fun answerEffectsAt(nowMs: Long): List<SessionEffect> =
        startedReducer().reduce(SessionEvent.Tick, nowMs)
            .filter { it == SessionEffect.SendCommand(CallCommand.Answer) }

    /** 已由 AI 接听并在 [activeAt] 观察到 ACTIVE 的会话。 */
    private fun activeReducer(activeAt: Long = 3_000L): SessionReducer {
        val reducer = startedReducer()
        reducer.reduce(SessionEvent.Tick, activeAt)
        reducer.reduce(SessionEvent.StateChanged(Fixtures.answered()), activeAt)
        assertEquals(SessionState.ACTIVE_AI, reducer.state)
        return reducer
    }

    private fun List<SessionEffect>.answerCommands(): List<CallCommand> =
        filterIsInstance<SessionEffect.SendCommand>()
            .map { it.command }
            .filter { it == CallCommand.Answer }

    private fun List<SessionEffect>.hasCommand(command: CallCommand): Boolean =
        contains(SessionEffect.SendCommand(command))

    private fun List<SessionEffect>.hasHangup(): Boolean = hasCommand(CallCommand.Hangup)

    @Test
    fun answerOnlyOnceAtDeadline() {
        assertEquals(0, answerEffectsAt(2_999).size)
        assertEquals(1, answerEffectsAt(3_000).size)

        // 重复 Tick（例如父/子类重复回调后仍在轮询）不得补发第二条接听命令。
        // 时间由 fixtures 的固定时钟提供，reducer 不读取系统时钟。
        val clock = Fixtures.FixedClock()
        val reducer = startedReducer()
        clock.set(3_000)
        assertEquals(1, reducer.reduce(SessionEvent.Tick, clock.nowMs()).answerCommands().size)
        clock.advance(1)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, clock.nowMs()).size)
        clock.advance(5_999)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, clock.nowMs()).size)
    }

    @Test
    fun duplicateRingingCallbacksDoNotRestartOrDuplicateTheAnswer() {
        val reducer = startedReducer()
        // 同一个 Call 的重复 RINGING 快照不得重置倒计时，也不得再发准备副作用。
        assertTrue(reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 1_000L).isEmpty())
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 2_999).answerCommands().size)
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
    }

    @Test
    fun manualAnswerCancelsPending() {
        val reducer = startedReducer()

        val takeover = reducer.reduce(SessionEvent.Takeover(TakeoverReason.USER_ANSWERED), 2_999)

        // 人工接听必须先撤销 AI 所有权。
        assertTrue(takeover.contains(SessionEffect.RevokeLease(id)))
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().isEmpty())
        assertTrue(reducer.reduce(SessionEvent.Tick, 10_000).answerCommands().isEmpty())
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    @Test
    fun staleEpochIgnored() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())
        reducer.reduce(SessionEvent.ReadyChanged(ready = true), 0L)

        // 旧进程代次的快照（同一 callId）不得让本会话进入可接听状态。
        val stale = Fixtures.ringing(id = Fixtures.sessionId(processEpoch = "epoch-0"))
        assertTrue(reducer.reduce(SessionEvent.StateChanged(stale), 0L).isEmpty())
        assertTrue(reducer.reduce(SessionEvent.Tick, 2_999).answerCommands().isEmpty())
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().isEmpty())
        assertEquals(SessionState.IDLE, reducer.state)
    }

    @Test
    fun answerWaitsForReadyHandshake() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())

        reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 0L)
        // 名单已命中但 APK 尚未 READY：停在 ELIGIBLE，不声明租约/隔离、不接听。
        assertEquals(SessionState.ELIGIBLE, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).isEmpty())

        val ready = reducer.reduce(SessionEvent.ReadyChanged(ready = true), 4_000)
        assertTrue(ready.contains(SessionEffect.AcquireLease(id)))
        assertTrue(ready.contains(SessionEffect.IsolateMicrophone))
        assertEquals(SessionState.ANSWER_PENDING, reducer.state)

        assertEquals(0, reducer.reduce(SessionEvent.Tick, 6_999).answerCommands().size)
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 7_000).answerCommands().size)
    }

    @Test
    fun ineligibleCallNeverAnswers() {
        val reducer = SessionReducer(id = id, config = Fixtures.config(blacklist = emptyList()))
        reducer.reduce(SessionEvent.ReadyChanged(ready = true), 0L)
        reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 0L)

        assertEquals(SessionState.IDLE, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 30_000).isEmpty())
    }

    @Test
    fun conversationStartsOnlyAfterActiveSnapshot() {
        val reducer = startedReducer()
        reducer.reduce(SessionEvent.Tick, 3_000)

        // 接听命令已发出但还没观察到 ACTIVE：不得打开双向流。
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_500).none { it == SessionEffect.StartConversation })
        val active = reducer.reduce(SessionEvent.StateChanged(Fixtures.answered()), 3_600)

        assertTrue(active.contains(SessionEffect.StartConversation))
        assertEquals(SessionState.ACTIVE_AI, reducer.state)
    }

    @Test
    fun userAnsweredSnapshotDuringCountdownHandsOffWithoutAnswer() {
        val reducer = startedReducer()

        val effects = reducer.reduce(SessionEvent.StateChanged(Fixtures.answered()), 1_000)

        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(effects.contains(SessionEffect.RestoreMicrophone(id)))
        assertTrue(effects.none { it == SessionEffect.StartConversation })
        assertEquals(SessionState.HANDOFF, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).isEmpty())
    }

    @Test
    fun peerLossAfterHeartbeatTimeoutStopsAudioThenTerminatesOwnedCall() {
        val reducer = activeReducer(activeAt = 3_000)
        reducer.heartbeat(PeerRole.HOOK, 3_000)

        // 4999 ms 还没到 5000 ms 心跳超时。
        assertFalse(reducer.reduce(SessionEvent.Tick, 7_999).hasHangup())

        val lost = reducer.reduce(SessionEvent.Tick, 8_000)

        assertTrue(lost.contains(SessionEffect.StopConversation))
        assertTrue(lost.contains(SessionEffect.SendCommand(CallCommand.Hangup)))
        assertTrue(lost.contains(SessionEffect.RevokeLease(id)))
        // 先停音频、再故障终止精确通话。
        assertTrue(
            lost.indexOf(SessionEffect.StopConversation) <
                lost.indexOf(SessionEffect.SendCommand(CallCommand.Hangup)),
        )
        assertEquals(SessionState.ENDING, reducer.state)
        // 终止只发生一次。
        assertTrue(reducer.reduce(SessionEvent.Tick, 9_000).none { it is SessionEffect.SendCommand })
    }

    @Test
    fun peerLostEventAdjudicatesImmediately() {
        val reducer = activeReducer()

        val effects = reducer.reduce(SessionEvent.PeerLost(PeerRole.APP), 4_000)

        assertTrue(effects.contains(SessionEffect.SendCommand(CallCommand.Hangup)))
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun peerLossBeforeAnswerKeepsCallRingingForHuman() {
        val reducer = startedReducer()

        val effects = reducer.reduce(SessionEvent.PeerLost(PeerRole.APP), 1_000)

        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        // 还没接听：不得挂断用户仍在响铃的电话。
        assertTrue(effects.none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.HANDOFF, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).isEmpty())
    }

    @Test
    fun audioFailureKeepsIsolationAndTerminatesAfterHandoffWindow() {
        val reducer = activeReducer(activeAt = 3_000)

        val failure = reducer.onAudioFailure(4_000)

        assertTrue(failure.contains(SessionEffect.StopConversation))
        assertTrue(failure.any { it is SessionEffect.NotifyUser })
        assertFalse(failure.hasHangup())
        // 故障后保持隔离：在确认断开前不得恢复麦克风。
        assertTrue(failure.none { it is SessionEffect.RestoreMicrophone })
        assertEquals(SessionState.HANDOFF, reducer.state)

        assertFalse(reducer.reduce(SessionEvent.Tick, 13_999).hasHangup())
        val expired = reducer.reduce(SessionEvent.Tick, 14_000)

        assertTrue(expired.hasHangup())
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun manualTakeoverDuringAudioFailureWindowPreventsHangup() {
        val reducer = activeReducer(activeAt = 3_000)
        reducer.onAudioFailure(4_000)

        val takeover = reducer.reduce(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED), 5_000)

        assertTrue(takeover.contains(SessionEffect.RevokeLease(id)))
        assertTrue(takeover.contains(SessionEffect.RestoreMicrophone(id)))
        assertFalse(reducer.reduce(SessionEvent.Tick, 60_000).hasHangup())
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    @Test
    fun maxDurationTerminatesOnlyAfterActiveDuration() {
        val reducer = activeReducer(activeAt = 3_000)

        assertFalse(reducer.reduce(SessionEvent.Tick, 182_999).hasHangup())
        val expired = reducer.reduce(SessionEvent.Tick, 183_000)

        assertTrue(expired.contains(SessionEffect.StopConversation))
        assertTrue(expired.hasHangup())
        assertEquals(SessionState.ENDING, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 190_000).none { it is SessionEffect.SendCommand })
    }

    @Test
    fun maxDurationDoesNotHangUpCallHandedBackToHuman() {
        val reducer = activeReducer(activeAt = 3_000)
        reducer.reduce(SessionEvent.Takeover(TakeoverReason.USER_ANSWERED), 10_000)

        assertFalse(reducer.reduce(SessionEvent.Tick, 600_000).hasHangup())
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    @Test
    fun manualTakeoverWinsOverDueTimeout() {
        val reducer = activeReducer(activeAt = 3_000)

        // 人工交还先进入单写者序列；随后到期的 180000 ms 不得再挂断。
        reducer.reduce(SessionEvent.Takeover(TakeoverReason.USER_ANSWERED), 183_000)

        assertFalse(reducer.reduce(SessionEvent.Tick, 183_000).hasHangup())
    }

    @Test
    fun timeoutAdjudicatedFirstIsNotUndoneByLateTakeover() {
        val reducer = activeReducer(activeAt = 3_000)
        reducer.reduce(SessionEvent.Tick, 183_000)

        val late = reducer.reduce(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED), 183_001)

        assertTrue(late.none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun disconnectAfterTerminationRestoresMicrophone() {
        val reducer = activeReducer(activeAt = 3_000)
        reducer.reduce(SessionEvent.Tick, 183_000)

        val end = reducer.reduce(SessionEvent.StateChanged(Fixtures.disconnected()), 184_000)

        assertTrue(end.contains(SessionEffect.RestoreMicrophone(id)))
        assertTrue(end.none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.ENDED, reducer.state)
    }

    @Test
    fun holdingBeforeAnswerCancelsPending() {
        val reducer = startedReducer()

        // 快照显示通话已被置为保持：不再处于可接听状态，撤销待执行接听。
        val effects = reducer.reduce(
            SessionEvent.StateChanged(Fixtures.snapshot(state = CallState.HOLDING)),
            1_000,
        )

        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    @Test
    fun staleActiveAfterTerminationKeepsIsolationUntilDisconnect() {
        val reducer = activeReducer(activeAt = 3_000)
        reducer.reduce(SessionEvent.Tick, 183_000)

        // 终止后迟到的 ACTIVE 快照不得把状态拉回 HANDOFF，也不得提前恢复麦克风。
        assertTrue(reducer.reduce(SessionEvent.StateChanged(Fixtures.answered()), 183_500L).isEmpty())
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun disableActsImmediatelyOnActiveCall() {
        val reducer = activeReducer(activeAt = 3_000)

        val effects = reducer.onConfigChanged(Fixtures.config(enabled = false))

        assertTrue(effects.contains(SessionEffect.StopConversation))
        assertTrue(effects.hasHangup())
        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun disableBeforeAnswerCancelsWithoutCommand() {
        val reducer = startedReducer()

        val effects = reducer.onConfigChanged(Fixtures.config(enabled = false))

        assertTrue(effects.none { it is SessionEffect.SendCommand })
        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    @Test
    fun configUpdateAppliesToNextCallOnly() {
        val inFlight = startedReducer(answerDelayMs = 3_000)
        val nextConfig = Fixtures.config(answerDelayMs = 10_000)

        // 热更新只影响下一通：本会话保留开始时冻结的快照。
        assertTrue(inFlight.onConfigChanged(nextConfig).isEmpty())
        assertEquals(1, inFlight.reduce(SessionEvent.Tick, 3_000).answerCommands().size)

        val nextId = Fixtures.sessionId(callId = "call-b")
        val next = SessionReducer(id = nextId, config = nextConfig)
        next.reduce(SessionEvent.ReadyChanged(ready = true), 0L)
        next.reduce(SessionEvent.StateChanged(Fixtures.ringing(nextId)), 0L)

        assertEquals(0, next.reduce(SessionEvent.Tick, 9_999).answerCommands().size)
        assertEquals(1, next.reduce(SessionEvent.Tick, 10_000).answerCommands().size)
    }
}

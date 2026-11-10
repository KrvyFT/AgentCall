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
        // READY 确认后进入 PREPARING：接听前的租约/隔离在倒计时窗口内就绪。
        assertTrue(ready.contains(SessionEffect.AcquireLease(id)))
        assertTrue(ready.contains(SessionEffect.IsolateMicrophone))
        assertEquals(SessionState.PREPARING, reducer.state)

        assertEquals(0, reducer.reduce(SessionEvent.Tick, 6_999).answerCommands().size)
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 7_000).answerCommands().size)
        assertEquals(SessionState.ANSWER_PENDING, reducer.state)
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

    // ---- 复查修复：可观察的 PREPARING 窗口与完整状态路径 ----

    @Test
    fun observedStatePathIsIdleEligiblePreparingAnswerPendingActiveAi() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())
        val observed = mutableListOf(reducer.state)

        // READY 晚于 RINGING：ELIGIBLE 与 PREPARING 各自可观察。
        reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 0L)
        observed += reducer.state
        reducer.reduce(SessionEvent.ReadyChanged(ready = true), 0L)
        observed += reducer.state

        // 倒计时基准点是进入 PREPARING 的时刻：2999 不接听，3000 恰好一次。
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 2_999).answerCommands().size)
        observed += reducer.state
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        observed += reducer.state

        reducer.reduce(SessionEvent.StateChanged(Fixtures.answered()), 3_000)
        observed += reducer.state

        // 依次观察到 IDLE→ELIGIBLE→PREPARING→ANSWER_PENDING→ACTIVE_AI；
        // 2999 ms 的 Tick 不得离开 PREPARING 窗口。
        assertEquals(
            listOf(
                SessionState.IDLE,
                SessionState.ELIGIBLE,
                SessionState.PREPARING,
                SessionState.PREPARING,
                SessionState.ANSWER_PENDING,
                SessionState.ACTIVE_AI,
            ),
            observed,
        )
    }

    @Test
    fun preparingEmitsLeaseAndIsolationOnEntryWhenReadyFirst() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())
        reducer.reduce(SessionEvent.ReadyChanged(ready = true), 0L)

        // READY 已就绪：名单命中后立即进入 PREPARING，并在进入时声明接听前的租约与麦克风隔离。
        val prep = reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 0L)

        assertEquals(SessionState.PREPARING, reducer.state)
        assertEquals(
            listOf(SessionEffect.AcquireLease(id), SessionEffect.IsolateMicrophone),
            prep,
        )
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 2_999).answerCommands().size)
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        assertEquals(SessionState.ANSWER_PENDING, reducer.state)
    }

    // ---- 复查修复：总开关关闭在每个状态都有明确行为 ----

    @Test
    fun disableInIdleLatchesAndRefusesLaterRinging() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())

        // 本通 RINGING 尚未到达时关闭总开关：IDLE 不得丢弃停用信号。
        assertTrue(reducer.onConfigChanged(Fixtures.config(enabled = false)).isEmpty())
        reducer.reduce(SessionEvent.ReadyChanged(ready = true), 0L)

        assertTrue(reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 0L).isEmpty())
        assertEquals(SessionState.IDLE, reducer.state)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 2_999).answerCommands().size)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 30_000).answerCommands().size)
    }

    @Test
    fun disableInEligibleHandsBackWithoutCommand() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())
        reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 0L)
        assertEquals(SessionState.ELIGIBLE, reducer.state)

        val effects = reducer.onConfigChanged(Fixtures.config(enabled = false))

        // ELIGIBLE 还没有租约/隔离：没有资源要撤销，但必须交还人工并停在这里。
        assertEquals(SessionState.HANDOFF, reducer.state)
        assertTrue(effects.none { it is SessionEffect.SendCommand })
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().isEmpty())
        // READY 迟到也不得重新准备接听。
        assertTrue(
            reducer.reduce(SessionEvent.ReadyChanged(ready = true), 4_000)
                .none { it == SessionEffect.AcquireLease(id) },
        )
    }

    @Test
    fun disableInPreparingRevokesLeaseAndRestoresMicrophone() {
        val reducer = startedReducer()
        assertEquals(SessionState.PREPARING, reducer.state)

        val effects = reducer.onConfigChanged(Fixtures.config(enabled = false))

        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(effects.contains(SessionEffect.RestoreMicrophone(id)))
        assertTrue(effects.none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.HANDOFF, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().isEmpty())
    }

    @Test
    fun disableInAnswerPendingTerminatesIssuedCall() {
        val reducer = startedReducer()
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        assertEquals(SessionState.ANSWER_PENDING, reducer.state)

        val effects = reducer.onConfigChanged(Fixtures.config(enabled = false))

        // 接听命令已下发、ACTIVE 尚未确认：不能再交还人工，必须终止并保持隔离。
        assertTrue(effects.hasHangup())
        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(effects.none { it is SessionEffect.RestoreMicrophone })
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun disableDuringAudioFailureWindowTerminatesImmediately() {
        val reducer = activeReducer(activeAt = 3_000)
        reducer.onAudioFailure(4_000)
        assertEquals(SessionState.HANDOFF, reducer.state)

        // 音频故障的 10 s 窗口仍在等待人工接管，但总开关关闭必须立即终止，不能等窗口到期。
        val effects = reducer.onConfigChanged(Fixtures.config(enabled = false))

        assertTrue(effects.hasHangup())
        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertEquals(SessionState.ENDING, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 13_999).none { it is SessionEffect.SendCommand })
    }

    // ---- 复查修复：已知失联的对端不得进入接听 ----

    @Test
    fun peerLostInIdleRefusesRingingUntilHeartbeatReturns() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())
        reducer.heartbeat(PeerRole.HOOK, 0L)
        assertTrue(reducer.reduce(SessionEvent.PeerLost(PeerRole.HOOK), 1_000).isEmpty())
        assertEquals(SessionState.IDLE, reducer.state)
        reducer.reduce(SessionEvent.ReadyChanged(ready = true), 1_000)

        // 已知失联的对端尚未恢复：不得进入 ELIGIBLE，更不得进入倒计时。
        assertTrue(reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 1_000).isEmpty())
        assertEquals(SessionState.IDLE, reducer.state)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 4_000).answerCommands().size)

        // 心跳恢复证明对端重新在线，之后的 RINGING 才允许接管。
        reducer.heartbeat(PeerRole.HOOK, 5_000)
        val prep = reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 5_000)
        assertTrue(prep.contains(SessionEffect.AcquireLease(id)))
        assertEquals(SessionState.PREPARING, reducer.state)
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 8_000).answerCommands().size)
    }

    // ---- 复查修复：接听前拒接/挂断后不再执行自动命令（spec §8） ----

    @Test
    fun disconnectDuringPreparingCancelsPendingAnswer() {
        val reducer = startedReducer()
        assertEquals(SessionState.PREPARING, reducer.state)

        val effects = reducer.reduce(SessionEvent.StateChanged(Fixtures.disconnected()), 1_000)

        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(effects.contains(SessionEffect.RestoreMicrophone(id)))
        assertEquals(SessionState.ENDED, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().isEmpty())
        assertTrue(reducer.reduce(SessionEvent.Tick, 30_000).none { it is SessionEffect.SendCommand })
    }

    @Test
    fun disconnectAfterAnswerIssuedSuppressesFurtherCommands() {
        val reducer = startedReducer()
        assertEquals(1, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        assertEquals(SessionState.ANSWER_PENDING, reducer.state)

        val effects = reducer.reduce(SessionEvent.StateChanged(Fixtures.disconnected()), 3_500)

        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(effects.none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.ENDED, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 4_000).isEmpty())
    }

    // ---- 复查修复：API/会话失败立即终止，不共享音频失败的 10 s 窗口 ----

    @Test
    fun apiFailureTerminatesOwnedCallWithoutHandoffWindow() {
        val reducer = activeReducer(activeAt = 3_000)

        val effects = reducer.onApiFailure()

        // 有故障提示（是否有有效上行由调用方判定），随后立即终止 AI 通话。
        assertTrue(effects.any { it is SessionEffect.NotifyUser })
        assertTrue(effects.contains(SessionEffect.StopConversation))
        assertTrue(effects.hasHangup())
        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        // 没有 10000 ms 人工接管窗口；麦克风保持隔离直到 DISCONNECTED 确认。
        assertTrue(effects.none { it is SessionEffect.RestoreMicrophone })
        assertEquals(SessionState.ENDING, reducer.state)
        assertTrue(reducer.reduce(SessionEvent.Tick, 3_001).none { it is SessionEffect.SendCommand })
        assertTrue(reducer.reduce(SessionEvent.Tick, 13_000).none { it is SessionEffect.SendCommand })
    }

    // ---- 任务 7D：接听前 READY 丢失必须永久放弃本通自动接听（spec §8） ----

    @Test
    fun readyLostDuringPreparingDoesNotAnswerAtDeadline() {
        val reducer = startedReducer()
        assertEquals(SessionState.PREPARING, reducer.state)

        // 倒计时窗口内 READY 丢失：本通自动接听被永久放弃并保守交还。
        reducer.reduce(SessionEvent.ReadyChanged(ready = false), 1_000)
        assertEquals(SessionState.HANDOFF, reducer.state)

        assertEquals(0, reducer.reduce(SessionEvent.Tick, 2_999).answerCommands().size)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 30_000).answerCommands().size)
    }

    @Test
    fun readyLostDuringPreparingReleasesLeaseAndIsolation() {
        val reducer = startedReducer()
        assertEquals(SessionState.PREPARING, reducer.state)

        val effects = reducer.reduce(SessionEvent.ReadyChanged(ready = false), 1_000)

        // 安全顺序：先撤销租约（所有权与后续命令都以租约为基础），再恢复麦克风。
        assertEquals(
            listOf(SessionEffect.RevokeLease(id), SessionEffect.RestoreMicrophone(id)),
            effects,
        )
        // 模块从未接听这通电话：READY 丢失不得产生任何通话命令，尤其不得挂断。
        assertTrue(effects.none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.HANDOFF, reducer.state)

        // 重复的 READY 丢失与到期 Tick 不得重复撤销/恢复。
        assertTrue(reducer.reduce(SessionEvent.ReadyChanged(ready = false), 1_500).isEmpty())
        assertTrue(reducer.reduce(SessionEvent.Tick, 30_000).isEmpty())

        // 隔离只恢复一次：随后确认断开不得再发一次 RestoreMicrophone。
        val disconnected = reducer.reduce(
            SessionEvent.StateChanged(Fixtures.disconnected()),
            30_001,
        )
        assertTrue(disconnected.none { it is SessionEffect.RestoreMicrophone })
        assertEquals(SessionState.ENDED, reducer.state)
    }

    @Test
    fun readyRecoveryAfterLossDoesNotResurrectTheAnswer() {
        val reducer = startedReducer()
        reducer.reduce(SessionEvent.ReadyChanged(ready = false), 1_000)
        assertEquals(SessionState.HANDOFF, reducer.state)

        // READY 在 2500 ms 恢复：不得重新取得租约/隔离，也不得恢复倒计时。
        val recovery = reducer.reduce(SessionEvent.ReadyChanged(ready = true), 2_500)
        assertEquals(emptyList<SessionEffect>(), recovery)
        assertEquals(SessionState.HANDOFF, reducer.state)

        assertEquals(0, reducer.reduce(SessionEvent.Tick, 2_999).answerCommands().size)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 30_000).answerCommands().size)
    }

    @Test
    fun readyLossDuringEligibleHandsBackAndNeverAnswers() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())
        reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 0L)
        assertEquals(SessionState.ELIGIBLE, reducer.state)

        // ELIGIBLE 尚未取得租约/隔离：READY 丢失只保守交还，不产生资源副作用。
        val effects = reducer.reduce(SessionEvent.ReadyChanged(ready = false), 1_000)
        assertTrue(effects.isEmpty())
        assertEquals(SessionState.HANDOFF, reducer.state)

        // READY 迟到恢复也不得重新准备或接听；原响铃保留给人工。
        assertTrue(reducer.reduce(SessionEvent.ReadyChanged(ready = true), 2_000).isEmpty())
        assertEquals(SessionState.HANDOFF, reducer.state)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 3_000).answerCommands().size)
        assertEquals(0, reducer.reduce(SessionEvent.Tick, 30_000).answerCommands().size)
    }

    @Test
    fun readyLossBeforePreparingAcquisitionEmitsNoSpuriousRestoreOrRevoke() {
        val reducer = SessionReducer(id = id, config = Fixtures.config())

        // IDLE 时的 READY 丢失与本次会话无关：无副作用。
        assertTrue(reducer.reduce(SessionEvent.ReadyChanged(ready = false), 0L).isEmpty())
        assertEquals(SessionState.IDLE, reducer.state)

        // 名单已命中但 READY 未确认（ELIGIBLE）：没有取得租约/隔离，
        // READY 丢失不得发出任何撤销或恢复副作用。
        reducer.reduce(SessionEvent.StateChanged(Fixtures.ringing()), 100L)
        assertEquals(SessionState.ELIGIBLE, reducer.state)

        val effects = reducer.reduce(SessionEvent.ReadyChanged(ready = false), 200L)
        assertTrue(effects.none { it is SessionEffect.RevokeLease })
        assertTrue(effects.none { it is SessionEffect.RestoreMicrophone })
        assertTrue(effects.isEmpty())
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    // ---- 7D 复审补充：接听之后的 READY 丢失是 no-op（不得撤销/恢复，180 s 上限照常生效） ----

    @Test
    fun readyLossAfterTheAnswerIsANoOpAndTheCapStillHangsUp() {
        val reducer = startedReducer()
        // 3 s 倒计时到期：唯一的接听命令下发，进入 ANSWER_PENDING。
        assertEquals(
            listOf(SessionEffect.SendCommand(CallCommand.Answer)),
            reducer.reduce(SessionEvent.Tick, 3_000),
        )
        assertEquals(SessionState.ANSWER_PENDING, reducer.state)

        // 接听之后 READY 丢失：状态不变、没有任何资源副作用（租约/隔离是接听的前置条件，
        // 此时归 AI 的通话已建立，不能因为 READY 抖动就撤销）。
        val loss = reducer.reduce(SessionEvent.ReadyChanged(ready = false), 3_500)
        assertTrue("READY loss after the answer must be a no-op, got " + loss, loss.isEmpty())
        assertEquals(SessionState.ANSWER_PENDING, reducer.state)

        // 已下发的接听不受影响：ACTIVE 快照照常打开会话；180 s 上限从观察到 ACTIVE 起算。
        val active = reducer.reduce(SessionEvent.StateChanged(Fixtures.answered()), 3_600)
        assertTrue(active.contains(SessionEffect.StartConversation))
        assertEquals(SessionState.ACTIVE_AI, reducer.state)

        assertTrue(reducer.reduce(SessionEvent.Tick, 3_600 + 179_999).none { it is SessionEffect.SendCommand })
        val cap = reducer.reduce(SessionEvent.Tick, 3_600 + 180_000)
        assertTrue("the 180 s cap must still hang up a ready-lost active call", cap.hasHangup())
        assertTrue(cap.contains(SessionEffect.RevokeLease(id)))
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun readyLossInActiveAiKeepsTheCallAndTheTimeout() {
        val reducer = activeReducer(activeAt = 3_000)

        val loss = reducer.reduce(SessionEvent.ReadyChanged(ready = false), 3_500)

        assertTrue("READY loss in ACTIVE_AI must be a no-op, got " + loss, loss.isEmpty())
        assertEquals(SessionState.ACTIVE_AI, reducer.state)
        assertTrue(loss.none { it is SessionEffect.RevokeLease })
        assertTrue(loss.none { it is SessionEffect.RestoreMicrophone })

        // 180 s 上限仍然生效：到点挂断并撤销租约，麦克风一直隔离到断开确认。
        assertTrue(reducer.reduce(SessionEvent.Tick, 182_999).none { it is SessionEffect.SendCommand })
        val cap = reducer.reduce(SessionEvent.Tick, 183_000)
        assertTrue(cap.hasHangup())
        assertTrue(cap.contains(SessionEffect.RevokeLease(id)))
        assertTrue(cap.none { it is SessionEffect.RestoreMicrophone })
        assertEquals(SessionState.ENDING, reducer.state)
    }

    @Test
    fun readyLossInHandoffAndEndedIsANoOp() {
        val handedBack = activeReducer(activeAt = 3_000)
        val takeover = handedBack.reduce(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED), 3_500)
        assertEquals(SessionState.HANDOFF, handedBack.state)
        assertTrue(takeover.contains(SessionEffect.RevokeLease(id)))
        assertTrue(takeover.contains(SessionEffect.RestoreMicrophone(id)))

        val afterHandoff = handedBack.reduce(SessionEvent.ReadyChanged(ready = false), 4_000)
        assertTrue("READY loss in HANDOFF must not revoke/restore twice, got " + afterHandoff, afterHandoff.isEmpty())
        assertEquals(SessionState.HANDOFF, handedBack.state)

        val ended = activeReducer(activeAt = 3_000)
        ended.reduce(SessionEvent.StateChanged(Fixtures.disconnected()), 4_000)
        assertEquals(SessionState.ENDED, ended.state)
        val afterEnded = ended.reduce(SessionEvent.ReadyChanged(ready = false), 4_500)
        assertTrue("READY loss after ENDED must be a no-op, got " + afterEnded, afterEnded.isEmpty())
        assertEquals(SessionState.ENDED, ended.state)
    }

    // ------------------------------------------------------------------ 事件会话绑定（7B3 FIX ROUND 1）

    /**
     * [SessionEvent.Takeover] 现在携带产生它的 [SessionId]：别的/上一通电话的交还事实绝不能
     * 撤销当前会话（旧实现让协调器把任何 handback 作用到“当前会话”）。
     */
    @Test
    fun takeoverForAnotherSessionIsIgnoredButTheOwnedSessionStillHandsBack() {
        val reducer = activeReducer(activeAt = 3_000)

        val foreign = reducer.reduce(
            SessionEvent.Takeover(TakeoverReason.UNKNOWN, Fixtures.sessionId(callId = "call-b")),
            4_000,
        )

        assertTrue("a handback for another call must be a no-op, got " + foreign, foreign.isEmpty())
        assertEquals(SessionState.ACTIVE_AI, reducer.state)

        val mine = reducer.reduce(SessionEvent.Takeover(TakeoverReason.UNKNOWN, id), 4_000)

        assertTrue(mine.contains(SessionEffect.StopConversation))
        assertTrue(mine.contains(SessionEffect.RevokeLease(id)))
        assertTrue(mine.contains(SessionEffect.RestoreMicrophone(id)))
        assertTrue("a handback must never hang up", mine.none { it is SessionEffect.SendCommand })
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    /** 请求没有携带会话（本地用户接管）时仍作用于本会话，保持既有行为。 */
    @Test
    fun takeoverWithoutASessionStillHandsBackTheOwnedCall() {
        val reducer = activeReducer(activeAt = 3_000)

        val effects = reducer.reduce(SessionEvent.Takeover(TakeoverReason.USER_REQUESTED), 4_000)

        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertEquals(SessionState.HANDOFF, reducer.state)
    }

    /**
     * [SessionEvent.SessionEnded] 是“通话已经不存在”的事实（HOOK 失联/监督端确认断开）：
     * 释放所有权、租约与隔离，但**绝不**下发挂断（电话已经没了）。
     */
    @Test
    fun sessionEndedForTheOwnedSessionReleasesEverythingWithoutHangup() {
        val reducer = activeReducer(activeAt = 3_000)

        val effects = reducer.reduce(SessionEvent.SessionEnded(id), 5_000)

        assertEquals(SessionState.ENDED, reducer.state)
        assertTrue(effects.contains(SessionEffect.StopConversation))
        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(effects.contains(SessionEffect.RestoreMicrophone(id)))
        assertFalse("the call is already gone: no Hangup may be sent", effects.hasHangup())
        // 结束之后不得再有任何命令（180 s 上限/倒计时都失效）。
        assertTrue(reducer.reduce(SessionEvent.Tick, 600_000).none { it is SessionEffect.SendCommand })
    }

    @Test
    fun sessionEndedForAnotherSessionIsIgnored() {
        val reducer = activeReducer(activeAt = 3_000)

        val effects = reducer.reduce(
            SessionEvent.SessionEnded(Fixtures.sessionId(callId = "call-b")),
            5_000,
        )

        assertTrue("a SessionEnded for another call must be a no-op, got " + effects, effects.isEmpty())
        assertEquals(SessionState.ACTIVE_AI, reducer.state)
        // 本会话仍然归 AI：其它会话的结束事实不改变它的 180 s 上限。
        assertTrue(reducer.reduce(SessionEvent.Tick, 183_000).hasHangup())
    }

    @Test
    fun sessionEndedDuringPreparationCancelsTheAnswerWithoutHangup() {
        val reducer = startedReducer()

        val effects = reducer.reduce(SessionEvent.SessionEnded(id), 1_000)

        assertEquals(SessionState.ENDED, reducer.state)
        assertTrue(effects.contains(SessionEffect.RevokeLease(id)))
        assertTrue(effects.contains(SessionEffect.RestoreMicrophone(id)))
        assertFalse(effects.hasHangup())
        assertFalse("no answer may be issued after the call ended", reducer.reduce(SessionEvent.Tick, 3_000).hasCommand(CallCommand.Answer))
    }
}

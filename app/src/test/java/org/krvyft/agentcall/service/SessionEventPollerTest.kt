package org.krvyft.agentcall.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.krvyft.agentcall.core.session.SessionReducer
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.EventsBatch
import org.krvyft.agentcall.ipc.SessionEventSource
import org.krvyft.agentcall.ipc.SessionWireEvent

/**
 * APK 侧事件轮询器的 JVM 测试（Task 7B3）。
 *
 * 锁定的不变量：
 * - **按 seq 严格去重**：重放/重复批次绝不能让协调器把一通电话处理两次；
 * - **cursor_ahead 必须重置为 0 再读**，不是“暂时没有事件”；
 * - **传输失败有界退避**并回传机器码，绝不伪造事件；
 * - **stop 之后零轮询、零投递**（服务停止即不再有自动接听输入）；
 * - **事件→reducer 的映射**：只有真实字段齐备的 RINGING 才可能进入 ELIGIBLE，缺一项就不投递；
 *   ReadyChanged 由服务就绪判定负责、PeerLost 不是事件流的事实，二者都不许编造。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionEventPollerTest {

    private val session = SessionId(processEpoch = "epoch-1", callId = "call-a")
    private val other = SessionId(processEpoch = "epoch-2", callId = "call-b")

    private val config = AgentConfig(
        enabled = true,
        blacklist = listOf(NUMBER),
        enabledPhoneAccounts = listOf("acct-1"),
        answerDelayMs = 3_000,
        maxCallDurationMs = 180_000,
    )

    // ------------------------------------------------------------------ 投递/去重

    @Test
    fun wireEventsAreDeliveredWithTheRealSessionAndSnapshotFields() = runTest {
        val source = FakeSource { cursor -> batch(ringing(1), nextSeq = 1, since = cursor) }
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(source, { published += it })
        poller.start()
        try {
            runCurrent()
            val event = published.single() as SessionEvent.StateChanged
            val snapshot = event.snapshot
            assertEquals(session, snapshot.id)
            assertEquals(NUMBER, snapshot.number)
            assertEquals(CallState.RINGING, snapshot.state)
            assertEquals(CallDirection.INCOMING, snapshot.direction)
            assertEquals(Presentation.ALLOWED, snapshot.presentation)
            assertEquals("acct-1", snapshot.phoneAccount)
            assertEquals(false, snapshot.isEmergency)
            assertEquals(false, snapshot.isConference)
            assertEquals(1, snapshot.activeCallCount)
            assertEquals(listOf(0L), source.cursors)
            assertEquals(1L, poller.lastSeq)

            advance(500)
            assertEquals("the next poll must carry the advanced cursor", listOf(0L, 1L), source.cursors)
        } finally {
            poller.stop()
        }
    }

    @Test
    fun replayedSequencesAreDedupedStrictlyBySeq() = runTest {
        // 故意无视游标，每次都重放 seq 1..2：轮询器必须只投递一次。
        val source = FakeSource { batch(ringing(1), ringing(2, other), nextSeq = 2, since = 0) }
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(source, { published += it })
        poller.start()
        try {
            advance(3_000)
            assertEquals("replays must never be delivered twice", 2, published.size)
            assertEquals(2L, poller.lastSeq)
            assertTrue("replays must be counted, got " + poller.duplicateEventCount, poller.duplicateEventCount > 0)
        } finally {
            poller.stop()
        }
    }

    // ------------------------------------------------------------------ 游标/退避

    @Test
    fun cursorAheadResetsThePollerCursorToZeroAndContinues() = runTest {
        var reset = false
        val source = FakeSource { cursor ->
            if (cursor > 0L && !reset) {
                reset = true
                BridgeRead.Unavailable(SessionEventPoller.ERROR_CURSOR_AHEAD)
            } else {
                batch(ringing(1), nextSeq = 1, since = cursor)
            }
        }
        val published = mutableListOf<SessionEvent>()
        val errors = mutableListOf<String>()
        val poller = TestScopePoller(source, { published += it }, initialCursor = 7L, onError = { errors += it })
        // 模拟“桥接重启后本地游标超前”：以本地已保存的游标启动。
        poller.start()
        try {
            runCurrent()
            assertEquals(listOf(7L), source.cursors)
            advance(500)
            assertEquals("cursor_ahead must reset to 0", listOf(7L, 0L), source.cursors)
            assertEquals(1, published.size)
            assertEquals("cursor_ahead is not a transport failure", emptyList<String>(), errors)
        } finally {
            poller.stop()
        }
    }

    @Test
    fun transportFailuresBackOffBoundedlyAndSurfaceTheirMachineCode() = runTest {
        val source = FakeSource { BridgeRead.Unavailable("bridge_unavailable") }
        source.now = { testScheduler.currentTime }
        val published = mutableListOf<SessionEvent>()
        val errors = mutableListOf<String>()
        val poller = TestScopePoller(source, { published += it }, onError = { errors += it })
        poller.start()
        try {
            advance(12_000)
            assertEquals(
                "backoff must be bounded (500/1000/2000/4000/4000 ms)",
                listOf(0L, 500L, 1_500L, 3_500L, 7_500L, 11_500L),
                source.times,
            )
            assertEquals(List(6) { "bridge_unavailable" }, errors)
            assertEquals("a failed poll must never fabricate events", emptyList<SessionEvent>(), published)
            assertTrue(poller.failureCount >= 6)
        } finally {
            poller.stop()
        }
    }

    @Test
    fun aHangingSourceIsBoundedAndRetriedWithBackoff() = runTest {
        val source = FakeSource { awaitCancellation() }
        val published = mutableListOf<SessionEvent>()
        val errors = mutableListOf<String>()
        val poller = TestScopePoller(source, { published += it }, onError = { errors += it })
        poller.start()
        try {
            advance(5_000)
            assertEquals(listOf(SessionEventPoller.ERROR_POLL_TIMEOUT), errors)
            assertEquals("a timed-out poll must never deliver anything", emptyList<SessionEvent>(), published)
            assertEquals(1, source.cursors.size)

            advance(500)
            assertEquals("the timed-out poll is retried after the bounded backoff", 2, source.cursors.size)
            advance(5_000)
            assertEquals(2, errors.size)
        } finally {
            poller.stop()
        }
    }

    @Test
    fun failuresRecoverToTheNormalCadenceAfterASuccess() = runTest {
        var fail = true
        val source = FakeSource { cursor ->
            if (fail) {
                fail = false
                BridgeRead.Unavailable("bridge_unavailable")
            } else {
                batch(nextSeq = cursor, since = cursor)
            }
        }
        source.now = { testScheduler.currentTime }
        val poller = TestScopePoller(source, {})
        poller.start()
        try {
            runCurrent()
            assertEquals(1, source.times.size)
            advance(500)
            assertEquals(2, source.times.size)
            advance(500)
            assertEquals("a successful poll must restore the 500 ms cadence", 3, source.times.size)
            assertEquals(0L, poller.failureCount)
        } finally {
            poller.stop()
        }
    }

    // ------------------------------------------------------------------ 停止

    @Test
    fun nothingIsPolledBeforeStartAndNothingAfterStop() = runTest {
        val source = FakeSource { cursor -> batch(nextSeq = cursor, since = cursor) }
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(source, { published += it })

        advance(5_000)
        assertEquals("no poll may happen before start()", emptyList<Long>(), source.cursors)

        poller.start()
        runCurrent()
        advance(1_100)
        val pollsBeforeStop = source.cursors.size
        assertTrue(pollsBeforeStop >= 3)

        poller.stop()
        runCurrent()
        advance(60_000)
        assertEquals("a stopped poller must not poll", pollsBeforeStop, source.cursors.size)
        assertEquals(emptyList<SessionEvent>(), published)

        poller.start()
        advance(5_000)
        assertEquals("start() after stop() must be a no-op", pollsBeforeStop, source.cursors.size)
    }

    // ------------------------------------------------------------------ 事件→reducer

    @Test
    fun ringingReachesEligibleOnlyWhenEveryReducerGatePasses() {
        // 基线：真实字段齐备、命中黑名单的一路入站响铃。
        assertEquals(SessionState.ELIGIBLE, reduceState(SessionEventMapping.map(ringing(1)).single()))
        // READY 后同一事件才进入 PREPARING（倒计时仍由 reducer 掌管）。
        val reducer = SessionReducer(session, config)
        reducer.reduce(SessionEvent.ReadyChanged(true), 0)
        reducer.reduce(SessionEventMapping.map(ringing(1)).single(), 0)
        assertEquals(SessionState.PREPARING, reducer.state)

        // 任一安全事实不满足都必须留在 IDLE（保留原响铃）。
        assertEquals(SessionState.IDLE, reduceState(SessionEventMapping.map(ringing(1, emergency = true)).single()))
        assertEquals(SessionState.IDLE, reduceState(SessionEventMapping.map(ringing(1, conference = true)).single()))
        assertEquals(SessionState.IDLE, reduceState(SessionEventMapping.map(ringing(1, activeCalls = 2)).single()))
        assertEquals(SessionState.IDLE, reduceState(SessionEventMapping.map(ringing(1, direction = CallDirection.OUTGOING)).single()))
        assertEquals(SessionState.IDLE, reduceState(SessionEventMapping.map(ringing(1, presentation = Presentation.RESTRICTED, number = null)).single()))
        assertEquals(SessionState.IDLE, reduceState(SessionEventMapping.map(ringing(1, number = "+8613800138001")).single()))
        assertEquals(SessionState.IDLE, reduceState(SessionEventMapping.map(ringing(1, number = null)).single()))
    }

    @Test
    fun theRealPolledCallReachesTheSessionCoordinator() = runTest {
        val source = FakeSource { cursor -> batch(ringing(1), nextSeq = 1, since = cursor) }
        val calls = FakeCallControl()
        val coordinator = SessionCoordinator(
            config = config,
            calls = calls,
            audio = NoopAudioBridge,
            conversation = NoopConversation,
            clock = MonotonicClock { testScheduler.currentTime },
            observer = SessionObserver(),
            dispatcher = StandardTestDispatcher(testScheduler),
            tickIntervalMs = 1_000,
        )
        coordinator.start()
        val poller = TestScopePoller(source, publish = coordinator::onEvent)
        poller.start()
        try {
            runCurrent()
            assertEquals("a polled RINGING must reach the coordinator", session, coordinator.activeSession)
            assertEquals(SessionState.ELIGIBLE, coordinator.state)
            assertTrue("no answer may be issued before READY", calls.sent.isEmpty())

            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            assertEquals(
                "the coordinator answers only after its own countdown",
                listOf(SentCommand(session, CallCommand.Answer)),
                calls.sent,
            )
        } finally {
            poller.stop()
            coordinator.close()
        }
    }

    @Test
    fun handbackFactsBecomeTakeoverAndNeverFabricateReadyOrPeerLoss() {
        val hookHandback = ringing(1, handbackReason = "user_unmuted")
        assertEquals(listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN)), SessionEventMapping.map(hookHandback))

        val supervisorHandback = supervisor(2, outcome = "handback_confirmed", state = CallState.ACTIVE)
        assertEquals(listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN)), SessionEventMapping.map(supervisorHandback))

        // 事件流里没有“就绪”或“对端失联”的事实源：绝不能从事件里编造。
        for (event in listOf(ringing(1), hookHandback, supervisor(2, "handback_confirmed", CallState.ACTIVE))) {
            for (mapped in SessionEventMapping.map(event)) {
                assertFalse(mapped is SessionEvent.ReadyChanged)
                assertFalse(mapped is SessionEvent.PeerLost)
            }
        }
    }

    @Test
    fun supervisorDisconnectNeedsTheHookSnapshotInsteadOfInventingDirectionAndPresentation() {
        // 监督端事实只有 state=DISCONNECTED；快照字段一律 null。绝不补方向/呈现/紧急等默认值。
        val supervisorDisconnect = supervisor(2, outcome = "call_disconnected", state = CallState.DISCONNECTED)
        assertEquals(emptyList<SessionEvent>(), SessionEventMapping.map(supervisorDisconnect))
        // 真实断开由同一 feed 里前面的 HOOK 完整快照表达。
        val hookDisconnect = ringing(1).copy(state = CallState.DISCONNECTED)
        val mapped = SessionEventMapping.map(hookDisconnect).single() as SessionEvent.StateChanged
        assertEquals(CallState.DISCONNECTED, mapped.snapshot.state)
        assertEquals(CallDirection.INCOMING, mapped.snapshot.direction)
    }

    @Test
    fun incompleteSnapshotsAreNeverFabricatedAndTheCursorStillAdvances() = runTest {
        val incomplete = ringing(1).copy(isEmergency = null)
        val source = FakeSource { cursor -> batch(incomplete, nextSeq = 1, since = cursor) }
        val published = mutableListOf<SessionEvent>()
        val errors = mutableListOf<String>()
        val poller = TestScopePoller(source, { published += it }, onError = { errors += it })
        poller.start()
        try {
            advance(1_000)
            assertEquals("an incomplete snapshot must never reach the coordinator", emptyList<SessionEvent>(), published)
            assertEquals(listOf(SessionEventPoller.ERROR_EVENT_INCOMPLETE), errors)
            assertEquals("the cursor must advance past the unusable event", 1L, poller.lastSeq)
            assertEquals(0L, source.cursors.first())
            assertTrue(
                "every later poll must carry the advanced cursor, got " + source.cursors,
                source.cursors.drop(1).all { it == 1L },
            )
        } finally {
            poller.stop()
        }
    }

    // ------------------------------------------------------------------ 装配

    private fun TestScope.TestScopePoller(
        source: SessionEventSource,
        publish: (SessionEvent) -> Unit,
        initialCursor: Long = 0L,
        onError: (String) -> Unit = {},
    ): SessionEventPoller = SessionEventPoller(
        source = source,
        publish = publish,
        scope = this,
        initialCursor = initialCursor,
        dispatcher = StandardTestDispatcher(testScheduler),
        pollIntervalMs = SessionEventPoller.POLL_INTERVAL_MS,
        backoffBaseMs = SessionEventPoller.BACKOFF_BASE_MS,
        backoffMaxMs = SessionEventPoller.BACKOFF_MAX_MS,
        onError = onError,
    )

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun reduceState(event: SessionEvent): SessionState {
        val reducer = SessionReducer(session, config)
        reducer.reduce(event, 0)
        return reducer.state
    }

    private fun batch(vararg events: SessionWireEvent, nextSeq: Long, since: Long): BridgeRead<EventsBatch> =
        BridgeRead.Available(EventsBatch(events.toList(), nextSeq, dropped = 0))

    private fun ringing(
        seq: Long,
        id: SessionId = session,
        number: String? = NUMBER,
        direction: CallDirection = CallDirection.INCOMING,
        presentation: Presentation = Presentation.ALLOWED,
        emergency: Boolean = false,
        conference: Boolean = false,
        activeCalls: Int = 1,
        handbackReason: String? = null,
    ): SessionWireEvent = SessionWireEvent(
        seq = seq,
        sessionId = id,
        epoch = id.processEpoch,
        state = CallState.RINGING,
        presentation = presentation,
        direction = direction,
        phoneAccount = "acct-1",
        isEmergency = emergency,
        isConference = conference,
        activeCallCount = activeCalls,
        number = number,
        source = "UNKNOWN",
        handbackReason = handbackReason,
        outcome = null,
    )

    private fun supervisor(seq: Long, outcome: String, state: CallState?): SessionWireEvent = SessionWireEvent(
        seq = seq,
        sessionId = session,
        epoch = session.processEpoch,
        state = state,
        source = "SUPERVISOR",
        handbackReason = if (outcome.startsWith("handback_")) "user_unmuted" else null,
        outcome = outcome,
    )

    private class FakeSource(
        private val respond: suspend (Long) -> BridgeRead<EventsBatch>,
    ) : SessionEventSource {
        val cursors = mutableListOf<Long>()
        val times = mutableListOf<Long>()
        var now: () -> Long = { 0L }

        override suspend fun pollEvents(sinceSeq: Long): BridgeRead<EventsBatch> {
            cursors += sinceSeq
            times += now()
            return respond(sinceSeq)
        }
    }

    private data class SentCommand(val id: SessionId, val command: CallCommand)

    private class FakeCallControl : CallControl {
        val sent = mutableListOf<SentCommand>()

        override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
            sent += SentCommand(id, command)
            return CommandResult.Accepted
        }
    }

    private object NoopAudioBridge : AudioBridge {
        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession = NoopAudioSession
    }

    private object NoopAudioSession : AudioSession {
        override val downlink: Flow<PcmChunk> = emptyFlow()

        override suspend fun writeUplink(audio: PcmAudio) = Unit

        override suspend fun stop() = Unit
    }

    private object NoopConversation : ConversationPort {
        override suspend fun run(id: SessionId, audio: AudioSession, config: AgentConfig) {
            awaitCancellation()
        }
    }

    private companion object {
        const val NUMBER = "+8613800138000"
    }
}
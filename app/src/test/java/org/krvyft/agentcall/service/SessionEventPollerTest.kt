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
import org.krvyft.agentcall.core.session.LeaseRegistry
import org.krvyft.agentcall.core.session.SessionReducer
import org.krvyft.agentcall.ipc.BridgeClient
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.EventsBatch
import org.krvyft.agentcall.ipc.SessionEpochSource
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
    fun handbackFactsBecomeSessionBoundTakeoverAndNeverFabricateReadyOrPeerLoss() {
        val hookHandback = ringing(1, handbackReason = "user_unmuted")
        assertEquals(
            listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN, session)),
            SessionEventMapping.map(hookHandback),
        )

        val supervisorHandback = supervisor(2, outcome = "handback_confirmed", state = CallState.ACTIVE)
        assertEquals(
            "a supervisor handback must carry its session, never be applied to 'whatever is current'",
            listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN, session)),
            SessionEventMapping.map(supervisorHandback),
        )

        // 事件流里没有“就绪”或“对端失联”的事实源：绝不能从事件里编造。
        for (event in listOf(ringing(1), hookHandback, supervisorHandback)) {
            for (mapped in SessionEventMapping.map(event)) {
                assertFalse(mapped is SessionEvent.ReadyChanged)
                assertFalse(mapped is SessionEvent.PeerLost)
            }
        }
    }

    /**
     * 监督端的四种 handback 结果不能混为一谈：它们以 [SupervisorHandback] 交给协调器，
     * 由后者决定是否重试 handover RPC、以及能否显示“已交还”。
     */
    @Test
    fun supervisorHandbackOutcomesKeepTheirOwnMeaning() {
        assertEquals(
            SupervisorHandback.CONFIRMED,
            SessionEventMapping.supervisorHandback(supervisor(2, "handback_confirmed", CallState.ACTIVE)),
        )
        assertEquals(
            SupervisorHandback.NOT_OWNED,
            SessionEventMapping.supervisorHandback(supervisor(2, "handback_not_owned", CallState.ACTIVE)),
        )
        assertEquals(
            SupervisorHandback.REVOKE_FAILED,
            SessionEventMapping.supervisorHandback(supervisor(2, "handback_revoke_failed", CallState.ACTIVE)),
        )
        assertEquals(
            SupervisorHandback.RESTORE_FAILED,
            SessionEventMapping.supervisorHandback(supervisor(2, "handback_restore_failed", CallState.ACTIVE)),
        )
        assertEquals(
            SupervisorHandback.UNKNOWN,
            SessionEventMapping.supervisorHandback(supervisor(2, "handback_whatever", CallState.ACTIVE)),
        )
        // 监督端的断开事实不是 handback，也不是 Takeover。
        assertNull(SessionEventMapping.supervisorHandback(supervisor(2, "call_disconnected", CallState.DISCONNECTED)))
        // HOOK 事件没有监督端结果语义。
        assertNull(SessionEventMapping.supervisorHandback(ringing(1)))
    }

    /** 轮询器把监督端交还事实连同会话身份与结果语义交给协调器（生产接线）。 */
    @Test
    fun thePollerRoutesSupervisorHandbacksWithTheirSessionAndOutcome() = runTest {
        val source = FakeSource { cursor ->
            batch(supervisor(1, "handback_revoke_failed", CallState.ACTIVE), nextSeq = 1, since = cursor)
        }
        val facts = mutableListOf<Pair<SessionId, SupervisorHandback>>()
        val poller = TestScopePoller(
            source,
            publish = { error("a wired supervisor handback must not go through the plain publish path") },
            onSupervisorHandback = { id, outcome -> facts += id to outcome },
        )
        poller.start()
        try {
            runCurrent()
            assertEquals(listOf(session to SupervisorHandback.REVOKE_FAILED), facts)
        } finally {
            poller.stop()
        }
    }

    @Test
    fun supervisorDisconnectEndsTheSessionWithoutInventingDirectionAndPresentation() {
        // 监督端事实只有 state=DISCONNECTED；快照字段一律 null。绝不补方向/呈现/紧急等默认值。
        val supervisorDisconnect = supervisor(2, outcome = "call_disconnected", state = CallState.DISCONNECTED)
        assertEquals(
            "a supervisor disconnect is a session fact, not a fabricated CallSnapshot",
            listOf(SessionEvent.SessionEnded(session)),
            SessionEventMapping.map(supervisorDisconnect),
        )
        // HOOK 快照仍然按完整事实映射（同一 feed 里它才是 StateChanged 的来源）。
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
        val diagnostics = mutableListOf<String>()
        val poller = TestScopePoller(
            source,
            { published += it },
            onError = { errors += it },
            onDiagnostic = { diagnostics += it },
        )
        poller.start()
        try {
            advance(1_000)
            assertEquals("an incomplete snapshot must never reach the coordinator", emptyList<SessionEvent>(), published)
            assertEquals(
                "an incomplete snapshot is a diagnostic, never a transport failure",
                emptyList<String>(),
                errors,
            )
            assertEquals(listOf(SessionEventPoller.ERROR_EVENT_INCOMPLETE), diagnostics)
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

    // ------------------------------------------- 桥接重启：两种模式（CRITICAL / FIX ROUND 1）

    /**
     * 模式 A：旧游标 > 新进程的 latest（桥接重启后 APP 游标超前）。
     *
     * 客户端用 0 重读并把批次标成 reset；轮询器必须**先清空基线再应用**，否则重读回来的
     * seq 1..lastSeq 会被当成重复丢弃，事件流永远死掉（生产上的真实故障）。
     */
    @Test
    fun aClientReportedResetClearsTheBaselineBeforeTheRetriedBatchIsApplied() = runTest {
        // 模拟 BridgeClient 的 cursor_ahead 重读结果：从 0 读，标记 reset。
        val source = FakeSource { cursor ->
            BridgeRead.Available(
                EventsBatch(listOf(ringing(1)), nextSeq = 1, dropped = 0, reset = cursor == 7L),
            )
        }
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(source, { published += it }, initialCursor = 7L)
        poller.start()
        try {
            runCurrent()
            assertEquals("the retried cursor_ahead read must carry the old cursor first", listOf(7L), source.cursors)
            assertEquals("the new call's first event must be delivered, not deduped", 1, published.size)
            assertEquals(CallState.RINGING, (published.single() as SessionEvent.StateChanged).snapshot.state)
            assertEquals(1L, poller.lastSeq)
            assertEquals(1L, poller.baselineResetCount)
        } finally {
            poller.stop()
        }
    }

    /**
     * 模式 B：旧游标 ≤ 新进程的 latest（桥接重启后新进程已经记录了更多事件）。
     *
     * 桥接不会返回 cursor_ahead，返回的批次看起来完全正常——新进程的 seq 1..oldCursor 被
     * 静默跳过（通常正是新来电的 RINGING）。只有进程 id 信标能发现重启：轮询器必须先清空
     * 基线、从 0 重读，再应用批次（FIX ROUND 2：id 变了，不是 uptime 变小）。
     */
    @Test
    fun aRestartedBridgeWhoseLatestSequenceIsAheadStillDeliversTheNewCallsFirstEvents() = runTest {
        val bridge = RestartingFakeBridge(processId = "proc-a")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(bridge, { published += it }, epochSource = bridge)
        poller.start()
        try {
            runCurrent()
            assertEquals(2, published.size)
            assertEquals(2L, poller.lastSeq)

            // 桥接重启：事件环从 0 开始，进程 id 变化；新来电的 RINGING 是 seq 1。
            bridge.restart(processId = "proc-b")
            bridge.record(ringing(0, id = other), source = "UNKNOWN")
            bridge.record(ringing(0, id = other, handbackReason = null), source = "UNKNOWN")
            bridge.record(ringing(0, id = session, state = CallState.DISCONNECTED), source = "UNKNOWN")

            advance(500)

            assertEquals("a restart must resync from the start of the new ring", 0L, bridge.cursors.last())
            assertEquals("the restart must be counted", 1L, poller.baselineResetCount)
            assertTrue(
                "the new call's RINGING must be delivered, got " + published,
                published.any {
                    it is SessionEvent.StateChanged &&
                        it.snapshot.id == other &&
                        it.snapshot.state == CallState.RINGING
                },
            )
            assertEquals(3L, poller.lastSeq)
        } finally {
            poller.stop()
        }
    }

    /**
     * 服务重启后不得重放旧事件：已投递的游标必须持久化，新轮询器从它继续。
     */
    @Test
    fun theDeliveredCursorIsPersistedSoAServiceRestartDoesNotReplayStaleEvents() = runTest {
        val store = InMemoryCursorStore()
        val first = FakeSource { cursor -> batch(ringing(1), ringing(2), nextSeq = 2, since = cursor) }
        val firstPoller = TestScopePoller(first, {}, cursorStore = store)
        firstPoller.start()
        try {
            advance(500)
            assertEquals(2L, firstPoller.lastSeq)
            assertEquals(SessionEventCursor(2L, null), store.cursor)
        } finally {
            firstPoller.stop()
        }

        // 服务重启（新轮询器），桥接没有重启：从 2 继续，绝不重放 seq 1..2。
        val second = FakeSource { cursor -> batch(nextSeq = cursor, since = cursor) }
        val published = mutableListOf<SessionEvent>()
        val secondPoller = TestScopePoller(second, { published += it }, cursorStore = store)
        secondPoller.start()
        try {
            runCurrent()
            assertEquals(listOf(2L), second.cursors)
            assertEquals(2L, secondPoller.restoredCursor)
            assertEquals("no stale handback may be replayed", emptyList<SessionEvent>(), published)
        } finally {
            secondPoller.stop()
        }
    }

    /** 服务重启 + 桥接在此期间也重启：持久化的进程 id 让新模式 B 仍能重新同步。 */
    @Test
    fun aPersistedProcessIdDetectsARestartThatHappenedWhileTheServiceWasDead() = runTest {
        val store = InMemoryCursorStore(SessionEventCursor(seq = 5L, processId = "proc-a"))
        val bridge = RestartingFakeBridge(processId = "proc-b")
        bridge.record(ringing(0, id = other), source = "UNKNOWN")
        bridge.record(ringing(0, id = other), source = "UNKNOWN")
        bridge.record(ringing(0, id = other), source = "UNKNOWN")
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(bridge, { published += it }, epochSource = bridge, cursorStore = store)
        poller.start()
        try {
            runCurrent()
            assertEquals("the persisted id must expose the restart before the first poll", 0L, bridge.cursors.first())
            assertEquals("the resynced ring is delivered from its start", 3, published.size)
            assertTrue(
                "the new process's first event must be delivered",
                (published.first() as SessionEvent.StateChanged).snapshot.id == other,
            )
            assertEquals(3L, poller.lastSeq)
        } finally {
            poller.stop()
        }
    }

    /**
     * 同一进程 id = 同一代次：即使游标是服务重启后从磁盘恢复的，也绝不重置基线。
     * （FIX ROUND 2：信标从墙钟 uptime 改成每进程随机 id 之后，同代次的判定不能依赖时间。）
     */
    @Test
    fun aPersistedProcessIdThatMatchesTheRunningBridgeKeepsTheBaseline() = runTest {
        val store = InMemoryCursorStore(SessionEventCursor(seq = 2L, processId = "proc-a"))
        val bridge = RestartingFakeBridge(processId = "proc-a")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(bridge, { published += it }, epochSource = bridge, cursorStore = store)
        poller.start()
        try {
            runCurrent()
            assertEquals("the restored cursor must be used as-is", listOf(2L), bridge.cursors)
            assertEquals("an unchanged process id must never reset the baseline", 0L, poller.baselineResetCount)
            assertEquals("only the new event is delivered", 1, published.size)
            assertEquals(3L, poller.lastSeq)
            assertEquals(SessionEventCursor(3L, "proc-a"), store.cursor)
        } finally {
            poller.stop()
        }
    }

    /**
     * 没有 processId 的 status 是“代次未知”，不是“同一进程”，也不是一次干净的“没重启”：
     * 轮询器必须 fail closed（不重置基线、不轮询事件、按失败退避）。信标恢复且 id 相同后，
     * 从原游标继续。
     */
    @Test
    fun aStatusWithoutTheProcessIdFailsClosedAndNeverFakesACleanRestart() = runTest {
        val bridge = RestartingFakeBridge(processId = "proc-a")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        val epoch = FixedEpochSource(BridgeRead.Unavailable(BridgeClient.ERROR_STATUS_PAYLOAD))
        val errors = mutableListOf<String>()
        val published = mutableListOf<SessionEvent>()
        val poller = TestScopePoller(
            bridge,
            { published += it },
            onError = { errors += it },
            epochSource = epoch,
        )
        poller.start()
        try {
            advance(1_000)
            assertEquals("an unknown generation must never fake a restart", 0L, poller.baselineResetCount)
            assertTrue("no event may be polled without a known generation", bridge.cursors.isEmpty())
            assertTrue("the unknown generation must be reported", errors.isNotEmpty())
            assertTrue(errors.all { it == BridgeClient.ERROR_STATUS_PAYLOAD })
            assertEquals("fail closed must not deliver anything", emptyList<SessionEvent>(), published)

            // 信标恢复（同一进程）：从旧游标（0）继续，不重置基线。
            epoch.set(BridgeRead.Available("proc-a"))
            advance(3_000)
            assertEquals("a known generation must not fake a restart", 0L, poller.baselineResetCount)
            assertEquals("the real cursor is used once the generation is known", 0L, bridge.cursors.first())
            assertEquals(1, published.size)
        } finally {
            poller.stop()
        }
    }

    // ------------------------------------- 循环异常保护（7B3 FIX ROUND 2 / N5）

    /**
     * 游标存储写失败（磁盘/SharedPreferences 异常）不能杀死轮询 Job：此前只有映射/投递被包住，
     * cursorStore.write 抛异常会让 Job 静默结束且 job 永不为 null，start() 再也拉不起来。
     * 现在整轮都被守卫：上报固定机器码、继续轮询。
     */
    @Test
    fun aThrowingCursorStoreIsDiagnosedAndNeverWedgesThePoller() = runTest {
        val source = FakeSource { cursor -> batch(ringing(cursor + 1), nextSeq = cursor + 1, since = cursor) }
        val diagnostics = mutableListOf<String>()
        val poller = TestScopePoller(
            source,
            {},
            cursorStore = ThrowingCursorStore(),
            onDiagnostic = { diagnostics += it },
        )
        poller.start()
        try {
            runCurrent()
            assertEquals(listOf(SessionEventPoller.ERROR_LOOP_FAILED), diagnostics)
            assertTrue("the poller must stay alive after a cursor-store failure", poller.isRunning)

            advance(1_000)
            assertTrue(
                "the loop must keep polling after a cursor-store failure, got " + source.cursors,
                source.cursors.size >= 2,
            )
            assertTrue("the cursor must keep advancing, got " + poller.lastSeq, poller.lastSeq >= 2L)
        } finally {
            poller.stop()
        }
    }

    // ------------------------------------- 桥接代次变化：本地结束旧会话（7B3 FOLLOW-UP）

    /**
     * 裁定（7B3 FOLLOW-UP）：桥接代次变化时，协调器仍拥有的会话必须**本地结束一次**
     * （StopConversation → RevokeLease → RestoreMicrophone → ENDED，绝不发挂断，也不伪造
     * DISCONNECTED 快照），随后新代次的 RINGING 才能被接受并按正常倒计时接听。
     *
     * 旧进程的 call_disconnected 会随进程一起消失；没有这次本地结束，会话会卡在
     * ACTIVE_AI/ENDING 直到 180 s 上限，并把新来电一直挡在“不可替换”之外。
     */
    @Test
    fun aBridgeRestartEndsTheOwnedSessionLocallyAndTheNextCallIsAnswered() = runTest {
        val bridge = RestartingFakeBridge(processId = "proc-a")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = LoggingAudioBridge(events)
        val conversation = LoggingConversation(events)
        val revoker = LoggingRevoker(events)
        val observer = RecordingObserver(events)
        val leases = LeaseRegistry()
        val coordinator = polledCoordinator(calls, audio, conversation, revoker, observer, leases)
        coordinator.start()
        val poller = TestScopePoller(
            bridge,
            coordinator::onEvent,
            epochSource = bridge,
            onBridgeRestarted = coordinator::onBridgeRestarted,
        )
        poller.start()
        try {
            runCurrent()
            coordinator.onEvent(SessionEvent.ReadyChanged(true))
            advance(3_100)
            assertEquals(listOf(SentCommand(session, CallCommand.Answer)), calls.sent)

            // 通话进入 ACTIVE：音频/会话打开、本地租约有效。
            bridge.record(ringing(0, id = session, state = CallState.ACTIVE), source = "UNKNOWN")
            advance(500)
            assertEquals(SessionState.ACTIVE_AI, coordinator.state)
            assertTrue("the AI call must hold the local lease", leases.isLive(session))
            assertEquals(listOf("audio.opened:call-a", "conversation.started:call-a"), events)

            // 桥接重启（进程 id 变化）：旧进程的断开事实永远不会到达；新进程里已有一路新响铃。
            bridge.restart(processId = "proc-b")
            bridge.record(ringing(0, id = other), source = "UNKNOWN")
            advance(500)

            // 旧会话本地结束；同一次重新同步读出的新代次响铃随后被接受（旧会话不得被静默顶掉）。
            assertEquals("the new generation's call must own the session", other, coordinator.activeSession)
            assertEquals(
                "StopConversation → RevokeLease(local) → RestoreMicrophone, never Hangup",
                listOf(
                    "audio.opened:call-a",
                    "conversation.started:call-a",
                    "conversation.cancelled:call-a",
                    "audio.stopped:call-a",
                    "restored:call-a",
                ),
                events,
            )
            assertFalse("the local end must release the local lease", leases.isLive(session))
            assertTrue(
                "the supervisor owns termination: no handover RPC may be sent",
                revoker.revoked.isEmpty(),
            )
            assertTrue(
                "a restart-ended call must never be hung up",
                calls.sent.none { it.command == CallCommand.Hangup },
            )
            assertEquals(
                "ENDED must be broadcast exactly once for the old session",
                1,
                observer.states.count { it.first == session && it.second == SessionState.ENDED },
            )
            assertEquals(listOf(session), observer.handedBack)
            assertEquals(listOf(RevokeOutcome.NOT_OWNED), observer.outcomes)

            // 新代次的 RINGING 必须被接受，并按正常倒计时接听（READY 是服务级事实，继承）。
            assertEquals(SessionState.PREPARING, coordinator.state)
            advance(3_100)
            assertEquals(
                "the new call is answered normally",
                listOf(
                    SentCommand(session, CallCommand.Answer),
                    SentCommand(other, CallCommand.Answer),
                ),
                calls.sent,
            )
        } finally {
            poller.stop()
            coordinator.close()
        }
    }

    /** 同一次重启可能跨多轮轮询被观测：代次信号只能上报一次。 */
    @Test
    fun aDetectedBridgeRestartIsReportedOnceAcrossPolls() = runTest {
        val bridge = RestartingFakeBridge(processId = "proc-a")
        bridge.record(ringing(0, id = session), source = "UNKNOWN")
        var restarts = 0
        val poller = TestScopePoller(
            bridge,
            {},
            epochSource = bridge,
            onBridgeRestarted = { restarts += 1 },
        )
        poller.start()
        try {
            runCurrent()
            assertEquals("an unchanged generation must not signal a restart", 0, restarts)

            bridge.restart(processId = "proc-b")
            advance(500)
            assertEquals("the generation change must be reported", 1, restarts)

            advance(5_000)
            assertEquals("later polls of the same generation must not report it again", 1, restarts)
        } finally {
            poller.stop()
        }
    }

    /**
     * 协调器没有会话时，代次变化是空操作，但**游标重新同步照常发生**：新进程的事件环
     * 必须从 0 重读，新代次的响铃才能被投递。
     */
    @Test
    fun aBridgeRestartWithoutAnOwnedSessionStillResyncsTheCursor() = runTest {
        val bridge = RestartingFakeBridge(processId = "proc-a")
        bridge.record(supervisor(0, "call_disconnected", null), source = "SUPERVISOR")
        val events = mutableListOf<String>()
        val calls = FakeCallControl()
        val audio = LoggingAudioBridge(events)
        val conversation = LoggingConversation(events)
        val revoker = LoggingRevoker(events)
        val observer = RecordingObserver(events)
        val coordinator = polledCoordinator(calls, audio, conversation, revoker, observer, LeaseRegistry())
        coordinator.start()
        val poller = TestScopePoller(
            bridge,
            coordinator::onEvent,
            epochSource = bridge,
            onBridgeRestarted = coordinator::onBridgeRestarted,
        )
        poller.start()
        try {
            runCurrent()
            assertNull("a disconnect fact without a session owns nothing", coordinator.activeSession)
            assertEquals(1L, poller.lastSeq)

            bridge.restart(processId = "proc-b")
            bridge.record(ringing(0, id = other), source = "UNKNOWN")
            advance(500)

            assertTrue(
                "no owned session: nothing may be ended",
                observer.states.none { it.second == SessionState.ENDED },
            )
            assertTrue("no owned session: no handback may be reported", observer.handedBack.isEmpty())
            assertTrue("no owned session: no audio/conversation/revoke may happen", events.isEmpty())
            assertTrue("no owned session: no command may be sent", calls.sent.isEmpty())
            assertEquals("the restart must still resync the cursor", 0L, bridge.cursors.last())
            assertEquals(1L, poller.baselineResetCount)
            assertEquals("the new generation's first event must be delivered", other, coordinator.activeSession)
        } finally {
            poller.stop()
            coordinator.close()
        }
    }

    // ------------------------------------------- 批次容错/诊断（minors）

    /** stop() 必须取消在途批次：本批剩余事件绝不能在服务停止后再投递。 */
    @Test
    fun stopInsideABatchDropsTheRestOfIt() = runTest {
        val source = FakeSource { cursor -> batch(ringing(1), ringing(2), ringing(3), nextSeq = 3, since = cursor) }
        var poller: SessionEventPoller? = null
        val published = mutableListOf<SessionEvent>()
        poller = TestScopePoller(source, { published += it; poller?.stop() })
        poller.start()
        try {
            runCurrent()
            assertEquals("the rest of the batch must be dropped after stop()", 1, published.size)
        } finally {
            poller.stop()
        }
    }

    /** 映射/投递抛异常不能静默杀死轮询循环：上报固定码并继续轮询。 */
    @Test
    fun aThrowableFromAPublishIsDiagnosedAndNeverKillsTheLoop() = runTest {
        // 每次轮询都推进一条：失败那批之后仍能看到“循环还活着且还能投递”。
        val source = FakeSource { cursor -> batch(ringing(cursor + 1), nextSeq = cursor + 1, since = cursor) }
        val diagnostics = mutableListOf<String>()
        val published = mutableListOf<SessionEvent>()
        var throwOnce = true
        val poller = TestScopePoller(
            source,
            publish = {
                if (throwOnce) {
                    throwOnce = false
                    throw IllegalStateException("boom")
                }
                published += it
            },
            onDiagnostic = { diagnostics += it },
        )
        poller.start()
        try {
            runCurrent()
            assertEquals(listOf(SessionEventPoller.ERROR_DELIVER_FAILED), diagnostics)
            advance(500)
            assertEquals("the loop must keep polling after a delivery failure", 2, source.cursors.size)
            assertEquals(1, published.size)
            assertEquals(1L, poller.deliverFailureCount)
        } finally {
            poller.stop()
        }
    }

    /** 客户端跳过了一条非法事件：轮询器仍投递其余事件，并把跳过数如实上报为诊断。 */
    @Test
    fun skippedWireEventsAreDiagnosedWithoutDroppingTheRestOfTheBatch() = runTest {
        val source = FakeSource { cursor ->
            BridgeRead.Available(EventsBatch(listOf(ringing(2)), nextSeq = 2, dropped = 0, skipped = 1))
        }
        val published = mutableListOf<SessionEvent>()
        val diagnostics = mutableListOf<String>()
        val poller = TestScopePoller(source, { published += it }, onDiagnostic = { diagnostics += it })
        poller.start()
        try {
            runCurrent()
            assertEquals(1, published.size)
            assertEquals(listOf(SessionEventPoller.ERROR_EVENT_INVALID), diagnostics)
            assertEquals(1L, poller.invalidEventCount)
            assertEquals(2L, poller.lastSeq)
        } finally {
            poller.stop()
        }
    }

    /** 诊断（不完整快照/坏事件）必须上报，且成功恢复后清除——它绝不是交还失败。 */
    @Test
    fun diagnosticsAreReportedAndClearedOnRecovery() = runTest {
        var incomplete = true
        val source = FakeSource { cursor ->
            if (incomplete) {
                incomplete = false
                BridgeRead.Available(EventsBatch(listOf(ringing(1).copy(isEmergency = null)), nextSeq = 1, dropped = 0))
            } else {
                batch(nextSeq = cursor, since = cursor)
            }
        }
        val diagnostics = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var recovered = 0
        val poller = TestScopePoller(
            source,
            {},
            onError = { errors += it },
            onDiagnostic = { diagnostics += it },
            onRecovered = { recovered += 1 },
        )
        poller.start()
        try {
            runCurrent()
            assertEquals(listOf(SessionEventPoller.ERROR_EVENT_INCOMPLETE), diagnostics)
            assertEquals("an incomplete snapshot is a diagnostic, never a transport failure", emptyList<String>(), errors)
            advance(500)
            assertEquals("a clean poll must clear the diagnostic residue", 1, recovered)
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
        epochSource: SessionEpochSource? = null,
        cursorStore: SessionEventCursorStore = NoSessionEventCursorStore,
        onDiagnostic: (String) -> Unit = {},
        onRecovered: () -> Unit = {},
        onSupervisorHandback: ((SessionId, SupervisorHandback) -> Unit)? = null,
        onBridgeRestarted: () -> Unit = {},
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
        epochSource = epochSource,
        cursorStore = cursorStore,
        onDiagnostic = onDiagnostic,
        onRecovered = onRecovered,
        onSupervisorHandback = onSupervisorHandback ?: { id, _ ->
            publish(SessionEvent.Takeover(TakeoverReason.UNKNOWN, id))
        },
        onBridgeRestarted = onBridgeRestarted,
    )

    /** 模拟“每进程一份”的事件环：重启后进程 id 变化、seq 从 1 重新开始。 */
    private class RestartingFakeBridge(processId: String) : SessionEventSource, SessionEpochSource {
        val cursors = mutableListOf<Long>()
        private var process = processId
        private var latest = 0L
        private val ring = mutableListOf<SessionWireEvent>()

        fun record(event: SessionWireEvent, source: String) {
            latest += 1
            ring += event.copy(seq = latest, source = source)
        }

        fun restart(processId: String) {
            process = processId
            latest = 0L
            ring.clear()
        }

        override suspend fun processId(): BridgeRead<String> = BridgeRead.Available(process)

        override suspend fun pollEvents(sinceSeq: Long): BridgeRead<EventsBatch> {
            cursors += sinceSeq
            if (sinceSeq > latest) return BridgeRead.Unavailable(SessionEventPoller.ERROR_CURSOR_AHEAD)
            val events = ring.filter { it.seq > sinceSeq }
            return BridgeRead.Available(EventsBatch(events, latest, dropped = 0))
        }
    }

    private class InMemoryCursorStore(var cursor: SessionEventCursor? = null) : SessionEventCursorStore {
        override fun read(): SessionEventCursor? = cursor

        override fun write(cursor: SessionEventCursor) {
            this.cursor = cursor
        }
    }

    /** 只提供代次信标的事件源（不参与事件轮询）。 */
    private class FixedEpochSource(private var result: BridgeRead<String>) : SessionEpochSource {
        override suspend fun processId(): BridgeRead<String> = result

        fun set(next: BridgeRead<String>) {
            result = next
        }
    }

    /** 模拟磁盘/SharedPreferences 写失败：每次持久化都抛异常。 */
    private class ThrowingCursorStore : SessionEventCursorStore {
        override fun read(): SessionEventCursor? = null

        override fun write(cursor: SessionEventCursor) {
            throw IllegalStateException("cursor store is broken")
        }
    }

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
        state: CallState = CallState.RINGING,
    ): SessionWireEvent = SessionWireEvent(
        seq = seq,
        sessionId = id,
        epoch = id.processEpoch,
        state = state,
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

    /** 与生产一致的装配：轮询器把代次变化交给协调器的本地结束入口。 */
    private fun TestScope.polledCoordinator(
        calls: CallControl,
        audio: AudioBridge,
        conversation: ConversationPort,
        revoker: OwnershipRevoker,
        observer: SessionObserver,
        leases: LeaseRegistry = LeaseRegistry(),
    ): SessionCoordinator = SessionCoordinator(
        config = config,
        calls = calls,
        audio = audio,
        conversation = conversation,
        clock = MonotonicClock { testScheduler.currentTime },
        leases = leases,
        revoker = revoker,
        observer = observer,
        dispatcher = StandardTestDispatcher(testScheduler),
        tickIntervalMs = 1_000,
    )

    private class LoggingAudioBridge(private val events: MutableList<String>) : AudioBridge {
        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
            events += "audio.opened:" + id.callId
            return LoggingAudioSession(id, events)
        }
    }

    private class LoggingAudioSession(
        private val session: SessionId,
        private val events: MutableList<String>,
    ) : AudioSession {
        override val downlink: Flow<PcmChunk> = emptyFlow()

        override suspend fun writeUplink(audio: PcmAudio) = Unit

        override suspend fun stop() {
            events += "audio.stopped:" + session.callId
        }
    }

    private class LoggingConversation(private val events: MutableList<String>) : ConversationPort {
        override suspend fun run(id: SessionId, audio: AudioSession, config: AgentConfig) {
            events += "conversation.started:" + id.callId
            try {
                awaitCancellation()
            } finally {
                events += "conversation.cancelled:" + id.callId
            }
        }
    }

    private class LoggingRevoker(private val events: MutableList<String>) : OwnershipRevoker {
        val revoked = mutableListOf<SessionId>()

        override suspend fun revoke(id: SessionId): RevokeOutcome {
            revoked += id
            events += "revoked:" + id.callId
            return RevokeOutcome.CONFIRMED
        }
    }

    private class RecordingObserver(private val events: MutableList<String>) : SessionObserver() {
        val states = mutableListOf<Pair<SessionId, SessionState>>()
        val handedBack = mutableListOf<SessionId>()
        val outcomes = mutableListOf<RevokeOutcome>()

        override fun onStateChanged(id: SessionId, state: SessionState) {
            states += id to state
        }

        override fun onHandedBack(id: SessionId, outcome: RevokeOutcome) {
            handedBack += id
            outcomes += outcome
            events += if (outcome == RevokeOutcome.CONFIRMED || outcome == RevokeOutcome.NOT_OWNED) {
                "restored:" + id.callId
            } else {
                "unconfirmed:" + id.callId
            }
        }
    }

    private companion object {
        const val NUMBER = "+8613800138000"
    }
}
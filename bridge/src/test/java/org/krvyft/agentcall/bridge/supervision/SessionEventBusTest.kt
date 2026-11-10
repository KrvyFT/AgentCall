package org.krvyft.agentcall.bridge.supervision

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.SessionId

/**
 * Task 7C：HOOK → APP 会话事件环形缓冲的纯单元测试（无 socket、无设备）。
 *
 * 只锁三件事：每会话顺序、有界性（drop-oldest + 计数）、游标语义（0/中间/陈旧/超前）。
 * 事件内容必须来自真实快照字段，绝不补默认值。
 */
class SessionEventBusTest {

    private val sessionA = SessionId("epoch-1", "call-a")
    private val sessionB = SessionId("epoch-1", "call-b")

    private fun snapshot(
        state: CallState,
        number: String? = "+8613800138000",
        source: String? = "UNKNOWN",
        handbackReason: String? = null,
    ) = HookSnapshotDecoder.Decoded(
        state = state,
        handbackReason = handbackReason,
        source = source,
        presentation = Presentation.ALLOWED,
        direction = CallDirection.INCOMING,
        phoneAccount = "acct-1",
        isEmergency = false,
        isConference = false,
        activeCallCount = 1,
        number = number,
    )

    private fun batch(poll: SessionEventBus.Poll): SessionEventBus.Batch =
        (poll as SessionEventBus.Poll.Available).batch

    @Test
    fun snapshotsAreOrderedPerSessionAndGloballyBySequence() {
        val bus = SessionEventBus()

        bus.recordSnapshot(sessionA, snapshot(CallState.RINGING))
        bus.recordSnapshot(sessionB, snapshot(CallState.RINGING))
        bus.recordSnapshot(sessionA, snapshot(CallState.ACTIVE))
        bus.recordSnapshot(sessionA, snapshot(CallState.DISCONNECTED))

        val events = batch(bus.poll(0L)).events
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.seq })
        assertEquals(
            listOf(CallState.RINGING, CallState.ACTIVE, CallState.DISCONNECTED),
            events.filter { it.sessionId == sessionA }.map { it.state },
        )
        assertEquals(listOf(CallState.RINGING), events.filter { it.sessionId == sessionB }.map { it.state })
        // 快照字段原样搬运：号码仍然可用（APP 的 RuleMatcher 需要它），但没有任何补默认值。
        assertEquals("+8613800138000", events[0].number)
        assertEquals("UNKNOWN", events[0].source)
        assertNull(events[0].handbackReason)
    }

    @Test
    fun ringDropsTheOldestEventsAndCountsThem() {
        val bus = SessionEventBus(capacity = 4)

        repeat(6) { bus.recordSnapshot(sessionA, snapshot(CallState.RINGING)) }

        val batch = batch(bus.poll(0L))
        assertEquals(listOf(3L, 4L, 5L, 6L), batch.events.map { it.seq })
        assertEquals(2L, batch.dropped)
        assertEquals(6L, batch.nextSeq)
        assertEquals(6L, bus.latestSeq)
        assertEquals(2L, bus.droppedCount)
    }

    @Test
    fun pollReturnsAtMostSixtyFourEventsAndNeverMore() {
        val bus = SessionEventBus()
        repeat(100) { bus.recordSnapshot(sessionA, snapshot(CallState.RINGING)) }

        val first = batch(bus.poll(0L))
        assertEquals(SessionEventBus.MAX_BATCH, first.events.size)
        assertEquals(64, first.events.size)
        assertEquals(64L, first.nextSeq)
        // 调用方即使要求更多，单次响应也不会超过 64。
        assertEquals(SessionEventBus.MAX_BATCH, batch(bus.poll(0L, maxEvents = 10_000)).events.size)

        val second = batch(bus.poll(first.nextSeq))
        assertEquals(36, second.events.size)
        assertEquals(100L, second.nextSeq)
        assertEquals(0L, second.dropped)
    }

    @Test
    fun staleCursorReturnsWhatIsAvailableWithoutError() {
        val bus = SessionEventBus(capacity = 4)
        repeat(6) { bus.recordSnapshot(sessionA, snapshot(CallState.RINGING)) }

        val batch = batch(bus.poll(1L))
        assertEquals(listOf(3L, 4L, 5L, 6L), batch.events.map { it.seq })
        assertEquals(2L, batch.dropped)
    }

    @Test
    fun cursorBeyondTheLastRecordedSequenceIsRejected() {
        val bus = SessionEventBus()
        // 空缓冲：任何 > 0 的游标都是“超前”（不是“暂时没有新事件”）。
        assertEquals(SessionEventBus.Poll.CursorAhead, bus.poll(1L) as SessionEventBus.Poll)
        assertEquals(SessionEventBus.Poll.CursorAhead, SessionEventBus().poll(Long.MAX_VALUE))

        repeat(3) { bus.recordSnapshot(sessionA, snapshot(CallState.RINGING)) }
        assertEquals(SessionEventBus.Poll.CursorAhead, bus.poll(4L) as SessionEventBus.Poll)
        // 等于最新序号不是超前：返回空批次并保持游标。
        val atCursor = batch(bus.poll(3L))
        assertTrue(atCursor.events.isEmpty())
        assertEquals(3L, atCursor.nextSeq)
    }

    @Test
    fun emptyFeedReturnsAnEmptyBatchWithAZeroCursor() {
        val batch = batch(SessionEventBus().poll(0L))
        assertTrue(batch.events.isEmpty())
        assertEquals(0L, batch.nextSeq)
        assertEquals(0L, batch.dropped)
    }

    @Test
    fun supervisorOutcomeCarriesTheHandbackReasonAndTheOutcomeCode() {
        val bus = SessionEventBus()

        bus.recordHandback(
            sessionA,
            CallState.ACTIVE,
            "user_unmuted",
            SessionEventBus.OUTCOME_HANDBACK_CONFIRMED,
        )
        bus.recordDisconnect(sessionA, CallState.DISCONNECTED)

        val events = batch(bus.poll(0L)).events
        assertEquals(2, events.size)
        assertEquals(SessionEventBus.SUPERVISOR_SOURCE, events[0].source)
        assertEquals("user_unmuted", events[0].handbackReason)
        assertEquals(SessionEventBus.OUTCOME_HANDBACK_CONFIRMED, events[0].outcome)
        // 监督端事实不携带任何 Hook 快照字段（没有观测值就不补默认值）。
        assertNull(events[0].presentation)
        assertNull(events[0].direction)
        assertNull(events[0].phoneAccount)
        assertNull(events[0].isEmergency)
        assertNull(events[0].isConference)
        assertNull(events[0].activeCallCount)
        assertNull(events[0].number)
        assertEquals(CallState.ACTIVE, events[0].state)
        assertEquals(SessionEventBus.OUTCOME_CALL_DISCONNECTED, events[1].outcome)
        assertNull(events[1].handbackReason)
    }

    @Test
    fun encodedBatchUsesTheDocumentedFixedFieldSet() {
        val bus = SessionEventBus()
        bus.recordSnapshot(sessionA, snapshot(CallState.RINGING, handbackReason = null))
        bus.recordHandback(
            sessionA,
            CallState.ACTIVE,
            "route_change",
            SessionEventBus.OUTCOME_HANDBACK_REVOKE_FAILED,
        )
        val batch = batch(bus.poll(0L))

        val payload = JsonParser.parseString(SessionEventBus.encode(batch)).asJsonObject
        assertEquals(setOf("events", "nextSeq", "dropped"), payload.keySet())
        assertEquals(2L, payload.get("nextSeq").asLong)
        assertEquals(0L, payload.get("dropped").asLong)
        val events = payload.getAsJsonArray("events")
        assertEquals(2, events.size())

        val hookEvent = events.get(0).asJsonObject
        assertEquals(
            setOf(
                "seq",
                "sessionId",
                "epoch",
                "state",
                "presentation",
                "direction",
                "phoneAccount",
                "isEmergency",
                "isConference",
                "activeCallCount",
                "number",
                "source",
                "handbackReason",
                "outcome",
            ),
            hookEvent.keySet(),
        )
        assertEquals(1L, hookEvent.get("seq").asLong)
        assertEquals("epoch-1", hookEvent.getAsJsonObject("sessionId").get("processEpoch").asString)
        assertEquals("call-a", hookEvent.getAsJsonObject("sessionId").get("callId").asString)
        assertEquals("epoch-1", hookEvent.get("epoch").asString)
        assertEquals("RINGING", hookEvent.get("state").asString)
        assertEquals("ALLOWED", hookEvent.get("presentation").asString)
        assertEquals("INCOMING", hookEvent.get("direction").asString)
        assertEquals("acct-1", hookEvent.get("phoneAccount").asString)
        assertFalse(hookEvent.get("isEmergency").asBoolean)
        assertFalse(hookEvent.get("isConference").asBoolean)
        assertEquals(1, hookEvent.get("activeCallCount").asInt)
        assertEquals("+8613800138000", hookEvent.get("number").asString)
        assertEquals("UNKNOWN", hookEvent.get("source").asString)
        assertTrue(hookEvent.get("handbackReason").isJsonNull)
        assertTrue(hookEvent.get("outcome").isJsonNull)

        val supervisorEvent = events.get(1).asJsonObject
        assertEquals(SessionEventBus.SUPERVISOR_SOURCE, supervisorEvent.get("source").asString)
        assertEquals("route_change", supervisorEvent.get("handbackReason").asString)
        assertEquals(
            SessionEventBus.OUTCOME_HANDBACK_REVOKE_FAILED,
            supervisorEvent.get("outcome").asString,
        )
        assertTrue(supervisorEvent.get("number").isJsonNull)
    }

    @Test
    fun capacityMustBePositive() {
        try {
            SessionEventBus(capacity = 0)
            throw AssertionError("capacity 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
            // 固定大小环：容量 0 的“有界缓冲”没有意义。
        }
    }
}

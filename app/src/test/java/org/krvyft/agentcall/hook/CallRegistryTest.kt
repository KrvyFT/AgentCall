package org.krvyft.agentcall.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.CommandResult.Rejected
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/**
 * [CallRegistry] 的 JVM 测试：用可观察的 fake [CallHandle] 证明
 * “重复 observe 只注册一个回调、命令按会话/代次/状态拒绝、人工接听撤销待执行命令”，
 * 断言的是真实调用次数，而不只是返回值。
 *
 * 生产实现不依赖本文件的任何类型；fake 只存在于测试源集。
 */
class CallRegistryTest {

    // ------------------------------------------------------------------ fake

    private class FakeCallHandle(
        override val identity: String = "fake-call",
        var current: HandleSnapshot = ringingSnapshot(),
    ) : CallHandle {

        val callbacks = mutableListOf<CallCallback>()
        var addCallbackCount = 0
        var removeCallbackCount = 0
        var answerCount = 0
        var disconnectCount = 0
        var boundSession: SessionId? = null
        var answerOutcome: CommandResult = CommandResult.Accepted
        var disconnectOutcome: CommandResult = CommandResult.Accepted

        override fun snapshot(): HandleSnapshot = current

        override fun bindSession(id: SessionId) {
            boundSession = id
        }

        override fun addCallback(callback: CallCallback) {
            addCallbackCount += 1
            callbacks += callback
        }

        override fun removeCallback(callback: CallCallback) {
            removeCallbackCount += 1
            callbacks -= callback
        }

        override fun answer(): CommandResult {
            answerCount += 1
            return answerOutcome
        }

        override fun disconnect(): CommandResult {
            disconnectCount += 1
            return disconnectOutcome
        }

        /** 模拟 Telecom 回调：只投递给当时已注册的监听者。 */
        fun emit(
            kind: EventKind = EventKind.STATE_CHANGED,
            source: ActionSource = ActionSource.USER,
            snapshot: HandleSnapshot = current,
            destroyed: Boolean = false,
            reason: String = "",
        ) {
            current = snapshot
            val event = HandleEvent(snapshot = snapshot, source = source, kind = kind, reason = reason, destroyed = destroyed)
            callbacks.toList().forEach { it.onEvent(event) }
        }
    }

    private class RecordingSink : CommandSink {
        val attempts = mutableListOf<CommandAttempt>()

        override fun onCommandAttempt(attempt: CommandAttempt) {
            attempts += attempt
        }
    }

    private class RecordingListener : RegistryListener {
        val snapshots = mutableListOf<CallSnapshot>()
        val removed = mutableListOf<SessionId>()
        val handbacks = mutableListOf<Triple<SessionId, ActionSource, String>>()

        override fun onSnapshot(snapshot: CallSnapshot, source: ActionSource) {
            snapshots += snapshot
        }

        override fun onSessionRemoved(session: SessionId) {
            removed += session
        }

        override fun onHandedBack(session: SessionId, source: ActionSource, reason: String) {
            handbacks += Triple(session, source, reason)
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun duplicateObserveRegistersExactlyOneCallback() {
        val handle = FakeCallHandle()
        val registry = CallRegistry()

        val first = registry.observe(handle)
        val second = registry.observe(handle)

        assertEquals(first, second)
        assertEquals(1, handle.addCallbackCount)
        assertEquals(1, registry.liveSessions().size)
        assertEquals(first, handle.boundSession)
        assertEquals(first.processEpoch, registry.processEpoch)
        assertTrue("callId must be a random token, never the phone number", first.callId.isNotBlank())
        assertFalse(first.callId.contains("138"))
    }

    @Test
    fun commandsAfterRemovalAreRejectedWithoutTouchingTheHandle() {
        val handle = FakeCallHandle()
        val sink = RecordingSink()
        val registry = CallRegistry(sink = sink)
        val session = registry.observe(handle)

        assertTrue(registry.remove(handle))

        assertEquals(Rejected(STALE_SESSION), registry.answer(session))
        assertEquals(Rejected(STALE_SESSION), registry.hangup(session))
        assertEquals(0, handle.answerCount)
        assertEquals(0, handle.disconnectCount)
        assertEquals(1, handle.removeCallbackCount)
        assertNull(registry.snapshot(session))
        assertTrue(registry.liveSessions().isEmpty())
        assertEquals(2, sink.attempts.size)
        assertEquals(Rejected(STALE_SESSION), sink.attempts[0].result)
        assertEquals(ActionSource.MODULE, sink.attempts[0].source)
    }

    @Test
    fun answerRejectedWhenNotRinging() {
        val handle = FakeCallHandle(current = ringingSnapshot().copy(state = CallState.ACTIVE))
        val sink = RecordingSink()
        val registry = CallRegistry(sink = sink)
        val session = registry.observe(handle)

        assertEquals(Rejected(NOT_RINGING), registry.answer(session))
        assertEquals(0, handle.answerCount)
        assertEquals(1, sink.attempts.size)
        assertEquals(CallCommand.Answer, sink.attempts[0].command)
    }

    @Test
    fun answerRejectedWhenEpochIsStale() {
        val handle = FakeCallHandle()
        val sink = RecordingSink()
        val registry = CallRegistry(sink = sink)
        val session = registry.observe(handle)
        val stale = session.copy(processEpoch = "epoch-from-a-dead-process")

        assertEquals(Rejected(STALE_SESSION), registry.answer(stale))
        assertEquals(Rejected(STALE_SESSION), registry.hangup(stale))
        assertEquals(0, handle.answerCount)
        assertEquals(0, handle.disconnectCount)
        assertEquals(2, sink.attempts.size)
    }

    @Test
    fun duplicateAnswerWhilePendingDoesNotCallTelecomTwice() {
        val handle = FakeCallHandle()
        val sink = RecordingSink()
        val registry = CallRegistry(sink = sink)
        val session = registry.observe(handle)

        assertEquals(CommandResult.Accepted, registry.answer(session))
        assertEquals(Rejected(NOT_OWNED), registry.answer(session))

        assertEquals(1, handle.answerCount)
        assertEquals(2, sink.attempts.size)
        assertEquals(CommandResult.Accepted, sink.attempts[0].result)
        assertEquals(Rejected(NOT_OWNED), sink.attempts[1].result)
        assertTrue(registry.isPendingAnswer(session))
    }

    @Test
    fun moduleAnswerIsMarkedAsModuleSource() {
        val handle = FakeCallHandle()
        val sink = RecordingSink()
        val registry = CallRegistry(sink = sink)
        val session = registry.observe(handle)

        assertEquals(CommandResult.Accepted, registry.answer(session))
        handle.emit(snapshot = ringingSnapshot().copy(state = CallState.ACTIVE), source = ActionSource.MODULE)

        assertFalse(registry.isPendingAnswer(session))
        assertFalse(registry.isHandedBack(session))
        assertEquals(1, sink.attempts.size)
        assertEquals(ActionSource.MODULE, sink.attempts[0].source)
        assertEquals(CallCommand.Answer, sink.attempts[0].command)

        assertEquals(CommandResult.Accepted, registry.hangup(session))
        assertEquals(1, handle.disconnectCount)
        assertEquals(2, sink.attempts.size)
        assertEquals(ActionSource.MODULE, sink.attempts[1].source)
        assertEquals(CallCommand.Hangup, sink.attempts[1].command)
    }

    @Test
    fun userManualAnswerCancelsPendingModuleAnswer() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))
        assertTrue(registry.isPendingAnswer(session))

        handle.emit(snapshot = ringingSnapshot().copy(state = CallState.ACTIVE), source = ActionSource.USER)

        assertFalse(registry.isPendingAnswer(session))
        assertTrue(registry.isHandedBack(session))
        assertEquals(1, handle.answerCount)
        assertEquals(1, listener.handbacks.size)
        assertEquals(ActionSource.USER, listener.handbacks[0].second)
        assertEquals("manual_answer", listener.handbacks[0].third)

        // 已交还人工后模块不再拥有接听权：拒绝原因是 NOT_OWNED，而不是“状态不允许”。
        assertEquals(Rejected(NOT_OWNED), registry.answer(session))
        assertEquals(1, handle.answerCount)
    }

    @Test
    fun unknownRouteChangeCancelsPendingAnswerAndKeepsCallForHuman() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))

        handle.emit(kind = EventKind.ROUTE_CHANGED, source = ActionSource.UNKNOWN, snapshot = ringingSnapshot())

        assertFalse(registry.isPendingAnswer(session))
        assertTrue(registry.isHandedBack(session))
        assertEquals(1, listener.handbacks.size)
        assertEquals("route_change", listener.handbacks[0].third)
        assertEquals(Rejected(NOT_OWNED), registry.hangup(session))
        assertEquals(0, handle.disconnectCount)
    }

    @Test
    fun hangupRejectedForCallTheModuleDidNotAnswer() {
        val handle = FakeCallHandle()
        val sink = RecordingSink()
        val registry = CallRegistry(sink = sink)
        val session = registry.observe(handle)

        assertEquals(Rejected(NOT_OWNED), registry.hangup(session))
        assertEquals(0, handle.disconnectCount)
        assertEquals(1, sink.attempts.size)
        assertEquals(Rejected(NOT_OWNED), sink.attempts[0].result)
    }

    @Test
    fun destroyedCallIsRemovedAndCommandsBecomeStale() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)

        handle.emit(
            kind = EventKind.DESTROYED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.DISCONNECTED),
            destroyed = true,
        )

        assertTrue(registry.liveSessions().isEmpty())
        assertEquals(1, handle.removeCallbackCount)
        assertEquals(listOf(session), listener.removed)
        assertEquals(Rejected(STALE_SESSION), registry.answer(session))
        assertEquals(0, handle.answerCount)
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {
        val STALE_SESSION = RejectReason.STALE_SESSION
        val NOT_RINGING = RejectReason.NOT_RINGING
        val NOT_OWNED = RejectReason.NOT_OWNED

        fun ringingSnapshot(): HandleSnapshot = HandleSnapshot(
            number = "+8613800000000",
            state = CallState.RINGING,
            direction = CallDirection.INCOMING,
            presentation = Presentation.ALLOWED,
            phoneAccount = "SIM1",
            isEmergency = false,
            isConference = false,
            activeCallCount = 1,
        )
    }
}

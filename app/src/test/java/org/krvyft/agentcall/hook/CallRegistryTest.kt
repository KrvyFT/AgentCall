package org.krvyft.agentcall.hook

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.CommandResult.Rejected
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.rules.RuleMatcher

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
    fun routeEventDuringAnswerWindowIsANoOp() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))

        // I2(c)：接听窗口内的路由事件可能只是框架在接通过程中重配音频，不得撤销接听。
        handle.emit(
            kind = EventKind.ROUTE_CHANGED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(routeFingerprint = "speaker"),
            reason = "audio_route_changed",
        )

        assertTrue("a route event inside the answer window must not cancel", registry.isPendingAnswer(session))
        assertFalse(registry.isHandedBack(session))
        assertTrue(listener.handbacks.isEmpty())
        assertEquals(1, handle.answerCount)
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

    @Test
    fun routeChangeOnModuleOwnedActiveCallHandsBackToHuman() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))
        handle.emit(
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "earpiece"),
            source = ActionSource.MODULE,
        )

        // AI 通话中用户真的切换了音频路由（指纹变化）：来源不明，必须保守交还。
        handle.emit(
            kind = EventKind.ROUTE_CHANGED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "speaker"),
            reason = "audio_route_changed",
        )

        assertFalse(registry.isPendingAnswer(session))
        assertTrue(registry.isHandedBack(session))
        assertEquals(1, listener.handbacks.size)
        assertEquals("route_change", listener.handbacks[0].third)
        assertEquals(Rejected(NOT_OWNED), registry.hangup(session))
        assertEquals(0, handle.disconnectCount)
    }

    @Test
    fun moduleSourceRouteChangeKeepsModuleOwnership() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))
        handle.emit(
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "earpiece"),
            source = ActionSource.MODULE,
        )

        // 模块自己标记的路由动作不算“来源不明”，不能交还（即使指纹变化）。
        handle.emit(
            kind = EventKind.ROUTE_CHANGED,
            source = ActionSource.MODULE,
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "speaker"),
        )

        assertFalse(registry.isHandedBack(session))
        assertTrue(listener.handbacks.isEmpty())
        assertEquals(CommandResult.Accepted, registry.hangup(session))
        assertEquals(1, handle.disconnectCount)
    }

    @Test
    fun terminalStateEmitsFinalDisconnectedSnapshotBeforeRemoval() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)

        handle.emit(
            kind = EventKind.STATE_CHANGED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.DISCONNECTED),
        )

        // 控制器必须收到终态快照，而不是只等心跳超时。
        val finalSnapshot = listener.snapshots.last()
        assertEquals(session, finalSnapshot.id)
        assertEquals(CallState.DISCONNECTED, finalSnapshot.state)
        assertEquals(listOf(session), listener.removed)
        assertTrue(registry.liveSessions().isEmpty())
        assertEquals(1, handle.removeCallbackCount)
    }

    @Test
    fun observingAnAlreadyDisconnectedHandleLeavesNoZombieSession() {
        val handle = FakeCallHandle(current = ringingSnapshot().copy(state = CallState.DISCONNECTED))
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)

        val session = registry.observe(handle)

        assertTrue(registry.liveSessions().isEmpty())
        assertNull(registry.snapshot(session))
        assertEquals(Rejected(STALE_SESSION), registry.answer(session))
        assertEquals(0, handle.answerCount)
        assertEquals(1, handle.addCallbackCount)
        assertEquals(1, handle.removeCallbackCount)
        assertEquals(listOf(session), listener.removed)
        assertEquals(CallState.DISCONNECTED, listener.snapshots.single().state)
    }

    @Test
    fun duplicateHangupDoesNotDisconnectTelecomTwice() {
        val handle = FakeCallHandle()
        val sink = RecordingSink()
        val registry = CallRegistry(sink = sink)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))
        handle.emit(snapshot = ringingSnapshot().copy(state = CallState.ACTIVE), source = ActionSource.MODULE)

        assertEquals(CommandResult.Accepted, registry.hangup(session))
        assertEquals(Rejected(NOT_OWNED), registry.hangup(session))

        assertEquals(1, handle.disconnectCount)
        assertEquals(2, sink.attempts.count { it.command == CallCommand.Hangup })
    }

    @Test
    fun moduleAnswerIsClaimedThroughTheProductionSourceDerivation() {
        val handle = FakeCallHandle()
        val registry = CallRegistry()
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))

        // 不硬编码 MODULE：来源由生产用的 claimSource 推导（目标状态匹配 + 窗口内）。
        val claim = TelecomDecisions.claimSource(
            telecomState = Call.STATE_ACTIVE,
            pendingTargetState = Call.STATE_ACTIVE,
            pendingAtNs = 1_000L,
            nowNs = 2_000L,
        )
        handle.emit(snapshot = ringingSnapshot().copy(state = CallState.ACTIVE), source = claim.source)

        assertEquals(ActionSource.MODULE, claim.source)
        assertFalse(registry.isPendingAnswer(session))
        assertFalse(registry.isHandedBack(session))
        assertEquals(CommandResult.Accepted, registry.hangup(session))
        assertEquals(1, handle.disconnectCount)
    }

    @Test
    fun unknownTransientStateDoesNotCancelPendingAnswer() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))

        // 门面把未映射的瞬时状态上报为 UNKNOWN_STATE，快照里的状态是保守映射（ACTIVE）。
        handle.emit(
            kind = EventKind.UNKNOWN_STATE,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE),
            reason = "telecom_state_unknown_" + 12,
        )

        assertTrue(registry.isPendingAnswer(session))
        assertFalse(registry.isHandedBack(session))
        assertTrue(listener.handbacks.isEmpty())
        // 不猜测：保留上一次已知状态，绝不因为瞬时状态把待执行接听当成人工接听。
        assertEquals(CallState.RINGING, registry.snapshot(session)?.state)
        assertEquals(1, handle.answerCount)
    }

    @Test
    fun actionFailureDuringModuleOwnershipHandsBackToHuman() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))
        handle.emit(snapshot = ringingSnapshot().copy(state = CallState.ACTIVE), source = ActionSource.MODULE)

        handle.disconnectOutcome = CommandResult.Failed("disconnect_failed", "SECURITY")
        assertEquals(CommandResult.Failed("disconnect_failed", "SECURITY"), registry.hangup(session))
        handle.emit(
            kind = EventKind.ACTION_FAILED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE),
            reason = "disconnect_precheck_failed",
        )

        assertTrue(registry.isHandedBack(session))
        assertEquals("disconnect_precheck_failed", listener.handbacks.single().third)
        // 所有权已交还：再挂断既不下发 Telecom，也不会掩盖人工通话。
        assertEquals(Rejected(NOT_OWNED), registry.hangup(session))
        assertEquals(1, handle.disconnectCount)
    }

    @Test
    fun answerSafetyGateRefusesUnsafeCalls() {
        val unsafe = listOf(
            "emergency" to ringingSnapshot().copy(isEmergency = true),
            "conference" to ringingSnapshot().copy(isConference = true),
            "second_active_call" to ringingSnapshot().copy(activeCallCount = 2),
            "outgoing" to ringingSnapshot().copy(direction = CallDirection.OUTGOING),
            "zero_active_calls" to ringingSnapshot().copy(activeCallCount = 0),
        )
        for ((name, snapshot) in unsafe) {
            val handle = FakeCallHandle(current = snapshot)
            val registry = CallRegistry()
            val session = registry.observe(handle)

            assertEquals(name, Rejected(NOT_OWNED), registry.answer(session))
            assertEquals(name, 0, handle.answerCount)
            assertFalse(name, registry.isPendingAnswer(session))
        }
    }

    @Test
    fun hiddenNumbersReachAnswerBecauseTheCoreOwnsThatPolicy() {
        // I1：号码可识别性（含隐藏/未知呈现）由 core RuleMatcher 依 handleUnknownNumbers
        // 裁决；hook 只保留硬排除（紧急/会议/第二路/出站），否则 spec §7 的总开关永远无效。
        val candidates = listOf(
            "restricted" to ringingSnapshot().copy(presentation = Presentation.RESTRICTED, number = null),
            "unknown_presentation" to ringingSnapshot().copy(presentation = Presentation.UNKNOWN, number = null),
            "payphone" to ringingSnapshot().copy(presentation = Presentation.PAYPHONE, number = null),
        )
        for ((name, snapshot) in candidates) {
            val handle = FakeCallHandle(current = snapshot)
            val sink = RecordingSink()
            val registry = CallRegistry(sink = sink)
            val session = registry.observe(handle)

            assertEquals(name, CommandResult.Accepted, registry.answer(session))
            assertEquals(name, 1, handle.answerCount)
            assertEquals(name, ActionSource.MODULE, sink.attempts.single().source)
        }
    }

    @Test
    fun coreRuleMatcherRemainsTheOnlyNumberIdentifiabilityPolicy() {
        // 同一份隐藏号码快照：开关 ON 由 core 判定可接管，开关 OFF 保留响铃。
        // hook 不接收配置，因此不能自己再判一次 presentation/number。
        val account = "SIM1"
        val hidden = CallSnapshot(
            id = SessionId(processEpoch = "epoch-1", callId = "call-1"),
            number = null,
            state = CallState.RINGING,
            direction = CallDirection.INCOMING,
            presentation = Presentation.RESTRICTED,
            phoneAccount = account,
            isEmergency = false,
            isConference = false,
            activeCallCount = 1,
        )
        val enabled = AgentConfig(
            enabled = true,
            blacklist = listOf("+8613800000000"),
            enabledPhoneAccounts = listOf(account),
            handleUnknownNumbers = true,
        )

        assertTrue(RuleMatcher.matches(enabled, hidden))
        assertFalse(
            "the toggle OFF must keep the ring for a human",
            RuleMatcher.matches(enabled.copy(handleUnknownNumbers = false), hidden),
        )
    }

    @Test
    fun benignDuplicateRouteEventAfterAnswerDoesNotHandBack() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))
        handle.emit(
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "earpiece"),
            source = ActionSource.MODULE,
        )

        // I2(b)：框架在接通后重复上报同一条音频状态/端点，不得当成用户改路由。
        handle.emit(
            kind = EventKind.ROUTE_CHANGED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "earpiece"),
            reason = "audio_route_changed",
        )

        assertFalse(registry.isHandedBack(session))
        assertTrue(listener.handbacks.isEmpty())
        assertEquals("module keeps ownership after a duplicate route report", CommandResult.Accepted, registry.hangup(session))
        assertEquals(1, handle.disconnectCount)
    }

    @Test
    fun routeEventBeforeActiveRebaselinesWithoutHandingBack() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))

        // 接听窗口内框架切到听筒：不改所有权，但成为新的基线。
        handle.emit(
            kind = EventKind.ROUTE_CHANGED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(routeFingerprint = "earpiece"),
        )
        handle.emit(
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "earpiece"),
            source = ActionSource.MODULE,
        )
        handle.emit(
            kind = EventKind.ROUTE_CHANGED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = "earpiece"),
        )

        assertFalse(registry.isHandedBack(session))
        assertTrue(listener.handbacks.isEmpty())
        assertEquals(CommandResult.Accepted, registry.hangup(session))
    }

    @Test
    fun routeEventWithUnknownFingerprintCannotProveAChange() {
        val handle = FakeCallHandle()
        val listener = RecordingListener()
        val registry = CallRegistry(listener = listener)
        val session = registry.observe(handle)
        assertEquals(CommandResult.Accepted, registry.answer(session))
        handle.emit(
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = null),
            source = ActionSource.MODULE,
        )

        // 没有可比较的音频状态时不得凭空交还（这正是框架重复事件的误伤路径）。
        handle.emit(
            kind = EventKind.ROUTE_CHANGED,
            source = ActionSource.UNKNOWN,
            snapshot = ringingSnapshot().copy(state = CallState.ACTIVE, routeFingerprint = null),
        )

        assertFalse(registry.isHandedBack(session))
        assertTrue(listener.handbacks.isEmpty())
        assertEquals(CommandResult.Accepted, registry.hangup(session))
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
            routeFingerprint = "earpiece",
        )
    }
}
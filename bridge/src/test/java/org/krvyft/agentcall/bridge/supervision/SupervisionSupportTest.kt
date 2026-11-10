package org.krvyft.agentcall.bridge.supervision

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.krvyft.agentcall.bridge.audio.AudioFailureCode
import org.krvyft.agentcall.bridge.audio.BridgeProcessRole
import org.krvyft.agentcall.bridge.audio.IsolationProbe
import org.krvyft.agentcall.bridge.audio.ProcessFaultDecision
import org.krvyft.agentcall.bridge.audio.SupervisedAudio
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.core.session.SessionLimits

/**
 * 监督角色支撑设施测试（I5）：重启预算必须留在监督端；最小状态必须原子持久化并
 * 在启动时 fail closed 地恢复隔离；周期轮询必须真的驱动 [Supervisor.poll]。
 */
class RestartBudgetTest {

    @Test
    fun budgetIsBoundedWithinWindow() {
        val budget = RestartBudget(maxRestarts = 3, windowMs = 60_000)

        assertEquals(ProcessFaultDecision.RestartAllowed(1), budget.record(0))
        assertEquals(ProcessFaultDecision.RestartAllowed(2), budget.record(1_000))
        assertEquals(ProcessFaultDecision.RestartAllowed(3), budget.record(2_000))
        assertEquals(ProcessFaultDecision.RestartExhausted(3), budget.record(3_000))
        assertEquals(3, budget.used(3_000))
    }

    @Test
    fun oldAttemptsLeaveTheWindow() {
        val budget = RestartBudget(maxRestarts = 1, windowMs = 10_000)

        assertEquals(ProcessFaultDecision.RestartAllowed(1), budget.record(0))
        assertEquals(ProcessFaultDecision.RestartExhausted(1), budget.record(5_000))
        assertEquals(ProcessFaultDecision.RestartAllowed(1), budget.record(10_001))
    }
}

class SupervisionStateStoreTest {

    private fun tempRoot(): File = Files.createTempDirectory("agentcall-supervision-state").toFile()

    private val state = SupervisionPersistedState(
        phase = SupervisorPhase.TERMINATING,
        ownedSession = SessionId("epoch-1", "call-a"),
        microphoneIsolated = true,
        blockedReason = null,
        lastFault = "peer_lost_APP",
        updatedAtMs = 1_234,
    )

    @Test
    fun roundTripsStateAtomically() {
        val root = tempRoot()
        try {
            val store = SupervisionStateStore(root)
            store.save(state)

            val loaded = SupervisionStateStore(root).load()

            assertEquals(state, loaded)
            assertTrue(File(root, SupervisionStateStore.FILE_NAME).isFile)
            assertFalse(File(root, SupervisionStateStore.FILE_NAME + ".tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun roundTripsThePersistedShutdownCode() {
        val root = tempRoot()
        try {
            val store = SupervisionStateStore(root)
            store.save(
                state.copy(
                    phase = SupervisorPhase.ENDED,
                    ownedSession = null,
                    shutdownCode = Supervisor.SHUTDOWN_MICROPHONE_STILL_ISOLATED,
                ),
            )

            val loaded = SupervisionStateStore(root).load()

            assertEquals(
                "关机机器码必须跨进程可读（下一次启动的 status.shutdownCode）",
                Supervisor.SHUTDOWN_MICROPHONE_STILL_ISOLATED,
                loaded?.shutdownCode,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyStateWithoutShutdownCodeDecodesAsNull() {
        val root = tempRoot()
        try {
            File(root, SupervisionStateStore.FILE_NAME).writeText(
                "{\"schema\":2,\"phase\":\"ENDED\",\"ownedSession\":null," +
                    "\"microphoneIsolated\":true,\"blockedReason\":null,\"lastFault\":null," +
                    "\"updatedAtMs\":1,\"bootId\":null,\"ownedCallMayBeLive\":true}",
                StandardCharsets.UTF_8,
            )

            val loaded = SupervisionStateStore(root).load()

            assertNotNull(loaded)
            assertNull("旧 schema 没有关机码时必须是 null，不能伪造", loaded!!.shutdownCode)
            assertTrue("旧 schema 的粘性标记仍按保守值保留", loaded.ownedCallMayBeLive)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingStateIsNull() {
        val root = tempRoot()
        try {
            assertNull(SupervisionStateStore(root).load())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptStateFailsClosed() {
        val root = tempRoot()
        try {
            File(root, SupervisionStateStore.FILE_NAME).writeText("not json", StandardCharsets.UTF_8)
            try {
                SupervisionStateStore(root).load()
                fail("损坏的状态必须抛错，不能当成干净启动")
            } catch (expected: SupervisionStateException) {
                assertEquals(SupervisionStateErrorCode.CORRUPT, expected.code)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun newerSchemaFailsClosed() {
        val root = tempRoot()
        try {
            File(root, SupervisionStateStore.FILE_NAME).writeText(
                "{\"schema\":99,\"phase\":\"IDLE\",\"microphoneIsolated\":false}",
                StandardCharsets.UTF_8,
            )
            try {
                SupervisionStateStore(root).load()
                fail("更新 schema 的状态必须拒绝读取")
            } catch (expected: SupervisionStateException) {
                assertEquals(SupervisionStateErrorCode.UNSUPPORTED_SCHEMA, expected.code)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

class SupervisorBootRecoveryTest {

    @Test
    fun bootAlwaysRestoresAndVerifiesMicrophoneBeforeEnablingAnything() = runBlocking {
        val audio = SupportAudio()
        val store = FakeStateStore(
            SupervisionPersistedState(
                phase = SupervisorPhase.TERMINATING,
                ownedSession = SessionId("epoch-old", "old-call"),
                microphoneIsolated = true,
                blockedReason = null,
                lastFault = "peer_lost_HOOK",
                updatedAtMs = 1,
            ),
        )
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(audio, store, { blocked = it }, SupportClock())

        val outcome = recovery.recover()

        assertEquals(BootRecoveryOutcome.RESTORED, outcome)
        assertEquals(1, audio.restoreCalls)
        assertEquals(1, audio.verifyCalls)
        assertNull("旧所有权绝不能绑定到新通话", store.load()!!.ownedSession)
        assertEquals(SupervisorPhase.IDLE, store.load()!!.phase)
        assertNull(blocked)
    }

    @Test
    fun stillIsolatedAfterRestoreBlocksNewSessions() = runBlocking {
        val audio = SupportAudio().apply { systemIsolated = true; restoreEffective = false }
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(
            audio,
            FakeStateStore(null),
            { blocked = it },
            SupportClock(),
        )

        assertEquals(BootRecoveryOutcome.BLOCKED, recovery.recover())
        assertEquals(SupervisorBootRecovery.BLOCK_STILL_ISOLATED, blocked)
    }

    /**
     * I1：不可达不是“隔离丢失”。工作进程晚启动时启动恢复必须返回 DEFERRED 并登记
     * 可重试的未验证阻塞，而不是 3×500 ms 后永久 BLOCKED。
     */
    @Test
    fun unreachableWorkerDefersRecoveryInsteadOfBlocking() = runBlocking {
        val audio = SupportAudio().apply {
            restoreResult = false
            probeResult = IsolationProbe.Unverified("audio_worker_unreachable")
        }
        var blocked: String? = null
        var deferredReason: String? = null
        var deadlineAtMs = 0L
        val recovery = SupervisorBootRecovery(
            audio,
            FakeStateStore(null),
            { blocked = it },
            SupportClock(1_000),
            beginRecovery = { reason, deadline ->
                deferredReason = reason
                deadlineAtMs = deadline
            },
        )

        assertEquals(BootRecoveryOutcome.DEFERRED, recovery.recover())
        assertNull("不可达绝不是永久阻塞的证据", blocked)
        assertEquals(SupervisorBootRecovery.BLOCK_RESTORE_FAILED, deferredReason)
        assertEquals(
            "重试必须有总期限（不是无限重试）",
            1_000 + SupervisorBootRecovery.DEFAULT_RECOVERY_DEADLINE_MS,
            deadlineAtMs,
        )
    }

    /** I1：工作进程明确回报错误是确定证据，仍必须立即 fail closed。 */
    @Test
    fun definiteWorkerErrorBlocksImmediately() = runBlocking {
        val audio = SupportAudio().apply {
            restoreResult = false
            probeResult = IsolationProbe.WorkerError("audio_isolation_read_failed")
        }
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(audio, FakeStateStore(null), { blocked = it }, SupportClock())

        assertEquals(BootRecoveryOutcome.BLOCKED, recovery.recover())
        assertEquals(SupervisorBootRecovery.BLOCK_RESTORE_FAILED, blocked)
    }

    @Test
    fun sameBootRestartWithOwnedCallKeepsIsolationAndBlocks() = runBlocking {
        val audio = SupportAudio().apply { systemIsolated = true }
        val store = FakeStateStore(
            SupervisionPersistedState(
                phase = SupervisorPhase.ACTIVE,
                ownedSession = SessionId("epoch-1", "live-call"),
                microphoneIsolated = true,
                blockedReason = null,
                lastFault = null,
                updatedAtMs = 1_000,
                bootId = "boot-a",
            ),
        )
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(
            audio,
            store,
            { blocked = it },
            SupportClock(5_000),
            bootId = { "boot-a" },
        )

        assertEquals(BootRecoveryOutcome.BLOCKED, recovery.recover())
        assertEquals("同一开机内重启不得解除可能仍活着的 AI 通话隔离", 0, audio.restoreCalls)
        assertEquals(SupervisorBootRecovery.BLOCK_RESTART_WITH_OWNED_CALL, blocked)
        assertTrue(audio.systemIsolated)
    }

    @Test
    fun rebootWithPersistedOwnershipRestoresMicrophoneAndClearsStaleOwnership() = runBlocking {
        val audio = SupportAudio().apply { systemIsolated = true }
        val store = FakeStateStore(
            SupervisionPersistedState(
                phase = SupervisorPhase.ACTIVE,
                ownedSession = SessionId("epoch-old", "dead-call"),
                microphoneIsolated = true,
                blockedReason = null,
                lastFault = null,
                updatedAtMs = 100_000,
                bootId = "boot-a",
            ),
        )
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(
            audio,
            store,
            { blocked = it },
            SupportClock(100),
            bootId = { "boot-b" },
        )

        assertEquals(BootRecoveryOutcome.RESTORED, recovery.recover())
        assertEquals(1, audio.restoreCalls)
        assertNull(blocked)
        assertNull("旧所有权绝不绑定新通话", store.load()!!.ownedSession)
        assertEquals("boot-b", store.load()!!.bootId)
        assertFalse(audio.systemIsolated)
    }

    @Test
    fun corruptStateFailsClosedButStillAttemptsRestore() = runBlocking {
        val audio = SupportAudio()
        var blocked: String? = null
        val store = object : SupervisionStateStorePort {
            override fun load(): SupervisionPersistedState =
                throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "corrupt")

            override fun save(state: SupervisionPersistedState) = Unit
        }
        val recovery = SupervisorBootRecovery(audio, store, { blocked = it }, SupportClock())

        assertEquals(BootRecoveryOutcome.BLOCKED, recovery.recover())
        assertEquals(1, audio.restoreCalls)
        assertEquals(SupervisorBootRecovery.BLOCK_STATE_CORRUPT, blocked)
    }
}

class SupervisionLoopTest {

    @Test
    fun pollOnceDelegatesToSupervisorPollAndReportsActions() = runBlocking {
        var observedNow = -1L
        val expected = listOf(SupervisorAction.BlockNewSessions("test"))
        var sinkActions: List<SupervisorAction> = emptyList()
        val loop = SupervisionLoop(
            poll = { now ->
                observedNow = now
                expected
            },
            clock = SupportClock(42L),
            actionSink = { sinkActions = it },
        )

        val actions = loop.pollOnce()

        assertEquals(42L, observedNow)
        assertEquals(expected, actions)
        assertEquals(expected, sinkActions)
    }

    @Test
    fun runKeepsDrivingAfterATransientPollFailure() = runBlocking {
        var polls = 0
        val loop = SupervisionLoop(
            poll = {
                polls++
                if (polls == 1) error("transient poll failure")
                emptyList()
            },
            clock = SupportClock(),
            intervalMs = 1L,
        )

        loop.run { polls >= 3 }

        assertEquals("单轮故障不能让轮询驱动静默退出", 3, polls)
    }

    @Test
    fun runInvokesBeforePollEachIterationAndSurvivesItsFailure() = runBlocking {
        var polls = 0
        var cleanups = 0
        val loop = SupervisionLoop(
            poll = {
                polls++
                emptyList()
            },
            clock = SupportClock(),
            intervalMs = 1L,
            beforePoll = {
                cleanups++
                error("cleanup failure must never stop the poll driver")
            },
        )

        loop.run { polls >= 3 }

        assertEquals(3, polls)
        assertTrue("每轮都必须尝试清理（实际 " + cleanups + " 次）", cleanups >= 3)
    }

    @Test
    fun runStopsWhenPredicateSaysSo() = runBlocking {
        var polls = 0
        val loop = SupervisionLoop(
            poll = {
                polls++
                emptyList()
            },
            clock = SupportClock(),
            intervalMs = 1L,
        )

        loop.run { polls >= 3 }

        assertEquals(3, polls)
    }
}

/**
 * C1 生产装配测试：用假平台构造真实的生产图（不是另写一套逻辑），证明角色入口
 * 构造的 Supervisor / 启动恢复 / 周期轮询 / 状态持久化真的接在一起。
 */
class SupervisorProductionGraphTest {

    private val session = SessionId("epoch-1", "call-a")

    private class FakePlatform(
        val clock: SupportClock = SupportClock(),
        val audio: SupportAudio = SupportAudio(),
        val calls: SupportCalls = SupportCalls(),
        val emergency: EmergencyCallControl,
        val authorization: AutoAnswerAuthorization,
        val store: FakeStateStore = FakeStateStore(null),
        val bootIdValue: String? = null,
        val probe: CallStateProbe = CallStateProbe { null },
    ) : SupervisionPlatform {
        override fun clock(): MonotonicClock = clock
        override fun supervisedAudio(): SupervisedAudio = audio
        override fun calls(): CallControl = calls
        override fun emergency(): EmergencyCallControl = emergency
        override fun authorization(): AutoAnswerAuthorization = authorization
        override fun stateStore(): SupervisionStateStorePort = store
        override fun bootId(): String? = bootIdValue
        override fun callState(): CallStateProbe = probe
    }

    private fun verifiedEmergency(): EmergencyCallControl = EmergencyCallControl(
        TerminationPermission { true },
        object : CallIdentityVerifier {
            override fun isAvailable(): Boolean = true
            override suspend fun verify(id: SessionId): VerifiedCallIdentity? =
                VerifiedCallIdentity(id, "telecom-1")
        },
        PrivilegedTermination { CommandResult.Accepted },
    )

    private fun platform(authorized: Boolean = true): FakePlatform =
        FakePlatform(
            emergency = verifiedEmergency(),
            authorization = AutoAnswerAuthorization { authorized },
        )

    @Test
    fun graphWiresStartupRecoveryPollDriverAndStatePersistence() = runBlocking {
        val platform = platform()
        val graph = SupervisorProductionGraph.build(platform)

        assertEquals(BridgeProcessRole.SUPERVISOR, graph.supervisor.role)
        assertEquals(BootRecoveryOutcome.CLEAN, graph.recovery.recover())
        assertEquals("启动恢复必须先恢复并读回隔离", 1, platform.audio.restoreCalls)
        assertEquals(1, platform.audio.verifyCalls)

        graph.supervisor.start()
        platform.heartbeat(graph.supervisor, session)
        assertEquals(CommandResult.Accepted, graph.supervisor.prepareAnswer(session))
        assertTrue(platform.audio.isolateCalls > 0)
        assertNotNull("状态迁移必须持久化最小状态", platform.store.load())

        platform.clock.advance(SessionLimits.HEARTBEAT_TIMEOUT_MS + 1)
        val actions = graph.loop.pollOnce()

        assertTrue("生产轮询必须真的驱动 Supervisor.poll", actions.any { it is SupervisorAction.TerminateCall })
        assertEquals(listOf(session to CallCommand.Hangup), platform.calls.commands.filter { it.second == CallCommand.Hangup })
        assertTrue(platform.audio.microphoneIsolated)

        // Task 11D2a：租约已撤销（上一轮故障终止已接受挂断）→ AI 不再拥有该通话；
        // 优雅关闭必须恢复麦克风，而不是把系统全局静音留给下一次启动。
        val result = graph.supervisor.shutdown(deadlineMs = 200)
        assertTrue("租约撤销/已确认非 AI 所有时关闭必须恢复麦克风", result.clean)
        assertFalse("关闭后麦克风不得仍然被静音", platform.audio.microphoneIsolated)
        assertEquals(
            "启动恢复一次 + 关闭交还一次",
            2,
            platform.audio.restoreCalls,
        )
    }

    /**
     * Task 11E（minor）：status.shutdownCode 在进程退出后无法从内存读取，所以关闭机器码
     * 写入监督状态文件；下一次构建生产图时读出来，供启动后的 status 报告。
     */
    @Test
    fun graphExposesTheShutdownCodePersistedByThePreviousProcess() {
        val store = FakeStateStore(
            SupervisionPersistedState(
                phase = SupervisorPhase.ENDED,
                ownedSession = null,
                microphoneIsolated = true,
                blockedReason = null,
                lastFault = Supervisor.SHUTDOWN_MICROPHONE_STILL_ISOLATED,
                updatedAtMs = 1,
                shutdownCode = Supervisor.SHUTDOWN_MICROPHONE_STILL_ISOLATED,
            ),
        )

        val graph = SupervisorProductionGraph.build(
            FakePlatform(
                emergency = verifiedEmergency(),
                authorization = AutoAnswerAuthorization { true },
                store = store,
            ),
        )

        assertEquals(
            "下一次启动必须能读到上一次关闭的机器码",
            Supervisor.SHUTDOWN_MICROPHONE_STILL_ISOLATED,
            graph.previousShutdownCode,
        )
    }

    @Test
    fun graphHasNoPreviousShutdownCodeForAFreshStore() {
        val graph = SupervisorProductionGraph.build(platform())

        assertNull(graph.previousShutdownCode)
    }

    @Test
    fun authorizationIsInjectedAndBlocksOwnershipWhenDenied() = runBlocking {
        val platform = platform(authorized = false)
        val graph = SupervisorProductionGraph.build(platform)
        graph.recovery.recover()
        graph.supervisor.start()
        platform.heartbeat(graph.supervisor, session)

        assertEquals(
            CommandResult.Rejected(org.krvyft.agentcall.core.model.RejectReason.UNSUPPORTED),
            graph.supervisor.prepareAnswer(session),
        )
        assertEquals("未授权时不得隔离麦克风或取得租约", 0, platform.audio.isolateCalls)
    }

    /**
     * 最小缺陷：生产图必须注入真实的 [CallStateProbe]（Hook 快照收件箱），否则
     * “接听结果不明确”只能永远按不可核验处理，已有核验路径在生产上是死代码。
     */
    @Test
    fun productionGraphInjectsTheHookCallStateProbeForAmbiguousAnswers() = runBlocking {
        val platform = platform()
        platform.calls.result = CommandResult.Failed("hook_command_timeout", "no result from hook")
        val graph = SupervisorProductionGraph.build(
            FakePlatform(
                clock = platform.clock,
                audio = platform.audio,
                calls = platform.calls,
                emergency = platform.emergency,
                authorization = platform.authorization,
                store = platform.store,
                probe = CallStateProbe { CallState.DISCONNECTED },
            ),
        )
        graph.recovery.recover()
        graph.supervisor.start()
        platform.heartbeat(graph.supervisor, session)

        val result = graph.supervisor.prepareAnswer(session)

        assertTrue(result is CommandResult.Failed)
        assertFalse("确认已断开后必须恢复麦克风，而不是当成不可核验", platform.audio.microphoneIsolated)
        assertEquals("启动恢复 1 次 + 明确未接听 1 次", 2, platform.audio.restoreCalls)
    }

    /**
     * 最小缺陷：重启预算必须由生产路径驱动。音频工作进程不可达时
     * [SupervisedAudio.takeProcessFault] 会带出故障，监督端每轮 poll 计入预算，
     * 耗尽即故障终止（而不是只在测试里调用 onProcessFault）。
     */
    @Test
    fun audioWorkerFaultsFromTheProductionAudioPortExhaustTheRestartBudget() = runBlocking {
        val platform = platform()
        val graph = SupervisorProductionGraph.build(platform)
        graph.recovery.recover()
        graph.supervisor.start()
        platform.heartbeat(graph.supervisor, session)
        assertEquals(CommandResult.Accepted, graph.supervisor.prepareAnswer(session))

        repeat(RestartBudget.DEFAULT_MAX_RESTARTS) {
            platform.audio.faults.addLast("audio_worker_unreachable")
            assertTrue(
                "预算内的音频工作进程故障只允许重启，不得终止通话",
                graph.loop.pollOnce().none { it is SupervisorAction.TerminateCall },
            )
        }
        platform.audio.faults.addLast("audio_worker_unreachable")
        val actions = graph.loop.pollOnce()

        assertTrue(
            "重启预算耗尽必须由生产轮询触发故障终止",
            actions.any { it is SupervisorAction.TerminateCall },
        )
        assertTrue("终止未确认前不得恢复麦克风", platform.audio.microphoneIsolated)
    }

    /**
     * 新增重要缺陷 2：粘性同一开机守卫。第一次重启时 block() 会把 ownedSession 写成
     * null（并缓存 microphoneIsolated=false），如果所有权标记不独立持久化，第二次重启
     * 就会看到“无所有权”而恢复麦克风——而泄漏的音频工作进程可能仍持有这通电话。
     */
    @Test
    fun twoConsecutiveSameBootRestartsNeverRestoreTheMicrophone() = runBlocking {
        val boot = "boot-a"
        val leaked = SessionId("epoch-1", "leaked-call")
        val store = FakeStateStore(
            SupervisionPersistedState(
                phase = SupervisorPhase.ACTIVE,
                ownedSession = leaked,
                microphoneIsolated = true,
                blockedReason = null,
                lastFault = null,
                updatedAtMs = 1_000,
                bootId = boot,
            ),
        )

        val firstAudio = SupportAudio().apply { systemIsolated = true }
        val first = SupervisorProductionGraph.build(
            FakePlatform(
                audio = firstAudio,
                emergency = verifiedEmergency(),
                authorization = AutoAnswerAuthorization { true },
                store = store,
                bootIdValue = boot,
            ),
        )
        assertEquals(BootRecoveryOutcome.BLOCKED, first.recovery.recover())
        assertEquals(
            "同一开机内第一次重启不得解除可能仍活着的 AI 通话隔离",
            0,
            firstAudio.restoreCalls,
        )
        assertTrue("必须持久化独立于 ownedSession 的粘性标记", store.load()!!.ownedCallMayBeLive)

        // 第二次重启：block() 已把 ownedSession 置空，但粘性标记必须仍然阻止开麦。
        val secondAudio = SupportAudio().apply { systemIsolated = true }
        val second = SupervisorProductionGraph.build(
            FakePlatform(
                audio = secondAudio,
                emergency = verifiedEmergency(),
                authorization = AutoAnswerAuthorization { true },
                store = store,
                bootIdValue = boot,
            ),
        )
        assertEquals(BootRecoveryOutcome.BLOCKED, second.recovery.recover())
        assertEquals(
            "第二次重启同样不得把麦克风打开给可能还活着的 AI 通话",
            0,
            secondAudio.restoreCalls,
        )
        assertTrue(store.load()!!.ownedCallMayBeLive)
    }

    /**
     * 设备重启会结束泄漏的音频工作进程：新开机里启动恢复验证并恢复麦克风后，粘性标记
     * 必须清掉；随后一次普通关闭不得把旧标记重新写成 true（否则下一次重启无故 BLOCKED）。
     */
    @Test
    fun rebootClearsTheStickyMarkerAfterVerifiedRestore() = runBlocking {
        val store = FakeStateStore(
            SupervisionPersistedState(
                phase = SupervisorPhase.ACTIVE,
                ownedSession = null,
                microphoneIsolated = true,
                blockedReason = null,
                lastFault = null,
                updatedAtMs = 1_000,
                bootId = "boot-a",
                ownedCallMayBeLive = true,
            ),
        )
        val audio = SupportAudio().apply { systemIsolated = true }
        val graph = SupervisorProductionGraph.build(
            FakePlatform(
                audio = audio,
                emergency = verifiedEmergency(),
                authorization = AutoAnswerAuthorization { true },
                store = store,
                bootIdValue = "boot-b",
            ),
        )

        assertEquals(BootRecoveryOutcome.RESTORED, graph.recovery.recover())
        assertEquals("新开机必须验证并恢复麦克风", 1, audio.restoreCalls)
        assertFalse("已验证恢复后粘性标记必须清除", store.load()!!.ownedCallMayBeLive)

        graph.supervisor.block("test_block")
        assertFalse("新开机的普通关闭不得复活旧开机的粘性标记", store.load()!!.ownedCallMayBeLive)
    }

    /**
     * I1：工作进程晚启动（瞬时不可达）不得永久禁用模块。启动恢复先 DEFERRED，自动接听
     * 保持关闭；工作进程起来后，轮询取得“系统确认未隔离”证据即清除阻塞并恢复就绪。
     */
    @Test
    fun transientUnreachableWorkerNeverPermanentlyBlocksAndRecoveryClearsTheBlocker() = runBlocking {
        val audio = SupportAudio().apply {
            restoreResult = false
            probeResult = IsolationProbe.Unverified("audio_worker_unreachable")
        }
        val platform = FakePlatform(
            audio = audio,
            emergency = verifiedEmergency(),
            authorization = AutoAnswerAuthorization { true },
        )
        val graph = SupervisorProductionGraph.build(platform)

        assertEquals(BootRecoveryOutcome.DEFERRED, graph.recovery.recover())
        assertEquals(
            "未取得隔离证据期间自动接听必须保持关闭",
            SupervisorBootRecovery.BLOCK_RESTORE_FAILED,
            graph.supervisor.snapshot().blockedReason,
        )
        graph.supervisor.start()
        platform.heartbeat(graph.supervisor, session)
        assertFalse("未验证期间不得就绪", graph.supervisor.isReady())
        assertEquals(
            CommandResult.Rejected(org.krvyft.agentcall.core.model.RejectReason.UNSUPPORTED),
            graph.supervisor.prepareAnswer(session),
        )

        // 音频工作进程起来了：恢复成功且系统读回确认未隔离。
        audio.restoreResult = true
        audio.probeResult = IsolationProbe.Verified(false)
        platform.clock.advance(Supervisor.RECOVERY_RETRY_INTERVAL_MS)
        assertFalse(
            "取证成功的轮询不得再新增长期阻塞",
            graph.loop.pollOnce().any { it is SupervisorAction.BlockNewSessions },
        )

        assertNull("取得系统证据后必须清除可恢复阻塞", graph.supervisor.snapshot().blockedReason)
        assertEquals(SupervisorPhase.IDLE, graph.supervisor.snapshot().phase)
        assertTrue(graph.supervisor.isReady())
        audio.probeResult = null
        assertEquals(CommandResult.Accepted, graph.supervisor.prepareAnswer(session))
    }

    /** I1：总期限用尽仍未取得证据时仍必须 fail closed（保持隔离并阻止新会话）。 */
    @Test
    fun unverifiedRecoveryFailsClosedAfterTheBoundedDeadline() = runBlocking {
        val audio = SupportAudio().apply {
            restoreResult = false
            probeResult = IsolationProbe.Unverified("audio_worker_unreachable")
        }
        val clock = SupportClock()
        val graph = SupervisorProductionGraph.build(
            FakePlatform(
                clock = clock,
                audio = audio,
                emergency = verifiedEmergency(),
                authorization = AutoAnswerAuthorization { true },
            ),
        )
        assertEquals(BootRecoveryOutcome.DEFERRED, graph.recovery.recover())
        graph.supervisor.start()

        // 启动恢复登记的期限从 0 开始：超过 DEFAULT_RECOVERY_DEADLINE_MS 后轮询必须阻塞。
        clock.advance(SupervisorBootRecovery.DEFAULT_RECOVERY_DEADLINE_MS + 1)
        val actions = graph.loop.pollOnce()

        assertTrue(
            "超期仍未取得证据必须显式阻止新会话",
            actions.any { it is SupervisorAction.BlockNewSessions },
        )
        assertEquals(
            SupervisorBootRecovery.BLOCK_RESTORE_FAILED,
            graph.supervisor.snapshot().blockedReason,
        )
        assertFalse(graph.supervisor.isReady())
    }

    /** I1：之后任一轮读回系统确认“未隔离”，可恢复阻塞必须被清除。 */
    @Test
    fun verifiedNotIsolatedClearsAPreviousRecoveryBlock() = runBlocking {
        val audio = SupportAudio().apply {
            restoreResult = false
            probeResult = IsolationProbe.Unverified("audio_worker_unreachable")
        }
        val clock = SupportClock()
        val graph = SupervisorProductionGraph.build(
            FakePlatform(
                clock = clock,
                audio = audio,
                emergency = verifiedEmergency(),
                authorization = AutoAnswerAuthorization { true },
            ),
        )
        graph.recovery.recover()
        graph.supervisor.start()
        clock.advance(SupervisorBootRecovery.DEFAULT_RECOVERY_DEADLINE_MS + 1)
        graph.loop.pollOnce()
        assertEquals(SupervisorBootRecovery.BLOCK_RESTORE_FAILED, graph.supervisor.snapshot().blockedReason)

        audio.probeResult = IsolationProbe.Verified(false)
        clock.advance(Supervisor.RECOVERY_RETRY_INTERVAL_MS)
        graph.loop.pollOnce()

        assertNull("系统确认未隔离后必须清除恢复阻塞", graph.supervisor.snapshot().blockedReason)
        assertEquals(SupervisorPhase.IDLE, graph.supervisor.snapshot().phase)
    }

    /** I1：拥有的通话在 ACTIVE 期间遇到“不可达”不得 block + 终止；确认未隔离才 fail closed。 */
    @Test
    fun unverifiedIsolationDuringActiveCallDoesNotTerminateOrBlock() = runBlocking {
        val platform = platform()
        val graph = SupervisorProductionGraph.build(platform)
        graph.recovery.recover()
        graph.supervisor.start()
        platform.heartbeat(graph.supervisor, session)
        assertEquals(CommandResult.Accepted, graph.supervisor.prepareAnswer(session))
        assertEquals(CommandResult.Accepted, graph.supervisor.onCallActive(session))

        platform.audio.probeResult = IsolationProbe.Unverified("audio_worker_unreachable")
        val transient = graph.loop.pollOnce()
        assertTrue(
            "一次不可达不得被当成隔离丢失而 block/终止",
            transient.none { it is SupervisorAction.BlockNewSessions || it is SupervisorAction.TerminateCall },
        )
        assertNull(graph.supervisor.snapshot().blockedReason)

        platform.audio.probeResult = IsolationProbe.Verified(false)
        val definite = graph.loop.pollOnce()
        assertTrue(definite.any { it is SupervisorAction.BlockNewSessions })
        assertTrue(definite.any { it is SupervisorAction.TerminateCall })
        assertFalse(graph.supervisor.isReady())
    }

    private fun FakePlatform.heartbeat(supervisor: Supervisor, id: SessionId) {
        supervisor.heartbeat(id, PeerRole.APP, clock.nowMs())
        supervisor.heartbeat(id, PeerRole.HOOK, clock.nowMs())
    }
}

// ---------------------------------------------------------------------------- 测试替身

private class SupportClock(var now: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = now

    fun advance(byMs: Long) {
        now += byMs
    }
}

private class FakeStateStore(var state: SupervisionPersistedState?) : SupervisionStateStorePort {
    var saves = 0

    override fun load(): SupervisionPersistedState? = state

    override fun save(state: SupervisionPersistedState) {
        saves++
        this.state = state
    }
}

private class SupportAudio : SupervisedAudio {
    var restoreResult = true
    var restoreEffective = true
    var isolateResult = true
    var isolateCalls = 0
    var restoreCalls = 0
    var verifyCalls = 0
    var stopCalls = 0
    var openCalls = 0
    var systemIsolated = false
    var restoreFailuresRemaining = 0
    val faults = ArrayDeque<String>()

    override fun takeProcessFault(): String? = faults.removeFirstOrNull()

    override val microphoneIsolated: Boolean
        get() = systemIsolated

    override suspend fun isolateMicrophone(): Boolean {
        isolateCalls++
        if (isolateResult) systemIsolated = true
        return isolateResult
    }

    override suspend fun verifyIsolation(): Boolean {
        verifyCalls++
        return systemIsolated
    }

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        openCalls++
        return object : AudioSession {
            override val downlink: Flow<PcmChunk> = emptyFlow()
            override suspend fun writeUplink(audio: PcmAudio) = Unit
            override suspend fun stop() = Unit
        }
    }

    override suspend fun stopKeepingIsolation() {
        stopCalls++
    }

    override suspend fun restoreMicrophone(): Boolean {
        restoreCalls++
        if (restoreFailuresRemaining > 0) {
            restoreFailuresRemaining--
            return false
        }
        if (restoreResult && restoreEffective) systemIsolated = false
        return restoreResult
    }

    /** 覆盖三态读回；null 时退化为“进程内布尔就是系统事实”（旧测试替身语义）。 */
    var probeResult: IsolationProbe? = null

    override suspend fun probeIsolation(): IsolationProbe =
        probeResult ?: IsolationProbe.Verified(verifyIsolation())
}

private class SupportCalls : CallControl {
    val commands = mutableListOf<Pair<SessionId, CallCommand>>()
    var result: CommandResult = CommandResult.Accepted

    override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
        commands += id to command
        return result
    }
}
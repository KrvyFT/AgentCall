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
import org.krvyft.agentcall.bridge.audio.ProcessFaultDecision
import org.krvyft.agentcall.bridge.audio.SupervisedAudio
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
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
    fun restoreFailureBlocksNewSessions() = runBlocking {
        val audio = SupportAudio().apply { restoreResult = false }
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(audio, FakeStateStore(null), { blocked = it }, SupportClock())

        assertEquals(BootRecoveryOutcome.BLOCKED, recovery.recover())
        assertEquals(SupervisorBootRecovery.BLOCK_RESTORE_FAILED, blocked)
    }

    @Test
    fun stillIsolatedAfterRestoreBlocksNewSessions() = runBlocking {
        val audio = SupportAudio().apply { systemIsolated = true; restoreEffective = false }
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(audio, FakeStateStore(null), { blocked = it }, SupportClock())

        assertEquals(BootRecoveryOutcome.BLOCKED, recovery.recover())
        assertEquals(SupervisorBootRecovery.BLOCK_STILL_ISOLATED, blocked)
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
            ),
        )
        var blocked: String? = null
        val recovery = SupervisorBootRecovery(audio, store, { blocked = it }, SupportClock(5_000))

        assertEquals(BootRecoveryOutcome.BLOCKED, recovery.recover())
        assertEquals("同一开机内重启不得解除可能仍活着的 AI 通话隔离", 0, audio.restoreCalls)
        assertEquals(SupervisorBootRecovery.BLOCK_RESTART_WITH_OWNED_CALL, blocked)
        assertTrue(audio.systemIsolated)
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
    ) : SupervisionPlatform {
        override fun clock(): MonotonicClock = clock
        override fun supervisedAudio(): SupervisedAudio = audio
        override fun calls(): CallControl = calls
        override fun emergency(): EmergencyCallControl = emergency
        override fun authorization(): AutoAnswerAuthorization = authorization
        override fun stateStore(): SupervisionStateStorePort = store
    }

    private fun platform(authorized: Boolean = true): FakePlatform =
        FakePlatform(
            emergency = EmergencyCallControl(
                TerminationPermission { true },
                object : CallIdentityVerifier {
                    override fun isAvailable(): Boolean = true
                    override suspend fun verify(id: SessionId): VerifiedCallIdentity? =
                        VerifiedCallIdentity(id, "telecom-1")
                },
                PrivilegedTermination { CommandResult.Accepted },
            ),
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

        graph.supervisor.shutdown()
        assertTrue("关闭监督角色必须保持隔离，不得静默开麦", platform.audio.microphoneIsolated)
        assertEquals(
            "关闭监督角色不得再调用恢复麦克风（只允许启动恢复那一次）",
            1,
            platform.audio.restoreCalls,
        )
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
        if (restoreResult && restoreEffective) systemIsolated = false
        return restoreResult
    }
}

private class SupportCalls : CallControl {
    val commands = mutableListOf<Pair<SessionId, CallCommand>>()

    override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
        commands += id to command
        return CommandResult.Accepted
    }
}
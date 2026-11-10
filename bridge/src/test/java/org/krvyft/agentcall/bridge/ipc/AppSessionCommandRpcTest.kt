package org.krvyft.agentcall.bridge.ipc

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.audio.BridgeProcessRole
import org.krvyft.agentcall.bridge.audio.SupervisedAudio
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.bridge.supervision.AutoAnswerAuthorization
import org.krvyft.agentcall.bridge.supervision.CallIdentityVerifier
import org.krvyft.agentcall.bridge.supervision.EmergencyCallControl
import org.krvyft.agentcall.bridge.supervision.HookCallInbox
import org.krvyft.agentcall.bridge.supervision.PrivilegedTermination
import org.krvyft.agentcall.bridge.supervision.Supervisor
import org.krvyft.agentcall.bridge.supervision.SupervisorPhase
import org.krvyft.agentcall.bridge.supervision.SupervisorRpcPort
import org.krvyft.agentcall.bridge.supervision.TerminationPermission
import org.krvyft.agentcall.bridge.supervision.VerifiedCallIdentity
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * Task 7A1 的 APP→bridge 命令/心跳 RPC 的 JVM 测试（无设备）。
 *
 * 这里跑的是**生产分发路径**：同一个 [HookCommandRouter] + [HookCallInbox] +
 * [SupervisorRpcPort] 实例，模拟 HOOK 通过 hook.heartbeat 取走命令、
 * 通过 call.command.result 回报结果。APP 的 session.command 必须拿到这条真实结果，
 * 而不是 bridge 自己编造 Accepted。
 */
class AppSessionCommandRpcTest {

    private lateinit var baseDir: File
    private lateinit var dispatcher: ControlDispatcher
    private lateinit var supervisor: Supervisor
    private val router = HookCommandRouter()
    private val inbox = HookCallInbox()
    private val clock = FakeClock()
    private val audio = RecordingAudio()
    private val calls = RecordingCalls()

    private val session = SessionId("epoch-1", "call-1")
    private val otherSession = SessionId("epoch-1", "call-2")

    private val appPeer = PeerIdentity(
        uid = 10001,
        role = PeerRole.APP,
        packageName = "org.krvyft.agentcall",
        signatureSha256 = "00",
    )
    private val hookPeer = PeerIdentity(
        uid = 10002,
        role = PeerRole.HOOK,
        packageName = "com.google.android.dialer",
        signatureSha256 = "00",
    )
    private val rootPeer = PeerIdentity(uid = 0, role = PeerRole.CLI_ROOT)

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-app-command-rpc-").toFile()
        supervisor = Supervisor(
            clock = clock,
            audio = audio,
            calls = calls,
            emergency = EmergencyCallControl(
                permissions = TerminationPermission { true },
                identities = object : CallIdentityVerifier {
                    override fun isAvailable(): Boolean = true

                    override suspend fun verify(id: SessionId): VerifiedCallIdentity =
                        VerifiedCallIdentity(id, "telecom-call-1")
                },
                termination = PrivilegedTermination { CommandResult.Accepted },
            ),
            authorization = AutoAnswerAuthorization { true },
            role = BridgeProcessRole.SUPERVISOR,
        )
        dispatcher = ControlDispatcher(
            store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L }),
            packages = NoPackages,
            probe = DeviceProbe(
                packages = NoPackages,
                platform = object : PlatformFacts {
                    override fun apiLevel(): Int = 37
                    override fun romFingerprint(): String = "test"
                    override fun kernelVersion(): String = "test"
                    override fun frameworkVersion(): String = "test"
                    override fun audioPolicyDigest(): String = "test"
                },
                audioApiProbe = object : AudioApiProbe {
                    override fun visibleMethods(): Set<String> = emptySet()
                },
                clockMs = { 0L },
                bridgeVersion = "1.0",
            ),
            shutdown = CountDownLatch(1),
            supervision = SupervisorRpcPort(supervisor, inbox),
            startedAtMs = 0L,
            connectionStats = { 0 to 0L },
            hookCommands = router,
            hookInbox = inbox,
        )
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }
    // ------------------------------------------------------------------ 心跳

    @Test
    fun appHeartbeatRecordsPresenceForThatSessionWithoutChangingThePhase() {
        // 先让 HOOK 也在同一会话上活着：一旦 APP 心跳错误地触发 prepareAnswer，
        // 监督端此时完全“就绪”，会真的取租约/隔离麦克风并下发 Answer。
        assertNull(dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_HOOK_HEARTBEAT)).error)

        val response = dispatcher.handle(appPeer, appRequest(RolePolicy.METHOD_SESSION_HEARTBEAT))

        assertNull(response.error)
        assertEquals("{\"acknowledged\":true}", response.payload)
        assertTrue("APP 心跳必须按 SessionId 记录存活", supervisor.peerAliveFor(session, PeerRole.APP))
        // 心跳只表达“APP 仍看着这通电话”，不得接听、不得改变监督阶段。
        assertTrue(supervisor.isReadyFor(session))
        assertEquals(SupervisorPhase.IDLE, supervisor.snapshot().phase)
        assertNull(supervisor.snapshot().ownedSession)
        assertTrue(calls.commands.isEmpty())
        assertEquals(0, audio.isolateCalls)
    }

    // ------------------------------------------------------------------ 命令

    @Test
    fun answerCommandRoundTripsThroughTheRealHookResult() {
        val pending = appCommandOnThread(commandPayload("answer"))

        awaitQueued(1)
        // HOOK 的下一次心跳取走命令，线格式与 Task 4 一致。
        val heartbeat = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_HOOK_HEARTBEAT))
        assertEquals("answer call-1\n", heartbeat.payload)

        // HOOK 回报真实结果：APP 的响应必须就是这条结果。
        assertNull(
            dispatcher.handle(
                hookPeer,
                hookRequest(RolePolicy.METHOD_CALL_COMMAND_RESULT, resultPayload("Answer", "accepted")),
            ).error,
        )

        val response = pending.get(5, TimeUnit.SECONDS)
        assertNull(response.error)
        assertEquals("{\"outcome\":\"accepted\"}", response.payload)
        // 命令只下发一次，不会在超时/结果之后重发。
        assertEquals("", router.drainCommandLines(session))
    }

    @Test
    fun hangupCommandCarriesTheRealRejectionBackToTheApp() {
        val pending = appCommandOnThread(commandPayload("hangup"))

        awaitQueued(1)
        val heartbeat = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_HOOK_HEARTBEAT))
        assertEquals("hangup call-1\n", heartbeat.payload)

        assertNull(
            dispatcher.handle(
                hookPeer,
                hookRequest(
                    RolePolicy.METHOD_CALL_COMMAND_RESULT,
                    resultPayload("Hangup", "rejected", "NOT_OWNED"),
                ),
            ).error,
        )

        val response = pending.get(5, TimeUnit.SECONDS)
        assertNull(response.error)
        assertEquals("{\"outcome\":\"rejected\",\"reason\":\"NOT_OWNED\"}", response.payload)
        // 监督端的 CallControl 没有被 bridge 直接调用：命令只能经 HOOK 执行。
        assertTrue(calls.commands.isEmpty())
    }
    @Test
    fun timeoutIsBoundedAndNeverFabricatesASuccess() {
        val startedAt = System.currentTimeMillis()
        val pending = appCommandOnThread(commandPayload("hangup"))
        awaitQueued(1)

        // 命令在等待结果时，另一条连接（HOOK 心跳）必须立刻被服务：4 s 等待不能占住分发器。
        val otherStartedAt = System.currentTimeMillis()
        val other = dispatcher.handle(
            hookPeer,
            hookRequest(RolePolicy.METHOD_HOOK_HEARTBEAT, sessionId = otherSession),
        )
        val otherElapsed = System.currentTimeMillis() - otherStartedAt
        assertNull(other.error)
        assertTrue("等待中的命令阻塞了其它连接: " + otherElapsed + " ms", otherElapsed < 1_000)

        val response = pending.get(8, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals("command_timeout", response.error?.code)
        // 上界来自 HookCommandCallControl.DEFAULT_TIMEOUT_MS = 4000 ms。
        assertTrue("超时过早: " + elapsed + " ms", elapsed >= 3_900)
        assertTrue("超时无界: " + elapsed + " ms", elapsed <= 6_500)
        // 没有结果就不能回 Accepted，也不能由 bridge 自己下发挂断。
        assertTrue(dispatcher.takeHookCommandResults().isEmpty())
        assertTrue(calls.commands.isEmpty())
        // 命令仍原样留在队列里等 HOOK 取走：超时不是“重发”或“执行”。
        assertEquals(1, router.pendingCount(session))
    }

    @Test
    fun malformedPayloadIsRejectedBeforeAnythingIsQueued() {
        val malformed = listOf(
            "",
            "not-json",
            "{}",
            "[]",
            "{\"command\":null}",
            "{\"command\":1}",
            "{\"command\":\"answer\",\"extra\":1}",
            "{\"cmd\":\"answer\"}",
        )
        for (payload in malformed) {
            val response = dispatcher.handle(
                appPeer,
                appRequest(RolePolicy.METHOD_SESSION_COMMAND, payload),
            )
            assertEquals("payload 必须整体拒绝: " + payload, "payload_invalid", response.error?.code)
            assertEquals("拒绝后不得入队: " + payload, 0, router.pendingCount(session))
        }
    }

    @Test
    fun unknownCommandIsRejectedAsUnsupportedWithoutQueueing() {
        // 结构合法但不在受限命令表里的动词：机器可读的 unsupported_command，
        // 而不是被当成 Answer/Hangup 或静默忽略。
        for (payload in listOf(
            "{\"command\":\"reboot\"}",
            "{\"command\":\"endCall\"}",
            "{\"command\":\"Answer\"}",
        )) {
            val response = dispatcher.handle(
                appPeer,
                appRequest(RolePolicy.METHOD_SESSION_COMMAND, payload),
            )
            assertEquals(payload, "unsupported_command", response.error?.code)
            assertEquals(payload, 0, router.pendingCount(session))
        }
    }

    @Test
    fun missingOrUnsafeSessionIdIsRejectedWithSessionRequired() {
        val missing = dispatcher.handle(
            appPeer,
            RpcRequest(id = "app-missing", method = RolePolicy.METHOD_SESSION_COMMAND, payload = commandPayload("answer")),
        )
        assertEquals("session_required", missing.error?.code)

        val unsafe = dispatcher.handle(
            appPeer,
            appRequest(
                RolePolicy.METHOD_SESSION_COMMAND,
                commandPayload("answer"),
                sessionId = SessionId("epoch-1", "bad id\nanswer other"),
            ),
        )
        assertEquals("session_required", unsafe.error?.code)

        val heartbeatMissing = dispatcher.handle(
            appPeer,
            RpcRequest(id = "app-hb", method = RolePolicy.METHOD_SESSION_HEARTBEAT),
        )
        assertEquals("session_required", heartbeatMissing.error?.code)
        assertEquals(0, router.pendingCount(session))
    }

    @Test
    fun saturatedQueueFailsBoundedWithoutBlockingOtherConnections() {
        repeat(HookCommandRouter.MAX_PENDING_PER_SESSION) {
            assertTrue(router.enqueueCommand(session, CallCommand.Answer))
        }

        val startedAt = System.currentTimeMillis()
        val response = dispatcher.handle(
            appPeer,
            appRequest(RolePolicy.METHOD_SESSION_COMMAND, commandPayload("answer")),
        )
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals("command_not_queued", response.error?.code)
        assertTrue("队列饱和必须立即失败: " + elapsed + " ms", elapsed < 1_000)
        // 另一条连接的普通请求不受影响。
        val otherStartedAt = System.currentTimeMillis()
        val heartbeat = dispatcher.handle(appPeer, appRequest(RolePolicy.METHOD_SESSION_HEARTBEAT))
        val otherElapsed = System.currentTimeMillis() - otherStartedAt
        assertNull(heartbeat.error)
        assertTrue(otherElapsed < 1_000)
    }

    @Test
    fun hookAndRootCannotUseTheAppSessionMethods() {
        for (peer in listOf(hookPeer, rootPeer)) {
            for (method in listOf(RolePolicy.METHOD_SESSION_HEARTBEAT, RolePolicy.METHOD_SESSION_COMMAND)) {
                assertFalse(
                    peer.role.name + " 不得调用 " + method,
                    RolePolicy.authorize(
                        peer,
                        RpcRequest(
                            id = "attempt",
                            method = method,
                            sessionId = session,
                            payload = commandPayload("hangup"),
                        ),
                    ),
                )
            }
        }
        assertFalse(RolePolicy.methodsFor(PeerRole.HOOK).contains(RolePolicy.METHOD_SESSION_COMMAND))
        assertFalse(RolePolicy.methodsFor(PeerRole.CLI_ROOT).contains(RolePolicy.METHOD_SESSION_HEARTBEAT))
    }

    // ------------------------------------------------------------------ 辅助

    private fun appRequest(
        method: String,
        payload: String = "",
        id: String = "app-" + method,
        sessionId: SessionId? = session,
    ): RpcRequest = RpcRequest(id = id, method = method, sessionId = sessionId, payload = payload)

    private fun hookRequest(
        method: String,
        payload: String = "",
        sessionId: SessionId = session,
    ): RpcRequest = RpcRequest(id = "hook-" + method, method = method, sessionId = sessionId, payload = payload)

    private fun commandPayload(token: String): String = "{\"command\":\"" + token + "\"}"

    private fun resultPayload(command: String, outcome: String, reason: String? = null): String =
        "{" +
            "\"processEpoch\":\"epoch-1\"," +
            "\"callId\":\"call-1\"," +
            "\"command\":\"" + command + "\"," +
            "\"source\":\"MODULE\"," +
            "\"outcome\":\"" + outcome + "\"," +
            "\"reason\":" + (if (reason == null) "null" else "\"" + reason + "\"") + "," +
            "\"code\":null," +
            "\"message\":null," +
            "\"note\":\"\"" +
            "}"

    private fun appCommandOnThread(payload: String): CompletableFuture<RpcResponse> {
        val future = CompletableFuture<RpcResponse>()
        Thread {
            try {
                future.complete(
                    dispatcher.handle(appPeer, appRequest(RolePolicy.METHOD_SESSION_COMMAND, payload)),
                )
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }.apply {
            isDaemon = true
            name = "agentcall-test-app-command"
            start()
        }
        return future
    }

    private fun awaitQueued(expected: Int) {
        val deadline = System.currentTimeMillis() + 2_000
        while (router.pendingCount(session) < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(expected, router.pendingCount(session))
    }

    private object NoPackages : PackageIdentitySource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = null
        override fun versionName(packageName: String): String? = null
        override fun uidForPackage(packageName: String): Int? = null
    }
}

private class FakeClock(var now: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = now
}

/** 记录是否被调用：心跳/命令不得触发任何音频动作。 */
private class RecordingAudio : SupervisedAudio {
    var isolateCalls = 0

    override val microphoneIsolated: Boolean get() = false

    override suspend fun isolateMicrophone(): Boolean {
        isolateCalls++
        return true
    }

    override suspend fun verifyIsolation(): Boolean = true

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession =
        error("APP session.command must not open audio")

    override suspend fun stopKeepingIsolation() = Unit

    override suspend fun restoreMicrophone(): Boolean = true
}

private class RecordingCalls : CallControl {
    val commands = mutableListOf<Pair<SessionId, CallCommand>>()

    override suspend fun send(id: SessionId, command: CallCommand): CommandResult {
        commands += id to command
        return CommandResult.Accepted
    }
}

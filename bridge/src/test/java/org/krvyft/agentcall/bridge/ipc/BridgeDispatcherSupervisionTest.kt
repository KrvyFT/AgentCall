package org.krvyft.agentcall.bridge.ipc

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * C1 控制面接线测试：生产分发器必须把 APP/HOOK 的真实请求接到监督端口，
 * 而不是继续返回 method_not_implemented 或只记日志。
 */
class BridgeDispatcherSupervisionTest {

    private lateinit var baseDir: File
    private lateinit var dispatcher: ControlDispatcher
    private val port = RecordingPort()

    private val session = SessionId("epoch-1", "call-a")
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

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-supervision-rpc-").toFile()
        val packages = object : PackageIdentitySource {
            override fun packagesForUid(uid: Int): List<String> = emptyList()
            override fun signatureSha256(packageName: String): String? = null
            override fun versionName(packageName: String): String? = null
            override fun uidForPackage(packageName: String): Int? = null
        }
        dispatcher = ControlDispatcher(
            store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L }),
            packages = packages,
            probe = DeviceProbe(
                packages = packages,
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
            supervision = port,
            startedAtMs = 0L,
            connectionStats = { 0 to 0L },
        )
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun request(peer: PeerIdentity, method: String, payload: String = ""): RpcRequest =
        RpcRequest(id = "req-" + method, method = method, sessionId = session, payload = payload)

    @Test
    fun sessionRequestArmsTheSupervisor() {
        val response = dispatcher.handle(appPeer, request(appPeer, RolePolicy.METHOD_SESSION_REQUEST))

        assertNull(response.error)
        assertTrue(response.payload.contains("\"accepted\""))
        assertEquals(1, port.prepared)
        assertEquals(session, port.lastPrepared)
    }

    @Test
    fun handoverGoesToTheSupervisor() {
        port.takeover = org.krvyft.agentcall.bridge.supervision.TakeoverOutcome.Confirmed

        val response = dispatcher.handle(appPeer, request(appPeer, RolePolicy.METHOD_HANDOVER))

        assertNull(response.error)
        assertTrue(response.payload.contains("\"confirmed\""))
        assertEquals(1, port.takeovers)
    }

    @Test
    fun snapshotStateDrivesActiveAndDisconnected() {
        assertNull(dispatcher.handle(hookPeer, request(hookPeer, RolePolicy.METHOD_HOOK_SNAPSHOT, snapshot("ACTIVE", null))).error)
        assertEquals(1, port.active)

        assertNull(dispatcher.handle(hookPeer, request(hookPeer, RolePolicy.METHOD_HOOK_SNAPSHOT, snapshot("DISCONNECTED", null))).error)
        assertEquals(1, port.disconnected)
    }

    @Test
    fun snapshotHandbackRequestsManualTakeover() {
        port.takeover = org.krvyft.agentcall.bridge.supervision.TakeoverOutcome.Confirmed

        assertNull(
            dispatcher.handle(
                hookPeer,
                request(hookPeer, RolePolicy.METHOD_HOOK_SNAPSHOT, snapshot("ACTIVE", "route_change")),
            ).error,
        )

        assertEquals(1, port.takeovers)
        assertEquals(0, port.active)
    }

    @Test
    fun hookPresenceIsRecordedWithoutASession() {
        // Task 11Q：空闲存在性没有 sessionId，不能走 session.heartbeat 路径。
        val response = dispatcher.handle(
            hookPeer,
            RpcRequest(id = "req-presence", method = RolePolicy.METHOD_HOOK_PRESENCE),
        )

        assertNull(response.error)
        assertEquals("", response.payload)
        assertEquals(listOf(PeerRole.HOOK), port.presences)
        assertTrue("presence 不是会话心跳", port.heartbeats.isEmpty())
    }

    @Test
    fun authenticatedRequestsFeedSessionHeartbeats() {
        assertNull(dispatcher.handle(hookPeer, request(hookPeer, RolePolicy.METHOD_HOOK_HEARTBEAT)).error)

        assertTrue(port.heartbeats.contains(session to PeerRole.HOOK))
    }

    @Test
    fun commandResultAlsoFeedsTheSupervisorInbox() {
        val payload = "{" +
            "\"processEpoch\":\"epoch-1\"," +
            "\"callId\":\"call-a\"," +
            "\"command\":\"Answer\"," +
            "\"source\":\"MODULE\"," +
            "\"outcome\":\"accepted\"," +
            "\"reason\":null,\"code\":null,\"message\":null,\"note\":\"\"" +
            "}"

        assertNull(dispatcher.handle(hookPeer, request(hookPeer, RolePolicy.METHOD_CALL_COMMAND_RESULT, payload)).error)

        assertEquals(session, port.lastResultSession)
        assertEquals(CallCommand.Answer, port.lastResultCommand)
        assertEquals(CommandResult.Accepted, port.lastResult)
    }

    private fun snapshot(state: String, handback: String?): String =
        "{" +
            "\"processEpoch\":\"epoch-1\"," +
            "\"callId\":\"call-a\"," +
            "\"state\":\"" + state + "\"," +
            "\"handbackReason\":" + (if (handback == null) "null" else "\"" + handback + "\"") +
            "}"

    private class RecordingPort : SupervisionRpcPort() {
        val heartbeats = mutableListOf<Pair<SessionId, PeerRole>>()
        val presences = mutableListOf<PeerRole>()

        override fun notePresence(role: PeerRole) {
            presences += role
        }

        var prepared = 0
        var lastPrepared: SessionId? = null
        var active = 0
        var disconnected = 0
        var takeovers = 0
        var takeover: org.krvyft.agentcall.bridge.supervision.TakeoverOutcome =
            org.krvyft.agentcall.bridge.supervision.TakeoverOutcome.NotOwned
        var lastResultSession: SessionId? = null
        var lastResultCommand: CallCommand? = null
        var lastResult: CommandResult? = null

        override fun heartbeat(id: SessionId, role: PeerRole) {
            heartbeats += id to role
        }

        override suspend fun prepareAnswer(id: SessionId): CommandResult {
            prepared++
            lastPrepared = id
            return CommandResult.Accepted
        }

        override suspend fun onCallActive(id: SessionId): CommandResult {
            active++
            return CommandResult.Accepted
        }

        override suspend fun onCallDisconnected(id: SessionId) {
            disconnected++
        }

        override suspend fun onManualTakeover(id: SessionId): org.krvyft.agentcall.bridge.supervision.TakeoverOutcome {
            takeovers++
            return takeover
        }

        override fun recordSnapshotState(id: SessionId, state: CallState?) = Unit

        override fun recordCommandResult(id: SessionId, command: CallCommand, result: CommandResult) {
            lastResultSession = id
            lastResultCommand = command
            lastResult = result
        }
    }
}

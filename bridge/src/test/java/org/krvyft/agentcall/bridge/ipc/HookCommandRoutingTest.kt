package org.krvyft.agentcall.bridge.ipc

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * HOOK 命令路由的 JVM 测试（模拟一个 HOOK 连接，不触达 LocalSocket）。
 *
 * 证明评审第 3 条要求的行为：
 * - `hook.snapshot`/`hook.heartbeat` 不再返回 method_not_implemented，而是带回待执行命令；
 * - 命令行格式与 app 端 `HookWireFormat` 完全一致（`answer <callId>\n` / `hangup <callId>\n`）；
 * - `call.command.result` 被接受并解析成真实 [CommandResult]，供 Task 7 的 CallControl.send 返回；
 * - 非法/伪造结果（会话不匹配、未知动作、越界队列）一律拒绝。
 */
class HookCommandRoutingTest {

    private lateinit var baseDir: File
    private lateinit var dispatcher: ControlDispatcher
    private val supervision = RecordingSupervision()

    private val session = SessionId(processEpoch = "epoch-1", callId = "call-1")
    private val hookPeer = PeerIdentity(
        uid = 10001,
        role = PeerRole.HOOK,
        packageName = "com.google.android.dialer",
        signatureSha256 = "00",
    )

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-hook-routing-").toFile()
        dispatcher = ControlDispatcher(
            store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L }),
            packages = object : PackageIdentitySource {
                override fun packagesForUid(uid: Int): List<String> = emptyList()
                override fun signatureSha256(packageName: String): String? = null
                override fun versionName(packageName: String): String? = null
                override fun uidForPackage(packageName: String): Int? = null
            },
            probe = DeviceProbe(
                packages = object : PackageIdentitySource {
                    override fun packagesForUid(uid: Int): List<String> = emptyList()
                    override fun signatureSha256(packageName: String): String? = null
                    override fun versionName(packageName: String): String? = null
                    override fun uidForPackage(packageName: String): Int? = null
                },
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
            supervision = supervision,
            startedAtMs = 0L,
            connectionStats = { 0 to 0L },
        )
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun hookRequest(method: String, payload: String = ""): RpcRequest =
        RpcRequest(id = "req-" + method, method = method, sessionId = session, payload = payload)

    @Test
    fun hookSnapshotReturnsQueuedCommandsInTheHookWireFormat() {
        assertTrue(dispatcher.enqueueHookCommand(session, CallCommand.Answer))

        val response = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_HOOK_SNAPSHOT, "{}"))

        assertNull(response.error)
        assertEquals("answer call-1\n", response.payload)
        // 取走一次后不再重复下发（每条命令最多执行一次）。
        val second = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_HOOK_SNAPSHOT, "{}"))
        assertEquals("", second.payload)
    }

    @Test
    fun hookHeartbeatAlsoCarriesCommandsAndIsNoLongerUnimplemented() {
        assertTrue(dispatcher.enqueueHookCommand(session, CallCommand.Hangup))

        val response = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_HOOK_HEARTBEAT))

        assertNull(response.error)
        assertEquals("hangup call-1\n", response.payload)
    }

    @Test
    fun commandResultIsParsedIntoARealCommandResult() {
        val payload = "{" +
            "\"processEpoch\":\"epoch-1\"," +
            "\"callId\":\"call-1\"," +
            "\"command\":\"Answer\"," +
            "\"source\":\"MODULE\"," +
            "\"outcome\":\"rejected\"," +
            "\"reason\":\"NOT_OWNED\"," +
            "\"code\":null," +
            "\"message\":null," +
            "\"note\":\"not_owned\"" +
            "}"

        val response = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_CALL_COMMAND_RESULT, payload))

        assertNull(response.error)
        // 结果只有一条生产通路：call.command.result → 监督端收件箱。
        val recorded = supervision.results.single()
        assertEquals(session, recorded.first)
        assertEquals(CallCommand.Answer, recorded.second)
        assertEquals(CommandResult.Rejected(RejectReason.NOT_OWNED), recorded.third)
        assertTrue("结果不得留在任何第二缓冲区", supervision.results.size == 1)
    }

    @Test
    fun failedCommandResultKeepsMachineReadableCode() {
        val payload = resultPayload("Hangup", "UNKNOWN", "failed", "null", "\"disconnect_failed\"", "\"SECURITY\"")
        assertNull(dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_CALL_COMMAND_RESULT, payload)).error)

        val result = supervision.results.single().third
        assertEquals(CommandResult.Failed("disconnect_failed", "SECURITY"), result)
    }

    @Test
    fun resultForAnotherSessionIsRejected() {
        val payload = "{" +
            "\"processEpoch\":\"epoch-1\"," +
            "\"callId\":\"call-other\"," +
            "\"command\":\"Answer\"," +
            "\"source\":\"MODULE\"," +
            "\"outcome\":\"accepted\"," +
            "\"reason\":null,\"code\":null,\"message\":null,\"note\":\"\"" +
            "}"

        val response = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_CALL_COMMAND_RESULT, payload))

        assertEquals("payload_invalid", response.error?.code)
        assertTrue(supervision.results.isEmpty())
    }

    @Test
    fun malformedResultIsRejectedWithoutClaimingSuccess() {
        for (payload in listOf("", "{}", "{\"outcome\":\"accepted\"}", "not-json")) {
            val response = dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_CALL_COMMAND_RESULT, payload))
            assertEquals("payload_invalid", response.error?.code)
        }
        assertTrue(supervision.results.isEmpty())
    }

    /**
     * I4：结果路由没有第二个 64 条缓冲区。旧实现的 HookCommandRouter.results 从不被生产
     * 消费，第 65 条结果起 recordResult 返回 false → payload_invalid 且监督端收不到结果。
     */
    @Test
    fun moreThanSixtyFourResultsKeepFlowingToTheSupervisor() {
        for (index in 1..70) {
            val payload = resultPayload("Answer", "MODULE", "accepted", "null", "null", "null")
            val response = dispatcher.handle(
                hookPeer,
                hookRequest(RolePolicy.METHOD_CALL_COMMAND_RESULT, payload),
            )
            assertNull("第 " + index + " 条结果必须被接受: " + response.error, response.error)
        }
        assertEquals(70, supervision.results.size)
        assertTrue(supervision.results.all { it.third == CommandResult.Accepted })
    }

    @Test
    fun pendingCommandsAreBoundedPerSession() {
        repeat(HookCommandRouter.MAX_PENDING_PER_SESSION) {
            assertTrue(dispatcher.enqueueHookCommand(session, CallCommand.Answer))
        }
        assertFalse(dispatcher.enqueueHookCommand(session, CallCommand.Answer))
    }

    @Test
    fun invalidSessionTokensAreRejectedBeforeAnyCommandLineIsProduced() {
        val malformed = SessionId(processEpoch = "epoch-1", callId = "bad id\nanswer other")
        assertFalse(dispatcher.enqueueHookCommand(malformed, CallCommand.Answer))
        assertEquals("", dispatcher.handle(hookPeer, hookRequest(RolePolicy.METHOD_HOOK_SNAPSHOT)).payload)
    }

    private class RecordingSupervision : SupervisionRpcPort() {
        val results = java.util.concurrent.CopyOnWriteArrayList<Triple<SessionId, CallCommand, CommandResult>>()

        override fun recordCommandResult(id: SessionId, command: CallCommand, result: CommandResult) {
            results += Triple(id, command, result)
        }
    }

    private fun resultPayload(
        command: String,
        source: String,
        outcome: String,
        reason: String,
        code: String,
        message: String,
    ): String = "{" +
        "\"processEpoch\":\"epoch-1\"," +
        "\"callId\":\"call-1\"," +
        "\"command\":\"" + command + "\"," +
        "\"source\":\"" + source + "\"," +
        "\"outcome\":\"" + outcome + "\"," +
        "\"reason\":" + reason + "," +
        "\"code\":" + code + "," +
        "\"message\":" + message + "," +
        "\"note\":\"\"" +
        "}"
}

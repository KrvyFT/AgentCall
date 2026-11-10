package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonParser
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
import org.krvyft.agentcall.bridge.BridgeShutdownPort
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.bridge.supervision.ShutdownResult
import org.krvyft.agentcall.bridge.supervision.TerminationOutcome
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * Task 11D2a：`shutdown` 响应必须携带优雅关闭的机器可读结果。
 *
 * 关闭路径无法确认断开时（隔离仍保留），响应必须明确给出 `shutdown_call_unresolved`，
 * 而不是旧的固定 `{"stopping":true}` 成功语义。
 */
class BridgeShutdownRpcTest {

    private lateinit var baseDir: File
    private lateinit var store: ConfigStore
    private val cliPeer = PeerIdentity(uid = 0, role = PeerRole.CLI_ROOT)

    private val packages = object : PackageIdentitySource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = "sig"
        override fun versionName(packageName: String): String? = "1.0"
        override fun uidForPackage(packageName: String): Int? = 10_001
    }

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-shutdown-rpc-").toFile()
        store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L })
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun dispatcher(latch: CountDownLatch, lifecycle: BridgeShutdownPort): ControlDispatcher =
        ControlDispatcher(
            store = store,
            packages = packages,
            probe = DeviceProbe(
                packages = packages,
                platform = object : PlatformFacts {
                    override fun apiLevel(): Int = 37
                    override fun romFingerprint(): String = "rom"
                    override fun kernelVersion(): String = "6.1.0"
                    override fun frameworkVersion(): String = "fw"
                    override fun audioPolicyDigest(): String = "digest"
                    override fun simCarrier(): String = "carrier"
                    override fun transport(): String = "VoLTE"
                    override fun route(): String = "SWB"
                },
                audioApiProbe = object : AudioApiProbe {
                    override fun visibleMethods(): Set<String> = emptySet()
                },
                clockMs = { 0L },
                bridgeVersion = "1.0",
            ),
            shutdown = latch,
            startedAtMs = 0L,
            lifecycle = lifecycle,
        )

    @Test
    fun shutdownResponseCarriesTheUnresolvedCodeAndKeepsIsolation() {
        val latch = CountDownLatch(1)
        val lifecycle = BridgeShutdownPort {
            ShutdownResult(
                ownedSession = SessionId("epoch-1", "call-a"),
                termination = TerminationOutcome.HOOK_ACCEPTED,
                ownershipResolved = false,
                disconnected = false,
                microphoneIsolated = true,
                code = "shutdown_call_unresolved",
            )
        }

        val response = dispatcher(latch, lifecycle).handle(
            cliPeer,
            RpcRequest(id = "req-shutdown", method = RolePolicy.METHOD_SHUTDOWN),
        )

        assertNull(response.error)
        val json = JsonParser.parseString(response.payload).asJsonObject
        assertEquals("关闭请求必须触发进程退出", 0, latch.count)
        assertFalse("未确认断开绝不能报干净关闭", json.get("clean").asBoolean)
        assertEquals("shutdown_call_unresolved", json.get("shutdownCode").asString)
        val supervision = json.getAsJsonObject("supervision")
        assertTrue(supervision.get("microphoneIsolated").asBoolean)
        assertEquals("HOOK_ACCEPTED", supervision.get("termination").asString)
    }

    @Test
    fun shutdownWithoutWiredLifecycleKeepsTheLegacyAckShape() {
        val latch = CountDownLatch(1)

        val response = ControlDispatcher(
            store = store,
            packages = packages,
            probe = DeviceProbe(
                packages = packages,
                platform = object : PlatformFacts {
                    override fun apiLevel(): Int = 37
                    override fun romFingerprint(): String = "rom"
                    override fun kernelVersion(): String = "6.1.0"
                    override fun frameworkVersion(): String = "fw"
                    override fun audioPolicyDigest(): String = "digest"
                    override fun simCarrier(): String = "carrier"
                    override fun transport(): String = "VoLTE"
                    override fun route(): String = "SWB"
                },
                audioApiProbe = object : AudioApiProbe {
                    override fun visibleMethods(): Set<String> = emptySet()
                },
                clockMs = { 0L },
                bridgeVersion = "1.0",
            ),
            shutdown = latch,
            startedAtMs = 0L,
        ).handle(cliPeer, RpcRequest(id = "req-shutdown", method = RolePolicy.METHOD_SHUTDOWN))

        assertNull(response.error)
        assertEquals("{\"stopping\":true}", response.payload)
        assertEquals(0, latch.count)
    }
}

package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DialerDefaultRole
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcStatus
import org.krvyft.agentcall.core.model.ConfigPatch
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.SecretUpdate
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * 阶段 B：status 必须携带两个真实字段（spec §11 概览）。
 *
 * - hookConnected 来自监督端按会话记录的 HOOK 心跳（5000 ms 窗口，SessionLimits）；
 * - dialerDefaultRole 来自特权进程的默认拨号器探测，无法可靠确定时线上值是字符串
 *   "unknown"（WebUI 只按真实布尔渲染，绝不伪造结论）；
 * - 两者都不得携带密钥或远程调用结果。
 */
class BridgeStatusLiveValuesTest {

    private lateinit var baseDir: File
    private lateinit var store: ConfigStore
    private val port = StatusPort()

    private val cliPeer = PeerIdentity(uid = 0, role = PeerRole.CLI_ROOT)

    private val packages = object : PackageIdentitySource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = null
        override fun versionName(packageName: String): String? = null
        override fun uidForPackage(packageName: String): Int? = null
    }

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-status-live-").toFile()
        store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L })
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun dispatcher(dialer: () -> DialerDefaultRole = { DialerDefaultRole.UNKNOWN }): ControlDispatcher =
        ControlDispatcher(
            store = store,
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
            dialerDefaultRole = dialer,
        )

    private fun statusJson(dialer: () -> DialerDefaultRole = { DialerDefaultRole.UNKNOWN }): JsonObject {
        val response = dispatcher(dialer).handle(
            cliPeer,
            RpcRequest(id = "req-status", method = RolePolicy.METHOD_STATUS),
        )
        assertNull(response.error)
        return JsonParser.parseString(response.payload).asJsonObject
    }

    @Test
    fun statusReportsExplicitUnknownWhenNoSourceCanDecide() {
        port.statusValue = null
        val json = statusJson()
        assertEquals("unknown", json.get("hookConnected").asString)
        assertEquals("unknown", json.get("dialerDefaultRole").asString)
    }

    @Test
    fun hookConnectedFollowsSupervisionHeartbeatPresence() {
        port.statusValue = status(hookConnected = true)
        assertEquals(true, statusJson().get("hookConnected").asBoolean)

        port.statusValue = status(hookConnected = false)
        assertEquals(false, statusJson().get("hookConnected").asBoolean)
    }

    @Test
    fun dialerDefaultRoleIsBooleanOnlyWhenDeterminable() {
        port.statusValue = status(hookConnected = true)

        assertEquals(true, statusJson { DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT }.get("dialerDefaultRole").asBoolean)
        assertEquals(false, statusJson { DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT }.get("dialerDefaultRole").asBoolean)
        assertEquals("unknown", statusJson { DialerDefaultRole.UNKNOWN }.get("dialerDefaultRole").asString)
    }

    @Test
    fun statusNeverCarriesSecretValues() {
        store.update(
            0L,
            ConfigPatch(secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace("sk-live-status-do-not-leak"))),
        )
        port.statusValue = status(hookConnected = true)

        val payload = statusJson().toString()

        assertFalse("status 不得包含密钥原值", payload.contains("sk-live-status-do-not-leak"))
        assertFalse("status 不得包含密钥段", payload.contains("secrets"))
    }

    private fun status(hookConnected: Boolean): SupervisionRpcStatus = SupervisionRpcStatus(
        phase = "IDLE",
        owned = false,
        microphoneIsolated = false,
        blockedReason = null,
        lastFault = null,
        hookConnected = hookConnected,
    )

    private class StatusPort : SupervisionRpcPort() {
        var statusValue: SupervisionRpcStatus? = null

        override fun status(): SupervisionRpcStatus? = statusValue
    }
}

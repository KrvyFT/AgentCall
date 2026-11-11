package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
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
import org.krvyft.agentcall.bridge.supervision.RolePresenceWindow
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcStatus
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * Task 11Q：hook.presence 必须真正驱动 status.hookConnected（5000 ms 窗口）。
 *
 * 设备证据：Hook 注入成功、观察到 RINGING，但 bridge 的 hookConnected=false，因为旧实现
 * 只在有存活会话时才记录 HOOK 心跳。空闲存在性必须让“Hook 活着”在 status 里可见，并在
 * 5 秒边界后重新变为 false。
 */
class HookPresenceStatusTest {

    private lateinit var baseDir: File
    private lateinit var dispatcher: ControlDispatcher
    private val clockMs = AtomicLong(1_000L)

    private val hookPeer = PeerIdentity(
        uid = 10_002,
        role = PeerRole.HOOK,
        packageName = "com.google.android.dialer",
        signatureSha256 = "00",
    )

    /**
     * status 只允许 APP/CLI_ROOT（RolePolicy 固定表）。测试直接调用 dispatcher 绕过
     * 角色鉴权，因此查询必须用允许的角色，否则一次 status 查询本身会被当成 HOOK 出现，
     * 无法验证“没有 presence 时必须为 false”。
     */
    private val cliPeer = PeerIdentity(
        uid = 0,
        role = PeerRole.CLI_ROOT,
        packageName = null,
        signatureSha256 = null,
    )

    private val packages = object : PackageIdentitySource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = null
        override fun versionName(packageName: String): String? = null
        override fun uidForPackage(packageName: String): Int? = null
    }

    private val presence = object : SupervisionRpcPort() {
        val window = RolePresenceWindow({ clockMs.get() })

        override fun notePresence(role: PeerRole) = window.note(role)

        override fun status(): SupervisionRpcStatus = SupervisionRpcStatus(
            phase = "IDLE",
            owned = false,
            microphoneIsolated = false,
            blockedReason = null,
            lastFault = null,
            hookConnected = window.isPresent(PeerRole.HOOK),
        )
    }

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-presence-status-").toFile()
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
            supervision = presence,
            startedAtMs = 0L,
            connectionStats = { 0 to 0L },
        )
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun hookPresenceFlipsAndExpiresHookConnectedAtTheFiveSecondBoundary() {
        assertEquals("没有 presence 时必须是确定的 false", false, hookConnected())

        val response = dispatcher.handle(
            hookPeer,
            RpcRequest(id = "req-presence", method = RolePolicy.METHOD_HOOK_PRESENCE),
        )
        assertNull(response.error)
        assertEquals(true, hookConnected())

        // 恰好 5000 ms 仍在窗口内（与 SessionLimits.HEARTBEAT_TIMEOUT_MS 一致）。
        clockMs.addAndGet(5_000L)
        assertEquals(true, hookConnected())

        clockMs.addAndGet(1L)
        assertEquals("超过 5 秒窗口后必须重新变为 absent", false, hookConnected())
    }

    @Test
    fun hookPresenceNeedsNoSessionAndIsRecordedAsRolePresence() {
        val response = dispatcher.handle(
            hookPeer,
            RpcRequest(id = "req-presence", method = RolePolicy.METHOD_HOOK_PRESENCE),
        )

        assertNull(response.error)
        assertEquals("", response.payload)
        assertTrue(presence.window.isPresent(PeerRole.HOOK))
    }

    private fun hookConnected(): Boolean {
        val response = dispatcher.handle(
            cliPeer,
            RpcRequest(id = "req-status", method = RolePolicy.METHOD_STATUS),
        )
        assertNull(response.error)
        val json = JsonParser.parseString(response.payload).asJsonObject
        return booleanField(json, "hookConnected")
    }

    private fun booleanField(json: JsonObject, name: String): Boolean {
        val element = json.get(name)
        assertTrue(name + " 必须序列化为 JSON 布尔：" + element, element is JsonPrimitive && element.isBoolean)
        return (element as JsonPrimitive).asBoolean
    }
}

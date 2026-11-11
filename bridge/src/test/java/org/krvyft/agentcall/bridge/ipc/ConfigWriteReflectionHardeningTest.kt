package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * Task 11P / Fix B：隐藏 API 反射路径的 Throwable 绝不能逃出 config-set。
 *
 * 真实 ART 的 MissingMetadataException 与 libphonenumber 的同名异常都可能以
 * Throwable（Exception 或 Error）形态出现。这里用 Error 模拟最坏情况：
 * 拨号器/包事实/音频探测的“source”抛 Throwable 时，事实必须变成显式 UNKNOWN，
 * 保存仍必须通过配置校验并写入；status 也不能因此失败。
 */
class ConfigWriteReflectionHardeningTest {

    /** 模拟反射被隐藏 API 策略拒绝时抛出的 Throwable。 */
    private class MissingMetadataException : Error("simulated hidden-api metadata failure")

    private val cliPeer = PeerIdentity(uid = 0, role = PeerRole.CLI_ROOT)
    private lateinit var baseDir: File
    private lateinit var store: ConfigStore

    /** 所有包事实查询都抛 Throwable：模拟 AppGlobals/RoleManager 反射被拒。 */
    private val throwingFacts = object : PackageFactsSource {
        override fun packagesForUid(uid: Int): List<String> = throw MissingMetadataException()
        override fun signatureSha256(packageName: String): String? = throw MissingMetadataException()
        override fun versionName(packageName: String): String? = throw MissingMetadataException()
        override fun uidForPackage(packageName: String): Int? = throw MissingMetadataException()
        override fun packageFacts(packageName: String): PackageFacts = throw MissingMetadataException()
        override fun dialerPackageFacts(): PackageFacts = throw MissingMetadataException()
    }

    private val throwingAudio = object : AudioApiProbe {
        override fun visibleMethods(): Set<String> = throw MissingMetadataException()
    }

    private val platform = object : PlatformFacts {
        override fun apiLevel(): Int = 37
        override fun romFingerprint(): String = "test"
        override fun kernelVersion(): String = "test"
        override fun frameworkVersion(): String = "test"
        override fun audioPolicyDigest(): String = "test"
    }

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-reflection-hardening-").toFile()
        store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L })
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun throwableReflectionSourcesBecomeUnknownInsteadOfAbortingTheProbe() {
        val report = DeviceProbe(
            packages = throwingFacts,
            platform = platform,
            audioApiProbe = throwingAudio,
            clockMs = { 0L },
            bridgeVersion = "1.0",
        ).inspect()

        // 事实必须是显式 UNKNOWN，而不是异常或伪造值。
        assertEquals(PlatformFacts.UNKNOWN, report.environment.dialerPackageVersion)
        assertTrue(report.notes.contains("dialerVersion=UNKNOWN"))
        assertTrue(report.notes.contains("dialerSignatureSha256=UNKNOWN"))
        assertTrue(report.notes.contains("hiddenApi=NONE"))
        assertEquals(
            org.krvyft.agentcall.core.model.CapabilityStatus.NOT_DETECTED,
            report.downlinkExtraction,
        )
    }

    @Test
    fun configWriteStillSucceedsAndStatusStaysUnknownWhenReflectionThrows() {
        val probe = DeviceProbe(
            packages = throwingFacts,
            platform = platform,
            audioApiProbe = throwingAudio,
            clockMs = { 0L },
            bridgeVersion = "1.0",
        )
        // 构造期不 probe；这里先走一次真实的环境采集，证明 Throwable 没有破坏配置前置路径。
        probe.inspect()

        val dispatcher = ControlDispatcher(
            store = store,
            packages = throwingFacts,
            probe = probe,
            shutdown = CountDownLatch(1),
            startedAtMs = 0L,
            connectionStats = { 0 to 0L },
            dialerDefaultRole = { throw MissingMetadataException() },
        )

        val write = dispatcher.handle(
            cliPeer,
            RpcRequest(
                id = "req-config-write",
                method = RolePolicy.METHOD_CONFIG_WRITE,
                payload = "{\"expectedRevision\":0,\"patch\":{\"enabled\":true}}",
            ),
        )
        assertNull("保存不得因反射 Throwable 变成 internal_error", write.error)
        val written = JsonParser.parseString(write.payload).asJsonObject
        assertEquals(1L, written.getAsJsonObject("config").get("revision").asLong)

        val status = dispatcher.handle(cliPeer, RpcRequest(id = "req-status", method = RolePolicy.METHOD_STATUS))
        assertNull("status 不得因反射 Throwable 失败", status.error)
        val statusJson: JsonObject = JsonParser.parseString(status.payload).asJsonObject
        assertEquals("unknown", statusJson.get("dialerDefaultRole").asString)
    }
}

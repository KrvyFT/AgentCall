package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.audio.AudioPcmLinkException
import org.krvyft.agentcall.bridge.audio.AudioPcmProtocol
import org.krvyft.agentcall.bridge.audio.AudioPcmRelay
import org.krvyft.agentcall.bridge.audio.FakeInputStream
import org.krvyft.agentcall.bridge.audio.FakeOutputStream
import org.krvyft.agentcall.bridge.audio.FrameCollector
import org.krvyft.agentcall.bridge.audio.PcmControl
import org.krvyft.agentcall.bridge.audio.PcmLaneCodec
import org.krvyft.agentcall.bridge.audio.PcmLaneFrame
import org.krvyft.agentcall.bridge.audio.PcmLaneType
import org.krvyft.agentcall.bridge.audio.PcmSessionOwner
import org.krvyft.agentcall.bridge.audio.PcmWorkerLink
import org.krvyft.agentcall.bridge.audio.PcmWorkerLinkFactory
import org.krvyft.agentcall.bridge.audio.awaitCondition
import org.krvyft.agentcall.bridge.audio.frameBytes
import org.krvyft.agentcall.bridge.audio.openOkBytes
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * APP 音频通道的控制面接线与 lane 隔离测试（Task 7A2）。
 *
 * - audio.channel 由控制分发器处理，返回 7B 需要的线格式（socket 名 + 格式）；
 * - 会话不属于监督端时 fail closed；
 * - PCM 中继的上行队列饱和时，控制面请求（心跳/诊断）必须仍在毫秒级被服务：
 *   PCM 连接跑在独立线程/独立 lane 上，绝不让电话 UI 或 shutdown 等流结束。
 */
class PcmRelayControlPlaneTest {

    private val format = AudioFormatSpec(sampleRate = 16_000)
    private val session = SessionId("epoch-1", "call-A")
    private val appPeer = PeerIdentity(
        uid = 10_001,
        role = PeerRole.APP,
        packageName = "org.krvyft.agentcall",
        signatureSha256 = "00",
    )

    private lateinit var baseDir: File
    private lateinit var owner: SimpleOwner
    private lateinit var links: SimpleLinkFactory
    private lateinit var relay: AudioPcmRelay
    private lateinit var dispatcher: ControlDispatcher

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-pcm-control-").toFile()
        owner = SimpleOwner()
        links = SimpleLinkFactory(format)
        relay = AudioPcmRelay(owner, links, FixedClock())
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
            supervision = SupervisionRpcPort(),
            startedAtMs = 0L,
            audioPcm = relay,
        )
    }

    @After
    fun tearDown() {
        relay.close()
        baseDir.deleteRecursively()
    }

    @Test
    fun audioChannelReturnsTheSocketAndFormatForABoundSession() {
        owner.owned += session
        val response = dispatcher.handle(appPeer, audioChannelRequest(session, formatPayload()))
        assertNull(response.error)

        val payload = JsonParser.parseString(response.payload).asJsonObject
        assertTrue(payload.get("opened").asBoolean)
        assertEquals(AudioPcmProtocol.SOCKET_NAME, payload.get("socket").asString)
        val formatJson = payload.getAsJsonObject("format")
        assertEquals(16_000, formatJson.get("sampleRate").asInt)
        assertEquals(1, formatJson.get("channels").asInt)
        assertEquals("PCM16", formatJson.get("encoding").asString)
    }

    @Test
    fun audioChannelFailsClosedForUnownedSessionsAndMalformedPayloads() {
        val unowned = dispatcher.handle(appPeer, audioChannelRequest(session, formatPayload()))
        assertEquals("audio_session_not_owned", unowned.error?.code)

        owner.owned += session
        for (payload in listOf(
            "",
            "not-json",
            "{}",
            "{\"format\":{}}",
            "{\"format\":{\"sampleRate\":16000,\"channels\":1,\"encoding\":\"PCM24\"}}",
            "{\"format\":{\"sampleRate\":16000,\"channels\":2,\"encoding\":\"PCM16\"}}",
            "{\"format\":{\"sampleRate\":0,\"channels\":1,\"encoding\":\"PCM16\"}}",
            "{\"format\":{\"sampleRate\":16000,\"channels\":1,\"encoding\":\"PCM16\"},\"extra\":1}",
        )) {
            val rejected = dispatcher.handle(appPeer, audioChannelRequest(session, payload))
            assertEquals("payload 必须整体拒绝: " + payload, "payload_invalid", rejected.error?.code)
        }

        val missingSession = dispatcher.handle(
            appPeer,
            RpcRequest(id = "app-audio", method = RolePolicy.METHOD_AUDIO_CHANNEL, payload = formatPayload()),
        )
        assertEquals("session_required", missingSession.error?.code)
        assertEquals(0, relay.boundSessionCount())
    }

    @Test
    fun audioChannelWithoutARelayStaysMethodNotImplemented() {
        val noRelay = ControlDispatcher(
            store = ConfigStore(File(baseDir, "agentcall2"), clockMs = { 0L }),
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
        )
        val response = noRelay.handle(appPeer, audioChannelRequest(session, formatPayload()))
        assertEquals("method_not_implemented", response.error?.code)
    }

    @Test
    fun controlLaneStillServesWhileThePcmRelayIsSaturated() {
        owner.owned += session
        val opened = dispatcher.handle(appPeer, audioChannelRequest(session, formatPayload()))
        assertNull(opened.error)

        val appInput = FakeInputStream()
        val appOutput = FakeOutputStream()
        val collector = FrameCollector(appOutput)
        val serveThread = Thread {
            relay.serve(
                appPeer,
                PcmLaneFrame(PcmLaneType.CONTROL, PcmLaneCodec.encodeOpen(session, format)),
                appInput,
                appOutput,
            ) { appInput.endInput() }
        }.apply {
            isDaemon = true
            name = "agentcall-test-pcm-saturated"
            start()
        }
        collector.await("open_ok") { frames ->
            frames.any { it.type == PcmLaneType.CONTROL && PcmLaneCodec.decodeControl(it.body) is PcmControl.OpenOk }
        }
        val workerLink = links.links.single()
        workerLink.toWorker.blockFromCurrentSize()

        repeat(AudioPcmRelay.UPLINK_QUEUE_CAPACITY + 6) { index ->
            appInput.feed(
                frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(index + 1, byteArrayOf(1, 2))!!),
            )
        }
        assertTrue("worker 写入必须已被阻塞", workerLink.toWorker.awaitBlocked(2_000))

        // PCM 饱和期间：控制面请求必须立即返回，不被流拖住。
        val heartbeatStartedAt = System.currentTimeMillis()
        val heartbeat = dispatcher.handle(
            appPeer,
            RpcRequest(
                id = "app-hb",
                method = RolePolicy.METHOD_SESSION_HEARTBEAT,
                sessionId = session,
                payload = "",
            ),
        )
        val heartbeatMs = System.currentTimeMillis() - heartbeatStartedAt
        assertNull(heartbeat.error)
        assertEquals("{\"acknowledged\":true}", heartbeat.payload)
        assertTrue("PCM 饱和阻塞了控制面心跳: " + heartbeatMs + " ms", heartbeatMs < 1_000)

        val diagnoseStartedAt = System.currentTimeMillis()
        val diagnosis = dispatcher.handle(
            appPeer,
            RpcRequest(id = "app-diag", method = RolePolicy.METHOD_DIAGNOSE, payload = ""),
        )
        val diagnoseMs = System.currentTimeMillis() - diagnoseStartedAt
        assertNull(diagnosis.error)
        assertTrue("PCM 饱和阻塞了控制面诊断: " + diagnoseMs + " ms", diagnoseMs < 1_000)

        // 队列饱和必须以机器可读错误有界失败。
        collector.await("saturation error") { frames ->
            frames.any { frame ->
                frame.type == PcmLaneType.CONTROL &&
                    (PcmLaneCodec.decodeControl(frame.body) as? PcmControl.Error)?.code ==
                    "audio_pcm_uplink_saturated"
            }
        }
        workerLink.toWorker.unblockWrites()
        serveThread.join(2_000)
        assertTrue(!serveThread.isAlive)
    }

    // ------------------------------------------------------------------ 辅助

    private fun audioChannelRequest(sessionId: SessionId?, payload: String): RpcRequest =
        RpcRequest(
            id = "app-audio",
            method = RolePolicy.METHOD_AUDIO_CHANNEL,
            sessionId = sessionId,
            payload = payload,
        )

    private fun formatPayload(): String =
        "{\"format\":{\"sampleRate\":16000,\"channels\":1,\"encoding\":\"PCM16\"}}"
}

private class FixedClock : MonotonicClock {
    override fun nowMs(): Long = 0L
}

private class SimpleOwner : PcmSessionOwner {
    val owned = CopyOnWriteArrayList<SessionId>()

    override fun owns(id: SessionId): Boolean = id in owned
}

private class SimpleLinkFactory(private val format: AudioFormatSpec) : PcmWorkerLinkFactory {

    val links = CopyOnWriteArrayList<SimpleLink>()

    override fun connect(): PcmWorkerLink {
        val link = SimpleLink(format)
        links += link
        return link
    }
}

private class SimpleLink(format: AudioFormatSpec) : PcmWorkerLink {

    val fromWorker = FakeInputStream()
    val toWorker = FakeOutputStream()

    init {
        fromWorker.feed(openOkBytes(format))
    }

    override val input: java.io.InputStream get() = fromWorker
    override val output: java.io.OutputStream get() = toWorker

    override fun setReadTimeoutMs(ms: Int) = Unit

    override fun close() {
        runCatching { fromWorker.endInput() }
    }
}

private object NoPackages : PackageIdentitySource {
    override fun packagesForUid(uid: Int): List<String> = emptyList()
    override fun signatureSha256(packageName: String): String? = null
    override fun versionName(packageName: String): String? = null
    override fun uidForPackage(packageName: String): Int? = null
}

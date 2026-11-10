package org.krvyft.agentcall.bridge.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession

/**
 * 音频工作进程侧 PCM 服务的测试（Task 7A2）。
 *
 * 这里跑的是工作进程的真实播放语义：上行帧必须经 [AudioSession.writeUplink] 注入，
 * 而该调用**播放结束才返回**（core AudioBridge 契约）。因此 ack 只在播放完成后出现，
 * 顺序与注入顺序一致；链路拆掉只结束本链路，绝不 stop 音频会话、绝不碰麦克风隔离。
 */
class AudioWorkerPcmServiceTest {

    private val format = AudioFormatSpec(sampleRate = 16_000)
    private val otherFormat = AudioFormatSpec(sampleRate = 48_000)
    private val session = SessionId("epoch-1", "call-A")

    private lateinit var opened: FakeAudioSession
    private lateinit var source: FakeAudioSessionSource
    private lateinit var lease: FakeLease
    private lateinit var service: AudioPcmSessionService
    private val sides = ArrayList<WorkerSide>()

    @Before
    fun setUp() {
        opened = FakeAudioSession(format)
        source = FakeAudioSessionSource()
        lease = FakeLease()
        service = AudioPcmSessionService(source, lease)
    }

    @After
    fun tearDown() {
        for (side in sides) {
            side.input.endInput()
            side.thread.join(1_000)
        }
        opened.chunks.close()
    }

    @Test
    fun handshakeRequiresAnArmedLease() {
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitControlError()
        assertEquals("audio_pcm_not_armed", side.lastErrorCode)
        side.awaitFinished()
        // 未取租约就绝不读下行/不上行。
        assertTrue(opened.writes.isEmpty())
    }

    @Test
    fun handshakeRequiresAnOpenSession() {
        lease.armed = session
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitControlError()
        assertEquals("audio_pcm_not_open", side.lastErrorCode)
        side.awaitFinished()
    }

    @Test
    fun handshakeFormatMustMatchTheOpenedSession() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(otherFormat)
        side.awaitControlError()
        assertEquals("audio_pcm_format_mismatch", side.lastErrorCode)
        side.awaitFinished()
    }

    @Test
    fun firstFrameMustBeAnOpenHandshake() {
        armAndOpen(format)
        val side = start()
        side.input.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(1, 2)))
        side.awaitControlError()
        assertEquals("audio_pcm_handshake_invalid", side.lastErrorCode)
        side.awaitFinished()
    }

    @Test
    fun downlinkChunksArePumpedAsBinaryPcmFrames() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()

        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6)
        opened.chunks.trySend(PcmChunk(first, format))
        opened.chunks.trySend(PcmChunk(second, format))

        side.collector.await("两个下行帧") { frames ->
            frames.count { it.type == PcmLaneType.DOWNLINK } >= 2
        }
        val downlink = side.collector.ofType(PcmLaneType.DOWNLINK)
        assertArrayEquals(first, downlink[0].body)
        assertArrayEquals(second, downlink[1].body)
    }

    @Test
    fun downlinkSlicesStayInsideTheFrameBodyLimit() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()

        // 32764 = 旧的 MAX_PCM_BYTES：作为单帧正文，帧长会是 32765 > 32764，被 framing 拒绝。
        val chunk = ByteArray(PcmLaneCodec.MAX_PCM_BYTES + 2) { (it % 251).toByte() }
        opened.chunks.trySend(PcmChunk(chunk, format))

        side.collector.await("限制内的下行切片") { frames ->
            frames.filter { it.type == PcmLaneType.DOWNLINK }.sumOf { it.body.size } >= chunk.size
        }
        val downlink = side.collector.ofType(PcmLaneType.DOWNLINK)
        assertTrue(
            "每个下行正文都必须满足 body+1 <= MAX_BODY_BYTES",
            downlink.all { it.body.size + 1 <= PcmLaneCodec.MAX_BODY_BYTES },
        )
        assertTrue(downlink.all { it.body.size <= PcmLaneCodec.MAX_PCM_BYTES })
        assertEquals(
            listOf(PcmLaneCodec.MAX_PCM_BYTES, chunk.size - PcmLaneCodec.MAX_PCM_BYTES),
            downlink.map { it.body.size },
        )
        assertArrayEquals(chunk, downlink.flatMap { it.body.toList() }.toByteArray())
    }

    @Test
    fun uplinkIsAckedOnlyAfterPlaybackHasCompleted() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()

        val playGate = CountDownLatch(1)
        opened.playGate = playGate
        val pcm = byteArrayOf(9, 9, 9, 9)
        side.input.feed(frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(7, pcm)!!))

        // 播放未完成：不得出现 ack（把“已写入”当成“已播放”会破坏回声保护契约）。
        Thread.sleep(200)
        assertTrue(side.collector.ofType(PcmLaneType.UPLINK_ACK).isEmpty())
        assertEquals(0, opened.writes.size)

        playGate.countDown()
        side.collector.await("播放完成后的 ack") { frames ->
            frames.any { it.type == PcmLaneType.UPLINK_ACK }
        }
        val ack = PcmLaneCodec.decodeAck(side.collector.ofType(PcmLaneType.UPLINK_ACK).single().body)
        assertEquals(PcmAck(7, true, null), ack)
        assertArrayEquals(pcm, opened.writes.single().data)
    }

    @Test
    fun uplinkFramesAreInjectedAndAckedInOrder() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()

        val playGate = CountDownLatch(1)
        opened.playGate = playGate
        side.input.feed(frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(1, byteArrayOf(1, 1))!!))
        side.input.feed(frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(2, byteArrayOf(2, 2))!!))

        Thread.sleep(150)
        assertTrue(side.collector.ofType(PcmLaneType.UPLINK_ACK).isEmpty())
        playGate.countDown()

        side.collector.await("两个按序 ack") { frames ->
            frames.count { it.type == PcmLaneType.UPLINK_ACK } >= 2
        }
        val acks = side.collector.ofType(PcmLaneType.UPLINK_ACK)
            .mapNotNull { PcmLaneCodec.decodeAck(it.body) }
        assertEquals(listOf(1, 2), acks.map { it.seq })
        assertEquals(listOf(1, 2), opened.writes.map { it.data[0].toInt() })
    }

    @Test
    fun playbackFailureIsAckedWithTheMachineCodeAndEndsThePump() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()
        opened.failureCode = "audio_playback_stalled"

        side.input.feed(frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(3, byteArrayOf(4, 4))!!))
        side.collector.await("失败 ack") { frames ->
            frames.any { it.type == PcmLaneType.UPLINK_ACK }
        }
        val ack = PcmLaneCodec.decodeAck(side.collector.ofType(PcmLaneType.UPLINK_ACK).single().body)
        assertNotNull(ack)
        assertEquals(3, ack!!.seq)
        assertFalse(ack.ok)
        assertEquals("audio_playback_stalled", ack.code)
        side.awaitFinished()
        // 失败不会 stop 会话，也不会碰隔离。
        assertEquals(0, opened.stopCalls)
    }

    @Test
    fun downlinkEndsWithAClosedFrameWhenTheSessionStops() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()

        opened.chunks.close()
        side.collector.await("结束帧") { frames ->
            frames.any { frame ->
                frame.type == PcmLaneType.CONTROL &&
                    PcmLaneCodec.decodeControl(frame.body) is PcmControl.Closed
            }
        }
        side.awaitFinished()
    }

    @Test
    fun linkTeardownNeverStopsTheAudioSession() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()

        side.input.endInput()
        side.awaitFinished()
        assertEquals(0, opened.stopCalls)
    }

    @Test
    fun malformedUplinkFrameIsRejectedWithAMachineCode() {
        armAndOpen(format)
        val side = start()
        side.feedOpenHandshake(format)
        side.awaitOpenOk()

        // 序号 + 奇数字节 PCM：非法 PCM16。
        side.input.feed(frameBytes(PcmLaneType.UPLINK, byteArrayOf(0, 0, 0, 1, 7)))
        side.awaitControlError()
        assertEquals("audio_pcm_frame_invalid", side.lastErrorCode)
        side.awaitFinished()
        assertTrue(opened.writes.isEmpty())
    }

    // ------------------------------------------------------------------ 辅助

    private fun armAndOpen(format: AudioFormatSpec) {
        lease.armed = session
        source.byId[session] = PcmOpenedAudio(opened, format)
    }

    private fun start(): WorkerSide {
        val side = WorkerSide()
        side.start()
        sides += side
        return side
    }

    private inner class WorkerSide {

        val input = FakeInputStream()
        val output = FakeOutputStream()
        val collector = FrameCollector(output)

        lateinit var thread: Thread

        @Volatile
        var lastErrorCode: String? = null

        fun start() {
            thread = Thread({
                service.serve(input, output, closeLink = { input.endInput() })
            }, "agentcall-test-worker-pcm").apply {
                isDaemon = true
                start()
            }
        }

        fun feedOpenHandshake(requestFormat: AudioFormatSpec) {
            input.feed(frameBytes(PcmLaneType.CONTROL, PcmLaneCodec.encodeOpen(session, requestFormat)))
        }

        fun awaitOpenOk() {
            collector.await("open_ok") { frames ->
                frames.any { frame ->
                    frame.type == PcmLaneType.CONTROL &&
                        PcmLaneCodec.decodeControl(frame.body) is PcmControl.OpenOk
                }
            }
        }

        fun awaitControlError() {
            collector.await("control error") { frames ->
                frames.mapNotNull { frame ->
                    if (frame.type != PcmLaneType.CONTROL) return@mapNotNull null
                    (PcmLaneCodec.decodeControl(frame.body) as? PcmControl.Error)?.code
                }.firstOrNull()?.let { code ->
                    lastErrorCode = code
                    true
                } ?: false
            }
        }

        fun awaitFinished() {
            thread.join(2_000)
            assertFalse("PCM 服务线程必须结束", thread.isAlive)
        }
    }
}

private class FakeLease(var armed: SessionId? = null) : AudioLeaseCheck {
    override fun isAuthorized(id: SessionId): Boolean = armed == id
}

private class FakeAudioSessionSource : PcmAudioSessionSource {
    val byId = java.util.concurrent.ConcurrentHashMap<SessionId, PcmOpenedAudio>()

    override fun opened(id: SessionId): PcmOpenedAudio? = byId[id]
}

private class FakeAudioSession(val format: AudioFormatSpec) : AudioSession {

    val chunks = Channel<PcmChunk>(64)
    override val downlink: Flow<PcmChunk> = chunks.receiveAsFlow()

    val writes = CopyOnWriteArrayList<PcmAudio>()

    @Volatile
    var playGate: CountDownLatch = CountDownLatch(0)

    @Volatile
    var failureCode: String? = null

    @Volatile
    var stopCalls: Int = 0

    override suspend fun writeUplink(audio: PcmAudio) {
        // 假播放头：只有测试放行（等价于播放完成）才返回。
        playGate.await(5, TimeUnit.SECONDS)
        failureCode?.let { code ->
            throw AudioUnavailableException(code, AudioFailureCode.UPLINK_FAILED, code)
        }
        writes += audio
    }

    override suspend fun stop() {
        stopCalls++
    }
}

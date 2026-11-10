package org.krvyft.agentcall.bridge.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CapabilityStatus
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId

/**
 * PCM16 单声道重采样器的行为测试。
 *
 * 只比较可听/可送的 PCM 样本值，不检查内部变量；覆盖同速率、上下采样、跨分片连续性、
 * 空输入、奇数字节和非法速率等边界。
 */
class ResamplerTest {

    private fun samplesOf(vararg values: Int): ShortArray = ShortArray(values.size) { values[it].toShort() }

    private fun ramp(count: Int, step: Int = 100): ShortArray = ShortArray(count) { (it * step).toShort() }

    @Test
    fun sameRateCopiesPcmBytes() {
        val input = ByteArray(64) { (it * 3).toByte() }
        val output = Resampler(16_000, 16_000).resample(input)

        assertArrayEquals(input, output)
    }

    @Test
    fun downSample48kTo16kKeepsEveryThirdSample() {
        val resampler = Resampler(48_000, 16_000)
        val output = resampler.resampleSamples(ramp(48))

        assertEquals(16, output.size)
        assertEquals(samplesOf(0, 300, 600, 900, 1200, 1500).toList(), output.take(6))
        assertEquals(4_500.toShort(), output[15])
    }

    @Test
    fun upSample16kTo48kHasThreeTimesSamples() {
        val resampler = Resampler(16_000, 48_000)
        val output = resampler.resampleSamples(ramp(48))

        // 流式算法不越过本分片最后一个样本，48 个输入产生 141 个输出（约 3 倍）。
        assertEquals(141, output.size)
        assertEquals(0.toShort(), output[0])
        assertEquals(33.toShort(), output[1])
        assertEquals(67.toShort(), output[2])
        assertEquals(100.toShort(), output[3])
    }

    @Test
    fun linearInterpolationUsesFractionalPosition() {
        val resampler = Resampler(8_000, 16_000)
        val output = resampler.resampleSamples(samplesOf(0, 300))

        assertEquals(2, output.size)
        assertEquals(0.toShort(), output[0])
        assertEquals(150.toShort(), output[1])
    }

    @Test
    fun streamingAcrossChunksKeepsSampleCountAndContinuity() {
        val resampler = Resampler(48_000, 16_000)
        val collected = ArrayList<Short>()
        var offset = 0
        while (offset < 48) {
            val size = minOf(7, 48 - offset)
            collected.addAll(resampler.resampleSamples(ramp(48).copyOfRange(offset, offset + size)).asList())
            offset += size
        }

        assertEquals(16, collected.size)
        assertEquals(samplesOf(0, 300, 600, 900, 1200, 1500).toList(), collected.take(6))
        assertEquals(4_500.toShort(), collected[15])
    }

    @Test
    fun constantSignalStaysConstantAcrossChunks() {
        val resampler = Resampler(48_000, 16_000)
        val constant = ShortArray(7) { 1_234 }
        var nonZero = 0
        repeat(8) {
            val chunk = resampler.resampleSamples(constant)
            assertTrue(chunk.all { it == 1_234.toShort() })
            nonZero += chunk.size
        }
        assertTrue(nonZero > 0)
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals(0, Resampler(48_000, 16_000).resampleSamples(ShortArray(0)).size)
        assertEquals(0, Resampler(48_000, 16_000).resample(ByteArray(0)).size)
    }

    @Test
    fun oddByteCountIsRejected() {
        try {
            Resampler(48_000, 16_000).resample(ByteArray(3))
            fail("PCM16 input with an odd byte count must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("even"))
        }
    }

    @Test
    fun invalidRatesAreRejected() {
        val invalid = listOf(0 to 16_000, 16_000 to 0, -8_000 to 16_000, 16_000 to 384_001, 100 to 16_000)
        for ((input, output) in invalid) {
            try {
                Resampler(input, output)
                fail("rate pair must be rejected: " + input + " -> " + output)
            } catch (expected: IllegalArgumentException) {
                // 预期。
            }
        }
    }

    @Test
    fun resetClearsStreamingPhase() {
        val resampler = Resampler(16_000, 48_000)
        resampler.resampleSamples(ramp(2))
        resampler.reset()

        val output = resampler.resampleSamples(ramp(3))
        assertEquals(0.toShort(), output[0])
        assertEquals(100.toShort(), output[3])
    }

    @Test
    fun pcm16BytesRoundTrip() {
        val resampler = Resampler(16_000, 16_000)
        val input = samplesOf(-32768, -1, 0, 1, 32767)
        val bytes = ByteArray(input.size * 2)
        for (i in input.indices) {
            bytes[2 * i] = (input[i].toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = ((input[i].toInt() shr 8) and 0xFF).toByte()
        }

        val decoded = resampler.resample(bytes)
        assertArrayEquals(bytes, decoded)
    }
}

/**
 * 能力分类测试（与 [ResamplerTest] 放在同一文件，遵守任务 5 的测试文件范围）。
 *
 * 分类只允许出现 未检测/接口可见/待对端确认/不支持；创建成功绝不直接变成“已验证”。
 */
class AudioPathPolicyTest {

    private val allMethods = setOf(
        AudioPathPolicy.DOWNLINK_METHOD,
        AudioPathPolicy.UPLINK_METHOD,
        AudioPathPolicy.INTERCEPTABLE_METHOD,
    )

    @Test
    fun missingMethodStaysNotDetected() {
        assertEquals(
            CapabilityStatus.NOT_DETECTED,
            AudioPathPolicy.classify(emptySet(), AudioPathPolicy.DOWNLINK_METHOD, interceptable = null, creationSucceeded = null),
        )
    }

    @Test
    fun visibleMethodWithoutCreationIsInterfaceVisible() {
        assertEquals(
            CapabilityStatus.INTERFACE_VISIBLE,
            AudioPathPolicy.classify(allMethods, AudioPathPolicy.DOWNLINK_METHOD, interceptable = true, creationSucceeded = null),
        )
    }

    @Test
    fun successfulObjectCreationIsOnlyPendingPeerConfirmation() {
        assertEquals(
            CapabilityStatus.PENDING_PEER_CONFIRMATION,
            AudioPathPolicy.classify(allMethods, AudioPathPolicy.DOWNLINK_METHOD, interceptable = true, creationSucceeded = true),
        )
    }

    @Test
    fun interceptableFalseIsUnsupported() {
        assertEquals(
            CapabilityStatus.UNSUPPORTED,
            AudioPathPolicy.classify(allMethods, AudioPathPolicy.DOWNLINK_METHOD, interceptable = false, creationSucceeded = null),
        )
    }

    @Test
    fun failedObjectCreationIsUnsupported() {
        assertEquals(
            CapabilityStatus.UNSUPPORTED,
            AudioPathPolicy.classify(allMethods, AudioPathPolicy.UPLINK_METHOD, interceptable = true, creationSucceeded = false),
        )
    }

    @Test
    fun classificationNeverReturnsVerified() {
        val statuses = listOf(null, true, false).flatMap { interceptable ->
            listOf(null, true, false).map { creation ->
                AudioPathPolicy.classify(allMethods, AudioPathPolicy.DOWNLINK_METHOD, interceptable, creation)
            }
        }
        assertFalse(statuses.contains(CapabilityStatus.VERIFIED))
    }
}

/**
 * FrameworkAudioBridge 的无设备测试：注入假的受限框架，验证“没有通话提取通路就明确
 * 不支持、绝不降级到环境麦克风”。
 */
class FrameworkAudioBridgeTest {

    private val session = SessionId("epoch-1", "call-a")
    private val format = AudioFormatSpec(16_000)

    private fun framework(
        visible: Set<String> = setOf(
            AudioPathPolicy.DOWNLINK_METHOD,
            AudioPathPolicy.UPLINK_METHOD,
            AudioPathPolicy.INTERCEPTABLE_METHOD,
        ),
        interceptable: Boolean? = true,
        downlink: CallAudioFramework.Downlink? = FakeDownlink(),
        uplink: CallAudioFramework.Uplink? = FakeUplink(),
    ): FakeFramework = FakeFramework(visible, interceptable, downlink, uplink)

    @Test
    fun missingRestrictedMethodsFailUnsupportedWithoutOpeningAnything() {
        val framework = framework(visible = emptySet(), downlink = null, uplink = null)
        val bridge = FrameworkAudioBridge(framework)

        val capability = bridge.probe()
        assertEquals(CapabilityStatus.NOT_DETECTED, capability.downlink)
        assertEquals(CapabilityStatus.NOT_DETECTED, capability.uplink)
        assertFalse(capability.canOpen)

        try {
            runBlocking { bridge.open(session, format) }
            fail("open must fail when the call extraction path is absent")
        } catch (expected: AudioUnavailableException) {
            assertEquals(AudioFailureCode.UNSUPPORTED, expected.failure)
        }
        assertEquals(0, framework.downlinkOpenCalls)
        assertEquals(0, framework.uplinkOpenCalls)
    }

    @Test
    fun interceptableFalseFailsUnsupported() {
        val framework = framework(interceptable = false, downlink = null, uplink = null)
        val bridge = FrameworkAudioBridge(framework)

        assertEquals(CapabilityStatus.UNSUPPORTED, bridge.probe().downlink)
        try {
            runBlocking { bridge.open(session, format) }
            fail("open must fail when PSTN interception is not interceptable")
        } catch (expected: AudioUnavailableException) {
            assertEquals(AudioFailureCode.UNSUPPORTED, expected.failure)
        }
        assertEquals(0, framework.downlinkOpenCalls)
    }

    @Test
    fun createdStreamsArePendingPeerConfirmationNotVerified() {
        val framework = framework()
        val bridge = FrameworkAudioBridge(framework)
        val capability = bridge.probe()

        assertEquals(CapabilityStatus.INTERFACE_VISIBLE, capability.downlink)
        assertEquals(CapabilityStatus.INTERFACE_VISIBLE, capability.uplink)
        assertTrue(capability.canOpen)
        assertFalse(capability.downlink == CapabilityStatus.VERIFIED)
    }

    @Test
    fun downlinkFlowCarriesExtractionAudioAndUplinkWritesInjection() = runBlocking {
        val downlink = FakeDownlink(sampleRate = 16_000, channels = 1)
        val uplink = FakeUplink(sampleRate = 16_000, channels = 1)
        val framework = framework(downlink = downlink, uplink = uplink)
        val bridge = FrameworkAudioBridge(framework, dispatcher = Dispatchers.IO)

        val audio = bridge.open(session, format)
        val chunk = withTimeout(2_000) { audio.downlink.first() }
        assertEquals(format, chunk.format)
        // FakeDownlink 产出 4 个 PCM16 样本 = 8 字节；分片契约是字节而不是帧。
        val expected = ByteArray(4 * 2)
        for (index in 0 until 4) {
            val sample = 1_000 + index
            expected[2 * index] = (sample and 0xFF).toByte()
            expected[2 * index + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        assertArrayEquals(expected, chunk.data)

        val uplinkSamples = samplesOf(1, 2, 3)
        val uplinkBytes = ByteArray(uplinkSamples.size * 2)
        for (i in uplinkSamples.indices) {
            uplinkBytes[2 * i] = (uplinkSamples[i].toInt() and 0xFF).toByte()
            uplinkBytes[2 * i + 1] = ((uplinkSamples[i].toInt() shr 8) and 0xFF).toByte()
        }
        audio.writeUplink(PcmAudio(uplinkBytes, format))
        assertArrayEquals(uplinkSamples, uplink.written.toShortArray())

        audio.stop()
        assertTrue(downlink.closed)
        assertTrue(uplink.closed)
    }

    @Test
    fun unsupportedInternalFormatIsRejectedBeforeOpeningAnything() = runBlocking {
        val uplink = FakeUplink()
        val framework = framework(uplink = uplink)
        val bridge = FrameworkAudioBridge(framework)

        try {
            bridge.open(session, AudioFormatSpec(sampleRate = 4_000))
            fail("sample rates outside the declared PCM16 range must be rejected")
        } catch (expected: AudioUnavailableException) {
            assertEquals("audio_format_invalid", expected.code)
            assertEquals(AudioFailureCode.UNSUPPORTED, expected.failure)
        }
        assertEquals(0, framework.downlinkOpenCalls)
        assertEquals(0, framework.uplinkOpenCalls)
        assertTrue(uplink.written.isEmpty())
    }

    @Test
    fun unusableDeviceStreamFormatIsRejectedAndHandlesAreClosed() = runBlocking {
        val downlink = FakeDownlink(sampleRate = 0, channels = 1)
        val uplink = FakeUplink()
        val bridge = FrameworkAudioBridge(framework(downlink = downlink, uplink = uplink))

        try {
            bridge.open(session, format)
            fail("a 0 Hz device stream must be rejected")
        } catch (expected: AudioUnavailableException) {
            assertEquals("audio_device_format_invalid", expected.code)
            assertEquals(AudioFailureCode.UNSUPPORTED, expected.failure)
        }
        assertTrue(downlink.closed)
        assertTrue(uplink.closed)
    }

    @Test
    fun stereoInjectionTrackReceivesDuplicatedMonoSamples() = runBlocking {
        val downlink = FakeDownlink(sampleRate = 16_000, channels = 1)
        val uplink = FakeUplink(sampleRate = 16_000, channels = 2)
        val bridge = FrameworkAudioBridge(framework(downlink = downlink, uplink = uplink))
        val audio = bridge.open(session, format)

        val mono = samplesOf(1, 2, 3)
        val bytes = ByteArray(mono.size * 2)
        for (index in mono.indices) {
            bytes[2 * index] = (mono[index].toInt() and 0xFF).toByte()
            bytes[2 * index + 1] = ((mono[index].toInt() shr 8) and 0xFF).toByte()
        }
        audio.writeUplink(PcmAudio(bytes, format))

        // 内部单声道样本必须按帧复制到立体声注入轨，而不是被当成交错帧。
        assertArrayEquals(shortArrayOf(1, 1, 2, 2, 3, 3), uplink.written.toShortArray())
        audio.stop()
    }

    /**
     * 播放完成契约（core AudioBridge.kt:27-38）：writeUplink 必须等到注入的音频**真正
     * 播放结束**才返回。假播放器把“写入”和“播放头前进”分开，证明实现等的是播放头，
     * 而不是 AudioTrack.write 已把数据塞进播放缓冲。
     */
    @Test
    fun writeUplinkReturnsOnlyAfterTheTrackReportsCompletedPlayback() = runBlocking {
        val downlink = FakeDownlink(sampleRate = 16_000, channels = 1)
        val uplink = FakeUplink(sampleRate = 16_000, channels = 1, completesPlaybackImmediately = false)
        val bridge = FrameworkAudioBridge(framework(downlink = downlink, uplink = uplink), playbackPollMs = 5)
        val audio = bridge.open(session, format)
        val samples = samplesOf(1, 2, 3)
        val job = async(Dispatchers.Default) {
            audio.writeUplink(PcmAudio(pcm16Bytes(samples), format))
        }
        try {
            delay(150)
            assertEquals("上行样本必须已经写入注入播放器", 3, uplink.writtenFrames)
            assertFalse(
                "播放头未到写入帧数前 writeUplink 不得返回，否则引擎的 300 ms 窗口覆盖不了回声",
                job.isCompleted,
            )

            uplink.headFrames = 3
            withTimeout(2_000) { job.await() }
            assertTrue(job.isCompleted)
        } finally {
            job.cancel()
            audio.stop()
        }
    }

    /** 取消必须在若干次轮询内返回，而不是等整段音频“播完”或一次阻塞写返回。 */
    @Test
    fun writeUplinkCancellationStopsThePlaybackWait() = runBlocking {
        val uplink = FakeUplink(sampleRate = 16_000, channels = 1, completesPlaybackImmediately = false)
        val bridge = FrameworkAudioBridge(framework(uplink = uplink), playbackPollMs = 5)
        val audio = bridge.open(session, format)
        val job = launch(Dispatchers.Default) {
            audio.writeUplink(PcmAudio(pcm16Bytes(samplesOf(1, 2, 3)), format))
        }
        try {
            delay(150)
            assertFalse("播放尚未完成时写入必须保持挂起", job.isCompleted)

            val startedAt = System.nanoTime()
            withTimeout(2_000) { job.cancelAndJoin() }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(job.isCancelled)
            assertTrue("取消后必须在一个轮询周期内返回（实际 " + elapsedMs + " ms）", elapsedMs < 1_000)
        } finally {
            job.cancel()
            audio.stop()
        }
    }

    /** 暂停/被外部停止时播放头不会前进：不能永远挂起，也不能当成“播放完成”。 */
    @Test
    fun pausedOrStoppedPlaybackIsNeverTreatedAsCompleted() = runBlocking {
        val uplink = FakeUplink(sampleRate = 16_000, channels = 1, completesPlaybackImmediately = false)
            .apply { playing = false }
        val bridge = FrameworkAudioBridge(
            framework(uplink = uplink),
            playbackPollMs = 5,
            playbackStallTimeoutMs = 100,
        )
        val audio = bridge.open(session, format)
        try {
            audio.writeUplink(PcmAudio(pcm16Bytes(samplesOf(1, 2, 3)), format))
            fail("暂停/停止的播放器不得被当成“已播放完成”")
        } catch (expected: AudioUnavailableException) {
            assertEquals("audio_playback_stalled", expected.code)
            assertEquals(AudioFailureCode.STOPPED, expected.failure)
        } finally {
            audio.stop()
        }
    }

    /** 播放头必须追平本次写入的**全部**帧（分片写入也一样），少一帧都不算播放完成。 */
    @Test
    fun writeUplinkWaitsForEveryWrittenFrame() = runBlocking {
        val uplink = FakeUplink(sampleRate = 16_000, channels = 1, completesPlaybackImmediately = false)
        val bridge = FrameworkAudioBridge(framework(uplink = uplink), playbackPollMs = 5)
        val audio = bridge.open(session, format)
        val frames = ShortArray(1_000) { 1 }
        val job = async(Dispatchers.Default) {
            audio.writeUplink(PcmAudio(pcm16Bytes(frames), format))
        }
        try {
            delay(100)
            assertEquals("全部帧必须都已写入注入播放器", 1_000, uplink.writtenFrames)
            assertFalse(job.isCompleted)

            uplink.headFrames = 999
            delay(50)
            assertFalse("少一帧都不算播放完成", job.isCompleted)

            uplink.headFrames = 1_000
            withTimeout(2_000) { job.await() }
            assertTrue(job.isCompleted)
        } finally {
            job.cancel()
            audio.stop()
        }
    }

    /** flush/stop 把播放头归零：排队音频已被丢弃，必须明确失败而不是当成“播完”。 */
    @Test
    fun flushedPlaybackIsNeverTreatedAsCompleted() = runBlocking {
        val uplink = FakeUplink(sampleRate = 16_000, channels = 1, completesPlaybackImmediately = false)
        val bridge = FrameworkAudioBridge(framework(uplink = uplink), playbackPollMs = 5)
        val audio = bridge.open(session, format)
        val job = async(Dispatchers.Default) {
            try {
                audio.writeUplink(PcmAudio(pcm16Bytes(samplesOf(1, 2, 3)), format))
                null
            } catch (error: AudioUnavailableException) {
                error
            }
        }
        try {
            delay(100)
            assertFalse(job.isCompleted)
            uplink.headFrames = 1
            delay(100)
            uplink.headFrames = 0
            val failure = withTimeout(2_000) { job.await() }
            assertEquals("audio_playback_flushed", failure?.code)
            assertEquals(AudioFailureCode.STOPPED, failure?.failure)
        } finally {
            job.cancel()
            audio.stop()
        }
    }

    /** 会话停止必须打断播放等待，而不是让它卡在“播放头不再前进”。 */
    @Test
    fun stopDuringPlaybackInterruptsTheWait() = runBlocking {
        val uplink = FakeUplink(sampleRate = 16_000, channels = 1, completesPlaybackImmediately = false)
        val bridge = FrameworkAudioBridge(framework(uplink = uplink), playbackPollMs = 5)
        val audio = bridge.open(session, format)
        val job = async(Dispatchers.Default) {
            try {
                audio.writeUplink(PcmAudio(pcm16Bytes(samplesOf(1, 2, 3)), format))
                null
            } catch (error: AudioUnavailableException) {
                error
            }
        }
        try {
            delay(100)
            assertFalse(job.isCompleted)
            audio.stop()
            val failure = withTimeout(2_000) { job.await() }
            assertEquals("audio_stopped", failure?.code)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun stopIsIdempotentAndNeverRestoresMicrophone() = runBlocking {
        val downlink = FakeDownlink()
        val uplink = FakeUplink()
        val bridge = FrameworkAudioBridge(framework(downlink = downlink, uplink = uplink), dispatcher = Dispatchers.IO)
        val audio = bridge.open(session, format)

        audio.stop()
        audio.stop()
        assertTrue(downlink.closed)
        assertTrue(uplink.closed)
    }

    private fun samplesOf(vararg values: Int): ShortArray = ShortArray(values.size) { values[it].toShort() }

    private fun pcm16Bytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            bytes[2 * index] = (samples[index].toInt() and 0xFF).toByte()
            bytes[2 * index + 1] = ((samples[index].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private class FakeFramework(
        private val visible: Set<String>,
        private val interceptable: Boolean?,
        private val downlink: CallAudioFramework.Downlink?,
        private val uplink: CallAudioFramework.Uplink?,
    ) : CallAudioFramework {
        var downlinkOpenCalls = 0
        var uplinkOpenCalls = 0

        override fun visibleMethods(): Set<String> = visible

        override fun isInterceptable(): Boolean? = interceptable

        override fun openCallExtractionDownlinkRecord(): CallAudioFramework.Downlink? {
            downlinkOpenCalls++
            return downlink
        }

        override fun openCallUplinkInjectionTrack(): CallAudioFramework.Uplink? {
            uplinkOpenCalls++
            return uplink
        }
    }

    private class FakeDownlink(
        override val sampleRate: Int = 16_000,
        override val channels: Int = 1,
    ) : CallAudioFramework.Downlink {
        var closed = false
        private var served = false

        override fun read(samples: ShortArray, offset: Int, count: Int): Int {
            if (served) return 0
            served = true
            val n = minOf(count, 4)
            for (i in 0 until n) samples[offset + i] = (1_000 + i).toShort()
            return n
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * 假注入播放器：把“已写入”与“已播放”分开建模（真实 AudioTrack 的
     * getPlaybackHeadPosition 语义）。[completesPlaybackImmediately] 为真时写入即视为
     * 播放完成（既有用例的快速路径）；为假时必须由测试手动推进 [headFrames]。
     */
    private class FakeUplink(
        override val sampleRate: Int = 16_000,
        override val channels: Int = 1,
        private val completesPlaybackImmediately: Boolean = true,
    ) : CallAudioFramework.Uplink {
        var closed = false
        val written = ArrayList<Short>()

        @Volatile
        var playing: Boolean = true

        @Volatile
        var headFrames: Int = 0

        @Volatile
        var writtenFrames: Int = 0

        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            for (i in 0 until count) written.add(samples[offset + i])
            writtenFrames += count / channels
            if (completesPlaybackImmediately) headFrames = writtenFrames
            return count
        }

        override fun playedFrames(): Int = headFrames

        override fun isPlaying(): Boolean = playing

        override fun close() {
            closed = true
        }
    }
}

package org.krvyft.agentcall.service

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession

/**
 * 音频采样率适配（Task 7B2）：TTS 供应商通常返回 24 kHz，PCM lane 固定 16 kHz 单声道。
 *
 * 适配器必须在写入上行**之前**重采样；直接写入不同采样率会被 bridge 以
 * `audio_pcm_format_mismatch` 拒绝，而按错误采样率解释音频会变成“花栗鼠”音。
 * 因此这里的测试同时验证采样点数、格式、音高保持与抗混叠衰减。
 */
class AudioRateAdapterTest {

    private val lane = AudioFormatSpec(sampleRate = 16_000)

    // ---------------------------------------------------------------- 纯重采样

    @Test
    fun providerRateIsResampledToTheLaneFormat() {
        val input = tone(hertz = 440.0, sampleRate = 24_000, seconds = 1.0)
        val output = AudioRateAdapter.resample(input, lane)

        assertEquals(16_000, output.format.sampleRate)
        assertEquals(1, output.format.channels)
        assertEquals(0, output.data.size % 2)
        val expectedFrames = (input.data.size / 2).toLong() * 16_000L / 24_000L
        assertTrue(
            "frames=" + (output.data.size / 2) + " expected≈" + expectedFrames,
            abs((output.data.size / 2) - expectedFrames) <= 2L,
        )
    }

    @Test
    fun pitchIsPreservedSoPlaybackIsNotChipmunked() {
        val input = tone(hertz = 1_000.0, sampleRate = 24_000, seconds = 0.5)
        val output = AudioRateAdapter.resample(input, lane)
        val measured = frequencyOf(output)
        assertTrue("measured=" + measured, abs(measured - 1_000.0) < 30.0)
    }

    @Test
    fun contentAboveTheTargetNyquistIsAttenuated() {
        val passband = AudioRateAdapter.resample(tone(1_000.0, 24_000, 0.5), lane)
        val stopband = AudioRateAdapter.resample(tone(10_000.0, 24_000, 0.5), lane)
        val ratio = rms(stopband) / rms(passband)
        assertTrue("aliasing ratio=" + ratio, ratio < 0.15)
    }

    @Test
    fun cdrRateIsAlsoResampled() {
        val input = tone(hertz = 1_000.0, sampleRate = 44_100, seconds = 0.5)
        val output = AudioRateAdapter.resample(input, lane)
        assertEquals(16_000, output.format.sampleRate)
        assertTrue(abs(frequencyOf(output) - 1_000.0) < 40.0)
    }

    @Test
    fun upsamplesFromEightKiloHertz() {
        val input = tone(hertz = 500.0, sampleRate = 8_000, seconds = 0.5)
        val output = AudioRateAdapter.resample(input, lane)
        val expectedFrames = (input.data.size / 2).toLong() * 2L
        assertTrue(abs((output.data.size / 2) - expectedFrames) <= 2L)
        assertTrue(abs(frequencyOf(output) - 500.0) < 20.0)
    }

    @Test
    fun sameRateIsAPassthroughCopyWithTheTargetFormat() {
        val input = tone(hertz = 1_000.0, sampleRate = 16_000, seconds = 0.25)
        val output = AudioRateAdapter.resample(input, lane)
        assertArrayEquals(input.data, output.data)
        assertEquals(lane, output.format)
    }

    @Test
    fun emptyInputStaysEmpty() {
        val output = AudioRateAdapter.resample(PcmAudio(ByteArray(0), AudioFormatSpec(24_000)), lane)
        assertEquals(0, output.data.size)
        assertEquals(lane, output.format)
    }

    // ---------------------------------------------------------------- 未知/非法格式 fail closed

    @Test
    fun belowTheSupportedRateFailsClosed() {
        val failure = assertThrows(AudioFormatException::class.java) {
            AudioRateAdapter.resample(PcmAudio(ByteArray(64), AudioFormatSpec(sampleRate = 4_000)), lane)
        }
        assertEquals(AudioRateAdapter.ERROR_FORMAT_UNSUPPORTED, failure.code)
    }

    @Test
    fun aboveTheSupportedRateFailsClosed() {
        val failure = assertThrows(AudioFormatException::class.java) {
            AudioRateAdapter.resample(PcmAudio(ByteArray(64), AudioFormatSpec(sampleRate = 384_000)), lane)
        }
        assertEquals(AudioRateAdapter.ERROR_FORMAT_UNSUPPORTED, failure.code)
    }

    @Test
    fun anUnsupportedTargetFormatFailsClosed() {
        val failure = assertThrows(AudioFormatException::class.java) {
            AudioRateAdapter.resample(
                PcmAudio(ByteArray(64), AudioFormatSpec(24_000)),
                AudioFormatSpec(sampleRate = 2_000),
            )
        }
        assertEquals(AudioRateAdapter.ERROR_FORMAT_UNSUPPORTED, failure.code)
    }

    @Test
    fun unalignedPcmFailsClosed() {
        val failure = assertThrows(AudioFormatException::class.java) {
            AudioRateAdapter.resample(PcmAudio(ByteArray(3), AudioFormatSpec(24_000)), lane)
        }
        assertEquals(AudioRateAdapter.ERROR_NOT_ALIGNED, failure.code)
    }

    // ---------------------------------------------------------------- 会话包装

    @Test
    fun sessionResamplesTtsOutputBeforeWritingUplink() = runBlocking {
        val delegate = RecordingAudioSession()
        val session = ResamplingAudioSession(delegate, lane)
        val input = tone(hertz = 1_000.0, sampleRate = 24_000, seconds = 0.5)

        session.writeUplink(input)

        val written = delegate.written
        assertNotNull(written)
        assertEquals(lane, written!!.format)
        assertTrue(abs(frequencyOf(written) - 1_000.0) < 30.0)
        val expectedFrames = (input.data.size / 2).toLong() * 2L / 3L
        assertTrue(abs((written.data.size / 2) - expectedFrames) <= 2L)
    }

    @Test
    fun sessionNeverWritesUplinkForAnUnsupportedFormat() = runBlocking {
        val delegate = RecordingAudioSession()
        val session = ResamplingAudioSession(delegate, lane)
        assertThrows(AudioFormatException::class.java) {
            runBlocking {
                session.writeUplink(PcmAudio(ByteArray(64), AudioFormatSpec(sampleRate = 4_000)))
            }
        }
        assertNull(delegate.written)
    }

    @Test
    fun sessionForwardsDownlinkAndStopUnchanged() = runBlocking {
        val chunk = PcmChunk(ByteArray(8) { 0x01 }, lane)
        val delegate = RecordingAudioSession(downlinkChunks = listOf(chunk))
        val session = ResamplingAudioSession(delegate, lane)
        assertEquals(listOf(chunk), session.downlink.toList())
        session.stop()
        assertEquals(1, delegate.stopCalls)
    }

    @Test
    fun bridgeWrapsEveryOpenedSessionAtTheLaneFormat() = runBlocking {
        val recording = RecordingAudioBridge()
        val bridge = ResamplingAudioBridge(recording)
        val id = SessionId("epoch", "call")
        val session = bridge.open(id, lane)
        assertEquals(listOf(Opened(id, lane)), recording.opened)
        val delegate = (session as ResamplingAudioSession).delegate as InspectableAudioSession
        assertEquals(lane, delegate.laneFormat)
        assertSame(id, delegate.openedSession)
    }

    // ---------------------------------------------------------------- helpers

    private fun tone(hertz: Double, sampleRate: Int, seconds: Double): PcmAudio {
        val frames = (sampleRate * seconds).roundToInt()
        val bytes = ByteArray(frames * 2)
        val amplitude = 12_000.0
        for (index in 0 until frames) {
            val value = (amplitude * sin(2.0 * PI * hertz * index / sampleRate)).roundToInt().toShort()
            bytes[2 * index] = (value.toInt() and 0xFF).toByte()
            bytes[2 * index + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return PcmAudio(bytes, AudioFormatSpec(sampleRate = sampleRate))
    }

    private fun frequencyOf(audio: PcmAudio): Double {
        val frames = audio.data.size / 2
        if (frames < 4) return 0.0
        var crossings = 0
        var previous = sampleAt(audio, 0)
        for (index in 1 until frames) {
            val current = sampleAt(audio, index)
            if (previous <= 0 && current > 0) crossings += 1
            previous = current
        }
        // 去掉首尾半个周期的影响：用“正穿越数 / 名义时长”估计频率。
        val duration = frames.toDouble() / audio.format.sampleRate
        return crossings / duration
    }

    private fun sampleAt(audio: PcmAudio, index: Int): Int {
        val low = audio.data[2 * index].toInt() and 0xFF
        val high = audio.data[2 * index + 1].toInt() and 0xFF
        return (low or (high shl 8)).toShort().toInt()
    }

    private fun rms(audio: PcmAudio): Double {
        val frames = audio.data.size / 2
        if (frames == 0) return 0.0
        var sum = 0.0
        // 跳过首尾 10% 边缘（滤波器启动/收尾），只比较稳定段。
        val from = frames / 10
        val to = frames - frames / 10
        var count = 0
        for (index in from until to) {
            val value = sampleAt(audio, index).toDouble() / Short.MAX_VALUE
            sum += value * value
            count += 1
        }
        if (count == 0) return 0.0
        return sqrt(sum / count)
    }

    private class RecordingAudioSession(
        downlinkChunks: List<PcmChunk> = emptyList(),
    ) : AudioSession {
        override val downlink: Flow<PcmChunk> = flowOf(*downlinkChunks.toTypedArray())
        var written: PcmAudio? = null
        var stopCalls: Int = 0

        override suspend fun writeUplink(audio: PcmAudio) {
            written = audio
        }

        override suspend fun stop() {
            stopCalls += 1
        }
    }

    private class RecordingAudioBridge : AudioBridge {
        val opened = ArrayList<Opened>()

        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
            val delegate = InspectableAudioSession(id, format)
            opened += Opened(id, format)
            return delegate
        }
    }

    private data class Opened(val id: SessionId, val format: AudioFormatSpec)

    private class InspectableAudioSession(
        val openedSession: SessionId,
        val laneFormat: AudioFormatSpec,
    ) : AudioSession {
        override val downlink: Flow<PcmChunk> = flowOf()
        override suspend fun writeUplink(audio: PcmAudio) = Unit
        override suspend fun stop() = Unit
    }
}

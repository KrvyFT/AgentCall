package org.krvyft.agentcall.ai

import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk

/**
 * 基于能量的端侧 VAD 分句器（spec §10：单段 15 秒、静音 800 ms）。
 *
 * 输入是通话下行 PCM16 分片；输出是完整分句，供 ASR 使用。分句在以下任一条件成立时结束：
 * - 已检测到语音后静音累计达到 [silenceMs]（800 ms）；
 * - 段长达到 [maxSegmentMs]（15 s 硬上限）。
 *
 * 只有一次语音持续够 [minSpeechMs] 才会上交，短暂点击/回声残片被丢弃；[flush]/[reset]
 * 用于通话结束或播放结束后的缓冲清理。所有状态由实例锁保护：收集器线程 accept、回合线程
 * reset 可以并发调用。
 */
class VoiceActivityDetector(
    private val silenceMs: Int = DEFAULT_SILENCE_MS,
    private val maxSegmentMs: Int = DEFAULT_MAX_SEGMENT_MS,
    private val minSpeechMs: Int = DEFAULT_MIN_SPEECH_MS,
    private val rmsThreshold: Double = DEFAULT_RMS_THRESHOLD,
) {
    private val buffer = java.io.ByteArrayOutputStream(16 * 1024)
    private var segmentFormat: AudioFormatSpec? = null
    private var segmentFrames = 0L
    private var speechFrames = 0L
    private var silenceFrames = 0L

    /**
     * 送入一个下行分片，返回本片结束时已经完整的分句（通常为空）。
     *
     * 分片必须与段内格式一致；不一致时丢弃当前段并重新开始，避免把两种采样率拼进同一段。
     */
    @Synchronized
    fun accept(chunk: PcmChunk): List<PcmAudio> {
        val frames = chunk.data.size / 2
        if (frames <= 0) return emptyList()
        val active = segmentFormat
        if (active != null && active.sampleRate != chunk.format.sampleRate) {
            clear()
        }
        val speaking = rms(chunk.data, frames) >= rmsThreshold
        if (segmentFormat == null) {
            if (!speaking) return emptyList()
            segmentFormat = chunk.format
        }
        buffer.write(chunk.data, 0, frames * 2)
        segmentFrames += frames
        if (speaking) {
            speechFrames += frames
            silenceFrames = 0L
        } else {
            silenceFrames += frames
        }
        val sampleRate = segmentFormat!!.sampleRate
        if (segmentFrames * 1_000L >= maxSegmentMs.toLong() * sampleRate) {
            return emit()
        }
        if (silenceFrames * 1_000L >= silenceMs.toLong() * sampleRate) {
            return emit()
        }
        return emptyList()
    }

    /** 通话结束/取消时交出尚未满 800 ms 静音的最后一段；没有合格语音时返回 null。 */
    @Synchronized
    fun flush(): PcmAudio? = emit().firstOrNull()

    /** 丢弃当前缓冲（例如 TTS 播放前，避免把回声与播报前残留拼进下一段）。 */
    @Synchronized
    fun reset() {
        clear()
    }

    private fun emit(): List<PcmAudio> {
        val format = segmentFormat ?: return emptyList()
        val frames = segmentFrames
        val speech = speechFrames
        val audio = PcmAudio(buffer.toByteArray(), format)
        clear()
        if (speech * 1_000L < minSpeechMs.toLong() * format.sampleRate) return emptyList()
        if (frames <= 0L) return emptyList()
        return listOf(audio)
    }

    private fun clear() {
        buffer.reset()
        segmentFormat = null
        segmentFrames = 0L
        speechFrames = 0L
        silenceFrames = 0L
    }

    private fun rms(data: ByteArray, frames: Int): Double {
        var sum = 0.0
        for (index in 0 until frames) {
            val low = data[2 * index].toInt() and 0xFF
            val high = data[2 * index + 1].toInt() and 0xFF
            val sample = (low or (high shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        return Math.sqrt(sum / frames) / Short.MAX_VALUE
    }

    companion object {
        /** spec §10：VAD 静音 800 ms。 */
        const val DEFAULT_SILENCE_MS = 800

        /** spec §10：单段 15 秒。 */
        const val DEFAULT_MAX_SEGMENT_MS = 15_000

        /** 低于 200 ms 的“语音”视为点击/回声残片。 */
        const val DEFAULT_MIN_SPEECH_MS = 200

        /** 归一化 RMS 门限；16-bit 满幅为 1.0。 */
        const val DEFAULT_RMS_THRESHOLD = 0.02
    }
}

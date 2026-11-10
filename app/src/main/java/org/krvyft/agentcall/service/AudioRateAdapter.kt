package org.krvyft.agentcall.service

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.Flow
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.PcmEncoding
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession

/**
 * 重采样格式不合法（未知采样率、PCM16 以外、非 16-bit 对齐）。
 *
 * [code] 是固定机器码，绝不包含音频内容；调用方必须把它当作“本次上行不可用”处理，
 * 不能退回原样写入（那会被 bridge 以格式不符拒绝，或按错误采样率播放成花栗鼠音）。
 */
class AudioFormatException(
    val code: String,
    message: String,
) : Exception(message)

/**
 * 采样率适配（spec §9：“内部 PCM16 单声道、明确采样率，适配层负责重采样”）。
 *
 * ConversationEngine 原样把供应商 TTS 输出（常见 24 kHz WAV）交给 [AudioSession.writeUplink]，
 * 而 PCM lane 的格式是固定的 16 kHz 单声道。若直接写入不同采样率：
 * - bridge 的格式校验会拒绝（`audio_pcm_format_mismatch`），整轮通话失败；或
 * - 忽略差异按 16 kHz 解释 24 kHz 采样，播放速度变成 1.5 倍（“花栗鼠”）。
 * 因此必须在写入上行之前重采样。
 *
 * 实现是带 Hann 窗的 windowed-sinc 插值：
 * - 截止频率取目标 Nyquist 的 95%（降采样时同时是抗混叠低通），因此目标 Nyquist 以上的
 *   内容被衰减而不是折叠成混叠；
 * - 每个输出样本的权重做归一化，直流/幅度基本保持不变；
 * - 只使用 PCM16 单声道；未知格式、非法采样率或非 16-bit 对齐一律抛 [AudioFormatException]，
 *   绝不“尽力播放”。
 */
object AudioRateAdapter {

    /** 允许的采样率范围（与 bridge 的 PCM lane 解码范围一致）。 */
    const val MIN_SAMPLE_RATE: Int = 8_000
    const val MAX_SAMPLE_RATE: Int = 192_000

    const val ERROR_FORMAT_UNSUPPORTED: String = "audio_resample_format_unsupported"
    const val ERROR_NOT_ALIGNED: String = "audio_resample_not_aligned"

    /** 截止频率相对目标 Nyquist 的比例：留 5% 过渡带，抑制边界混叠。 */
    private const val ROLLOFF = 0.95

    /** 窗函数覆盖的零交叉数目标；窗口长度按截止频率放大（降采样需要更多抽头）。 */
    private const val SUPPORT_ZERO_CROSSINGS = 12.0
    private const val MIN_HALF_TAPS = 8
    private const val MAX_HALF_TAPS = 64

    /**
     * 把 [input] 重采样为 [target] 格式（采样率/声道由 [target] 声明；编码固定 PCM16）。
     *
     * @throws AudioFormatException 源/目标格式不合法、PCM 未按 16-bit 对齐。
     */
    fun resample(input: PcmAudio, target: AudioFormatSpec): PcmAudio {
        val source = input.format
        if (source.encoding != PcmEncoding.PCM16 || target.encoding != PcmEncoding.PCM16) {
            throw AudioFormatException(ERROR_FORMAT_UNSUPPORTED, "only PCM16 can be resampled")
        }
        if (source.sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE ||
            target.sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE
        ) {
            throw AudioFormatException(
                ERROR_FORMAT_UNSUPPORTED,
                "sample rate must be within " + MIN_SAMPLE_RATE + ".." + MAX_SAMPLE_RATE,
            )
        }
        if (input.data.size % 2 != 0) {
            throw AudioFormatException(ERROR_NOT_ALIGNED, "PCM data is not 16-bit aligned")
        }
        if (input.data.isEmpty()) return PcmAudio(ByteArray(0), target)
        if (source.sampleRate == target.sampleRate) {
            return PcmAudio(input.data.copyOf(), target)
        }
        return PcmAudio(
            data = resampleBytes(input.data, source.sampleRate, target.sampleRate),
            format = target,
        )
    }

    private fun resampleBytes(data: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        val sourceFrames = data.size / 2
        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        val outputFrames = max(1, (sourceFrames * ratio).roundToInt())
        val output = ByteArray(outputFrames * 2)

        val cutoff = min(1.0, ratio) * ROLLOFF
        val halfTaps = ceil(SUPPORT_ZERO_CROSSINGS / cutoff).toInt()
            .coerceIn(MIN_HALF_TAPS, MAX_HALF_TAPS)
        val step = sourceRate.toDouble() / targetRate.toDouble()

        // Hann 窗 over [-halfTaps, halfTaps]（按源样本距离）；预先算好窗值。
        val window = DoubleArray(halfTaps + 1) { offset ->
            if (offset == 0) 1.0 else 0.5 * (1.0 + cos(PI * offset / (halfTaps + 1)))
        }

        for (outputIndex in 0 until outputFrames) {
            val position = outputIndex * step
            val center = position.toInt()
            var weighted = 0.0
            var weightSum = 0.0
            for (offset in -halfTaps..halfTaps) {
                val sourceIndex = center + offset
                if (sourceIndex < 0 || sourceIndex >= sourceFrames) continue
                val distance = position - sourceIndex
                val normalized = distance * cutoff
                val sinc = if (abs(normalized) < 1e-9) 1.0 else sin(PI * normalized) / (PI * normalized)
                val weight = sinc * window[abs(offset)]
                weighted += sampleAt(data, sourceIndex) * weight
                weightSum += weight
            }
            val value = if (abs(weightSum) < 1e-9) 0.0 else weighted / weightSum
            val clamped = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[2 * outputIndex] = (clamped and 0xFF).toByte()
            output[2 * outputIndex + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return output
    }

    private fun sampleAt(data: ByteArray, frame: Int): Int {
        val low = data[2 * frame].toInt() and 0xFF
        val high = data[2 * frame + 1].toInt() and 0xFF
        return (low or (high shl 8)).toShort().toInt()
    }
}

/**
 * 把 [AudioSession.writeUplink] 的供应商采样率音频转换为 PCM lane 格式后再写出。
 *
 * 下行原样转发：bridge 已经按 lane 格式（16 kHz）发送，VAD/ASR 直接按声明采样率工作。
 * [stop] 原样转发——停止不解除麦克风隔离，那是监督端的职责。
 */
class ResamplingAudioSession(
    internal val delegate: AudioSession,
    private val laneFormat: AudioFormatSpec,
) : AudioSession {

    override val downlink: Flow<PcmChunk> get() = delegate.downlink

    override suspend fun writeUplink(audio: PcmAudio) {
        val converted = AudioRateAdapter.resample(audio, laneFormat)
        delegate.writeUplink(converted)
    }

    override suspend fun stop() = delegate.stop()
}

/**
 * 在 [AudioBridge.open] 返回的每个会话上套一层重采样。
 *
 * 目标格式就是协调器请求的 lane 格式：会话一旦打开，任何供应商采样率的 TTS 输出都先转换
 * 成它再写入，绝不把不同采样率直接交给 PCM lane。
 */
class ResamplingAudioBridge(
    private val delegate: AudioBridge,
) : AudioBridge {

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession =
        ResamplingAudioSession(delegate.open(id, format), format)
}

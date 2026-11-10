package org.krvyft.agentcall.bridge.audio

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 有状态 PCM16 单声道重采样器（线性插值）。
 *
 * 内部契约固定 PCM16 单声道；设备流可能不是内部采样率（例如 48 kHz 提取流、16 kHz
 * 内部 TTS），适配层必须转换而不能把设备采样率谎报成内部采样率。
 *
 * 跨分片保持相位与最后一个样本，避免在每个分片边界重置插值造成周期性咔嗒声。
 * 因为不读取“未来”样本，一个分片最多保留不到一个输入样本的相位；分片极短时输出会
 * 比理论长度略少，但不会丢帧或重复整段音频。非线程安全：每个方向单独实例、单线程使用。
 *
 * @param inputRate 输入采样率（Hz），8000..192000。
 * @param outputRate 输出采样率（Hz），8000..192000。
 */
class Resampler(
    val inputRate: Int,
    val outputRate: Int,
) {

    init {
        require(inputRate in MIN_RATE..MAX_RATE) { "inputRate out of range: " + inputRate }
        require(outputRate in MIN_RATE..MAX_RATE) { "outputRate out of range: " + outputRate }
    }

    /** 下一个输出样本在“当前分片”坐标系中的位置；可为 -1（用上一分片末样本插值）。 */
    private var position: Double = 0.0

    /** 上一分片的最后一个输入样本，用于跨分片插值。 */
    private var previousSample: Short? = null

    /** 丢弃跨分片相位与缓存；下一次输入从位置 0 重新开始。 */
    fun reset() {
        position = 0.0
        previousSample = null
    }

    /** 把 PCM16 LE 字节重采样为 PCM16 LE 字节；输入字节数必须为偶数。 */
    fun resample(input: ByteArray): ByteArray {
        require(input.size % 2 == 0) { "PCM16 input must have an even byte count: " + input.size }
        val samples = ShortArray(input.size / 2)
        for (index in samples.indices) {
            val low = input[2 * index].toInt() and 0xFF
            val high = input[2 * index + 1].toInt() and 0xFF
            samples[index] = ((high shl 8) or low).toShort()
        }
        val output = resampleSamples(samples)
        val bytes = ByteArray(output.size * 2)
        for (index in output.indices) {
            val value = output[index].toInt()
            bytes[2 * index] = (value and 0xFF).toByte()
            bytes[2 * index + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * 重采样一个 PCM16 样本分片。空输入原样返回空输出；同速率直接复制。
     */
    fun resampleSamples(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        if (inputRate == outputRate) {
            previousSample = input[input.size - 1]
            return input.copyOf()
        }

        // 极短分片可能让相位越过上一分片末样本；夹到 -1 才能继续用 0 阶保持插值。
        if (position < -1.0) position = -1.0

        val step = inputRate.toDouble() / outputRate.toDouble()
        val lastUsablePosition = input.size - 1.0
        val estimated = ((input.size.toDouble() * outputRate / inputRate) + 2.0).toInt().coerceAtLeast(1)
        val output = ArrayList<Short>(estimated)

        while (position < lastUsablePosition) {
            val base = floor(position).toInt()
            val fraction = position - base
            val first = when {
                base < 0 -> (previousSample ?: input[0]).toInt()
                else -> input[base].toInt()
            }
            val second = when {
                base + 1 < 0 -> first
                base + 1 >= input.size -> input[input.size - 1].toInt()
                else -> input[base + 1].toInt()
            }
            val value = first + ((second - first) * fraction).roundToInt()
            output.add(value.coerceIn(MIN_SAMPLE, MAX_SAMPLE).toShort())
            position += step
        }

        position -= input.size
        previousSample = input[input.size - 1]
        return output.toShortArray()
    }

    companion object {
        const val MIN_RATE: Int = 8_000
        const val MAX_RATE: Int = 192_000

        private const val MIN_SAMPLE: Int = Short.MIN_VALUE.toInt()
        private const val MAX_SAMPLE: Int = Short.MAX_VALUE.toInt()
    }
}

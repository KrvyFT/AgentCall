package org.krvyft.agentcall.core.model

/**
 * 内部 PCM 编码。首版固定 [PCM16]，适配层负责与设备格式之间的重采样/转换。
 */
enum class PcmEncoding {
    PCM16,
}

/**
 * 内部音频格式声明。
 *
 * @property sampleRate 采样率（Hz），单声道 16-bit 时一个采样 2 字节。
 * @property channels 声道数；内部契约为单声道，多声道设备流由适配层转换为单声道。
 */
data class AudioFormatSpec(
    val sampleRate: Int = 16_000,
    val channels: Int = INTERNAL_CHANNELS,
    val encoding: PcmEncoding = PcmEncoding.PCM16,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: " + sampleRate }
        require(channels == INTERNAL_CHANNELS) {
            "internal PCM contract is mono, got channels=" + channels
        }
    }

    companion object {
        /** 内部 PCM 契约固定为单声道。 */
        const val INTERNAL_CHANNELS = 1
    }
}

/**
 * 下行 PCM 分片。按内容比较，是 data class（spec §5），copy()/解构均可用。
 *
 * [data] 是 ByteArray，默认相等性是引用相等，所以本类在 body 中显式实现了
 * equals/hashCode/toString：一旦显式实现，data class 不再生成这三个函数，
 * 但 component/copy 仍由编译器生成，内容相等与 copy()/解构同时成立。
 * copy() 是浅拷贝，[data] 仍是同一数组引用；调用方不得原地修改 [data]。
 */
data class PcmChunk(
    val data: ByteArray,
    val format: AudioFormatSpec,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PcmChunk && format == other.format && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * format.hashCode() + data.contentHashCode()

    override fun toString(): String =
        "PcmChunk(format=" + format + ", bytes=" + data.size + ")"
}

/**
 * 一段完整的上行 PCM 音频（例如 TTS 输出）。按内容比较，是 data class，理由同 [PcmChunk]。
 */
data class PcmAudio(
    val data: ByteArray,
    val format: AudioFormatSpec,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PcmAudio && format == other.format && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * format.hashCode() + data.contentHashCode()

    override fun toString(): String =
        "PcmAudio(format=" + format + ", bytes=" + data.size + ")"
}

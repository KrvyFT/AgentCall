package org.krvyft.agentcall.ai

import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmEncoding

/**
 * WAV 头或帧尺寸不合法。分析/合成响应都必须先通过本校验，坏输入绝不发往设备。
 */
class WavFormatException(message: String) : IllegalArgumentException(message)

/**
 * 最小 WAV 编解码器：只接受 RIFF/WAVE、PCM（format 1）、单声道、16-bit。
 *
 * 编码用于把 VAD 分句上传给 ASR 的 multipart 载荷；解码用于 TTS 返回的 audio/wav。
 * 校验是双向的：输入长度、RIFF 声明长度、chunk 边界、fmt 字段、blockAlign 与 data 帧对齐
 * 任一不成立都拒绝；超过 8 MiB 的输入/输出直接拒绝，绝不边读边猜。
 */
object WavCodec {

    /** 单次请求/响应允许的最大 WAV 字节数（spec §10：响应上限 8 MiB）。 */
    const val MAX_BYTES = 8 * 1024 * 1024

    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE = 16
    private const val MONO_CHANNELS = 1

    /**
     * 把一段 PCM16 单声道音频编码成 44 字节头的 WAV。
     *
     * @throws WavFormatException 编码、声道、帧对齐或尺寸不合法。
     */
    fun encode(audio: PcmAudio): ByteArray {
        val format = audio.format
        val pcm = audio.data
        if (format.encoding != PcmEncoding.PCM16) {
            throw WavFormatException("only PCM16 can be encoded, got " + format.encoding)
        }
        if (pcm.isEmpty()) throw WavFormatException("PCM data is empty")
        if (pcm.size % 2 != 0) throw WavFormatException("PCM data is not frame aligned: " + pcm.size + " bytes")
        if (pcm.size > MAX_BYTES - HEADER_BYTES) {
            throw WavFormatException("WAV payload would exceed 8 MiB")
        }
        val out = ByteArray(HEADER_BYTES + pcm.size)
        writeAscii(out, 0, "RIFF")
        writeIntLe(out, 4, 36 + pcm.size)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeIntLe(out, 16, 16)
        writeShortLe(out, 20, PCM_FORMAT)
        writeShortLe(out, 22, MONO_CHANNELS)
        writeIntLe(out, 24, format.sampleRate)
        writeIntLe(out, 28, format.sampleRate * MONO_CHANNELS * (BITS_PER_SAMPLE / 8))
        writeShortLe(out, 32, MONO_CHANNELS * (BITS_PER_SAMPLE / 8))
        writeShortLe(out, 34, BITS_PER_SAMPLE)
        writeAscii(out, 36, "data")
        writeIntLe(out, 40, pcm.size)
        pcm.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * 解码并校验 PCM16 单声道 WAV。
     *
     * @param expected 可选的期望格式；给出时采样率必须一致（声道/编码由本类固定校验）。
     * @throws WavFormatException 任意头部、长度或帧尺寸不合法。
     */
    fun decode(data: ByteArray, expected: AudioFormatSpec? = null): PcmAudio {
        if (data.size > MAX_BYTES) throw WavFormatException("WAV input exceeds 8 MiB")
        if (data.size < 12) throw WavFormatException("not a RIFF/WAVE file: too short")
        if (ascii(data, 0) != "RIFF") throw WavFormatException("not a RIFF/WAVE file: missing RIFF signature")
        if (ascii(data, 8) != "WAVE") throw WavFormatException("not a RIFF/WAVE file: missing WAVE form")

        val riffSize = readIntLe(data, 4).toLong() and 0xFFFFFFFFL
        if (riffSize < 4L) throw WavFormatException("invalid RIFF size: " + riffSize)
        val riffEnd = 8L + riffSize
        if (riffEnd > data.size.toLong()) {
            throw WavFormatException("truncated WAV: RIFF declares " + riffEnd + " bytes, file has " + data.size)
        }

        var offset = 12L
        var fmt: ByteArray? = null
        var dataOffset = -1L
        var dataSize = -1L
        while (offset + 8L <= riffEnd) {
            val id = ascii(data, offset.toInt())
            val size = readIntLe(data, (offset + 4L).toInt()).toLong() and 0xFFFFFFFFL
            val payload = offset + 8L
            if (payload + size > riffEnd) {
                throw WavFormatException("chunk '" + id + "' exceeds the declared RIFF size")
            }
            when (id) {
                "fmt " -> {
                    if (size < 16L) throw WavFormatException("fmt chunk is too short: " + size)
                    fmt = data.copyOfRange(payload.toInt(), (payload + size).toInt())
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                }
            }
            offset = payload + size + (size and 1L)
            if (offset > riffEnd) offset = riffEnd
        }

        val fmtBytes = fmt ?: throw WavFormatException("missing fmt chunk")
        val dataStart = dataOffset
        val pcmSize = dataSize
        if (dataStart < 0L || pcmSize < 0L) throw WavFormatException("missing data chunk")

        val audioFormat = readShortLe(fmtBytes, 0)
        val channels = readShortLe(fmtBytes, 2)
        val sampleRate = readIntLe(fmtBytes, 4)
        val blockAlign = readShortLe(fmtBytes, 12)
        val bits = readShortLe(fmtBytes, 14)
        if (audioFormat != PCM_FORMAT) throw WavFormatException("only PCM (format 1) is supported, got " + audioFormat)
        if (channels != MONO_CHANNELS) throw WavFormatException("only mono is supported, got " + channels + " channels")
        if (bits != BITS_PER_SAMPLE) throw WavFormatException("only 16-bit samples are supported, got " + bits)
        if (sampleRate <= 0) throw WavFormatException("invalid sample rate: " + sampleRate)
        if (blockAlign != channels * (bits / 8)) {
            throw WavFormatException("invalid frame alignment: blockAlign=" + blockAlign)
        }
        if (pcmSize == 0L) throw WavFormatException("data chunk is empty")
        if (pcmSize % 2L != 0L) throw WavFormatException("data chunk is not frame aligned: " + pcmSize + " bytes")
        if (dataStart + pcmSize > data.size.toLong()) throw WavFormatException("truncated data chunk")
        if (expected != null && expected.sampleRate != sampleRate) {
            throw WavFormatException(
                "unexpected sample rate " + sampleRate + ", expected " + expected.sampleRate,
            )
        }
        return PcmAudio(
            data = data.copyOfRange(dataStart.toInt(), (dataStart + pcmSize).toInt()),
            format = AudioFormatSpec(sampleRate = sampleRate),
        )
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

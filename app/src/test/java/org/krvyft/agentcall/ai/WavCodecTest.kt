package org.krvyft.agentcall.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio

/**
 * WAV 头/帧/长度校验：只接受 PCM16 单声道，拒绝截断、奇数帧、虚假长度、超限与非 WAV 输入。
 *
 * 断言直接读取 RIFF 字节（不是对比实现内部的常量），失败输入全部来自手工构造的字节。
 */
class WavCodecTest {

    @Test
    fun encodeWritesACanonicalPcm16MonoHeader() {
        val data = tone(100, 16_000)
        val encoded = WavCodec.encode(PcmAudio(data, AudioFormatSpec(16_000)))

        assertEquals("RIFF", String(encoded, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + data.size, readIntLe(encoded, 4))
        assertEquals("WAVE", String(encoded, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(encoded, 12, 4, Charsets.US_ASCII))
        assertEquals(16, readIntLe(encoded, 16))
        assertEquals(1, readShortLe(encoded, 20))
        assertEquals(1, readShortLe(encoded, 22))
        assertEquals(16_000, readIntLe(encoded, 24))
        assertEquals(32_000, readIntLe(encoded, 28))
        assertEquals(2, readShortLe(encoded, 32))
        assertEquals(16, readShortLe(encoded, 34))
        assertEquals("data", String(encoded, 36, 4, Charsets.US_ASCII))
        assertEquals(data.size, readIntLe(encoded, 40))
        assertEquals(44 + data.size, encoded.size)
    }

    @Test
    fun roundTripPreservesSamplesAndFormat() {
        val data = tone(250, 24_000)
        val encoded = WavCodec.encode(PcmAudio(data, AudioFormatSpec(24_000)))

        val decoded = WavCodec.decode(encoded)

        assertEquals(24_000, decoded.format.sampleRate)
        assertEquals(1, decoded.format.channels)
        assertArrayEquals(data, decoded.data)
    }

    @Test
    fun decodeSkipsUnknownChunksAndStillFindsData() {
        val data = tone(50, 16_000)
        val withListChunk = riffWithExtraChunk(
            fmt = pcmFmt(16_000),
            extraId = "LIST",
            extraPayload = "INFOfixture".toByteArray(Charsets.US_ASCII),
            data = data,
        )

        val decoded = WavCodec.decode(withListChunk)

        assertArrayEquals(data, decoded.data)
        assertEquals(16_000, decoded.format.sampleRate)
    }

    @Test
    fun decodeRejectsNonWavAndTooShortAndEmptyInputs() {
        for (bytes in listOf(
            ByteArray(0),
            "hello".toByteArray(),
            "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.US_ASCII),
            ByteArray(43),
        )) {
            expectFailure(WavFormatException::class.java) { WavCodec.decode(bytes) }
        }
    }

    @Test
    fun decodeRejectsInputLargerThanEightMebibytes() {
        val oversized = ByteArray(WavCodec.MAX_BYTES + 1)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(oversized, 0)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(oversized, 8)
        val failure = expectFailure(WavFormatException::class.java) { WavCodec.decode(oversized) }
        assertTrue(failure.message!!.contains("MiB") || failure.message!!.contains("8 MiB"))
    }

    @Test
    fun encodeRejectsOversizedAudio() {
        val oversized = PcmAudio(ByteArray(WavCodec.MAX_BYTES), AudioFormatSpec(16_000))
        expectFailure(WavFormatException::class.java) { WavCodec.encode(oversized) }
    }

    @Test
    fun decodeRejectsDeclaredDataLongerThanTheFile() {
        val bytes = pcmWav(data = tone(20, 16_000), declaredDataSize = 4_000)
        expectFailure(WavFormatException::class.java) { WavCodec.decode(bytes) }
    }

    @Test
    fun decodeRejectsRiffSizeBeyondTheActualFile() {
        val data = tone(20, 16_000)
        val bytes = pcmWav(data = data, declaredRiffSize = 36 + data.size + 1_000)
        expectFailure(WavFormatException::class.java) { WavCodec.decode(bytes) }
    }

    @Test
    fun decodeRejectsOddFrameLength() {
        val bytes = pcmWav(data = ByteArray(3) { 1 }, declaredDataSize = 3)
        val failure = expectFailure(WavFormatException::class.java) { WavCodec.decode(bytes) }
        assertTrue(failure.message!!.contains("frame") || failure.message!!.contains("align"))
    }

    @Test
    fun decodeRejectsEmptyDataChunk() {
        val bytes = pcmWav(data = ByteArray(0), declaredDataSize = 0)
        expectFailure(WavFormatException::class.java) { WavCodec.decode(bytes) }
    }

    @Test
    fun decodeRejectsNonPcm16OrNonMonoHeaders() {
        val data = tone(20, 16_000)
        val variants = listOf(
            "float samples" to pcmWav(data = data, fmt = pcmFmt(16_000, audioFormat = 3)),
            "stereo" to pcmWav(data = data, fmt = pcmFmt(16_000, channels = 2, blockAlign = 4)),
            "8-bit" to pcmWav(data = data, fmt = pcmFmt(16_000, bits = 8, blockAlign = 1)),
            "bad block align" to pcmWav(data = data, fmt = pcmFmt(16_000, blockAlign = 4)),
            "zero sample rate" to pcmWav(data = data, fmt = pcmFmt(0, byteRate = 0)),
        )
        for ((label, bytes) in variants) {
            val failure = try {
                WavCodec.decode(bytes)
                null
            } catch (t: Throwable) {
                t
            }
            assertTrue(label + " must be rejected, got " + failure, failure is WavFormatException)
        }
    }

    @Test
    fun decodeRejectsMissingDataChunk() {
        val fmt = pcmFmt(16_000)
        val body = chunk("fmt ", fmt)
        val riffSize = 4 + body.size
        val bytes = buildRiff(riffSize, body)
        expectFailure(WavFormatException::class.java) { WavCodec.decode(bytes) }
    }

    @Test
    fun decodeRejectsSampleRateMismatchWhenExpectedFormatIsGiven() {
        val bytes = WavCodec.encode(PcmAudio(tone(50, 16_000), AudioFormatSpec(16_000)))
        expectFailure(WavFormatException::class.java) {
            WavCodec.decode(bytes, AudioFormatSpec(48_000))
        }
        // 匹配时期望格式不改变结果。
        assertEquals(16_000, WavCodec.decode(bytes, AudioFormatSpec(16_000)).format.sampleRate)
    }
}

internal fun tone(ms: Int, sampleRate: Int = 16_000, amplitude: Double = 0.3): ByteArray {
    val frames = sampleRate * ms / 1_000
    val out = ByteArray(frames * 2)
    for (i in 0 until frames) {
        val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * amplitude * Short.MAX_VALUE).toInt()
        out[2 * i] = (sample and 0xFF).toByte()
        out[2 * i + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return out
}

internal fun silence(ms: Int, sampleRate: Int = 16_000): ByteArray = ByteArray(sampleRate * ms / 1_000 * 2)

internal fun readIntLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

internal fun readShortLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun pcmFmt(
    sampleRate: Int,
    audioFormat: Int = 1,
    channels: Int = 1,
    bits: Int = 16,
    blockAlign: Int = 2,
    byteRate: Int = sampleRate * blockAlign,
): ByteArray {
    val out = ByteArray(16)
    putShortLe(out, 0, audioFormat)
    putShortLe(out, 2, channels)
    putIntLe(out, 4, sampleRate)
    putIntLe(out, 8, byteRate)
    putShortLe(out, 12, blockAlign)
    putShortLe(out, 14, bits)
    return out
}

internal fun pcmWav(
    data: ByteArray,
    fmt: ByteArray = pcmFmt(16_000),
    declaredDataSize: Int = data.size,
    declaredRiffSize: Int = 4 + (8 + fmt.size) + (8 + declaredDataSize),
): ByteArray {
    val body = chunk("fmt ", fmt) + chunk("data", data, declaredDataSize)
    return buildRiff(declaredRiffSize, body)
}

internal fun riffWithExtraChunk(
    fmt: ByteArray,
    extraId: String,
    extraPayload: ByteArray,
    data: ByteArray,
): ByteArray {
    var body = chunk("fmt ", fmt) + chunk(extraId, extraPayload) + chunk("data", data)
    if (extraPayload.size % 2 == 1) {
        body += byteArrayOf(0)
    }
    return buildRiff(4 + body.size, body)
}

internal fun buildRiff(riffSize: Int, body: ByteArray): ByteArray {
    val out = ByteArray(12 + body.size)
    "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
    putIntLe(out, 4, riffSize)
    "WAVE".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
    body.copyInto(out, 12)
    return out
}

internal fun chunk(id: String, payload: ByteArray, declaredSize: Int = payload.size): ByteArray {
    val out = ByteArray(8 + payload.size)
    id.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
    putIntLe(out, 4, declaredSize)
    payload.copyInto(out, 8)
    return out
}

internal fun putIntLe(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

internal fun putShortLe(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

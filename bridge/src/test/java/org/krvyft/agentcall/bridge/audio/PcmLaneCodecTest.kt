package org.krvyft.agentcall.bridge.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmEncoding
import org.krvyft.agentcall.core.model.SessionId

/**
 * PCM lane 线格式的边界测试（Task 7A2）。
 *
 * 线格式是 4 字节大端长度 + 1 字节类型 + 正文：
 * - 0x01 控制帧（JSON：open/open_ok/stop/error/closed）
 * - 0x02 下行 PCM16LE 单声道原始字节
 * - 0x03 上行 PCM：4 字节大端序号 + PCM16LE 字节
 * - 0x04 上行播放结果（JSON：seq/ok/code），只在真正播放结束后才回
 *
 * 超长/截断/未知类型一律在读取正文前失败，绝不按声明长度预分配内存。
 */
class PcmLaneCodecTest {

    private val format = AudioFormatSpec(sampleRate = 16_000, encoding = PcmEncoding.PCM16)
    private val session = SessionId("epoch-1", "call-1")

    @Test
    fun controlFrameRoundTripsTheOpenHandshake() {
        val body = PcmLaneCodec.encodeOpen(session, format)
        assertTrue(body.size <= PcmLaneCodec.MAX_BODY_BYTES)

        val handshake = PcmLaneCodec.decodeOpen(body)
        assertNotNull(handshake)
        assertEquals(session, handshake!!.session)
        assertEquals(format, handshake.format)

        val bytes = frameBytes(PcmLaneType.CONTROL, body)
        val read = PcmLaneCodec.readFrame(ByteArrayInputStream(bytes))
        assertTrue(read is PcmLaneRead.Frame)
        assertEquals(PcmLaneType.CONTROL, (read as PcmLaneRead.Frame).frame.type)
        assertArrayEquals(body, read.frame.body)
    }

    @Test
    fun handshakeRejectsUnknownFieldsUnsafeTokensAndUnsupportedEncodings() {
        val valid = "{" +
            "\"type\":\"open\"," +
            "\"sessionId\":{\"processEpoch\":\"epoch-1\",\"callId\":\"call-1\"}," +
            "\"format\":{\"sampleRate\":16000,\"channels\":1,\"encoding\":\"PCM16\"}" +
            "}"
        assertNotNull(PcmLaneCodec.decodeOpen(valid.toByteArray()))

        val invalid = listOf(
            "",
            "not-json",
            "[]",
            valid.replace("\"type\":\"open\",", ""),
            valid.replace("\"callId\":\"call-1\"", "\"callId\":\"bad\\nid\""),
            valid.replace("\"encoding\":\"PCM16\"", "\"encoding\":\"PCM24\""),
            valid.replace("\"channels\":1", "\"channels\":2"),
            valid.replace("\"sampleRate\":16000", "\"sampleRate\":0"),
            valid.replace("\"sampleRate\":16000", "\"sampleRate\":\"16000\""),
            valid.replace("}", ",\"extra\":1}"),
        )
        for (candidate in invalid) {
            assertNull("handshake must be rejected: " + candidate, PcmLaneCodec.decodeOpen(candidate.toByteArray()))
        }
    }

    @Test
    fun oversizedFrameIsRejectedBeforeTheBodyIsRead() {
        val header = byteArrayOf(0, 0x00, 0x80.toByte(), 0x01) // 32769 > MAX_BODY_BYTES
        val input = ByteArrayInputStream(header)
        val read = PcmLaneCodec.readFrame(input)
        assertTrue(read is PcmLaneRead.Invalid)
        assertEquals(PcmLaneCodec.ERROR_FRAME_TOO_LARGE, (read as PcmLaneRead.Invalid).code)
        // 正文没有按声明长度分配/读取：输入里只剩帧头之后的内容。
        assertEquals(0, input.available())
    }

    @Test
    fun truncatedFrameAndUnknownTypeAreRejected() {
        val bytes = frameBytes(PcmLaneType.CONTROL, PcmLaneCodec.encodeStop())
        val truncated = PcmLaneCodec.readFrame(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)))
        assertTrue(truncated is PcmLaneRead.Invalid)
        assertEquals(PcmLaneCodec.ERROR_FRAME_TRUNCATED, (truncated as PcmLaneRead.Invalid).code)

        // 长度 2、类型 0x7F、正文 1 字节：未知类型必须整体拒绝。
        val unknown = PcmLaneCodec.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 0x7F, 1)))
        assertTrue(unknown is PcmLaneRead.Invalid)
        assertEquals(PcmLaneCodec.ERROR_FRAME_TYPE, (unknown as PcmLaneRead.Invalid).code)
    }

    @Test
    fun zeroAndOneByteLengthFieldsAreRejectedAsInvalidFrameLength() {
        for (length in listOf(0, 1)) {
            val header = byteArrayOf(0, 0, 0, length.toByte())
            val read = PcmLaneCodec.readFrame(ByteArrayInputStream(header))
            assertTrue("length=" + length, read is PcmLaneRead.Invalid)
            assertEquals(
                "length=" + length,
                PcmLaneCodec.ERROR_FRAME_LENGTH,
                (read as PcmLaneRead.Invalid).code,
            )
        }
    }

    @Test
    fun emptyStreamReportsClosedAndWriteRefusesOversizedBodies() {
        assertTrue(PcmLaneCodec.readFrame(ByteArrayInputStream(ByteArray(0))) === PcmLaneRead.Closed)

        val output = ByteArrayOutputStream()
        val tooLarge = ByteArray(PcmLaneCodec.MAX_BODY_BYTES + 1)
        assertTrue(!PcmLaneCodec.writeFrame(output, PcmLaneType.DOWNLINK, tooLarge))
        assertEquals(0, output.size())
    }

    @Test
    fun uplinkFrameCarriesSequenceAndRejectsOddOrOversizedPcm() {
        val pcm = ByteArray(8) { it.toByte() }
        val body = PcmLaneCodec.encodeUplink(7, pcm)
        assertNotNull(body)
        val uplink = PcmLaneCodec.decodeUplink(body!!)
        assertNotNull(uplink)
        assertEquals(7, uplink!!.seq)
        assertArrayEquals(pcm, uplink.pcm)

        // 奇数字节不是合法 PCM16；超长/空正文都整体拒绝。
        assertNull(PcmLaneCodec.encodeUplink(1, ByteArray(3)))
        assertNull(PcmLaneCodec.encodeUplink(1, ByteArray(0)))
        assertNull(PcmLaneCodec.encodeUplink(1, ByteArray(PcmLaneCodec.MAX_UPLINK_PCM_BYTES + 2)))
        assertNull(PcmLaneCodec.decodeUplink(byteArrayOf(0, 0, 0, 1, 9)))
        assertNull(PcmLaneCodec.decodeUplink(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun controlFramesCarryMachineReadableErrorsAndPlaybackAcks() {
        val error = PcmLaneCodec.decodeControl(PcmLaneCodec.encodeError("audio_pcm_saturated", "bounded failure"))
        assertTrue(error is PcmControl.Error)
        assertEquals("audio_pcm_saturated", (error as PcmControl.Error).code)

        val closed = PcmLaneCodec.decodeControl(PcmLaneCodec.encodeClosed("call_ended"))
        assertTrue(closed is PcmControl.Closed)
        assertEquals("call_ended", (closed as PcmControl.Closed).reason)

        assertTrue(PcmLaneCodec.decodeControl(PcmLaneCodec.encodeStop()) === PcmControl.Stop)

        val ack = PcmLaneCodec.decodeAck(PcmLaneCodec.encodeAck(3, true))
        assertEquals(PcmAck(3, true, null), ack)
        val failedAck = PcmLaneCodec.decodeAck(PcmLaneCodec.encodeAck(4, false, "audio_uplink_failed"))
        assertEquals(PcmAck(4, false, "audio_uplink_failed"), failedAck)
        assertNull(PcmLaneCodec.decodeAck("not-json".toByteArray()))
    }

    @Test
    fun openOkCarriesTheAuthoritativeFormat() {
        val decoded = PcmLaneCodec.decodeOpenOk(PcmLaneCodec.encodeOpenOk(format))
        assertEquals(format, decoded)
        assertNull(PcmLaneCodec.decodeOpenOk(PcmLaneCodec.encodeError("x", "y")))
    }
}

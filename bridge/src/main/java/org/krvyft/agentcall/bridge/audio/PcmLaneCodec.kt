package org.krvyft.agentcall.bridge.audio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmEncoding
import org.krvyft.agentcall.core.model.SessionId

/** PCM lane 的帧类型（帧正文的第一个字节）。 */
object PcmLaneType {
    /** JSON 控制帧：open / open_ok / stop / error / closed。 */
    const val CONTROL: Int = 0x01

    /** 下行 PCM16LE 单声道原始字节（桥接 → APP）。 */
    const val DOWNLINK: Int = 0x02

    /** 上行 PCM：4 字节大端序号 + PCM16LE 单声道字节（APP → 桥接 → 工作进程）。 */
    const val UPLINK: Int = 0x03

    /** 上行播放结果：JSON {seq, ok, code}，只在注入音频**播放结束**后才回。 */
    const val UPLINK_ACK: Int = 0x04

    fun isKnown(type: Int): Boolean = type in CONTROL..UPLINK_ACK
}

/** 一条 PCM lane 帧：类型 + 正文（不含 4 字节长度与类型字节）。 */
class PcmLaneFrame(
    val type: Int,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PcmLaneFrame && type == other.type && body.contentEquals(other.body))

    override fun hashCode(): Int = 31 * type + body.contentHashCode()

    override fun toString(): String = "PcmLaneFrame(type=" + type + ", body=" + body.size + " bytes)"
}

/** 读取一帧的结果：[Closed] 是对端正常关闭，[Invalid] 是必须 fail closed 的协议错误。 */
sealed interface PcmLaneRead {
    data class Frame(val frame: PcmLaneFrame) : PcmLaneRead

    data object Closed : PcmLaneRead

    data class Invalid(val code: String) : PcmLaneRead
}

/** 一次 PCM 会话的打开握手（APP 与工作进程共用同一种形状）。 */
data class PcmHandshake(
    val session: SessionId,
    val format: AudioFormatSpec,
)

/** 控制帧的语义。任何未知/不完整控制帧都返回 null（整体拒绝，不猜）。 */
sealed interface PcmControl {
    data class Open(val handshake: PcmHandshake) : PcmControl

    data class OpenOk(val format: AudioFormatSpec) : PcmControl

    data object Stop : PcmControl

    data class Error(val code: String, val message: String) : PcmControl

    data class Closed(val reason: String?) : PcmControl
}

/** 上行播放结果；[ok] 为 true 表示该帧音频已经真正播放结束。 */
data class PcmAck(val seq: Int, val ok: Boolean, val code: String?)

/** 上行帧正文：序号 + PCM16LE 字节。 */
class PcmUplink(val seq: Int, val pcm: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PcmUplink && seq == other.seq && pcm.contentEquals(other.pcm))

    override fun hashCode(): Int = 31 * seq + pcm.contentHashCode()
}

/**
 * PCM lane 的唯一线格式（spec §6.4：控制帧与 PCM 分开传输，长度有界）。
 *
 * 帧 = 4 字节大端长度 + 1 字节类型 + 正文；长度是“类型 + 正文”的字节数。
 * 超长长度在读取正文**之前**失败，绝不按声明长度预分配内存；截断、未知类型、
 * 空帧同样失败。PCM 是原始字节，不进 JSON（避免编码放大与文本解析开销）。
 *
 * 这条通道只承载一通电话的音频：连接建立时用 [encodeOpen] 绑定 SessionId，
 * 之后不再接受任何会话字段，帧也不可能指向另一个会话。
 */
object PcmLaneCodec {

    /** 一帧字节数上限（含 4 字节长度与 1 字节类型）：32 KiB。 */
    const val MAX_FRAME_BYTES: Int = 32 * 1024

    const val FRAME_HEADER_BYTES: Int = 4

    /** 长度字段允许的最大值（类型 + 正文）。 */
    const val MAX_BODY_BYTES: Int = MAX_FRAME_BYTES - FRAME_HEADER_BYTES

    /**
     * 下行 PCM 单帧上限。
     *
     * 帧长字段覆盖“类型 + 正文”，所以正文（= PCM）必须满足 body + 1 <= [MAX_BODY_BYTES]，
     * 即 PCM <= 32763；PCM16 必须偶数 → 32762。取 32764 会让 32765 的帧长被
     * [writeFrame] 拒绝，链路随后关闭。
     */
    const val MAX_PCM_BYTES: Int = ((MAX_BODY_BYTES - 1) / 2) * 2

    /** 上行帧序号前缀字节数。 */
    const val UPLINK_SEQ_BYTES: Int = 4

    /**
     * 上行 PCM 单帧上限。
     *
     * 上行正文 = 4 字节序号 + PCM，帧长字段再覆盖 1 字节类型，因此
     * PCM <= [MAX_BODY_BYTES] - [UPLINK_SEQ_BYTES] - 1 = 32759；PCM16 必须偶数 → 32758。
     * APP 的 BridgeClient 使用同一推导（32758）；32760 的帧长是 32765，会被
     * [writeFrame] 的 body.size + 1 > MAX_BODY_BYTES 拒绝。
     */
    const val MAX_UPLINK_PCM_BYTES: Int = ((MAX_BODY_BYTES - UPLINK_SEQ_BYTES - 1) / 2) * 2

    const val ERROR_FRAME_TOO_LARGE: String = "pcm_frame_too_large"
    const val ERROR_FRAME_TRUNCATED: String = "pcm_frame_truncated"
    const val ERROR_FRAME_TYPE: String = "pcm_frame_type_invalid"
    const val ERROR_FRAME_LENGTH: String = "pcm_frame_length_invalid"

    private const val MAX_SESSION_FIELD_CHARS = 64
    private const val MAX_CODE_CHARS = 64
    private const val MAX_MESSAGE_CHARS = 200

    // ------------------------------------------------------------------ 成帧

    fun readFrame(input: InputStream): PcmLaneRead {
        val header = ByteArray(FRAME_HEADER_BYTES)
        var headerRead = 0
        while (headerRead < FRAME_HEADER_BYTES) {
            val read = try {
                input.read(header, headerRead, FRAME_HEADER_BYTES - headerRead)
            } catch (io: IOException) {
                return PcmLaneRead.Invalid(ERROR_FRAME_TRUNCATED)
            }
            if (read < 0) {
                return if (headerRead == 0) PcmLaneRead.Closed else PcmLaneRead.Invalid(ERROR_FRAME_TRUNCATED)
            }
            headerRead += read
        }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length <= 1) return PcmLaneRead.Invalid(ERROR_FRAME_LENGTH)
        if (length > MAX_BODY_BYTES) return PcmLaneRead.Invalid(ERROR_FRAME_TOO_LARGE)

        val body = ByteArray(length)
        var bodyRead = 0
        while (bodyRead < length) {
            val read = try {
                input.read(body, bodyRead, length - bodyRead)
            } catch (io: IOException) {
                return PcmLaneRead.Invalid(ERROR_FRAME_TRUNCATED)
            }
            if (read < 0) return PcmLaneRead.Invalid(ERROR_FRAME_TRUNCATED)
            bodyRead += read
        }
        val type = body[0].toInt() and 0xFF
        if (!PcmLaneType.isKnown(type)) return PcmLaneRead.Invalid(ERROR_FRAME_TYPE)
        return PcmLaneRead.Frame(PcmLaneFrame(type, body.copyOfRange(1, body.size)))
    }

    /** 写一帧；超长或写入失败返回 false（调用方必须关链路，不得静默丢弃）。 */
    fun writeFrame(output: OutputStream, type: Int, body: ByteArray): Boolean {
        if (!PcmLaneType.isKnown(type)) return false
        if (body.size + 1 > MAX_BODY_BYTES) return false
        val length = body.size + 1
        val header = byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
            type.toByte(),
        )
        return try {
            output.write(header)
            if (body.isNotEmpty()) output.write(body)
            output.flush()
            true
        } catch (io: IOException) {
            false
        }
    }

    // ------------------------------------------------------------------ 控制帧

    fun encodeOpen(session: SessionId, format: AudioFormatSpec): ByteArray {
        val json = JsonObject()
        json.addProperty("type", "open")
        json.add("sessionId", sessionJson(session))
        json.add("format", formatJson(format))
        return jsonBytes(json)
    }

    fun decodeOpen(body: ByteArray): PcmHandshake? {
        val json = parseObject(body) ?: return null
        if (json.keySet() != OPEN_KEYS) return null
        if (stringField(json, "type") != "open") return null
        val session = decodeSession(json.get("sessionId") as? JsonObject ?: return null) ?: return null
        val format = decodeFormat(json.get("format") as? JsonObject ?: return null) ?: return null
        return PcmHandshake(session, format)
    }

    fun encodeOpenOk(format: AudioFormatSpec): ByteArray {
        val json = JsonObject()
        json.addProperty("type", "open_ok")
        json.add("format", formatJson(format))
        return jsonBytes(json)
    }

    fun decodeOpenOk(body: ByteArray): AudioFormatSpec? {
        val json = parseObject(body) ?: return null
        if (json.keySet() != OPEN_OK_KEYS) return null
        if (stringField(json, "type") != "open_ok") return null
        return decodeFormat(json.get("format") as? JsonObject ?: return null)
    }

    fun encodeStop(): ByteArray = JsonObject().apply { addProperty("type", "stop") }.toString().toByteArray()

    fun encodeError(code: String, message: String): ByteArray {
        val json = JsonObject()
        json.addProperty("type", "error")
        json.addProperty("code", code.take(MAX_CODE_CHARS))
        json.addProperty("message", message.take(MAX_MESSAGE_CHARS))
        return jsonBytes(json)
    }

    fun encodeClosed(reason: String?): ByteArray {
        val json = JsonObject()
        json.addProperty("type", "closed")
        if (reason != null) json.addProperty("reason", reason.take(MAX_MESSAGE_CHARS))
        return jsonBytes(json)
    }

    fun decodeControl(body: ByteArray): PcmControl? {
        val json = parseObject(body) ?: return null
        return when (stringField(json, "type")) {
            "open" -> decodeOpen(body)?.let { PcmControl.Open(it) }
            "open_ok" -> decodeOpenOk(body)?.let { PcmControl.OpenOk(it) }
            "stop" -> if (json.keySet() == setOf("type")) PcmControl.Stop else null
            "error" -> {
                if (json.keySet() != ERROR_KEYS) return null
                val code = stringField(json, "code") ?: return null
                val message = stringField(json, "message") ?: return null
                if (!isSafeToken(code, MAX_CODE_CHARS)) return null
                PcmControl.Error(code, message.take(MAX_MESSAGE_CHARS))
            }
            "closed" -> {
                if (json.keySet() != CLOSED_KEYS && json.keySet() != CLOSED_WITH_REASON_KEYS) return null
                val reason = if (json.has("reason")) stringField(json, "reason") ?: return null else null
                PcmControl.Closed(reason)
            }
            else -> null
        }
    }

    fun encodeAck(seq: Int, ok: Boolean, code: String? = null): ByteArray {
        val json = JsonObject()
        json.addProperty("type", "ack")
        json.addProperty("seq", seq)
        json.addProperty("ok", ok)
        if (code != null) json.addProperty("code", code.take(MAX_CODE_CHARS))
        return jsonBytes(json)
    }

    fun decodeAck(body: ByteArray): PcmAck? {
        val json = parseObject(body) ?: return null
        if (json.keySet() != ACK_KEYS && json.keySet() != ACK_WITH_CODE_KEYS) return null
        if (stringField(json, "type") != "ack") return null
        val seq = intField(json, "seq") ?: return null
        if (seq < 0) return null
        val ok = booleanField(json, "ok") ?: return null
        val code = if (json.has("code")) {
            stringField(json, "code")?.takeIf { isSafeToken(it, MAX_CODE_CHARS) } ?: return null
        } else {
            null
        }
        return PcmAck(seq, ok, code)
    }

    /**
     * 控制面 audio.channel 的负载：只接受 {"format":{sampleRate,channels,encoding}}。
     * 多一个键/少一个键/类型不符都整体拒绝。
     */
    fun decodeFormatRequest(payload: ByteArray): AudioFormatSpec? {
        val json = parseObject(payload) ?: return null
        if (json.keySet() != setOf("format")) return null
        return decodeFormat(json.get("format") as? JsonObject ?: return null)
    }

    /** audio.channel 成功响应里的格式对象（与握手格式完全相同）。 */
    fun encodeFormatJson(format: AudioFormatSpec): JsonObject = formatJson(format)

    // ------------------------------------------------------------------ 上行

    /** 编码上行帧正文；空/奇数 PCM 或超过单帧上限返回 null（调用方必须拒绝，不得裁剪）。 */
    fun encodeUplink(seq: Int, pcm: ByteArray): ByteArray? {
        if (seq < 0 || pcm.isEmpty() || pcm.size % 2 != 0) return null
        if (pcm.size > MAX_UPLINK_PCM_BYTES) return null
        val body = ByteArray(UPLINK_SEQ_BYTES + pcm.size)
        body[0] = (seq ushr 24).toByte()
        body[1] = (seq ushr 16).toByte()
        body[2] = (seq ushr 8).toByte()
        body[3] = seq.toByte()
        pcm.copyInto(body, UPLINK_SEQ_BYTES)
        return body
    }

    fun decodeUplink(body: ByteArray): PcmUplink? {
        if (body.size <= UPLINK_SEQ_BYTES) return null
        val pcmBytes = body.size - UPLINK_SEQ_BYTES
        if (pcmBytes % 2 != 0) return null
        val seq = ((body[0].toInt() and 0xFF) shl 24) or
            ((body[1].toInt() and 0xFF) shl 16) or
            ((body[2].toInt() and 0xFF) shl 8) or
            (body[3].toInt() and 0xFF)
        return PcmUplink(seq, body.copyOfRange(UPLINK_SEQ_BYTES, body.size))
    }

    // ------------------------------------------------------------------ 内部

    private val OPEN_KEYS = setOf("type", "sessionId", "format")
    private val OPEN_OK_KEYS = setOf("type", "format")
    private val ERROR_KEYS = setOf("type", "code", "message")
    private val CLOSED_KEYS = setOf("type")
    private val CLOSED_WITH_REASON_KEYS = setOf("type", "reason")
    private val ACK_KEYS = setOf("type", "seq", "ok")
    private val ACK_WITH_CODE_KEYS = setOf("type", "seq", "ok", "code")

    private fun jsonBytes(json: JsonObject): ByteArray = json.toString().toByteArray(StandardCharsets.UTF_8)

    private fun parseObject(body: ByteArray): JsonObject? {
        if (body.isEmpty()) return null
        val text = String(body, StandardCharsets.UTF_8)
        val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun sessionJson(session: SessionId): JsonObject = JsonObject().apply {
        addProperty("processEpoch", session.processEpoch)
        addProperty("callId", session.callId)
    }

    private fun decodeSession(json: JsonObject): SessionId? {
        if (json.keySet() != setOf("processEpoch", "callId")) return null
        val epoch = stringField(json, "processEpoch") ?: return null
        val call = stringField(json, "callId") ?: return null
        if (!isSafeToken(epoch, MAX_SESSION_FIELD_CHARS) || !isSafeToken(call, MAX_SESSION_FIELD_CHARS)) return null
        return SessionId(epoch, call)
    }

    private fun formatJson(format: AudioFormatSpec): JsonObject = JsonObject().apply {
        addProperty("sampleRate", format.sampleRate)
        addProperty("channels", format.channels)
        addProperty("encoding", format.encoding.name)
    }

    /** 只接受协议允许的固定格式：PCM16、单声道、8 kHz–192 kHz（与生产重采样器一致）。 */
    private fun decodeFormat(json: JsonObject): AudioFormatSpec? {
        if (json.keySet() != setOf("sampleRate", "channels", "encoding")) return null
        if (stringField(json, "encoding") != PcmEncoding.PCM16.name) return null
        val sampleRate = intField(json, "sampleRate") ?: return null
        val channels = intField(json, "channels") ?: return null
        if (sampleRate !in Resampler.MIN_RATE..Resampler.MAX_RATE) return null
        if (channels != AudioFormatSpec.INTERNAL_CHANNELS) return null
        return AudioFormatSpec(sampleRate = sampleRate, channels = channels, encoding = PcmEncoding.PCM16)
    }

    private fun stringField(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun intField(json: JsonObject, name: String): Int? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val value = element.asDouble
        if (!value.isFinite() || value != Math.floor(value)) return null
        if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
        return value.toInt()
    }

    private fun booleanField(json: JsonObject, name: String): Boolean? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
        return element.asBoolean
    }

    private fun isSafeToken(value: String, maxChars: Int): Boolean =
        value.isNotEmpty() && value.length <= maxChars &&
            value.none { it.isISOControl() || it.isWhitespace() }
}

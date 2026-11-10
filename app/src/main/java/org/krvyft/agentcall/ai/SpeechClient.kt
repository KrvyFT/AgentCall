package org.krvyft.agentcall.ai

import okhttp3.RequestBody.Companion.toRequestBody
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.PcmAudio

/** TTS 端口：把一段文本合成为可写入通话上行的 PCM16 音频。 */
fun interface SpeechSynthesizer {
    suspend fun synthesize(text: String): PcmAudio
}

/**
 * OpenAI-compatible /audio/speech 客户端，要求返回 WAV（PCM16 单声道，格式由 WavCodec 校验）。
 *
 * 音色与语速来自 AgentConfig；语速收敛到 OpenAI 兼容区间 0.25–4.0。响应不是 WAV 家族或
 * 头部/帧尺寸不合法时抛异常，绝不把坏音频交给设备。
 */
class SpeechClient(
    private val transport: ApiTransport,
    private val endpoint: ApiEndpoint,
    private val apiKey: String?,
    private val voice: String = DEFAULT_VOICE,
    private val speed: Double = 1.0,
) : SpeechSynthesizer {

    override suspend fun synthesize(text: String): PcmAudio {
        if (endpoint.model.isBlank()) throw IllegalArgumentException("TTS model must not be blank")
        if (text.isBlank()) throw IllegalArgumentException("TTS text must not be blank")
        val resolvedVoice = voice.trim().ifEmpty { DEFAULT_VOICE }
        val resolvedSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val payload = buildString {
            append("{\"model\":\"").append(JsonText.escape(endpoint.model)).append("\",")
            append("\"input\":\"").append(JsonText.escape(text)).append("\",")
            append("\"voice\":\"").append(JsonText.escape(resolvedVoice)).append("\",")
            append("\"response_format\":\"wav\",")
            append("\"speed\":").append(resolvedSpeed).append('}')
        }
        val response = transport.post(
            endpoint = endpoint,
            path = PATH,
            apiKey = apiKey,
            body = payload.toRequestBody(ApiTransport.JSON_MEDIA_TYPE),
            accept = "audio/wav",
            deadline = transport.deadlineAfter(ApiTransport.TURN_TIMEOUT_MS),
        )
        if (!isWavContentType(response.contentType)) {
            throw UnsupportedContentTypeException(
                "TTS response must be audio/wav, got '" + response.contentType + "'",
            )
        }
        return WavCodec.decode(response.body)
    }

    companion object {
        /** spec §10：OpenAI-compatible 语音合成固定路径。 */
        const val PATH = "audio/speech"

        /** AgentConfig.voice 为空时的默认音色（OpenAI-compatible 服务普遍实现的音色名）。 */
        const val DEFAULT_VOICE = "alloy"

        /** OpenAI 兼容语速区间。 */
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 4.0
    }
}

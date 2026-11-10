package org.krvyft.agentcall.ai

import okhttp3.RequestBody.Companion.toRequestBody
import org.krvyft.agentcall.core.model.ApiEndpoint

/** ASR 端口：上传一段 PCM16 WAV，返回识别文本。 */
fun interface Transcriber {
    suspend fun transcribe(wav: ByteArray): String
}

/**
 * OpenAI-compatible /audio/transcriptions 客户端（multipart WAV，非流式 JSON 响应）。
 *
 * 上传前用 [WavCodec] 校验输入，坏 WAV 不会离开设备；响应必须是 application/json，
 * 且包含字符串 text 字段。没有 API Key 时省略 Authorization（fixtures 可运行，不等于真实远程验证）。
 */
class TranscriptionClient(
    private val transport: ApiTransport,
    private val endpoint: ApiEndpoint,
    private val apiKey: String?,
) : Transcriber {

    override suspend fun transcribe(wav: ByteArray): String {
        if (endpoint.model.isBlank()) throw IllegalArgumentException("ASR model must not be blank")
        WavCodec.decode(wav)
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", endpoint.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "audio.wav", wav.toRequestBody(ApiTransport.WAV_MEDIA_TYPE))
            .build()
        val response = transport.post(
            endpoint = endpoint,
            path = PATH,
            apiKey = apiKey,
            body = multipart,
            accept = "application/json",
            deadline = transport.deadlineAfter(ApiTransport.TURN_TIMEOUT_MS),
        )
        if (!isJsonContentType(response.contentType)) {
            throw UnsupportedContentTypeException(
                "ASR response must be application/json, got '" + response.contentType + "'",
            )
        }
        val json = JsonText.parse(String(response.body, Charsets.UTF_8))
        val root = JsonText.asObject(json)
            ?: throw MalformedResponseException("ASR response is not a JSON object")
        val text = JsonText.string(root, "text")
            ?: throw MalformedResponseException("ASR response has no text field")
        return text.trim()
    }

    companion object {
        /** spec §10：OpenAI-compatible 语音识别固定路径。 */
        const val PATH = "audio/transcriptions"
    }
}

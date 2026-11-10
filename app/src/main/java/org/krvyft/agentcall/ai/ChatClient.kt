package org.krvyft.agentcall.ai

import okhttp3.RequestBody.Companion.toRequestBody
import org.krvyft.agentcall.core.model.ApiEndpoint

/**
 * 一条对话消息。角色只允许 system/user/assistant：本模块不执行工具、设备命令或身份/付款操作，
 * 因此不提供 tool 角色。
 */
data class ChatMessage(val role: String, val content: String) {
    init {
        require(role == ROLE_SYSTEM || role == ROLE_USER || role == ROLE_ASSISTANT) {
            "unsupported chat role: " + role
        }
    }

    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** LLM 端口：给定消息列表，返回一条非流式文本回复。 */
fun interface ChatModel {
    suspend fun reply(messages: List<ChatMessage>): String
}

/**
 * OpenAI-compatible /chat/completions 客户端（明确非流式：请求 stream=false，拒绝 text/event-stream）。
 *
 * 只解析 choices[0].message.content；空回复、缺失字段或非法 JSON 都抛 [MalformedResponseException]。
 * 不做任何工具/函数调用，模型输出只是对话文本。
 */
class ChatClient(
    private val transport: ApiTransport,
    private val endpoint: ApiEndpoint,
    private val apiKey: String?,
    private val maxTokens: Int = MAX_TOKENS,
) : ChatModel {

    override suspend fun reply(messages: List<ChatMessage>): String {
        if (endpoint.model.isBlank()) throw IllegalArgumentException("LLM model must not be blank")
        if (messages.isEmpty()) throw IllegalArgumentException("chat messages must not be empty")
        val payload = buildString {
            append("{\"model\":\"").append(JsonText.escape(endpoint.model)).append("\",")
            append("\"stream\":false,")
            append("\"max_tokens\":").append(maxTokens).append(',')
            append("\"messages\":[")
            messages.forEachIndexed { index, message ->
                if (index > 0) append(',')
                append("{\"role\":\"").append(JsonText.escape(message.role)).append("\",")
                append("\"content\":\"").append(JsonText.escape(message.content)).append("\"}")
            }
            append("]}")
        }
        val response = transport.post(
            endpoint = endpoint,
            path = PATH,
            apiKey = apiKey,
            body = payload.toRequestBody(ApiTransport.JSON_MEDIA_TYPE),
            accept = "application/json",
            deadline = transport.deadlineAfter(ApiTransport.TURN_TIMEOUT_MS),
        )
        if (!isJsonContentType(response.contentType)) {
            throw UnsupportedContentTypeException(
                "chat response must be non-streaming application/json, got '" + response.contentType + "'",
            )
        }
        val json = JsonText.parse(String(response.body, Charsets.UTF_8))
        val root = JsonText.asObject(json)
            ?: throw MalformedResponseException("chat response is not a JSON object")
        val choices = JsonText.asArray(root["choices"])
            ?: throw MalformedResponseException("chat response has no choices array")
        val first = JsonText.asObject(choices.firstOrNull())
            ?: throw MalformedResponseException("chat response has no choices[0]")
        val message = JsonText.asObject(first["message"])
            ?: throw MalformedResponseException("chat response has no message")
        val content = JsonText.string(message, "content")
            ?: throw MalformedResponseException("chat response has no message content")
        if (content.isBlank()) throw MalformedResponseException("chat response content is blank")
        return content.trim()
    }

    companion object {
        /** spec §10：OpenAI-compatible 对话固定路径。 */
        const val PATH = "chat/completions"

        /** 300 字中文回复的上限留足 token 余量；仅是请求上限，不替代本地 300 字截断。 */
        const val MAX_TOKENS = 1_024

        /** 默认系统提示词：只对话，不执行工具/设备命令，不处理身份或付款。 */
        const val DEFAULT_SYSTEM_PROMPT = "你是一名电话助理，只用简体中文自然对话。你只生成对话内容，" +
            "不执行工具或设备命令，不处理身份验证或付款，也不承诺这些操作。回复不超过 300 字。"
    }
}

package org.krvyft.agentcall.core.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.CapabilityReport
import org.krvyft.agentcall.core.model.ConfigPatch
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.RedactedConfig
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.RuleMode
import org.krvyft.agentcall.core.model.SecretState
import org.krvyft.agentcall.core.model.SecretUpdate
import org.krvyft.agentcall.core.model.SessionId

/**
 * 编码阶段违反协议约束时抛出的异常。[code] 是机器可读错误码，[message] 不含任何密钥。
 */
class RpcProtocolException(val code: String, message: String) : IllegalArgumentException(message)

/**
 * 本地控制协议的唯一编解码实现（spec §6.4）。
 *
 * 两件事严格分开：
 * - **成帧**：控制帧是 4 字节大端长度 + 正文，正文上限 [MAX_CONTROL_FRAME_BYTES]（64 KiB）。
 *   截断、超长、非正长度都在读取正文前失败（fail closed），不会先按声明长度分配内存。
 * - **语义校验**：请求 ID、方法名、协议版本、会话 ID 全部校验后才交给 [RolePolicy]。
 *
 * 公开签名只使用共享契约类型；Gson 只作为内部实现细节。
 */
object RpcCodec {

    /** 控制帧正文上限：64 KiB。PCM 不走控制帧，也不受该上限约束。 */
    const val MAX_CONTROL_FRAME_BYTES: Int = 64 * 1024

    /** 帧头：4 字节大端无符号长度。 */
    const val FRAME_HEADER_BYTES: Int = 4

    const val MAX_REQUEST_ID_CHARS: Int = 128
    const val MAX_METHOD_CHARS: Int = 64
    const val MAX_SESSION_FIELD_CHARS: Int = 64
    const val MAX_ERROR_CODE_CHARS: Int = 64
    const val MAX_ERROR_MESSAGE_CHARS: Int = 512

    const val ERROR_FRAME_TOO_LARGE: String = "frame_too_large"
    const val ERROR_FRAME_TRUNCATED: String = "frame_truncated"
    const val ERROR_FRAME_LENGTH_INVALID: String = "frame_length_invalid"
    const val ERROR_REQUEST_MALFORMED: String = "request_malformed"
    const val ERROR_RESPONSE_MALFORMED: String = "response_malformed"
    const val ERROR_PROTOCOL_VERSION: String = "protocol_version_unsupported"
    const val ERROR_REQUEST_ID_INVALID: String = "request_id_invalid"
    const val ERROR_METHOD_INVALID: String = "method_invalid"
    const val ERROR_SESSION_ID_INVALID: String = "session_id_invalid"

    /** 一条控制请求的 CAS 写入意图（配置补丁只从 stdin/控制帧进入）。 */
    data class ConfigWrite(val expectedRevision: Long, val patch: ConfigPatch)

    sealed interface FrameResult {
        data class Accepted(val body: ByteArray) : FrameResult

        data class Rejected(val code: String, val message: String) : FrameResult
    }

    sealed interface RequestResult {
        data class Accepted(val request: RpcRequest) : RequestResult

        data class Rejected(val code: String, val message: String) : RequestResult
    }

    sealed interface ResponseResult {
        data class Accepted(val response: RpcResponse) : ResponseResult

        data class Rejected(val code: String, val message: String) : ResponseResult
    }

    // ------------------------------------------------------------------ 成帧

    /**
     * 把正文封装为一条控制帧。正文为空或超过 [MAX_CONTROL_FRAME_BYTES] 时抛
     * [RpcProtocolException]（不生成帧）。
     */
    fun encodeFrame(body: ByteArray): ByteArray {
        if (body.isEmpty()) {
            throw RpcProtocolException(ERROR_FRAME_LENGTH_INVALID, "control frame must not be empty")
        }
        if (body.size > MAX_CONTROL_FRAME_BYTES) {
            throw RpcProtocolException(ERROR_FRAME_TOO_LARGE, "control frame exceeds " + MAX_CONTROL_FRAME_BYTES + " bytes")
        }
        val frame = ByteArray(FRAME_HEADER_BYTES + body.size)
        frame[0] = (body.size ushr 24).toByte()
        frame[1] = (body.size ushr 16).toByte()
        frame[2] = (body.size ushr 8).toByte()
        frame[3] = body.size.toByte()
        body.copyInto(frame, FRAME_HEADER_BYTES)
        return frame
    }

    /**
     * 读取一条控制帧。超长长度在读取正文前直接拒绝，截断返回
     * [ERROR_FRAME_TRUNCATED]；调用方必须先按 [RolePolicy] 鉴权再处理正文。
     */
    fun readFrame(input: InputStream): FrameResult {
        val header = ByteArray(FRAME_HEADER_BYTES)
        var headerRead = 0
        while (headerRead < FRAME_HEADER_BYTES) {
            val read = try {
                input.read(header, headerRead, FRAME_HEADER_BYTES - headerRead)
            } catch (io: IOException) {
                return FrameResult.Rejected(ERROR_FRAME_TRUNCATED, "control frame header could not be read")
            }
            if (read < 0) {
                return FrameResult.Rejected(ERROR_FRAME_TRUNCATED, "control frame header truncated")
            }
            headerRead += read
        }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length <= 0) {
            return FrameResult.Rejected(ERROR_FRAME_LENGTH_INVALID, "control frame length must be positive")
        }
        if (length > MAX_CONTROL_FRAME_BYTES) {
            return FrameResult.Rejected(ERROR_FRAME_TOO_LARGE, "control frame exceeds " + MAX_CONTROL_FRAME_BYTES + " bytes")
        }
        val body = ByteArray(length)
        var bodyRead = 0
        while (bodyRead < length) {
            val read = try {
                input.read(body, bodyRead, length - bodyRead)
            } catch (io: IOException) {
                return FrameResult.Rejected(ERROR_FRAME_TRUNCATED, "control frame body could not be read")
            }
            if (read < 0) {
                return FrameResult.Rejected(
                    ERROR_FRAME_TRUNCATED,
                    "control frame body truncated after " + bodyRead + " of " + length + " bytes",
                )
            }
            bodyRead += read
        }
        return FrameResult.Accepted(body)
    }

    // ------------------------------------------------------------------ 请求/响应

    /** 编码一条请求帧；字段不合法时抛 [RpcProtocolException]，不会静默修补。 */
    fun encodeRequestFrame(request: RpcRequest): ByteArray {
        if (!isValidToken(request.id, MAX_REQUEST_ID_CHARS)) {
            throw RpcProtocolException(ERROR_REQUEST_ID_INVALID, "request id must be 1.." + MAX_REQUEST_ID_CHARS + " chars without control characters")
        }
        if (!isValidToken(request.method, MAX_METHOD_CHARS)) {
            throw RpcProtocolException(ERROR_METHOD_INVALID, "method must be 1.." + MAX_METHOD_CHARS + " chars without control characters")
        }
        if (request.version != PROTOCOL_VERSION) {
            throw RpcProtocolException(ERROR_PROTOCOL_VERSION, "unsupported protocol version " + request.version)
        }
        val session = request.sessionId
        if (session != null && (!isValidToken(session.processEpoch, MAX_SESSION_FIELD_CHARS) ||
                !isValidToken(session.callId, MAX_SESSION_FIELD_CHARS))
        ) {
            throw RpcProtocolException(ERROR_SESSION_ID_INVALID, "session id fields must be 1.." + MAX_SESSION_FIELD_CHARS + " chars")
        }
        val json = JsonObject()
        json.addProperty("id", request.id)
        json.addProperty("method", request.method)
        json.addProperty("version", request.version)
        if (session != null) {
            json.add("sessionId", sessionJson(session))
        }
        json.addProperty("payload", request.payload)
        return encodeFrame(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** 读取并校验一条请求帧。任何不合法输入都不会返回 [RpcRequest]。 */
    fun readRequestFrame(input: InputStream): RequestResult =
        when (val frame = readFrame(input)) {
            is FrameResult.Rejected -> RequestResult.Rejected(frame.code, frame.message)
            is FrameResult.Accepted -> decodeRequest(frame.body)
        }

    /** 校验一段已经是帧正文的请求 JSON。 */
    fun decodeRequest(body: ByteArray): RequestResult {
        val json = parseObject(body) ?: return RequestResult.Rejected(ERROR_REQUEST_MALFORMED, "request body must be a JSON object")
        val id = stringField(json, "id") ?: return RequestResult.Rejected(ERROR_REQUEST_ID_INVALID, "request id is missing")
        if (!isValidToken(id, MAX_REQUEST_ID_CHARS)) {
            return RequestResult.Rejected(ERROR_REQUEST_ID_INVALID, "request id must be 1.." + MAX_REQUEST_ID_CHARS + " chars without control characters")
        }
        val version = intField(json, "version") ?: return RequestResult.Rejected(ERROR_PROTOCOL_VERSION, "request version is missing")
        if (version != PROTOCOL_VERSION) {
            return RequestResult.Rejected(ERROR_PROTOCOL_VERSION, "unsupported protocol version " + version)
        }
        val method = stringField(json, "method") ?: return RequestResult.Rejected(ERROR_METHOD_INVALID, "method is missing")
        if (!isValidToken(method, MAX_METHOD_CHARS)) {
            return RequestResult.Rejected(ERROR_METHOD_INVALID, "method must be 1.." + MAX_METHOD_CHARS + " chars without control characters")
        }
        val session = decodeSession(json)
        if (json.has("sessionId") && session == null) {
            return RequestResult.Rejected(ERROR_SESSION_ID_INVALID, "session id fields must be 1.." + MAX_SESSION_FIELD_CHARS + " chars")
        }
        val payload = when (val payloadElement = json.get("payload")) {
            null -> ""
            else -> stringElement(payloadElement) ?: return RequestResult.Rejected(ERROR_REQUEST_MALFORMED, "payload must be a string")
        }
        return RequestResult.Accepted(RpcRequest(id = id, method = method, sessionId = session, payload = payload, version = version))
    }

    /** 编码一条响应帧；payload 与 error 必须互斥。 */
    fun encodeResponseFrame(response: RpcResponse): ByteArray {
        if (!isValidToken(response.id, MAX_REQUEST_ID_CHARS)) {
            throw RpcProtocolException(ERROR_REQUEST_ID_INVALID, "response id must be 1.." + MAX_REQUEST_ID_CHARS + " chars")
        }
        if (response.version != PROTOCOL_VERSION) {
            throw RpcProtocolException(ERROR_PROTOCOL_VERSION, "unsupported protocol version " + response.version)
        }
        val error = response.error
        if (error != null && response.payload.isNotEmpty()) {
            throw RpcProtocolException(ERROR_RESPONSE_MALFORMED, "response error must not carry a payload")
        }
        if (error != null && !isValidError(error)) {
            throw RpcProtocolException(ERROR_RESPONSE_MALFORMED, "error code/message are invalid")
        }
        val json = JsonObject()
        json.addProperty("id", response.id)
        json.addProperty("version", response.version)
        json.addProperty("payload", response.payload)
        if (error != null) {
            val errorJson = JsonObject()
            errorJson.addProperty("code", error.code)
            errorJson.addProperty("message", error.message)
            json.add("error", errorJson)
        }
        return encodeFrame(json.toString().toByteArray(Charsets.UTF_8))
    }

    fun readResponseFrame(input: InputStream): ResponseResult =
        when (val frame = readFrame(input)) {
            is FrameResult.Rejected -> ResponseResult.Rejected(frame.code, frame.message)
            is FrameResult.Accepted -> decodeResponse(frame.body)
        }

    fun decodeResponse(body: ByteArray): ResponseResult {
        val json = parseObject(body) ?: return ResponseResult.Rejected(ERROR_RESPONSE_MALFORMED, "response body must be a JSON object")
        val id = stringField(json, "id") ?: return ResponseResult.Rejected(ERROR_REQUEST_ID_INVALID, "response id is missing")
        if (!isValidToken(id, MAX_REQUEST_ID_CHARS)) {
            return ResponseResult.Rejected(ERROR_REQUEST_ID_INVALID, "response id must be 1.." + MAX_REQUEST_ID_CHARS + " chars")
        }
        val version = intField(json, "version") ?: return ResponseResult.Rejected(ERROR_PROTOCOL_VERSION, "response version is missing")
        if (version != PROTOCOL_VERSION) {
            return ResponseResult.Rejected(ERROR_PROTOCOL_VERSION, "unsupported protocol version " + version)
        }
        val payload = when (val payloadElement = json.get("payload")) {
            null -> ""
            else -> stringElement(payloadElement) ?: return ResponseResult.Rejected(ERROR_RESPONSE_MALFORMED, "payload must be a string")
        }
        val error = decodeError(json.get("error"))
        if (json.has("error") && error == null) {
            return ResponseResult.Rejected(ERROR_RESPONSE_MALFORMED, "error must be an object with code and message")
        }
        if (error != null && payload.isNotEmpty()) {
            return ResponseResult.Rejected(ERROR_RESPONSE_MALFORMED, "response error must not carry a payload")
        }
        return ResponseResult.Accepted(RpcResponse(id = id, payload = payload, error = error, version = version))
    }

    // ------------------------------------------------------------------ 配置

    /** 公开配置的持久化/IPC 表示；绝不包含任何密钥原值。 */
    fun encodeAgentConfig(config: AgentConfig): String {
        val json = JsonObject()
        json.addProperty("enabled", config.enabled)
        json.addProperty("mode", config.mode.name)
        json.add("blacklist", stringArray(config.blacklist))
        json.add("whitelist", stringArray(config.whitelist))
        json.addProperty("region", config.region)
        json.addProperty("handleUnknownNumbers", config.handleUnknownNumbers)
        json.addProperty("answerDelayMs", config.answerDelayMs)
        json.addProperty("maxCallDurationMs", config.maxCallDurationMs)
        json.add("enabledPhoneAccounts", stringArray(config.enabledPhoneAccounts))
        json.add("asr", endpointJson(config.asr))
        json.add("llm", endpointJson(config.llm))
        json.add("tts", endpointJson(config.tts))
        json.addProperty("voice", config.voice)
        json.addProperty("speed", config.speed)
        json.addProperty("openingLine", config.openingLine)
        json.addProperty("prompt", config.prompt)
        json.addProperty("revision", config.revision)
        return json.toString()
    }

    /** 宽松读取（缺字段用默认值，供 schema 迁移）；类型不符时返回 null。 */
    fun decodeAgentConfig(jsonText: String): AgentConfig? {
        val json = runCatching { JsonParser.parseString(jsonText) }.getOrNull() ?: return null
        if (!json.isJsonObject) return null
        val obj = json.asJsonObject
        return try {
            AgentConfig(
                enabled = softBoolean(obj, "enabled", false),
                mode = softMode(obj, "mode", RuleMode.BLACKLIST),
                blacklist = softStringList(obj, "blacklist", emptyList()),
                whitelist = softStringList(obj, "whitelist", emptyList()),
                region = softString(obj, "region", AgentConfig.DEFAULT_REGION),
                handleUnknownNumbers = softBoolean(obj, "handleUnknownNumbers", false),
                answerDelayMs = softInt(obj, "answerDelayMs", AgentConfig.DEFAULT_ANSWER_DELAY_MS),
                maxCallDurationMs = softInt(obj, "maxCallDurationMs", AgentConfig.DEFAULT_MAX_CALL_DURATION_MS),
                enabledPhoneAccounts = softStringList(obj, "enabledPhoneAccounts", emptyList()),
                asr = softEndpoint(obj, "asr"),
                llm = softEndpoint(obj, "llm"),
                tts = softEndpoint(obj, "tts"),
                voice = softString(obj, "voice", ""),
                speed = softDouble(obj, "speed", 1.0),
                openingLine = softString(obj, "openingLine", AgentConfig.DEFAULT_OPENING_LINE),
                prompt = softString(obj, "prompt", ""),
                revision = softLong(obj, "revision", 0L),
            )
        } catch (invalid: InvalidPayload) {
            null
        }
    }

    /** 可安全返回给 UI/CLI 的脱敏视图：密钥只出现 CONFIGURED/MISSING 状态。 */
    fun encodeRedacted(redacted: RedactedConfig): String {
        val json = JsonObject()
        json.add("config", JsonParser.parseString(encodeAgentConfig(redacted.config)))
        val secrets = JsonObject()
        for (kind in EndpointKind.entries) {
            secrets.addProperty(kind.name, (redacted.secrets[kind] ?: SecretState.MISSING).name)
        }
        json.add("secrets", secrets)
        return json.toString()
    }

    /**
     * 解析 CLI 的 config-set stdin 负载：只接受固定字段表，任何未知字段、错误类型或
     * 未知密钥动作都整体拒绝（返回 null），不会“尽力应用一部分”。
     */
    fun decodeConfigWrite(payload: String): ConfigWrite? {
        val json = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        if (!json.isJsonObject) return null
        val obj = json.asJsonObject
        if (!obj.keySet().all { it == "expectedRevision" || it == "patch" }) return null
        return try {
            if (!obj.has("expectedRevision")) return null
            val revision = obj.get("expectedRevision").let { element ->
                if (element !is JsonPrimitive || !element.isNumber) return null
                val value = element.asLong
                if (value < 0L) return null
                value
            }
            val patchElement = obj.get("patch") ?: return null
            if (!patchElement.isJsonObject) return null
            ConfigWrite(revision, decodePatch(patchElement.asJsonObject))
        } catch (invalid: InvalidPayload) {
            null
        }
    }

    /** 诊断报告 JSON：只有真实观测值与“未测”状态，不输出任何“已验证”。 */
    fun encodeCapabilityReport(report: CapabilityReport): String {
        val environment = JsonObject()
        environment.addProperty("apiLevel", report.environment.apiLevel)
        environment.addProperty("romFingerprint", report.environment.romFingerprint)
        environment.addProperty("kernelVersion", report.environment.kernelVersion)
        environment.addProperty("dialerPackageVersion", report.environment.dialerPackageVersion)
        environment.addProperty("frameworkVersion", report.environment.frameworkVersion)
        environment.addProperty("bridgeVersion", report.environment.bridgeVersion)
        environment.addProperty("audioPolicyDigest", report.environment.audioPolicyDigest)
        environment.addProperty("simCarrier", report.environment.simCarrier)
        environment.addProperty("transport", report.environment.transport)
        environment.addProperty("route", report.environment.route)
        val json = JsonObject()
        json.add("environment", environment)
        json.addProperty("downlinkExtraction", report.downlinkExtraction.name)
        json.addProperty("uplinkInjection", report.uplinkInjection.name)
        json.addProperty("microphoneIsolation", report.microphoneIsolation.name)
        json.addProperty("privilegedTermination", report.privilegedTermination.name)
        json.addProperty("checkedAtMs", report.checkedAtMs)
        json.addProperty("notes", report.notes)
        return json.toString()
    }

    // ------------------------------------------------------------------ 内部

    private class InvalidPayload : RuntimeException()

    private fun parseObject(body: ByteArray): JsonObject? {
        val text = body.toString(Charsets.UTF_8)
        val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun parseObject(text: String): JsonObject? {
        val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun isValidToken(value: String, maxChars: Int): Boolean =
        value.isNotEmpty() && value.length <= maxChars && value.none { it.isISOControl() }

    private fun isValidError(error: RpcError): Boolean =
        isValidToken(error.code, MAX_ERROR_CODE_CHARS) &&
            error.message.isNotEmpty() &&
            error.message.length <= MAX_ERROR_MESSAGE_CHARS &&
            error.message.none { it.isISOControl() && it != '\n' }

    private fun stringField(json: JsonObject, name: String): String? =
        json.get(name)?.let { stringElement(it) }

    private fun stringElement(element: JsonElement): String? =
        if (element is JsonPrimitive && element.isString) element.asString else null

    private fun intField(json: JsonObject, name: String): Int? {
        val element = json.get(name) ?: return null
        if (element !is JsonPrimitive || !element.isNumber) return null
        val asDouble = element.asDouble
        val asInt = element.asInt
        return if (asDouble == asInt.toDouble()) asInt else null
    }

    private fun decodeSession(json: JsonObject): SessionId? {
        val element = json.get("sessionId") ?: return null
        if (!element.isJsonObject) return null
        val sessionJson = element.asJsonObject
        val processEpoch = stringField(sessionJson, "processEpoch") ?: return null
        val callId = stringField(sessionJson, "callId") ?: return null
        if (!isValidToken(processEpoch, MAX_SESSION_FIELD_CHARS) || !isValidToken(callId, MAX_SESSION_FIELD_CHARS)) {
            return null
        }
        return SessionId(processEpoch, callId)
    }

    private fun sessionJson(session: SessionId): JsonObject = JsonObject().apply {
        addProperty("processEpoch", session.processEpoch)
        addProperty("callId", session.callId)
    }

    private fun decodeError(element: JsonElement?): RpcError? {
        if (element == null || element.isJsonNull) return null
        if (!element.isJsonObject) return null
        val errorJson = element.asJsonObject
        val code = stringField(errorJson, "code") ?: return null
        val message = stringField(errorJson, "message") ?: return null
        val error = RpcError(code, message)
        return if (isValidError(error)) error else null
    }

    private fun endpointJson(endpoint: ApiEndpoint?): JsonElement =
        if (endpoint == null) {
            JsonNull.INSTANCE
        } else {
            JsonObject().apply {
                addProperty("baseUrl", endpoint.baseUrl)
                addProperty("model", endpoint.model)
            }
        }

    private fun stringArray(values: List<String>): JsonArray = JsonArray().apply {
        values.forEach { add(it) }
    }

    private fun softBoolean(json: JsonObject, name: String, default: Boolean): Boolean {
        val element = json.get(name) ?: return default
        if (element.isJsonNull) return default
        if (element !is JsonPrimitive || !element.isBoolean) throw InvalidPayload()
        return element.asBoolean
    }

    private fun softString(json: JsonObject, name: String, default: String): String {
        val element = json.get(name) ?: return default
        if (element.isJsonNull) return default
        if (element !is JsonPrimitive || !element.isString) throw InvalidPayload()
        return element.asString
    }

    private fun softInt(json: JsonObject, name: String, default: Int): Int {
        val element = json.get(name) ?: return default
        if (element.isJsonNull) return default
        if (element !is JsonPrimitive || !element.isNumber) throw InvalidPayload()
        val asDouble = element.asDouble
        val asInt = element.asInt
        if (asDouble != asInt.toDouble()) throw InvalidPayload()
        return asInt
    }

    private fun softLong(json: JsonObject, name: String, default: Long): Long {
        val element = json.get(name) ?: return default
        if (element.isJsonNull) return default
        if (element !is JsonPrimitive || !element.isNumber) throw InvalidPayload()
        val asDouble = element.asDouble
        val asLong = element.asLong
        if (asDouble != asLong.toDouble()) throw InvalidPayload()
        return asLong
    }

    private fun softDouble(json: JsonObject, name: String, default: Double): Double {
        val element = json.get(name) ?: return default
        if (element.isJsonNull) return default
        if (element !is JsonPrimitive || !element.isNumber) throw InvalidPayload()
        return element.asDouble
    }

    private fun softMode(json: JsonObject, name: String, default: RuleMode): RuleMode {
        val raw = softString(json, name, default.name)
        return RuleMode.entries.firstOrNull { it.name == raw } ?: throw InvalidPayload()
    }

    private fun softStringList(json: JsonObject, name: String, default: List<String>): List<String> {
        val element = json.get(name) ?: return default
        if (element.isJsonNull) return default
        if (!element.isJsonArray) throw InvalidPayload()
        return element.asJsonArray.map { item ->
            if (item !is JsonPrimitive || !item.isString) throw InvalidPayload()
            item.asString
        }
    }

    private fun softEndpoint(json: JsonObject, name: String): ApiEndpoint? {
        val element = json.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonObject) throw InvalidPayload()
        val endpointJson = element.asJsonObject
        val baseUrl = softString(endpointJson, "baseUrl", "")
        val model = softString(endpointJson, "model", "")
        return ApiEndpoint(baseUrl = baseUrl, model = model)
    }

    // ------------------------------------------------------------------ 补丁解析

    private val PATCH_KEYS = setOf(
        "enabled",
        "mode",
        "blacklist",
        "whitelist",
        "region",
        "handleUnknownNumbers",
        "answerDelayMs",
        "maxCallDurationMs",
        "enabledPhoneAccounts",
        "asr",
        "llm",
        "tts",
        "voice",
        "speed",
        "openingLine",
        "prompt",
        "secrets",
    )

    private fun decodePatch(json: JsonObject): ConfigPatch {
        if (!json.keySet().all { it in PATCH_KEYS }) throw InvalidPayload()

        val enabled = if (json.has("enabled")) strictBoolean(json, "enabled") else null
        val mode = if (json.has("mode")) {
            val raw = strictString(json, "mode")
            RuleMode.entries.firstOrNull { it.name == raw } ?: throw InvalidPayload()
        } else {
            null
        }
        val blacklist = if (json.has("blacklist")) strictStringList(json, "blacklist") else null
        val whitelist = if (json.has("whitelist")) strictStringList(json, "whitelist") else null
        val region = if (json.has("region")) strictString(json, "region") else null
        val handleUnknownNumbers = if (json.has("handleUnknownNumbers")) {
            strictBoolean(json, "handleUnknownNumbers")
        } else {
            null
        }
        val answerDelayMs = if (json.has("answerDelayMs")) strictInt(json, "answerDelayMs") else null
        val maxCallDurationMs = if (json.has("maxCallDurationMs")) strictInt(json, "maxCallDurationMs") else null
        val enabledPhoneAccounts = if (json.has("enabledPhoneAccounts")) {
            strictStringList(json, "enabledPhoneAccounts")
        } else {
            null
        }
        val asr = if (json.has("asr")) strictEndpoint(json, "asr") else null
        val llm = if (json.has("llm")) strictEndpoint(json, "llm") else null
        val tts = if (json.has("tts")) strictEndpoint(json, "tts") else null
        val voice = if (json.has("voice")) strictString(json, "voice") else null
        val speed = if (json.has("speed")) strictDouble(json, "speed") else null
        val openingLine = if (json.has("openingLine")) strictString(json, "openingLine") else null
        val prompt = if (json.has("prompt")) strictString(json, "prompt") else null
        val secrets = if (json.has("secrets")) decodeSecrets(json) else emptyMap()

        return ConfigPatch(
            enabled = enabled,
            mode = mode,
            blacklist = blacklist,
            whitelist = whitelist,
            region = region,
            handleUnknownNumbers = handleUnknownNumbers,
            answerDelayMs = answerDelayMs,
            maxCallDurationMs = maxCallDurationMs,
            enabledPhoneAccounts = enabledPhoneAccounts,
            asr = asr,
            llm = llm,
            tts = tts,
            voice = voice,
            speed = speed,
            openingLine = openingLine,
            prompt = prompt,
            secrets = secrets,
        )
    }

    private fun decodeSecrets(json: JsonObject): Map<EndpointKind, SecretUpdate> {
        val element = json.get("secrets")
        if (element == null || !element.isJsonObject) throw InvalidPayload()
        val secrets = mutableMapOf<EndpointKind, SecretUpdate>()
        for ((kindName, value) in element.asJsonObject.entrySet()) {
            val kind = EndpointKind.entries.firstOrNull { it.name == kindName } ?: throw InvalidPayload()
            if (!value.isJsonObject) throw InvalidPayload()
            val actionJson = value.asJsonObject
            val action = strictString(actionJson, "action")
            when (action) {
                "keep" -> {
                    if (actionJson.size() != 1) throw InvalidPayload()
                    secrets[kind] = SecretUpdate.Keep
                }
                "clear" -> {
                    if (actionJson.size() != 1) throw InvalidPayload()
                    secrets[kind] = SecretUpdate.Clear
                }
                "replace" -> {
                    if (actionJson.size() != 2) throw InvalidPayload()
                    secrets[kind] = SecretUpdate.Replace(strictString(actionJson, "value"))
                }
                else -> throw InvalidPayload()
            }
        }
        return secrets
    }

    private fun strictBoolean(json: JsonObject, name: String): Boolean {
        val element = json.get(name) ?: throw InvalidPayload()
        if (element !is JsonPrimitive || !element.isBoolean) throw InvalidPayload()
        return element.asBoolean
    }

    private fun strictString(json: JsonObject, name: String): String {
        val element = json.get(name) ?: throw InvalidPayload()
        if (element !is JsonPrimitive || !element.isString) throw InvalidPayload()
        return element.asString
    }

    private fun strictInt(json: JsonObject, name: String): Int {
        val element = json.get(name) ?: throw InvalidPayload()
        if (element !is JsonPrimitive || !element.isNumber) throw InvalidPayload()
        val asDouble = element.asDouble
        val asInt = element.asInt
        if (asDouble != asInt.toDouble()) throw InvalidPayload()
        return asInt
    }

    private fun strictDouble(json: JsonObject, name: String): Double {
        val element = json.get(name) ?: throw InvalidPayload()
        if (element !is JsonPrimitive || !element.isNumber) throw InvalidPayload()
        return element.asDouble
    }

    private fun strictStringList(json: JsonObject, name: String): List<String> {
        val element = json.get(name) ?: throw InvalidPayload()
        if (!element.isJsonArray) throw InvalidPayload()
        return element.asJsonArray.map { item ->
            if (item !is JsonPrimitive || !item.isString) throw InvalidPayload()
            item.asString
        }
    }

    private fun strictEndpoint(json: JsonObject, name: String): ApiEndpoint {
        val element = json.get(name) ?: throw InvalidPayload()
        if (!element.isJsonObject) throw InvalidPayload()
        val endpointJson = element.asJsonObject
        if (!endpointJson.keySet().all { it == "baseUrl" || it == "model" }) throw InvalidPayload()
        return ApiEndpoint(
            baseUrl = strictString(endpointJson, "baseUrl"),
            model = strictString(endpointJson, "model"),
        )
    }
}

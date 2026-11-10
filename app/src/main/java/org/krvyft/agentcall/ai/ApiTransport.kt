package org.krvyft.agentcall.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.port.MonotonicClock

/** 绝对单调截止时间（毫秒），由注入的 [MonotonicClock] 求剩余时间。 */
@JvmInline
value class Deadline(val atMs: Long) {
    fun remainingMs(clock: MonotonicClock): Long = atMs - clock.nowMs()
}

/** 一次 HTTP 响应的已限流正文与规范化（小写、去掉参数）内容类型。 */
data class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
)

/** 超过截止时间或网络超时。 */
class ApiTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Base URL 非 HTTPS、带凭据/查询串，或要拼接的路径会逃出配置的前缀。 */
class InsecureEndpointException(message: String) : IOException(message)

/** 重定向无法安全跟随（跨主机、降级、层数过多）。 */
class RedirectRefusedException(message: String) : IOException(message)

/** 响应正文超过 8 MiB 上限（Content-Length 或流式读取任一维度）。 */
class ResponseTooLargeException(message: String) : IOException(message)

/** 响应类型不是该端点要求的类型（例如 TTS 返回 audio/mpeg）。 */
class UnsupportedContentTypeException(message: String) : IOException(message)

/** 响应体不是合法 JSON 或缺必需字段。 */
class MalformedResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 非 2xx 响应；429/5xx 可在截止时间内重试一次，其余是终结错误。 */
class ApiStatusException(
    val status: Int,
    message: String,
) : IOException(message) {
    val retryable: Boolean get() = status == 429 || status in 500..599
}

/**
 * OpenAI-compatible HTTPS 传输。
 *
 * 安全约束（spec §10）：
 * - 只接受 HTTPS Base URL；不接受 URL 内凭据、查询串或 fragment；
 * - API 路径必须落在配置的 Base 路径前缀下，绝不允许 ../ 逃逸；
 * - 重定向只允许同主机跟随（最多 2 跳），跨主机直接拒绝且绝不携带 Authorization；
 * - 响应正文最多 8 MiB，先看 Content-Length 再对字节流设上限；
 * - 只记录方法、主机、路径与状态/字节数，绝不记录 header、请求体或响应体。
 *
 * 响应头与正文读取都受 [Deadline] 约束：整个 HTTP 调用在同一个 withTimeout 内完成，
 * 静默服务器或慢正文不能把一次请求拖过截止时间。取消会 cancel OkHttp Call（正文读取阶段
 * 同样如此），阻塞的 socket 读取被立即打断；只有本类自己的截止时间超时才映射为
 * [ApiTimeoutException]，调用方取消原样传播。
 */
class ApiTransport(
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
    httpClient: OkHttpClient = defaultClient(),
    private val logger: (String) -> Unit = {},
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) {
    private val client: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 以本传输的单调时钟生成一个截止时间。 */
    fun deadlineAfter(ms: Long): Deadline = Deadline(clock.nowMs() + ms)

    /**
     * 以 multipart 或 JSON body 发起 POST。
     *
     * @param path 相对 Base 路径的固定 API 后缀（如 audio/transcriptions），不得以 / 开头。
     * @param apiKey null/空白时不发送 Authorization（fixtures 可运行，但不是真实远程验证）。
     */
    suspend fun post(
        endpoint: ApiEndpoint,
        path: String,
        apiKey: String?,
        body: RequestBody,
        accept: String,
        deadline: Deadline,
    ): HttpResponse {
        val base = validateBaseUrl(endpoint.baseUrl)
        val url = joinPath(base, path)
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim())
        }
        val request = requestBuilder.build()
        logger("ai http " + request.method + " " + url.host + url.encodedPath)
        return execute(request, deadline, redirectsLeft = MAX_REDIRECTS)
    }

    private suspend fun execute(request: Request, deadline: Deadline, redirectsLeft: Int): HttpResponse {
        val remaining = deadline.remainingMs(clock)
        if (remaining <= 0L) throw ApiTimeoutException("deadline exceeded before the request was sent")
        val call = client.newCall(request)
        // 响应头与正文读取都必须在同一个回合截止时间内：静默服务器或慢正文不得把
        // 一个回合拖过 45 s。取消由 awaitResponse/readCapped 的 invokeOnCancellation
        // 关闭 OkHttp Call，阻塞的正文读取会被立即打断。
        //
        // 用 withTimeoutOrNull 而不是 withTimeout：它内部只吞掉“自己创建的
        // TimeoutCoroutine”抛出的超时（private 的 coroutine 归属检查），调用方取消——
        // 包括外层 withTimeout——会原样抛出，不会被改写成 ApiTimeoutException（spec §10）。
        val outcome = withTimeoutOrNull(remaining) {
            val received = awaitResponse(call)
            if (received.code in 300..399) {
                val next = received.use { followRedirectTarget(request, it, redirectsLeft) }
                execute(next, deadline, redirectsLeft - 1)
            } else {
                received.use { response ->
                    if (!response.isSuccessful) {
                        logger("ai http status " + response.code)
                        throw ApiStatusException(response.code, "HTTP " + response.code)
                    }
                    val contentType = response.header("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                    val bytes = readCapped(call, response.body)
                    logger("ai http status " + response.code + " bytes=" + bytes.size)
                    HttpResponse(response.code, contentType, bytes)
                }
            }
        }
        return outcome ?: throw ApiTimeoutException("request deadline exceeded")
    }

    private suspend fun awaitResponse(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            // 取消与 resume 之间到达的响应必须由本类关闭，否则连接不会被回收。
            val pending = AtomicReference<Response?>(null)
            // 只使用公开 API（Continuation.resume/resumeWithException）；tryResume/completeResume
            // 是 kotlinx.coroutines 内部 API，不属于稳定契约。
            continuation.invokeOnCancellation {
                call.cancel()
                pending.getAndSet(null)?.close()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (!continuation.isActive) return
                    val mapped = if (e is InterruptedIOException) {
                        ApiTimeoutException("network timeout", e)
                    } else {
                        e
                    }
                    continuation.resumeWithException(mapped)
                }

                override fun onResponse(call: Call, response: Response) {
                    pending.set(response)
                    if (!continuation.isActive) {
                        pending.getAndSet(null)?.close()
                        return
                    }
                    continuation.resume(response)
                    // 对已取消的 continuation，resume 会被忽略且不触发 invokeOnCancellation，
                    // 因此这里必须自己关闭响应。
                    if (continuation.isCancelled) {
                        pending.getAndSet(null)?.close()
                    } else {
                        pending.set(null)
                    }
                }
            })
        }

    private fun followRedirectTarget(request: Request, response: Response, redirectsLeft: Int): Request {
        if (redirectsLeft <= 0) throw RedirectRefusedException("too many redirects")
        val location = response.header("Location")
            ?: throw RedirectRefusedException("redirect without Location")
        val target = request.url.resolve(location)
            ?: throw RedirectRefusedException("unresolvable redirect target")
        if (target.scheme != "https") throw RedirectRefusedException("redirect would downgrade to " + target.scheme)
        if (target.host != request.url.host || target.port != request.url.port) {
            // 安全红线：绝不对另一个主机重放 Authorization。
            throw RedirectRefusedException("cross-host redirect refused")
        }
        val body = request.body
        if (body == null || body.isOneShot()) {
            throw RedirectRefusedException("request body cannot be replayed after a redirect")
        }
        return request.newBuilder().url(target).build()
    }

    /**
     * 读取正文并返回原始字节。
     *
     * 阻塞读取放在可取消的 continuation 上：协程取消（回合截止时间或调用方取消）会立刻
     * 关闭 OkHttp Call，从而打断阻塞的 stream.read，不依赖读取完成后才发现已取消。
     */
    private suspend fun readCapped(call: Call, body: ResponseBody?): ByteArray {
        if (body == null) return ByteArray(0)
        val declared = body.contentLength()
        if (declared > maxResponseBytes.toLong()) {
            throw ResponseTooLargeException("response exceeds " + maxResponseBytes + " bytes")
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            if (continuation.isActive) {
                READ_EXECUTOR.execute {
                    try {
                        continuation.resume(readBody(body, declared, continuation))
                    } catch (error: Throwable) {
                        // 已取消/已完成的 continuation 会忽略 resumeWithException；这里只负责退出。
                        continuation.resumeWithException(mapBodyFailure(error))
                    }
                }
            }
        }
    }

    private fun readBody(
        body: ResponseBody,
        declared: Long,
        continuation: CancellableContinuation<ByteArray>,
    ): ByteArray {
        val stream = body.byteStream()
        val out = ByteArrayOutputStream(if (declared in 0..64L * 1024L) declared.toInt() else 16 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            // 取消后即使连接还没完全关闭也不再继续读；关闭连接由 invokeOnCancellation 负责。
            if (!continuation.isActive) throw IOException("response body read was cancelled")
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxResponseBytes) {
                throw ResponseTooLargeException("response exceeds " + maxResponseBytes + " bytes")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun mapBodyFailure(error: Throwable): Throwable =
        if (error is InterruptedIOException) {
            // OkHttp 的 readTimeout/连接中断在正文读取阶段也表现为 InterruptedIOException。
            ApiTimeoutException("network timeout while reading the response", error)
        } else {
            error
        }

    private fun validateBaseUrl(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull()
            ?: throw InsecureEndpointException("base URL is not an absolute HTTP(S) URL")
        if (url.scheme != "https") throw InsecureEndpointException("base URL must use HTTPS")
        if (url.host.isBlank()) throw InsecureEndpointException("base URL has no host")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw InsecureEndpointException("base URL must not embed credentials")
        }
        if (url.query != null || url.fragment != null) {
            throw InsecureEndpointException("base URL must not contain a query or fragment")
        }
        return url
    }

    private fun joinPath(base: HttpUrl, path: String): HttpUrl {
        if (path.isBlank() || path.startsWith('/') || path.contains("..") ||
            path.contains('?') || path.contains('#') || path.contains('\\')
        ) {
            throw InsecureEndpointException("invalid API path suffix: " + path)
        }
        val baseText = base.toString().trimEnd('/')
        val candidate = (baseText + "/" + path).toHttpUrlOrNull()
            ?: throw InsecureEndpointException("invalid API path suffix: " + path)
        val prefix = if (base.encodedPath.endsWith('/')) base.encodedPath else base.encodedPath + "/"
        if (!candidate.encodedPath.startsWith(prefix)) {
            throw InsecureEndpointException("API path escapes the configured base prefix")
        }
        if (candidate.scheme != "https" || candidate.host != base.host || candidate.port != base.port) {
            throw InsecureEndpointException("API path changed the configured origin")
        }
        return candidate
    }

    companion object {
        /** spec §10：连接超时 10 秒。 */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** spec §10：整个回合 45 秒（传输层作为独立使用时的兜底）。 */
        const val TURN_TIMEOUT_MS = 45_000L

        /** spec §10：单响应上限 8 MiB。 */
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        /** 同主机重定向最多 2 跳，避免无限循环放大请求。 */
        const val MAX_REDIRECTS = 2

        const val USER_AGENT = "AgentCall/1"

        internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

        internal val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

        /**
         * 正文读取的工作线程：阻塞 read 必须放在独立线程上，取消时由 [Call.cancel] 打断。
         * Dispatchers.IO 的线程池由 kotlinx.coroutines 管理，无需感知生命周期。
         */
        private val READ_EXECUTOR = Dispatchers.IO.asExecutor()

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TURN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TURN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TURN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // spec §10 只允许“最多一次重试”，由引擎的 callWithRetry 决定；OkHttp 的
            // 自动重连重试会让一次 POST 变成多次，必须关闭。
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** application/json 或 +json 派生类型；text/event-stream 明确不算（非流式契约）。 */
internal fun isJsonContentType(contentType: String): Boolean {
    val base = contentType.substringBefore(';').trim().lowercase()
    return base == "application/json" || base.endsWith("+json")
}

/** TTS 只接受 WAV 家族；audio/mpeg 等其它格式一律拒绝。 */
internal fun isWavContentType(contentType: String): Boolean {
    val base = contentType.substringBefore(';').trim().lowercase()
    return base == "audio/wav" ||
        base == "audio/x-wav" ||
        base == "audio/wave" ||
        base == "audio/vnd.wave"
}

/**
 * 极简 JSON 读取/转义器：只为解析 OpenAI 兼容响应中的字符串字段，避免把 JSON 库引入 app。
 * 结构不合法或尾部有残留数据时抛 [MalformedResponseException]；不保留数字精度，本模块只需要字符串。
 */
internal object JsonText {

    fun parse(text: String): Any? = Parser(text).document()

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    fun string(value: Map<String, Any?>, key: String): String? = value[key] as? String

    private class Parser(private val text: String) {
        private var index = 0

        fun document(): Any? {
            val parsed = value()
            skipWhitespace()
            if (index != text.length) throw fail("trailing data")
            return parsed
        }

        private fun value(): Any? {
            skipWhitespace()
            if (index >= text.length) throw fail("unexpected end of input")
            return when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val ch = text[index]
                    if (ch == '-' || ch.isDigit()) numberValue() else throw fail("unexpected character '" + ch + "'")
                }
            }
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val out = LinkedHashMap<String, Any?>()
            if (peek() == '}') {
                index += 1
                return out
            }
            while (true) {
                skipWhitespace()
                val key = stringValue()
                skipWhitespace()
                expect(':')
                out[key] = value()
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    '}' -> return out
                    else -> throw fail("expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(): List<Any?> {
            expect('[')
            skipWhitespace()
            val out = ArrayList<Any?>()
            if (peek() == ']') {
                index += 1
                return out
            }
            while (true) {
                out += value()
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> throw fail("expected ',' or ']'")
                }
            }
        }

        private fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) throw fail("unterminated string")
                when (val ch = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) throw fail("unterminated escape")
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw fail("truncated unicode escape")
                                val hex = text.substring(index, index + 4)
                                index += 4
                                val code = hex.toIntOrNull(16) ?: throw fail("invalid unicode escape")
                                out.append(code.toChar())
                            }
                            else -> throw fail("invalid escape '" + escaped + "'")
                        }
                    }
                    else -> if (ch < ' ') throw fail("control character in string") else out.append(ch)
                }
            }
        }

        private fun numberValue(): Double {
            val start = index
            if (text[index] == '-') index += 1
            while (index < text.length) {
                val ch = text[index]
                if (ch.isDigit() || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                    index += 1
                } else {
                    break
                }
            }
            return text.substring(start, index).toDoubleOrNull() ?: throw fail("invalid number")
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!text.startsWith(word, index)) throw fail("expected '" + word + "'")
            index += word.length
            return result
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index += 1
        }

        private fun expect(expected: Char) {
            if (index >= text.length || text[index] != expected) throw fail("expected '" + expected + "'")
            index += 1
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun next(): Char = if (index < text.length) text[index++] else throw fail("unexpected end of input")

        private fun fail(message: String): MalformedResponseException =
            MalformedResponseException("malformed JSON: " + message + " at offset " + index)
    }
}

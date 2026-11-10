package org.krvyft.agentcall.ai

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 真实 TLS + MockWebServer 的 HTTP 失败路径测试：路径前缀、内容类型、8 MiB 上限、
 * 跨主机重定向不携带 Authorization、鉴权失败、429/5xx 可重试、超时与“日志不含密钥/正文”。
 *
 * 服务器使用提交在 test/resources 的自签名证书（只覆盖 127.0.0.1/localhost）；
 * 生产实现默认信任系统 CA，因此这里额外用默认客户端证明证书校验没有被关闭。
 * 这些全部是 JVM fixture 证据，不是真实远程 API 验证（无凭据、无设备）。
 */
class ApiTransportTest {

    private lateinit var server: MockWebServer
    private val tls = TlsFixture()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(tls.serverContext.socketFactory, false)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ 三类端点

    @Test
    fun transcribeUploadsMultipartWavWithBearerToken() = runBlocking {
        server.enqueue(jsonResponse(TestResources.text("ai/json/transcription.json")))
        val client = TranscriptionClient(
            transport(),
            ApiEndpoint(server.url("/v1/").toString(), "whisper-fixture"),
            "sk-test-key",
        )
        val wav = WavCodec.encode(PcmAudio(tone(200), AudioFormatSpec(16_000)))

        assertEquals("你好，请讲。", client.transcribe(wav))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/audio/transcriptions", recorded.path)
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("whisper-fixture"))
        assertTrue(body.contains("name=\"response_format\""))
    }

    @Test
    fun transcribeRejectsNonWavInputBeforeAnyRequest() = runBlocking {
        val client = TranscriptionClient(
            transport(),
            ApiEndpoint(server.url("/v1/").toString(), "whisper"),
            "sk",
        )
        expectFailure(WavFormatException::class.java) {
            client.transcribe("this is not a wav file".toByteArray())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun chatParsesNonStreamingChoicesAndSendsJson() = runBlocking {
        server.enqueue(jsonResponse(TestResources.text("ai/json/chat_completion.json")))
        val client = ChatClient(
            transport(),
            ApiEndpoint(server.url("/openai/v1").toString(), "gpt-fixture"),
            "sk-chat",
        )

        val reply = client.reply(
            listOf(
                ChatMessage(ChatMessage.ROLE_SYSTEM, "系统提示"),
                ChatMessage(ChatMessage.ROLE_USER, "你好"),
            ),
        )

        assertEquals("您好，这里是机主的 AI 助理，请讲。", reply)
        val recorded = server.takeRequest()
        assertEquals("/openai/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-chat", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"content\":\"你好\""))
        assertTrue(body.contains("\"model\":\"gpt-fixture\""))
    }

    @Test
    fun chatParsesEscapedJsonContent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"a \\\"quoted\\\" line\\nline2 \\u4f60\\u597d\"}}]}"),
        )
        val client = ChatClient(transport(), ApiEndpoint(server.url("/v1/").toString(), "m"), null)

        assertEquals("a \"quoted\" line\nline2 你好", client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi"))))
        assertNull("missing key must not send Authorization", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun chatRejectsStreamingContentType() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[]}\n\n"),
        )
        val client = ChatClient(transport(), ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        expectFailure(UnsupportedContentTypeException::class.java) {
            client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
        }
    }

    @Test
    fun chatRejectsMalformedOrEmptyChoices() = runBlocking {
        val client = ChatClient(transport(), ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        server.enqueue(jsonResponse("{\"choices\":[]}"))
        expectFailure(MalformedResponseException::class.java) {
            client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
        }
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"))
        expectFailure(MalformedResponseException::class.java) {
            client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
        }
        server.enqueue(jsonResponse("not json at all"))
        expectFailure(MalformedResponseException::class.java) {
            client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
        }
    }

    @Test
    fun synthesizePostsJsonAndDecodesWav() = runBlocking {
        val wav = WavCodec.encode(PcmAudio(tone(250, 24_000), AudioFormatSpec(24_000)))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wav)),
        )
        val client = SpeechClient(
            transport(),
            ApiEndpoint(server.url("/tts/v1/").toString(), "tts-fixture"),
            "sk-tts",
            voice = "nova",
            speed = 1.25,
        )

        val audio = client.synthesize("你好")

        assertEquals(24_000, audio.format.sampleRate)
        assertArrayEquals(wav, audio.data)
        val recorded = server.takeRequest()
        assertEquals("/tts/v1/audio/speech", recorded.path)
        assertEquals("Bearer sk-tts", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"response_format\":\"wav\""))
        assertTrue(body.contains("\"voice\":\"nova\""))
        assertTrue(body.contains("\"speed\":1.25"))
        assertTrue(body.contains("\"input\":\"你好\""))
    }

    @Test
    fun synthesizeRejectsNonWavContentType() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(ByteArray(64))),
        )
        val client = SpeechClient(transport(), ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        expectFailure(UnsupportedContentTypeException::class.java) { client.synthesize("hi") }
    }

    @Test
    fun synthesizeRejectsMalformedWavBody() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/wav")
                .setBody("RIFF-not-a-real-wave"),
        )
        val client = SpeechClient(transport(), ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        expectFailure(WavFormatException::class.java) { client.synthesize("hi") }
    }

    @Test
    fun missingApiKeyIsAllowedForFixturesButSendsNoAuthorization() = runBlocking {
        server.enqueue(jsonResponse("{\"text\":\"ok\"}"))
        val client = TranscriptionClient(
            transport(),
            ApiEndpoint(server.url("/v1/").toString(), "m"),
            apiKey = "   ",
        )
        assertEquals("ok", client.transcribe(WavCodec.encode(PcmAudio(tone(100), AudioFormatSpec(16_000)))))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // ------------------------------------------------------------------ 安全边界

    @Test
    fun crossHostRedirectIsRefusedWithoutSendingAuthorization() = runBlocking {
        val stealServer = MockWebServer()
        stealServer.useHttps(tls.serverContext.socketFactory, false)
        stealServer.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", stealServer.url("/steal").toString()),
            )
            val client = ChatClient(transport(), ApiEndpoint(server.url("/v1/").toString(), "m"), "sk-secret")

            expectFailure(RedirectRefusedException::class.java) {
                client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
            }

            val first = server.takeRequest()
            assertEquals("Bearer sk-secret", first.getHeader("Authorization"))
            assertEquals("redirect target must never be contacted", 0, stealServer.requestCount)
        } finally {
            stealServer.shutdown()
        }
    }

    @Test
    fun sameHostRedirectIsFollowedAndKeepsAuthorization() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/v1/moved"))
        server.enqueue(jsonResponse("{\"text\":\"ok\"}"))
        val client = TranscriptionClient(
            transport(),
            ApiEndpoint(server.url("/v1/").toString(), "m"),
            "sk-same-host",
        )
        assertEquals("ok", client.transcribe(WavCodec.encode(PcmAudio(tone(100), AudioFormatSpec(16_000)))))
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", first.path)
        assertEquals("/v1/moved", second.path)
        assertEquals("Bearer sk-same-host", second.getHeader("Authorization"))
    }

    @Test
    fun insecureOrAmbiguousBaseUrlsAreRejectedBeforeNetwork() = runBlocking {
        val transport = transport()
        val bodies = mutableListOf<String>()
        for (base in listOf(
            "http://127.0.0.1:1/v1/",
            "https://user:pass@example.test/v1/",
            "https://example.test/v1/?token=in-url",
            "https://example.test/v1/#frag",
            "not a url",
        )) {
            expectFailure(InsecureEndpointException::class.java) {
                transport.post(
                    ApiEndpoint(base, "m"),
                    "chat/completions",
                    "sk",
                    "{}".toRequestBody("application/json".toMediaType()),
                    "application/json",
                    transport.deadlineAfter(1_000),
                )
            }
            bodies += base
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun apiPathMayNotEscapeTheConfiguredBasePrefix() = runBlocking {
        val transport = transport()
        expectFailure(InsecureEndpointException::class.java) {
            transport.post(
                ApiEndpoint(server.url("/v1/").toString(), "m"),
                "../admin",
                "sk",
                "".toRequestBody(),
                "application/json",
                transport.deadlineAfter(1_000),
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun defaultClientRejectsTheSelfSignedFixtureCertificate() = runBlocking {
        server.enqueue(jsonResponse("{\"text\":\"must not be reached\"}"))
        val client = TranscriptionClient(
            ApiTransport(),
            ApiEndpoint(server.url("/v1/").toString(), "m"),
            "sk",
        )
        val failure = expectFailure(IOException::class.java) {
            client.transcribe(WavCodec.encode(PcmAudio(tone(100), AudioFormatSpec(16_000))))
        }
        assertTrue(
            "TLS validation must fail the handshake, got " + failure,
            failure.message?.contains("certificate", ignoreCase = true) == true ||
                failure.cause?.message?.contains("certificate", ignoreCase = true) == true ||
                failure is javax.net.ssl.SSLException,
        )
    }

    // ------------------------------------------------------------------ 状态码与上限

    @Test
    fun clientErrorsAreTerminalAndRetryableStatusesAreFlagged() = runBlocking {
        val transport = transport()
        val client = ChatClient(transport, ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        for (status in listOf(400, 401, 403, 404, 422)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"error\":\"nope\"}"))
            val failure = expectFailure(ApiStatusException::class.java) {
                client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
            }
            assertEquals(status, failure.status)
            assertFalse("status " + status + " must not be retried", failure.retryable)
        }
        for (status in listOf(429, 500, 502, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"error\":\"later\"}"))
            val failure = expectFailure(ApiStatusException::class.java) {
                client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
            }
            assertEquals(status, failure.status)
            assertTrue("status " + status + " must be retryable", failure.retryable)
        }
    }

    @Test
    fun declaredOversizedResponseIsRejected() = runBlocking {
        val oversized = "x".repeat(ApiTransport.MAX_RESPONSE_BYTES + 1)
        server.enqueue(jsonResponse(oversized))
        val client = ChatClient(transport(), ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        expectFailure(ResponseTooLargeException::class.java) {
            client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
        }
    }

    @Test
    fun chunkedOversizedResponseIsRejectedWhileReading() = runBlocking {
        val transport = transport(maxResponseBytes = 1_024)
        val client = ChatClient(transport, ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setChunkedBody("x".repeat(4_096), 256),
        )
        expectFailure(ResponseTooLargeException::class.java) {
            client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
        }
    }

    @Test
    fun expiredDeadlineSendsNoRequest() = runBlocking {
        val transport = transport()
        expectFailure(ApiTimeoutException::class.java) {
            transport.post(
                ApiEndpoint(server.url("/v1/").toString(), "m"),
                "chat/completions",
                "sk",
                "{}".toRequestBody("application/json".toMediaType()),
                "application/json",
                Deadline(MonotonicClock { 0L }.nowMs()),
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun slowResponseTimesOutWithinTheNetworkDeadline() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        val client = ChatClient(transport(timeoutMs = 300), ApiEndpoint(server.url("/v1/").toString(), "m"), "sk")
        val startedAt = System.nanoTime()
        expectFailure(ApiTimeoutException::class.java) {
            client.reply(listOf(ChatMessage(ChatMessage.ROLE_USER, "hi")))
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("slow response must fail promptly, elapsed=" + elapsedMs, elapsedMs < 4_000L)
    }

    // ------------------------------------------------------------------ 日志

    @Test
    fun logsNeverContainSecretsHeadersOrBodies() = runBlocking {
        val logs = mutableListOf<String>()
        val client = TranscriptionClient(
            transport(logger = { logs += it }),
            ApiEndpoint(server.url("/v1/").toString(), "m"),
            "sk-super-secret",
        )
        server.enqueue(jsonResponse("{\"text\":\"秘密转写内容\"}"))
        client.transcribe(WavCodec.encode(PcmAudio(tone(100), AudioFormatSpec(16_000))))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"sk-super-secret 秘密\"}"))
        expectFailure(ApiStatusException::class.java) {
            client.transcribe(WavCodec.encode(PcmAudio(tone(100), AudioFormatSpec(16_000))))
        }

        val all = logs.joinToString("\n")
        assertFalse(all, all.contains("sk-super-secret"))
        assertFalse(all, all.contains("秘密"))
        assertFalse(all, all.contains("Authorization", ignoreCase = true))
        assertFalse(all, all.contains("Bearer", ignoreCase = true))
        assertFalse("logs must not contain audio bytes", all.contains("RIFF"))
        assertTrue("expected some diagnostics, got none", logs.isNotEmpty())
    }

    // ------------------------------------------------------------------ 工具

    private fun transport(
        logger: (String) -> Unit = {},
        timeoutMs: Long = 2_000,
        maxResponseBytes: Int = ApiTransport.MAX_RESPONSE_BYTES,
    ): ApiTransport {
        val client = OkHttpClient.Builder()
            .sslSocketFactory(tls.clientContext.socketFactory, tls.trustManager)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return ApiTransport(httpClient = client, logger = logger, maxResponseBytes = maxResponseBytes)
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
}

internal suspend fun <T : Throwable> expectFailure(type: Class<T>, block: suspend () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (type.isInstance(t)) return type.cast(t)
        throw AssertionError("expected " + type.simpleName + " but got " + t, t)
    }
    throw AssertionError("expected " + type.simpleName + " but nothing was thrown")
}

internal object TestResources {
    fun bytes(name: String): ByteArray =
        checkNotNull(ApiTransportTest::class.java.classLoader?.getResourceAsStream(name)) {
            "missing test resource " + name
        }.use { it.readBytes() }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
}

/** 测试专用自签名 TLS 材料（PEM 位于 test/resources/ai/tls）。 */
private class TlsFixture {
    private val password = "agentcall-test".toCharArray()
    private val certificate: X509Certificate = loadCertificate()
    private val privateKey: PrivateKey = loadPrivateKey()

    val serverContext: SSLContext = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        store.setKeyEntry("server", privateKey, password, arrayOf(certificate))
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(store, password)
        init(keyManagers.keyManagers, null, null)
    }

    val trustManager: X509TrustManager
    val clientContext: SSLContext

    init {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        store.setCertificateEntry("fixture", certificate)
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagers.init(store)
        trustManager = trustManagers.trustManagers.filterIsInstance<X509TrustManager>().single()
        clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    private fun loadCertificate(): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(
            ByteArrayInputStream(pem("ai/tls/test-cert.pem")),
        ) as X509Certificate
    }

    private fun loadPrivateKey(): PrivateKey {
        val spec = PKCS8EncodedKeySpec(pem("ai/tls/test-key.pem"))
        for (algorithm in listOf("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec)
            } catch (ignored: Exception) {
                // 换下一个算法重试。
            }
        }
        throw IllegalStateException("test private key is not a supported PKCS#8 RSA/EC key")
    }

    private fun pem(name: String): ByteArray {
        val text = String(TestResources.bytes(name), Charsets.US_ASCII)
        val base64 = text.lines()
            .filter { line -> line.isNotBlank() && !line.startsWith("-----") }
            .joinToString(separator = "")
        return Base64.getDecoder().decode(base64)
    }
}

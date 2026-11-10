package org.krvyft.agentcall.service

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.ai.ApiKeyProvider
import org.krvyft.agentcall.ai.ApiStatusException
import org.krvyft.agentcall.ai.ApiTimeoutException
import org.krvyft.agentcall.ai.ChatMessage
import org.krvyft.agentcall.ai.MalformedResponseException
import org.krvyft.agentcall.ai.WavCodec
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PcmAudio

/**
 * 逐端点连接测试的 JVM 编排测试（Task 8 carry）。
 *
 * 用假客户端证明：未确认时零请求、每个已配置端点恰好一次、错误码真实且不泄漏密钥。
 * 这里的“通过”只代表编排正确；真实 HTTPS 请求不在 JVM 测试范围内。
 */
class EndpointConnectionTestTest {

    private class FakeClients : ConnectionTestClients {
        val calls = ArrayList<EndpointKind>()
        val keys = HashMap<EndpointKind, String?>()
        var asrWav: ByteArray? = null
        var chatMessages: List<ChatMessage>? = null
        var ttsText: String? = null
        var ttsVoice: String? = null
        var ttsSpeed: Double? = null
        var failureFor: (EndpointKind) -> Throwable? = { null }

        override suspend fun transcribe(endpoint: ApiEndpoint, apiKey: String?, wav: ByteArray): String {
            calls += EndpointKind.ASR
            keys[EndpointKind.ASR] = apiKey
            asrWav = wav
            failureFor(EndpointKind.ASR)?.let { throw it }
            return "ok"
        }

        override suspend fun chat(
            endpoint: ApiEndpoint,
            apiKey: String?,
            messages: List<ChatMessage>,
        ): String {
            calls += EndpointKind.LLM
            keys[EndpointKind.LLM] = apiKey
            chatMessages = messages
            failureFor(EndpointKind.LLM)?.let { throw it }
            return "OK"
        }

        override suspend fun synthesize(
            endpoint: ApiEndpoint,
            apiKey: String?,
            text: String,
            voice: String,
            speed: Double,
        ): PcmAudio {
            calls += EndpointKind.TTS
            keys[EndpointKind.TTS] = apiKey
            ttsText = text
            ttsVoice = voice
            ttsSpeed = speed
            failureFor(EndpointKind.TTS)?.let { throw it }
            return PcmAudio(ByteArray(320), AudioFormatSpec(sampleRate = 16_000))
        }
    }

    private val config = AgentConfig(
        enabled = true,
        asr = ApiEndpoint("https://asr.example/v1", "asr-model"),
        llm = ApiEndpoint("https://llm.example/v1", "llm-model"),
        tts = ApiEndpoint("https://tts.example/v1", "tts-model"),
        voice = "verse",
        speed = 1.25,
    )

    private fun keys(): Map<EndpointKind, String> = mapOf(
        EndpointKind.ASR to "secret-asr",
        EndpointKind.LLM to "secret-llm",
        EndpointKind.TTS to "secret-tts",
    )

    private fun runner(
        clients: FakeClients,
        logs: MutableList<String> = ArrayList(),
        config: AgentConfig = this.config,
    ): EndpointConnectionTest {
        val secrets = keys()
        return EndpointConnectionTest(
            config = config,
            apiKeys = ApiKeyProvider { kind -> secrets[kind] },
            clients = clients,
            logger = { logs += it },
        )
    }

    @Test
    fun unconfirmedTestMakesNoNetworkCall() = runBlocking {
        val clients = FakeClients()
        val logs = ArrayList<String>()
        val report = runner(clients, logs).run(confirmed = false)
        assertTrue(report.requiresConfirmation)
        assertTrue(report.results.isEmpty())
        assertTrue(clients.calls.isEmpty())
        assertTrue(logs.isEmpty())
        assertFalse(report.allPassed)
    }

    @Test
    fun eachConfiguredEndpointIsCalledExactlyOnce() = runBlocking {
        val clients = FakeClients()
        val report = runner(clients).run(confirmed = true)
        assertEquals(listOf(EndpointKind.ASR, EndpointKind.LLM, EndpointKind.TTS), clients.calls)
        assertEquals(3, report.results.size)
        assertEquals(listOf(EndpointKind.ASR, EndpointKind.LLM, EndpointKind.TTS), report.results.map { it.kind })
        assertTrue(report.results.all { it.outcome == EndpointTestOutcome.PASSED })
        assertTrue(report.allPassed)
        assertEquals(3, report.passedCount)
    }

    @Test
    fun aFailingEndpointDoesNotStopTheOthers() = runBlocking {
        val clients = FakeClients()
        clients.failureFor = { kind -> if (kind == EndpointKind.LLM) ApiStatusException(401, "HTTP 401") else null }
        val report = runner(clients).run(confirmed = true)
        assertEquals(3, clients.calls.size)
        assertEquals(EndpointTestOutcome.PASSED, report.results.first { it.kind == EndpointKind.ASR }.outcome)
        val llm = report.results.first { it.kind == EndpointKind.LLM }
        assertEquals(EndpointTestOutcome.FAILED, llm.outcome)
        assertEquals("http_401", llm.code)
        assertEquals(EndpointTestOutcome.PASSED, report.results.first { it.kind == EndpointKind.TTS }.outcome)
        assertFalse(report.allPassed)
    }

    @Test
    fun realErrorsMapToMachineReadableCodes() = runBlocking {
        val clients = FakeClients()
        clients.failureFor = { kind ->
            when (kind) {
                EndpointKind.ASR -> ApiTimeoutException("timeout")
                EndpointKind.LLM -> MalformedResponseException("bad json")
                EndpointKind.TTS -> ApiStatusException(429, "HTTP 429")
            }
        }
        val report = runner(clients).run(confirmed = true)
        assertEquals("timeout", report.results.first { it.kind == EndpointKind.ASR }.code)
        assertEquals("response_invalid", report.results.first { it.kind == EndpointKind.LLM }.code)
        assertEquals("http_429", report.results.first { it.kind == EndpointKind.TTS }.code)
    }

    @Test
    fun unconfiguredEndpointsAreNotCalled() = runBlocking {
        val clients = FakeClients()
        val partial = config.copy(
            asr = null,
            llm = ApiEndpoint("https://llm.example/v1", " "),
        )
        val report = runner(clients, config = partial).run(confirmed = true)
        assertEquals(listOf(EndpointKind.TTS), clients.calls)
        assertEquals(EndpointTestOutcome.NOT_CONFIGURED, report.results.first { it.kind == EndpointKind.ASR }.outcome)
        assertEquals(EndpointTestOutcome.NOT_CONFIGURED, report.results.first { it.kind == EndpointKind.LLM }.outcome)
        assertEquals(EndpointTestOutcome.PASSED, report.results.first { it.kind == EndpointKind.TTS }.outcome)
        assertFalse(report.allPassed)
        assertEquals(2, report.notConfiguredCount)
    }

    @Test
    fun eachEndpointReceivesOnlyItsOwnKey() = runBlocking {
        val clients = FakeClients()
        runner(clients).run(confirmed = true)
        assertEquals("secret-asr", clients.keys[EndpointKind.ASR])
        assertEquals("secret-llm", clients.keys[EndpointKind.LLM])
        assertEquals("secret-tts", clients.keys[EndpointKind.TTS])
    }

    @Test
    fun secretsNeverAppearInLogsOrReport() = runBlocking {
        val clients = FakeClients()
        val logs = ArrayList<String>()
        val report = runner(clients, logs).run(confirmed = true)
        val rendered = report.toString() + "|" + logs.joinToString("|")
        assertFalse(rendered.contains("secret-asr"))
        assertFalse(rendered.contains("secret-llm"))
        assertFalse(rendered.contains("secret-tts"))
        assertTrue(logs.all { line -> line.startsWith("endpoint_test ") })
    }

    @Test
    fun theAsrProbeIsAValidTinyWav() = runBlocking {
        val clients = FakeClients()
        runner(clients).run(confirmed = true)
        val wav = clients.asrWav
        assertTrue(wav != null && wav.isNotEmpty())
        val decoded = WavCodec.decode(wav!!)
        assertEquals(16_000, decoded.format.sampleRate)
        assertEquals(1_600, decoded.data.size / 2)
    }

    @Test
    fun theChatProbeCarriesNoCallContent() = runBlocking {
        val clients = FakeClients()
        runner(clients).run(confirmed = true)
        val messages = clients.chatMessages
        assertTrue(messages != null)
        assertEquals(2, messages!!.size)
        assertEquals(ChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals(ChatMessage.ROLE_USER, messages[1].role)
        assertTrue(messages.none { it.content.contains("13800138000") })
    }

    @Test
    fun ttsUsesTheConfiguredVoiceAndSpeed() = runBlocking {
        val clients = FakeClients()
        runner(clients).run(confirmed = true)
        assertEquals("连接测试", clients.ttsText)
        assertEquals("verse", clients.ttsVoice)
        assertEquals(1.25, clients.ttsSpeed!!, 0.0001)
    }

    @Test
    fun aCancelledTestPropagatesAndStopsLaterEndpoints() {
        val clients = FakeClients()
        clients.failureFor = { kind -> if (kind == EndpointKind.ASR) CancellationException("cancel") else null }
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { runner(clients).run(confirmed = true) }
        }
        assertEquals("cancel", thrown.message)
        assertNull(clients.chatMessages)
        assertNull(clients.ttsText)
    }
}

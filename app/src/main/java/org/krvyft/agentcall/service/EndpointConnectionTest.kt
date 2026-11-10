package org.krvyft.agentcall.service

import java.io.IOException
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import org.krvyft.agentcall.ai.ApiKeyProvider
import org.krvyft.agentcall.ai.ApiStatusException
import org.krvyft.agentcall.ai.ApiTimeoutException
import org.krvyft.agentcall.ai.ApiTransport
import org.krvyft.agentcall.ai.ChatClient
import org.krvyft.agentcall.ai.ChatMessage
import org.krvyft.agentcall.ai.InsecureEndpointException
import org.krvyft.agentcall.ai.MalformedResponseException
import org.krvyft.agentcall.ai.RedirectRefusedException
import org.krvyft.agentcall.ai.ResponseTooLargeException
import org.krvyft.agentcall.ai.SpeechClient
import org.krvyft.agentcall.ai.TranscriptionClient
import org.krvyft.agentcall.ai.UnsupportedContentTypeException
import org.krvyft.agentcall.ai.WavCodec
import org.krvyft.agentcall.ai.WavFormatException
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.ipc.BridgeClient

/** 单个端点的测试结论。 */
enum class EndpointTestOutcome {
    /** 端点完成一次真实请求并返回了合法响应。 */
    PASSED,

    /** 请求失败；[EndpointTestResult.code] 是机器可读错误码。 */
    FAILED,

    /** 端点未配置（null 或 model 为空）；没有发起请求。 */
    NOT_CONFIGURED,
}

/** 单个端点的结果；不含密钥、URL 或响应正文。 */
data class EndpointTestResult(
    val kind: EndpointKind,
    val outcome: EndpointTestOutcome,
    val code: String? = null,
) {
    val passed: Boolean get() = outcome == EndpointTestOutcome.PASSED
}

/**
 * 一次连接测试的报告。
 *
 * requiresConfirmation 为 true 表示调用方没有确认“会发起真实付费请求”，此时
 * results 必为空且没有任何网络调用。
 */
data class ConnectionTestReport(
    val requiresConfirmation: Boolean = false,
    val results: List<EndpointTestResult> = emptyList(),
) {
    val passedCount: Int get() = results.count { it.outcome == EndpointTestOutcome.PASSED }
    val failedCount: Int get() = results.count { it.outcome == EndpointTestOutcome.FAILED }
    val notConfiguredCount: Int get() = results.count { it.outcome == EndpointTestOutcome.NOT_CONFIGURED }

    /** 只有真正执行且三项全部通过才算整体通过（未配置不算通过）。 */
    val allPassed: Boolean
        get() = !requiresConfirmation &&
            results.size == EndpointKind.entries.size &&
            results.all { it.outcome == EndpointTestOutcome.PASSED }
}

/** 端点测试的可替换客户端边界；测试用假实现，生产用 [AiConnectionTestClients]。 */
interface ConnectionTestClients {
    suspend fun transcribe(endpoint: ApiEndpoint, apiKey: String?, wav: ByteArray): String

    suspend fun chat(endpoint: ApiEndpoint, apiKey: String?, messages: List<ChatMessage>): String

    suspend fun synthesize(
        endpoint: ApiEndpoint,
        apiKey: String?,
        text: String,
        voice: String,
        speed: Double,
    ): PcmAudio
}

/**
 * 生产客户端：直接复用 Task 6 的 OpenAI-compatible 客户端与 [ApiTransport]。
 *
 * 没有新的 HTTP 代码：TLS 校验、跨主机重定向不带 Authorization、8 MiB 上限、45 s 截止时间
 * 全部沿用既有传输。日志仍只写主机/路径/状态，不写 header、请求体或密钥。
 */
class AiConnectionTestClients(
    private val transport: ApiTransport = ApiTransport(),
) : ConnectionTestClients {

    override suspend fun transcribe(endpoint: ApiEndpoint, apiKey: String?, wav: ByteArray): String =
        TranscriptionClient(transport, endpoint, apiKey).transcribe(wav)

    override suspend fun chat(
        endpoint: ApiEndpoint,
        apiKey: String?,
        messages: List<ChatMessage>,
    ): String = ChatClient(transport, endpoint, apiKey).reply(messages)

    override suspend fun synthesize(
        endpoint: ApiEndpoint,
        apiKey: String?,
        text: String,
        voice: String,
        speed: Double,
    ): PcmAudio = SpeechClient(
        transport = transport,
        endpoint = endpoint,
        apiKey = apiKey,
        voice = voice,
        speed = speed,
    ).synthesize(text)
}

/**
 * 逐端点连接测试（Task 8 carry，spec §11“AI 三项端点独立连接测试 / 提示会请求远程 API”）。
 *
 * 安全与隐私约束：
 * - 绝不自动运行：confirmed=false 时直接返回“需要确认”，零网络调用；
 * - 每个已配置端点只请求一次；未配置端点不请求（不拿空 model/URL 试探）；
 * - 密钥只作为参数传给对应客户端，不进入日志、结果、UI 或异常消息；
 * - 失败返回机器码（http_<status>/timeout/...），不返回响应正文；
 * - CancellationException 原样传播，取消后不再发起后续端点。
 */
class EndpointConnectionTest(
    private val config: AgentConfig,
    private val apiKeys: ApiKeyProvider,
    private val clients: ConnectionTestClients,
    private val logger: (String) -> Unit = {},
) {

    suspend fun run(confirmed: Boolean): ConnectionTestReport {
        if (!confirmed) {
            // 没有明确确认就没有任何远程请求（结构上不可能“误跑”）。
            return ConnectionTestReport(requiresConfirmation = true)
        }
        val results = ArrayList<EndpointTestResult>(EndpointKind.entries.size)
        results += test(EndpointKind.ASR, config.asr) { endpoint, key ->
            clients.transcribe(endpoint, key, ConnectionTestProbe.tinyWav())
        }
        results += test(EndpointKind.LLM, config.llm) { endpoint, key ->
            clients.chat(endpoint, key, ConnectionTestProbe.minimalChat())
        }
        results += test(EndpointKind.TTS, config.tts) { endpoint, key ->
            clients.synthesize(
                endpoint,
                key,
                ConnectionTestProbe.SHORT_TEXT,
                config.voice.ifBlank { SpeechClient.DEFAULT_VOICE },
                config.speed,
            )
        }
        return ConnectionTestReport(results = results)
    }

    private suspend fun test(
        kind: EndpointKind,
        endpoint: ApiEndpoint?,
        call: suspend (ApiEndpoint, String?) -> Any,
    ): EndpointTestResult {
        if (endpoint == null || endpoint.model.isBlank()) {
            logger("endpoint_test kind=" + kind.name + " result=not_configured")
            return EndpointTestResult(kind, EndpointTestOutcome.NOT_CONFIGURED)
        }
        val key = apiKeys.apiKey(kind)
        return try {
            call(endpoint, key)
            logger("endpoint_test kind=" + kind.name + " result=passed")
            EndpointTestResult(kind, EndpointTestOutcome.PASSED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val code = EndpointTestErrors.codeFor(failure)
            // 只记录机器码与端点类别：绝不记录 URL、密钥或响应正文。
            logger("endpoint_test kind=" + kind.name + " result=failed code=" + code)
            EndpointTestResult(kind, EndpointTestOutcome.FAILED, code)
        }
    }
}

/** 固定探测载荷（小、确定、无个人信息）。 */
internal object ConnectionTestProbe {

    /** TTS 测试文本：短、固定，不含任何用户数据。 */
    const val SHORT_TEXT: String = "连接测试"

    private const val SAMPLE_RATE = 16_000
    private const val PROBE_MS = 100
    private const val PROBE_HZ = 440.0
    private const val AMPLITUDE = 2_000.0

    /** 100 ms、16 kHz、单声道、PCM16 的合法 WAV：既小又能被 ASR 端点真实解析。 */
    fun tinyWav(): ByteArray {
        val frames = SAMPLE_RATE * PROBE_MS / 1_000
        val pcm = ByteArray(frames * 2)
        for (index in 0 until frames) {
            val value = (AMPLITUDE * sin(2.0 * PI * PROBE_HZ * index / SAMPLE_RATE)).roundToInt().toShort()
            pcm[2 * index] = (value.toInt() and 0xFF).toByte()
            pcm[2 * index + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return WavCodec.encode(PcmAudio(pcm, AudioFormatSpec(sampleRate = SAMPLE_RATE)))
    }

    /** LLM 最小请求：一句系统约束 + 一句固定用户输入，不携带通话内容。 */
    fun minimalChat(): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.ROLE_SYSTEM, "这是一次连通性测试。"),
        ChatMessage(ChatMessage.ROLE_USER, "只回复 OK。"),
    )
}

/** 真实错误 → 固定机器码（不含 URL/状态正文）。 */
object EndpointTestErrors {

    fun codeFor(error: Throwable): String = when (error) {
        is ApiStatusException -> "http_" + error.status
        is ApiTimeoutException -> "timeout"
        is InsecureEndpointException -> "endpoint_insecure"
        is RedirectRefusedException -> "redirect_refused"
        is ResponseTooLargeException -> "response_too_large"
        is UnsupportedContentTypeException -> "content_type_unsupported"
        is MalformedResponseException -> "response_invalid"
        is WavFormatException -> "audio_format_invalid"
        is IllegalArgumentException -> "config_invalid"
        is IOException -> "network_error"
        else -> "request_failed"
    }
}

/**
 * UI 入口的密钥读取封装：密钥只在服务/API 层读取，UI 只拿到报告。
 *
 * 仍然要求显式的 confirmed，且只对每个端点读一次密钥；不存在“自动运行”的默认值。
 */
object ConnectionTestSupport {

    suspend fun run(
        client: BridgeClient,
        config: AgentConfig,
        confirmed: Boolean,
        clients: ConnectionTestClients = AiConnectionTestClients(),
        logger: (String) -> Unit = {},
    ): ConnectionTestReport {
        if (!confirmed) return ConnectionTestReport(requiresConfirmation = true)
        // 只读取已配置端点的密钥：未配置端点绝不触碰密钥存储。
        val keys = HashMap<EndpointKind, String?>()
        for (kind in EndpointKind.entries) {
            val endpoint = when (kind) {
                EndpointKind.ASR -> config.asr
                EndpointKind.LLM -> config.llm
                EndpointKind.TTS -> config.tts
            }
            if (endpoint != null && endpoint.model.isNotBlank()) {
                keys[kind] = client.readSecret(kind)
            }
        }
        return EndpointConnectionTest(
            config = config,
            apiKeys = ApiKeyProvider { kind -> keys[kind] },
            clients = clients,
            logger = logger,
        ).run(confirmed = true)
    }
}

package org.krvyft.agentcall.ai

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.ConversationPort
import org.krvyft.agentcall.core.port.MonotonicClock

/** 端点密钥读取端口。密钥不经 AgentConfig/日志/IPC，只在装配客户端时短暂读取。 */
fun interface ApiKeyProvider {
    fun apiKey(kind: EndpointKind): String?
}

/** 一个语音回合在 45 秒截止时间前没有完成。 */
class VoiceTurnTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 轮次式语音回合引擎（spec §10）：VAD 分句 → ASR → LLM → TTS → 上行播放。
 *
 * 资源与并发约束：
 * - 所有取消来自调用者的 CoroutineScope（不使用 GlobalScope）；CancellationException 原样传播，
 *   取消时在 NonCancellable 中关闭音频会话，取消后不再有 in-flight 结果写上行；
 * - 播放期间关闭新回合触发（accepting=false），播放后再保留 300 ms 回声残留窗口，
 *   期间下行只丢弃不缓冲，因此不承诺也不伪造双工打断；
 * - 每个回合 45 秒（含播放），ASR/LLM/TTS 的 429/5xx 在截止时间内最多重试一次；
 * - 上下文最多 8 轮，每次 run() 独立（通话结束即清空），回复本地截断到 300 字；
 * - 端点不齐（例如只有 chat-compatible 服务）时直接返回，不写任何上行音频。
 */
class ConversationEngine(
    private val transcriber: Transcriber,
    private val chat: ChatModel,
    private val speech: SpeechSynthesizer,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
    private val turnTimeoutMs: Long = TURN_TIMEOUT_MS,
    private val maxContextRounds: Int = MAX_CONTEXT_ROUNDS,
    private val maxReplyChars: Int = MAX_REPLY_CHARS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val logger: (String) -> Unit = {},
) : ConversationPort {

    override suspend fun run(id: SessionId, audio: AudioSession, config: AgentConfig) {
        if (!isVoiceCapable(config)) {
            logger("ai voice endpoints are incomplete; turn engine not started")
            return
        }
        val vad = VoiceActivityDetector()
        val accepting = AtomicBoolean(false)
        val segments = Channel<PcmAudio>(capacity = SEGMENT_QUEUE_CAPACITY)
        // 每通电话独立：run() 返回即丢弃，下一次通话绝不带上一通上下文。
        val context = mutableListOf<ChatMessage>()
        try {
            coroutineScope {
                val collector = launch {
                    try {
                        audio.downlink.collect { chunk ->
                            if (!accepting.get()) return@collect
                            for (segment in vad.accept(chunk)) {
                                if (!segments.trySend(segment).isSuccess) {
                                    logger("ai vad segment queue full; segment dropped")
                                }
                            }
                        }
                    } finally {
                        segments.close()
                    }
                }
                try {
                    if (config.openingLine.isNotBlank()) {
                        playGreeting(audio, config.openingLine)
                        // 播放结束后的回声残留窗口：只消费、丢弃，绝不开启新回合。
                        delay(ECHO_GUARD_MS)
                    }
                    vad.reset()
                    accepting.set(true)
                    for (segment in segments) {
                        accepting.set(false)
                        vad.reset()
                        try {
                            handleTurn(segment, audio, config, context)
                        } finally {
                            vad.reset()
                            accepting.set(true)
                        }
                    }
                } finally {
                    accepting.set(false)
                    collector.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { audio.stop() }
            }
        }
    }

    private suspend fun playGreeting(audio: AudioSession, openingLine: String) {
        val deadline = Deadline(clock.nowMs() + turnTimeoutMs)
        val pcm = try {
            withTimeout(turnTimeoutMs) {
                callWithRetry("tts-greeting", deadline) { speech.synthesize(openingLine) }
            }
        } catch (error: TimeoutCancellationException) {
            throw VoiceTurnTimeoutException("greeting timed out after " + turnTimeoutMs + " ms", error)
        }
        currentCoroutineContext().ensureActive()
        audio.writeUplink(pcm)
    }

    private suspend fun handleTurn(
        segment: PcmAudio,
        audio: AudioSession,
        config: AgentConfig,
        context: MutableList<ChatMessage>,
    ) {
        val deadline = Deadline(clock.nowMs() + turnTimeoutMs)
        try {
            withTimeout(turnTimeoutMs) {
                val wav = WavCodec.encode(segment)
                val transcript = callWithRetry("asr", deadline) {
                    transcriber.transcribe(wav)
                }.trim()
                // 迟到的结果不得推进到下一阶段：取消后既不请求 TTS，也不写上行。
                currentCoroutineContext().ensureActive()
                if (transcript.isEmpty()) return@withTimeout
                context += ChatMessage(ChatMessage.ROLE_USER, transcript)
                trimContext(context)

                val reply = callWithRetry("llm", deadline) {
                    chat.reply(buildMessages(config, context))
                }
                currentCoroutineContext().ensureActive()
                val trimmed = trimReply(reply)
                if (trimmed.isEmpty()) return@withTimeout
                context += ChatMessage(ChatMessage.ROLE_ASSISTANT, trimmed)
                trimContext(context)

                val pcm = callWithRetry("tts", deadline) { speech.synthesize(trimmed) }
                // 取消后到达的结果绝不写上行：写之前再检查一次协程仍存活。
                currentCoroutineContext().ensureActive()
                if (clock.nowMs() >= deadline.atMs) {
                    throw VoiceTurnTimeoutException("turn deadline reached before playback")
                }
                audio.writeUplink(pcm)
                delay(ECHO_GUARD_MS)
            }
        } catch (error: TimeoutCancellationException) {
            throw VoiceTurnTimeoutException("turn timed out after " + turnTimeoutMs + " ms", error)
        }
    }

    private suspend fun <T> callWithRetry(
        operation: String,
        deadline: Deadline,
        block: suspend () -> T,
    ): T {
        val retryable: ApiStatusException = try {
            return block()
        } catch (error: ApiStatusException) {
            if (!error.retryable) throw error
            error
        }
        logger("ai retry " + operation + " status=" + retryable.status)
        val remaining = deadline.atMs - clock.nowMs()
        if (remaining <= 0L) throw ApiTimeoutException("no time left to retry " + operation)
        if (remaining > retryDelayMs) delay(retryDelayMs)
        return block()
    }

    private fun buildMessages(config: AgentConfig, context: List<ChatMessage>): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(context.size + 1)
        messages += ChatMessage(ChatMessage.ROLE_SYSTEM, systemPrompt(config))
        messages += context
        return messages
    }

    private fun systemPrompt(config: AgentConfig): String =
        config.prompt.trim().ifEmpty { ChatClient.DEFAULT_SYSTEM_PROMPT }

    private fun trimContext(context: MutableList<ChatMessage>) {
        while (context.size > maxContextRounds * 2) {
            context.removeAt(0)
            if (context.isNotEmpty()) context.removeAt(0)
        }
    }

    private fun trimReply(reply: String): String {
        val trimmed = reply.trim()
        if (trimmed.length <= maxReplyChars) return trimmed
        return trimmed.take(maxReplyChars).trimEnd()
    }

    companion object {
        /** spec §10：整个回合 45 秒。 */
        const val TURN_TIMEOUT_MS = 45_000L

        /** spec §10：最多 8 轮上下文。 */
        const val MAX_CONTEXT_ROUNDS = 8

        /** spec §10：回复 300 字。 */
        const val MAX_REPLY_CHARS = 300

        /** 播放结束后的回声残留丢弃窗口。 */
        const val ECHO_GUARD_MS = 300L

        /** 429/5xx 单次重试前的短退避；仍受回合截止时间约束。 */
        const val RETRY_DELAY_MS = 250L

        /** 分句队列容量；满时丢弃新分句而不是无限缓冲。 */
        const val SEGMENT_QUEUE_CAPACITY = 8

        /** 三项端点齐备且都是 HTTPS 才具备语音能力；只有聊天兼容的服务不算。 */
        fun isVoiceCapable(config: AgentConfig): Boolean =
            isConfiguredEndpoint(config.asr) &&
                isConfiguredEndpoint(config.llm) &&
                isConfiguredEndpoint(config.tts)

        private fun isConfiguredEndpoint(endpoint: ApiEndpoint?): Boolean =
            endpoint != null &&
                endpoint.baseUrl.trim().startsWith("https://") &&
                endpoint.model.isNotBlank()

        /**
         * 从公开配置与密钥端口装配真实引擎；任一语音端点缺失或非 HTTPS 时返回 null，
         * 由协调器保持人工处理（不得用聊天服务替代 ASR/TTS）。
         */
        fun create(
            config: AgentConfig,
            apiKeys: ApiKeyProvider,
            transport: ApiTransport = ApiTransport(),
            clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
            logger: (String) -> Unit = {},
        ): ConversationEngine? {
            if (!isVoiceCapable(config)) return null
            val asr = config.asr ?: return null
            val llm = config.llm ?: return null
            val tts = config.tts ?: return null
            return ConversationEngine(
                transcriber = TranscriptionClient(transport, asr, apiKeys.apiKey(EndpointKind.ASR)),
                chat = ChatClient(transport, llm, apiKeys.apiKey(EndpointKind.LLM)),
                speech = SpeechClient(
                    transport = transport,
                    endpoint = tts,
                    apiKey = apiKeys.apiKey(EndpointKind.TTS),
                    voice = config.voice.ifBlank { SpeechClient.DEFAULT_VOICE },
                    speed = config.speed,
                ),
                clock = clock,
                logger = logger,
            )
        }
    }
}

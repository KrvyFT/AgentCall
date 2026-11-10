package org.krvyft.agentcall.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 语音回合引擎与 VAD 的 JVM 测试（kotlinx-coroutines-test 虚拟调度器）。
 *
 * 覆盖：端点齐备才具备语音能力、默认中文开场白、ASR→LLM→TTS→上行链路、300 字截断、
 * 8 轮上下文与通话结束清空、429/5xx 截止时间内只重试一次、45 s 回合超时、
 * 播放期间不触发新回合、取消后 in-flight 结果绝不写上行（writeUplink 次数为 0）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEngineTest {

    private val session = SessionId(processEpoch = "epoch-fixture", callId = "call-fixture")

    // ------------------------------------------------------------------ 能力与开场白

    @Test
    fun voiceCapabilityRequiresAllThreeHttpsEndpoints() {
        assertFalse(ConversationEngine.isVoiceCapable(AgentConfig()))
        assertFalse(
            "chat-only service is not voice capable",
            ConversationEngine.isVoiceCapable(config(asr = null, tts = null)),
        )
        assertFalse(ConversationEngine.isVoiceCapable(config(tts = null)))
        assertFalse(ConversationEngine.isVoiceCapable(config(asr = ApiEndpoint("http://asr.test/v1/", "m"))))
        assertFalse(ConversationEngine.isVoiceCapable(config(llm = ApiEndpoint("https://llm.test/v1/", " "))))
        assertTrue(ConversationEngine.isVoiceCapable(config()))
    }

    @Test
    fun engineWithoutVoiceEndpointsWritesNoAudio() = runTest {
        val audio = FakeAudioSession()
        engine().run(session, audio, AgentConfig())
        assertEquals(0, audio.writeCount)
        assertEquals(0, audio.stopCount)
    }

    @Test
    fun defaultGreetingIsSynthesizedAndPlayed() = runTest {
        val audio = FakeAudioSession()
        val speech = FakeSpeech()
        val job = launch {
            engine(speech = speech).run(
                session,
                audio,
                config(openingLine = AgentConfig.DEFAULT_OPENING_LINE),
            )
        }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(AgentConfig.DEFAULT_OPENING_LINE), speech.texts)
        assertEquals(1, audio.writeCount)
        job.cancel()
    }

    @Test
    fun configuredPromptIsSentAsSystemMessage() = runTest {
        val audio = FakeAudioSession()
        val chat = FakeChat()
        val job = launch {
            engine(chat = chat).run(session, audio, config(prompt = "只回答是否。"))
        }
        audio.feedSegment()
        testScheduler.advanceUntilIdle()

        assertEquals(ChatMessage.ROLE_SYSTEM, chat.requests.single().first().role)
        assertEquals("只回答是否。", chat.requests.single().first().content)
        job.cancel()
    }

    // ------------------------------------------------------------------ 回合链路

    @Test
    fun turnPipesAsrLlmTtsAndWritesAudio() = runTest {
        val audio = FakeAudioSession()
        val transcriber = FakeTranscriber()
        val chat = FakeChat()
        val speech = FakeSpeech()
        val job = launch { engine(transcriber, chat, speech).run(session, audio, config()) }
        audio.feedSegment()
        testScheduler.advanceUntilIdle()

        assertEquals(1, transcriber.calls)
        val wav = WavCodec.decode(transcriber.wavs.single())
        assertEquals(16_000, wav.format.sampleRate)
        assertEquals(1_800L, wav.data.size / 2L * 1_000L / 16_000L)

        assertEquals(1, chat.calls)
        val messages = chat.requests.single()
        assertEquals(ChatMessage.ROLE_SYSTEM, messages.first().role)
        assertEquals(ChatMessage.ROLE_USER, messages[1].role)
        assertEquals("你好", messages[1].content)

        assertEquals(listOf(chat.result), speech.texts)
        assertEquals(1, audio.writeCount)
        assertArrayEquals(speech.result.data, audio.written.single().data)
        job.cancel()
    }

    @Test
    fun replyIsTrimmedToThreeHundredCharacters() = runTest {
        val audio = FakeAudioSession()
        val chat = FakeChat().apply { result = "字".repeat(400) }
        val speech = FakeSpeech()
        val job = launch { engine(chat = chat, speech = speech).run(session, audio, config()) }
        audio.feedSegment()
        testScheduler.advanceUntilIdle()

        assertEquals(300, speech.texts.single().length)
        audio.feedSegment()
        testScheduler.advanceUntilIdle()
        assertEquals(300, chat.requests.last()[2].content.length)
        job.cancel()
    }

    @Test
    fun contextIsLimitedToEightRounds() = runTest {
        val audio = FakeAudioSession()
        val transcriber = FakeTranscriber().apply { resultFactory = { n -> "第" + n + "轮" } }
        val chat = FakeChat()
        val job = launch { engine(transcriber = transcriber, chat = chat).run(session, audio, config()) }
        repeat(9) {
            audio.feedSegment()
            testScheduler.advanceUntilIdle()
        }

        assertEquals(9, chat.calls)
        val last = chat.requests.last()
        assertEquals("system + 7 full rounds + current user", 16, last.size)
        assertEquals("第2轮", last[1].content)
        assertEquals("第9轮", last.last().content)
        job.cancel()
    }

    @Test
    fun contextIsResetBetweenCalls() = runTest {
        val transcriber = FakeTranscriber()
        val chat = FakeChat()
        val engine = engine(transcriber = transcriber, chat = chat)

        val firstAudio = FakeAudioSession()
        firstAudio.feedSegment()
        val first = launch { engine.run(session, firstAudio, config()) }
        testScheduler.advanceUntilIdle()
        assertEquals(1, chat.calls)
        first.cancelAndJoin()

        transcriber.resultFactory = { "第二通" }
        val secondAudio = FakeAudioSession()
        secondAudio.feedSegment()
        val second = launch { engine.run(session, secondAudio, config()) }
        testScheduler.advanceUntilIdle()

        val secondRequest = chat.requests.last()
        assertEquals("previous call context must be cleared", 2, secondRequest.size)
        assertEquals("第二通", secondRequest[1].content)
        second.cancel()
    }

    // ------------------------------------------------------------------ 错误、重试与超时

    @Test
    fun retryableFailureIsRetriedOnceWithinTheDeadline() = runTest {
        val audio = FakeAudioSession()
        val transcriber = FakeTranscriber().apply {
            failures.add(ApiStatusException(429, "rate limited"))
        }
        val job = launch { engine(transcriber = transcriber).run(session, audio, config()) }
        audio.feedSegment()
        testScheduler.advanceUntilIdle()

        assertEquals("429 must be retried exactly once", 2, transcriber.calls)
        assertEquals(1, audio.writeCount)
        job.cancel()
    }

    @Test
    fun retryStopsWhenTheDeadlineHasNoTimeLeft() = runTest {
        val clock = FakeClock()
        val audio = FakeAudioSession()
        val transcriber = FakeTranscriber().apply {
            failures.add(ApiStatusException(500, "server error"))
            onCall = { clock.now = 50_000L }
        }
        audio.feedSegment()

        expectFailure(ApiTimeoutException::class.java) {
            engine(transcriber = transcriber, clock = clock).run(session, audio, config())
        }

        assertEquals("no second attempt after the deadline", 1, transcriber.calls)
        assertEquals(0, audio.writeCount)
    }

    @Test
    fun turnIsAbortedAtFortyFiveSeconds() = runTest {
        val audio = FakeAudioSession()
        val chat = FakeChat().apply { delayMs = 60_000L }
        audio.feedSegment()

        val failure = expectFailure(VoiceTurnTimeoutException::class.java) {
            engine(chat = chat).run(session, audio, config())
        }

        assertTrue(failure.message!!.contains("45"))
        assertEquals(0, audio.writeCount)
        assertEquals(1, audio.stopCount)
    }

    @Test
    fun alreadyPlayedAudioIsNeverReplayedWhenALaterStageFails() = runTest {
        val audio = FakeAudioSession()
        val transcriber = FakeTranscriber()
        // 第一回合完整播放；第二回合在 TTS 阶段失败两次（不产生新的上行音频，
        // 也绝不重播第一回合已播放过的语音）。
        val speech = FakeSpeech().apply { failFromCall = 2 }
        audio.feedSegment()
        audio.feedSegment()

        expectFailure(ApiStatusException::class.java) {
            engine(transcriber = transcriber, speech = speech).run(session, audio, config())
        }

        assertEquals("one successful synthesis for the first turn plus one failed retry pair", 3, speech.calls)
        assertEquals("already played audio is written once, never replayed", 1, audio.writeCount)
    }

    // ------------------------------------------------------------------ 取消与回声

    @Test
    fun cancelledTurnNeverWritesAudio() = runTest {
        val audio = FakeAudioSession()
        val transcriber = FakeTranscriber()
        val chat = FakeChat().apply {
            delayMs = 5_000L
            lateNonCancellable = true
        }
        val speech = FakeSpeech()
        audio.feedSegment()
        val job = launch { engine(transcriber, chat, speech).run(session, audio, config()) }

        testScheduler.advanceTimeBy(1_000L)
        assertEquals("turn must have reached the model call", 1, chat.calls)
        job.cancel()
        testScheduler.advanceTimeBy(10_000L)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals("a late model result must never write uplink", 0, audio.writeCount)
        assertEquals("cancellation must close the audio session", 1, audio.stopCount)
        assertEquals(0, speech.calls)
    }

    @Test
    fun speechDuringPlaybackDoesNotStartATurn() = runTest {
        val audio = FakeAudioSession()
        val transcriber = FakeTranscriber()
        val speech = FakeSpeech().apply { delayMs = 2_000L }
        audio.feedSegment()
        val job = launch {
            engine(transcriber = transcriber, speech = speech).run(
                session,
                audio,
                config(openingLine = "您好，我是助理。"),
            )
        }

        testScheduler.advanceTimeBy(1_000L)
        assertEquals("greeting playback has not finished yet", 0, audio.writeCount)
        testScheduler.advanceTimeBy(2_000L)
        assertEquals(1, audio.writeCount)
        assertEquals("input consumed during playback is echo residue", 0, transcriber.calls)

        audio.feedSegment()
        testScheduler.advanceUntilIdle()
        assertEquals(1, transcriber.calls)
        job.cancel()
    }

    // ------------------------------------------------------------------ 基础设施

    private fun engine(
        transcriber: Transcriber = FakeTranscriber(),
        chat: ChatModel = FakeChat(),
        speech: SpeechSynthesizer = FakeSpeech(),
        clock: MonotonicClock = FakeClock(),
        turnTimeoutMs: Long = ConversationEngine.TURN_TIMEOUT_MS,
        logger: (String) -> Unit = {},
    ): ConversationEngine = ConversationEngine(
        transcriber = transcriber,
        chat = chat,
        speech = speech,
        clock = clock,
        turnTimeoutMs = turnTimeoutMs,
        logger = logger,
    )

    private fun config(
        asr: ApiEndpoint? = ApiEndpoint("https://asr.example/v1/", "asr-model"),
        llm: ApiEndpoint? = ApiEndpoint("https://llm.example/v1/", "llm-model"),
        tts: ApiEndpoint? = ApiEndpoint("https://tts.example/v1/", "tts-model"),
        openingLine: String = "",
        prompt: String = "",
    ): AgentConfig = AgentConfig(
        enabled = true,
        asr = asr,
        llm = llm,
        tts = tts,
        openingLine = openingLine,
        prompt = prompt,
    )

    private class FakeClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class FakeTranscriber : Transcriber {
        var calls = 0
        var resultFactory: (Int) -> String = { "你好" }
        var delayMs = 0L
        var lateNonCancellable = false
        var onCall: (Int) -> Unit = {}
        val wavs = mutableListOf<ByteArray>()
        val failures = ArrayDeque<Throwable>()

        override suspend fun transcribe(wav: ByteArray): String {
            calls += 1
            wavs += wav
            onCall(calls)
            failures.removeFirstOrNull()?.let { throw it }
            if (delayMs > 0L) {
                if (lateNonCancellable) {
                    withContext(NonCancellable) { delay(delayMs) }
                } else {
                    delay(delayMs)
                }
            }
            return resultFactory(calls)
        }
    }

    private class FakeChat : ChatModel {
        var calls = 0
        var result = "收到"
        var delayMs = 0L
        var lateNonCancellable = false
        val requests = mutableListOf<List<ChatMessage>>()
        val failures = ArrayDeque<Throwable>()

        override suspend fun reply(messages: List<ChatMessage>): String {
            calls += 1
            requests += messages.toList()
            failures.removeFirstOrNull()?.let { throw it }
            if (delayMs > 0L) {
                if (lateNonCancellable) {
                    withContext(NonCancellable) { delay(delayMs) }
                } else {
                    delay(delayMs)
                }
            }
            return result
        }
    }

    private class FakeSpeech : SpeechSynthesizer {
        var calls = 0
        var delayMs = 0L
        var failFromCall = Int.MAX_VALUE
        val texts = mutableListOf<String>()
        val failures = ArrayDeque<Throwable>()
        var result = PcmAudio(tone(50), AudioFormatSpec(16_000))

        override suspend fun synthesize(text: String): PcmAudio {
            calls += 1
            texts += text
            failures.removeFirstOrNull()?.let { throw it }
            if (calls >= failFromCall) throw ApiStatusException(500, "later TTS failure")
            if (delayMs > 0L) delay(delayMs)
            return result
        }
    }

    private class FakeAudioSession : AudioSession {
        val chunks = Channel<PcmChunk>(Channel.UNLIMITED)
        override val downlink: Flow<PcmChunk> = chunks.receiveAsFlow()
        val written = mutableListOf<PcmAudio>()
        var writeCount = 0
        var stopCount = 0

        override suspend fun writeUplink(audio: PcmAudio) {
            writeCount += 1
            written += audio
        }

        override suspend fun stop() {
            stopCount += 1
        }
    }

    private fun FakeAudioSession.feedSegment() {
        chunked(tone(1_000) + silence(800)).forEach { chunks.trySend(it) }
    }
}

/**
 * VAD 合成音频边界：800 ms 静音、15 s 单段上限、最短语音与 flush/reset。
 * 输入是手工生成的 440 Hz 正弦与零值 PCM16，不读取实现内部计数器。
 */
class VoiceActivityDetectorTest {

    @Test
    fun silenceAloneNeverEmitsASegment() {
        val vad = VoiceActivityDetector()
        val emitted = chunked(silence(2_000)).flatMap { vad.accept(it) }
        assertTrue(emitted.isEmpty())
        assertNull(vad.flush())
    }

    @Test
    fun segmentEndsAtExactlyEightHundredMillisecondsOfSilence() {
        val vad = VoiceActivityDetector()
        val before = chunked(tone(1_000) + silence(790)).flatMap { vad.accept(it) }
        assertTrue("no segment before 800 ms of silence", before.isEmpty())

        val boundary = vad.accept(chunked(silence(10)).single())

        assertEquals(1, boundary.size)
        assertEquals(1_800L, durationMs(boundary.single()))
    }

    @Test
    fun continuousSpeechIsCappedAtFifteenSeconds() {
        val vad = VoiceActivityDetector()
        val chunks = chunked(tone(15_000))
        val emissions = mutableListOf<PcmAudio>()
        for ((index, chunk) in chunks.withIndex()) {
            val out = vad.accept(chunk)
            if (index < chunks.size - 1) {
                assertTrue("must not emit before 15 s (chunk " + index + ")", out.isEmpty())
            }
            emissions += out
        }
        assertEquals(1, emissions.size)
        assertEquals(15_000L, durationMs(emissions.single()))
    }

    @Test
    fun shortBlipBelowMinimumSpeechIsDropped() {
        val vad = VoiceActivityDetector(minSpeechMs = 200)
        val emitted = chunked(tone(100) + silence(800)).flatMap { vad.accept(it) }
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun flushEmitsPendingSpeechOnlyOnce() {
        val vad = VoiceActivityDetector()
        assertTrue(chunked(tone(300)).flatMap { vad.accept(it) }.isEmpty())
        val flushed = vad.flush()
        assertEquals(300L, durationMs(flushed!!))
        assertNull(vad.flush())
    }

    @Test
    fun resetDropsThePendingSegment() {
        val vad = VoiceActivityDetector()
        chunked(tone(300)).forEach { vad.accept(it) }
        vad.reset()
        assertTrue(chunked(silence(1_000)).flatMap { vad.accept(it) }.isEmpty())
        assertNull(vad.flush())
    }

    private fun durationMs(audio: PcmAudio): Long =
        audio.data.size / 2L * 1_000L / audio.format.sampleRate
}

private fun chunked(bytes: ByteArray, chunkMs: Int = 10, sampleRate: Int = 16_000): List<PcmChunk> {
    val chunkBytes = sampleRate / 1_000 * chunkMs * 2
    val out = mutableListOf<PcmChunk>()
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + chunkBytes, bytes.size)
        out += PcmChunk(bytes.copyOfRange(offset, end), AudioFormatSpec(sampleRate))
        offset = end
    }
    return out
}


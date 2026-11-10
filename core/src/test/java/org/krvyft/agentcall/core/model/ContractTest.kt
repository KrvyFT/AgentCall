package org.krvyft.agentcall.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享契约的行为约定。此测试先于契约类型存在，作为任务 1 的红灯证据：
 * 缺少类型时编译失败即视为“测试先失败”。
 */
class ContractTest {

    private fun snapshot(
        processEpoch: String,
        callId: String,
        number: String? = "+8613800138000",
    ): CallSnapshot = CallSnapshot(
        id = SessionId(processEpoch = processEpoch, callId = callId),
        number = number,
        state = CallState.RINGING,
        direction = CallDirection.INCOMING,
        presentation = Presentation.ALLOWED,
        phoneAccount = "sim-slot-1",
        isEmergency = false,
        isConference = false,
        activeCallCount = 1,
    )

    @Test
    fun sameNumberDifferentCallIdsAreDistinctSessions() {
        val first = snapshot(processEpoch = "epoch-1", callId = "call-a")
        val second = snapshot(processEpoch = "epoch-1", callId = "call-b")

        assertEquals(first.number, second.number)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun sameCallIdInAnotherProcessEpochIsDistinctSession() {
        val beforeRestart = snapshot(processEpoch = "epoch-1", callId = "call-a")
        val afterRestart = snapshot(processEpoch = "epoch-2", callId = "call-a")

        assertEquals(beforeRestart.id.callId, afterRestart.id.callId)
        assertNotEquals(beforeRestart.id, afterRestart.id)
    }

    @Test
    fun snapshotCarriesTelecomFacts() {
        val snapshot = snapshot(processEpoch = "epoch-1", callId = "call-a")

        assertEquals(CallState.RINGING, snapshot.state)
        assertEquals(CallDirection.INCOMING, snapshot.direction)
        assertEquals(Presentation.ALLOWED, snapshot.presentation)
        assertEquals("sim-slot-1", snapshot.phoneAccount)
        assertEquals(1, snapshot.activeCallCount)
        assertNull(snapshot(processEpoch = "epoch-1", callId = "call-a", number = null).number)
    }

    @Test
    fun secretUpdateVariantsStayDistinct() {
        val keep: SecretUpdate = SecretUpdate.Keep
        val clear: SecretUpdate = SecretUpdate.Clear

        assertNotEquals(keep, clear)
        assertNotEquals(keep, SecretUpdate.Replace("k-1"))
        assertNotEquals(clear, SecretUpdate.Replace("k-1"))
        // Replace 覆盖了 toString，但 equals 仍由 data class 生成。
        assertEquals(SecretUpdate.Replace("k-1"), SecretUpdate.Replace("k-1"))
        assertNotEquals(SecretUpdate.Replace("k-1"), SecretUpdate.Replace("k-2"))
    }

    @Test
    fun secretUpdateReplaceNeverPrintsItsValue() {
        val secret = "sk-live-0123456789abcdef"
        val replace: SecretUpdate = SecretUpdate.Replace(secret)

        assertFalse(replace.toString().contains(secret))
        assertEquals("Replace(value=<redacted>)", replace.toString())

        // ConfigPatch 是 data class，默认 toString 会拼接成员；Replace 的覆盖必须同时遮蔽这条路径。
        val patch = ConfigPatch(secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace(secret)))
        assertFalse(patch.toString().contains(secret))

        // Keep/Clear 没有密钥可泄露，保持可读即可。
        assertEquals("Keep", SecretUpdate.Keep.toString())
        assertEquals("Clear", SecretUpdate.Clear.toString())
    }

    @Test
    fun protocolVersionDefaultsToOne() {
        assertEquals(1, PROTOCOL_VERSION)
        assertEquals(1, RpcRequest(id = "req-1", method = "status").version)
        assertEquals(1, RpcResponse(id = "req-1").version)
        assertEquals(
            1,
            PeerIdentity(uid = 1000, role = PeerRole.APP).protocolVersion,
        )
    }

    @Test
    fun peerRolesCoverThreeDevices() {
        assertEquals(
            setOf("APP", "HOOK", "CLI_ROOT"),
            PeerRole.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun stateChangedEventCarriesTheCallSnapshot() {
        val snapshot = snapshot(processEpoch = "epoch-1", callId = "call-a")

        val event = SessionEvent.StateChanged(snapshot)

        assertEquals(snapshot, event.snapshot)
        assertEquals(CallState.RINGING, event.snapshot.state)
    }

    @Test
    fun sessionStatesFollowSpecPath() {
        assertEquals(
            listOf(
                "IDLE",
                "ELIGIBLE",
                "PREPARING",
                "ANSWER_PENDING",
                "ACTIVE_AI",
                "HANDOFF",
                "ENDING",
                "ENDED",
            ),
            SessionState.entries.map { it.name },
        )
    }

    @Test
    fun agentConfigDefaultsKeepAutomationOff() {
        val config = AgentConfig()

        assertEquals(false, config.enabled)
        assertEquals(RuleMode.BLACKLIST, config.mode)
        assertEquals("CN", config.region)
        assertEquals(false, config.handleUnknownNumbers)
        assertEquals(3_000, config.answerDelayMs)
        assertEquals(180_000, config.maxCallDurationMs)
        assertEquals(emptyList<String>(), config.blacklist)
        assertEquals(emptyList<String>(), config.whitelist)
        assertNull(config.asr)
        assertNull(config.llm)
        assertNull(config.tts)
        assertEquals(0L, config.revision)
    }

    @Test
    fun pcmContractsDeclarePcm16AndCompareByContent() {
        val format = AudioFormatSpec(sampleRate = 16_000)

        assertEquals(PcmEncoding.PCM16, format.encoding)
        assertEquals(1, format.channels)
        assertEquals(
            PcmChunk(byteArrayOf(1, 2, 3), format),
            PcmChunk(byteArrayOf(1, 2, 3), format),
        )
        assertNotEquals(
            PcmChunk(byteArrayOf(1, 2, 3), format),
            PcmChunk(byteArrayOf(1, 2, 4), format),
        )
        assertEquals(
            PcmAudio(byteArrayOf(9, 9), format),
            PcmAudio(byteArrayOf(9, 9), format),
        )
    }

    @Test
    fun pcmChunkIsADataClassWithContentEquality() {
        val format = AudioFormatSpec(sampleRate = 16_000)
        val chunk = PcmChunk(byteArrayOf(1, 2, 3), format)

        // copy() 是 data class 才有的能力，且复制品按内容相等。
        assertEquals(chunk, chunk.copy())
        assertEquals(chunk.hashCode(), chunk.copy().hashCode())
        assertNotEquals(chunk, chunk.copy(data = byteArrayOf(1, 2, 4)))

        // 解构依赖生成的 componentN()。
        val (data, copiedFormat) = chunk
        assertArrayEquals(byteArrayOf(1, 2, 3), data)
        assertEquals(format, copiedFormat)

        // toString 同样被显式覆盖，不打印原始字节。
        assertEquals("PcmChunk(format=" + format + ", bytes=3)", chunk.toString())
    }

    @Test
    fun pcmAudioIsADataClassWithContentEquality() {
        val format = AudioFormatSpec(sampleRate = 24_000)
        val audio = PcmAudio(byteArrayOf(9, 9), format)

        assertEquals(audio, audio.copy())
        assertEquals(audio.hashCode(), audio.copy().hashCode())
        assertNotEquals(audio, audio.copy(data = byteArrayOf(9, 8)))

        val (data, copiedFormat) = audio
        assertArrayEquals(byteArrayOf(9, 9), data)
        assertEquals(format, copiedFormat)
    }

    @Test
    fun sealTypesExposeTheDocumentedEvents() {
        val events: List<SessionEvent> = listOf(
            SessionEvent.StateChanged(snapshot("epoch-1", "call-a")),
            SessionEvent.Tick,
            SessionEvent.ReadyChanged(ready = true),
            SessionEvent.Takeover(TakeoverReason.USER_ANSWERED),
            SessionEvent.PeerLost(PeerRole.HOOK),
        )

        assertEquals(5, events.size)
        val effects: List<SessionEffect> = listOf(
            SessionEffect.SendCommand(CallCommand.Answer),
            SessionEffect.StartConversation,
            SessionEffect.IsolateMicrophone,
            SessionEffect.NotifyUser("manual takeover"),
        )

        assertTrue(effects.contains(SessionEffect.StartConversation))
    }
}

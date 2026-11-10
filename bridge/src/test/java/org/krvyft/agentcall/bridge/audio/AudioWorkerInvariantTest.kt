package org.krvyft.agentcall.bridge.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 音频工作角色的单元测试：只验证进程内不变量，不伪造设备音频证据。
 *
 * - 重复 open 必须先停止旧会话，不能泄漏句柄/读取任务；
 * - 奇数字节 PCM16 上行负载必须映射为 [AudioUnavailableException]（监督端可分类），
 *   不能泄漏 IllegalArgumentException 让调用方误以为是编程错误。
 */
class AudioWorkerInvariantTest {

    private val session = SessionId("epoch-1", "call-a")
    private val format = AudioFormatSpec(16_000)

    private class FixedClock : MonotonicClock {
        override fun nowMs(): Long = 0L
    }

    private class SilentIsolation : MicrophoneIsolation {
        var isolated = false

        override suspend fun isolate(): Boolean {
            isolated = true
            return true
        }

        override suspend fun restore(): Boolean {
            isolated = false
            return true
        }

        override fun isIsolated(): Boolean = isolated
    }

    private class RecordingBridge : AudioBridge {
        val sessions = mutableListOf<RecordingSession>()

        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession =
            RecordingSession().also { sessions += it }
    }

    private class RecordingSession : AudioSession {
        var stopped = false
        override val downlink: Flow<PcmChunk> = emptyFlow()

        override suspend fun writeUplink(audio: PcmAudio) = Unit

        override suspend fun stop() {
            stopped = true
        }
    }

    @Test
    fun reopeningStopsThePreviousSessionFirst() = runBlocking {
        val bridge = RecordingBridge()
        val worker = AudioWorker(bridge, SilentIsolation(), FixedClock(), AudioLeaseCheck { true })
        assertTrue(worker.isolateMicrophone())

        worker.open(session, format)
        worker.open(session, format)

        assertEquals(2, bridge.sessions.size)
        assertTrue("第二次 open 之前必须停止第一个会话，否则句柄与读取任务泄漏", bridge.sessions[0].stopped)
        assertFalse(bridge.sessions[1].stopped)
    }

    @Test
    fun oddByteUplinkIsClassifiedAsAudioUnavailable() = runBlocking {
        val framework = UnstableFramework()
        val bridge = FrameworkAudioBridge(framework, dispatcher = Dispatchers.IO)
        val audio = bridge.open(session, format)

        try {
            audio.writeUplink(PcmAudio(ByteArray(3), format))
            fail("奇数字节的 PCM16 负载必须被拒绝")
        } catch (expected: AudioUnavailableException) {
            assertEquals("audio_odd_pcm_bytes", expected.code)
        } finally {
            audio.stop()
        }
    }

    private class UnstableFramework : CallAudioFramework {
        override fun visibleMethods(): Set<String> = setOf(
            AudioPathPolicy.DOWNLINK_METHOD,
            AudioPathPolicy.UPLINK_METHOD,
            AudioPathPolicy.INTERCEPTABLE_METHOD,
        )

        override fun isInterceptable(): Boolean = true

        override fun openCallExtractionDownlinkRecord(): CallAudioFramework.Downlink = Downlink()

        override fun openCallUplinkInjectionTrack(): CallAudioFramework.Uplink = Uplink()

        private class Downlink : CallAudioFramework.Downlink {
            private var served = false
            override val sampleRate: Int = 16_000
            override val channels: Int = 1

            override fun read(samples: ShortArray, offset: Int, count: Int): Int {
                if (served) return 0
                served = true
                samples[offset] = 1
                return 1
            }

            override fun close() = Unit
        }

        private class Uplink : CallAudioFramework.Uplink {
            override val sampleRate: Int = 16_000
            override val channels: Int = 1

            override fun write(samples: ShortArray, offset: Int, count: Int): Int = count

            override fun close() = Unit
        }
    }
}

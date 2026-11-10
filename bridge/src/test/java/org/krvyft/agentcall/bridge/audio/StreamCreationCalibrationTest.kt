package org.krvyft.agentcall.bridge.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.policy.CalibrationBlocker
import org.krvyft.agentcall.core.policy.CalibrationPolicy

/**
 * M5 补测：**真的创建一次通话音频流**之后，校准仍然不能被解锁。
 *
 * 方法可见/对象创建成功只到 PENDING_PEER_CONFIRMATION（spec §9）；解锁必须依赖五项
 * 人工测试 + 完整环境绑定，不能由 JVM 里的流创建“顺带”完成。
 */
class StreamCreationCalibrationTest {

    private val session = SessionId("epoch-1", "call-a")

    private fun environment(): EnvironmentKey = EnvironmentKey(
        apiLevel = 37,
        romFingerprint = "Sony/XQ-GE74/XQ-GE:17/test",
        kernelVersion = "5.15.189-android13",
        dialerPackageVersion = "245.0.1006864670",
        frameworkVersion = "82",
        bridgeVersion = "1.0",
        audioPolicyDigest = "digest",
        simCarrier = "carrier",
        transport = "VoLTE",
        route = "handset",
    )

    @Test
    fun streamCreationDoesNotUnlockCalibration() = runBlocking {
        val framework = StreamFramework()
        val audio = FrameworkAudioBridge(framework, dispatcher = Dispatchers.IO)

        // 真正创建通话提取/注入对象。
        val created = audio.open(session, AudioFormatSpec(16_000))
        assertTrue(framework.downlinkOpened)
        assertTrue(framework.uplinkOpened)
        assertFalse(audio.probe().downlink == org.krvyft.agentcall.core.model.CapabilityStatus.VERIFIED)

        val streamOnly = CalibrationEvidence(
            environment = environment(),
            calibratedAtMs = 1_700_000_000_000L,
        )
        assertFalse("创建流不等于对端听到语音/隔离/恢复/故障注入已验证", CalibrationPolicy.canAutoAnswer(environment(), streamOnly))
        val blockers = CalibrationPolicy.blockers(environment(), streamOnly)
        assertTrue(CalibrationBlocker.DOWNLINK_NOT_RECOGNIZED in blockers)
        assertTrue(CalibrationBlocker.PEER_HEARD_SPEECH_NOT_VERIFIED in blockers)
        assertTrue(CalibrationBlocker.MICROPHONE_ISOLATION_NOT_VERIFIED in blockers)
        assertTrue(CalibrationBlocker.MANUAL_RESTORE_NOT_VERIFIED in blockers)
        assertTrue(CalibrationBlocker.PROCESS_FAILURE_NOT_VERIFIED in blockers)

        created.stop()
    }

    private class StreamFramework : CallAudioFramework {
        var downlinkOpened = false
        var uplinkOpened = false

        override fun visibleMethods(): Set<String> = setOf(
            AudioPathPolicy.DOWNLINK_METHOD,
            AudioPathPolicy.UPLINK_METHOD,
            AudioPathPolicy.INTERCEPTABLE_METHOD,
        )

        override fun isInterceptable(): Boolean = true

        override fun openCallExtractionDownlinkRecord(): CallAudioFramework.Downlink {
            downlinkOpened = true
            return Downlink()
        }

        override fun openCallUplinkInjectionTrack(): CallAudioFramework.Uplink {
            uplinkOpened = true
            return Uplink()
        }

        private class Downlink : CallAudioFramework.Downlink {
            private var served = false
            override val sampleRate: Int = 16_000
            override val channels: Int = 1

            override fun read(samples: ShortArray, offset: Int, count: Int): Int {
                if (served) return 0
                served = true
                samples[offset] = 7
                return 1
            }

            override fun close() = Unit
        }

        private class Uplink : CallAudioFramework.Uplink {
            override val sampleRate: Int = 16_000
            override val channels: Int = 1

            private var written = 0

            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                written += count
                return count
            }

            override fun playedFrames(): Int = written

            override fun isPlaying(): Boolean = true

            override fun close() = Unit
        }
    }
}

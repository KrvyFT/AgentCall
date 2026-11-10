package org.krvyft.agentcall.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey

/**
 * 校准策略的行为测试（spec §9）。
 *
 * 关键不变式：创建音频对象、接口可见、权限通过都不是“已验证”；只有绑定同一环境且
 * 五项人工测试全部完成、带真实校准时间的证据才能解锁自动接听。任何未知/变化的环境
 * 一律 fail closed。
 */
class CalibrationPolicyTest {

    private fun environment(
        apiLevel: Int = CalibrationPolicy.SUPPORTED_API_LEVEL,
        romFingerprint: String = "XQ-EC72/37.0.A.1.100/123456:user/release-keys",
        kernelVersion: String = "6.1.75-android14-11-g1234567",
        dialerPackageVersion: String = "com.google.android.dialer/151.0.0",
        frameworkVersion: String = "LSPosed/1.9.2",
        bridgeVersion: String = "1.0",
        audioPolicyDigest: String = "0123456789abcdef",
        simCarrier: String = "46000",
        transport: String = "VoLTE",
        route: String = "HANDSET",
    ): EnvironmentKey = EnvironmentKey(
        apiLevel = apiLevel,
        romFingerprint = romFingerprint,
        kernelVersion = kernelVersion,
        dialerPackageVersion = dialerPackageVersion,
        frameworkVersion = frameworkVersion,
        bridgeVersion = bridgeVersion,
        audioPolicyDigest = audioPolicyDigest,
        simCarrier = simCarrier,
        transport = transport,
        route = route,
    )

    private fun completeEvidence(
        environment: EnvironmentKey,
        calibratedAtMs: Long = 1_700_000_000_000L,
    ): CalibrationEvidence = CalibrationEvidence(
        environment = environment,
        downlinkRecognized = true,
        peerHeardSpeech = true,
        microphoneIsolated = true,
        manualRestoreVerified = true,
        processFailureVerified = true,
        calibratedAtMs = calibratedAtMs,
    )

    @Test
    fun streamCreationDoesNotUnlockCalibration() {
        val environment = environment()
        // 仅仅成功创建了 AudioRecord/AudioTrack，并记录了探测时间：没有任何人工测试步骤。
        val streamOnly = CalibrationEvidence(
            environment = environment,
            calibratedAtMs = 1_700_000_000_000L,
        )

        assertFalse(CalibrationPolicy.canAutoAnswer(environment, streamOnly))

        val blockers = CalibrationPolicy.blockers(environment, streamOnly)
        assertTrue(CalibrationBlocker.DOWNLINK_NOT_RECOGNIZED in blockers)
        assertTrue(CalibrationBlocker.PEER_HEARD_SPEECH_NOT_VERIFIED in blockers)
        assertTrue(CalibrationBlocker.MICROPHONE_ISOLATION_NOT_VERIFIED in blockers)
        assertTrue(CalibrationBlocker.MANUAL_RESTORE_NOT_VERIFIED in blockers)
        assertTrue(CalibrationBlocker.PROCESS_FAILURE_NOT_VERIFIED in blockers)
    }

    @Test
    fun environmentChangeInvalidatesEvidence() {
        val environment = environment()
        val evidence = completeEvidence(environment)
        assertTrue(CalibrationPolicy.canAutoAnswer(environment, evidence))

        val changed = listOf(
            environment.copy(kernelVersion = "6.1.99-other"),
            environment.copy(romFingerprint = "OTHER/37/1:user/release-keys"),
            environment.copy(dialerPackageVersion = "com.google.android.dialer/152.0.0"),
            environment.copy(frameworkVersion = "LSPosed/2.0.0"),
            environment.copy(bridgeVersion = "1.1"),
            environment.copy(audioPolicyDigest = "fedcba9876543210"),
            environment.copy(simCarrier = "46001"),
            environment.copy(transport = "VoWiFi"),
            environment.copy(route = "BLUETOOTH"),
        )
        for (other in changed) {
            assertFalse("environment change must invalidate: " + other, CalibrationPolicy.canAutoAnswer(other, evidence))
            assertTrue(CalibrationBlocker.ENVIRONMENT_MISMATCH in CalibrationPolicy.blockers(other, evidence))
        }
    }

    @Test
    fun completeEvidenceForTheBoundEnvironmentUnlocks() {
        val environment = environment()
        val evidence = completeEvidence(environment)

        assertTrue(CalibrationPolicy.canAutoAnswer(environment, evidence))
        assertTrue(CalibrationPolicy.blockers(environment, evidence).isEmpty())
    }

    @Test
    fun api36DoesNotInheritApi37Calibration() {
        val api37 = environment()
        val evidence = completeEvidence(api37)

        assertFalse(CalibrationPolicy.canAutoAnswer(api37.copy(apiLevel = 36), evidence))

        val api36Evidence = completeEvidence(api37.copy(apiLevel = 36))
        assertFalse(CalibrationPolicy.canAutoAnswer(api37.copy(apiLevel = 36), api36Evidence))
        assertTrue(
            CalibrationBlocker.API_LEVEL_UNSUPPORTED in
                CalibrationPolicy.blockers(api37.copy(apiLevel = 36), api36Evidence),
        )
    }

    @Test
    fun unknownEnvironmentFieldsFailClosed() {
        val environment = environment(simCarrier = "UNKNOWN")
        assertFalse(CalibrationPolicy.canAutoAnswer(environment, completeEvidence(environment)))
        assertTrue(
            CalibrationBlocker.ENVIRONMENT_INCOMPLETE in
                CalibrationPolicy.blockers(environment, completeEvidence(environment)),
        )

        val blankRoute = environment(route = "")
        assertFalse(CalibrationPolicy.canAutoAnswer(blankRoute, completeEvidence(blankRoute)))
    }

    @Test
    fun missingCalibrationTimestampFailsClosed() {
        val environment = environment()
        val evidence = completeEvidence(environment, calibratedAtMs = 0L)

        assertFalse(CalibrationPolicy.canAutoAnswer(environment, evidence))
        assertTrue(
            CalibrationBlocker.EVIDENCE_TIMESTAMP_MISSING in
                CalibrationPolicy.blockers(environment, evidence),
        )
    }

    @Test
    fun partialManualTestNeverUnlocks() {
        val environment = environment()
        val complete = completeEvidence(environment)
        val partials = listOf(
            complete.copy(downlinkRecognized = false),
            complete.copy(peerHeardSpeech = false),
            complete.copy(microphoneIsolated = false),
            complete.copy(manualRestoreVerified = false),
            complete.copy(processFailureVerified = false),
        )
        for (partial in partials) {
            assertFalse(partial.toString(), CalibrationPolicy.canAutoAnswer(environment, partial))
        }
    }
}

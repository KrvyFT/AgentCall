package org.krvyft.agentcall.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.EnvironmentKey

/**
 * “已验证”展示裁决（Task 7B2 FIX ROUND 1，spec §9）。
 *
 * 旧 CalibrationActivity 只检查“五步勾选 + 环境非空 + 时间戳”，绕过了 core 的
 * CalibrationPolicy：API 36 上同样会显示“已验证”。本测试锁定 API 36 证据绝不显示已验证。
 */
class CalibrationVerdictTest {

    private fun environment(apiLevel: Int = 37): EnvironmentKey = EnvironmentKey(
        apiLevel = apiLevel,
        romFingerprint = "fp",
        kernelVersion = "6.1",
        dialerPackageVersion = "1.2",
        frameworkVersion = "82",
        bridgeVersion = "1.0",
        audioPolicyDigest = "digest",
        simCarrier = "carrier",
        transport = "VoLTE",
        route = "handset",
    )

    private fun record(
        environment: EnvironmentKey?,
        completed: Set<CalibrationManualStep> = CalibrationManualStep.entries.toSet(),
        calibratedAtMs: Long = 1_700_000_000_000L,
    ): CalibrationRecord = CalibrationRecord(environment, completed, calibratedAtMs)

    @Test
    fun aCompleteApi37RecordIsVerified() {
        val env = environment(37)
        assertTrue(CalibrationVerdict.canAutoAnswer(record(env), env))
    }

    @Test
    fun api36EvidenceIsNeverVerified() {
        val env = environment(36)
        val api36 = record(env)
        // 本机记录本身“完整”，但策略明确不承认 API 36 的校准结果。
        assertTrue(api36.allVerified)
        assertFalse(CalibrationVerdict.canAutoAnswer(api36, env))
    }

    @Test
    fun anEnvironmentMismatchIsNotVerified() {
        val recorded = environment(37)
        val current = recorded.copy(romFingerprint = "other-rom")
        assertFalse(CalibrationVerdict.canAutoAnswer(record(recorded), current))
    }

    @Test
    fun aMissingCurrentEnvironmentIsNotVerified() {
        assertFalse(CalibrationVerdict.canAutoAnswer(record(environment(37)), null))
    }

    @Test
    fun anIncompleteRecordIsNotVerified() {
        val env = environment(37)
        val incomplete = record(
            env,
            completed = CalibrationManualStep.entries.toSet() - CalibrationManualStep.PROCESS_FAILURE,
        )
        assertFalse(CalibrationVerdict.canAutoAnswer(incomplete, env))
        assertFalse(CalibrationVerdict.canAutoAnswer(record(env, calibratedAtMs = 0L), env))
    }

    @Test
    fun aRecordWithoutAnyBoundEnvironmentIsNotVerified() {
        assertFalse(CalibrationVerdict.canAutoAnswer(record(null), environment(37)))
    }
}

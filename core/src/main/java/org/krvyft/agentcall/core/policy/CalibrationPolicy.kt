package org.krvyft.agentcall.core.policy

import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey

/**
 * 阻止自动接听的具体原因。诊断与 UI 可据此显示可读说明；判定本身只依赖
 * [CalibrationPolicy.canAutoAnswer]。
 */
enum class CalibrationBlocker {
    /** 证据绑定的环境与当前环境不一致（任一字段变化即失效）。 */
    ENVIRONMENT_MISMATCH,

    /** 目标 SDK 不在首版验证范围内（仅 API 37；API 36 不继承任何校准）。 */
    API_LEVEL_UNSUPPORTED,

    /** 环境字段缺失或为显式的 UNKNOWN，不能作为可复现的校准绑定。 */
    ENVIRONMENT_INCOMPLETE,

    /** 证据没有真实人工测试时间戳，视为未完成校准。 */
    EVIDENCE_TIMESTAMP_MISSING,

    /** 未确认本机识别到通话下行音频。 */
    DOWNLINK_NOT_RECOGNIZED,

    /** 未确认对端听到测试语音（TTS 上行注入可用）。 */
    PEER_HEARD_SPEECH_NOT_VERIFIED,

    /** 未确认本机麦克风隔离生效。 */
    MICROPHONE_ISOLATION_NOT_VERIFIED,

    /** 未确认人工恢复（解除隔离并交还人工）可用。 */
    MANUAL_RESTORE_NOT_VERIFIED,

    /** 未完成进程故障注入测试。 */
    PROCESS_FAILURE_NOT_VERIFIED,
}

/**
 * 校准策略（spec §9）：自动接听的唯一判定入口，保守且 fail closed。
 *
 * 明确边界：
 * - 创建 AudioRecord/AudioTrack 成功、受限方法可见、权限通过都不解锁自动接听；
 * - 只有绑定**完全相同** [EnvironmentKey] 且五项人工测试全部完成、带真实校准时间的
 *   [CalibrationEvidence] 才返回 true；
 * - 环境任一字段变化、字段缺失/UNKNOWN、非 API 37 一律返回 false；
 * - 本对象没有写入/伪造证据的入口，能力状态中的“已验证”只能由人工测试流程派生。
 */
object CalibrationPolicy {

    /** 首版唯一验证过的 SDK；API 36 可安装但不继承校准结果。 */
    const val SUPPORTED_API_LEVEL: Int = 37

    /** 未知值的显式占位（与诊断层使用的字符串一致）。 */
    const val UNKNOWN_VALUE: String = "UNKNOWN"

    /**
     * 当前环境下是否允许自动接听。
     *
     * @param environment 当前设备实际环境（不能是猜测值）。
     * @param evidence 人工测试完成的持久证据；默认值表示未完成任何测试。
     */
    fun canAutoAnswer(environment: EnvironmentKey, evidence: CalibrationEvidence): Boolean =
        blockers(environment, evidence).isEmpty()

    /**
     * 返回全部未满足项；空列表等价于 [canAutoAnswer] 为 true。顺序固定，便于 UI/日志稳定。
     */
    fun blockers(environment: EnvironmentKey, evidence: CalibrationEvidence): List<CalibrationBlocker> {
        val blockers = mutableListOf<CalibrationBlocker>()
        if (environment.apiLevel != SUPPORTED_API_LEVEL) {
            blockers += CalibrationBlocker.API_LEVEL_UNSUPPORTED
        }
        if (!environment.isComplete()) {
            blockers += CalibrationBlocker.ENVIRONMENT_INCOMPLETE
        }
        if (evidence.environment != environment) {
            blockers += CalibrationBlocker.ENVIRONMENT_MISMATCH
        }
        if (evidence.calibratedAtMs <= 0L) {
            blockers += CalibrationBlocker.EVIDENCE_TIMESTAMP_MISSING
        }
        if (!evidence.downlinkRecognized) {
            blockers += CalibrationBlocker.DOWNLINK_NOT_RECOGNIZED
        }
        if (!evidence.peerHeardSpeech) {
            blockers += CalibrationBlocker.PEER_HEARD_SPEECH_NOT_VERIFIED
        }
        if (!evidence.microphoneIsolated) {
            blockers += CalibrationBlocker.MICROPHONE_ISOLATION_NOT_VERIFIED
        }
        if (!evidence.manualRestoreVerified) {
            blockers += CalibrationBlocker.MANUAL_RESTORE_NOT_VERIFIED
        }
        if (!evidence.processFailureVerified) {
            blockers += CalibrationBlocker.PROCESS_FAILURE_NOT_VERIFIED
        }
        return blockers
    }

    private fun EnvironmentKey.isComplete(): Boolean {
        val fields = listOf(
            romFingerprint,
            kernelVersion,
            dialerPackageVersion,
            frameworkVersion,
            bridgeVersion,
            audioPolicyDigest,
            simCarrier,
            transport,
            route,
        )
        return fields.all { it.isNotBlank() && it != UNKNOWN_VALUE }
    }
}

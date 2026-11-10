package org.krvyft.agentcall.core.model

/**
 * 校准证据绑定的运行环境。任一字段变化都会使已记录的校准证据失效。
 *
 * @property apiLevel 目标 ROM 的 SDK 级别（首版仅验证 37）。
 * @property romFingerprint ROM 指纹。
 * @property kernelVersion 内核版本字符串。
 * @property dialerPackageVersion Google 电话包版本。
 * @property frameworkVersion Xposed 框架版本。
 * @property bridgeVersion AgentCall 桥接版本。
 * @property audioPolicyDigest 音频策略摘要。
 * @property simCarrier SIM 运营商标识。
 * @property transport 承载方式（例如 VoLTE、VoWiFi）。
 * @property route 音频路由（默认仅手持无耳机）。
 */
data class EnvironmentKey(
    val apiLevel: Int,
    val romFingerprint: String,
    val kernelVersion: String,
    val dialerPackageVersion: String,
    val frameworkVersion: String,
    val bridgeVersion: String,
    val audioPolicyDigest: String,
    val simCarrier: String,
    val transport: String,
    val route: String,
)

/**
 * 能力状态。创建对象成功、方法可见都不等于已验证；只有人工测试电话通过后才能标记
 * [VERIFIED]。
 */
enum class CapabilityStatus {
    NOT_DETECTED,
    INTERFACE_VISIBLE,
    PENDING_PEER_CONFIRMATION,
    VERIFIED,
    UNSUPPORTED,
}

/**
 * 一次设备探测结果。[checkedAtMs] 是探测时刻；未实际执行的项保持
 * [CapabilityStatus.NOT_DETECTED]，不得按默认可用处理。
 */
data class CapabilityReport(
    val environment: EnvironmentKey,
    val downlinkExtraction: CapabilityStatus,
    val uplinkInjection: CapabilityStatus,
    val microphoneIsolation: CapabilityStatus,
    val privilegedTermination: CapabilityStatus,
    val checkedAtMs: Long,
    val notes: String = "",
)

/**
 * 人工校准证据：全部步骤都完成才可视为已校准，且只对 [environment] 有效。
 */
data class CalibrationEvidence(
    val environment: EnvironmentKey,
    val downlinkRecognized: Boolean = false,
    val peerHeardSpeech: Boolean = false,
    val microphoneIsolated: Boolean = false,
    val manualRestoreVerified: Boolean = false,
    val processFailureVerified: Boolean = false,
    val calibratedAtMs: Long = 0L,
)

package org.krvyft.agentcall.bridge.audio

import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CapabilityStatus
import org.krvyft.agentcall.core.model.SessionId

/**
 * 特权桥接内部的两类 app_process 角色（spec §6.3：监督进程与音频工作进程分离）。
 *
 * 角色只是运行容器入口的命名，不是 IPC 的 [org.krvyft.agentcall.core.model.PeerRole]：
 * - [SUPERVISOR] 持有租约、心跳与故障终止能力，绝不读取 PCM；
 * - [AUDIO_WORKER] 持有 PCM 流与麦克风隔离状态，绝不下发通话命令。
 *
 * 两个角色由模块的 service.sh 以独立 app_process 拉起：监督角色运行在桥接容器
 * （BridgeMain）内，音频工作角色由 AudioWorkerMain 单独运行。两者只通过 root 私有的
 * AudioRole 生命周期通道交互，不共享静态可变状态；设备上的实际运行仍属“未验证”。
 */
enum class BridgeProcessRole(
    /** 该角色是否允许持有通话音频流。 */
    val ownsAudioStreams: Boolean,
    /** 该角色是否允许下发/终止通话。 */
    val ownsCallCommands: Boolean,
) {
    SUPERVISOR(ownsAudioStreams = false, ownsCallCommands = true),
    AUDIO_WORKER(ownsAudioStreams = true, ownsCallCommands = false),
}

/** 音频通路状态；[FAILED] 保留失败原因，不能当成“静默成功”。 */
enum class AudioPathState {
    CLOSED,
    OPENING,
    OPEN,
    FAILED,
}

/** 机器可读的音频故障分类，供监督端决定保持隔离/终止/重启。 */
enum class AudioFailureCode {
    /** 受限提取/注入通路不存在、权限不足或对象创建失败。 */
    UNSUPPORTED,

    /** 已存在的通路打开失败（例如设备流被占用）。 */
    OPEN_FAILED,

    /** 下行读取失败或对端已关闭；不得回退到环境麦克风。 */
    DOWNLINK_FAILED,

    /** 上行写入失败（TTS 注入不可用）。 */
    UPLINK_FAILED,

    /** 有界 PCM 队列溢出，按有界失败处理而不是阻塞电话 UI。 */
    BUFFER_OVERFLOW,

    /** 音频工作进程失效/重启预算耗尽。 */
    PROCESS_LOST,

    /** 会话已停止后仍尝试写入/读取。 */
    STOPPED,
}

/**
 * 一次音频能力探测结果（spec §9 的五态分类）。
 *
 * [downlink] 只能来自通话提取 AudioRecord，[uplink] 只能来自通话注入 AudioTrack；
 * 创建成功只到 [CapabilityStatus.PENDING_PEER_CONFIRMATION]，绝不在此处产生
 * [CapabilityStatus.VERIFIED]。
 */
data class AudioCapability(
    val downlink: CapabilityStatus,
    val uplink: CapabilityStatus,
    val interceptable: CapabilityStatus,
    val visibleMethods: Set<String> = emptySet(),
    val notes: String = "",
) {
    /** 是否具备尝试打开通路的资格（仍需逐通话重新检查）。 */
    val canOpen: Boolean
        get() = AudioPathPolicy.canOpen(downlink) && AudioPathPolicy.canOpen(uplink)
}

/**
 * 音频工作角色的状态快照（spec §8：故障恢复必须可观测）。
 *
 * 重启预算不在这里：它由监督端持有（I5），音频工作进程重启不会清零预算。
 */
data class AudioStateSnapshot(
    val role: BridgeProcessRole,
    val state: AudioPathState,
    val sessionId: SessionId?,
    val format: AudioFormatSpec?,
    val microphoneIsolated: Boolean,
    val downlinkChunks: Long,
    val uplinkWrites: Long,
    val failure: AudioFailureCode?,
    val updatedAtMs: Long,
)

package org.krvyft.agentcall.core.port

import kotlinx.coroutines.flow.Flow
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId

/**
 * 特权音频桥接端口。
 */
interface AudioBridge {
    /**
     * 为 [id] 打开双向音频会话。打开失败必须抛出异常，不得返回“无声”的成功会话，
     * 也不得用环境麦克风冒充通话下行。
     */
    suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession
}

/**
 * 一次通话的音频会话。只承载实际通话音频，不包含环境麦克风采集。
 */
interface AudioSession {
    /** 通话下行 PCM 分片流；调用方取消收集即停止接收。 */
    val downlink: Flow<PcmChunk>

    /**
     * 写入上行 PCM 音频（例如 TTS 输出）。
     *
     * 播放完成契约（ConversationEngine 的回声保护依赖它，实现不得弱化）：
     * - 本方法只有在注入的音频**播放结束**（或收到明确的播放完成信号）后才返回；
     *   不得把“已写入系统播放缓冲/已交给 AudioTrack”当成“已播放”；
     * - 因此本方法在整段播放期间保持挂起，并必须响应协程取消（取消后尽快返回）；
     * - 引擎在播放期间及播放结束后的短暂残留窗口内把下行当作本机 TTS 回声丢弃，
     *   不会据此开启新回合；若本方法提前返回，保留的 300 ms 窗口不足以覆盖回声，
     *   TTS 回声可能被当成新的用户语音。实现改变播放语义时必须同步修改引擎的窗口。
     */
    suspend fun writeUplink(audio: PcmAudio)

    /**
     * 停止音频会话。
     *
     * 停止不解除麦克风隔离：隔离的恢复必须在确认断开后由监督侧单独执行，避免人工
     * 接管前环境麦克风被意外打开。
     */
    suspend fun stop()
}

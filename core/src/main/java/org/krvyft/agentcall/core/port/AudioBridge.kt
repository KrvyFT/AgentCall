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

    /** 写入上行 PCM 音频（例如 TTS 输出）。 */
    suspend fun writeUplink(audio: PcmAudio)

    /**
     * 停止音频会话。
     *
     * 停止不解除麦克风隔离：隔离的恢复必须在确认断开后由监督侧单独执行，避免人工
     * 接管前环境麦克风被意外打开。
     */
    suspend fun stop()
}

package org.krvyft.agentcall.core.port

import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.SessionId

/**
 * 远程语音会话端口（ASR → LLM → TTS 回合由实现负责）。
 *
 * 取消由调用方 Job 传播；实现必须原样传播 CancellationException，并在取消后停止
 * 写入上行音频。
 */
interface ConversationPort {
    /**
     * 在 [id] 会话上使用 [audio] 与 [config] 运行语音对话，直到通话结束或调用方取消。
     *
     * @return 正常结束时返回；取消时抛出 CancellationException。
     */
    suspend fun run(id: SessionId, audio: AudioSession, config: AgentConfig)
}

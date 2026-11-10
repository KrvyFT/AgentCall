package org.krvyft.agentcall.core.port

import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.SessionId

/**
 * 通话控制端口。实现位于 Google 电话进程内（Hook），命令在目标 Call 对象上按会话/代次
 * 核验后执行；不提供无选择的全局 endCall。
 */
interface CallControl {
    /**
     * 向 [id] 对应的通话下发 [command]。
     *
     * @return 已执行、被拒绝或执行失败的结构化结果；调用方必须检查结果而不是假定成功。
     */
    suspend fun send(id: SessionId, command: CallCommand): CommandResult
}

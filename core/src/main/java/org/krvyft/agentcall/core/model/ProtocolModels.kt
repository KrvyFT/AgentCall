package org.krvyft.agentcall.core.model

/**
 * 本地控制协议版本。固定为 1；版本不匹配的请求必须被拒绝。
 */
const val PROTOCOL_VERSION: Int = 1

/**
 * 本地 abstract Unix socket 的对端角色。
 */
enum class PeerRole {
    /** AgentCall APK 进程：会话请求、受限配置读取、音频、人工交还。 */
    APP,

    /** Google 电话进程内的 Hook：快照、心跳、受限通话命令；不得读取 API Key。 */
    HOOK,

    /** root CLI：配对、配置写入、诊断、关闭服务。 */
    CLI_ROOT,
}

/**
 * 由内核对端凭据核验后建立的对端身份。UID 不取自消息内容。
 *
 * @property uid 来自 LocalSocket credentials 的真实用户 ID。
 * @property packageName 配对时核验的包名。
 * @property signatureSha256 配对时记录的签名摘要。
 * @property protocolVersion 协商后的控制协议版本。
 */
data class PeerIdentity(
    val uid: Int,
    val role: PeerRole,
    val packageName: String? = null,
    val signatureSha256: String? = null,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

/**
 * 一条控制请求。请求 ID、会话 ID 与版本都必须由接收端校验。
 */
data class RpcRequest(
    val id: String,
    val method: String,
    val sessionId: SessionId? = null,
    val payload: String = "",
    val version: Int = PROTOCOL_VERSION,
)

/**
 * 一条控制响应。[error] 非 null 时 [payload] 必须为空。
 */
data class RpcResponse(
    val id: String,
    val payload: String = "",
    val error: RpcError? = null,
    val version: Int = PROTOCOL_VERSION,
)

/**
 * 机器可读错误。[message] 不得包含密钥原值。
 */
data class RpcError(
    val code: String,
    val message: String,
)

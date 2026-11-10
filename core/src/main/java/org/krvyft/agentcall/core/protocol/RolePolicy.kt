package org.krvyft.agentcall.core.protocol

import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest

/**
 * 固定角色/方法表的鉴权（spec §6.4）。
 *
 * 唯一可信输入是 [PeerIdentity]：它由 [org.krvyft.agentcall.bridge.ipc.PeerVerifier]
 * 从 LocalSocket 内核凭据 + PackageManager 核验得出。请求内容里的 uid、包名或角色
 * 字段不参与判定；未知方法一律拒绝，没有通配符或“管理员可做任何事”的分支。
 *
 * | 角色 | 允许的方法 |
 * | --- | --- |
 * | APP | session.request、session.heartbeat、session.command、session.events、status、diagnose、config.read、config.secret.read、audio.channel、handover |
 * | HOOK | hook.snapshot、hook.heartbeat、call.command.result |
 * | CLI_ROOT | status、config.read、config.write、pair、diagnose、shutdown |
 *
 * `session.heartbeat` 只表达“APP 仍在看着这通电话”，是存在性信号，不会触发接听或改变
 * 监督阶段；`session.command` 只允许经 HOOK 执行的受限命令（answer/hangup），由 bridge
 * 排入同一份 HookCommandRouter 并等待真实结果。两者都必须携带 SessionId，且只有 APP
 * 能调用：HOOK 不能借 APP 通道下发命令，CLI_ROOT 也不能。
 *
 * `status`/`diagnose` 对 APP 也是**只读**：真实 APK 必须能读到桥接状态与诊断（否则永远
 * 到不了 READY），但 APP 永远拿不到 config.write/pair/shutdown。`session.events` 是 APP
 * 轮询的会话事件流（发现新通话的唯一入口），因此它**不**在会话方法表里：新通话的
 * SessionId 正是这条通道要读出来的东西。
 *
 * 密钥读取只对 APP 开放（远程 API 在 APK 进程内）；HOOK 与 CLI 都拿不到密钥，
 * CLI 只能写密钥状态，不能读回原值。
 */
object RolePolicy {

    const val METHOD_SESSION_REQUEST: String = "session.request"

    /** APP 的会话存在性心跳；只记录 presence，不重新准备接听、不改变监督阶段。 */
    const val METHOD_SESSION_HEARTBEAT: String = "session.heartbeat"

    /** APP 请求一条受限通话命令（answer/hangup），由 bridge 排入 HOOK 命令路由并等待结果。 */
    const val METHOD_SESSION_COMMAND: String = "session.command"

    const val METHOD_CONFIG_READ: String = "config.read"
    const val METHOD_CONFIG_SECRET_READ: String = "config.secret.read"
    const val METHOD_AUDIO_CHANNEL: String = "audio.channel"
    const val METHOD_HANDOVER: String = "handover"

    const val METHOD_HOOK_SNAPSHOT: String = "hook.snapshot"
    const val METHOD_HOOK_HEARTBEAT: String = "hook.heartbeat"
    const val METHOD_CALL_COMMAND_RESULT: String = "call.command.result"

    /**
     * APP 轮询的会话事件流（Task 7C）：HOOK 快照 / 监督端交还/断开的真实事实，按进程内
     * 单调 seq 排列。**不需要** SessionId——新通话的 SessionId 正是这条通道要读出来的
     * 东西，因此它不在 [SESSION_METHODS] 里。
     */
    const val METHOD_SESSION_EVENTS: String = "session.events"

    const val METHOD_STATUS: String = "status"
    const val METHOD_CONFIG_WRITE: String = "config.write"
    const val METHOD_PAIR: String = "pair"
    const val METHOD_DIAGNOSE: String = "diagnose"
    const val METHOD_SHUTDOWN: String = "shutdown"

    const val MAX_REQUEST_ID_CHARS: Int = RpcCodec.MAX_REQUEST_ID_CHARS
    const val MAX_METHOD_CHARS: Int = RpcCodec.MAX_METHOD_CHARS

    private val ROLE_METHODS: Map<PeerRole, Set<String>> = mapOf(
        PeerRole.APP to setOf(
            METHOD_SESSION_REQUEST,
            METHOD_SESSION_HEARTBEAT,
            METHOD_SESSION_COMMAND,
            METHOD_CONFIG_READ,
            METHOD_CONFIG_SECRET_READ,
            METHOD_AUDIO_CHANNEL,
            METHOD_HANDOVER,
            // Task 7C：真实 APK 必须能读桥接状态/诊断并轮询会话事件，否则永远到不了 READY。
            // 三者都是只读/只读投影；写入能力仍然只属于 CLI_ROOT。
            METHOD_STATUS,
            METHOD_DIAGNOSE,
            METHOD_SESSION_EVENTS,
        ),
        PeerRole.HOOK to setOf(
            METHOD_HOOK_SNAPSHOT,
            METHOD_HOOK_HEARTBEAT,
            METHOD_CALL_COMMAND_RESULT,
        ),
        PeerRole.CLI_ROOT to setOf(
            METHOD_STATUS,
            METHOD_CONFIG_READ,
            METHOD_CONFIG_WRITE,
            METHOD_PAIR,
            METHOD_DIAGNOSE,
            METHOD_SHUTDOWN,
        ),
    )

    /**
     * 必须携带 [RpcRequest.sessionId] 的方法：所有会话内动作都不能作用于“当前通话”。
     *
     * `session.events` 刻意不在其中：它是 APP 发现新会话的入口，自身没有会话参数。
     */
    private val SESSION_METHODS: Set<String> = setOf(
        METHOD_SESSION_REQUEST,
        METHOD_SESSION_HEARTBEAT,
        METHOD_SESSION_COMMAND,
        METHOD_AUDIO_CHANNEL,
        METHOD_HANDOVER,
        METHOD_HOOK_SNAPSHOT,
        METHOD_HOOK_HEARTBEAT,
        METHOD_CALL_COMMAND_RESULT,
    )

    /** 某角色在固定方法表中的可调用方法；只读副本，测试用于证明 HOOK 不含配置方法。 */
    fun methodsFor(role: PeerRole): Set<String> = ROLE_METHODS[role].orEmpty().toSet()

    /**
     * 判定某个已核验对端能否执行该请求。
     *
     * 拒绝条件：协议版本不一致、身份与角色不匹配、请求 ID/方法名不合法、
     * 方法不在该角色的固定表中、会话方法缺少会话 ID。
     */
    fun authorize(peer: PeerIdentity, request: RpcRequest): Boolean {
        if (request.version != PROTOCOL_VERSION || peer.protocolVersion != PROTOCOL_VERSION) return false
        if (!identityMatchesRole(peer)) return false
        if (!isValidRequestId(request.id)) return false
        if (!isValidMethod(request.method)) return false
        if (request.method !in methodsFor(peer.role)) return false
        if (request.method in SESSION_METHODS && request.sessionId == null) return false
        return true
    }

    /**
     * 角色必须与内核凭据一致：CLI_ROOT 只能是 uid 0；APP/HOOK 必须是已配对、已核验
     * 包名与签名的非 root 进程。凭这个判定，消息里自称 root 不会提权。
     */
    private fun identityMatchesRole(peer: PeerIdentity): Boolean = when (peer.role) {
        PeerRole.CLI_ROOT -> peer.uid == 0
        PeerRole.APP, PeerRole.HOOK ->
            peer.uid > 0 && !peer.packageName.isNullOrBlank() && !peer.signatureSha256.isNullOrBlank()
    }

    private fun isValidRequestId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_REQUEST_ID_CHARS && id.none { it.isISOControl() }

    private fun isValidMethod(method: String): Boolean =
        method.isNotEmpty() && method.length <= MAX_METHOD_CHARS &&
            method.none { it.isISOControl() || it.isWhitespace() }
}

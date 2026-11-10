package org.krvyft.agentcall.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.SessionId

/**
 * 固定角色/方法表的鉴权测试。先于实现存在，用于记录红灯。
 *
 * 身份只能来自 LocalSocket 凭据核验后的 [PeerIdentity]；消息内容里的 uid/role
 * 不参与判定（spec §6.4“不能信任消息中的 UID”）。
 */
class RolePolicyTest {

    private val session = SessionId("epoch-1", "call-1")
    private val appSignature = "a".repeat(64)
    private val hookSignature = "b".repeat(64)

    private fun appPeer(uid: Int = 10_250) = PeerIdentity(
        uid = uid,
        role = PeerRole.APP,
        packageName = "org.krvyft.agentcall",
        signatureSha256 = appSignature,
    )

    private fun hookPeer(uid: Int = 10_300) = PeerIdentity(
        uid = uid,
        role = PeerRole.HOOK,
        packageName = "com.google.android.dialer",
        signatureSha256 = hookSignature,
    )

    private fun rootPeer(uid: Int = 0) = PeerIdentity(uid = uid, role = PeerRole.CLI_ROOT)

    private fun request(
        method: String,
        sessionId: SessionId? = null,
        payload: String = "",
        id: String = "req-1",
        version: Int = PROTOCOL_VERSION,
    ) = RpcRequest(id = id, method = method, sessionId = sessionId, payload = payload, version = version)

    @Test
    fun hookCannotReadSecrets() {
        // HOOK 只允许快照、心跳与受限命令结果，任何配置读取都拒绝。
        assertFalse(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_CONFIG_READ)))
        assertFalse(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_CONFIG_SECRET_READ)))
        assertFalse(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_CONFIG_WRITE)))
        assertEquals(
            setOf(
                RolePolicy.METHOD_HOOK_SNAPSHOT,
                RolePolicy.METHOD_HOOK_HEARTBEAT,
                RolePolicy.METHOD_CALL_COMMAND_RESULT,
            ),
            RolePolicy.methodsFor(PeerRole.HOOK),
        )

        // 密钥读取只对 APP 开放（APP 负责远程 API），CLI 与 HOOK 都拿不到。
        assertTrue(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_CONFIG_SECRET_READ)))
        assertFalse(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_CONFIG_SECRET_READ)))

        // 防御性断言：HOOK 的固定方法表本身不含任何密钥/配置方法。
        val hookMethods = RolePolicy.methodsFor(PeerRole.HOOK)
        assertFalse(hookMethods.contains(RolePolicy.METHOD_CONFIG_SECRET_READ))
        assertFalse(hookMethods.contains(RolePolicy.METHOD_CONFIG_READ))
    }

    @Test
    fun claimedUidIsIgnored() {
        // 消息负载自称 root CLI：鉴权结果必须完全不变。
        val claimedRoot = """{"uid":0,"role":"CLI_ROOT","packageName":"org.krvyft.agentcall"}"""

        assertFalse(
            RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_CONFIG_WRITE, payload = claimedRoot)),
        )
        assertFalse(
            RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_SHUTDOWN, payload = claimedRoot)),
        )
        assertFalse(
            RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_PAIR, payload = claimedRoot)),
        )
        // 合法方法不受负载影响。
        assertTrue(
            RolePolicy.authorize(
                hookPeer(),
                request(RolePolicy.METHOD_HOOK_HEARTBEAT, sessionId = session, payload = claimedRoot),
            ),
        )

        // 伪造“uid 0 + APP 角色”“普通 uid + CLI_ROOT 角色”的 PeerIdentity 都不能提权：
        // 角色必须与内核凭据一致，CLI_ROOT 只接受 root uid。
        assertFalse(
            RolePolicy.authorize(
                PeerIdentity(0, PeerRole.APP, "org.krvyft.agentcall", appSignature),
                request(RolePolicy.METHOD_CONFIG_SECRET_READ),
            ),
        )
        assertFalse(
            RolePolicy.authorize(
                PeerIdentity(10_250, PeerRole.CLI_ROOT),
                request(RolePolicy.METHOD_SHUTDOWN),
            ),
        )
    }

    @Test
    fun appCannotUseRootOrHookOnlyCommands() {
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_CONFIG_WRITE)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_PAIR)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_DIAGNOSE)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_SHUTDOWN)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_HOOK_SNAPSHOT, session)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_HOOK_HEARTBEAT, session)))
    }

    @Test
    fun rootCliOwnsPrivilegedOperations() {
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_STATUS)))
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_CONFIG_READ)))
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_CONFIG_WRITE)))
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_PAIR)))
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_DIAGNOSE)))
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_SHUTDOWN)))

        // 非 0 uid 不能自称 CLI_ROOT。
        assertFalse(RolePolicy.authorize(rootPeer(uid = 2_000), request(RolePolicy.METHOD_SHUTDOWN)))
    }

    @Test
    fun onlyFixedCommandTableIsAuthorized() {
        assertFalse(RolePolicy.authorize(rootPeer(), request("config.write.evil")))
        assertFalse(RolePolicy.authorize(rootPeer(), request("shutdown ")))
        assertFalse(RolePolicy.authorize(rootPeer(), request("")))
        assertFalse(RolePolicy.authorize(rootPeer(), request("m".repeat(RolePolicy.MAX_METHOD_CHARS + 1))))
        assertFalse(RolePolicy.authorize(rootPeer(), request("status", id = "")))
        assertFalse(RolePolicy.authorize(rootPeer(), request("status", id = "i".repeat(129))))
    }

    @Test
    fun fixedRoleMethodTableIsExact() {
        // 固定表必须逐项相等：多一个方法（例如把 HOOK 命令暴露给 APP）就是权限回归，
        // 少一个方法就是 Task 7 的 APP→bridge 命令/心跳通道被悄悄删掉。
        assertEquals(
            setOf(
                RolePolicy.METHOD_SESSION_REQUEST,
                RolePolicy.METHOD_CONFIG_READ,
                RolePolicy.METHOD_CONFIG_SECRET_READ,
                RolePolicy.METHOD_AUDIO_CHANNEL,
                RolePolicy.METHOD_HANDOVER,
                RolePolicy.METHOD_SESSION_HEARTBEAT,
                RolePolicy.METHOD_SESSION_COMMAND,
            ),
            RolePolicy.methodsFor(PeerRole.APP),
        )
        assertEquals(
            setOf(
                RolePolicy.METHOD_HOOK_SNAPSHOT,
                RolePolicy.METHOD_HOOK_HEARTBEAT,
                RolePolicy.METHOD_CALL_COMMAND_RESULT,
            ),
            RolePolicy.methodsFor(PeerRole.HOOK),
        )
        assertEquals(
            setOf(
                RolePolicy.METHOD_STATUS,
                RolePolicy.METHOD_CONFIG_READ,
                RolePolicy.METHOD_CONFIG_WRITE,
                RolePolicy.METHOD_PAIR,
                RolePolicy.METHOD_DIAGNOSE,
                RolePolicy.METHOD_SHUTDOWN,
            ),
            RolePolicy.methodsFor(PeerRole.CLI_ROOT),
        )
        // 协议版本保持 1：新增方法不改变线协议版本。
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test
    fun appSessionHeartbeatAndCommandAreAppOnlyAndRequireSession() {
        // APP 可以在会话内报心跳、下发受限命令。
        assertTrue(
            RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_SESSION_HEARTBEAT, session)),
        )
        assertTrue(
            RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_SESSION_COMMAND, session)),
        )
        // 两个方法都必须携带 SessionId：不能作用于“当前通话”。
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_SESSION_HEARTBEAT)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_SESSION_COMMAND)))

        // HOOK 与 CLI_ROOT 不能借用 APP 的命令/心跳通道（即使伪造会话 ID）。
        assertFalse(RolePolicy.methodsFor(PeerRole.HOOK).contains(RolePolicy.METHOD_SESSION_COMMAND))
        assertFalse(RolePolicy.methodsFor(PeerRole.HOOK).contains(RolePolicy.METHOD_SESSION_HEARTBEAT))
        assertFalse(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_SESSION_COMMAND, session)))
        assertFalse(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_SESSION_HEARTBEAT, session)))
        assertFalse(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_SESSION_COMMAND, session)))
        assertFalse(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_SESSION_HEARTBEAT, session)))
        assertFalse(RolePolicy.methodsFor(PeerRole.CLI_ROOT).contains(RolePolicy.METHOD_SESSION_COMMAND))
        assertFalse(RolePolicy.methodsFor(PeerRole.CLI_ROOT).contains(RolePolicy.METHOD_SESSION_HEARTBEAT))

        // 未核验身份（无包名/签名）的 APP 也不能调用。
        assertFalse(
            RolePolicy.authorize(
                PeerIdentity(10_250, PeerRole.APP),
                request(RolePolicy.METHOD_SESSION_COMMAND, session),
            ),
        )
    }

    @Test
    fun sessionBoundMethodsRequireSessionId() {
        assertFalse(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_HOOK_SNAPSHOT)))
        assertTrue(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_HOOK_SNAPSHOT, session)))
        assertTrue(RolePolicy.authorize(hookPeer(), request(RolePolicy.METHOD_CALL_COMMAND_RESULT, session)))

        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_SESSION_REQUEST)))
        assertTrue(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_SESSION_REQUEST, session)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_AUDIO_CHANNEL)))
        assertTrue(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_AUDIO_CHANNEL, session)))
        assertFalse(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_HANDOVER)))
        assertTrue(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_HANDOVER, session)))

        // 配置/状态类方法不绑定会话。
        assertTrue(RolePolicy.authorize(appPeer(), request(RolePolicy.METHOD_CONFIG_READ)))
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_STATUS)))
    }

    @Test
    fun protocolVersionMismatchRejected() {
        assertFalse(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_STATUS, version = 2)))
        assertTrue(RolePolicy.authorize(rootPeer(), request(RolePolicy.METHOD_STATUS, version = PROTOCOL_VERSION)))
    }

    @Test
    fun unverifiedPeerIdentityIsRejected() {
        // 没有配对记录核验出的包名/签名时，APP/HOOK 身份不成立。
        assertFalse(
            RolePolicy.authorize(
                PeerIdentity(10_250, PeerRole.APP),
                request(RolePolicy.METHOD_CONFIG_SECRET_READ),
            ),
        )
        assertFalse(
            RolePolicy.authorize(
                PeerIdentity(10_300, PeerRole.HOOK, packageName = "com.google.android.dialer"),
                request(RolePolicy.METHOD_HOOK_HEARTBEAT, session),
            ),
        )
    }
}

package org.krvyft.agentcall.bridge.supervision

import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/**
 * 独立特权终止路径的权限检查（例如 MODIFY_PHONE_STATE 或默认拨号器资格）。
 * 由运行容器注入真实实现；测试注入假实现。
 */
fun interface TerminationPermission {
    fun isGranted(): Boolean
}

/** 一次已核验的通话身份。只允许携带恰好匹配 [SessionId] 的通话。 */
data class VerifiedCallIdentity(
    val id: SessionId,
    /** 特权 Telecom 侧的通话标识（诊断用，可为空；不能用来替代 [id] 核验）。 */
    val telecomCallId: String? = null,
)

/**
 * 独立于 Hook 的通话身份核对。
 *
 * Hook 死亡时监督端仍必须确认“要终止的正是自己租约下的那通电话”。若实现无法核对
 * （没有可靠 API、通话列表不可达、身份不一致），必须返回 null 而不是猜测。
 */
interface CallIdentityVerifier {

    /** 当前进程是否具备核对通话身份的能力。 */
    fun isAvailable(): Boolean

    /** 返回与 [id] 完全对应的通话身份；无法核对返回 null。 */
    suspend fun verify(id: SessionId): VerifiedCallIdentity?
}

/** 特权 Telecom 终止端口：只有身份核验通过后才会被调用。 */
fun interface PrivilegedTermination {
    suspend fun disconnect(verified: VerifiedCallIdentity): CommandResult
}

/**
 * 独立于 Hook 的特权 Telecom 终止路径（spec §8）。
 *
 * 顺序固定且 fail closed：
 * 1. 没有权限 → 明确 [RejectReason.UNSUPPORTED]，不尝试任何挂断；
 * 2. 身份不可核对 → 明确 [RejectReason.UNSUPPORTED]，不猜测目标通话；
 * 3. 身份与请求不一致 → [RejectReason.STALE_SESSION]；
 * 4. 只有全部通过才调用 [PrivilegedTermination]。
 *
 * 任一前置条件不满足时 [blocksAutoAnswer] 返回 true：没有独立终止能力就不允许启用
 * 自动接管（spec §8：不可用则不允许启用自动接管）。
 */
class EmergencyCallControl(
    private val permissions: TerminationPermission,
    private val identities: CallIdentityVerifier,
    private val termination: PrivilegedTermination,
) {

    @Volatile
    private var lastBlocker: String? = null

    /** 无法核对身份或缺少权限时阻止自动接听。每次调用都重新检查权限可用性。 */
    fun blocksAutoAnswer(): Boolean =
        !permissions.isGranted() || !identities.isAvailable()

    /** 最近一次拒绝的机器可读原因，供诊断；成功时清空。 */
    fun lastBlocker(): String? = lastBlocker

    /**
     * 只终止 [id] 对应的电话；不能核验身份/权限时返回明确不支持，且不产生挂断。
     */
    suspend fun disconnectOwned(id: SessionId): CommandResult {
        if (!permissions.isGranted()) {
            lastBlocker = BLOCKER_PERMISSION
            return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        }
        if (!identities.isAvailable()) {
            lastBlocker = BLOCKER_IDENTITY_UNAVAILABLE
            return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        }
        val verified = try {
            identities.verify(id)
        } catch (t: Throwable) {
            null
        }
        if (verified == null) {
            lastBlocker = BLOCKER_IDENTITY_UNAVAILABLE
            return CommandResult.Rejected(RejectReason.UNSUPPORTED)
        }
        if (verified.id != id) {
            lastBlocker = BLOCKER_IDENTITY_MISMATCH
            return CommandResult.Rejected(RejectReason.STALE_SESSION)
        }
        lastBlocker = null
        return try {
            termination.disconnect(verified)
        } catch (t: Throwable) {
            CommandResult.Failed("telecom_termination_failed", t.javaClass.simpleName)
        }
    }

    companion object {
        const val BLOCKER_PERMISSION: String = "telecom_permission_missing"
        const val BLOCKER_IDENTITY_UNAVAILABLE: String = "call_identity_unverifiable"
        const val BLOCKER_IDENTITY_MISMATCH: String = "call_identity_mismatch"
    }
}

/**
 * 特权 Telecom 终止的安全默认实现：**永远返回** [RejectReason.UNSUPPORTED]。
 *
 * I6：旧实现反射调用 TelecomManager.endCall()——那是全局操作，会结束当前所有通话，
 * 完全忽略已核验的 [VerifiedCallIdentity]，可能替用户挂断无关电话。本实现因此绝不触碰
 * Telecom，也不把“拿到 TelecomManager 实例”当作可用证据。
 *
 * 只有当目标 ROM 上存在**可按本次通话身份定位**且经真机验证的终止 API 时，才允许把本类
 * 替换为真实实现；在那之前 [EmergencyCallControl.blocksAutoAnswer] 必须保持 true，
 * 不允许启用自动接管（spec §8）。
 *
 * @param telecomManagerProvider 仅为将来按身份终止适配保留的注入点；当前实现不调用它。
 */
class ReflectiveTelecomTermination(
    @Suppress("UNUSED_PARAMETER") private val telecomManagerProvider: () -> Any? = { null },
) : PrivilegedTermination {

    override suspend fun disconnect(verified: VerifiedCallIdentity): CommandResult {
        // 没有经过真机验证的按身份终止 API：明确不支持，绝不退化成全局 endCall。
        return CommandResult.Rejected(RejectReason.UNSUPPORTED)
    }
}

/**
 * 诚实的默认身份核对实现：当前目标 ROM 上尚未验证独立于 Hook 的通话身份来源。
 *
 * 它永远报告不可用，因此 [EmergencyCallControl] 会阻止自动接听。只有当设备验证任务
 * 给出可核对身份的机制后，才允许替换为真实实现并解除阻止。
 */
object UnverifiedCallIdentityVerifier : CallIdentityVerifier {

    override fun isAvailable(): Boolean = false

    override suspend fun verify(id: SessionId): VerifiedCallIdentity? = null
}
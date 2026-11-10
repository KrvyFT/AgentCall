package org.krvyft.agentcall.core.session

import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.SessionId

/**
 * 单会话租约登记表：每通电话至多一个有效租约，撤销是终态。
 *
 * 由监督端的单写者序列调用。租约是“可以针对这通电话下发命令”的凭据：撤销后
 * [Lease.authorize] 永远返回 null，旧租约不能把命令补发给新通话，也不能被重新取回。
 */
class LeaseRegistry {

    private val live = mutableSetOf<SessionId>()
    private val revoked = mutableSetOf<SessionId>()

    /** 取得 [id] 的租约；已存在有效租约或该会话曾被撤销时返回 null。 */
    @Synchronized
    fun acquire(id: SessionId): Lease? {
        if (id in revoked) return null
        if (!live.add(id)) return null
        return Lease(id, this)
    }

    /** 撤销 [id] 的有效租约；只有真的撤销了一个有效租约才返回 true。 */
    @Synchronized
    fun revoke(id: SessionId): Boolean {
        if (!live.remove(id)) return false
        revoked += id
        return true
    }

    @Synchronized
    fun isLive(id: SessionId): Boolean = id in live

    /** 租约仍有效时返回该命令；否则返回 null，调用方不得生成命令。 */
    @Synchronized
    internal fun authorize(id: SessionId, command: CallCommand): CallCommand? =
        if (id in live) command else null
}

/**
 * 一个通话会话的租约句柄。句柄本身不持有可变状态：每次下发命令都回到登记表核验，
 * 因此撤销对所有已发出的句柄立即生效。
 */
class Lease internal constructor(
    val id: SessionId,
    private val registry: LeaseRegistry,
) {
    val isLive: Boolean get() = registry.isLive(id)

    /** 租约仍有效时返回 [command]；已撤销时返回 null（不得产生任何命令）。 */
    fun authorize(command: CallCommand): CallCommand? = registry.authorize(id, command)
}

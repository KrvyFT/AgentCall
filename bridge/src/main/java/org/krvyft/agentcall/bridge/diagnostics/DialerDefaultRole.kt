package org.krvyft.agentcall.bridge.diagnostics

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.krvyft.agentcall.bridge.ipc.PeerVerifier

/**
 * 默认拨号器状态（spec §8 接听前检查 / §11 概览）。
 *
 * 线上表示是三态：[definite] 为 true/false 时 status 字段写布尔值，为 null 时写字符串
 * `"unknown"`。只有特权进程能可靠确定时才给布尔值——绝不用“看起来像”的信号拼结论。
 */
enum class DialerDefaultRole(val definite: Boolean?) {
    /** 系统 `ROLE_DIALER` 持有者是 Google 电话。 */
    GOOGLE_DIALER_IS_DEFAULT(true),

    /** 能确定 Google 电话不是默认拨号器（角色持有者是别的包，或默认处理器是别的应用）。 */
    GOOGLE_DIALER_IS_NOT_DEFAULT(false),

    /** 特权进程无法可靠确定（无 RoleManager/权限不足/解析到系统 Resolver/无候选）。 */
    UNKNOWN(null),
}

/**
 * 默认拨号器三态探测（阶段 B）。
 *
 * 判定顺序（只有能给出确定结论时才算数）：
 * 0. 角色机制本身不可用（`isRoleAvailable(ROLE_DIALER) == false`，即系统不支持该角色）时，
 *    “角色不可用”不等于“Google 电话不是默认拨号器”：直接 fail closed 为 UNKNOWN，
 *    既不看持有者列表，也不用默认处理器负证据；
 * 1. 系统 [RoleManager] 的 `ROLE_DIALER` 持有者列表可读时，它是唯一权威事实；
 * 2. 角色不可读（无系统服务/权限/异常）时退到 `ACTION_DIAL + CATEGORY_DEFAULT` 的默认
 *    处理器：处理器是其它应用 → 可确定为“不是”；处理器是 Google 电话 → 只证明它是当前
 *    处理器，**不**证明持有系统默认拨号器角色；解析到系统 Resolver（多候选）→ 不确定；
 * 3. 任何异常/无候选 → [DialerDefaultRole.UNKNOWN]。
 *
 * 本类只做本地查询：不调用 Telecom、不发远程请求、不修改任何角色。
 */
class DialerDefaultRoleProbe(
    private val roleHolders: () -> List<String>?,
    private val dialHandlerPackage: () -> String?,
    private val roleMechanismAvailable: () -> Boolean? = { true },
) {

    fun inspect(): DialerDefaultRole {
        // 角色机制不可用（系统不支持 ROLE_DIALER / 查询失败）不能当作负证据：
        // 只有“机制可用且持有者不是 Google 电话”才是确定的“不是默认拨号器”。
        val available = try {
            roleMechanismAvailable()
        } catch (t: Throwable) {
            null
        }
        if (available == false) return DialerDefaultRole.UNKNOWN
        val holders = try {
            roleHolders()
        } catch (t: Throwable) {
            null
        }
        if (holders != null) {
            return if (holders.any { it == PeerVerifier.DIALER_PACKAGE }) {
                DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT
            } else {
                DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT
            }
        }
        val handler = try {
            dialHandlerPackage()
        } catch (t: Throwable) {
            null
        } ?: return DialerDefaultRole.UNKNOWN
        if (handler == RESOLVER_PACKAGE) return DialerDefaultRole.UNKNOWN
        return if (handler == PeerVerifier.DIALER_PACKAGE) {
            // 处理器是 Google 电话只证明“默认处理器”，不证明“默认拨号器角色”。
            DialerDefaultRole.UNKNOWN
        } else {
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT
        }
    }

    companion object {
        /** 系统 Resolver Activity 的包名：出现它说明有多个候选，不是确定事实。 */
        const val RESOLVER_PACKAGE: String = "android"
    }
}

/**
 * 生产探测：系统 [RoleManager]（ROLE_DIALER 持有者）+ [PackageManager]
 * （`ACTION_DIAL` 默认处理器）。
 *
 * 两者都是本地调用。`RoleManager` 通过反射拿到的系统 Context 可能不可用（不同 ROM 的
 * 隐藏 API 策略不同）：此时角色事实不可读，由 [DialerDefaultRoleProbe] 回落为负证据/未知。
 * `isRoleAvailable(ROLE_DIALER) == false`（系统没有该角色机制）单独上报为“机制不可用”，
 * 由探测直接 fail closed 为 UNKNOWN，而不是当成“Google 电话不是默认拨号器”。
 */
class AndroidDialerDefaultRoleProbe(
    private val packageManager: PackageManager,
    private val roleManager: RoleManager?,
) : () -> DialerDefaultRole {

    private val probe = DialerDefaultRoleProbe(
        roleHolders = { roleHolders() },
        dialHandlerPackage = { dialHandlerPackage() },
        roleMechanismAvailable = { roleMechanismAvailable() },
    )

    override fun invoke(): DialerDefaultRole = probe.inspect()

    /**
     * 角色机制是否可用：`isRoleAvailable` 是公开 API，但系统服务/隐藏策略仍可能抛错，
     * 此时返回 null（未知），由探测继续走持有者/默认处理器路径，不猜。
     */
    private fun roleMechanismAvailable(): Boolean? {
        val manager = roleManager ?: return null
        return try {
            manager.isRoleAvailable(RoleManager.ROLE_DIALER)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 角色持有者列表。`getRoleHolders` 是 @SystemApi/隐藏方法，公开 SDK 里没有，
     * 因此用反射调用（root app_process 无 package，不属于普通应用的隐藏 API 策略）；
     * 反射失败/权限不足/隐藏 API 策略拒绝时返回 null，交给负证据/未知，不猜。
     */
    private fun roleHolders(): List<String>? {
        val manager = roleManager ?: return null
        return try {
            val method = manager.javaClass.getMethod("getRoleHolders", String::class.java)
            @Suppress("UNCHECKED_CAST")
            method.invoke(manager, RoleManager.ROLE_DIALER) as? List<String>
        } catch (t: Throwable) {
            null
        }
    }

    private fun dialHandlerPackage(): String? {
        // MATCH_DEFAULT_ONLY：只返回默认处理器，不返回候选列表。
        val withPhoneData = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "", null))
            .addCategory(Intent.CATEGORY_DEFAULT)
        val withoutData = Intent(Intent.ACTION_DIAL).addCategory(Intent.CATEGORY_DEFAULT)
        for (intent in listOf(withPhoneData, withoutData)) {
            val packageName = packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
            if (packageName != null) return packageName
        }
        return null
    }
}

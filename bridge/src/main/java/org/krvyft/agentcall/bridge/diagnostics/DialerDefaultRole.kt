package org.krvyft.agentcall.bridge.diagnostics

import android.app.role.RoleManager
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
 * 2. 角色不可读（无系统服务/权限/异常）时，Task 11Q-b 的固定 root 命令兜底
 *    （[ShellDialerDefaultRoleProbe]：`cmd role get-role-holders`，再
 *    `cmd telecom get-default-dialer`）给出确定结论时优先于 `ACTION_DIAL` 处理器——
 *    系统角色/Telecom 默认拨号器比 Intent 解析更强；
 * 3. 命令也给不出事实时，退到 `ACTION_DIAL + CATEGORY_DEFAULT` 的默认处理器：处理器是
 *    其它应用 → 可确定为“不是”；处理器是 Google 电话 → 只证明它是当前处理器，**不**
 *    证明持有系统默认拨号器角色；解析到系统 Resolver（多候选）→ 不确定；
 * 4. 任何异常/无候选 → [DialerDefaultRole.UNKNOWN]。
 *
 * 本类只做本地查询：不调用 Telecom 的 binder API、不发远程请求、不修改任何角色。
 */
class DialerDefaultRoleProbe(
    private val roleHolders: () -> List<String>?,
    private val dialHandlerPackage: () -> String?,
    private val roleMechanismAvailable: () -> Boolean? = { true },
    private val shellDialerRole: () -> DialerDefaultRole = { DialerDefaultRole.UNKNOWN },
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
        // Task 11Q-b：binder/context 读不到角色事实时，用固定的 root 命令兜底
        // （本 ROM 上 getRoleHolders 的反射路径失败，但 cmd role / cmd telecom 能看到真实值）。
        val shellRole = try {
            shellDialerRole()
        } catch (t: Throwable) {
            DialerDefaultRole.UNKNOWN
        }
        if (shellRole != DialerDefaultRole.UNKNOWN) return shellRole

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
 * 固定 shell 命令输出的纯解析（Task 11Q-b，JVM 可测）。
 *
 * 设备 XQ-DQ72（API 37）上 binder/context 路径读不到 ROLE_DIALER 持有者，但 root shell
 * 的固定命令能看到真实事实。两条命令的输出形态都很窄：一行包名，或错误文本/空。
 * 解析只接受恰好一个合法包名——多持有者、空、纯标点、错误文本、超长输出一律
 * UNKNOWN（fail closed，绝不猜）。
 */
object DialerShellParsing {

    /** 命令输出的字符上限：持有者/默认拨号器都只有一行包名，超出即不可用。 */
    const val MAX_OUTPUT_CHARS: Int = 8 * 1024

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*$")

    /** cmd role get-role-holders android.app.role.DIALER 的输出分类。 */
    fun classifyRoleHolders(output: String?): DialerDefaultRole = classifySinglePackage(output)

    /** cmd telecom get-default-dialer 的默认处理器输出分类（同一个 fail-closed 规则）。 */
    fun classifyDefaultDialer(output: String?): DialerDefaultRole = classifySinglePackage(output)

    /**
     * 输出的包名列表：null 表示输出不可用（超长、非包名行）；空输出返回空列表
     * （没有持有者）。重复行不是“多持有者”，按出现顺序去重。
     */
    fun packageNames(output: String?): List<String>? {
        if (output == null) return null
        if (output.length > MAX_OUTPUT_CHARS) return null
        val names = LinkedHashSet<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!PACKAGE_NAME.matches(line)) return null
            names.add(line)
        }
        return names.toList()
    }

    private fun classifySinglePackage(output: String?): DialerDefaultRole {
        val names = packageNames(output) ?: return DialerDefaultRole.UNKNOWN
        if (names.size != 1) return DialerDefaultRole.UNKNOWN
        return if (names.single() == PeerVerifier.DIALER_PACKAGE) {
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT
        } else {
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT
        }
    }
}

/**
 * 默认拨号器的固定命令兜底（Task 11Q-b）。
 *
 * 只有 binder/context 都读不到角色事实时才会被调用；命令是常量、不接受任何外部输入：
 * 1. /system/bin/cmd role get-role-holders android.app.role.DIALER
 * 2. /system/bin/cmd telecom get-default-dialer
 *
 * 每条命令都有短超时与输出上限（[SystemPackageShellCommandRunner]）；不可用/超时/非零
 * 退出都只表示“没有这个事实”，继续下一条，最终 UNKNOWN。结果（含 UNKNOWN）缓存
 * [CACHE_TTL_MS]，status 轮询不会每次开 shell。
 */
class ShellDialerDefaultRoleProbe(
    private val runner: PackageShellCommandRunner = SystemPackageShellCommandRunner(
        timeoutMs = COMMAND_TIMEOUT_MS,
        maxOutputChars = DialerShellParsing.MAX_OUTPUT_CHARS,
    ),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val log: (String) -> Unit = {},
) : () -> DialerDefaultRole {

    private data class Cached(val role: DialerDefaultRole, val atMs: Long)

    @Volatile
    private var cached: Cached? = null

    override fun invoke(): DialerDefaultRole {
        val now = clockMs()
        cached?.let { entry ->
            if (now - entry.atMs in 0 until cacheTtlMs) return entry.role
        }
        val role = resolve()
        cached = Cached(role, now)
        return role
    }

    private fun resolve(): DialerDefaultRole {
        val holders = run(ROLE_HOLDERS_COMMAND, "shell_role_holders")
        val holderRole = DialerShellParsing.classifyRoleHolders(holders)
        if (holderRole != DialerDefaultRole.UNKNOWN) return holderRole

        val defaultDialer = run(DEFAULT_DIALER_COMMAND, "shell_default_dialer")
        val defaultRole = DialerShellParsing.classifyDefaultDialer(defaultDialer)
        if (defaultRole != DialerDefaultRole.UNKNOWN) return defaultRole
        return DialerDefaultRole.UNKNOWN
    }

    /** runner 自身抛错（进程不可用）等同“没有事实”，不向外传播。 */
    private fun run(command: List<String>, source: String): String? = try {
        runner.run(command)
    } catch (t: Throwable) {
        log("dialer_role_unavailable source=" + source + " code=runner_throwable")
        null
    }

    companion object {
        /** 每条命令的短超时：默认拨号器探测不能拖住 status。 */
        const val COMMAND_TIMEOUT_MS: Long = 2_000L

        /** 结果缓存上限（含 UNKNOWN）：status 轮询不必每次开 shell。 */
        const val CACHE_TTL_MS: Long = 30_000L

        /** 固定的 role 命令（无外部输入拼接）。 */
        val ROLE_HOLDERS_COMMAND: List<String> = listOf(
            "/system/bin/cmd", "role", "get-role-holders", "android.app.role.DIALER",
        )

        /** 固定的 telecom 命令（无外部输入拼接）。 */
        val DEFAULT_DIALER_COMMAND: List<String> = listOf(
            "/system/bin/cmd", "telecom", "get-default-dialer",
        )
    }
}

/**
 * 生产探测：系统 [RoleManager]（ROLE_DIALER 持有者）+ 分层包事实 provider 的
 * `ACTION_DIAL` 默认处理器。
 *
 * 两者都是本地调用。`RoleManager` 通过反射拿到的系统 Context 可能不可用（不同 ROM 的
 * 隐藏 API 策略不同）：此时角色事实不可读，由 [DialerDefaultRoleProbe] 回落为负证据/未知。
 * `isRoleAvailable(ROLE_DIALER) == false`（系统没有该角色机制）单独上报为“机制不可用”，
 * 由探测直接 fail closed 为 UNKNOWN，而不是当成“Google 电话不是默认拨号器”。
 *
 * Task 11N：处理器事实不再直接依赖 PackageManager——context provider 用公开
 * `resolveActivity`，AppGlobals/binder provider 用反射 `resolveIntent`，shell provider 用
 * `resolve-activity --brief`；任一路径拿不到事实时返回 null，判定保持 UNKNOWN。
 */
class AndroidDialerDefaultRoleProbe(
    private val dialHandler: () -> String?,
    private val roleManager: RoleManager?,
    /** 机器码日志；默认丢弃，生产装配显式接 stderr。绝不输出异常文本/路径。 */
    private val log: (String) -> Unit = {},
    /** binder/context 之后的固定命令兜底（Task 11Q-b）；生产用真实进程，测试可注入。 */
    private val shellDialerRole: () -> DialerDefaultRole = ShellDialerDefaultRoleProbe(log = log),
) : () -> DialerDefaultRole {

    private val probe = DialerDefaultRoleProbe(
        roleHolders = { roleHolders() },
        dialHandlerPackage = { dialHandler() },
        roleMechanismAvailable = { roleMechanismAvailable() },
        shellDialerRole = shellDialerRole,
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
            log("dialer_role_unavailable source=role_available code=reflection_throwable")
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
            log("dialer_role_unavailable source=role_holders code=hidden_api_reflection_failed")
            null
        }
    }

}

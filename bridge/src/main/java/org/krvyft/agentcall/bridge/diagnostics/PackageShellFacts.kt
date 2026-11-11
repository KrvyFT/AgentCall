package org.krvyft.agentcall.bridge.diagnostics

import android.content.Intent
import java.util.concurrent.TimeUnit
import org.krvyft.agentcall.bridge.ipc.PackageFacts
import org.krvyft.agentcall.bridge.ipc.PackageFactsSource

/**
 * 执行一条 shell 命令并返回合并输出；进程不可用/超时/非零退出返回 null。
 * 测试用替身实现，JVM 不需要真的执行 pm。
 */
fun interface PackageShellCommandRunner {
    fun run(command: List<String>): String?
}

/**
 * 包事实的 shell 回退（分层获取的最后一步）。
 *
 * 只读取能证明的最小事实：`pm list packages -U`（uid→包名）、
 * `dumpsys package <pkg>` / `pm dump <pkg>`（versionName、userId）。**签名摘要没有
 * 可验证来源，[signatureSha256] 永远返回 null（unsupported）**；PeerVerifier 因此会
 * 拒绝非 root 对端，绝不把“读不到签名”当成“签名匹配”。
 *
 * 命令固定为 /system/bin/pm、/system/bin/cmd、/system/bin/dumpsys，不接受任何外部
 * 输入拼接；每条命令都有超时与输出上限。
 */
class ShellPackageFactsSource(
    private val runner: PackageShellCommandRunner = SystemPackageShellCommandRunner(),
) : PackageFactsSource {

    override fun packagesForUid(uid: Int): List<String> {
        if (uid < 0) return emptyList()
        val output = runner.run(listOf(PM, "list", "packages", "-U"))
            ?: runner.run(listOf(CMD, "package", "list", "packages", "-U"))
            ?: return emptyList()
        return PackageShellParsing.packagesForUid(output, uid)
    }

    override fun versionName(packageName: String): String? = packageFacts(packageName).versionName

    /** 显式 unsupported：shell 路径没有证书字节，返回 null 即“签名未知”。 */
    override fun signatureSha256(packageName: String): String? = null

    override fun uidForPackage(packageName: String): Int? = packageFacts(packageName).uid

    /**
     * `ACTION_DIAL` 默认处理器：`cmd package resolve-activity --brief` / `pm resolve-activity --brief`。
     * 输出无组件/无法解析时返回 null（探测保持 UNKNOWN）。
     */
    override fun dialHandlerPackage(): String? {
        val output = runner.run(
            listOf(
                CMD, "package", "resolve-activity", "--brief",
                "-a", Intent.ACTION_DIAL, "-c", Intent.CATEGORY_DEFAULT,
            ),
        ) ?: runner.run(
            listOf(
                PM, "resolve-activity", "--brief",
                "-a", Intent.ACTION_DIAL, "-c", Intent.CATEGORY_DEFAULT,
            ),
        ) ?: return null
        return PackageShellParsing.dialHandlerPackage(output)
    }

    override fun packageFacts(packageName: String): PackageFacts {
        val dump = dump(packageName) ?: return PackageFacts(packageName)
        return PackageFacts(
            packageName = packageName,
            versionName = PackageShellParsing.versionName(dump),
            signatureSha256 = null,
            uid = PackageShellParsing.userId(dump),
        )
    }

    private fun dump(packageName: String): String? =
        runner.run(listOf(DUMPSYS, "package", packageName))
            ?: runner.run(listOf(PM, "dump", packageName))
            ?: runner.run(listOf(CMD, "package", "dump", packageName))

    private companion object {
        const val PM = "/system/bin/pm"
        const val CMD = "/system/bin/cmd"
        const val DUMPSYS = "/system/bin/dumpsys"
    }
}

/**
 * shell 输出的纯解析（JVM 可测，无 Android 依赖）。
 *
 * 只接受设备命令的固定字段格式；没有 uid 的 `pm list` 行不归属任何 uid，损坏的
 * 数字/超长/控制字符值一律当作“没有这个事实”，不猜测。
 */
object PackageShellParsing {

    private const val PACKAGE_PREFIX = "package:"
    private const val UID_PREFIX = "uid:"
    private const val VERSION_NAME_PREFIX = "versionName="
    private const val USER_ID_PREFIX = "userId="
    private const val NULL_VALUE = "null"
    private const val MAX_VALUE_CHARS = 128
    private val WHITESPACE = Regex("\\s+")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*$")

    /** `pm list packages -U`：`package:<name> uid:<uid>`，共享 uid 保留全部包名。 */
    fun packagesForUid(output: String, uid: Int): List<String> {
        if (uid < 0) return emptyList()
        val packages = LinkedHashSet<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith(PACKAGE_PREFIX)) continue
            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            val packageName = tokens.firstOrNull()?.substring(PACKAGE_PREFIX.length) ?: continue
            if (!PACKAGE_NAME.matches(packageName)) continue
            val owner = tokens.drop(1)
                .firstOrNull { it.startsWith(UID_PREFIX) }
                ?.substring(UID_PREFIX.length)
                ?.toIntOrNull()
                ?: continue
            if (owner == uid) packages.add(packageName)
        }
        return packages.toList()
    }

    /** `dumpsys package <pkg>` 的 versionName；null/空/超长/控制字符都视为未知。 */
    fun versionName(output: String): String? =
        fieldValue(output, VERSION_NAME_PREFIX)?.takeIf { it != NULL_VALUE }

    /** `dumpsys package <pkg>` 的 userId；无法解析返回 null。 */
    fun userId(output: String): Int? = fieldValue(output, USER_ID_PREFIX)?.toIntOrNull()

    /**
     * `resolve-activity --brief` 的组件行（`com.foo/.Activity`）→ 包名。
     * 只接受合法包名；`No activity found`/空输出/无斜杠行都返回 null。
     */
    fun dialHandlerPackage(output: String): String? {
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (!line.contains("/")) continue
            val candidate = line.substringBefore("/").trim()
            if (PACKAGE_NAME.matches(candidate)) return candidate
        }
        return null
    }

    private fun fieldValue(output: String, prefix: String): String? {
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith(prefix)) continue
            val value = line.substring(prefix.length).trim()
            if (value.isEmpty() || value.length > MAX_VALUE_CHARS) return null
            if (value.any { it.isISOControl() }) return null
            return value
        }
        return null
    }
}

/**
 * 真实进程执行器：ProcessBuilder + 超时 + 输出上限。
 *
 * 读线程与 waitFor 并行，避免大输出（dumpsys）填满管道导致假超时；超时/非零退出都
 * 返回 null，调用方只看到“没有事实”。
 */
class SystemPackageShellCommandRunner(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
) : PackageShellCommandRunner {

    override fun run(command: List<String>): String? {
        if (command.isEmpty()) return null
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return null
        }
        val output = StringBuilder()
        val reader = Thread(
            {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (output.length >= maxOutputChars) break
                            output.append(line).append('\n')
                        }
                    }
                } catch (t: Throwable) {
                    // 读线程失败等同“没有事实”：由退出码/超时统一收敛。
                }
            },
            "agentcall-pm-reader",
        )
        reader.isDaemon = true
        reader.start()
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                reader.join(READER_JOIN_MS)
                output.toString()
            }
        } catch (t: Throwable) {
            null
        } finally {
            process.destroy()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val DEFAULT_MAX_OUTPUT_CHARS = 512 * 1024
        const val READER_JOIN_MS = 1_000L
    }
}

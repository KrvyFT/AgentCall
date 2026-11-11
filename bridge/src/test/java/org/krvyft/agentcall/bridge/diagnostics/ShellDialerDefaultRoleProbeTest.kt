package org.krvyft.agentcall.bridge.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Task 11Q-b：shell 兜底探测的命令契约、失败收敛与 30 s 缓存（纯 JVM）。
 *
 * 命令必须是**固定**的（不接受任何外部输入拼接），且顺序固定：先
 * `cmd role get-role-holders android.app.role.DIALER`，再 `cmd telecom get-default-dialer`。
 * 每条命令都有超时与输出上限（由 [SystemPackageShellCommandRunner] 提供）；任何
 * 不可用/超时/非零退出都只意味着“没有这个事实”，不是负证据。
 */
class ShellDialerDefaultRoleProbeTest {

    private val roleCommand = listOf(
        "/system/bin/cmd", "role", "get-role-holders", "android.app.role.DIALER",
    )
    private val telecomCommand = listOf("/system/bin/cmd", "telecom", "get-default-dialer")

    private class RecordingRunner(
        val calls: MutableList<List<String>> = mutableListOf(),
        private val respond: (List<String>) -> String?,
    ) : PackageShellCommandRunner {
        override fun run(command: List<String>): String? {
            calls += command
            return respond(command)
        }
    }

    private fun probe(
        runner: PackageShellCommandRunner,
        clock: () -> Long = { 0L },
    ): ShellDialerDefaultRoleProbe = ShellDialerDefaultRoleProbe(
        runner = runner,
        clockMs = clock,
    )

    @Test
    fun roleHoldersAreQueriedWithTheFixedDeviceCommandFirst() {
        val runner = RecordingRunner { "com.google.android.dialer\n" }

        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            probe(runner).invoke(),
        )
        assertEquals("只有固定命令，且确定结论后不再跑第二条", listOf(roleCommand), runner.calls)
    }

    @Test
    fun telecomDefaultDialerIsTheSecondFixedCommand() {
        val runner = RecordingRunner { command ->
            if (command == roleCommand) null else "com.google.android.dialer\n"
        }

        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            probe(runner).invoke(),
        )
        assertEquals(listOf(roleCommand, telecomCommand), runner.calls)
    }

    @Test
    fun anUnusableFirstCommandStillAllowsTheSecondToDecide() {
        // 空输出/错误文本/非零退出在 runner 层都表现为 null 或不可解析文本。
        for (roleOutput in listOf(null, "", ",", "Error: unknown role")) {
            val runner = RecordingRunner { command ->
                if (command == roleCommand) roleOutput else "com.android.dialer\n"
            }

            assertEquals(
                "第一条命令不可用时必须继续跑第二条：" + roleOutput,
                DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT,
                probe(runner).invoke(),
            )
            assertEquals(listOf(roleCommand, telecomCommand), runner.calls)
        }
    }

    @Test
    fun everyCommandUnavailableStaysUnknown() {
        val runner = RecordingRunner { null }

        assertEquals(DialerDefaultRole.UNKNOWN, probe(runner).invoke())
        assertEquals(listOf(roleCommand, telecomCommand), runner.calls)
    }

    @Test
    fun runnerExceptionIsContainedAsUnknown() {
        val runner = PackageShellCommandRunner { throw IllegalStateException("process") }

        assertEquals(DialerDefaultRole.UNKNOWN, probe(runner).invoke())
    }

    @Test
    fun definiteResultIsCachedForThirtySeconds() {
        var now = 1_000L
        val runner = RecordingRunner { "com.google.android.dialer\n" }
        val probe = probe(runner, clock = { now })

        assertEquals(DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT, probe.invoke())
        now += 29_999L
        assertEquals(DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT, probe.invoke())
        assertEquals("TTL 内不得再开 shell", 1, runner.calls.size)

        now += 1L
        assertEquals(DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT, probe.invoke())
        assertEquals("TTL 到期后重新探测", 2, runner.calls.size)
    }

    @Test
    fun unknownResultIsAlsoCachedSoPollsDoNotSpawnShells() {
        var now = 0L
        val runner = RecordingRunner { null }
        val probe = probe(runner, clock = { now })

        assertEquals(DialerDefaultRole.UNKNOWN, probe.invoke())
        assertEquals(2, runner.calls.size)
        now += 10_000L
        assertEquals(DialerDefaultRole.UNKNOWN, probe.invoke())
        assertEquals("未知结果同样缓存 30 s", 2, runner.calls.size)

        now += 20_000L
        assertEquals(DialerDefaultRole.UNKNOWN, probe.invoke())
        assertEquals(4, runner.calls.size)
    }

    @Test
    fun realRunnerTimesOutInsteadOfBlocking() {
        assumeTrue("/bin/sh is required for this POSIX-only check", File("/bin/sh").canExecute())

        val runner = SystemPackageShellCommandRunner(timeoutMs = 100L)

        assertNull(runner.run(listOf("/bin/sh", "-c", "sleep 5")))
    }

    @Test
    fun realRunnerTreatsNonZeroExitAsNoFact() {
        assumeTrue("/bin/sh is required for this POSIX-only check", File("/bin/sh").canExecute())

        val runner = SystemPackageShellCommandRunner(timeoutMs = 5_000L)

        assertNull(runner.run(listOf("/bin/sh", "-c", "echo boom >&2; exit 3")))
    }
}

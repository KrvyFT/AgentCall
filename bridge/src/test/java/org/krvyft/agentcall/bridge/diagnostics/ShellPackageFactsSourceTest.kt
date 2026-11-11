package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 回退事实源的边界（JVM，命令执行被替身替换）。
 *
 * shell 只能证明 uid→包名、versionName、uid；签名摘要没有可验证来源，必须显式返回
 * null（unsupported），由 PeerVerifier 拒绝，绝不把“读不到签名”当成“签名匹配”。
 */
class ShellPackageFactsSourceTest {

    @Test
    fun packagesForUidRunsPmListWithUidOutput() {
        val commands = mutableListOf<List<String>>()
        val source = ShellPackageFactsSource { command ->
            commands += command
            "package:com.foo uid:1000\npackage:com.bar uid:1001"
        }

        assertEquals(listOf("com.foo"), source.packagesForUid(1000))
        assertEquals(1, commands.size)
        assertTrue(commands.single().containsAll(listOf("list", "packages", "-U")))
    }

    @Test
    fun fallsBackToCmdPackageWhenPmIsUnavailable() {
        val commands = mutableListOf<List<String>>()
        val source = ShellPackageFactsSource { command ->
            commands += command
            if (command.first().endsWith("/pm")) {
                null
            } else {
                "package:com.foo uid:1000"
            }
        }

        assertEquals(listOf("com.foo"), source.packagesForUid(1000))
        assertEquals(2, commands.size)
        assertTrue(commands[1].first().endsWith("/cmd"))
        assertTrue(commands[1].containsAll(listOf("package", "list", "packages", "-U")))
    }

    @Test
    fun bothShellCommandsFailingYieldsNoPackages() {
        val source = ShellPackageFactsSource { null }

        assertEquals(emptyList<String>(), source.packagesForUid(1000))
    }

    @Test
    fun signatureVerificationIsExplicitlyUnsupportedOnTheShellPath() {
        val source = ShellPackageFactsSource { command ->
            if (command.contains("list")) {
                "package:com.google.android.dialer uid:10123"
            } else {
                "    userId=10123\n    versionName=104.0"
            }
        }

        assertNull("shell 不能证明签名：必须显式 unsupported", source.signatureSha256("com.google.android.dialer"))
        val facts = source.dialerPackageFacts()
        assertEquals("com.google.android.dialer", facts.packageName)
        assertEquals("104.0", facts.versionName)
        assertEquals(10123, facts.uid)
        assertNull("签名仍必须是显式未知", facts.signatureSha256)
    }

    @Test
    fun dumpFallsBackFromDumpsysToPmDump() {
        val commands = mutableListOf<List<String>>()
        val source = ShellPackageFactsSource { command ->
            commands += command
            when {
                command.contains("dumpsys") -> null
                command.any { it.endsWith("/pm") } && command.contains("dump") -> "  versionName=9.9"
                else -> null
            }
        }

        assertEquals("9.9", source.versionName("com.foo"))
        assertTrue(commands.any { command -> command.any { it.contains("dumpsys") } })
        assertTrue(commands.any { it.contains("dump") })
    }

    @Test
    fun dialHandlerPackageComesFromResolveActivityBrief() {
        val commands = mutableListOf<List<String>>()
        val source = ShellPackageFactsSource { command ->
            commands += command
            if (command.contains("resolve-activity")) {
                "com.google.android.dialer/.ui.DialtactsActivity"
            } else {
                null
            }
        }

        assertEquals("com.google.android.dialer", source.dialHandlerPackage())
        assertTrue(commands.single().first().endsWith("/cmd"))
    }

    @Test
    fun dialHandlerIsNullWhenNoResolverActivityIsFound() {
        val source = ShellPackageFactsSource { "No activity found" }

        assertNull(source.dialHandlerPackage())
    }

    @Test
    fun versionNameIsUnknownWhenEveryDumpFails() {
        val source = ShellPackageFactsSource { null }

        assertNull(source.versionName("com.foo"))
        assertNull(source.uidForPackage("com.foo"))
    }
}

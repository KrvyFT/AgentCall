package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.ipc.PackageFacts
import org.krvyft.agentcall.bridge.ipc.PackageFactsSource
import org.krvyft.agentcall.bridge.ipc.PackageProviderKind

/**
 * Provider 选择顺序与缓存的纯 JVM 测试。
 *
 * 顺序是安全契约：app_process 先尝试 ActivityThread 系统 Context（真机上已有实现），
 * 失败后退到 AppGlobals / ServiceManager + IPackageManager binder，最后才是 shell
 * 事实源；**绝不是**“任何一个失败就退到 shell”。shell 的签名恒为不可验证，连接核验
 * 会 fail closed。选择结果必须写成一行机器可读码（package_provider=...）。
 */
class PackageProviderChainTest {

    private class FakeSource(private val name: String) : PackageFactsSource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = null
        override fun versionName(packageName: String): String? = null
        override fun uidForPackage(packageName: String): Int? = null
        override fun dialerPackageFacts(): PackageFacts = PackageFacts(name, null, null, null)
    }

    private fun candidate(
        kind: PackageProviderKind,
        calls: MutableList<String>,
        source: () -> PackageFactsSource?,
    ) = PackageProviderCandidate(kind) {
        calls += kind.wireCode
        source()
    }

    @Test
    fun firstWorkingProviderWinsInTheDeclaredOrder() {
        val calls = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val chain = PackageProviderChain(
            listOf(
                candidate(PackageProviderKind.CONTEXT, calls) { null },
                candidate(PackageProviderKind.APP_GLOBALS, calls) { throw IllegalStateException("hidden api") },
                candidate(PackageProviderKind.BINDER, calls) { FakeSource("binder") },
                candidate(PackageProviderKind.SHELL, calls) { FakeSource("shell") },
            ),
        ) { logs += it }

        val resolved = chain.resolve()

        assertNotNull(resolved)
        assertEquals(PackageProviderKind.BINDER, resolved!!.kind)
        assertEquals("只能尝试到第一个成功的 provider", listOf("context", "appglobals", "binder"), calls)
        assertTrue(logs.contains("package_provider=binder"))
        assertTrue(logs.contains("package_provider_attempt=context result=unavailable"))
        assertTrue(logs.contains("package_provider_attempt=appglobals result=unavailable"))
    }

    @Test
    fun contextRemainsTheFirstAttempt() {
        val calls = mutableListOf<String>()
        val chain = PackageProviderChain(
            listOf(
                candidate(PackageProviderKind.CONTEXT, calls) { FakeSource("context") },
                candidate(PackageProviderKind.SHELL, calls) { FakeSource("shell") },
            ),
        )

        assertEquals(PackageProviderKind.CONTEXT, chain.resolve()!!.kind)
        assertEquals(listOf("context"), calls)
    }

    @Test
    fun shellIsOnlyTriedAfterEveryBinderPathFails() {
        val calls = mutableListOf<String>()
        val chain = PackageProviderChain(
            listOf(
                candidate(PackageProviderKind.CONTEXT, calls) { null },
                candidate(PackageProviderKind.APP_GLOBALS, calls) { null },
                candidate(PackageProviderKind.BINDER, calls) { null },
                candidate(PackageProviderKind.SHELL, calls) { FakeSource("shell") },
            ),
        )

        assertEquals(PackageProviderKind.SHELL, chain.resolve()!!.kind)
        assertEquals(listOf("context", "appglobals", "binder", "shell"), calls)
    }

    @Test
    fun allCandidatesFailingYieldsNoProviderAndAMachineCode() {
        val logs = mutableListOf<String>()
        val chain = PackageProviderChain(
            listOf(
                PackageProviderCandidate(PackageProviderKind.CONTEXT) { null },
                PackageProviderCandidate(PackageProviderKind.SHELL) { throw IllegalStateException("no shell") },
            ),
        ) { logs += it }

        assertNull(chain.resolve())
        assertTrue(logs.contains("package_provider_unavailable candidates=context,shell"))
    }

    @Test
    fun resolvedProviderIsCachedAndCreatorsAreNotReRun() {
        var created = 0
        val chain = PackageProviderChain(
            listOf(
                PackageProviderCandidate(PackageProviderKind.BINDER) {
                    created += 1
                    FakeSource("binder")
                },
            ),
        )

        val first = chain.resolve()
        val second = chain.resolve()

        assertSame(first, second)
        assertEquals(1, created)
    }

    @Test
    fun productionCandidatesAreContextThenBinderPathsThenShell() {
        assertEquals(
            listOf(
                PackageProviderKind.CONTEXT,
                PackageProviderKind.APP_GLOBALS,
                PackageProviderKind.BINDER,
                PackageProviderKind.SHELL,
            ),
            AndroidPackageFactsProvider.candidateKinds,
        )
    }

    @Test
    fun eachProviderKindHasTheExpectedDeviceLogCode() {
        assertEquals("context", PackageProviderKind.CONTEXT.wireCode)
        assertEquals("appglobals", PackageProviderKind.APP_GLOBALS.wireCode)
        assertEquals("binder", PackageProviderKind.BINDER.wireCode)
        assertEquals("shell", PackageProviderKind.SHELL.wireCode)
    }
}

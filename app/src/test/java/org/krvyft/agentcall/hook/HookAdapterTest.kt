package org.krvyft.agentcall.hook

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hook 适配层与安装闸门的 JVM 测试。
 *
 * 这是评审第 7 条要求的“真正测试 Hook 包装/原方法保证”：不再在 guard 之后直接
 * 反射调用原方法，而是验证生产适配器本身既不传播异常、也把参数原样交出去。
 */
class HookAdapterTest {

    @Test
    fun throwingCallbackIsIsolatedAndOriginalMethodStillRuns() {
        val logs = mutableListOf<String>()
        var originalRuns = 0
        val adapter = HookCallbackAdapter(
            onCallback = { _, _, _ -> throw IllegalStateException("deliberate hook failure") },
            logger = logs::add,
        )

        val completed = adapter.before("onCallAdded", Any(), arrayOfNulls(1))
        // Xposed 在 before hook 返回后仍执行原方法：适配器不得抛异常打断它。
        originalRuns += 1

        assertFalse(completed)
        assertEquals(1, originalRuns)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("InCallService.onCallAdded"))
    }

    @Test
    fun callbackReceivesTargetAndArgumentsVerbatim() {
        val seen = mutableListOf<Triple<String, Any, Array<Any?>?>>()
        val adapter = HookCallbackAdapter(
            onCallback = { name, target, args -> seen += Triple(name, target, args) },
        )
        val service = Any()
        val args = arrayOfNulls<Any?>(1)

        assertTrue(adapter.before("onCallAdded", service, args))

        assertEquals(1, seen.size)
        assertEquals("onCallAdded", seen[0].first)
        assertSame(service, seen[0].second)
        assertSame(args, seen[0].third)
    }

    @Test
    fun missingTargetIsIgnoredWithoutFailingTheHook() {
        var calls = 0
        val adapter = HookCallbackAdapter(onCallback = { _, _, _ -> calls += 1 })

        assertTrue(adapter.before("onCallAdded", null, null))
        assertEquals(0, calls)
    }

    @Test
    fun installationIsClaimedExactlyOncePerProcess() {
        HookInstallGuard.resetForTest()

        assertTrue(HookInstallGuard.claim())
        assertFalse("a second handleLoadPackage must not duplicate hooks", HookInstallGuard.claim())
        assertFalse(HookInstallGuard.claim())
    }

    @Test
    fun aFailedInstallCanReleaseTheClaimAndRetry() {
        // M6：闸门只能在安装成功后永久占用；安装失败必须能重试，否则一次早期的
        // PackageManager 未就绪会让本进程永久失去 Hook。
        HookInstallGuard.resetForTest()

        assertTrue(HookInstallGuard.claim())
        HookInstallGuard.release()
        assertTrue(HookInstallGuard.claim())
        assertFalse(HookInstallGuard.claim())
    }

    @Test
    fun zeroSuccessfulHooksReleaseTheGuardSoTheProcessCanRetry() {
        // Task 11G 3：install() 吞掉每个 hookMethod 失败后，旧代码仍把闸门当作“已安装”，
        // 进程从此无法重试。零成功必须释放闸门。
        HookInstallGuard.resetForTest()
        val methods = listOf(lengthMethod())
        val installer = HookMethodInstaller(hook = { throw IllegalStateException("hook rejected") })
        assertTrue(HookInstallGuard.claim())

        val installed = holdGuardAfterInstallAttempt(
            installNow = { installer.install(methods) },
            defer = { false },
        )

        assertFalse(installed)
        assertEquals(0, installer.installedCount())
        assertTrue("zero hooks must release the guard so the process can retry", HookInstallGuard.claim())
    }

    @Test
    fun failedMethodsAreRetriedAndASuccessfulHookKeepsTheGuard() {
        HookInstallGuard.resetForTest()
        val method = lengthMethod()
        val attempted = mutableListOf<Method>()
        var reject = true
        val installer = HookMethodInstaller(
            hook = { candidate ->
                attempted += candidate
                if (reject) throw IllegalStateException("hook rejected")
            },
        )
        assertTrue(HookInstallGuard.claim())

        assertFalse(
            "a zero-hook attempt must not keep the guard",
            holdGuardAfterInstallAttempt({ installer.install(listOf(method)) }, { false }),
        )
        assertEquals(0, installer.installedCount())
        assertTrue("after a zero-hook attempt the process must be able to retry", HookInstallGuard.claim())

        reject = false
        assertTrue(holdGuardAfterInstallAttempt({ installer.install(listOf(method)) }, { false }))
        assertEquals("the failed method must be retried, not assumed installed", 2, attempted.size)
        assertEquals(1, installer.installedCount())
        assertFalse("at least one successful hook keeps the guard claimed", HookInstallGuard.claim())
    }

    @Test
    fun deferredInstallationAloneKeepsTheGuardBecauseAHookWasInstalled() {
        HookInstallGuard.resetForTest()
        assertTrue(HookInstallGuard.claim())

        assertTrue(holdGuardAfterInstallAttempt(installNow = { false }, defer = { true }))
        assertFalse("the deferred hook is a real hook and must keep the guard", HookInstallGuard.claim())
    }

    private fun lengthMethod(): Method = String::class.java.getDeclaredMethod("length")
}

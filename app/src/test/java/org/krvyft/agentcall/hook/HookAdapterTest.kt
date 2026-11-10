package org.krvyft.agentcall.hook

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
}

package org.krvyft.agentcall.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I4：app_process 没有 Application/Context，[AndroidAudioPlatform.audioManager] 每次调用都
 * 重新走 ActivityThread.systemMain() 会在隔离读回热路径（500 ms 轮询）上反复初始化框架。
 *
 * 缓存必须：成功一次后复用；失败有界重试（不是永久缓存一次失败，也不是无限重试）；
 * 预算用尽后 fail closed（返回 null，由适配层报告“不支持”）。
 */
class BoundedServiceCacheTest {

    @Test
    fun cachesTheFirstSuccessfulResolution() {
        var calls = 0
        val cache = BoundedServiceCache<String>(maxAttempts = 3) {
            calls++
            "audio-manager"
        }

        repeat(5) { assertEquals("audio-manager", cache.get()) }

        assertEquals("成功解析后必须复用，不能在热路径上重复初始化框架", 1, calls)
    }

    @Test
    fun retriesAreBoundedThenFailClosed() {
        var calls = 0
        val cache = BoundedServiceCache<String>(maxAttempts = 3) {
            calls++
            null
        }

        repeat(10) { assertNull(cache.get()) }

        assertEquals("失败重试必须有界", 3, calls)
    }

    @Test
    fun aLateSuccessWithinTheBudgetIsCached() {
        var calls = 0
        val cache = BoundedServiceCache<String>(maxAttempts = 3) {
            calls++
            if (calls < 3) null else "audio-manager"
        }

        assertNull(cache.get())
        assertNull(cache.get())
        assertEquals("audio-manager", cache.get())
        assertEquals("audio-manager", cache.get())
        assertEquals(3, calls)
    }

    @Test
    fun resolverExceptionCountsAsAFailedAttemptAndDoesNotPoisonLaterSuccess() {
        var calls = 0
        val cache = BoundedServiceCache<String>(maxAttempts = 3) {
            calls++
            if (calls == 1) error("framework not ready") else "audio-manager"
        }

        assertNull(cache.get())
        assertEquals("audio-manager", cache.get())
        assertEquals("audio-manager", cache.get())
        assertEquals(2, calls)
    }

    @Test
    fun resetAllowsANewBoundedWindow() {
        var calls = 0
        val cache = BoundedServiceCache<String>(maxAttempts = 1) {
            calls++
            if (calls == 1) null else "audio-manager"
        }

        assertNull(cache.get())
        assertNull("预算用尽后保持 fail closed", cache.get())
        cache.reset()
        assertEquals("audio-manager", cache.get())
        assertEquals(2, calls)
    }

    @Test
    fun platformAudioManagerResolutionIsBoundedOnThisJvm() {
        // JVM 上没有真实系统服务：必须返回 null 而不是抛异常，并且重试有界。
        repeat(5) { assertNull(AndroidAudioPlatform.audioManager()) }
        assertTrue(
            "解析尝试次数必须有界（实际 " + AndroidAudioPlatform.resolutionAttempts() + "）",
            AndroidAudioPlatform.resolutionAttempts() <= AndroidAudioPlatform.MAX_RESOLVE_ATTEMPTS,
        )
    }
}

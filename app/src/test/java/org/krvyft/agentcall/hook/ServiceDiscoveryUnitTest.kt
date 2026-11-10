package org.krvyft.agentcall.hook

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ServiceDiscovery] 选择的 JVM 测试（不构造 ServiceInfo，直接喂组件名/包名）。
 *
 * 覆盖评审要求：**每个组件独立回退**（某个组件继承基类回调时也必须被 Hook）、
 * 父子类链去重、按包名过滤；另覆盖本任务新增的 InCallService 音频路由回调，
 * 这是“路由变化在本进程内保守交还”的公开 API 路径。
 *
 * 组件类继承 android.telecom.InCallService，但只做类加载与反射，不实例化。
 */
class ServiceDiscoveryUnitTest {

    private val loader = javaClass.classLoader!!

    @Test
    fun perServiceFallbackAlsoHooksComponentsThatInheritTheBaseCallback() {
        val methods = ServiceDiscovery.resolveCandidates(
            loader,
            listOf(dialer(OverridingDialerService::class.java), dialer(InheritingDialerService::class.java)),
        )

        val added = methods.filter { it.name == "onCallAdded" }
        // 覆写组件 Hook 自己的实现，继承组件仍要 Hook 基类实现；不能因为“有人覆写”就跳过它。
        assertEquals(2, added.size)
        assertTrue(added.any { it.declaringClass == OverridingDialerService::class.java })
        assertTrue(added.any { it.declaringClass == InCallService::class.java })
    }

    @Test
    fun parentChildChainAndRepeatedRegistrationsAreDeduped() {
        val methods = ServiceDiscovery.resolveCandidates(
            loader,
            listOf(
                dialer(ChildDialerService::class.java),
                dialer(ParentDialerService::class.java),
                dialer(ChildDialerService::class.java),
            ),
        )

        val added = methods.filter { it.name == "onCallAdded" }
        assertEquals(1, added.size)
        assertEquals(ParentDialerService::class.java, added.single().declaringClass)
        assertEquals(1, methods.count { it.name == "onCallRemoved" })
    }

    @Test
    fun onlyComponentsDeclaredByTheDialerPackageAreResolved() {
        val foreign = ServiceCandidate(
            name = OverridingDialerService::class.java.name,
            packageName = "com.example.not.the.dialer",
        )
        val missingPackage = ServiceCandidate(name = OverridingDialerService::class.java.name, packageName = null)

        assertTrue(ServiceDiscovery.resolveCandidates(loader, listOf(foreign, missingPackage)).isEmpty())
        assertTrue(
            ServiceDiscovery.resolveCandidates(
                loader,
                listOf(foreign, dialer(OverridingDialerService::class.java)),
            ).isNotEmpty(),
        )
    }

    @Test
    fun nonInCallServiceTypesAreIgnored() {
        val methods = ServiceDiscovery.resolveCandidates(
            loader,
            listOf(ServiceCandidate(name = String::class.java.name, packageName = ServiceDiscovery.DIALER_PACKAGE)),
        )
        assertTrue(methods.isEmpty())
    }

    @Test
    fun audioRouteCallbacksAreDiscoveredForEveryComponent() {
        val methods = ServiceDiscovery.resolveCandidates(
            loader,
            listOf(dialer(OverridingDialerService::class.java), dialer(InheritingDialerService::class.java)),
        )

        // InCallService 的公开音频路由回调：本任务用它在本进程内实现保守交还。
        val audio = methods.filter { it.name == "onCallAudioStateChanged" }
        val endpoint = methods.filter { it.name == "onCallEndpointChanged" }
        assertEquals(1, audio.size)
        assertEquals(1, endpoint.size)
        assertEquals(InCallService::class.java, audio.single().declaringClass)
        assertEquals(InCallService::class.java, endpoint.single().declaringClass)
        assertEquals(listOf(CallAudioState::class.java), audio.single().parameterTypes.toList())
        assertEquals(listOf(CallEndpoint::class.java), endpoint.single().parameterTypes.toList())
    }

    private fun dialer(type: Class<*>): ServiceCandidate =
        ServiceCandidate(name = type.name, packageName = ServiceDiscovery.DIALER_PACKAGE)

    /** 覆写 onCallAdded 且刻意不调用 super 的组件。 */
    class OverridingDialerService : InCallService() {
        var onCallAddedCount: Int = 0

        override fun onCallAdded(call: Call?) {
            onCallAddedCount += 1
        }
    }

    /** 完全不覆写任何回调的组件：必须回退到基类实现。 */
    class InheritingDialerService : InCallService()

    open class ParentDialerService : InCallService() {
        override fun onCallAdded(call: Call?) {
            // 不调用 super：基类实现不能成为唯一的 Hook 点。
        }
    }

    class ChildDialerService : ParentDialerService()
}

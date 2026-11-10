package org.krvyft.agentcall.hook

import android.content.pm.ServiceInfo
import android.telecom.Call
import android.telecom.InCallService
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器测试：在真实 Android 运行时证明动态发现找到的是真实覆写方法（而不是只 Hook 基类
 * 空实现），每个组件独立回退、父子类链与重复注册被去重、按包名过滤，并且生产 Hook 适配器
 * 抛异常不会吞掉原方法执行。
 *
 * 本机无 Android 设备，文件只编译（:app:assembleDebugAndroidTest），未执行；
 * 结果按“未验证”记录，不作为设备结论。纯逻辑部分另有 JVM 测试 ServiceDiscoveryUnitTest。
 */
@RunWith(AndroidJUnit4::class)
class ServiceDiscoveryTest {

    @Test
    fun overriddenCallbackWithoutSuperCallIsDiscovered() {
        val methods = ServiceDiscovery.resolve(
            ServiceDiscoveryTest::class.java.classLoader!!,
            listOf(serviceInfo(FakeDialerService::class.java)),
        )

        val added = methods.single { it.name == "onCallAdded" }
        assertEquals(FakeDialerService::class.java, added.declaringClass)
        // 未覆写的公开回调回退到基类实现：音频路由回调也必须在 Hook 范围内。
        assertEquals(InCallService::class.java, methods.single { it.name == "onCallRemoved" }.declaringClass)
        assertEquals(InCallService::class.java, methods.single { it.name == "onCallAudioStateChanged" }.declaringClass)
        assertEquals(InCallService::class.java, methods.single { it.name == "onCallEndpointChanged" }.declaringClass)
        assertEquals(4, methods.size)
    }

    @Test
    fun perServiceFallbackAlsoCoversComponentsInheritingTheBaseCallback() {
        val methods = ServiceDiscovery.resolve(
            ServiceDiscoveryTest::class.java.classLoader!!,
            listOf(serviceInfo(FakeDialerService::class.java), serviceInfo(InheritingDialerService::class.java)),
        )

        val added = methods.filter { it.name == "onCallAdded" }
        // “有人覆写”不能成为跳过继承组件的理由。
        assertEquals(2, added.size)
        assertTrue(added.any { it.declaringClass == FakeDialerService::class.java })
        assertTrue(added.any { it.declaringClass == InCallService::class.java })
    }

    @Test
    fun parentChildChainAndRepeatedRegistrationsAreDeduped() {
        val loader = ServiceDiscoveryTest::class.java.classLoader!!
        val methods = ServiceDiscovery.resolve(
            loader,
            listOf(
                serviceInfo(ChildDialerService::class.java),
                serviceInfo(ParentDialerService::class.java),
                serviceInfo(ChildDialerService::class.java),
                // 不是 InCallService 子类的组件必须被忽略，不能凭名字 Hook。
                serviceInfo(String::class.java),
            ),
        )

        val added = methods.filter { it.name == "onCallAdded" }
        assertEquals(1, added.size)
        assertEquals(ParentDialerService::class.java, added.single().declaringClass)
        assertEquals(1, methods.count { it.name == "onCallRemoved" })
        assertEquals(4, methods.size)
    }

    @Test
    fun componentsFromOtherPackagesAreIgnored() {
        val foreign = ServiceInfo().apply {
            name = FakeDialerService::class.java.name
            packageName = "com.example.not.the.dialer"
        }
        val methods = ServiceDiscovery.resolve(
            ServiceDiscoveryTest::class.java.classLoader!!,
            listOf(foreign),
        )
        assertTrue(methods.isEmpty())
    }

    @Test
    fun throwingHookCallbackDoesNotPreventOriginalMethodRun() {
        // 用生产适配器（不是“guard 之后直接反射调用原方法”的同义反复）证明：
        // 我们的回调抛异常既不会传播，也不会阻止框架继续执行原方法。
        val service = FakeDialerService()
        val adapter = HookCallbackAdapter(
            onCallback = { _, _, _ -> throw IllegalStateException("deliberate hook failure") },
        )

        assertFalse(adapter.before("onCallAdded", service, arrayOfNulls(1)))
        service.onCallAdded(null)

        assertEquals(1, service.onCallAddedCount)
    }

    private fun serviceInfo(type: Class<*>): ServiceInfo = ServiceInfo().apply {
        name = type.name
        packageName = ServiceDiscovery.DIALER_PACKAGE
    }
}

/** 覆写 onCallAdded 且刻意不调用 super 的真实 InCallService 子类。 */
class FakeDialerService : InCallService() {
    var onCallAddedCount: Int = 0

    override fun onCallAdded(call: Call?) {
        onCallAddedCount += 1
    }
}

/** 完全不覆写任何回调的组件：必须按组件回退到基类实现。 */
class InheritingDialerService : InCallService()

open class ParentDialerService : InCallService() {
    override fun onCallAdded(call: Call?) {
        // 不调用 super：基类实现不能成为唯一的 Hook 点。
    }
}

class ChildDialerService : ParentDialerService()

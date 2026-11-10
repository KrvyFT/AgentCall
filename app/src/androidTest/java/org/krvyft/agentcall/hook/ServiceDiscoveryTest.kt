package org.krvyft.agentcall.hook

import android.content.pm.ServiceInfo
import android.telecom.Call
import android.telecom.InCallService
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器测试：证明动态发现找到的是真实覆写方法（而不是只 Hook 基类空实现），
 * 父子类链与重复注册被去重，并且 Hook 回调抛异常不会吞掉原方法执行。
 *
 * 本机无 Android 设备，文件只编译（:app:assembleDebugAndroidTest），未执行；
 * 结果按“未验证”记录，不作为设备结论。
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
        assertEquals(InCallService::class.java, methods.single { it.name == "onCallRemoved" }.declaringClass)
        assertEquals(2, methods.size)
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
        assertEquals(2, methods.size)
    }

    @Test
    fun throwingHookCallbackDoesNotPreventOriginalMethodRun() {
        val loader = ServiceDiscoveryTest::class.java.classLoader!!
        val method = ServiceDiscovery.resolve(loader, listOf(serviceInfo(FakeDialerService::class.java)))
            .single { it.name == "onCallAdded" }
        val service = FakeDialerService()
        val logs = mutableListOf<String>()

        val hookSucceeded = HookSafety.guard("simulated-xposed-callback", logs::add) {
            throw IllegalStateException("deliberate hook failure")
        }

        assertFalse(hookSucceeded)
        assertEquals(1, logs.size)
        assertNotNull(method.invoke(service, null as Call?))
        assertEquals(1, service.onCallAddedCount)
    }

    private fun serviceInfo(type: Class<*>): ServiceInfo = ServiceInfo().apply {
        name = type.name
        packageName = "com.google.android.dialer"
    }
}

/** 覆写 onCallAdded 且刻意不调用 super 的真实 InCallService 子类。 */
class FakeDialerService : InCallService() {
    var onCallAddedCount: Int = 0

    override fun onCallAdded(call: Call?) {
        onCallAddedCount += 1
    }
}

open class ParentDialerService : InCallService() {
    override fun onCallAdded(call: Call?) {
        // 不调用 super：基类实现不能成为唯一的 Hook 点。
    }
}

class ChildDialerService : ParentDialerService()

package org.krvyft.agentcall.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 开机/升级广播的启动裁决（I4）——纯 JVM 逻辑，不创建 Android 组件。
 *
 * 只在“用户上次显式启动且没有停止”（本机自动启动偏好）为 true 时启动服务；
 * 总开关/就绪仍由服务启动后读取桥接配置再判定，绝不能因为开机广播就跳过就绪握手。
 */
class BootReceiverDecisionTest {

    @Test
    fun bootCompletedStartsTheServiceWhenTheUserOptedIn() {
        assertEquals(
            BootStartDecision.START,
            BootStartPolicy.decide(BootStartPolicy.ACTION_BOOT_COMPLETED, autoStartEnabled = true),
        )
    }

    @Test
    fun packageReplacedRestartsTheServiceWhenTheUserOptedIn() {
        assertEquals(
            BootStartDecision.START,
            BootStartPolicy.decide(BootStartPolicy.ACTION_MY_PACKAGE_REPLACED, autoStartEnabled = true),
        )
    }

    @Test
    fun bootCompletedDoesNotStartTheServiceAfterTheUserStoppedIt() {
        assertEquals(
            BootStartDecision.SKIP_DISABLED,
            BootStartPolicy.decide(BootStartPolicy.ACTION_BOOT_COMPLETED, autoStartEnabled = false),
        )
    }

    @Test
    fun unrelatedBroadcastsAreIgnoredEvenWhenAutoStartIsEnabled() {
        assertEquals(
            BootStartDecision.SKIP_UNSUPPORTED_ACTION,
            BootStartPolicy.decide("android.intent.action.SCREEN_ON", autoStartEnabled = true),
        )
        assertEquals(
            BootStartDecision.SKIP_UNSUPPORTED_ACTION,
            BootStartPolicy.decide(null, autoStartEnabled = true),
        )
    }
}

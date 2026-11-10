package org.krvyft.agentcall.service

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.krvyft.agentcall.ui.CalibrationActivity
import org.krvyft.agentcall.ui.MainActivity
import org.krvyft.agentcall.ui.TakeoverActivity

/**
 * 仪器测试：在真实 Android 运行时核对 manifest/权限/通知接线（Task 7B2）。
 *
 * 本机无 Android 设备，本文件只参与 :app:assembleDebugAndroidTest 编译，未执行；
 * 结果按“未验证”记录，不得当作真机结论。权限集合按“必须包含 + 禁止包含未使用敏感权限”
 * 断言，而不是与实际包管理器清单做完全相等比较。
 */
@RunWith(AndroidJUnit4::class)
class ServiceWiringTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theServiceIsNotExportedAndDeclaresSpecialUseForegroundType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AgentCallService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertFalse(info.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    @Test
    fun onlyTheLauncherActivityIsExported() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
        )
        val activities = packageInfo.activities ?: emptyArray()
        val names = activities.map { it.name }.toSet()
        assertTrue(names.contains(MainActivity::class.java.name))
        assertTrue(names.contains(CalibrationActivity::class.java.name))
        assertTrue(names.contains(TakeoverActivity::class.java.name))
        for (activity in activities) {
            if (activity.name == MainActivity::class.java.name) {
                assertTrue(activity.exported)
            } else {
                assertFalse(activity.name + " must not be exported", activity.exported)
            }
        }
    }

    @Test
    fun backupsAreDisabled() {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun theRequestedPermissionsAreTheOnesActuallyUsed() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val requested = info.requestedPermissions?.toSet() ?: emptySet()
        val required = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
        )
        assertTrue("missing: " + (required - requested), requested.containsAll(required))
        val forbidden = setOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.WAKE_LOCK,
            "android.permission.MANAGE_OWN_CALLS",
        )
        val unexpected = requested.intersect(forbidden)
        assertTrue("unused permission(s): " + unexpected, unexpected.isEmpty())
    }

    @Test
    fun theSessionNotificationChannelCanBeCreated() {
        val manager = context.getSystemService(NotificationManager::class.java)
        CallNotification(context).ensureChannel()
        assertNotNull(manager.getNotificationChannel(CallNotification.CHANNEL_ID))
    }
}

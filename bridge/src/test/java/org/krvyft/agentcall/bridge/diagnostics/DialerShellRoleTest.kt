package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 11Q-b：默认拨号器的 shell 兜底解析（纯 JVM，无 Android 依赖）。
 *
 * 设备 XQ-DQ72（API 37）上 binder/context 路径读不到 ROLE_DIALER 持有者，但 root shell
 * 的固定命令能看到真实事实：
 * `cmd role get-role-holders android.app.role.DIALER` -> com.google.android.dialer
 * `cmd telecom get-default-dialer` -> com.google.android.dialer
 *
 * 解析只接受**恰好一个**合法包名：空输出、纯标点、多持有者、错误文本、超长输出一律
 * UNKNOWN（fail closed，绝不猜）。
 */
class DialerShellRoleTest {

    @Test
    fun deviceRoleHoldersOutputIsTheDefaultDialer() {
        assertEquals(
            "设备实测输出（带换行）必须判为默认拨号器",
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyRoleHolders("com.google.android.dialer\n"),
        )
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyRoleHolders("com.google.android.dialer"),
        )
    }

    @Test
    fun deviceDefaultDialerOutputIsTheDefaultDialer() {
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyDefaultDialer("com.google.android.dialer\n"),
        )
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyDefaultDialer("com.google.android.dialer\r\n"),
        )
    }

    @Test
    fun whitespaceAndBlankLinesAreTolerated() {
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyRoleHolders("  com.google.android.dialer  \r\n\n"),
        )
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyRoleHolders("\n\n\t com.google.android.dialer\n\n"),
        )
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyDefaultDialer("\tcom.google.android.dialer\t"),
        )
    }

    @Test
    fun anotherDialerIsAnExplicitNegative() {
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT,
            DialerShellParsing.classifyRoleHolders("com.android.dialer\n"),
        )
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT,
            DialerShellParsing.classifyDefaultDialer("com.samsung.android.dialer\n"),
        )
    }

    @Test
    fun multipleHoldersAreUnknownInsteadOfPickingOne() {
        assertEquals(
            "多持有者不是确定事实，不能挑一个当默认",
            DialerDefaultRole.UNKNOWN,
            DialerShellParsing.classifyRoleHolders(
                "com.google.android.dialer\ncom.android.dialer\n",
            ),
        )
        // 同一包名重复行不是“多持有者”。
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            DialerShellParsing.classifyRoleHolders(
                "com.google.android.dialer\ncom.google.android.dialer\n",
            ),
        )
    }

    @Test
    fun emptyOutputIsUnknown() {
        for (output in listOf("", "   ", "\n\n", null)) {
            assertEquals(
                "空输出必须保持 UNKNOWN：" + output,
                DialerDefaultRole.UNKNOWN,
                DialerShellParsing.classifyRoleHolders(output),
            )
            assertEquals(
                DialerDefaultRole.UNKNOWN,
                DialerShellParsing.classifyDefaultDialer(output),
            )
        }
    }

    @Test
    fun punctuationOnlyOrErrorTextIsUnknown() {
        for (output in listOf(
            ",",
            ".",
            " , ; ",
            "Error: java.lang.SecurityException: unknown role",
            "No role holders for android.app.role.DIALER",
            "com.google.android.dialer/.ui.DialtactsActivity",
            "com.google.android.dialer extra",
            "com.google.android.dialer,com.android.dialer",
            "-com.google.android.dialer",
            "com..dialer",
        )) {
            assertEquals(
                "不可解析的输出必须 fail closed：" + output,
                DialerDefaultRole.UNKNOWN,
                DialerShellParsing.classifyRoleHolders(output),
            )
        }
        assertEquals(
            DialerDefaultRole.UNKNOWN,
            DialerShellParsing.classifyDefaultDialer(
                "cmd: Failure calling service telecom: Broken pipe",
            ),
        )
    }

    @Test
    fun oversizedOutputIsUnknown() {
        assertEquals(
            DialerDefaultRole.UNKNOWN,
            DialerShellParsing.classifyRoleHolders(
                "com.google.android.dialer\n" + "x".repeat(16 * 1024),
            ),
        )
        assertEquals(
            DialerDefaultRole.UNKNOWN,
            DialerShellParsing.classifyRoleHolders(
                "com.google.android.dialer" + "x".repeat(16 * 1024),
            ),
        )
    }
}

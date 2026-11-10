package org.krvyft.agentcall.bridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 默认拨号器三态探测（阶段 B / spec §8 接听前检查、§11 概览）。
 *
 * 关键取舍：只有系统 RoleManager 的 ROLE_DIALER 持有者列表是权威事实；角色不可读时，
 * ACTION_DIAL 的默认处理器只能提供“确定不是”的负证据，绝不能据此宣称“已设为默认”。
 * 任何异常/无候选都返回显式未知，不猜测。
 */
class DialerDefaultRoleProbeTest {

    private fun probe(holders: List<String>?, handler: String?) = DialerDefaultRoleProbe(
        roleHolders = { holders },
        dialHandlerPackage = { handler },
    )

    @Test
    fun roleHoldersAreTheAuthoritativeFact() {
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            probe(listOf("com.google.android.dialer"), null).inspect(),
        )
        // 角色可读时处理器解析不参与判定（处理器可能是解析器/其它应用）。
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT,
            probe(listOf("com.google.android.dialer"), "com.other.dialer").inspect(),
        )
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT,
            probe(emptyList(), "com.google.android.dialer").inspect(),
        )
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT,
            probe(listOf("com.other.dialer"), "com.google.android.dialer").inspect(),
        )
    }

    @Test
    fun handlerFallbackOnlyProvidesNegativeEvidence() {
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT,
            probe(null, "com.other.dialer").inspect(),
        )
        // 处理器是 Google 电话只证明“它是当前处理器”，不证明持有系统默认拨号器角色。
        assertEquals(DialerDefaultRole.UNKNOWN, probe(null, "com.google.android.dialer").inspect())
        // 系统 Resolver（多候选）与无候选都不是确定事实。
        assertEquals(DialerDefaultRole.UNKNOWN, probe(null, "android").inspect())
        assertEquals(DialerDefaultRole.UNKNOWN, probe(null, null).inspect())
    }

    @Test
    fun failuresStayUnknownInsteadOfGuessing() {
        assertEquals(
            DialerDefaultRole.UNKNOWN,
            DialerDefaultRoleProbe(roleHolders = { throw SecurityException("role") }, dialHandlerPackage = { null }).inspect(),
        )
        assertEquals(
            DialerDefaultRole.UNKNOWN,
            DialerDefaultRoleProbe(roleHolders = { null }, dialHandlerPackage = { throw IllegalStateException("pm") }).inspect(),
        )
        // 角色查询失败、但处理器明确是其它应用：这是可确定的“否”。
        assertEquals(
            DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT,
            DialerDefaultRoleProbe(roleHolders = { throw SecurityException("role") }, dialHandlerPackage = { "com.other.dialer" }).inspect(),
        )
    }

    @Test
    fun wireValueIsBooleanOrExplicitUnknown() {
        assertEquals(java.lang.Boolean.TRUE, DialerDefaultRole.GOOGLE_DIALER_IS_DEFAULT.definite)
        assertEquals(java.lang.Boolean.FALSE, DialerDefaultRole.GOOGLE_DIALER_IS_NOT_DEFAULT.definite)
        assertNull(DialerDefaultRole.UNKNOWN.definite)
    }
}

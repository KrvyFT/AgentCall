package org.krvyft.agentcall.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState

/**
 * 通知里的接管 action 必须把生成它时的 SessionId 绑在 Intent data 上（Task 7B2 FIX ROUND 2）。
 *
 * JVM 单测不能构造真实的 Intent/PendingIntent（单测里的 android.jar 是 stub，工程没有
 * Robolectric），因此这里锁两层事实：
 * 1) 纯逻辑：data 里的旧 SessionId 解析出来后会被 [TakeoverGuard] 以 stale 拒绝；
 * 2) 生产绑定：CallNotification.takeoverIntent 真的把该 data 交给 Intent（删掉数据绑定，
 *    本测试必须失败——已用行为探针验证）。
 */
class CallNotificationBindingTest {

    private val old = SessionId(processEpoch = "epoch-1", callId = "call-a")
    private val current = SessionId(processEpoch = "epoch-2", callId = "call-b")

    @Test
    fun aStaleNotificationTargetIsRejectedByTheTakeoverGuard() {
        // 与 CallNotification 相同的编码：PendingIntent 的 Intent data。
        val data = TakeoverTarget.encode(TakeoverTarget.of(old))
        assertEquals(old, TakeoverTarget.parse(data))
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_STALE_SESSION),
            TakeoverGuard.evaluate(TakeoverTarget.parse(data), current, SessionState.ACTIVE_AI),
        )
    }

    @Test
    fun theNotificationActionBindsTheSessionIntoTheIntentData() {
        val body = functionBody(callNotificationSource(), "private fun takeoverIntent(")

        assertTrue(
            "接管 action 必须把 SessionId 写进 Intent data，否则旧通知与新通话共享 PendingIntent " +
                "身份，可能作用到新通话；实际实现：" + body,
            body.contains(".setData("),
        )
        assertTrue(
            "data 必须是 TakeoverTarget 编码的同一会话身份",
            body.contains("TakeoverTarget.encode("),
        )
        assertTrue("编码后的 data 必须变成 Uri", body.contains(".toUri()"))
    }

    /** 模拟“去掉 data 绑定”：没有 data 就没有可核验的会话身份，guard 只能报缺少会话。 */
    @Test
    fun withoutTheDataBindingNoSessionIdentityCanBeChecked() {
        assertNull(TakeoverTarget.parse(""))
        assertEquals(
            TakeoverDecision.Rejected(TakeoverGuard.ERROR_SESSION_REQUIRED),
            TakeoverGuard.evaluate(null, current, SessionState.ACTIVE_AI),
        )
    }

    private fun callNotificationSource(): String {
        val relative = "src/main/java/org/krvyft/agentcall/service/CallNotification.kt"
        val candidates = listOf(File(relative), File("app/" + relative), File("../" + relative))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("CallNotification.kt not found; tried " + candidates.map { it.absolutePath })
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("function not found: " + signature, start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("function has no body: " + signature, open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces in " + signature)
    }
}

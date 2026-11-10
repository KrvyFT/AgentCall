package org.krvyft.agentcall.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * C1 的可执行入口断言：两个角色都必须是可由 app_process 加载的真实 JVM 入口，
 * 模块脚本必须同时拉起它们。这里只证明“入口与编排存在”，真机运行仍属未验证。
 */
class RoleEntrypointsTest {

    @Test
    fun audioWorkerEntrypointHasAJvmMain() {
        assertMainExists("org.krvyft.agentcall.bridge.audio.AudioWorkerMain")
    }

    @Test
    fun bridgeAndSupervisionEntrypointHasAJvmMain() {
        // 监督角色由 bridge 容器承载：BridgeMain 是控制面 + 监督角色共同入口。
        assertMainExists("org.krvyft.agentcall.bridge.BridgeMain")
    }

    @Test
    fun serviceScriptLaunchesBothRolesWithBoundedRestart() {
        val script = locateServiceScript()
        val text = script.readText()

        assertTrue("bridge/监督入口必须由 service.sh 拉起", text.contains("BridgeMain"))
        assertTrue("音频工作入口必须由 service.sh 拉起", text.contains("AudioWorkerMain"))
        assertTrue("监督与控制面容器需要有限重启", text.contains("bridge_container_supervisor"))
        assertTrue("音频角色需要独立的有限重启预算", text.contains("audio_worker_supervisor"))
        assertTrue(text.contains("MAX_RESTARTS"))
    }

    private fun assertMainExists(className: String) {
        val type = try {
            Class.forName(className)
        } catch (missing: ClassNotFoundException) {
            fail("缺少 app_process 入口类: " + className)
            return
        }
        val main = try {
            type.getMethod("main", Array<String>::class.java)
        } catch (missing: NoSuchMethodException) {
            fail(className + " 缺少 public static void main(String[])")
            return
        }
        assertTrue(java.lang.reflect.Modifier.isStatic(main.modifiers))
        assertEquals(Void.TYPE, main.returnType)
        assertNotNull(type)
    }

    private fun locateServiceScript(): File {
        val candidates = listOf(
            File("../module/service.sh"),
            File("module/service.sh"),
            File("../../module/service.sh"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 module/service.sh（工作目录: " + File(".").absolutePath + "）")
    }
}

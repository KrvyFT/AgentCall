package org.krvyft.agentcall.bridge

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.config.ConfigErrorCode
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.config.ConfigStoreException

/**
 * 生产入口的配置根限制。
 *
 * BridgeMain 由 root app_process 启动，argv 不能指向任意目录：除 /data/adb/agentcall
 * 及其子目录外一律拒绝（finding：argv 可把明文密钥文件写到非 POSIX/宽松目录）。
 * 测试注入临时目录走 ConfigStore 的构造参数（文档化的 test-only 门），不走生产入口。
 */
class ConfigRootTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("agentcall-root-test-").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun failure(args: Array<String>): ConfigStoreException {
        try {
            resolveConfigRoot(args)
        } catch (expected: ConfigStoreException) {
            return expected
        }
        throw AssertionError("expected ConfigStoreException")
    }

    @Test
    fun defaultRootIsUsedWithoutArguments() {
        assertEquals(
            File(ConfigStore.DEFAULT_ROOT).canonicalFile,
            resolveConfigRoot(emptyArray()),
        )
    }

    @Test
    fun productionRootAndItsSubdirectoriesAreAccepted() {
        assertEquals(
            File(ConfigStore.DEFAULT_ROOT).canonicalFile,
            resolveConfigRoot(arrayOf(ConfigStore.DEFAULT_ROOT)),
        )
        assertEquals(
            File(ConfigStore.DEFAULT_ROOT, "profiles/test").canonicalFile,
            resolveConfigRoot(arrayOf(ConfigStore.DEFAULT_ROOT + "/profiles/test")),
        )
    }

    @Test
    fun arbitraryConfigRootIsRejectedWithoutCreatingAnything() {
        val candidate = File(tempDir, "evil")

        val error = failure(arrayOf(candidate.path))

        assertEquals(ConfigErrorCode.CONFIG_ROOT_REJECTED, error.code)
        // 错误消息不回显完整路径。
        assertFalse(error.message!!.contains(tempDir.path))
        assertFalse(candidate.exists())
    }

    @Test
    fun siblingDirectorySharingThePrefixIsRejected() {
        val sibling = ConfigStore.DEFAULT_ROOT + "-evil"

        assertEquals(ConfigErrorCode.CONFIG_ROOT_REJECTED, failure(arrayOf(sibling)).code)
    }

    @Test
    fun pathTraversalOutOfProductionRootIsRejected() {
        val escape = ConfigStore.DEFAULT_ROOT + "/../elsewhere"

        assertEquals(ConfigErrorCode.CONFIG_ROOT_REJECTED, failure(arrayOf(escape)).code)
    }
}

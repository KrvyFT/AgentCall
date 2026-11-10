package org.krvyft.agentcall.bridge.config

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 非 POSIX 文件系统上的权限回退（finding：File.setReadable/setWritable 的布尔结果被丢弃）。
 *
 * 非 POSIX 环境无法用 setPosixFilePermissions；回退到 File API 时必须逐个检查返回值，
 * 任一失败都要 STORAGE_FAILED，而不是继续把密钥写进一个可能对组/其他用户可读的文件。
 */
class FilePermissionsTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-perm-test-").toFile()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun failure(block: () -> Unit): ConfigStoreException {
        try {
            block()
        } catch (expected: ConfigStoreException) {
            return expected
        }
        throw AssertionError("expected ConfigStoreException")
    }

    private fun posixAvailable(): Boolean =
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    @Test
    fun nonPosixFallbackWithoutEnforcementFailsClosed() {
        val target = File(baseDir, "config.json").apply { writeText("{}") }

        val error = failure {
            PrivateFilePermissions.enforceWith(
                target,
                directory = false,
                posixSetter = { _, _ -> throw UnsupportedOperationException("non-posix") },
                legacySetter = { _, _ -> false },
            )
        }

        assertEquals(ConfigErrorCode.STORAGE_FAILED, error.code)
        assertFalse(error.message!!.contains(baseDir.path))
    }

    @Test
    fun nonPosixFallbackThatEnforcesOwnerOnlyIsAccepted() {
        val target = File(baseDir, "pairing.json").apply { writeText("{}") }
        var legacyDirectoryFlag: Boolean? = null

        PrivateFilePermissions.enforceWith(
            target,
            directory = false,
            posixSetter = { _, _ -> throw UnsupportedOperationException("non-posix") },
            legacySetter = { _, directory ->
                legacyDirectoryFlag = directory
                true
            },
        )

        assertEquals(false, legacyDirectoryFlag)
    }

    @Test
    fun legacyRestrictReallyEnforcesOwnerOnlyBits() {
        if (!posixAvailable()) return
        // 在 POSIX 文件系统上验证回退实现本身：它必须真的得到 0600/0700，
        // 而不是只返回 true。
        val file = File(baseDir, "secret.json").apply { writeText("{}") }
        assertTrue(PrivateFilePermissions.legacyRestrict(file, directory = false))
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file.toPath()),
        )

        val directory = File(baseDir, "secret-dir").apply { mkdirs() }
        assertTrue(PrivateFilePermissions.legacyRestrict(directory, directory = true))
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(directory.toPath()),
        )
    }

    @Test
    fun configStoreFailsClosedWhenPermissionsCannotBeEnforced() {
        val root = File(baseDir, "store")

        val error = failure {
            // permissions 是生产装配不暴露的测试注入点（默认走 PrivateFilePermissions）。
            ConfigStore(root, permissions = { _, _ ->
                throw ConfigStoreException(ConfigErrorCode.STORAGE_FAILED, "permissions denied")
            })
        }

        assertEquals(ConfigErrorCode.STORAGE_FAILED, error.code)
    }
}

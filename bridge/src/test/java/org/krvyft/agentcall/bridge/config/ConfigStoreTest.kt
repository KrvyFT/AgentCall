package org.krvyft.agentcall.bridge.config

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.ConfigPatch
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.SecretState
import org.krvyft.agentcall.core.model.SecretUpdate
import org.krvyft.agentcall.core.protocol.RpcCodec

/**
 * root 私有配置存储的行为测试（纯 JVM，注入临时目录）。先于实现存在，用于记录红灯。
 *
 * 关键约束（spec §6.4/§11）：CAS 冲突不覆盖他人更新、密钥只回显“已配置”、
 * 原子写入且目录/文件 root 私有、schema 迁移先备份。
 */
class ConfigStoreTest {

    private lateinit var baseDir: File

    private val nowMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-config-test-").toFile()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun store(root: File = File(baseDir, "agentcall")) = ConfigStore(root, clockMs = { nowMs })

    private fun failure(block: () -> Unit): ConfigStoreException {
        try {
            block()
        } catch (expected: ConfigStoreException) {
            return expected
        }
        fail("expected ConfigStoreException")
        throw AssertionError("unreachable")
    }

    private fun posixAvailable(): Boolean =
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun assertPrivateFile(file: File) {
        assertTrue(file.isFile)
        if (posixAvailable()) {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file.toPath()),
            )
        }
    }

    @Test
    fun staleRevisionDoesNotOverwrite() {
        val store = store()
        val first = store.update(
            0L,
            ConfigPatch(
                enabled = true,
                blacklist = listOf("+8613900139000"),
                secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace("sk-live-one")),
            ),
        )
        assertEquals(1L, first.config.revision)
        assertTrue(first.config.enabled)

        // 旧 revision 的写入必须被拒绝，并且只报告机器可读错误码（不回显密钥）。
        val stale = failure {
            store.update(
                0L,
                ConfigPatch(
                    enabled = false,
                    secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace("sk-stale-two")),
                ),
            )
        }
        assertEquals(ConfigErrorCode.STALE_REVISION, stale.code)
        assertFalse(stale.message!!.contains("sk-"))

        // 旧配置保持不变：新实例强制从磁盘重读。
        val reloaded = store().read()
        assertEquals(1L, reloaded.config.revision)
        assertTrue(reloaded.config.enabled)
        assertEquals(listOf("+8613900139000"), reloaded.config.blacklist)
        assertEquals(SecretState.CONFIGURED, reloaded.secrets[EndpointKind.ASR])
        assertEquals("sk-live-one", store().readSecret(EndpointKind.ASR))
    }

    @Test
    fun secretsFollowKeepReplaceClear() {
        val store = store()
        store.update(
            0L,
            ConfigPatch(
                secrets = mapOf(
                    EndpointKind.ASR to SecretUpdate.Replace("sk-asr-one"),
                    EndpointKind.LLM to SecretUpdate.Replace("sk-llm-one"),
                ),
            ),
        )

        val second = store.update(
            1L,
            ConfigPatch(
                secrets = mapOf(
                    EndpointKind.ASR to SecretUpdate.Keep,
                    EndpointKind.LLM to SecretUpdate.Clear,
                ),
            ),
        )

        assertEquals(2L, second.config.revision)
        assertEquals("sk-asr-one", store.readSecret(EndpointKind.ASR))
        assertNull(store.readSecret(EndpointKind.LLM))
        assertEquals(SecretState.CONFIGURED, second.secrets[EndpointKind.ASR])
        assertEquals(SecretState.MISSING, second.secrets[EndpointKind.LLM])
        assertEquals(SecretState.MISSING, second.secrets[EndpointKind.TTS])

        // 空密钥不是合法的替换值；失败不得改动已存密钥或 revision。
        val invalid = failure {
            store.update(2L, ConfigPatch(secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace(" "))))
        }
        assertEquals(ConfigErrorCode.INVALID_SECRET, invalid.code)
        assertEquals("sk-asr-one", store.readSecret(EndpointKind.ASR))
        assertEquals(2L, store.read().config.revision)
    }

    @Test
    fun redactedExportNeverContainsSecretValues() {
        val store = store()
        store.update(
            0L,
            ConfigPatch(secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace("sk-unique-token-42"))),
        )

        val json = RpcCodec.encodeRedacted(store.read())

        assertFalse(json.contains("sk-unique-token-42"))
        assertTrue(json.contains("\"ASR\":\"CONFIGURED\""))
        assertFalse(store.read().toString().contains("sk-unique-token-42"))

        // 校验失败的错误同样不能带出密钥。
        val revision = store.read().config.revision
        val invalid = failure {
            store.update(
                revision,
                ConfigPatch(
                    answerDelayMs = 999_999,
                    secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace("sk-second-token")),
                ),
            )
        }
        assertEquals(ConfigErrorCode.INVALID_CONFIG, invalid.code)
        assertFalse(invalid.message!!.contains("sk-second-token"))
        assertFalse(invalid.message!!.contains("sk-unique-token-42"))
    }

    @Test
    fun rootPrivateAtomicWriteLeavesNoTemporaryFiles() {
        val root = File(baseDir, "agentcall")
        val store = store(root)
        store.update(0L, ConfigPatch(enabled = true))
        store.update(1L, ConfigPatch(region = "US"))

        val configFile = File(root, ConfigStore.CONFIG_FILE_NAME)
        assertPrivateFile(configFile)
        if (posixAvailable()) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(root.toPath()),
            )
        }

        // 原子写入的临时文件不得残留，目录里只有主副本。
        val leftovers = root.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty())
        assertEquals(listOf(ConfigStore.CONFIG_FILE_NAME), root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun schemaMigrationBacksUpLegacyFile() {
        val root = File(baseDir, "legacy")
        assertTrue(root.mkdirs())
        val legacyJson = """{"revision":7,"enabled":true,"blacklist":["+8613900139000"],"answerDelayMs":2500}"""
        val configFile = File(root, ConfigStore.CONFIG_FILE_NAME)
        configFile.writeText(legacyJson)

        val read = store(root).read()

        assertEquals(7L, read.config.revision)
        assertTrue(read.config.enabled)
        assertEquals(listOf("+8613900139000"), read.config.blacklist)
        assertEquals(2500, read.config.answerDelayMs)

        val backup = File(root, "config.schema-0.json.bak")
        assertTrue(backup.isFile)
        assertEquals(legacyJson, backup.readText())
        assertTrue(configFile.readText().contains("\"schema\":1"))
    }

    @Test
    fun unknownFutureSchemaFailsClosedWithoutTouchingFile() {
        val root = File(baseDir, "future")
        assertTrue(root.mkdirs())
        val futureJson = """{"schema":99,"revision":1,"enabled":true}"""
        val configFile = File(root, ConfigStore.CONFIG_FILE_NAME)
        configFile.writeText(futureJson)

        val error = failure { store(root).read() }

        assertEquals(ConfigErrorCode.UNSUPPORTED_SCHEMA, error.code)
        assertEquals(futureJson, configFile.readText())
    }

    @Test
    fun emptyEndpointsAreNormalisedAndInvalidPatchDoesNotTouchFile() {
        val root = File(baseDir, "agentcall")
        val store = store(root)
        val first = store.update(0L, ConfigPatch(asr = ApiEndpoint("", "")))

        assertNull(first.config.asr)
        val configFile = File(root, ConfigStore.CONFIG_FILE_NAME)
        val before = configFile.readText()
        assertFalse(before.contains("\"baseUrl\":\"\""))

        val invalid = failure {
            store.update(first.config.revision, ConfigPatch(answerDelayMs = 999_999))
        }
        assertEquals(ConfigErrorCode.INVALID_CONFIG, invalid.code)

        // 失败的写入不改变磁盘内容，revision 也不变。
        assertEquals(before, configFile.readText())
        assertEquals(1L, store.read().config.revision)
    }

    @Test
    fun nonIntegralOrNegativeSchemaIsRejectedExplicitly() {
        // asInt 会把 1.5 截断成 1、把 -0.5 截断成 0；两者都必须显式拒绝，
        // 不能悄悄迁移或读成别的 schema。
        val cases = listOf(
            """{"schema":1.5,"revision":2,"enabled":true}""",
            """{"schema":-0.5,"revision":2,"enabled":true}""",
            """{"schema":-1,"revision":2,"enabled":true}""",
        )
        cases.forEachIndexed { index, json ->
            val root = File(baseDir, "schema-$index")
            assertTrue(root.mkdirs())
            val configFile = File(root, ConfigStore.CONFIG_FILE_NAME)
            configFile.writeText(json)

            val error = failure { store(root).read() }

            assertEquals(ConfigErrorCode.CORRUPT, error.code)
            assertEquals(json, configFile.readText())
        }
    }

    @Test
    fun integralSchemaValueIsAcceptedWithoutMigration() {
        val root = File(baseDir, "integral")
        assertTrue(root.mkdirs())
        val json = """{"schema":1.0,"revision":3,"enabled":true}"""
        File(root, ConfigStore.CONFIG_FILE_NAME).writeText(json)

        val read = store(root).read()

        assertEquals(3L, read.config.revision)
        assertTrue(read.config.enabled)
        assertFalse(File(root, "config.schema-1.json.bak").exists())
    }

    @Test
    fun pairingRecordRoundTripsRootPrivate() {
        val root = File(baseDir, "agentcall")
        val store = store(root)
        assertNull(store.readPairing())

        val record = PairingRecord(
            appPackage = "org.krvyft.agentcall",
            appUid = 10_123,
            appSignatureSha256 = "a".repeat(64),
            appVersionName = "1.0",
            dialerPackage = "com.google.android.dialer",
            dialerUid = 10_124,
            dialerSignatureSha256 = "b".repeat(64),
            dialerVersionName = "100.0",
            pairedAtMs = nowMs,
        )
        store.writePairing(record)

        assertEquals(record, store(root).readPairing())
        assertPrivateFile(File(root, ConfigStore.PAIRING_FILE_NAME))
    }

    @Test
    fun pairingRecordWithoutUidOrVersionFailsClosed() {
        // 旧版配对文件没有 uid：不能降级成只比包名/签名，必须拒绝并要求重新配对。
        val root = File(baseDir, "legacy-pairing")
        assertTrue(root.mkdirs())
        val legacy = JsonObject().apply {
            addProperty("appPackage", "org.krvyft.agentcall")
            addProperty("appSignatureSha256", "a".repeat(64))
            addProperty("appVersionName", "1.0")
            addProperty("dialerPackage", "com.google.android.dialer")
            addProperty("dialerSignatureSha256", "b".repeat(64))
            addProperty("dialerVersionName", "100.0")
            addProperty("pairedAtMs", 1_700_000_000_000L)
        }.toString()
        val pairingFile = File(root, ConfigStore.PAIRING_FILE_NAME)
        pairingFile.writeText(legacy)

        val error = failure { store(root).readPairing() }

        assertEquals(ConfigErrorCode.CORRUPT, error.code)
        assertEquals(legacy, pairingFile.readText())
    }
}
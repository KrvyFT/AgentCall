package org.krvyft.agentcall.bridge.config

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.core.model.EnvironmentKey

/**
 * 持久校准证据的行为测试（spec §9）。
 *
 * 关键不变式：证据与 [EnvironmentKey] 强绑定，任何环境字段变化都使旧证据失效；
 * 五项人工测试未全部完成时绝不能读成“已验证”；损坏或更新 schema 的文件 fail closed。
 */
class CalibrationStoreTest {

    private lateinit var baseDir: File
    private val nowMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-calibration-test-").toFile()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun environment(
        apiLevel: Int = 37,
        romFingerprint: String = "XQ-EC72/37.0.A.1.100/123456:user/release-keys",
        kernelVersion: String = "6.1.75-android14-11-g1234567",
        dialerPackageVersion: String = "com.google.android.dialer/151.0.0",
        frameworkVersion: String = "LSPosed/1.9.2",
        bridgeVersion: String = "1.0",
        audioPolicyDigest: String = "0123456789abcdef",
        simCarrier: String = "46000",
        transport: String = "VoLTE",
        route: String = "HANDSET",
    ): EnvironmentKey = EnvironmentKey(
        apiLevel = apiLevel,
        romFingerprint = romFingerprint,
        kernelVersion = kernelVersion,
        dialerPackageVersion = dialerPackageVersion,
        frameworkVersion = frameworkVersion,
        bridgeVersion = bridgeVersion,
        audioPolicyDigest = audioPolicyDigest,
        simCarrier = simCarrier,
        transport = transport,
        route = route,
    )

    private fun store(root: File = File(baseDir, "agentcall")) = CalibrationStore(root, clockMs = { nowMs })

    private fun failure(block: () -> Unit): CalibrationStoreException {
        try {
            block()
        } catch (expected: CalibrationStoreException) {
            return expected
        }
        fail("expected CalibrationStoreException")
        throw AssertionError("unreachable")
    }

    private fun complete(store: CalibrationStore, environment: EnvironmentKey) {
        for (step in CalibrationStep.entries) {
            store.record(step, passed = true, environment = environment)
        }
    }

    private fun posixAvailable(): Boolean =
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    @Test
    fun partialEvidenceIsPersistedButNeverVerifies() {
        val environment = environment()
        val store = store()

        assertNull(store.load())
        store.record(CalibrationStep.DOWNLINK_RECOGNIZED, passed = true, environment = environment)

        assertFalse(store.isVerified(environment))
        val loaded = requireNotNull(store.load())
        assertEquals(environment, loaded.environment)
        assertTrue(loaded.downlinkRecognized)
        assertFalse(loaded.peerHeardSpeech)
        assertEquals(0L, loaded.calibratedAtMs)

        // 新实例从磁盘重读，仍是未验证的部分证据。
        val reloaded = store()
        assertFalse(reloaded.isVerified(environment))
        assertNotNull(reloaded.load())
    }

    @Test
    fun allManualStepsVerifyAndPersistRootPrivate() {
        val root = File(baseDir, "agentcall")
        val environment = environment()
        val store = store(root)
        complete(store, environment)

        assertTrue(store.isVerified(environment))
        val loaded = requireNotNull(store.load())
        assertTrue(loaded.downlinkRecognized)
        assertTrue(loaded.peerHeardSpeech)
        assertTrue(loaded.microphoneIsolated)
        assertTrue(loaded.manualRestoreVerified)
        assertTrue(loaded.processFailureVerified)
        assertEquals(nowMs, loaded.calibratedAtMs)

        val file = File(root, CalibrationStore.FILE_NAME)
        assertTrue(file.isFile)
        if (posixAvailable()) {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file.toPath()),
            )
        }

        // 重启后仍然验证。
        assertTrue(store(root).isVerified(environment))
    }

    @Test
    fun failedStepCannotCountAsPassed() {
        val environment = environment()
        val store = store()
        complete(store, environment)
        assertTrue(store.isVerified(environment))

        store.record(CalibrationStep.PROCESS_FAILURE, passed = false, environment = environment)
        assertFalse(store.isVerified(environment))
        assertEquals(0L, requireNotNull(store.load()).calibratedAtMs)
    }

    @Test
    fun environmentChangeInvalidatesEvidence() {
        val environment = environment()
        val store = store()
        complete(store, environment)
        assertTrue(store.isVerified(environment))

        val changed = environment.copy(kernelVersion = "6.1.99-other")
        assertNull(store.loadFor(changed))
        assertFalse(store.isVerified(changed))

        // 在新环境记录一个步骤：旧环境的证据被整体丢弃，新环境只有部分证据。
        val partial = store.record(CalibrationStep.DOWNLINK_RECOGNIZED, passed = true, environment = changed)
        assertEquals(changed, partial.environment)
        assertFalse(partial.peerHeardSpeech)
        assertFalse(store.isVerified(environment))
        assertFalse(store.isVerified(changed))
    }

    @Test
    fun corruptEvidenceFailsClosed() {
        val root = File(baseDir, "corrupt")
        assertTrue(root.mkdirs())
        val file = File(root, CalibrationStore.FILE_NAME)
        file.writeText("{not json")

        val store = store(root)
        assertFalse(store.isVerified(environment()))
        val error = failure { store.load() }
        assertEquals(CalibrationErrorCode.CORRUPT, error.code)
        assertEquals("{not json", file.readText())
    }

    @Test
    fun futureSchemaFailsClosedWithoutTouchingFile() {
        val root = File(baseDir, "future")
        assertTrue(root.mkdirs())
        val file = File(root, CalibrationStore.FILE_NAME)
        val future = """{"schema":99,"environment":{}}"""
        file.writeText(future)

        val store = store(root)
        assertFalse(store.isVerified(environment()))
        val error = failure { store.load() }
        assertEquals(CalibrationErrorCode.UNSUPPORTED_SCHEMA, error.code)
        assertEquals(future, file.readText())
    }

    @Test
    fun resetAndClearRemoveEvidence() {
        val environment = environment()
        val store = store()
        complete(store, environment)
        assertTrue(store.isVerified(environment))

        store.reset(environment)
        assertFalse(store.isVerified(environment))
        assertEquals(environment, requireNotNull(store.load()).environment)

        complete(store, environment)
        store.clear()
        assertNull(store.load())
        assertFalse(store.isVerified(environment))
        assertFalse(File(File(baseDir, "agentcall"), CalibrationStore.FILE_NAME).exists())
    }

    @Test
    fun wrongEnvironmentEvidenceIsNotReturned() {
        val original = environment()
        val store = store()
        complete(store, original)

        val other = original.copy(transport = "VoWiFi")
        assertNull(store.loadFor(other))
        assertFalse(store.isVerified(other))
        // 旧证据本身仍然存在，只是不适用于新环境。
        assertEquals(original, requireNotNull(store.load()).environment)
    }
}

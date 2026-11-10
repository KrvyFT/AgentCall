package org.krvyft.agentcall.bridge.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.policy.CalibrationPolicy

/** 手动校准的五个必测步骤；全部通过才可能被视为“已验证”。 */
enum class CalibrationStep {
    /** 本机识别到通话下行（不是环境麦克风）。 */
    DOWNLINK_RECOGNIZED,

    /** 对端听到测试语音（TTS 注入可用）。 */
    PEER_HEARD_SPEECH,

    /** 本机麦克风隔离生效。 */
    MICROPHONE_ISOLATED,

    /** 人工恢复（解除隔离并交还人工）可用。 */
    MANUAL_RESTORE,

    /** 进程故障注入后仍能故障终止/保持隔离。 */
    PROCESS_FAILURE,
}

/** 校准存储的机器可读错误码。 */
object CalibrationErrorCode {
    /** 文件不是合法的校准证据 JSON。 */
    const val CORRUPT: String = "calibration_corrupt"

    /** 磁盘上的 schema 比本版本新，拒绝读取/覆盖。 */
    const val UNSUPPORTED_SCHEMA: String = "calibration_schema_unsupported"

    /** 文件系统读写或权限设置失败。 */
    const val STORAGE_FAILED: String = "calibration_storage_failed"
}

class CalibrationStoreException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 持久校准证据（spec §9）。
 *
 * - 证据与完整 [EnvironmentKey] 一起落盘；环境任一字段变化后旧证据不再适用于新环境；
 * - 五项人工测试全部通过且存在真实校准时间才可能 [isVerified]（判定交给
 *   [CalibrationPolicy]，本类不自造“已验证”）；
 * - 写入是 root 私有的原子替换（同目录临时文件 + rename），损坏/更新 schema 的文件
 *   fail closed：读操作抛错，[isVerified] 返回 false，绝不把半份证据当成可用；
 * - 单写者（特权进程）内用锁串行化，跨进程并发不在本类承诺内。
 *
 * @param root 校准文件目录；生产为 [ConfigStore.DEFAULT_ROOT]，测试注入临时目录。
 * @param clockMs 校准时间来源，测试注入固定值。
 * @param permissions 权限执行器，默认 [PrivateFilePermissions]；测试可注入失败路径。
 */
class CalibrationStore(
    root: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val permissions: (File, Boolean) -> Unit = PrivateFilePermissions::enforce,
) {

    private val rootDirectory: File = root
    private val lock = Any()

    init {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw CalibrationStoreException(CalibrationErrorCode.STORAGE_FAILED, "calibration root could not be created")
        }
        if (!rootDirectory.isDirectory) {
            throw CalibrationStoreException(CalibrationErrorCode.STORAGE_FAILED, "calibration root is not a directory")
        }
        try {
            permissions(rootDirectory, true)
        } catch (io: IOException) {
            throw CalibrationStoreException(
                CalibrationErrorCode.STORAGE_FAILED,
                "calibration root permissions could not be restricted",
                io,
            )
        }
    }

    /** 读取已持久化的证据；缺失返回 null，损坏/更新 schema 抛 [CalibrationStoreException]。 */
    fun load(): CalibrationEvidence? = synchronized(lock) {
        val file = File(rootDirectory, FILE_NAME)
        if (!file.isFile) return null
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (io: IOException) {
            throw CalibrationStoreException(CalibrationErrorCode.STORAGE_FAILED, "calibration file could not be read", io)
        }
        decode(text)
    }

    /** 只返回与 [environment] 完全绑定的证据；环境变化即视为失效（不删除旧文件）。 */
    fun loadFor(environment: EnvironmentKey): CalibrationEvidence? = synchronized(lock) {
        val evidence = load() ?: return null
        if (evidence.environment == environment) evidence else null
    }

    /** 当前环境是否满足自动接听；任何读取/解析失败都 fail closed 为 false。 */
    fun isVerified(environment: EnvironmentKey): Boolean = try {
        val evidence = loadFor(environment) ?: return false
        CalibrationPolicy.canAutoAnswer(environment, evidence)
    } catch (t: CalibrationStoreException) {
        false
    }

    /**
     * 记录一个手动测试步骤。
     *
     * 若 [environment] 与已存证据不一致，旧证据被整体丢弃（环境变化即失效），从该环境的
     * 空白证据开始。五项全部通过时写入校准时间；任一步回退为未通过时时间戳归零。
     *
     * @return 写入磁盘后的完整证据（可能仍未验证）。
     */
    fun record(step: CalibrationStep, passed: Boolean, environment: EnvironmentKey): CalibrationEvidence =
        synchronized(lock) {
            val existing = load()
            val base = if (existing != null && existing.environment == environment) {
                existing
            } else {
                CalibrationEvidence(environment = environment)
            }
            val updated = when (step) {
                CalibrationStep.DOWNLINK_RECOGNIZED -> base.copy(downlinkRecognized = passed)
                CalibrationStep.PEER_HEARD_SPEECH -> base.copy(peerHeardSpeech = passed)
                CalibrationStep.MICROPHONE_ISOLATED -> base.copy(microphoneIsolated = passed)
                CalibrationStep.MANUAL_RESTORE -> base.copy(manualRestoreVerified = passed)
                CalibrationStep.PROCESS_FAILURE -> base.copy(processFailureVerified = passed)
            }
            val complete = updated.downlinkRecognized &&
                updated.peerHeardSpeech &&
                updated.microphoneIsolated &&
                updated.manualRestoreVerified &&
                updated.processFailureVerified
            val stamped = updated.copy(calibratedAtMs = if (complete) clockMs() else 0L)
            persist(stamped)
            stamped
        }

    /** 丢弃该环境的全部证据，写回一份空白证据。 */
    fun reset(environment: EnvironmentKey): CalibrationEvidence = synchronized(lock) {
        val blank = CalibrationEvidence(environment = environment)
        persist(blank)
        blank
    }

    /** 删除证据文件（例如用户主动清除校准）。 */
    fun clear() = synchronized(lock) {
        val file = File(rootDirectory, FILE_NAME)
        if (file.exists() && !file.delete()) {
            throw CalibrationStoreException(CalibrationErrorCode.STORAGE_FAILED, "calibration file could not be removed")
        }
    }

    // ------------------------------------------------------------------ 序列化

    private fun decode(text: String): CalibrationEvidence {
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration file is not a JSON object")
        }
        val json = parsed.asJsonObject
        val schema = schemaOf(json)
        if (schema > SCHEMA_VERSION) {
            throw CalibrationStoreException(
                CalibrationErrorCode.UNSUPPORTED_SCHEMA,
                "calibration schema " + schema + " is newer than supported " + SCHEMA_VERSION,
            )
        }
        val environmentJson = json.get("environment")
        if (environmentJson == null || !environmentJson.isJsonObject) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration environment is missing")
        }
        val environment = decodeEnvironment(environmentJson.asJsonObject)
        return CalibrationEvidence(
            environment = environment,
            downlinkRecognized = boolean(json, "downlinkRecognized"),
            peerHeardSpeech = boolean(json, "peerHeardSpeech"),
            microphoneIsolated = boolean(json, "microphoneIsolated"),
            manualRestoreVerified = boolean(json, "manualRestoreVerified"),
            processFailureVerified = boolean(json, "processFailureVerified"),
            calibratedAtMs = long(json, "calibratedAtMs"),
        )
    }

    private fun decodeEnvironment(json: JsonObject): EnvironmentKey = EnvironmentKey(
        apiLevel = int(json, "apiLevel"),
        romFingerprint = string(json, "romFingerprint"),
        kernelVersion = string(json, "kernelVersion"),
        dialerPackageVersion = string(json, "dialerPackageVersion"),
        frameworkVersion = string(json, "frameworkVersion"),
        bridgeVersion = string(json, "bridgeVersion"),
        audioPolicyDigest = string(json, "audioPolicyDigest"),
        simCarrier = string(json, "simCarrier"),
        transport = string(json, "transport"),
        route = string(json, "route"),
    )

    private fun persist(evidence: CalibrationEvidence) {
        val environment = JsonObject().apply {
            addProperty("apiLevel", evidence.environment.apiLevel)
            addProperty("romFingerprint", evidence.environment.romFingerprint)
            addProperty("kernelVersion", evidence.environment.kernelVersion)
            addProperty("dialerPackageVersion", evidence.environment.dialerPackageVersion)
            addProperty("frameworkVersion", evidence.environment.frameworkVersion)
            addProperty("bridgeVersion", evidence.environment.bridgeVersion)
            addProperty("audioPolicyDigest", evidence.environment.audioPolicyDigest)
            addProperty("simCarrier", evidence.environment.simCarrier)
            addProperty("transport", evidence.environment.transport)
            addProperty("route", evidence.environment.route)
        }
        val json = JsonObject().apply {
            addProperty("schema", SCHEMA_VERSION)
            add("environment", environment)
            addProperty("downlinkRecognized", evidence.downlinkRecognized)
            addProperty("peerHeardSpeech", evidence.peerHeardSpeech)
            addProperty("microphoneIsolated", evidence.microphoneIsolated)
            addProperty("manualRestoreVerified", evidence.manualRestoreVerified)
            addProperty("processFailureVerified", evidence.processFailureVerified)
            addProperty("calibratedAtMs", evidence.calibratedAtMs)
        }
        atomicWrite(
            File(rootDirectory, FILE_NAME),
            json.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun schemaOf(json: JsonObject): Int {
        val element = json.get("schema") ?: return 0
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration schema field is invalid")
        }
        val asDouble = element.asDouble
        if (!asDouble.isFinite() || asDouble != Math.floor(asDouble)) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration schema field is invalid")
        }
        if (asDouble < 0.0 || asDouble > Int.MAX_VALUE.toDouble()) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration schema field is invalid")
        }
        return asDouble.toInt()
    }

    private fun string(json: JsonObject, name: String): String {
        val element = json.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration field is invalid: " + name)
        }
        return element.asString
    }

    private fun boolean(json: JsonObject, name: String): Boolean {
        val element = json.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration field is invalid: " + name)
        }
        return element.asBoolean
    }

    private fun int(json: JsonObject, name: String): Int {
        val value = long(json, name)
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration field is invalid: " + name)
        }
        return value.toInt()
    }

    private fun long(json: JsonObject, name: String): Long {
        val element = json.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration field is invalid: " + name)
        }
        val asDouble = element.asDouble
        val asLong = element.asLong
        if (!asDouble.isFinite() || asDouble != asLong.toDouble()) {
            throw CalibrationStoreException(CalibrationErrorCode.CORRUPT, "calibration field is invalid: " + name)
        }
        return asLong
    }

    /** 同目录临时文件 + fsync + rename；失败时清理临时文件，绝不留半份证据。 */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            permissions(temp, false)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (io: IOException) {
            throw CalibrationStoreException(
                CalibrationErrorCode.STORAGE_FAILED,
                "calibration write failed: " + io.javaClass.simpleName,
                io,
            )
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    companion object {
        /** 当前 schema；升级时必须递增并保留旧版本兼容策略。 */
        const val SCHEMA_VERSION: Int = 1

        const val FILE_NAME: String = "calibration.json"
    }
}

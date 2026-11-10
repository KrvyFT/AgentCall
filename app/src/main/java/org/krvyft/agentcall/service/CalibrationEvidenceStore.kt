package org.krvyft.agentcall.service

import android.content.Context
import androidx.core.content.edit
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.policy.CalibrationPolicy

/**
 * 校准页的五个人工测试步骤（spec §9）。顺序固定，UI 与存储共用。
 *
 * 这些步骤只证明“用户在真机上做过测试”；创建 AudioRecord/AudioTrack 成功、方法可见都不算。
 */
enum class CalibrationManualStep(val storageKey: String) {
    /** 本机识别到通话下行（不是环境麦克风）。 */
    DOWNLINK_RECOGNIZED("downlink_recognized"),

    /** 对端听到测试语音（TTS 上行注入可用）。 */
    PEER_HEARD_SPEECH("peer_heard_speech"),

    /** 本机麦克风隔离生效（上行只来自注入，环境麦克风被隔离）。 */
    MICROPHONE_ISOLATED("microphone_isolated"),

    /** 人工恢复（解除隔离并交还人工）可用。 */
    MANUAL_RESTORE("manual_restore"),

    /** 进程故障注入后仍能终止/保持隔离。 */
    PROCESS_FAILURE("process_failure"),
}

/**
 * 本机记录的一份校准进度。
 *
 * [environment] 为 null 表示还没有取得可信环境（桥接 diagnose 不可用时）：此时五步即使全部
 * 勾选也**不能**视为已验证，因为校准证据必须绑定完整环境（spec §9）。
 */
data class CalibrationRecord(
    val environment: EnvironmentKey?,
    val completed: Set<CalibrationManualStep>,
    val calibratedAtMs: Long,
) {
    /** 只有环境可用、五步齐全且存在真实校准时间才为 true。 */
    val allVerified: Boolean
        get() = environment != null &&
            completed.size == CalibrationManualStep.entries.size &&
            calibratedAtMs > 0L

    /** 转成 core 的校准证据；环境未知或未完成时返回 null（绝不编造）。 */
    fun toEvidence(): CalibrationEvidence? {
        val environment = environment ?: return null
        if (!allVerified) return null
        return CalibrationEvidence(
            environment = environment,
            downlinkRecognized = CalibrationManualStep.DOWNLINK_RECOGNIZED in completed,
            peerHeardSpeech = CalibrationManualStep.PEER_HEARD_SPEECH in completed,
            microphoneIsolated = CalibrationManualStep.MICROPHONE_ISOLATED in completed,
            manualRestoreVerified = CalibrationManualStep.MANUAL_RESTORE in completed,
            processFailureVerified = CalibrationManualStep.PROCESS_FAILURE in completed,
            calibratedAtMs = calibratedAtMs,
        )
    }

    companion object {
        val EMPTY: CalibrationRecord = CalibrationRecord(
            environment = null,
            completed = emptySet(),
            calibratedAtMs = 0L,
        )
    }
}

/**
 * 校准页/主页的“已验证”裁决（Task 7B2 FIX ROUND 1，spec §9）。
 *
 * 本机记录只说明“用户勾选过五步”，**不是**自动接听资格：必须再走 core 的
 * [CalibrationPolicy]——它会拒绝 API 36（不继承校准）、环境不一致/不完整与缺失的证据。
 * 任何展示“已验证”的路径都必须经过这里，不能只看 [CalibrationRecord.allVerified]。
 */
object CalibrationVerdict {

    /**
     * @param record 本机记录的校准进度
     * @param environment 当前设备实际环境（diagnose 的结果）；null 表示无法取得
     * @return true 仅当证据绑定同一个受支持环境并通过 [CalibrationPolicy]
     */
    fun canAutoAnswer(record: CalibrationRecord, environment: EnvironmentKey?): Boolean {
        if (environment == null) return false
        if (record.environment != environment) return false
        val evidence = record.toEvidence() ?: return false
        return CalibrationPolicy.canAutoAnswer(environment, evidence)
    }
}

/**
 * APK 侧的校准进度存储（SharedPreferences，普通应用私有目录）。
 *
 * 边界必须如实说明：
 * - 这里保存的只是**用户在本页确认过的人工测试**，不含密钥、号码或原始音频；
 * - 是否允许自动接听由 **root 桥接**的 CalibrationStore 与 `AutoAnswerAuthorization` 决定，
 *   APK 没有写入桥接校准证据的能力（CLI 固定命令表也没有该操作）；
 * - 因此本存储只在能取得桥接 diagnose 的 EnvironmentKey 时才能生成可用于
 *   [org.krvyft.agentcall.core.policy.CalibrationPolicy] 的证据；环境未知时 fail closed。
 */
class CalibrationEvidenceStore(
    context: Context,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 读取本机记录；损坏或不可读时返回空记录（不把半份证据当成可用）。 */
    fun record(): CalibrationRecord {
        val raw = preferences.getString(KEY_RECORD, null) ?: return CalibrationRecord.EMPTY
        return runCatching { parse(raw) }.getOrNull() ?: CalibrationRecord.EMPTY
    }

    /**
     * 勾选/取消某一步。
     *
     * 环境变化时进度必须重来：旧环境的证据不能继承。全部五步完成时记录一次校准时间
     * （之后重复勾选不刷新时间）。环境为 null 时保持未验证（calibratedAtMs = 0）。
     */
    fun markStep(step: CalibrationManualStep, verified: Boolean, environment: EnvironmentKey?) {
        val current = record()
        val base = if (current.environment != environment) {
            CalibrationRecord(environment = environment, completed = emptySet(), calibratedAtMs = 0L)
        } else {
            current
        }
        val completed = if (verified) base.completed + step else base.completed - step
        val allSteps = completed.size == CalibrationManualStep.entries.size
        val calibratedAtMs = when {
            environment == null -> 0L
            !allSteps -> 0L
            base.calibratedAtMs > 0L -> base.calibratedAtMs
            else -> clockMs()
        }
        save(base.copy(completed = completed, calibratedAtMs = calibratedAtMs))
    }

    /** 为某个环境给出校准证据；环境不匹配或未完成返回 null。 */
    fun evidenceFor(environment: EnvironmentKey): CalibrationEvidence? {
        val record = record()
        if (record.environment != environment) return null
        return record.toEvidence()
    }

    /** 重新绑定环境（环境变化时清空本机进度）。 */
    fun bindEnvironment(environment: EnvironmentKey?) {
        val current = record()
        if (current.environment == environment) return
        save(CalibrationRecord(environment = environment, completed = emptySet(), calibratedAtMs = 0L))
    }

    fun reset() {
        preferences.edit { remove(KEY_RECORD) }
    }

    private fun save(record: CalibrationRecord) {
        preferences.edit { putString(KEY_RECORD, encode(record)) }
    }

    private fun encode(record: CalibrationRecord): String {
        val json = JsonObject()
        json.add("environment", encodeEnvironment(record.environment))
        val steps = JsonArray()
        for (step in CalibrationManualStep.entries) {
            if (step in record.completed) steps.add(step.storageKey)
        }
        json.add("completed", steps)
        json.addProperty("calibratedAtMs", record.calibratedAtMs)
        return json.toString()
    }

    private fun encodeEnvironment(environment: EnvironmentKey?): JsonObject? {
        if (environment == null) return null
        return JsonObject().apply {
            addProperty("apiLevel", environment.apiLevel)
            addProperty("romFingerprint", environment.romFingerprint)
            addProperty("kernelVersion", environment.kernelVersion)
            addProperty("dialerPackageVersion", environment.dialerPackageVersion)
            addProperty("frameworkVersion", environment.frameworkVersion)
            addProperty("bridgeVersion", environment.bridgeVersion)
            addProperty("audioPolicyDigest", environment.audioPolicyDigest)
            addProperty("simCarrier", environment.simCarrier)
            addProperty("transport", environment.transport)
            addProperty("route", environment.route)
        }
    }

    private fun parse(raw: String): CalibrationRecord? {
        val element = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
        if (!element.isJsonObject) return null
        val json = element.asJsonObject
        val environment = json.get("environment")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseEnvironment)
        val steps = json.get("completed")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val completed = steps.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.let { key -> CalibrationManualStep.entries.firstOrNull { it.storageKey == key } }
        }.toSet()
        val calibratedAtMs = json.get("calibratedAtMs")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong ?: 0L
        return CalibrationRecord(environment, completed, calibratedAtMs)
    }

    private fun parseEnvironment(json: JsonObject): EnvironmentKey? {
        fun int(name: String): Int? = json.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt
        fun string(name: String): String? = json.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        val apiLevel = int("apiLevel") ?: return null
        return EnvironmentKey(
            apiLevel = apiLevel,
            romFingerprint = string("romFingerprint") ?: return null,
            kernelVersion = string("kernelVersion") ?: return null,
            dialerPackageVersion = string("dialerPackageVersion") ?: return null,
            frameworkVersion = string("frameworkVersion") ?: return null,
            bridgeVersion = string("bridgeVersion") ?: return null,
            audioPolicyDigest = string("audioPolicyDigest") ?: return null,
            simCarrier = string("simCarrier") ?: return null,
            transport = string("transport") ?: return null,
            route = string("route") ?: return null,
        )
    }

    companion object {
        /** 只存校准进度，不存号码/密钥/音频。 */
        const val PREFERENCES_NAME: String = "agentcall_calibration"

        private const val KEY_RECORD = "record"
    }
}

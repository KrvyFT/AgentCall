package org.krvyft.agentcall.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.core.model.PROTOCOL_VERSION
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.policy.CalibrationPolicy
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.ControlTimeouts
import org.krvyft.agentcall.ipc.ControlTransport
import org.krvyft.agentcall.ipc.LocalSocketControlTransport

/**
 * 桥接 `calibration.read` 的只读视图（Task 11H2）。
 *
 * 负载与 `status.calibration` / `diagnose.calibration` 内嵌对象**完全相同**
 * （docs/config-schema §3）：`available`/`supportedApiLevel`/`environmentComplete`/
 * `verified`/`blockers`/`environmentMatchesEvidence`。这里只保留判定所需字段，
 * 不复制环境内容，避免把桥接的观测/背书混进 APP 自己的 `EnvironmentKey`。
 *
 * 语义（全部 fail closed）：
 * - [available]：校准存储是否接线；false 时只给 `{"available":false}`，其余字段不存在；
 * - [supported]：存储可用且 [supportedApiLevel] 等于 core 策略唯一支持的 SDK；
 * - [verified]：桥接按**有效环境**（观测 + root 背书）与持久证据算出的结论；
 * - [environmentComplete]：有效环境没有 UNKNOWN/空白字段；
 * - [environmentMatchesEvidence]：有效环境与证据绑定环境逐字段相同；
 * - [bridgeBlockers]：桥接给出的具体未满足项（CalibrationBlocker 名称）。
 *
 * [authorizesAutoAnswer] 只有在五项全部为 true 时才成立；任何未知/缺失/类型不符都不会
 * 走到这里（[BridgeCalibrationReader] 直接返回 [BridgeRead.Unavailable]）。
 */
data class BridgeCalibrationView(
    val available: Boolean,
    val supportedApiLevel: Int?,
    val verified: Boolean,
    val environmentComplete: Boolean,
    val environmentMatchesEvidence: Boolean,
    val bridgeBlockers: List<String> = emptyList(),
) {

    /** 桥接校准能力是否受支持（存储已接线 + API 在策略支持范围内）。 */
    val supported: Boolean
        get() = available && supportedApiLevel == CalibrationPolicy.SUPPORTED_API_LEVEL

    /**
     * 桥接是否授权自动接听：`supported && verified && environmentComplete &&
     * environmentMatchesEvidence`。**不包含** APP 本机证据；后者仍由
     * [BridgeCalibrationProbe] 经 core [CalibrationPolicy] 判定。
     */
    val authorizesAutoAnswer: Boolean
        get() = supported && verified && environmentComplete && environmentMatchesEvidence

    /**
     * 未满足项（固定机器码、顺序稳定、去重），供 UI/诊断直接渲染。
     *
     * 不满足时至少返回一条：未接线 → [CALIBRATION_UNAVAILABLE]；API 不支持 →
     * [CALIBRATION_API_UNSUPPORTED]；环境不完整/不匹配/证据未验证 → 对应码。
     * 桥接自报的 [bridgeBlockers] 会规范化成 `calibration_*` 机器码并追加（去重）。
     */
    fun readinessBlockers(): List<String> {
        val codes = LinkedHashSet<String>()
        if (!available) {
            codes += CALIBRATION_UNAVAILABLE
            return codes.toList()
        }
        if (!supported) codes += CALIBRATION_API_UNSUPPORTED
        if (!environmentComplete) codes += CALIBRATION_ENVIRONMENT_INCOMPLETE
        if (!environmentMatchesEvidence) codes += CALIBRATION_ENVIRONMENT_MISMATCH
        if (!verified) codes += CALIBRATION_NOT_VERIFIED
        if (!authorizesAutoAnswer) {
            for (raw in bridgeBlockers) {
                calibrationBlockerCode(raw)?.let { codes += it }
            }
        }
        return codes.toList()
    }

    companion object {
        /** 校准存储未接线 / 读取失败 / 负载未知：APP 不能据此判定任何事。 */
        const val CALIBRATION_UNAVAILABLE: String = "calibration_unavailable"

        /** 桥接声明的受支持 SDK 与 core 策略不一致。 */
        const val CALIBRATION_API_UNSUPPORTED: String = "calibration_api_unsupported"

        /** 有效环境缺字段/含 UNKNOWN，无法绑定校准证据。 */
        const val CALIBRATION_ENVIRONMENT_INCOMPLETE: String = "calibration_environment_incomplete"

        /** 有效环境与证据环境不一致（设备/系统/音频策略已变）。 */
        const val CALIBRATION_ENVIRONMENT_MISMATCH: String = "calibration_environment_mismatch"

        /** 桥接证据未通过 CalibrationPolicy（含五项人工测试/时间戳/API）。 */
        const val CALIBRATION_NOT_VERIFIED: String = "calibration_not_verified"

        /** 桥接具体未满足项的机器码前缀。 */
        const val BLOCKER_PREFIX: String = "calibration_"

        private val CODE_PATTERN = Regex("[a-z][a-z0-9_]*")

        /**
         * 把桥接的 CalibrationBlocker 名称（如 `ENVIRONMENT_MISMATCH`）规范成固定机器码。
         * 词表外/含空白/控制字符的值直接丢弃，绝不让任意负载进入 UI 文本。
         */
        internal fun calibrationBlockerCode(raw: String): String? {
            val token = raw.trim().lowercase()
            if (token.isEmpty()) return null
            val code = if (token.startsWith(BLOCKER_PREFIX)) token else BLOCKER_PREFIX + token
            return code.takeIf { it.matches(CODE_PATTERN) }
        }
    }
}

/**
 * `calibration.read` 的只读客户端（Task 11H2）。
 *
 * 与 [org.krvyft.agentcall.ipc.BridgeClient] 的只读方法同一安全模型：
 * - 只发固定方法 [RolePolicy.METHOD_CALIBRATION_READ]（APP 角色只读）；
 * - 请求使用 [ControlTimeouts.clientTimeoutMsFor] 的每方法预算，另有客户端兜底超时；
 * - 响应必须对应本请求且版本一致，否则按不可用处理（防串话/重放）；
 * - 错误原样返回固定机器码，负载未知/字段缺失/类型不符一律 [BridgeRead.Unavailable]；
 * - 每次调用一条 LocalSocket（与控制通道同构），不缓存任何结果。
 *
 * 这里单独建客户端而不是改 [org.krvyft.agentcall.ipc.BridgeClient]：status 投影刻意只保留
 * 已稳定的标量字段，校准视图是独立、可淘汰的只读投影；两条通道互不影响各自的失败语义。
 */
class BridgeCalibrationReader(
    private val control: ControlTransport = LocalSocketControlTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = {},
) {

    private val counter = AtomicLong(0)

    suspend fun read(): BridgeRead<BridgeCalibrationView> {
        val request = RpcRequest(
            id = "app-cal-" + counter.incrementAndGet(),
            method = RolePolicy.METHOD_CALIBRATION_READ,
            payload = "",
        )
        val response = try {
            withTimeoutOrNull(ControlTimeouts.clientTimeoutMsFor(request.method)) {
                withContext(ioDispatcher) { control.request(request) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        } ?: return BridgeRead.Unavailable(ERROR_BRIDGE_UNAVAILABLE)

        if (response.id != request.id || response.version != PROTOCOL_VERSION) {
            logger("bridge.calibration_read_mismatched")
            return BridgeRead.Unavailable(ERROR_INVALID_RESPONSE)
        }
        val error = response.error
        if (error != null) {
            logger("bridge.calibration_read_failed code=" + error.code)
            return BridgeRead.Unavailable(error.code)
        }
        return parseBridgeCalibrationView(response.payload)
    }

    suspend fun close() {
        runCatching { control.close() }
    }

    companion object {
        /** 控制通道不可达（桥接未起/被拒/uid 不是 root）。 */
        const val ERROR_BRIDGE_UNAVAILABLE: String = "bridge_unavailable"

        /** 响应与请求不匹配或版本不一致。 */
        const val ERROR_INVALID_RESPONSE: String = "invalid_response"

        /** 负载不是固定校准视图（缺失、非对象、字段类型不符）。 */
        const val ERROR_PAYLOAD: String = "calibration_payload_invalid"
    }
}

/**
 * 解析 `calibration.read` 负载（与 status/diagnose 内嵌对象同构）。
 *
 * 严格解析：`available` 必须是 JSON 布尔；可用时 `supportedApiLevel`（整数）、
 * `verified`/`environmentComplete`/`environmentMatchesEvidence`（布尔）与
 * `blockers`（字符串数组）都必须存在且类型正确。任何缺失/类型不符 → 不可用，
 * **绝不**用默认值猜成“已验证”。
 */
internal fun parseBridgeCalibrationView(payload: String?): BridgeRead<BridgeCalibrationView> {
    if (payload.isNullOrBlank()) return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    val element = runCatching { JsonParser.parseString(payload) }.getOrNull()
        ?: return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    if (!element.isJsonObject) return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    val json = element.asJsonObject

    val available = json.booleanOrNull("available")
        ?: return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    if (!available) {
        // 存储未接线：负载只有 available=false，没有任何可判定事实。
        return BridgeRead.Available(
            BridgeCalibrationView(
                available = false,
                supportedApiLevel = null,
                verified = false,
                environmentComplete = false,
                environmentMatchesEvidence = false,
            ),
        )
    }

    val supportedApiLevel = json.intOrNull("supportedApiLevel")
        ?: return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    val verified = json.booleanOrNull("verified")
        ?: return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    val environmentComplete = json.booleanOrNull("environmentComplete")
        ?: return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    val environmentMatchesEvidence = json.booleanOrNull("environmentMatchesEvidence")
        ?: return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)

    val blockersElement = json.get("blockers")
        ?: return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    if (!blockersElement.isJsonArray) return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
    val blockers = ArrayList<String>(blockersElement.asJsonArray.size())
    for (entry in blockersElement.asJsonArray) {
        if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
            return BridgeRead.Unavailable(BridgeCalibrationReader.ERROR_PAYLOAD)
        }
        blockers += entry.asString
    }

    return BridgeRead.Available(
        BridgeCalibrationView(
            available = true,
            supportedApiLevel = supportedApiLevel,
            verified = verified,
            environmentComplete = environmentComplete,
            environmentMatchesEvidence = environmentMatchesEvidence,
            bridgeBlockers = blockers,
        ),
    )
}

private fun JsonObject.booleanOrNull(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
    return element.asBoolean
}

private fun JsonObject.intOrNull(name: String): Int? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
    val value = element.asDouble
    if (!value.isFinite() || value != Math.floor(value)) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}

package org.krvyft.agentcall.service

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.ipc.ControlTimeouts
import org.krvyft.agentcall.ipc.ControlTransport
import org.krvyft.agentcall.ipc.LocalSocketControlTransport

/**
 * 桥接 `status` 的只读投影（Task 7B2）。
 *
 * 只保留 APK 需要的布尔/机器码字段；不含号码、密钥、连接凭据。
 */
data class BridgeStatus(
    /** 三态：桥接明确回答 true/false，或字段缺失/为 "unknown" 时的 UNKNOWN。 */
    val hookConnected: HookPresence,
    /** Google 电话默认角色；null 表示未知（桥接探测失败）。 */
    val dialerDefaultRole: Boolean?,
    /** APP 是否已配对（桥接视图）。 */
    val paired: Boolean?,
    /** 监督阶段名（机器码）。 */
    val supervisionPhase: String?,
    /** 桥接版本号。 */
    val bridgeVersion: String?,
    /** 总开关（与配置镜像一致时可用）。 */
    val enabled: Boolean?,
)

/** status 调用的结果；[Unavailable.code] 是固定机器码（forbidden/bridge_unavailable/...）。 */
sealed interface BridgeStatusResult {
    data class Available(val status: BridgeStatus) : BridgeStatusResult
    data class Unavailable(val code: String) : BridgeStatusResult
}

/** diagnose 调用的结果；环境不完整也按不可用处理（校准绑定必须完整）。 */
sealed interface EnvironmentResult {
    data class Available(val environment: EnvironmentKey) : EnvironmentResult
    data class Unavailable(val code: String) : EnvironmentResult
}

/**
 * APP 角色的只读诊断客户端（status + diagnose）。
 *
 * RolePolicy 的 APP 固定表现已包含 status/diagnose（只读），生产装配通常改用
 * [org.krvyft.agentcall.ipc.BridgeClient] 读同一份负载；本类仍被 UI/校准页使用。
 * `forbidden` 只可能来自旧桥接或角色表不匹配，必须表达为可观察的“不可用”，绝不能
 * 兜底成“在线/已校准”：就绪判定据此保持 fail closed。
 *
 * 传输复用 [ControlTransport]（生产是 LocalSocket，测试注入假实现）；所有请求都有
 * [ControlTimeouts] 的每方法预算，另有客户端兜底超时。
 */
class BridgeDiagnostics(
    private val control: ControlTransport = LocalSocketControlTransport(),
) {

    private val counter = AtomicLong(0)

    suspend fun status(): BridgeStatusResult {
        val response = request(RolePolicy.METHOD_STATUS)
            ?: return BridgeStatusResult.Unavailable(ERROR_UNREACHABLE)
        val error = response.error
        if (error != null) return BridgeStatusResult.Unavailable(error.code)
        val json = parseObject(response.payload)
            ?: return BridgeStatusResult.Unavailable(ERROR_PAYLOAD)
        return BridgeStatusResult.Available(
            BridgeStatus(
                hookConnected = HookPresence.fromStatusField(rawField(json, "hookConnected")),
                dialerDefaultRole = booleanField(json, "dialerDefaultRole"),
                paired = booleanField(json, "paired"),
                supervisionPhase = stringField(json, "supervisionPhase"),
                bridgeVersion = stringField(json, "bridgeVersion"),
                enabled = booleanField(json, "enabled"),
            ),
        )
    }

    suspend fun environment(): EnvironmentResult {
        val response = request(RolePolicy.METHOD_DIAGNOSE)
            ?: return EnvironmentResult.Unavailable(ERROR_UNREACHABLE)
        val error = response.error
        if (error != null) return EnvironmentResult.Unavailable(error.code)
        val json = parseObject(response.payload)
            ?: return EnvironmentResult.Unavailable(ERROR_PAYLOAD)
        val environment = json.get("environment")
        if (environment == null || !environment.isJsonObject) {
            return EnvironmentResult.Unavailable(ERROR_PAYLOAD)
        }
        val parsed = environment.asJsonObject
        val apiLevel = intField(parsed, "apiLevel") ?: return EnvironmentResult.Unavailable(ERROR_PAYLOAD)
        val fields = listOf(
            "romFingerprint", "kernelVersion", "dialerPackageVersion", "frameworkVersion",
            "bridgeVersion", "audioPolicyDigest", "simCarrier", "transport", "route",
        )
        val values = fields.map { name ->
            stringField(parsed, name)?.takeIf { it.isNotBlank() }
                ?: return EnvironmentResult.Unavailable(ERROR_PAYLOAD)
        }
        return EnvironmentResult.Available(
            EnvironmentKey(
                apiLevel = apiLevel,
                romFingerprint = values[0],
                kernelVersion = values[1],
                dialerPackageVersion = values[2],
                frameworkVersion = values[3],
                bridgeVersion = values[4],
                audioPolicyDigest = values[5],
                simCarrier = values[6],
                transport = values[7],
                route = values[8],
            ),
        )
    }

    suspend fun close() {
        runCatching { control.close() }
    }

    private suspend fun request(method: String): RpcResponse? = try {
        val request = RpcRequest(
            id = "app-diag-" + counter.incrementAndGet(),
            method = method,
            payload = "",
        )
        withTimeoutOrNull(ControlTimeouts.clientTimeoutMsFor(method)) { control.request(request) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        null
    }

    private fun parseObject(payload: String): JsonObject? {
        if (payload.isBlank()) return null
        val element = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    /** 保留原始 JSON 类型（布尔或字符串），交给 [HookPresence] 做三态解析。 */
    private fun rawField(json: JsonObject, name: String): Any? {
        val element: JsonElement = json.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isString -> primitive.asString
            else -> null
        }
    }

    private fun booleanField(json: JsonObject, name: String): Boolean? {
        val value = rawField(json, name) ?: return null
        return value as? Boolean
    }

    private fun stringField(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun intField(json: JsonObject, name: String): Int? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val value = element.asDouble
        if (!value.isFinite() || value != Math.floor(value)) return null
        if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
        return value.toInt()
    }

    companion object {
        /** 控制通道不可达（服务未启动/被抢绑/uid 不是 root）。 */
        const val ERROR_UNREACHABLE: String = "bridge_unavailable"

        /** 响应负载不是预期的 JSON 结构。 */
        const val ERROR_PAYLOAD: String = "diagnostics_payload_invalid"
    }
}

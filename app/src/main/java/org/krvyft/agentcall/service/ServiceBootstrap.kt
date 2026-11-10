package org.krvyft.agentcall.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.protocol.RpcCodec
import org.krvyft.agentcall.core.rules.ConfigValidator
import org.krvyft.agentcall.core.session.SessionLimits

/**
 * HOOK 进程的存在性事实。三态是必须的：桥接未向 APP 角色暴露 `status` 时，
 * “不知道”不能被当作“在线”，也不能被当作“离线”静默忽略——它必须阻塞自动接听。
 */
enum class HookPresence {
    /** 5000 ms 心跳窗口内收到过 HOOK 心跳。 */
    PRESENT,

    /** 桥接明确回答 HOOK 不在心跳窗口内。 */
    ABSENT,

    /** 无法确定（APP 无权限/桥接不可达/字段缺失）。fail closed。 */
    UNKNOWN,
    ;

    companion object {
        /**
         * 解析桥接 `status.hookConnected` 的三态值：布尔值或字符串 "true"/"false"；
         * "unknown"/缺失/null 一律 [UNKNOWN]。
         */
        fun fromStatusField(raw: Any?): HookPresence = when (raw) {
            true, "true" -> PRESENT
            false, "false" -> ABSENT
            else -> UNKNOWN
        }
    }
}

/** 公开配置的就绪事实（只含布尔，绝不携带密钥或号码）。 */
data class ConfigFacts(
    val enabled: Boolean,
    val valid: Boolean,
) {
    companion object {
        /** 配置不可读时的唯一安全表示：按“关闭且无效”处理。 */
        val UNAVAILABLE = ConfigFacts(enabled = false, valid = false)
    }
}

/**
 * 一次就绪判定的全部输入事实。**不包含“服务已启动”之类的意图字段**：
 * 启动请求本身不是就绪（spec §6.2）。
 */
data class ReadinessFacts(
    val bridgeReady: Boolean,
    val hookPresence: HookPresence,
    val config: ConfigFacts,
    val calibrationAllowed: Boolean,
    val networkReachable: Boolean,
)

/** 阻止自动接听的具体原因；[code] 是机器可读、无敏感数据的固定短码。 */
enum class BootstrapBlocker(val code: String) {
    /** 桥接控制不可达，或 APP 身份/配对核验失败。 */
    BRIDGE_UNREACHABLE("bridge_unreachable"),

    /** 桥接明确回答 HOOK 不在 5000 ms 心跳窗口内。 */
    HOOK_NOT_CONNECTED("hook_not_connected"),

    /** 无法取得 HOOK 存在性（fail closed，不等于离线）。 */
    HOOK_PRESENCE_UNKNOWN("hook_presence_unknown"),

    /** 总开关关闭。 */
    CONFIG_DISABLED("config_disabled"),

    /** 配置未通过 core 的 ConfigValidator。 */
    CONFIG_INVALID("config_invalid"),

    /** 校准策略不允许自动接听（环境/人工测试证据不满足）。 */
    CALIBRATION_BLOCKED("calibration_not_verified"),

    /** 当前没有已验证的可用网络。 */
    NETWORK_UNREACHABLE("network_unreachable"),
}

/** 就绪裁决；[ready] 为 true 当且仅当 [blockers] 为空。 */
data class BootstrapDecision(
    val ready: Boolean,
    val blockers: List<BootstrapBlocker>,
) {
    val codes: List<String> get() = blockers.map { it.code }
}

/**
 * 纯裁决逻辑（JVM 可测）：把事实映射成“可以自动接听 / 保持原响铃”。
 *
 * 设计约束（spec §6.2/§8/§11）：
 * - 任何一项缺失或未知都返回 false，调用方保留原响铃；
 * - 顺序固定，便于 UI 与日志稳定；
 * - 这里没有“已启动/已发广播”入口，也没有任何可绕过某项事实的开关。
 */
object BootstrapPolicy {

    fun evaluate(facts: ReadinessFacts): BootstrapDecision {
        val blockers = ArrayList<BootstrapBlocker>(7)
        if (!facts.bridgeReady) blockers += BootstrapBlocker.BRIDGE_UNREACHABLE
        when (facts.hookPresence) {
            HookPresence.PRESENT -> Unit
            HookPresence.ABSENT -> blockers += BootstrapBlocker.HOOK_NOT_CONNECTED
            HookPresence.UNKNOWN -> blockers += BootstrapBlocker.HOOK_PRESENCE_UNKNOWN
        }
        if (!facts.config.enabled) blockers += BootstrapBlocker.CONFIG_DISABLED
        if (!facts.config.valid) blockers += BootstrapBlocker.CONFIG_INVALID
        if (!facts.calibrationAllowed) blockers += BootstrapBlocker.CALIBRATION_BLOCKED
        if (!facts.networkReachable) blockers += BootstrapBlocker.NETWORK_UNREACHABLE
        return BootstrapDecision(ready = blockers.isEmpty(), blockers = blockers)
    }
}

/** 桥接控制可达 + APP 配对/身份核验（生产实现：`BridgeClient.ensureReady()`）。 */
fun interface BridgeProbe {
    suspend fun isReachable(): Boolean
}

/**
 * HOOK 心跳存在性（生产实现读桥接 `status.hookConnected`）。
 *
 * 桥接当前只把 `status` 开放给 CLI_ROOT；APP 调用会被 `RolePolicy` 拒绝，此时这里必须
 * 返回 [HookPresence.UNKNOWN]（→ 永不自动接听），不能猜测为在线。
 */
fun interface HookPresenceProbe {
    suspend fun presence(): HookPresence
}

/** 公开配置读取（生产实现：`BridgeClient.readRedactedConfig()` + core 的 ConfigValidator）。 */
fun interface ConfigProbe {
    suspend fun read(): ConfigFacts
}

/** 校准/环境策略（生产实现：桥接 diagnose 的环境 + 人工校准证据 + CalibrationPolicy）。 */
fun interface CalibrationProbe {
    suspend fun canAutoAnswer(): Boolean
}

/** 网络可达性（生产实现：ConnectivityManager 的 VALIDATED + INTERNET 能力）。 */
fun interface NetworkProbe {
    fun isOnline(): Boolean
}

/** 一组可注入的就绪探针；生产装配在 [AgentCallService] 内，测试可完全替换。 */
class ReadinessProbes(
    val bridge: BridgeProbe,
    val hook: HookPresenceProbe,
    val config: ConfigProbe,
    val calibration: CalibrationProbe,
    val network: NetworkProbe,
)

/**
 * 服务就绪握手（spec §6.2）：每次调用都重新探测，从不缓存 true。
 *
 * 单个探针有 [probeTimeoutMs] 上界：任何探针挂起都按“该项缺失”fail closed，绝不把
 * 调用方（服务/来电路径）无限期挂住。异常同样按缺失处理；CancellationException 原样传播。
 */
class ServiceBootstrap(
    private val probes: ReadinessProbes,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    private val logger: (String) -> Unit = {},
) {

    init {
        require(probeTimeoutMs > 0L) { "probeTimeoutMs must be positive" }
    }

    /** @return true 表示五项事实全部满足、可以自动接听；任何缺失都返回 false。 */
    suspend fun ensureReady(): Boolean = evaluate().ready

    /** 完整裁决（含全部阻塞原因），供 UI/诊断显示“到底缺哪一项”。 */
    suspend fun evaluate(): BootstrapDecision {
        val facts = ReadinessFacts(
            bridgeReady = probe("bridge") { probes.bridge.isReachable() } ?: false,
            hookPresence = probe("hook") { probes.hook.presence() } ?: HookPresence.UNKNOWN,
            config = probe("config") { probes.config.read() } ?: ConfigFacts.UNAVAILABLE,
            calibrationAllowed = probe("calibration") { probes.calibration.canAutoAnswer() } ?: false,
            networkReachable = probe("network") { probes.network.isOnline() } ?: false,
        )
        val decision = BootstrapPolicy.evaluate(facts)
        for (blocker in decision.blockers) logger("bootstrap.not_ready code=" + blocker.code)
        return decision
    }

    private suspend fun <T : Any> probe(label: String, block: suspend () -> T): T? = try {
        withTimeoutOrNull(probeTimeoutMs) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // 探针实现可能触碰 Android/IO；异常只能降级成“该项缺失”。
        logger("bootstrap.probe_failed probe=" + label + " error=" + failure.javaClass.simpleName)
        null
    }

    companion object {
        /**
         * HOOK 心跳窗口：与监督端的失联判定同源（spec §8 的 5000 ms），不另写魔数。
         */
        const val HOOK_PRESENCE_WINDOW_MS: Long = SessionLimits.HEARTBEAT_TIMEOUT_MS

        /** 单个探针的上界；取 HOOK 心跳窗口，保证一次判定不会晚于失联窗口太多。 */
        const val PROBE_TIMEOUT_MS: Long = HOOK_PRESENCE_WINDOW_MS
    }
}

/**
 * 从桥接 `config.read` 的脱敏负载派生公开配置事实（无 Android 依赖，JVM 可测）。
 *
 * 负载必须包含完整配置字段（`enabled`/`revision`/规则与时序字段）；字段缺失或类型不符
 * 一律按 [ConfigFacts.UNAVAILABLE] 处理，不拿宽松默认值猜测“已启用”。
 */
class RedactedConfigProbe(
    private val readRedacted: suspend () -> String?,
) : ConfigProbe {

    override suspend fun read(): ConfigFacts {
        val parsed = parseRedactedAgentConfig(readRedacted()) ?: return ConfigFacts.UNAVAILABLE
        return ConfigFacts(
            enabled = parsed.enabled,
            valid = ConfigValidator.validate(parsed).isValid,
        )
    }
}

/**
 * 解析桥接 `config.read` 的脱敏负载（`{"config":{...},"secrets":{...}}`）。
 *
 * 只接受**完整**的公开配置字段集合：缺字段说明负载不可信，绝不拿宽松默认值猜测“已启用”。
 * 返回的 [AgentConfig] 只含公开字段；密钥状态不参与解析。
 */
internal fun parseRedactedAgentConfig(payload: String?): AgentConfig? {
    if (payload.isNullOrBlank()) return null
    val element = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
    if (!element.isJsonObject) return null
    val config = element.asJsonObject.get("config") ?: return null
    if (!config.isJsonObject) return null
    val json: JsonObject = config.asJsonObject
    val required = listOf(
        "enabled", "mode", "blacklist", "whitelist", "region", "handleUnknownNumbers",
        "answerDelayMs", "maxCallDurationMs", "enabledPhoneAccounts", "asr", "llm", "tts",
        "voice", "speed", "openingLine", "prompt", "revision",
    )
    if (!json.keySet().containsAll(required)) return null
    return RpcCodec.decodeAgentConfig(json.toString())
}

/**
 * 便捷工厂：把公开配置里的三项端点转成“是否配置齐全且为 HTTPS”的判断，仅供 UI/诊断展示。
 * 与 [org.krvyft.agentcall.ai.ConversationEngine.isVoiceCapable] 复用同一规则。
 */
internal fun AgentConfig.hasCompleteVoiceEndpoints(): Boolean =
    org.krvyft.agentcall.ai.ConversationEngine.isVoiceCapable(this)

package org.krvyft.agentcall.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.CalibrationEvidence
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.policy.CalibrationPolicy
import org.krvyft.agentcall.core.protocol.RpcCodec
import org.krvyft.agentcall.core.rules.ConfigValidator
import org.krvyft.agentcall.core.session.SessionLimits
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.BridgeStatusFacts

/**
 * HOOK 进程的存在性事实。三态是必须的：桥接明确回答 true/false 之外的一切
 * （"unknown"、字段缺失、status 不可达/被拒）都是“不知道”，不能被当作“在线”，
 * 也不能被当作“离线”静默忽略——它必须阻塞自动接听。
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

/**
 * 桥接 `status` 的一次只读快照，供就绪判定与 UI 共用（Task 7B3）。
 *
 * [hookPresence] 来自 `status.hookConnected`：监督端只在 5000 ms 心跳窗口内认定 HOOK
 * 在线，因此这是“窗口内的真实结论”，不是 APP 自己的更长/更短超时。[statusAvailable] 区分
 * “status 读到了但字段是 unknown”与“status 根本不可用”，两者都仍然 fail closed。
 */
data class LiveFacts(
    val hookPresence: HookPresence,
    val dialerDefaultRole: Boolean?,
    val supervisionPhase: String?,
    val supervisionBlocked: String?,
    val statusAvailable: Boolean,
) {
    companion object {
        /** status 不可用时的唯一安全表示（UNKNOWN + 无默认角色）；绝不表示“在线”。 */
        val UNAVAILABLE: LiveFacts = LiveFacts(
            hookPresence = HookPresence.UNKNOWN,
            dialerDefaultRole = null,
            supervisionPhase = null,
            supervisionBlocked = null,
            statusAvailable = false,
        )
    }
}

/** 一次 `status` 读取（生产实现 [BridgeLiveFactsProbe]，读 [org.krvyft.agentcall.ipc.BridgeClient.status]）。 */
fun interface LiveFactsProbe {
    suspend fun read(): LiveFacts
}

/** 监督阶段（生产实现读 `status.supervisionPhase`）；null 表示无法确定。 */
fun interface SupervisionPhaseProbe {
    suspend fun phase(): String?
}

/** 公开配置的就绪事实（只含布尔，绝不携带密钥或号码）。 */
data class ConfigFacts(
    val enabled: Boolean,
    /** core ConfigValidator 的结论（时序/地区/名单/长度等）。 */
    val valid: Boolean,
    /**
     * 三项语音端点（ASR/LLM/TTS）齐备且都是可用的 HTTPS 端点。
     *
     * 与 [valid] **分开**是必须的：ConfigValidator 只管时序/地区/名单，不检查端点；
     * 端点缺失时 READY 仍可能为 true，而 [org.krvyft.agentcall.ai.ConversationEngine.create]
     * 会返回 null，导致“接听后立刻终止”。规则与 ConversationEngine.isVoiceCapable 同源。
     */
    val voiceCapable: Boolean,
) {
    companion object {
        /** 配置不可读时的唯一安全表示：按“关闭、无效且无语音能力”处理。 */
        val UNAVAILABLE = ConfigFacts(enabled = false, valid = false, voiceCapable = false)
    }
}

/**
 * 一次就绪判定的全部输入事实。**不包含“服务已启动”之类的意图字段**：
 * 启动请求本身不是就绪（spec §6.2）。
 */
data class ReadinessFacts(
    val bridgeReady: Boolean,
    val hookPresence: HookPresence,
    /**
     * Google 电话是否为默认拨号器；null 表示无法确定。
     *
     * 不是默认拨号器时 PhoneAccount 不具备接听资格（spec §8 的接听前检查项），
     * 而“无法确定”绝不能当成“是”——两者都必须保留原响铃。
     */
    val dialerDefaultRole: Boolean?,
    val config: ConfigFacts,
    val calibrationAllowed: Boolean,
    val networkReachable: Boolean,
    /**
     * 监督阶段机器码（观测值，只用于 UI/诊断，不单独构成阻塞项）。
     *
     * BLOCKED 时桥接自己会在 `session.request`/prepareAnswer 拒绝新的 AI 会话；
     * APP 不在这里另造一个阻塞码（固定码表与字符串资源属于既有契约）。
     */
    val supervisionPhase: String? = null,
)

/** 阻止自动接听的具体原因；[code] 是机器可读、无敏感数据的固定短码。 */
enum class BootstrapBlocker(val code: String) {
    /** 桥接控制不可达，或 APP 身份/配对核验失败。 */
    BRIDGE_UNREACHABLE("bridge_unreachable"),

    /** 桥接明确回答 HOOK 不在 5000 ms 心跳窗口内。 */
    HOOK_NOT_CONNECTED("hook_not_connected"),

    /** 无法取得 HOOK 存在性（fail closed，不等于离线）。 */
    HOOK_PRESENCE_UNKNOWN("hook_presence_unknown"),

    /** Google 电话不是默认拨号器，或无法确定默认角色（fail closed）。 */
    DIALER_NOT_DEFAULT("dialer_not_default"),

    /** 总开关关闭。 */
    CONFIG_DISABLED("config_disabled"),

    /** 配置未通过 core 的 ConfigValidator。 */
    CONFIG_INVALID("config_invalid"),

    /** ASR/LLM/TTS 三项语音端点不齐备（CONFIG_INVALID 不含端点检查）。 */
    CONFIG_VOICE_UNSUPPORTED("config_voice_unsupported"),

    /** 校准策略不允许自动接听（环境/人工测试证据不满足）。 */
    CALIBRATION_BLOCKED("calibration_not_verified"),

    /** 当前没有已验证的可用网络。 */
    NETWORK_UNREACHABLE("network_unreachable"),
}

/** 就绪裁决；[ready] 为 true 当且仅当 [blockers] 为空。 */
data class BootstrapDecision(
    val ready: Boolean,
    val blockers: List<BootstrapBlocker>,
    /** 本次判定观测到的 HOOK 存在性（供 UI/通知显示，不改变裁决）。 */
    val hookPresence: HookPresence = HookPresence.UNKNOWN,
    /** 本次判定观测到的默认拨号角色；null = 无法确定。 */
    val dialerDefaultRole: Boolean? = null,
    /** 本次判定观测到的监督阶段机器码。 */
    val supervisionPhase: String? = null,
    /** status 是否真的读到（false 表示 hook/dialer 都是“不可用”，不是“离线”）。 */
    val statusAvailable: Boolean = false,
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
        val blockers = ArrayList<BootstrapBlocker>(8)
        if (!facts.bridgeReady) blockers += BootstrapBlocker.BRIDGE_UNREACHABLE
        when (facts.hookPresence) {
            HookPresence.PRESENT -> Unit
            HookPresence.ABSENT -> blockers += BootstrapBlocker.HOOK_NOT_CONNECTED
            HookPresence.UNKNOWN -> blockers += BootstrapBlocker.HOOK_PRESENCE_UNKNOWN
        }
        // 只有明确 true 才通过：false（不是默认拨号器）与 null（无法确定）都保留原响铃。
        if (facts.dialerDefaultRole != true) blockers += BootstrapBlocker.DIALER_NOT_DEFAULT
        if (!facts.config.enabled) blockers += BootstrapBlocker.CONFIG_DISABLED
        if (!facts.config.valid) blockers += BootstrapBlocker.CONFIG_INVALID
        if (!facts.config.voiceCapable) blockers += BootstrapBlocker.CONFIG_VOICE_UNSUPPORTED
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
 * HOOK 心跳存在性（生产实现读桥接 `status.hookConnected`，Task 7C 起 APP 可读）。
 *
 * status 不可达/被拒/字段为 "unknown" 时这里必须返回 [HookPresence.UNKNOWN]
 * （→ 永不自动接听），不能猜测为在线。
 */
fun interface HookPresenceProbe {
    suspend fun presence(): HookPresence
}

/**
 * Google 电话默认角色（生产实现读桥接 `status.dialerDefaultRole`）。
 *
 * 返回 null 表示无法确定（status 不可用/字段缺失）。判定只接受 true。
 */
fun interface DialerDefaultRoleProbe {
    suspend fun isDefault(): Boolean?
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

/**
 * 一组可注入的就绪探针；生产装配在 [AgentCallService] 内，测试可完全替换。
 *
 * [live] 是首选的单次 status 读取：提供时 hook/dialer/supervision 全部来自同一份真实负载
 * （生产装配用 [BridgeLiveFactsProbe]）。[hook]/[dialer]/[supervision] 是 live 失败时的
 * 后备（生产仍是同一个桥接探针，绝不是“永远 unknown”的占位实现）。
 */
class ReadinessProbes(
    val bridge: BridgeProbe,
    val hook: HookPresenceProbe,
    val dialer: DialerDefaultRoleProbe,
    val config: ConfigProbe,
    val calibration: CalibrationProbe,
    val network: NetworkProbe,
    val live: LiveFactsProbe? = null,
    val supervision: SupervisionPhaseProbe = SupervisionPhaseProbe { null },
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

    /** @return true 表示全部事实满足、可以自动接听；任何缺失都返回 false。 */
    suspend fun ensureReady(): Boolean = evaluate().ready

    /** 完整裁决（含全部阻塞原因），供 UI/诊断显示“到底缺哪一项”。 */
    suspend fun evaluate(): BootstrapDecision {
        // 首选一次真实 status 读取（生产 BridgeLiveFactsProbe）：三项实时事实同源，避免
        // 同一判定里的 status 读三次。读取失败时退回桥接探针（生产是同一个实现），
        // 仍然只能得到 UNKNOWN/null。
        val live = probes.live?.let { probe("live") { it.read() } }
        val hookPresence = live?.hookPresence
            ?: probe("hook") { probes.hook.presence() }
            ?: HookPresence.UNKNOWN
        val dialerDefaultRole = if (live != null) {
            live.dialerDefaultRole
        } else {
            probe("dialer") { probes.dialer.isDefault() }
        }
        val supervisionPhase = if (live != null) {
            live.supervisionPhase
        } else {
            probe("supervision") { probes.supervision.phase() }
        }
        val facts = ReadinessFacts(
            bridgeReady = probe("bridge") { probes.bridge.isReachable() } ?: false,
            hookPresence = hookPresence,
            dialerDefaultRole = dialerDefaultRole,
            config = probe("config") { probes.config.read() } ?: ConfigFacts.UNAVAILABLE,
            calibrationAllowed = probe("calibration") { probes.calibration.canAutoAnswer() } ?: false,
            networkReachable = probe("network") { probes.network.isOnline() } ?: false,
            supervisionPhase = supervisionPhase,
        )
        val decision = BootstrapPolicy.evaluate(facts).copy(
            hookPresence = hookPresence,
            dialerDefaultRole = dialerDefaultRole,
            supervisionPhase = supervisionPhase,
            statusAvailable = live?.statusAvailable ?: false,
        )
        for (blocker in decision.blockers) logger("bootstrap.not_ready code=" + blocker.code)
        return decision
    }

    private suspend fun <T> probe(label: String, block: suspend () -> T): T? = try {
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
 * 生产 status 探针：一次 `BridgeClient.status()` 派生 [LiveFacts]，并同时实现三个
 * 单事实探针（live 读取失败时的后备，仍然是同一份真实负载，不是占位值）。
 *
 * [BridgeRead.Unavailable] 一律表达为 [LiveFacts.UNAVAILABLE]：status 被拒（旧桥接/角色表
 * 不匹配）与桥接不可达都不能变成“在线”或“默认拨号器”。RPC 机器码由 BridgeClient 原样
 * 返回并记日志，本类不做二次猜测。
 */
class BridgeLiveFactsProbe(
    private val readStatus: suspend () -> BridgeRead<BridgeStatusFacts>,
) : LiveFactsProbe, HookPresenceProbe, DialerDefaultRoleProbe, SupervisionPhaseProbe {

    override suspend fun read(): LiveFacts = when (val result = readStatus()) {
        is BridgeRead.Available -> LiveFacts(
            hookPresence = HookPresence.fromStatusField(result.value.hookConnected),
            dialerDefaultRole = result.value.dialerDefaultRole,
            supervisionPhase = result.value.supervisionPhase,
            supervisionBlocked = result.value.supervisionBlocked,
            statusAvailable = true,
        )
        is BridgeRead.Unavailable -> LiveFacts.UNAVAILABLE
    }

    override suspend fun presence(): HookPresence = read().hookPresence

    override suspend fun isDefault(): Boolean? = read().dialerDefaultRole

    override suspend fun phase(): String? = read().supervisionPhase
}

/**
 * 生产校准探针：真实 diagnose 的 [EnvironmentKey] + 本机人工证据 + core [CalibrationPolicy]
 * + **桥接校准授权**（Task 7B3）。
 *
 * 为什么多一个 [bridgeCalibrationVerified] 输入：桥接的 `CalibrationStore` 才是
 * `prepareAnswer` 授权的唯一权威（`AutoAnswerAuthorization`），而它目前**没有任何
 * 可读/可写通道**。APK 本机勾选五项人工测试不等于桥接已放行；若只看本机证据，服务会
 * 宣布 READY，却在真正接听时被桥接拒绝。因此生产装配显式传入 `{ false }`，就绪保持
 * NOT_READY（`calibration_not_verified`），直到桥接提供一条可验证的校准读写通道。
 *
 * 这不是“永久禁用”：未来通道落地时，把 [bridgeCalibrationVerified] 换成真实读取即可，
 * 其余判定（环境绑定、API 37、五项证据、时间戳）完全复用 [CalibrationPolicy]。
 */
class BridgeCalibrationProbe(
    private val environment: suspend () -> EnvironmentKey?,
    private val evidenceFor: (EnvironmentKey) -> CalibrationEvidence?,
    private val bridgeCalibrationVerified: suspend () -> Boolean,
) : CalibrationProbe {

    override suspend fun canAutoAnswer(): Boolean {
        val current = environment() ?: return false
        if (!bridgeCalibrationVerified()) return false
        val evidence = evidenceFor(current) ?: return false
        return CalibrationPolicy.canAutoAnswer(current, evidence)
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
            voiceCapable = parsed.hasCompleteVoiceEndpoints(),
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

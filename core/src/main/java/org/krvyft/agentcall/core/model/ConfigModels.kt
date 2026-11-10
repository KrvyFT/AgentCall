package org.krvyft.agentcall.core.model

/**
 * 自动接管名单模式。默认 [BLACKLIST]；[COMBINED] 表示命中黑名单或位于白名单外。
 */
enum class RuleMode {
    BLACKLIST,
    WHITELIST,
    COMBINED,
}

/**
 * 三项独立远程 API 的类别：语音识别、对话、语音合成。
 */
enum class EndpointKind {
    ASR,
    LLM,
    TTS,
}

/**
 * 远程 HTTP 端点配置。公开配置只保存地址与模型名，绝不携带密钥原值。
 *
 * @property baseUrl HTTPS Base URL。
 * @property model 供应商模型名。
 */
data class ApiEndpoint(
    val baseUrl: String,
    val model: String,
)

/**
 * 某项密钥是否已配置的公开状态。密钥原值只存在于特权配置存储。
 */
enum class SecretState {
    MISSING,
    CONFIGURED,
}

/**
 * 密钥更新意图。公开配置、日志与 IPC 响应都不得回显 [Replace.value]。
 */
sealed interface SecretUpdate {
    /** 保留现有密钥不变。 */
    data object Keep : SecretUpdate

    /** 用新值替换现有密钥。 */
    data class Replace(val value: String) : SecretUpdate

    /** 清除现有密钥。 */
    data object Clear : SecretUpdate
}

/**
 * AgentCall 的公开配置。总开关默认关闭；不含任何密钥原值。
 *
 * 延迟与最长时长范围由后续 ConfigValidator 按 spec 校验（0–30000 ms，10000–600000 ms）。
 * [revision] 用于配置 CAS：更新的写入必须匹配读取时的 revision。
 */
data class AgentConfig(
    val enabled: Boolean = false,
    val mode: RuleMode = RuleMode.BLACKLIST,
    val blacklist: List<String> = emptyList(),
    val whitelist: List<String> = emptyList(),
    val region: String = DEFAULT_REGION,
    val handleUnknownNumbers: Boolean = false,
    val answerDelayMs: Int = DEFAULT_ANSWER_DELAY_MS,
    val maxCallDurationMs: Int = DEFAULT_MAX_CALL_DURATION_MS,
    val enabledPhoneAccounts: List<String> = emptyList(),
    val asr: ApiEndpoint? = null,
    val llm: ApiEndpoint? = null,
    val tts: ApiEndpoint? = null,
    val voice: String = "",
    val speed: Double = 1.0,
    val openingLine: String = DEFAULT_OPENING_LINE,
    val prompt: String = "",
    val revision: Long = 0L,
) {
    companion object {
        /** 号码规范化初始地区。 */
        const val DEFAULT_REGION = "CN"

        /** 接听倒计时默认 3000 ms。 */
        const val DEFAULT_ANSWER_DELAY_MS = 3_000

        /** 允许的接听延迟范围 0–30000 ms。 */
        const val MIN_ANSWER_DELAY_MS = 0
        const val MAX_ANSWER_DELAY_MS = 30_000

        /** AI 会话最长时长默认 180000 ms，允许 10000–600000 ms，从 ACTIVE 起计时。 */
        const val DEFAULT_MAX_CALL_DURATION_MS = 180_000
        const val MIN_CALL_DURATION_MS = 10_000
        const val MAX_CALL_DURATION_MS = 600_000

        /** 默认中文开场白。 */
        const val DEFAULT_OPENING_LINE = "您好，我是机主的 AI 电话助理，请问有什么事？"
    }
}

/**
 * 配置更新补丁。字段为 null 表示保持原值；禁用某项 API 使用空的 [ApiEndpoint]。
 * 密钥只通过 [secrets] 传递，且不会出现在任何公开响应中。
 */
data class ConfigPatch(
    val enabled: Boolean? = null,
    val mode: RuleMode? = null,
    val blacklist: List<String>? = null,
    val whitelist: List<String>? = null,
    val region: String? = null,
    val handleUnknownNumbers: Boolean? = null,
    val answerDelayMs: Int? = null,
    val maxCallDurationMs: Int? = null,
    val enabledPhoneAccounts: List<String>? = null,
    val asr: ApiEndpoint? = null,
    val llm: ApiEndpoint? = null,
    val tts: ApiEndpoint? = null,
    val voice: String? = null,
    val speed: Double? = null,
    val openingLine: String? = null,
    val prompt: String? = null,
    val secrets: Map<EndpointKind, SecretUpdate> = emptyMap(),
)

/**
 * 可安全返回给 UI/CLI 的配置视图：公开配置加各项密钥的已配置状态，绝不含密钥原值。
 */
data class RedactedConfig(
    val config: AgentConfig,
    val secrets: Map<EndpointKind, SecretState>,
)

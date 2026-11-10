package org.krvyft.agentcall.core.rules

import org.krvyft.agentcall.core.model.AgentConfig

/**
 * 校验问题。字段与值都是公开配置内容，不含密钥。
 */
data class ConfigIssue(
    val code: ConfigIssueCode,
    val field: String,
    val value: String,
)

/**
 * 校验失败的原因，按 spec 可机器识别。
 */
enum class ConfigIssueCode {
    /** 接听延迟不在 0–30000 ms。 */
    ANSWER_DELAY_OUT_OF_RANGE,

    /** 最长 AI 通话时长不在 10000–600000 ms。 */
    MAX_CALL_DURATION_OUT_OF_RANGE,

    /** 号码规范化地区不是受支持的 ISO 地区码。 */
    REGION_UNSUPPORTED,

    /** 名单项无法按地区规范化为完整号码。 */
    RULE_NUMBER_UNPARSEABLE,

    /** 同一号码规范化后同时出现在黑名单与白名单。 */
    RULE_NUMBER_OVERLAP,
}

/**
 * 配置校验结果。[isValid] 为 false 时调用方必须拒绝启用该配置（spec §7“导入冲突阻止启用”）。
 */
data class ConfigValidation(val issues: List<ConfigIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

/**
 * 按 spec §7/§8 校验配置：延迟与时长范围、地区、名单可规范化与跨名单冲突。
 *
 * 空白名单/空黑名单都是合法配置：空白名单在保存时由 UI 醒目提示（任务 8），
 * 空黑名单按 spec 语义就是不接管，不构成校验失败。
 */
object ConfigValidator {

    fun validate(config: AgentConfig): ConfigValidation {
        val issues = mutableListOf<ConfigIssue>()

        if (config.answerDelayMs !in AgentConfig.MIN_ANSWER_DELAY_MS..AgentConfig.MAX_ANSWER_DELAY_MS) {
            issues += ConfigIssue(
                code = ConfigIssueCode.ANSWER_DELAY_OUT_OF_RANGE,
                field = "answerDelayMs",
                value = config.answerDelayMs.toString(),
            )
        }
        if (config.maxCallDurationMs !in AgentConfig.MIN_CALL_DURATION_MS..AgentConfig.MAX_CALL_DURATION_MS) {
            issues += ConfigIssue(
                code = ConfigIssueCode.MAX_CALL_DURATION_OUT_OF_RANGE,
                field = "maxCallDurationMs",
                value = config.maxCallDurationMs.toString(),
            )
        }

        if (!NumberNormalizer.isValidRegion(config.region)) {
            issues += ConfigIssue(ConfigIssueCode.REGION_UNSUPPORTED, "region", config.region)
            // 地区不可用时无法可靠规范化名单，只报告地区问题，避免级联噪声。
            return ConfigValidation(issues)
        }

        val blacklist = normalizedOrReport("blacklist", config.blacklist, config.region, issues)
        val whitelist = normalizedOrReport("whitelist", config.whitelist, config.region, issues)

        blacklist.intersect(whitelist).forEach { number ->
            issues += ConfigIssue(ConfigIssueCode.RULE_NUMBER_OVERLAP, "blacklist,whitelist", number)
        }
        return ConfigValidation(issues)
    }

    private fun normalizedOrReport(
        field: String,
        raw: List<String>,
        region: String,
        issues: MutableList<ConfigIssue>,
    ): List<String> = raw.mapNotNull { entry ->
        val normalized = NumberNormalizer.normalize(entry, region)
        if (normalized == null) {
            issues += ConfigIssue(ConfigIssueCode.RULE_NUMBER_UNPARSEABLE, field, entry)
        }
        normalized
    }
}

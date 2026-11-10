package org.krvyft.agentcall.core.rules

import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RuleMode

/**
 * spec §7 的接管判定：判断一通电话是否应在 RINGING 阶段由 AI 自动接管。
 *
 * 判定顺序即排除顺序：总开关 → 只处理新的入站 RINGING → 紧急/会议/第二路 → 已配置的
 * PhoneAccount → 号码可识别性 → 三种名单模式。任一条不满足都保留原响铃，交人工处理。
 */
object RuleMatcher {

    fun matches(config: AgentConfig, call: CallSnapshot): Boolean {
        if (!config.enabled) return false
        if (call.direction != CallDirection.INCOMING) return false
        if (call.state != CallState.RINGING) return false
        // 紧急/紧急回拨与会议通话无条件交人工，不受 handleUnknownNumbers 影响。
        if (call.isEmergency || call.isConference) return false
        // 只接管唯一一路通话；第二路活动/保持/等待电话不抢占。0 视为快照异常，保守处理。
        if (call.activeCallCount != 1) return false
        // 双卡不切卡：只使用实际 PhoneAccount，且必须是配置里已选定（已校准）的组合。
        // 空列表表示还没有任何组合被启用/校准：默认不自动接管，而不是“全部允许”。
        val account = call.phoneAccount ?: return false
        if (account !in config.enabledPhoneAccounts) return false

        val identifiable = call.presentation == Presentation.ALLOWED && call.number != null
        val number = if (identifiable) NumberNormalizer.normalize(call.number, config.region) else null
        // 未知/隐藏/不可解析号码走独立开关，默认人工处理。
        if (number == null) return config.handleUnknownNumbers

        val blacklist = normalize(config.blacklist, config.region)
        val whitelist = normalize(config.whitelist, config.region)
        return when (config.mode) {
            // 空黑名单不接管。
            RuleMode.BLACKLIST -> number in blacklist
            // 空白名单匹配所有可识别号码。
            RuleMode.WHITELIST -> number !in whitelist
            RuleMode.COMBINED -> number in blacklist || number !in whitelist
        }
    }

    private fun normalize(numbers: List<String>, region: String): Set<String> =
        numbers.mapNotNullTo(mutableSetOf()) { NumberNormalizer.normalize(it, region) }
}

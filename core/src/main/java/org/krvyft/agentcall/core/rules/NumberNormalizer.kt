package org.krvyft.agentcall.core.rules

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.krvyft.agentcall.core.model.AgentConfig

/**
 * 完整号码规范化：唯一规则是 libphonenumber 的 E.164 输出。
 *
 * 只做完整号码精确匹配，不按后缀裁剪或模糊匹配；无法可靠解析的号码一律返回 null，
 * 由调用方按“未知号码”处理（spec §7）。
 */
object NumberNormalizer {

    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    /**
     * 按 [region] 规范化 [raw]；空串、不可解析或不是该地区有效号码时返回 null。
     *
     * 这里要求 [PhoneNumberUtil.isValidNumber]，而不是“能解析就算”：能解析但号码模式无效的输入
     * 不能参与精确匹配，否则会把残缺号码误当成命中。
     */
    fun normalize(raw: String?, region: String = AgentConfig.DEFAULT_REGION): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val normalizedRegion = region.trim().uppercase()
        if (!isValidRegion(normalizedRegion)) return null

        val parsed = try {
            util.parse(candidate, normalizedRegion)
        } catch (_: NumberParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null

        if (!util.isValidNumber(parsed)) return null
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /** [region] 是否为 libphonenumber 支持的 ISO 地区码（大写）。 */
    fun isValidRegion(region: String): Boolean =
        util.supportedRegions.contains(region.trim().uppercase())
}

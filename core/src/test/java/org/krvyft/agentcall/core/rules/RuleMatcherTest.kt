package org.krvyft.agentcall.core.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.fixtures.Fixtures
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RuleMode

/**
 * spec §7 名单与接管规则的行为测试。先于实现存在，用于记录红灯。
 */
class RuleMatcherTest {

    @Test
    fun emptyBlacklistDoesNotMatch() {
        val config = Fixtures.config(mode = RuleMode.BLACKLIST, blacklist = emptyList())

        assertFalse(RuleMatcher.matches(config, Fixtures.ringing()))
    }

    @Test
    fun blacklistHitMatchesAfterNormalisation() {
        // 名单用本地区写法，来电用 E.164；规范化后是同一个完整号码。
        val config = Fixtures.config(
            mode = RuleMode.BLACKLIST,
            blacklist = listOf(Fixtures.KNOWN_NUMBER_LOCAL),
        )

        assertTrue(RuleMatcher.matches(config, Fixtures.ringing(number = Fixtures.KNOWN_NUMBER)))
    }

    @Test
    fun emptyWhitelistMatchesKnownNumber() {
        val config = Fixtures.config(
            mode = RuleMode.WHITELIST,
            blacklist = emptyList(),
            whitelist = emptyList(),
        )

        assertTrue(RuleMatcher.matches(config, Fixtures.ringing()))
    }

    @Test
    fun whitelistMissMatchesButWhitelistHitDoesNot() {
        val miss = Fixtures.config(
            mode = RuleMode.WHITELIST,
            blacklist = emptyList(),
            whitelist = listOf("+8613900139000"),
        )
        val hit = Fixtures.config(
            mode = RuleMode.WHITELIST,
            blacklist = emptyList(),
            whitelist = listOf(Fixtures.KNOWN_NUMBER),
        )

        assertTrue(RuleMatcher.matches(miss, Fixtures.ringing()))
        assertFalse(RuleMatcher.matches(hit, Fixtures.ringing()))
    }

    @Test
    fun combinedMatchesBlacklistHitOrWhitelistMiss() {
        val blacklistHit = Fixtures.config(
            mode = RuleMode.COMBINED,
            blacklist = listOf(Fixtures.KNOWN_NUMBER),
            whitelist = listOf(Fixtures.KNOWN_NUMBER),
        )
        val whitelistMiss = Fixtures.config(
            mode = RuleMode.COMBINED,
            blacklist = listOf("+8613900139000"),
            whitelist = listOf("+8613900139000"),
        )
        val listedInWhitelist = Fixtures.config(
            mode = RuleMode.COMBINED,
            blacklist = listOf("+8613900139000"),
            whitelist = listOf(Fixtures.KNOWN_NUMBER),
        )

        assertTrue(RuleMatcher.matches(blacklistHit, Fixtures.ringing()))
        assertTrue(RuleMatcher.matches(whitelistMiss, Fixtures.ringing()))
        assertFalse(RuleMatcher.matches(listedInWhitelist, Fixtures.ringing()))
    }

    @Test
    fun unknownDefaultsToManual() {
        val config = Fixtures.config(mode = RuleMode.BLACKLIST, blacklist = listOf(Fixtures.KNOWN_NUMBER))

        // 隐藏号码（无号码、受限呈现）与不可解析号码都不按可识别号码处理。
        assertFalse(
            RuleMatcher.matches(
                config,
                Fixtures.ringing(number = null).copy(presentation = Presentation.RESTRICTED),
            ),
        )
        assertFalse(RuleMatcher.matches(config, Fixtures.ringing(number = "not-a-number")))
    }

    @Test
    fun unknownSwitchAllowsHiddenNumberRegardlessOfLists() {
        val config = Fixtures.config(handleUnknownNumbers = true)

        assertTrue(
            RuleMatcher.matches(
                config,
                Fixtures.ringing(number = null).copy(presentation = Presentation.UNKNOWN),
            ),
        )
    }

    @Test
    fun emergencyCallsAreNeverTakenOver() {
        val config = Fixtures.config(handleUnknownNumbers = true)

        assertFalse(RuleMatcher.matches(config, Fixtures.ringing().copy(isEmergency = true)))
    }

    @Test
    fun outgoingCallsAreNeverTakenOver() {
        assertFalse(
            RuleMatcher.matches(Fixtures.config(), Fixtures.ringing().copy(direction = CallDirection.OUTGOING)),
        )
    }

    @Test
    fun nonRingingCallsAreNeverTakenOver() {
        assertFalse(RuleMatcher.matches(Fixtures.config(), Fixtures.answered()))
        assertFalse(
            RuleMatcher.matches(Fixtures.config(), Fixtures.ringing().copy(state = CallState.HOLDING)),
        )
    }

    @Test
    fun conferenceCallsAreNeverTakenOver() {
        assertFalse(RuleMatcher.matches(Fixtures.config(), Fixtures.ringing().copy(isConference = true)))
    }

    @Test
    fun secondCallIsManual() {
        assertFalse(RuleMatcher.matches(Fixtures.config(), Fixtures.ringing().copy(activeCallCount = 2)))
    }

    @Test
    fun onlyConfiguredPhoneAccountsAreHandled() {
        val config = Fixtures.config()

        assertFalse(RuleMatcher.matches(config, Fixtures.ringing().copy(phoneAccount = "sim-slot-2")))
        assertFalse(RuleMatcher.matches(config, Fixtures.ringing().copy(phoneAccount = null)))
        assertFalse(RuleMatcher.matches(config.copy(enabledPhoneAccounts = emptyList()), Fixtures.ringing()))
    }

    @Test
    fun disabledConfigNeverMatches() {
        assertFalse(RuleMatcher.matches(Fixtures.config(enabled = false), Fixtures.ringing()))
    }

    @Test
    fun suffixOrFuzzyNumberIsNotAMatch() {
        val config = Fixtures.config(mode = RuleMode.BLACKLIST, blacklist = listOf(Fixtures.KNOWN_NUMBER_LOCAL))

        // 只做完整号码精确匹配：末尾差一位不算命中。
        assertFalse(RuleMatcher.matches(config, Fixtures.ringing(number = "+8613800138001")))
    }

    @Test
    fun normalizedOverlapRejectsConfig() {
        val config = Fixtures.config(
            mode = RuleMode.COMBINED,
            blacklist = listOf("+8613800138000"),
            whitelist = listOf("13800138000"),
        )

        val validation = ConfigValidator.validate(config)

        assertFalse(validation.isValid)
        assertTrue(validation.issues.any { it.code == ConfigIssueCode.RULE_NUMBER_OVERLAP })
    }

    @Test
    fun emptyWhitelistIsAcceptedByValidator() {
        val config = Fixtures.config(
            mode = RuleMode.WHITELIST,
            blacklist = emptyList(),
            whitelist = emptyList(),
        )

        // 空白名单在保存时由 UI 醒目提示（任务 8），校验层必须接受。
        assertTrue(ConfigValidator.validate(config).isValid)
    }

    @Test
    fun answerDelayRangeIsEnforced() {
        assertTrue(ConfigValidator.validate(Fixtures.config(answerDelayMs = 0)).isValid)
        assertTrue(ConfigValidator.validate(Fixtures.config(answerDelayMs = 30_000)).isValid)
        assertFalse(ConfigValidator.validate(Fixtures.config(answerDelayMs = -1)).isValid)
        assertFalse(ConfigValidator.validate(Fixtures.config(answerDelayMs = 30_001)).isValid)
    }

    @Test
    fun maxCallDurationRangeIsEnforced() {
        assertTrue(ConfigValidator.validate(Fixtures.config(maxCallDurationMs = 10_000)).isValid)
        assertTrue(ConfigValidator.validate(Fixtures.config(maxCallDurationMs = 600_000)).isValid)
        assertFalse(ConfigValidator.validate(Fixtures.config(maxCallDurationMs = 9_999)).isValid)
        assertFalse(ConfigValidator.validate(Fixtures.config(maxCallDurationMs = 600_001)).isValid)
    }

    @Test
    fun unsupportedRegionIsRejected() {
        assertFalse(ConfigValidator.validate(Fixtures.config(region = "ZZ")).isValid)
    }

    @Test
    fun unparseableListEntryIsRejected() {
        assertFalse(
            ConfigValidator.validate(Fixtures.config(blacklist = listOf("not-a-number"))).isValid,
        )
    }
}

package org.krvyft.agentcall.core.fixtures

import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RuleMode
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 任务 2 的固定测试数据：固定时钟、通话快照与配置。
 *
 * 这里的所有时间都从注入的 [FixedClock] 读取，测试不依赖系统时钟；配置默认是一份
 * “已启用、已选定实际 PhoneAccount”的可自动接管配置，需要验证关闭/排除条件时再显式覆盖。
 */
object Fixtures {

    const val PROCESS_EPOCH = "epoch-1"
    const val CALL_ID = "call-a"

    /** 与快照一致的 PhoneAccount 标识，代表已校准的 SIM/路由组合。 */
    const val HOME_ACCOUNT = "sim-slot-1"

    /** 一个可被 libphonenumber 按 CN 规范化的完整号码。 */
    const val KNOWN_NUMBER = "+8613800138000"

    /** 与 [KNOWN_NUMBER] 相同号码的本地区写法，用于验证规范化后再比较。 */
    const val KNOWN_NUMBER_LOCAL = "13800138000"

    val sessionId: SessionId = SessionId(PROCESS_EPOCH, CALL_ID)

    fun sessionId(callId: String = CALL_ID, processEpoch: String = PROCESS_EPOCH): SessionId =
        SessionId(processEpoch = processEpoch, callId = callId)

    /** 可手动推进的单调时钟；reducer 与监督逻辑只在测试里通过它取时间。 */
    class FixedClock(private var now: Long = 0L) : MonotonicClock {
        override fun nowMs(): Long = now

        fun set(ms: Long) {
            now = ms
        }

        fun advance(byMs: Long): Long {
            now += byMs
            return now
        }
    }

    /** 一条入站通话快照；安全字段（紧急/会议/活动通话数）没有默认值，必须显式给出。 */
    fun snapshot(
        id: SessionId = sessionId,
        number: String? = KNOWN_NUMBER,
        state: CallState = CallState.RINGING,
        direction: CallDirection = CallDirection.INCOMING,
        presentation: Presentation = Presentation.ALLOWED,
        phoneAccount: String? = HOME_ACCOUNT,
        isEmergency: Boolean = false,
        isConference: Boolean = false,
        activeCallCount: Int = 1,
    ): CallSnapshot = CallSnapshot(
        id = id,
        number = number,
        state = state,
        direction = direction,
        presentation = presentation,
        phoneAccount = phoneAccount,
        isEmergency = isEmergency,
        isConference = isConference,
        activeCallCount = activeCallCount,
    )

    fun ringing(id: SessionId = sessionId, number: String? = KNOWN_NUMBER): CallSnapshot =
        snapshot(id = id, number = number)

    fun answered(id: SessionId = sessionId, number: String? = KNOWN_NUMBER): CallSnapshot =
        snapshot(id = id, number = number, state = CallState.ACTIVE)

    fun disconnected(id: SessionId = sessionId, number: String? = KNOWN_NUMBER): CallSnapshot =
        snapshot(id = id, number = number, state = CallState.DISCONNECTED)

    /**
     * 默认是一份可自动接管的配置：总开关打开、黑名单命中 [KNOWN_NUMBER]、只启用 [HOME_ACCOUNT]。
     *
     * 传 [blacklist] 为空即可验证“空黑名单不接管”；验证名单外号码时把 [mode] 改成白名单/组合。
     */
    fun config(
        enabled: Boolean = true,
        mode: RuleMode = RuleMode.BLACKLIST,
        blacklist: List<String> = listOf(KNOWN_NUMBER),
        whitelist: List<String> = emptyList(),
        region: String = AgentConfig.DEFAULT_REGION,
        handleUnknownNumbers: Boolean = false,
        answerDelayMs: Int = AgentConfig.DEFAULT_ANSWER_DELAY_MS,
        maxCallDurationMs: Int = AgentConfig.DEFAULT_MAX_CALL_DURATION_MS,
        enabledPhoneAccounts: List<String> = listOf(HOME_ACCOUNT),
    ): AgentConfig = AgentConfig(
        enabled = enabled,
        mode = mode,
        blacklist = blacklist,
        whitelist = whitelist,
        region = region,
        handleUnknownNumbers = handleUnknownNumbers,
        answerDelayMs = answerDelayMs,
        maxCallDurationMs = maxCallDurationMs,
        enabledPhoneAccounts = enabledPhoneAccounts,
    )
}

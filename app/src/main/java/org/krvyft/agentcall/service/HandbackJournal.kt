package org.krvyft.agentcall.service

import org.krvyft.agentcall.core.model.SessionId

/**
 * 一次人工交还的结果，**绑定产生它的 [SessionId]**（Task 7B2 FIX ROUND 1）。
 *
 * 旧实现把结果存成全局字符串，于是 A 通话的“已交还成功”会原样显示在 B 通话的接管页上。
 * 报告只能由拥有它的会话读取；新会话到达时旧报告必须被清掉。
 */
data class HandbackReport(
    val session: SessionId,
    /** 固定机器码：[HandbackJournal.CONFIRMED]/[HandbackJournal.NOT_OWNED]/[HandbackJournal.UNCONFIRMED]。 */
    val code: String,
)

/**
 * 接管页/主页的展示裁决。只由报告所属会话 + 该会话的机器码错误派生。
 *
 * [Unconfirmed] 与 [HandedBack] 是不同的类型：撤销未确认在类型上就不可能被当成“交还成功”。
 */
sealed interface HandbackOutcome {
    /** 撤销已确认（CONFIRMED 或 NOT_OWNED）。 */
    data class HandedBack(val report: HandbackReport) : HandbackOutcome

    /** 撤销未确认：必须显示为失败，绝不能显示成功或“尚未提交接管”。 */
    data class Unconfirmed(val report: HandbackReport) : HandbackOutcome

    /** 该会话没有交还报告，但有机器码错误。 */
    data class Failed(val code: String) : HandbackOutcome

    /** 该会话还没有任何交还结果。 */
    data object Pending : HandbackOutcome
}

/**
 * 人工交还结果的会话绑定规则（纯逻辑，JVM 可测）。
 *
 * 与 [AgentCallStatus.lastHandback] 配合使用：
 * - [record] 在 [SessionObserver.onHandedBack] 里写入 (SessionId, code)；
 * - [onStateChanged] 在每次状态变化时丢弃属于别的会话的残留；
 * - [outcomeFor] 是 UI 的唯一裁决入口，绝不读取“当前会话之外”的报告。
 */
object HandbackJournal {

    const val CONFIRMED: String = "handback_confirmed"
    const val NOT_OWNED: String = "handback_not_owned"
    const val UNCONFIRMED: String = "handback_unconfirmed"

    /** 协调器的撤销裁决 → 固定机器码；撤销失败与不可达都是 [UNCONFIRMED]。 */
    fun codeFor(outcome: RevokeOutcome): String = when (outcome) {
        RevokeOutcome.CONFIRMED -> CONFIRMED
        RevokeOutcome.NOT_OWNED -> NOT_OWNED
        RevokeOutcome.REVOKE_FAILED, RevokeOutcome.UNAVAILABLE -> UNCONFIRMED
    }

    /** 记录一次交还结果；报告总是替换成当前会话的这一次结果。 */
    fun record(session: SessionId, outcome: RevokeOutcome): HandbackReport =
        HandbackReport(session = session, code = codeFor(outcome))

    /**
     * 状态变化时清理残留：报告只属于它记录的会话。
     *
     * 新会话（哪怕是 IDLE/ELIGIBLE/RINGING）的第一个状态就会清掉上一通电话的结果；
     * 同一会话后续的 HANDOFF/ENDING/ENDED 仍保留报告，供接管页显示最终结论。
     */
    fun onStateChanged(current: HandbackReport?, session: SessionId): HandbackReport? =
        current?.takeIf { it.session == session }

    /** 只返回属于 [session] 的报告；别的会话/缺少目标一律 null。 */
    fun forSession(report: HandbackReport?, session: SessionId?): HandbackReport? {
        if (session == null) return null
        return report?.takeIf { it.session == session }
    }

    /**
     * UI 展示裁决：报告只对它自己的会话生效；没有报告时才回落到该会话的机器码错误。
     */
    fun outcomeFor(status: AgentCallStatus, session: SessionId?): HandbackOutcome {
        if (session == null) return HandbackOutcome.Pending
        val report = forSession(status.lastHandback, session)
        if (report != null) {
            return when (report.code) {
                CONFIRMED, NOT_OWNED -> HandbackOutcome.HandedBack(report)
                else -> HandbackOutcome.Unconfirmed(report)
            }
        }
        val error = status.lastError ?: return HandbackOutcome.Pending
        return HandbackOutcome.Failed(error)
    }
}

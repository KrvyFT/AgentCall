package org.krvyft.agentcall.service

import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState

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
 * 一次状态变化后保留的会话残留（Task 7B2 FIX ROUND 2）。
 *
 * 报告与错误都只属于会话；新会话的第一个状态会把上一通的残留清掉。
 */
data class HandbackResidue(
    val report: HandbackReport?,
    val error: String?,
    val errorSession: SessionId?,
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

    /** 属于该会话的机器码错误（不是上一通电话的残留）。 */
    data class Failed(val code: String) : HandbackOutcome

    /** 该会话还没有任何交还结果。 */
    data object Pending : HandbackOutcome
}

/**
 * 人工交还结果的会话绑定规则（纯逻辑，JVM 可测）。
 *
 * 与 [AgentCallStatus.lastHandback]/[AgentCallStatus.lastError] 配合使用：
 * - [record] 在 [SessionObserver.onHandedBack] 里写入 (SessionId, code)；
 * - [residueOnStateChanged] 在每次状态变化时丢弃属于别的会话的残留；
 * - [outcomeFor] 是 UI 的唯一裁决入口，绝不读取“当前会话之外”的报告或错误。
 *
 * FIX ROUND 2：错误（撤销失败、音频失败、接管被拒）此前只按全局 lastError 回落，于是
 * 上一通的 handback_revoke_failed/audio_failed 会显示成下一通的“接管失败”。
 * 现在错误也带会话归属，且新会话的第一个状态即清掉残留。
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
     * 状态变化时清理报告残留：报告只属于它记录的会话。
     *
     * 新会话（哪怕是 IDLE/ELIGIBLE/RINGING）的第一个状态就会清掉上一通电话的结果；
     * 同一会话后续的 HANDOFF/ENDING/ENDED 仍保留报告，供接管页显示最终结论。
     */
    fun onStateChanged(current: HandbackReport?, session: SessionId): HandbackReport? =
        current?.takeIf { it.session == session }

    /**
     * 状态变化时的**全部**会话残留裁决（报告 + 错误）。
     *
     * 规则：
     * - 报告：只保留属于 [session] 的；
     * - 错误：只保留属于 [session] 的；服务级错误（无会话归属）保留到下一次状态变化；
     * - 新会话的第一个非 ENDED 状态（[AgentCallStatus.activeSession] != [session]）清掉
     *   上一通电话/服务级的错误残留；同一会话的 ENDED 仍保留自己的最终结论。
     */
    fun residueOnStateChanged(
        status: AgentCallStatus,
        session: SessionId,
        state: SessionState,
    ): HandbackResidue {
        val report = onStateChanged(status.lastHandback, session)
        val error = status.lastError
        val errorSession = status.lastErrorSession
        val startsNewCall = state != SessionState.ENDED && status.activeSession != session
        val keepError = when {
            error == null -> false
            // 事件流/传输诊断绝不是这通电话的交还失败：立即丢弃（任务 7B3 FIX ROUND 1）。
            isDiagnosticOnly(error) -> false
            errorSession == session -> true
            errorSession == null -> !startsNewCall
            // 属于别的会话的错误：任何状态都不得保留。
            else -> false
        }
        return HandbackResidue(
            report = report,
            error = if (keepError) error else null,
            errorSession = if (keepError) errorSession else null,
        )
    }

    /** 只返回属于 [session] 的报告；别的会话/缺少目标一律 null。 */
    fun forSession(report: HandbackReport?, session: SessionId?): HandbackReport? {
        if (session == null) return null
        return report?.takeIf { it.session == session }
    }

    /**
     * UI 展示裁决：报告只对它自己的会话生效；没有报告时才回落到**属于该会话**的机器码错误。
     *
     * 上一通电话的错误（错误归属另一个会话）与服务级错误都不显示在这里——它们不是
     * “这通电话的接管失败”。
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
        if (status.lastErrorSession != session) return HandbackOutcome.Pending
        // 轮询器/事件流的诊断码（session_event_incomplete、bridge_unavailable、…）不是接管失败：
        // 它们此前经 lastError 直接进入“交还失败”，掩盖了真实的交还结果并绕过了文案过滤。
        // 事件流诊断现在写在 AgentCallStatus.pollerDiagnostic，这里再做一次防御性隔离。
        if (isDiagnosticOnly(error)) return HandbackOutcome.Pending
        return HandbackOutcome.Failed(error)
    }

    /** 只属于事件流/轮询诊断、绝不能被当成“这通电话交还失败”的机器码。 */
    fun isDiagnosticOnly(code: String): Boolean = code in SessionEventPoller.DIAGNOSTIC_ONLY_CODES
}

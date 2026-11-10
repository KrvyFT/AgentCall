package org.krvyft.agentcall.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState

/**
 * 接听前的最后一次就绪复检（Task 7B2 FIX ROUND 1/2，spec §8）。
 *
 * 就绪循环每 [AgentCallService.READY_RECHECK_INTERVAL_MS]（15 s）复检一次，而接听倒计时只有
 * 3 s：倒计时完全可能用**过期的 READY** 到期。这里在倒计时末尾（sessionAnswerDelay - guard）
 * 用同一组**本地**探针重读事实（不发起任何付费 API 请求），未就绪就投递
 * `ReadyChanged(false)`，让 reducer 在到期 tick 之前看到最新事实。
 *
 * ## 每会话只安排一次（FIX ROUND 2）
 *
 * [onStateChanged] 在生产上**每次 reducer 变化**都会被调用，包括 SessionCoordinator 的
 * 1 Hz Tick——状态始终是 PREPARING。旧实现每次回调都 cancel + 重新安排，于是 3 s 倒计时
 * 里的复检被 tick 无限推迟到 4250/5250 ms，永远晚于到期（只有“只回调一次”的单测看不出来）。
 *
 * 现在的规则：
 * - 一个 [SessionId] 最多安排一次复检（[armedSession]）；同一会话后续的 PREPARING 回调
 *   （Tick、配置 revision 刷新）一律忽略，不 cancel、不改窗口；
 * - 窗口用**调用方传入的本会话冻结倒计时**（[SessionCoordinator.currentAnswerDelayMs]），
 *   不是最新 config revision；延迟变化不会移动已安排的窗口；
 * - 换会话/离开 PREPARING（人工接管、断开、命令已下发）立即取消未执行的复检。
 *
 * 边界：
 * - 只下发 false；true 只由就绪循环决定，本类绝不“升级”为就绪；
 * - 复检有界：探针挂起/抛异常都在 [budgetMs] 内按“未就绪”处理；
 * - 只对仍然处于 [SessionState.PREPARING] 的同一个 [SessionId] 生效。
 */
class PreAnswerRecheck(
    private val scope: CoroutineScope,
    private val isReady: suspend () -> Boolean,
    private val publish: (Boolean) -> Unit,
    private val guardMs: Long = GUARD_MS,
    private val minGuardMs: Long = MIN_GUARD_MS,
    private val minBudgetMs: Long = MIN_BUDGET_MS,
) {

    init {
        require(guardMs > 0L) { "guardMs must be positive" }
        require(minGuardMs > 0L) { "minGuardMs must be positive" }
        require(minBudgetMs > 0L) { "minBudgetMs must be positive" }
    }

    private val lock = Any()

    private var job: Job? = null

    @Volatile
    private var session: SessionId? = null

    @Volatile
    private var state: SessionState? = null

    /** 已经为哪个会话安排过复检：同一会话绝不安排第二次。 */
    private var armedSession: SessionId? = null

    /**
     * 每次状态变化都调用；只有**首次**进入 PREPARING 的会话会安排一次复检。
     *
     * SessionCoordinator 在 PREPARING 期间每秒发一条 Tick 并回调本方法，这些回调必须被忽略，
     * 否则复检永远赶不上倒计时到期。
     *
     * @param answerDelayMs 本会话冻结的接听倒计时（不是最新配置 revision）
     */
    fun onStateChanged(id: SessionId, state: SessionState, answerDelayMs: Long) {
        var armSession: SessionId? = null
        var armDelayMs = 0L
        synchronized(lock) {
            if (session != id) {
                // 换会话：上一通未执行的复检作废，新会话重新计数（尚未安排）。
                job?.cancel()
                job = null
                session = id
                armedSession = null
            }
            this.state = state
            if (state != SessionState.PREPARING) {
                // 离开 PREPARING：未执行的复检立即作废；armedSession 保留，同一会话不重排。
                job?.cancel()
                job = null
                return
            }
            if (armedSession == id) return
            armedSession = id
            armSession = id
            armDelayMs = answerDelayMs
        }
        arm(armSession ?: return, armDelayMs)
    }

    /** 取消未执行的复检（服务销毁/协调器关闭时调用）。 */
    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
            session = null
            state = null
            armedSession = null
        }
    }

    private fun arm(id: SessionId, answerDelayMs: Long) {
        val guard = minOf(guardMs, maxOf(answerDelayMs / 2L, minGuardMs))
        val delayMs = (answerDelayMs - guard).coerceAtLeast(0L)
        val budgetMs = maxOf(guard * 3L / 4L, minBudgetMs)
        synchronized(lock) {
            // 安排期间会话/状态可能已经变化（人工接管/断开）：绝不再安排过期复检。
            if (session != id || state != SessionState.PREPARING || armedSession != id) return
            job = scope.launch {
                delay(delayMs)
                if (!isCurrent(id)) return@launch
                val ready = probeReady(budgetMs)
                if (!ready && isCurrent(id)) publish(false)
            }
        }
    }

    private fun isCurrent(id: SessionId): Boolean =
        session == id && state == SessionState.PREPARING

    private suspend fun probeReady(budgetMs: Long): Boolean = try {
        withTimeoutOrNull(budgetMs) { isReady() } ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // 探针异常只能降级成“未就绪”（fail closed），绝不因为读不到事实就接听。
        false
    }

    companion object {
        /** 复检提前量：默认 750 ms，让 ReadyChanged(false) 赶在 3 s 到期 tick 之前。 */
        const val GUARD_MS: Long = 750L

        /** 极短倒计时（0–1000 ms）下的最小提前量。 */
        const val MIN_GUARD_MS: Long = 200L

        /** 复检预算下限。 */
        const val MIN_BUDGET_MS: Long = 50L
    }
}

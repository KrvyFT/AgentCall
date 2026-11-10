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
 * 接听前的最后一次就绪复检（Task 7B2 FIX ROUND 1，spec §8）。
 *
 * 就绪循环每 [AgentCallService.READY_RECHECK_INTERVAL_MS]（15 s）复检一次，而接听倒计时只有
 * 3 s：倒计时完全可能用**过期的 READY** 到期。这里在倒计时末尾（`answerDelayMs - guard`）
 * 用同一组**本地**探针重读事实（不发起任何付费 API 请求），未就绪就投递
 * `ReadyChanged(false)`，让 reducer 在到期 tick 之前看到最新事实。
 *
 * 边界：
 * - 只下发 false；true 只由就绪循环决定，本类绝不“升级”为就绪；
 * - 复检有界：探针挂起/抛异常都在 [budgetMs] 内按“未就绪”处理；
 * - 只对仍然处于 [SessionState.PREPARING] 的同一个 [SessionId] 生效；换会话/先进入
 *   ANSWER_PENDING（人工接管、断开、命令已下发）时复检立即作废。
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

    private var job: Job? = null

    @Volatile
    private var session: SessionId? = null

    @Volatile
    private var state: SessionState? = null

    /**
     * 每次状态变化都调用；只有 PREPARING 会安排复检，其他状态取消未执行的复检。
     *
     * @param answerDelayMs reducer 的接听倒计时（与 AgentConfig.answerDelayMs 同源）
     */
    fun onStateChanged(id: SessionId, state: SessionState, answerDelayMs: Long) {
        this.session = id
        this.state = state
        job?.cancel()
        job = null
        if (state != SessionState.PREPARING) return

        val guard = minOf(guardMs, maxOf(answerDelayMs / 2L, minGuardMs))
        val delayMs = (answerDelayMs - guard).coerceAtLeast(0L)
        val budgetMs = maxOf(guard * 3L / 4L, minBudgetMs)
        job = scope.launch {
            delay(delayMs)
            if (!isCurrent(id)) return@launch
            val ready = probeReady(budgetMs)
            if (!ready && isCurrent(id)) publish(false)
            job = null
        }
    }

    /** 取消未执行的复检（服务销毁/协调器关闭时调用）。 */
    fun cancel() {
        job?.cancel()
        job = null
        session = null
        state = null
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

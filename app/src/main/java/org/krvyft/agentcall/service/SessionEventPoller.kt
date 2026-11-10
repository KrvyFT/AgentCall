package org.krvyft.agentcall.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.SessionEvent
import org.krvyft.agentcall.core.model.TakeoverReason
import org.krvyft.agentcall.ipc.BridgeClient
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.EventsBatch
import org.krvyft.agentcall.ipc.SessionEventSource
import org.krvyft.agentcall.ipc.SessionWireEvent

/**
 * HOOK → APP 会话事件轮询器（Task 7B3）。
 *
 * 真实 APK 只能通过 `session.events`（Task 7C）发现新通话：SessionId 由 HOOK 随机生成，
 * APP 无法预测。本类在服务存活期间按 [pollIntervalMs]（默认 500 ms）轮询，把**真实事实**
 * 映射成 [SessionEvent] 并串行投递给 [SessionCoordinator.onEvent]。
 *
 * 安全不变量：
 * - **严格按 seq 去重**：同一批次重放/游标回退都不会把一通电话处理两次；
 * - **cursor_ahead → 游标重置为 0**（桥接重启/事件环清空），不是“暂时没有新事件”；
 * - **传输失败有界退避**（[backoffBaseMs] 倍增到 [backoffMaxMs]），机器码经 [onError]
 *   上报；绝不伪造事件、绝不把失败当成功；
 * - **可取消**：运行在调用方注入的 [CoroutineScope] 上（没有 GlobalScope），[stop] 后
 *   零轮询、零投递，且不能再次 [start]；
 * - **不编造 reducer 输入**：ReadyChanged 由服务就绪判定（ServiceBootstrap）独占；
 *   PeerLost 不是事件流的事实（7C 明确 peer loss 不单独成事件，由 status.lastFault 体现）。
 *   这里只产出事件流真正携带事实的 StateChanged 与 Takeover。
 *
 * 日志只写固定机器码，不写会话 token、号码、seq 或事件负载。
 */
class SessionEventPoller(
    private val source: SessionEventSource,
    private val publish: (SessionEvent) -> Unit,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val initialCursor: Long = 0L,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val pollTimeoutMs: Long = POLL_TIMEOUT_MS,
    private val backoffBaseMs: Long = BACKOFF_BASE_MS,
    private val backoffMaxMs: Long = BACKOFF_MAX_MS,
    private val onError: (String) -> Unit = {},
    private val logger: (String) -> Unit = {},
) {

    init {
        require(initialCursor >= 0L) { "initialCursor must not be negative" }
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(pollTimeoutMs > 0L) { "pollTimeoutMs must be positive" }
        require(backoffBaseMs > 0L) { "backoffBaseMs must be positive" }
        require(backoffMaxMs >= backoffBaseMs) { "backoffMaxMs must be >= backoffBaseMs" }
    }

    /** 下一次轮询要携带的游标（最近一次已投递/已越过的 seq）。 */
    @Volatile
    var lastSeq: Long = initialCursor
        private set

    @Volatile
    private var stopped = false

    @Volatile
    private var running = false

    private var job: Job? = null

    private val pollCounter = AtomicLong(0)
    private val failureCounter = AtomicLong(0)
    private val duplicateCounter = AtomicLong(0)
    private val incompleteCounter = AtomicLong(0)
    private val droppedCounter = AtomicLong(0)

    /** 轮询尝试次数（含超时/失败；诊断用）。 */
    val pollCount: Long get() = pollCounter.get()

    /** 连续失败次数；成功一次即归零。 */
    val failureCount: Long get() = failureCounter.get()

    /** 被 seq 去重丢弃的事件数（重放/游标回退）。 */
    val duplicateEventCount: Long get() = duplicateCounter.get()

    /** 因快照字段不全而拒绝映射的事件数（绝不补默认值）。 */
    val incompleteEventCount: Long get() = incompleteCounter.get()

    /** 桥接报告的事件环丢弃总数（单调递增；只是诊断，不是错误）。 */
    val droppedEventCount: Long get() = droppedCounter.get()

    /** 最近一次失败的机器码；成功一次后清空。 */
    @Volatile
    var lastErrorCode: String? = null
        private set

    /** 是否正在轮询（停止后恒 false）。 */
    val isRunning: Boolean get() = running && !stopped

    /** 启动唯一轮询 Job；重复调用/停止后调用都无效。 */
    @Synchronized
    fun start() {
        if (stopped || job != null) return
        running = true
        job = scope.launch(dispatcher) { runLoop() }
    }

    /**
     * 停止轮询并取消在途请求。非阻塞（可能在主线程调用）：取消立即生效，
     * 已投递的事件不会被撤回。重复调用安全。
     */
    @Synchronized
    fun stop() {
        stopped = true
        running = false
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        while (!stopped && currentCoroutineContext().isActive) {
            pollCounter.incrementAndGet()
            val result = try {
                // 独立于事件源的每轮上限：即使注入的传输自身不设超时，轮询也不能被一次
                // 挂起的请求无限期占住（生产 BridgeClient 的控制请求本就有更短的预算）。
                withTimeoutOrNull(pollTimeoutMs) { source.pollEvents(lastSeq) }
                    ?: BridgeRead.Unavailable(ERROR_POLL_TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // 传输实现抛异常与“不可用”等价：只上报机器码，绝不当成没有新事件。
                BridgeRead.Unavailable(ERROR_POLL_FAILED)
            }
            // stop() 与取消之间返回的结果绝不能再投递：服务停止后不允许任何自动接听输入。
            if (stopped || !currentCoroutineContext().isActive) return
            when (result) {
                is BridgeRead.Available -> {
                    failureCounter.set(0)
                    lastErrorCode = null
                    deliver(result.value)
                    delay(pollIntervalMs)
                }
                is BridgeRead.Unavailable -> {
                    if (result.code == ERROR_CURSOR_AHEAD) {
                        if (lastSeq != 0L) {
                            lastSeq = 0L
                            logger("session_events_cursor_reset")
                        }
                        delay(pollIntervalMs)
                    } else {
                        val failures = failureCounter.incrementAndGet()
                        lastErrorCode = result.code
                        onError(result.code)
                        logger("session_events_poll_failed code=" + result.code)
                        delay(backoffMs(failures))
                    }
                }
            }
        }
    }

    /** 投递一批事件：按 seq 升序、严格去重，然后按批次的 [EventsBatch.nextSeq] 推进游标。 */
    private fun deliver(batch: EventsBatch) {
        if (batch.dropped > droppedCounter.get()) {
            droppedCounter.set(batch.dropped)
            logger("session_events_dropped")
        }
        var latest = lastSeq
        for (event in batch.events.sortedBy { it.seq }) {
            if (event.seq <= latest) {
                duplicateCounter.incrementAndGet()
                continue
            }
            latest = event.seq
            if (SessionEventMapping.isIncompleteSnapshot(event)) {
                // HOOK 快照缺了方向/呈现/紧急/会议/通话数之一：绝不补 false/0/1。
                incompleteCounter.incrementAndGet()
                lastErrorCode = ERROR_EVENT_INCOMPLETE
                onError(ERROR_EVENT_INCOMPLETE)
                logger("session_event_incomplete")
                continue
            }
            for (mapped in SessionEventMapping.map(event)) publish(mapped)
        }
        // 批次的 nextSeq 是桥接权威游标；即使在映射阶段跳过了事件，也不能反复读同一批。
        val next = maxOf(latest, batch.nextSeq)
        lastSeq = next
    }

    /** 第 n 次连续失败后的等待：base, 2*base, 4*base ... 封顶 [backoffMaxMs]。 */
    private fun backoffMs(failures: Long): Long {
        var delayMs = backoffBaseMs
        var step = 1L
        while (step < failures && delayMs < backoffMaxMs) {
            delayMs = minOf(backoffMaxMs, delayMs * 2)
            step += 1
        }
        return delayMs
    }

    companion object {
        /** 轮询间隔：7C 建议 1 s 级；500 ms 让新响铃尽早进入协调器。 */
        const val POLL_INTERVAL_MS: Long = 500L

        /** 单次轮询上限（生产控制请求自身 3 s 内失败；这里是独立兜底）。 */
        const val POLL_TIMEOUT_MS: Long = 5_000L

        /** 退避起点与上界（有界，最坏 4 s 一次尝试）。 */
        const val BACKOFF_BASE_MS: Long = 500L
        const val BACKOFF_MAX_MS: Long = 4_000L

        /** 游标超前：与桥接的机器码同源（客户端重置为 0 再读）。 */
        const val ERROR_CURSOR_AHEAD: String = BridgeClient.ERROR_CURSOR_AHEAD

        /** 快照字段不全：拒绝映射的机器码（不是伪造的“未知通话”）。 */
        const val ERROR_EVENT_INCOMPLETE: String = "session_event_incomplete"

        /** 事件源抛异常时的机器码（本地固定码，不是桥接协议码）。 */
        const val ERROR_POLL_FAILED: String = "session_events_poll_failed"

        /** 单次轮询超时的机器码（本地固定码；该轮不投递任何事件）。 */
        const val ERROR_POLL_TIMEOUT: String = "session_events_poll_timeout"
    }
}

/**
 * 事件 → reducer 输入的纯映射（Task 7B3）。
 *
 * 只使用事件里真实存在的字段：
 * - HOOK 快照（source != SUPERVISOR）：字段齐备 → [SessionEvent.StateChanged]；带
 *   [SessionWireEvent.handbackReason] → [SessionEvent.Takeover]（人工交还的事实，优先于
 *   状态：否则“交还瞬间的 ACTIVE 快照”会被 reducer 当成可接管的通话而打开音频）；
 * - 监督端事实（source == SUPERVISOR）：`handback_*` → [SessionEvent.Takeover]；
 *   `call_disconnected` 不单独成事件——它没有方向/呈现/紧急/会议/通话数，而同一 feed 里
 *   前面的 HOOK 完整 DISCONNECTED 快照才是 StateChanged 的事实源（绝不补默认值）。
 *
 * 不产生 ReadyChanged（服务就绪判定独占）或 PeerLost（7C 明确 peer loss 不是事件）。
 */
internal object SessionEventMapping {

    /** 7C 线格式：监督端事实的 source 固定值。 */
    const val SUPERVISOR_SOURCE: String = "SUPERVISOR"

    private const val HANDBACK_OUTCOME_PREFIX: String = "handback_"

    fun map(event: SessionWireEvent): List<SessionEvent> {
        if (event.source == SUPERVISOR_SOURCE) {
            val outcome = event.outcome ?: return emptyList()
            return if (outcome.startsWith(HANDBACK_OUTCOME_PREFIX)) {
                listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN))
            } else {
                emptyList()
            }
        }
        if (event.handbackReason != null) {
            // 交还原因词汇由 HOOK 自由生成（如 user_unmuted/action_failed）；reducer 对所有
            // 原因的行为相同，映射成 UNKNOWN（保守）而不是猜一个具体枚举。
            return listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN))
        }
        val state = event.state ?: return emptyList()
        val snapshot = snapshotOf(event, state) ?: return emptyList()
        return listOf(SessionEvent.StateChanged(snapshot))
    }

    /** HOOK 快照缺少构造 [CallSnapshot] 的必需观测值（不补默认值，保留原响铃）。 */
    fun isIncompleteSnapshot(event: SessionWireEvent): Boolean =
        event.source != SUPERVISOR_SOURCE &&
            event.handbackReason == null &&
            event.state != null &&
            snapshotOf(event, event.state) == null

    private fun snapshotOf(event: SessionWireEvent, state: CallState): CallSnapshot? {
        val direction = event.direction ?: return null
        val presentation = event.presentation ?: return null
        val emergency = event.isEmergency ?: return null
        val conference = event.isConference ?: return null
        val activeCalls = event.activeCallCount ?: return null
        if (activeCalls < 1) return null
        return CallSnapshot(
            id = event.sessionId,
            number = event.number,
            state = state,
            direction = direction,
            presentation = presentation,
            phoneAccount = event.phoneAccount,
            isEmergency = emergency,
            isConference = conference,
            activeCallCount = activeCalls,
        )
    }
}

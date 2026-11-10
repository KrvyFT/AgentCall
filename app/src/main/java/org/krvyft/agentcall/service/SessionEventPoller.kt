package org.krvyft.agentcall.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.SessionEvent
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.TakeoverReason
import org.krvyft.agentcall.ipc.BridgeClient
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.EventsBatch
import org.krvyft.agentcall.ipc.SessionEpochSource
import org.krvyft.agentcall.ipc.SessionEventSource
import org.krvyft.agentcall.ipc.SessionWireEvent

/**
 * 事件游标持久化（Task 7B3 FIX ROUND 1，FIX ROUND 2 换成进程 id）。
 *
 * 服务重启后游标归零会把桥接环里**已经发生过**的旧事件（尤其是 handback/断开事实）重新
 * 投递一遍。这里保存“最近一次已投递的 seq + 当时桥接的进程 id”，重启时从它继续；如果桥接
 * 在此期间也重启了（processId 变了），轮询器仍会清空基线重新同步。
 *
 * 用**每进程随机 id**而不是 uptime：墙钟回拨会让 uptime 变负（客户端判负载非法）或变小
 * （把同一进程误判成重启 → 重放 handback）。
 */
data class SessionEventCursor(
    val seq: Long,
    /** 写入时观测到的桥接进程 id；null = 当时没有可信的代次信标。 */
    val processId: String?,
)

interface SessionEventCursorStore {
    fun read(): SessionEventCursor?

    fun write(cursor: SessionEventCursor)
}

/** 不持久化（测试/没有存储时的默认实现）：等价于旧行为。 */
object NoSessionEventCursorStore : SessionEventCursorStore {
    override fun read(): SessionEventCursor? = null

    override fun write(cursor: SessionEventCursor) = Unit
}

/**
 * 监督端自己的交还结果（7C 线格式的 `outcome`）。
 *
 * 它们不能一律当成同一种“交还”：`revoke_failed` 表示所有权可能仍在监督端手里（协调器
 * 必须走 handover RPC 重试），`restore_failed` 表示恢复人工音频失败（绝不能显示为交还
 * 成功），`confirmed`/`not_owned` 则表示监督端已经放掉了这通电话。
 */
enum class SupervisorHandback {
    CONFIRMED,
    NOT_OWNED,
    REVOKE_FAILED,
    RESTORE_FAILED,

    /** 未知的 `handback_*` 结果：保守按“需要撤销”处理。 */
    UNKNOWN,
}

/**
 * HOOK → APP 会话事件轮询器（Task 7B3）。
 *
 * 真实 APK 只能通过 `session.events`（Task 7C）发现新通话：SessionId 由 HOOK 随机生成，
 * APP 无法预测。本类在服务存活期间按 [pollIntervalMs]（默认 500 ms）轮询，把**真实事实**
 * 映射成 [SessionEvent] 并串行投递给 [SessionCoordinator.onEvent]。
 *
 * 安全不变量：
 * - **桥接进程代次感知**（FIX ROUND 1 的 CRITICAL，ROUND 2 换成每进程 id）：桥接重启后
 *   事件环与 seq 从头开始。仅靠 `cursor_ahead` 只能覆盖“旧游标 > 新 latest”；“旧游标 ≤
 *   新 latest”时客户端看到的是干净批次，前 lastSeq 条事件（通常包含新来电的 RINGING）会被
 *   当成重复静默丢弃。因此每次轮询前用 [SessionEpochSource]（status.processId）检查 id
 *   是否**变化**；变化即**先清空基线（游标归零）再读**，并把清空后的游标持久化。id 不可用
 *   时按代次未知 fail closed（不重置、不把“没变”当成事实）。客户端用 0 重读标出的
 *   [EventsBatch.reset] 走同一条清空路径。
 * - **严格按 seq 去重**：同一批次重放/游标回退都不会把一通电话处理两次（同一代次内）；
 * - **传输失败有界退避**（[backoffBaseMs] 倍增到 [backoffMaxMs]），机器码经 [onError]
 *   上报；绝不伪造事件、绝不把失败当成功；
 * - **可取消**：运行在调用方注入的 [CoroutineScope] 上（没有 GlobalScope），[stop] 后
 *   零轮询、零投递（在途批次的剩余事件也不会再投递），且不能再次 [start]；
 * - **不编造 reducer 输入**：ReadyChanged 由服务就绪判定（ServiceBootstrap）独占；
 *   PeerLost 不是事件流的事实（7C 明确 peer loss 不单独成事件，由 status.lastFault 体现）。
 *
 * 诊断与快速失败分开（FIX ROUND 1）：
 * - [onError] 只报告**传输失败**（它们让轮询停摆并进入退避）；
 * - [onDiagnostic] 报告不致命的事件级问题（不完整快照、非法事件被跳过、单批投递失败、
 *   游标清空），绝不因此停摆；
 * - 两者都只是**诊断**，不进入交还结果；恢复后通过 [onRecovered] 清除。
 *
 * 日志只写固定机器码，不写会话 token、号码、seq 或事件负载。
 */
class SessionEventPoller(
    private val source: SessionEventSource,
    private val publish: (SessionEvent) -> Unit,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialCursor: Long = 0L,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val pollTimeoutMs: Long = POLL_TIMEOUT_MS,
    private val backoffBaseMs: Long = BACKOFF_BASE_MS,
    private val backoffMaxMs: Long = BACKOFF_MAX_MS,
    private val onError: (String) -> Unit = {},
    private val logger: (String) -> Unit = {},
    /** 桥接进程代次信标（生产是 [BridgeClient]）；null = 没有信标，只能依赖 cursor_ahead。 */
    private val epochSource: SessionEpochSource? = null,
    /** 跨服务重启的游标持久化（生产是 SharedPreferences）。 */
    private val cursorStore: SessionEventCursorStore = NoSessionEventCursorStore,
    /** 事件级诊断（不致命）；与 [onError] 一样绝不进入交还结果。 */
    private val onDiagnostic: (String) -> Unit = {},
    /** 一次成功且没有任何诊断的轮询之后调用，用于清除诊断残留。 */
    private val onRecovered: () -> Unit = {},
    /**
     * 监督端交还事实（携带监督端自己的结果语义）。
     *
     * 默认实现退化成“会话绑定的 Takeover”（未接线时至少不会作用到别的会话）；生产装配把它
     * 接到协调器，让 `revoke_failed`/`restore_failed` 与 `confirmed`/`not_owned` 有不同
     * 的处理与展示。
     */
    private val onSupervisorHandback: (SessionId, SupervisorHandback) -> Unit = { id, _ ->
        publish(SessionEvent.Takeover(TakeoverReason.UNKNOWN, id))
    },
    /**
     * 桥接进程代次变化（processId 变了 = 新进程）后的本地收尾（Task 7B3 FOLLOW-UP）。
     *
     * 旧进程所属会话的 `call_disconnected` 可能随进程一起消失；轮询器只报告“代次变了”，
     * 由协调器把它变成一次**本地** SessionEnded（不挂断、不伪造 DISCONNECTED 快照），
     * 从而允许新代次的响铃被接受。协调器没有会话时自然什么都不做。
     * 每次代次变化只报告一次（id 已更新，后续轮询不再判定为重启）。
     */
    private val onBridgeRestarted: () -> Unit = {},
) {

    init {
        require(initialCursor >= 0L) { "initialCursor must not be negative" }
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(pollTimeoutMs > 0L) { "pollTimeoutMs must be positive" }
        require(backoffBaseMs > 0L) { "backoffBaseMs must be positive" }
        require(backoffMaxMs >= backoffBaseMs) { "backoffMaxMs must be >= backoffBaseMs" }
    }

    /** 启动时从持久化存储恢复的游标（null = 没有存储值，按 [initialCursor] 开始）。 */
    private val restored: SessionEventCursor? = cursorStore.read()

    /** 下一次轮询要携带的游标（最近一次已投递/已越过的 seq）。 */
    @Volatile
    var lastSeq: Long = restored?.seq ?: initialCursor
        private set

    /** 最近一次观测到的桥接进程 id（代次信标）；null = 还没有可信观测。 */
    @Volatile
    private var lastProcessId: String? = restored?.processId

    /** 从持久化存储恢复的游标（诊断用）；null = 本次是按初始游标开始的。 */
    val restoredCursor: Long? get() = restored?.seq

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
    private val resetCounter = AtomicLong(0)
    private val invalidCounter = AtomicLong(0)
    private val deliverFailureCounter = AtomicLong(0)

    /** 轮询尝试次数（含超时/失败；诊断用）。 */
    val pollCount: Long get() = pollCounter.get()

    /** 连续失败次数；成功一次即归零。 */
    val failureCount: Long get() = failureCounter.get()

    /** 被 seq 去重丢弃的事件数（同一桥接代次内的重放/游标回退）。 */
    val duplicateEventCount: Long get() = duplicateCounter.get()

    /** 因快照字段不全而拒绝映射的事件数（绝不补默认值）。 */
    val incompleteEventCount: Long get() = incompleteCounter.get()

    /** 桥接报告的事件环丢弃总数（单调递增；只是诊断，不是错误）。 */
    val droppedEventCount: Long get() = droppedCounter.get()

    /** 因桥接重启（processId 变化）或客户端标出的 reset 而清空基线的次数。 */
    val baselineResetCount: Long get() = resetCounter.get()

    /** 因结构非法而被跳过的事件数（一条坏事件不拒绝整批）。 */
    val invalidEventCount: Long get() = invalidCounter.get()

    /** 单批投递抛异常的次数（不杀死轮询循环）。 */
    val deliverFailureCount: Long get() = deliverFailureCounter.get()

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
        if (restored != null) {
            logger("session_events_cursor_restored")
        } else {
            // 没有可恢复的游标：从桥接环的最旧事件开始读。这里不静默：可能重放旧事实。
            logger("session_events_cursor_fresh")
        }
        job = scope.launch(dispatcher) { runLoop() }
    }

    /**
     * 停止轮询并取消在途请求。非阻塞（可能在主线程调用）：取消立即生效，
     * 已投递的事件不会被撤回；**本批剩余事件也不会再投递**。重复调用安全。
     */
    @Synchronized
    fun stop() {
        stopped = true
        running = false
        job?.cancel()
        job = null
    }

    /**
     * 轮询 Job 的唯一入口。
     *
     * **整轮都被守卫**（FIX ROUND 2 / N5）：此前只包住映射/投递，`cursorStore.write` 或
     * `onRecovered()` 抛异常会让 Job 静默结束；`job` 又永远不为 null，`start()` 再也拉不起来。
     * 现在任何非取消异常都上报固定机器码并继续轮询；Job 退出时清空自己，允许重新 start()。
     */
    private suspend fun runLoop() {
        val self = currentCoroutineContext()[Job]
        try {
            while (!stopped && currentCoroutineContext().isActive) {
                try {
                    iterate()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // 轮询以外的一步（游标持久化/诊断回调）失败：绝不能杀死循环。
                    reportLoopFailure()
                    delay(nextBackoff())
                }
            }
        } finally {
            clearLoopJob(self)
        }
    }

    /** 一轮：代次探测 → 事件轮询 → 投递/退避。
     *
     * 从 [runLoop] 抽出来，让“整轮”可以被同一个异常守卫包住（见上）。
     */
    private suspend fun iterate() {
        // 先确认桥接进程代次：processId 变化 = 新进程，旧游标对新事件环没有意义。
        val restarted = probeBridgeRestart()
        if (restarted == null) {
            // 信标不可用/代次未知：不能安全地把“没有事件”当成事实，按传输失败退避（fail closed）。
            if (stopped || !currentCoroutineContext().isActive) return
            delay(nextBackoff())
            return
        }
        if (restarted) {
            // 代次变化：旧进程的断开事实可能永远不会到达。先让协调器本地结束它仍拥有的
            // 旧会话（否则新来电按“只有 IDLE/ENDED 才可替换”一直被忽略），再清空基线重读。
            // 回调先于本轮投递，保证“结束旧会话”排在“新代次的 RINGING”之前。
            reportBridgeRestart()
            if (lastSeq != 0L) {
                clearBaseline("session_events_epoch_reset")
            }
        }
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
                val clean = deliverOrReport(result.value)
                if (clean && !stopped) {
                    // 一次干净的成功轮询：清除诊断残留（失败码/不完整事件都不会残留）。
                    onRecovered()
                }
                delay(pollIntervalMs)
            }
            is BridgeRead.Unavailable -> {
                // 兜底：事件源直接报 cursor_ahead（客户端已把它变成 reset 批次；
                // 这里仍按“来自另一个进程”处理，绝不进入退避）。
                if (result.code == ERROR_CURSOR_AHEAD) {
                    if (lastSeq != 0L) clearBaseline("session_events_cursor_reset")
                    delay(pollIntervalMs)
                    return
                }
                val failures = failureCounter.incrementAndGet()
                lastErrorCode = result.code
                onError(result.code)
                logger("session_events_poll_failed code=" + result.code)
                delay(backoffMs(failures))
            }
        }
    }

    /** 上报一次“轮询循环自身失败”；诊断回调再抛也不能逃出守卫。 */
    private fun reportLoopFailure() {
        runCatching { reportDiagnostic(ERROR_LOOP_FAILED) }
        logger("session_events_loop_failed")
    }

    /**
     * 轮询 Job 已结束：清空它自己（不是别人），让 `start()` 可以重新拉起。
     *
     * 与 [start]/[stop] 共用同一把锁；`job` 已被 [stop] 置空时不覆盖新 Job。
     */
    @Synchronized
    private fun clearLoopJob(loopJob: Job?) {
        if (job === loopJob) job = null
        running = false
    }

    /**
     * 探测桥接是否重启过（processId 变化）。
     *
     * @return true = 观测到重启；false = 同一进程；null = 代次未知（已经按传输失败上报）。
     */
    private suspend fun probeBridgeRestart(): Boolean? {
        val epochSource = epochSource ?: return false
        val result = try {
            withTimeoutOrNull(pollTimeoutMs) { epochSource.processId() }
                ?: BridgeRead.Unavailable(ERROR_POLL_TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            BridgeRead.Unavailable(ERROR_POLL_FAILED)
        }
        return when (result) {
            is BridgeRead.Unavailable -> {
                val failures = failureCounter.incrementAndGet()
                lastErrorCode = result.code
                onError(result.code)
                logger("session_events_epoch_failed code=" + result.code)
                null
            }
            is BridgeRead.Available -> {
                val previous = lastProcessId
                val restarted = previous != null && previous != result.value
                lastProcessId = result.value
                if (restarted) logger("session_events_epoch_restarted")
                restarted
            }
        }
    }

    /**
     * 把“桥接代次变化”这一事实交给协调器（由它本地结束仍拥有的会话）。
     *
     * 与事件投递一样，回调抛异常绝不能杀死轮询循环：记一条投递失败诊断后继续，
     * 游标重新同步仍照常进行。
     */
    private fun reportBridgeRestart() {
        try {
            onBridgeRestarted()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            deliverFailureCounter.incrementAndGet()
            reportDiagnostic(ERROR_DELIVER_FAILED)
        }
    }

    private fun nextBackoff(): Long = backoffMs(failureCounter.get().coerceAtLeast(1L))

    /** 清空本地基线：下一个请求从新进程的事件环起点开始。 */
    private fun clearBaseline(logCode: String) {
        lastSeq = 0L
        resetCounter.incrementAndGet()
        logger(logCode)
        persistCursor()
    }

    /**
     * 投递一批事件：按 seq 升序、严格去重，然后按批次的 [EventsBatch.nextSeq] 推进游标。
     *
     * @return true = 本批没有任何跳过的坏事件/投递异常（用于清除诊断残留）。
     */
    private suspend fun deliverOrReport(batch: EventsBatch): Boolean {
        var clean = true
        if (batch.dropped > droppedCounter.get()) {
            droppedCounter.set(batch.dropped)
            logger("session_events_dropped")
        }
        if (batch.skipped > 0) {
            invalidCounter.addAndGet(batch.skipped.toLong())
            clean = false
            reportDiagnostic(ERROR_EVENT_INVALID)
        }
        // 客户端的 cursor_ahead/reset 意味着本批来自另一个桥接进程：先清空基线再应用，
        // 否则重读回来的 seq 1..lastSeq 会被去重逻辑当成重复而丢弃。
        if (batch.reset && lastSeq != 0L) {
            clearBaseline("session_events_batch_reset")
        }
        var latest = lastSeq
        for (event in batch.events.sortedBy { it.seq }) {
            if (stopped) return clean
            currentCoroutineContext().ensureActive()
            if (event.seq <= latest) {
                duplicateCounter.incrementAndGet()
                continue
            }
            latest = event.seq
            if (SessionEventMapping.isIncompleteSnapshot(event)) {
                // HOOK 快照缺了方向/呈现/紧急/会议/通话数之一：绝不补 false/0/1。
                incompleteCounter.incrementAndGet()
                clean = false
                reportDiagnostic(ERROR_EVENT_INCOMPLETE)
                continue
            }
            try {
                val handback = SessionEventMapping.supervisorHandback(event)
                if (handback != null) {
                    onSupervisorHandback(event.sessionId, handback)
                } else {
                    for (mapped in SessionEventMapping.map(event)) publish(mapped)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // 映射/投递的 bug 绝不能静默杀死轮询循环：上报固定码、跳过本批剩余事件、
                // 游标照常推进（同一条坏事件不会每 500 ms 重试一次）。
                deliverFailureCounter.incrementAndGet()
                clean = false
                reportDiagnostic(ERROR_DELIVER_FAILED)
                break
            }
        }
        // 批次的 nextSeq 是桥接权威游标；即使在映射阶段跳过了事件，也不能反复读同一批。
        lastSeq = maxOf(latest, batch.nextSeq)
        persistCursor()
        return clean
    }

    private fun reportDiagnostic(code: String) {
        lastErrorCode = code
        onDiagnostic(code)
        logger("session_events_diagnostic code=" + code)
    }

    private fun persistCursor() {
        cursorStore.write(SessionEventCursor(seq = lastSeq, processId = lastProcessId))
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

        /** 结构非法的事件被跳过（一条坏事件不拒绝整批）。 */
        const val ERROR_EVENT_INVALID: String = "session_event_invalid"

        /** 单批投递/映射抛异常（已跳过该批剩余事件并继续轮询）。 */
        const val ERROR_DELIVER_FAILED: String = "session_events_deliver_failed"

        /** 事件源抛异常时的机器码（本地固定码，不是桥接协议码）。 */
        const val ERROR_POLL_FAILED: String = "session_events_poll_failed"

        /** 单次轮询超时的机器码（本地固定码；该轮不投递任何事件）。 */
        const val ERROR_POLL_TIMEOUT: String = "session_events_poll_timeout"

        /**
         * 轮询循环自身的一步抛异常（游标持久化/诊断回调等，不是传输失败）。
         *
         * 该轮可能已经投递了事件；上报这条诊断后循环继续，绝不静默结束。
         */
        const val ERROR_LOOP_FAILED: String = "session_events_loop_failed"

        /**
         * 只属于事件流/诊断、**绝不**能成为“这通电话交还失败”的机器码。
         *
         * [HandbackJournal.outcomeFor] 用它与交还结果隔离：轮询器的传输码不是接管失败。
         */
        val DIAGNOSTIC_ONLY_CODES: Set<String> = setOf(
            ERROR_EVENT_INCOMPLETE,
            ERROR_EVENT_INVALID,
            ERROR_DELIVER_FAILED,
            ERROR_POLL_FAILED,
            ERROR_POLL_TIMEOUT,
            ERROR_LOOP_FAILED,
            ERROR_CURSOR_AHEAD,
            BridgeClient.ERROR_BRIDGE_UNAVAILABLE,
            BridgeClient.ERROR_EVENTS_PAYLOAD,
            BridgeClient.ERROR_STATUS_PAYLOAD,
        )
    }
}

/**
 * 事件 → reducer 输入的纯映射（Task 7B3）。
 *
 * 只使用事件里真实存在的字段：
 * - HOOK 快照（source != SUPERVISOR）：字段齐备 → [SessionEvent.StateChanged]；带
 *   [SessionWireEvent.handbackReason] → [SessionEvent.Takeover]（人工交还的事实，优先于
 *   状态：否则“交还瞬间的 ACTIVE 快照”会被 reducer 当成可接管的通话而打开音频）；
 * - 监督端事实（source == SUPERVISOR）：
 *   * `call_disconnected` → [SessionEvent.SessionEnded]（通话已经不存在；它没有方向/呈现/
 *     紧急/会议/通话数，绝不补默认值，也绝不下发挂断）；
 *   * `handback_*` → 由 [supervisorHandback] 给出监督端的结果语义，轮询器经
 *     `onSupervisorHandback` 交给协调器（兜底仍是会话绑定的 Takeover）。
 *
 * **会话绑定**：所有交还/结束事实都携带事件的 [SessionWireEvent.sessionId]，协调器与
 * reducer 只对匹配的会话生效——上一通电话的 handback 绝不能撤销当前通话。
 *
 * 不产生 ReadyChanged（服务就绪判定独占）或 PeerLost（7C 明确 peer loss 不是事件）。
 */
internal object SessionEventMapping {

    /** 7C 线格式：监督端事实的 source 固定值。 */
    const val SUPERVISOR_SOURCE: String = "SUPERVISOR"

    /** 7C 线格式：监督端已确认的通话断开。 */
    const val OUTCOME_CALL_DISCONNECTED: String = "call_disconnected"

    private const val HANDBACK_OUTCOME_PREFIX: String = "handback_"

    fun map(event: SessionWireEvent): List<SessionEvent> {
        if (event.source == SUPERVISOR_SOURCE) {
            val outcome = event.outcome
            if (outcome == OUTCOME_CALL_DISCONNECTED) {
                // 通话已不在：结束会话（不发挂断、不伪造快照字段）。
                return listOf(SessionEvent.SessionEnded(event.sessionId))
            }
            return if (supervisorHandback(event) != null) {
                // 兜底：未接线 onSupervisorHandback 时仍按会话绑定的交还处理。
                listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN, event.sessionId))
            } else {
                emptyList()
            }
        }
        if (event.handbackReason != null) {
            // 交还原因词汇由 HOOK 自由生成（如 user_unmuted/action_failed）；reducer 对所有
            // 原因的行为相同，映射成 UNKNOWN（保守）而不是猜一个具体枚举。
            return listOf(SessionEvent.Takeover(TakeoverReason.UNKNOWN, event.sessionId))
        }
        val state = event.state ?: return emptyList()
        val snapshot = snapshotOf(event, state) ?: return emptyList()
        return listOf(SessionEvent.StateChanged(snapshot))
    }

    /**
     * 监督端交还事实 → 监督端自己的结果语义；null 表示这不是一条 `handback_*` 事实。
     *
     * 不能把 `revoke_failed`（所有权可能仍在监督端）与 `not_owned`（本来就不持有）当成
     * 同一件事：前者必须走 handover RPC 重试，`restore_failed` 则绝不能显示为交还成功。
     */
    fun supervisorHandback(event: SessionWireEvent): SupervisorHandback? {
        if (event.source != SUPERVISOR_SOURCE) return null
        val outcome = event.outcome ?: return null
        if (!outcome.startsWith(HANDBACK_OUTCOME_PREFIX)) return null
        return when (outcome) {
            "handback_confirmed" -> SupervisorHandback.CONFIRMED
            "handback_not_owned" -> SupervisorHandback.NOT_OWNED
            "handback_revoke_failed" -> SupervisorHandback.REVOKE_FAILED
            "handback_restore_failed" -> SupervisorHandback.RESTORE_FAILED
            else -> SupervisorHandback.UNKNOWN
        }
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

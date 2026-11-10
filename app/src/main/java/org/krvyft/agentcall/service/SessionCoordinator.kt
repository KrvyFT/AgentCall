package org.krvyft.agentcall.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.SessionEffect
import org.krvyft.agentcall.core.model.SessionEvent
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.model.SessionState
import org.krvyft.agentcall.core.model.TakeoverReason
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.ConversationPort
import org.krvyft.agentcall.core.port.MonotonicClock
import org.krvyft.agentcall.core.session.LeaseRegistry
import org.krvyft.agentcall.core.session.SessionReducer
import org.krvyft.agentcall.ipc.AudioLaneException

/**
 * 人工交还（撤销 AI 所有权）的裁决。只有 [CONFIRMED] 与 [NOT_OWNED] 允许恢复声音；
 * [REVOKE_FAILED]/[UNAVAILABLE] 都必须保持当前状态并如实上报（不得显示交还成功）。
 */
enum class RevokeOutcome {
    CONFIRMED,
    NOT_OWNED,
    REVOKE_FAILED,
    UNAVAILABLE,
}

/**
 * 与监督端串行撤销 AI 所有权的端口。
 *
 * 7B2 的生产实现：BridgeClient.handover(id) 映射为 [CONFIRMED]/[NOT_OWNED]/
 * [REVOKE_FAILED]/[RESTORE_FAILED]/[UNAVAILABLE]。默认实现 fail closed（UNAVAILABLE）。
 */
fun interface OwnershipRevoker {
    suspend fun revoke(id: SessionId): RevokeOutcome
}

/**
 * 会话存活期间保持 APP 心跳的端口。
 *
 * 7B2 的生产实现：BridgeClient.heartbeatWhileLive(id)。协调器把它作为每通电话的子 Job
 * 启动、随会话 Job 一起取消；默认空实现只服务不关心心跳的测试装配。
 */
fun interface HeartbeatPump {
    suspend fun run(id: SessionId)
}

/**
 * 会话事实的对外广播（UI/通知由 7B2 实现）。
 *
 * 只广播机器可读事实：状态、交还结果、通知原因与机器码；不含号码、密钥或会话原值。
 * 观察者不得在回调里阻塞或反向调用协调器（它运行在协调器的串行消费者上）。
 */
open class SessionObserver {
    open fun onStateChanged(id: SessionId, state: SessionState) {}

    open fun onHandedBack(id: SessionId, outcome: RevokeOutcome) {}

    open fun onNotify(id: SessionId, reason: String) {}

    open fun onDiagnostic(code: String, id: SessionId?) {}
}

/**
 * APK 侧会话协调器（Task 7B1）：[SessionEvent] 的唯一串行消费者，拥有 [SessionReducer]，
 * 按顺序执行它输出的 [SessionEffect]。
 *
 * 强制顺序与安全边界：
 * - **单写者 + 有界队列**：[onEvent] 只做非阻塞投递；一个消费者协程按序调用 reducer，
 *   任何 reducer 状态变化都只发生在这里（用户/UI/网络线程从不直接改状态）。
 * - **每通电话一个可取消子 Job**：ticker、心跳与远程会话都是 [CallRuntime.callJob] 的子 Job；
 *   换会话/断开/关闭时取消，取消不会泄漏到全局（没有 GlobalScope；[scope] 由本类持有并
 *   在 [close] 取消）。
 * - **交还顺序**：StopConversation（取消 AI 会话）→ RevokeLease（与监督端串行撤销所有权，
 *   若还有在途 session.request 必须先等它结束，避免 handover 抢在授权之前）→ 只有在撤销
 *   确认后才 RestoreMicrophone（停止本地音频并广播交还）。撤销失败时不恢复声音、不广播成功。
 * - **迟到结果**：每个会话有递增的 generation；命令结果以 (SessionId, generation) 投递，
 *   与当前会话或 reducer 状态不符的结果只记诊断，绝不打开音频、启动会话或下发命令。
 * - **超时只终止仍归 AI 的通话**：挂断命令还要求本地租约仍有效；人工接管后的会话没有租约，
 *   即使超过 180 s 上限也不会被协调器挂断。
 * - **READY 才会接听**：READY 只由外部 [SessionEvent.ReadyChanged] 表达；未 READY 时 reducer
 *   停在 ELIGIBLE，协调器不会发出任何接听/会话请求。
 * - **隔离不在 APK 侧执行**：[SessionEffect.IsolateMicrophone] 是监督端职责（session.request
 *   在 bridge 内完成隔离并读回），这里刻意不重复执行。
 */
class SessionCoordinator(
    config: AgentConfig,
    private val calls: CallControl,
    private val audio: AudioBridge,
    private val conversation: ConversationPort,
    private val clock: MonotonicClock,
    private val leases: LeaseRegistry = LeaseRegistry(),
    private val revoker: OwnershipRevoker = OwnershipRevoker { RevokeOutcome.UNAVAILABLE },
    private val heartbeat: HeartbeatPump = HeartbeatPump { },
    private val observer: SessionObserver = SessionObserver(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val audioFormat: AudioFormatSpec = AudioFormatSpec(),
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val queueCapacity: Int = QUEUE_CAPACITY,
    private val commandSettleTimeoutMs: Long = COMMAND_SETTLE_TIMEOUT_MS,
) {

    init {
        require(tickIntervalMs > 0) { "tickIntervalMs must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(commandSettleTimeoutMs > 0) { "commandSettleTimeoutMs must be positive" }
    }

    /**
     * 当前生效的配置。热更新只影响下一通电话（新 reducer 用新快照），当前会话只吃
     * reducer 的“总开关关闭”规则（见 [onConfigChanged]）。
     */
    @Volatile
    private var config: AgentConfig = config

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * 安全相关输入（快照/接管/READY/命令结果/配置）的专用通道：无界、绝不丢弃。
     *
     * 这些事件每通电话只会产生寥寥几条（换会话、人工接管、一次命令结果、一次开关变化），
     * 无界不会变成内存泄漏；而丢掉一条 Takeover 意味着用户以为接管了却没人向监督端撤权，
     * 丢掉一条 DISCONNECTED 意味着状态永远停在 HANDOFF。
     */
    private val priorityInputs = Channel<Input>(Channel.UNLIMITED)

    /** 可丢弃的输入（只有 Tick）：有界，饱和时计数丢弃，绝不阻塞调用线程。 */
    private val inputs = Channel<Input>(queueCapacity)

    /**
     * close() 的收尾作用域：用户/服务线程调用 close() 时不阻塞，音频停止在注入的分派器上
     * 异步完成（[scope] 已被取消，不能再承载收尾工作）。
     */
    private val teardown = CoroutineScope(SupervisorJob() + dispatcher)

    private val generationCounter = AtomicLong(0)
    private val droppedInputs = AtomicLong(0)

    private var consumerJob: Job? = null

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    @Volatile
    private var current: CallRuntime? = null

    /** 最近一次 READY 事实。新建 reducer 时必须继承它，否则换会话后会卡在 ELIGIBLE。 */
    @Volatile
    private var ready: Boolean = false

    /** 当前会话的 reducer 状态；没有会话时为 null。 */
    val state: SessionState?
        get() = current?.reducer?.state

    /** 当前正在跟踪的会话（旧代次/旧 callId 的迟到结果对它无效）。 */
    val activeSession: SessionId?
        get() = current?.session

    /**
     * 当前会话在创建 reducer 时**冻结**的接听倒计时（毫秒）；没有会话时为 null。
     *
     * 接听前复检必须用它（而不是最新配置 revision），否则铃响期间的配置变化会把复检窗口
     * 推到真实到期之后（Task 7B2 FIX ROUND 2）。
     */
    val currentAnswerDelayMs: Long?
        get() = current?.answerDelayMs

    /**
     * 因有界队列饱和被丢弃的输入数（诊断用；不是协议错误）。
     *
     * 只有周期性的 Tick 会被丢弃（下一个 1 s 内还会再来一条）；快照/接管/READY/命令结果/
     * 配置走无界的优先通道，永不丢弃。
     */
    val droppedInputCount: Long get() = droppedInputs.get()

    /** 启动唯一消费者；重复调用无效，关闭后不再启动。 */
    @Synchronized
    fun start() {
        if (started || closed) return
        started = true
        consumerJob = scope.launch { consume() }
    }

    /** 非阻塞投递一个会话事件；只有 Tick 会在队列饱和时被丢弃并计数。 */
    fun onEvent(event: SessionEvent) {
        addInput(Input.Event(event))
    }

    /**
     * 配置热更新（总开关关闭时立即作用于进行中的通话）。
     *
     * 与 [onEvent] 一样经唯一的串行消费者生效，因此不会与正在执行的 reducer/效果竞争；
     * 它是安全相关输入，队列饱和时也不会被丢弃。关闭总开关时 reducer 会立即终止仍归 AI 的
     * 通话（挂断/撤销所有权）或交还人工，不需要重建协调器。
     */
    fun onConfigChanged(config: AgentConfig) {
        addInput(Input.ConfigChanged(config))
    }

    /**
     * 事件流里的监督端交还事实（Task 7B3 FIX ROUND 1）。
     *
     * 事实**绑定会话**：只有 [session] 仍是当前会话时才执行交还；上一通/别的 callId 的
     * handback 只记诊断，绝不动当前通话。监督端的结果语义决定处理方式：
     * `confirmed`/`not_owned` 已经在监督端放掉这通电话（不必再发 handover RPC），
     * `revoke_failed`/`unknown` 必须重试撤销，`restore_failed` 则绝不能显示成交还成功。
     */
    fun onSupervisorHandback(session: SessionId, outcome: SupervisorHandback) {
        addInput(Input.HandbackFact(session, outcome))
    }

    /**
     * 桥接进程代次变化（uptime 回退 = 新进程；Task 7B3 FOLLOW-UP 的裁定）。
     *
     * 桥接重启后，旧进程里那通电话的 `call_disconnected` 可能永远不会到达：会话会卡在
     * ACTIVE_AI/ENDING 直到 180 s 上限，而新来电又因“只有 IDLE/ENDED 才可替换”一直被忽略。
     * 代次变化是唯一可靠的“旧会话已不属于当前事实源”信号，因此把它结束为一次**本地**
     * [SessionEvent.SessionEnded]：StopConversation → RevokeLease → RestoreMicrophone →
     * ENDED，绝不发挂断、绝不向 bridge 发 handover、绝不伪造 DISCONNECTED 快照；隔离与
     * 工作线程事实仍由监督端拥有，这里不触碰。
     *
     * 这是一条按序输入：轮询器在投递新代次事件**之前**发出它，因此消费者处理它时仍拥有的
     * 会话只可能是旧代次的会话（即使旧响铃还在队列里没被处理，也排在它前面）。同一代次被
     * 重复报告时，第一次已经退役会话，后续自然空操作；没有会话/已关闭时什么都不做。
     */
    fun onBridgeRestarted() {
        addInput(Input.BridgeRestart)
    }

    /**
     * 取消消费者、当前会话子 Job 与所有后台协程；停止当前音频会话、撤销本地租约。
     * 重复调用安全，关闭后 [start]/[onEvent] 不再生效。
     */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        val runtime = current
        current = null
        scope.cancel()
        consumerJob = null
        started = false
        if (runtime == null) return
        runtime.active = false
        runtime.conversationJob?.cancel()
        runtime.callJob.cancel()
        // 本地租约是“可以命令这通电话”的凭据：关闭后绝不能被下一条命令复用。
        leases.revoke(runtime.session)
        val session = runtime.audioSession
        runtime.audioSession = null
        if (session != null) {
            // 不在调用线程上阻塞（可能是主线程）：停止音频交给收尾作用域，
            // 停完之后把收尾作用域收回（协调器已永久关闭）。
            teardown.launch {
                withContext(NonCancellable) { runCatching { session.stop() } }
                teardown.cancel()
            }
        }
        if (runtime.reducer.state != SessionState.ENDED) {
            observer.onStateChanged(runtime.session, SessionState.ENDED)
        }
    }

    // ------------------------------------------------------------------ 消费

    private sealed interface Input {
        /**
         * 安全相关输入永不被丢弃（走 [priorityInputs]）：只有周期性的 Tick 允许丢。
         */
        val safetyRelevant: Boolean

        data class Event(val event: SessionEvent) : Input {
            override val safetyRelevant: Boolean
                get() = event !is SessionEvent.Tick
        }

        data class CommandFinished(
            val session: SessionId,
            val generation: Long,
            val command: CallCommand,
            val result: CommandResult,
        ) : Input {
            /** 接听/挂断结果决定是否交还人工，丢了会让会话停在 ANSWER_PENDING。 */
            override val safetyRelevant: Boolean get() = true
        }

        /** 配置热更新（总开关关闭必须立即生效）。 */
        data class ConfigChanged(val config: AgentConfig) : Input {
            override val safetyRelevant: Boolean get() = true
        }

        /** 远程会话/API 失败（内部输入，必须终止仍归 AI 的通话）。 */
        data object ApiFailed : Input {
            override val safetyRelevant: Boolean get() = true
        }

        /**
         * 监督端自己的交还结果（事件流事实，携带会话身份与监督端的裁决）。
         *
         * 走无界优先通道：一条 `handback_revoke_failed` 丢了就等于没人向监督端撤销所有权。
         */
        data class HandbackFact(
            val session: SessionId,
            val outcome: SupervisorHandback,
        ) : Input {
            override val safetyRelevant: Boolean get() = true
        }

        /**
         * 桥接进程代次变化（Task 7B3 FOLLOW-UP）：本地结束处理时仍归协调器的旧会话。
         *
         * 走无界优先通道（丢一条会让会话卡到 180 s 上限）；与其它输入同序，因此新代次的
         * RINGING 一定排在它后面。
         */
        data object BridgeRestart : Input {
            override val safetyRelevant: Boolean get() = true
        }
    }

    private fun addInput(input: Input) {
        if (closed) return
        val target = if (input.safetyRelevant) priorityInputs else inputs
        if (!target.trySend(input).isSuccess) droppedInputs.incrementAndGet()
    }

    private suspend fun consume() {
        while (true) {
            val input = nextInput()
            currentCoroutineContext().ensureActive()
            try {
                apply(input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // 单个效果失败不能让串行消费者退出；只广播机器码。
                observer.onDiagnostic("coordinator_effect_failed", current?.session)
            }
        }
    }

    /**
     * 取下一个输入：安全通道优先（select 的声明顺序即优先级），其次才是可丢弃的 Tick 队列。
     * 关闭时由 [close] 取消 [scope]，这里不需要关闭通道。
     */
    private suspend fun nextInput(): Input = select {
        priorityInputs.onReceive { it }
        inputs.onReceive { it }
    }

    private suspend fun apply(input: Input) {
        when (input) {
            is Input.Event -> handleEvent(input.event)
            is Input.CommandFinished -> handleCommandResult(input)
            is Input.ConfigChanged -> handleConfigChanged(input.config)
            is Input.HandbackFact -> handleHandbackFact(input)
            Input.BridgeRestart -> handleBridgeRestart()
            Input.ApiFailed -> {
                val runtime = current ?: return
                val effects = runtime.reducer.onApiFailure()
                observer.onStateChanged(runtime.session, runtime.reducer.state)
                applyEffects(runtime, effects, handbackViaServer = false)
            }
        }
    }

    /**
     * 配置热更新：先更新协调器自己的快照（下一通电话与新会话用它），再让当前 reducer 处理。
     *
     * 只有“总开关关闭”立即作用于本会话：reducer 会终止仍归 AI 的通话（挂断 + 撤销所有权）
     * 或交还人工；其余字段仍只影响下一通电话。
     */
    private suspend fun handleConfigChanged(next: AgentConfig) {
        config = next
        val runtime = current ?: return
        val effects = runtime.reducer.onConfigChanged(next)
        observer.onStateChanged(runtime.session, runtime.reducer.state)
        applyEffects(runtime, effects, handbackViaServer = true)
    }

    private suspend fun handleEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.StateChanged -> handleSnapshot(event.snapshot)
            SessionEvent.Tick -> {
                val runtime = current ?: return
                reduceAndApply(runtime, event, handbackViaServer = false)
            }
            is SessionEvent.ReadyChanged -> {
                ready = event.ready
                val runtime = current ?: return
                reduceAndApply(runtime, event, handbackViaServer = true)
            }
            is SessionEvent.Takeover -> handleTakeover(event)
            is SessionEvent.SessionEnded -> handleSessionEnded(event.sessionId)
            is SessionEvent.PeerLost -> {
                val runtime = current ?: return
                reduceAndApply(runtime, event, handbackViaServer = false)
            }
        }
    }

    private suspend fun handleSnapshot(snapshot: CallSnapshot) {
        val runtime = current
        if (runtime == null) {
            if (snapshot.state == CallState.RINGING) startRuntime(snapshot)
            return
        }
        if (snapshot.id == runtime.session) {
            // DISCONNECTED 后的恢复只属于监督端；APP 侧不需要 handover RPC。
            reduceAndApply(
                runtime,
                SessionEvent.StateChanged(snapshot),
                handbackViaServer = snapshot.state != CallState.DISCONNECTED,
            )
            return
        }
        if (snapshot.state == CallState.RINGING) {
            // 新来电绝不顶掉仍是 IDLE/ENDED 以外的当前会话（FIX ROUND 1）：正在响铃/已接听/
            // 已接管的通话不会被第二路响铃退役（那会停掉音频/会话/心跳，且不发 handover）。
            // 第二路响铃继续响给用户听，APP 不为它建会话。
            if (!canReplaceCurrent(runtime)) {
                observer.onDiagnostic("incoming_call_ignored", runtime.session)
                return
            }
            // 当前会话确实已经 IDLE/ENDED：先退役（取消子 Job、关闭音频），再建新 reducer。
            retire(runtime, "session_replaced")
            startRuntime(snapshot)
            return
        }
        // 旧进程代次或别的 callId 的迟到快照：绝不产生命令、绝不作用到当前通话。
        observer.onDiagnostic("stale_snapshot_ignored", null)
    }

    /** 只有 IDLE/ENDED 的当前会话允许被新来电替换（FIX ROUND 1 的裁定）。 */
    private fun canReplaceCurrent(runtime: CallRuntime): Boolean {
        val state = runtime.reducer.state
        return state == SessionState.IDLE || state == SessionState.ENDED
    }

    /** 事件流里的交还事实：会话不匹配时绝不作用到当前通话。 */
    private suspend fun handleTakeover(event: SessionEvent.Takeover) {
        val runtime = current ?: return
        if (event.sessionId != null && event.sessionId != runtime.session) {
            observer.onDiagnostic("stale_takeover_ignored", event.sessionId)
            return
        }
        reduceAndApply(runtime, event, handbackViaServer = true)
    }

    /**
     * 监督端交还事实：先按会话绑定裁决，再按监督端结果语义决定撤销路径与可见结论。
     */
    private suspend fun handleHandbackFact(fact: Input.HandbackFact) {
        val runtime = current
        if (runtime == null || runtime.session != fact.session) {
            observer.onDiagnostic("stale_supervisor_handback_ignored", fact.session)
            return
        }
        // confirmed/not_owned/restore_failed：监督端已经执行过撤销，再发 handover 只会得到
        // NOT_OWNED；revoke_failed/unknown：所有权可能仍在监督端，必须重试撤销。
        val viaServer = fact.outcome == SupervisorHandback.REVOKE_FAILED ||
            fact.outcome == SupervisorHandback.UNKNOWN
        reduceAndApply(
            runtime,
            SessionEvent.Takeover(TakeoverReason.UNKNOWN, fact.session),
            handbackViaServer = viaServer,
        )
        // restore_failed：即使本地的撤销已确认，监督端也没能恢复人工音频——绝不能显示“已交还成功”。
        if (fact.outcome == SupervisorHandback.RESTORE_FAILED) {
            observer.onHandedBack(fact.session, RevokeOutcome.REVOKE_FAILED)
        }
    }

    /**
     * 通话已经不存在（监督端 `call_disconnected`）：结束会话但**不下发挂断**。
     *
     * 不向 bridge 发 handover：监督端自己的终止/恢复闭环负责真实路由与麦克风，这里只释放
     * 本地租约、停止本地音频并结束会话（reducer 复用既有断开路径，不伪造快照字段）。
     */
    private suspend fun handleSessionEnded(sessionId: SessionId) {
        val runtime = current
        if (runtime == null || runtime.session != sessionId) {
            observer.onDiagnostic("stale_session_ended_ignored", sessionId)
            return
        }
        reduceAndApply(runtime, SessionEvent.SessionEnded(sessionId), handbackViaServer = false)
    }

    /**
     * 桥接重启后的本地结束：对**处理时**仍拥有的会话复用 SessionEnded 语义。
     *
     * 重复/迟到的事实（会话已结束）是空操作：既不能结束之后的会话，也不能重复广播 ENDED。
     */
    private suspend fun handleBridgeRestart() {
        val runtime = current ?: return
        reduceAndApply(runtime, SessionEvent.SessionEnded(runtime.session), handbackViaServer = false)
        observer.onDiagnostic("bridge_restart_session_ended", runtime.session)
    }

    private suspend fun startRuntime(snapshot: CallSnapshot) {
        val parent = scope.coroutineContext[Job]
        val runtime = CallRuntime(
            session = snapshot.id,
            reducer = SessionReducer(snapshot.id, config),
            generation = generationCounter.incrementAndGet(),
            callJob = Job(parent),
            // reducer 冻结同一份 config：本会话的倒计时从这里起算，之后的 revision 只影响下一通。
            answerDelayMs = config.answerDelayMs.toLong(),
        )
        current = runtime
        runtime.tickerJob = scope.launch(runtime.callJob) {
            while (isActive) {
                delay(tickIntervalMs)
                addInput(Input.Event(SessionEvent.Tick))
            }
        }
        runtime.heartbeatJob = scope.launch(runtime.callJob) { heartbeat.run(snapshot.id) }
        // 新 reducer 继承当前 READY（READY 是服务级事实，不随 callId 变化）。
        runtime.reducer.reduce(SessionEvent.ReadyChanged(ready), clock.nowMs())
        observer.onStateChanged(runtime.session, runtime.reducer.state)
        reduceAndApply(runtime, SessionEvent.StateChanged(snapshot), handbackViaServer = true)
    }

    private suspend fun reduceAndApply(
        runtime: CallRuntime,
        event: SessionEvent,
        handbackViaServer: Boolean,
    ) {
        if (current !== runtime) return
        val effects = runtime.reducer.reduce(event, clock.nowMs())
        observer.onStateChanged(runtime.session, runtime.reducer.state)
        applyEffects(runtime, effects, handbackViaServer)
        if (runtime.reducer.state == SessionState.ENDED && current === runtime) {
            retire(runtime, "call_ended")
        }
    }

    private suspend fun applyEffects(
        runtime: CallRuntime,
        effects: List<SessionEffect>,
        handbackViaServer: Boolean,
    ) {
        for (effect in effects) {
            // 每个效果执行前都核对目标会话：旧会话/旧代次的效果一律不执行。
            if (!isCurrent(runtime)) return
            execute(runtime, effect, handbackViaServer)
        }
    }

    private suspend fun execute(runtime: CallRuntime, effect: SessionEffect, handbackViaServer: Boolean) {
        when (effect) {
            is SessionEffect.AcquireLease -> {
                if (!requireTarget(runtime, effect.id)) return
                if (leases.acquire(effect.id) == null) {
                    observer.onDiagnostic("lease_acquire_rejected", effect.id)
                }
            }
            is SessionEffect.RevokeLease -> {
                if (!requireTarget(runtime, effect.id)) return
                revokeOwnership(runtime, effect.id, handbackViaServer)
            }
            is SessionEffect.SendCommand -> sendCommand(runtime, effect.command)
            SessionEffect.StartConversation -> startConversation(runtime)
            SessionEffect.StopConversation -> stopConversation(runtime)
            // 麦克风隔离由监督端在 session.request 内完成并读回；APK 不重复执行。
            SessionEffect.IsolateMicrophone -> Unit
            is SessionEffect.RestoreMicrophone -> restoreSound(runtime, effect.id)
            is SessionEffect.NotifyUser -> observer.onNotify(runtime.session, effect.reason)
        }
    }

    // ------------------------------------------------------------------ 效果

    /**
     * 撤销 AI 所有权。
     *
     * [handbackViaServer] 为 true 时走 handover RPC（人工接管/用户先接听/总开关关闭）；
     * 为 false 时只撤销本地租约（故障挂断/断开路径由监督端自己的终止与恢复闭环负责）。
     */
    private suspend fun revokeOwnership(runtime: CallRuntime, id: SessionId, handbackViaServer: Boolean) {
        // 关键排序：撤销必须晚于授权。若 session.request 还在途中，先等它结束，
        // 否则监督端可能在 prepareAnswer 完成前处理 handover，让随后到达的接听“无人拥有”。
        val inFlight = runtime.commandJob
        if (inFlight != null && inFlight.isActive) {
            val settled = withTimeoutOrNull(commandSettleTimeoutMs) { inFlight.join() }
            if (settled == null && inFlight.isActive) {
                runtime.revokeOutcome = RevokeOutcome.UNAVAILABLE
                observer.onDiagnostic("handback_command_unsettled", id)
                // 撤销没能发起 = 未确认：必须以独立的交还结果到达 UI，绝不能消失或显示成功。
                observer.onHandedBack(id, RevokeOutcome.UNAVAILABLE)
                return
            }
        }
        runtime.commandJob = null

        val outcome = if (handbackViaServer) {
            try {
                revoker.revoke(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                RevokeOutcome.UNAVAILABLE
            }
        } else {
            RevokeOutcome.NOT_OWNED
        }
        runtime.revokeOutcome = outcome
        if (outcome == RevokeOutcome.CONFIRMED || outcome == RevokeOutcome.NOT_OWNED) {
            leases.revoke(id)
        } else {
            // 撤销未确认：不得恢复声音，也不得显示交还成功。但必须以**交还结果**的形式到达 UI，
            // 让接管页显示独立的“交还未确认”（只发诊断会让 Unconfirmed 在生产上不可达）。
            observer.onDiagnostic("handback_revoke_failed", id)
            observer.onHandedBack(id, outcome)
        }
    }

    /**
     * 恢复人工声音：只有撤销已确认（或本来就不拥有、且本地没有有效租约）才执行。
     * APP 侧“恢复”是停止本地音频会话并广播交还事实；真实路由/麦克风由监督端恢复。
     */
    private suspend fun restoreSound(runtime: CallRuntime, id: SessionId) {
        if (!requireTarget(runtime, id)) return
        val outcome = runtime.revokeOutcome
        val confirmed = outcome == RevokeOutcome.CONFIRMED || outcome == RevokeOutcome.NOT_OWNED
        val unowned = outcome == null && !leases.isLive(id)
        if (!confirmed && !unowned) {
            observer.onDiagnostic("handback_restore_blocked", id)
            return
        }
        val session = runtime.audioSession
        runtime.audioSession = null
        if (session != null) {
            withContext(NonCancellable) { runCatching { session.stop() } }
        }
        observer.onHandedBack(id, outcome ?: RevokeOutcome.NOT_OWNED)
    }

    /**
     * 下发一条受限命令。命令在子 Job 上执行（结果经 generation 校验投递），因此消费者
     * 不会被最长 4000 ms 的 session.request/handover 阻塞：新的来电、断开通话与人工接管
     * 仍能及时生效，旧结果也不会作用到新会话。
     */
    private fun sendCommand(runtime: CallRuntime, command: CallCommand) {
        if (!isCurrent(runtime)) return
        // 本地租约是“可以命令这通精确电话”的凭据：人工接管/挂断路径已撤销时绝不下发。
        if (!leases.isLive(runtime.session)) {
            observer.onDiagnostic("command_dropped_not_owned", runtime.session)
            return
        }
        val previous = runtime.commandJob
        if (previous != null && previous.isActive) {
            observer.onDiagnostic("command_already_in_flight", runtime.session)
            return
        }
        val session = runtime.session
        val generation = runtime.generation
        runtime.commandJob = scope.launch {
            val result = try {
                calls.send(session, command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                CommandResult.Failed("command_failed", failure.javaClass.simpleName)
            }
            addInput(Input.CommandFinished(session, generation, command, result))
        }
    }

    private suspend fun handleCommandResult(input: Input.CommandFinished) {
        val runtime = current
        if (runtime == null || runtime.session != input.session || runtime.generation != input.generation) {
            // 旧会话/旧代次的迟到结果：绝不打开音频、启动会话或下发命令。
            observer.onDiagnostic("stale_command_result_ignored", null)
            return
        }
        when (input.command) {
            CallCommand.Answer -> {
                if (runtime.reducer.state != SessionState.ANSWER_PENDING) {
                    observer.onDiagnostic("late_answer_result_ignored", input.session)
                    return
                }
                if (input.result != CommandResult.Accepted) {
                    // 接听没有被确认（含歧义失败）：不挂断可能已接起的电话，而是交还人工。
                    observer.onDiagnostic("answer_not_accepted", input.session)
                    val effects = runtime.reducer.reduce(
                        SessionEvent.Takeover(TakeoverReason.UNKNOWN),
                        clock.nowMs(),
                    )
                    observer.onStateChanged(runtime.session, runtime.reducer.state)
                    applyEffects(runtime, effects, handbackViaServer = true)
                }
            }
            CallCommand.Hangup -> {
                if (runtime.reducer.state != SessionState.ENDING) {
                    observer.onDiagnostic("late_hangup_result_ignored", input.session)
                    return
                }
                if (input.result !is CommandResult.Accepted) {
                    observer.onNotify(runtime.session, "hangup_failed")
                }
            }
        }
    }

    private suspend fun startConversation(runtime: CallRuntime) {
        if (!isCurrent(runtime)) return
        if (runtime.audioSession != null) return
        val opened = try {
            audio.open(runtime.session, audioFormat)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // 音频建立失败：先停 AI、保持隔离、开 10000 ms 人工接管窗口（reducer 负责计时）。
            val code = (failure as? AudioLaneException)?.code ?: "audio_open_failed"
            observer.onDiagnostic(code, runtime.session)
            val effects = runtime.reducer.onAudioFailure(clock.nowMs())
            observer.onStateChanged(runtime.session, runtime.reducer.state)
            applyEffects(runtime, effects, handbackViaServer = false)
            return
        }
        if (!isCurrent(runtime)) {
            withContext(NonCancellable) { runCatching { opened.stop() } }
            return
        }
        runtime.audioSession = opened
        runtime.conversationJob = scope.launch(runtime.callJob) {
            try {
                conversation.run(runtime.session, opened, config)
                // 会话正常返回意味着它已无法继续（例如端点不齐）：fail closed，终止 AI 通话。
                if (isActive) addInput(Input.ApiFailed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                addInput(Input.ApiFailed)
            }
        }
    }

    private suspend fun stopConversation(runtime: CallRuntime) {
        val job = runtime.conversationJob ?: return
        runtime.conversationJob = null
        // 等子 Job 真正结束：撤销所有权与恢复声音必须排在 AI 停止之后。
        job.cancelAndJoin()
    }

    /**
     * 退役当前会话：取消每通电话的子 Job（ticker/心跳/会话）、停止本地音频、撤销本地租约。
     *
     * 不向 bridge 发 handover：真正拥有通话的监督端按自己的会话/心跳规则撤销与恢复，
     * 这里发出的旧会话 handover 只可能是 NOT_OWNED。命令子 Job 不在这里取消——它最多受
     * 传输超时约束，结果会因 generation 不符被忽略。
     */
    private suspend fun retire(runtime: CallRuntime, reason: String) {
        if (current !== runtime) return
        current = null
        runtime.active = false
        runtime.conversationJob?.let { it.cancelAndJoin() }
        runtime.conversationJob = null
        runtime.callJob.cancel()
        val session = runtime.audioSession
        runtime.audioSession = null
        if (session != null) {
            withContext(NonCancellable) { runCatching { session.stop() } }
        }
        leases.revoke(runtime.session)
        // reduceAndApply 已经为本会话广播过 ENDED；这里只补“非 ENDED 路径”（换会话/关闭）。
        // 重复的 ENDED 会让 7B2 的通知/UI 把同一通电话结束两次。
        if (runtime.reducer.state != SessionState.ENDED) {
            observer.onStateChanged(runtime.session, SessionState.ENDED)
        }
        observer.onDiagnostic(reason, runtime.session)
    }

    private fun isCurrent(runtime: CallRuntime): Boolean = current === runtime && runtime.active

    private fun requireTarget(runtime: CallRuntime, id: SessionId): Boolean {
        if (id != runtime.session) {
            observer.onDiagnostic("effect_target_mismatch", id)
            return false
        }
        return true
    }

    private class CallRuntime(
        val session: SessionId,
        val reducer: SessionReducer,
        val generation: Long,
        val callJob: Job,
        /** 本会话 reducer 冻结的接听倒计时（与 SessionReducer 使用同一份 config）。 */
        val answerDelayMs: Long,
    ) {
        @Volatile
        var active: Boolean = true

        var tickerJob: Job? = null
        var heartbeatJob: Job? = null
        var conversationJob: Job? = null
        var commandJob: Job? = null
        var audioSession: AudioSession? = null
        var revokeOutcome: RevokeOutcome? = null
    }

    companion object {
        /** Tick 间隔：驱动倒计时、10 s 人工接管窗口与 180 s 上限；1 s 足够且开销可忽略。 */
        const val TICK_INTERVAL_MS: Long = 1_000L

        /** 有界输入队列：饱和时丢弃 Tick/事件并计数，绝不阻塞调用线程。 */
        const val QUEUE_CAPACITY: Int = 32

        /** 撤销前等待在途命令结束的上限；超时即放弃撤销（保持隔离），绝不抢在授权之前。 */
        const val COMMAND_SETTLE_TIMEOUT_MS: Long = 6_000L
    }
}

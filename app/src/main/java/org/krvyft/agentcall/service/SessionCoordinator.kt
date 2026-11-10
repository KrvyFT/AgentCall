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
    private val config: AgentConfig,
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

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inputs = Channel<Input>(queueCapacity)
    private val generationCounter = AtomicLong(0)
    private val droppedInputs = AtomicLong(0)

    private var consumerJob: Job? = null
    private var started = false

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

    /** 因有界队列饱和被丢弃的输入数（诊断用；不是协议错误）。 */
    val droppedInputCount: Long get() = droppedInputs.get()

    /** 启动唯一消费者；重复调用无效。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        consumerJob = scope.launch { consume() }
    }

    /** 非阻塞投递一个会话事件；队列饱和时丢弃并计数，绝不阻塞调用线程。 */
    fun onEvent(event: SessionEvent) {
        addInput(Input.Event(event))
    }

    /** 取消消费者、当前会话子 Job 与所有后台协程；重复调用安全。 */
    @Synchronized
    fun close() {
        inputs.close()
        scope.cancel()
        consumerJob = null
        started = false
    }

    // ------------------------------------------------------------------ 消费

    private sealed interface Input {
        data class Event(val event: SessionEvent) : Input

        data class CommandFinished(
            val session: SessionId,
            val generation: Long,
            val command: CallCommand,
            val result: CommandResult,
        ) : Input

        data object AudioFailed : Input

        data object ApiFailed : Input
    }

    private fun addInput(input: Input) {
        if (!inputs.trySend(input).isSuccess) droppedInputs.incrementAndGet()
    }

    private suspend fun consume() {
        try {
            for (input in inputs) {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun apply(input: Input) {
        when (input) {
            is Input.Event -> handleEvent(input.event)
            is Input.CommandFinished -> handleCommandResult(input)
            Input.AudioFailed -> {
                val runtime = current ?: return
                val effects = runtime.reducer.onAudioFailure(clock.nowMs())
                observer.onStateChanged(runtime.session, runtime.reducer.state)
                applyEffects(runtime, effects, handbackViaServer = false)
            }
            Input.ApiFailed -> {
                val runtime = current ?: return
                val effects = runtime.reducer.onApiFailure()
                observer.onStateChanged(runtime.session, runtime.reducer.state)
                applyEffects(runtime, effects, handbackViaServer = false)
            }
        }
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
            is SessionEvent.Takeover -> {
                val runtime = current ?: return
                reduceAndApply(runtime, event, handbackViaServer = true)
            }
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
            // 新代次/新 callId 的新来电：先退役旧会话（取消它的子 Job、关闭音频），再建新 reducer。
            retire(runtime, "session_replaced")
            startRuntime(snapshot)
            return
        }
        // 旧进程代次或别的 callId 的迟到快照：绝不产生命令、绝不作用到当前通话。
        observer.onDiagnostic("stale_snapshot_ignored", null)
    }

    private suspend fun startRuntime(snapshot: CallSnapshot) {
        val parent = scope.coroutineContext[Job]
        val runtime = CallRuntime(
            session = snapshot.id,
            reducer = SessionReducer(snapshot.id, config),
            generation = generationCounter.incrementAndGet(),
            callJob = Job(parent),
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
            // 撤销未确认：不得恢复声音，也不得显示交还成功。
            observer.onDiagnostic("handback_revoke_failed", id)
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
        observer.onStateChanged(runtime.session, SessionState.ENDED)
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

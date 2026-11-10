package org.krvyft.agentcall.bridge.audio

import kotlinx.coroutines.runBlocking
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.MonotonicClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.io.Closeable

/**
 * 音频侧的最小租约：只回答“该会话此刻是否仍被授权”，不携带撤销/命令能力。
 * 音频工作进程不能凭自己持有 PCM 就推断仍拥有通话。
 */
fun interface AudioLeaseCheck {
    fun isAuthorized(id: SessionId): Boolean
}

/**
 * 麦克风隔离端口。隔离的恢复只能由监督端在“断开确认”或“人工接管确认”之后调用；
 * 音频会话 stop 不得触碰本端口。
 */
interface MicrophoneIsolation {
    /** 隔离本机麦克风；只有读回确认成功才返回 true。 */
    suspend fun isolate(): Boolean

    /** 恢复本机麦克风；仅在通话已确认断开或人工接管后调用。 */
    suspend fun restore(): Boolean

    /** 重新读取系统隔离状态；失败/不可读返回 false（调用方按未隔离处理）。 */
    fun isIsolated(): Boolean
}

/** 音频工作进程重启预算的裁决结果。 */
sealed interface ProcessFaultDecision {
    /** 允许重启，[attempts] 是当前窗口内已使用的次数（含本次）。 */
    data class RestartAllowed(val attempts: Int) : ProcessFaultDecision

    /** 重启预算耗尽；监督端必须故障终止自己持有租约的通话。 */
    data class RestartExhausted(val attempts: Int) : ProcessFaultDecision

    /** 该角色不由本进程管理（例如监督进程自身崩溃由 service.sh 处理）。 */
    data object NotManaged : ProcessFaultDecision
}

/**
 * 麦克风隔离读回的三态结果（最终评审 I1）。
 *
 * [SupervisedAudio.verifyIsolation] 把“工作进程不可达”和“系统确认已解除隔离”压成同一个
 * false；启动恢复与 ACTIVE 轮询若据此判定，一次瞬时不可达就会被当成“隔离丢失”而永久
 * BLOCKED + 终止通话。只有 [Verified] 是系统事实：
 * - [Verified] false 才能作为“隔离确实已解除”的证据；
 * - [WorkerError] 是工作进程明确回报的错误（活着但无法给出隔离状态）；
 * - [Unverified] 是不可达/响应无法解析：没有系统事实，只能重试或保持“未验证”。
 */
sealed interface IsolationProbe {
    /** 系统读回确认；[isolated] 为 true 表示仍隔离，false 表示确定未隔离。 */
    data class Verified(val isolated: Boolean) : IsolationProbe

    /** 工作进程明确返回错误（例如无法读取系统隔离状态）。 */
    data class WorkerError(val code: String) : IsolationProbe

    /** 角色通道不可达/响应无法解析：调用方不得据此认定隔离已丢失。 */
    data class Unverified(val code: String) : IsolationProbe
}

/**
 * 监督端依赖的音频端口。生产实现是 [AudioWorker]（独立音频工作进程）或
 * [RemoteSupervisedAudio]（监督进程侧的生命周期代理）；测试用假实现替换。
 */
interface SupervisedAudio {
    /** 最后一次确认/读回的隔离状态；只用于状态快照与持久化意图，不能代替 [verifyIsolation]。 */
    val microphoneIsolated: Boolean

    suspend fun isolateMicrophone(): Boolean

    /** 重新读取系统隔离状态（不是缓存）；读不到或已解除返回 false（调用方 fail closed）。 */
    suspend fun verifyIsolation(): Boolean

    /**
     * 三态读回（评审 I1）：区分“确定已解除”与“无法确认”。
     *
     * 默认实现把 [verifyIsolation] 的布尔结果当作系统事实（进程内实现可以准确区分）；
     * 跨进程代理 [RemoteSupervisedAudio] 必须覆盖它，把不可达/非法响应归为
     * [IsolationProbe.Unverified]，绝不能冒充“隔离丢失”。
     */
    suspend fun probeIsolation(): IsolationProbe = IsolationProbe.Verified(verifyIsolation())

    /** 只有租约仍有效且隔离读回成功时才允许打开；失败必须抛 [AudioUnavailableException]。 */
    suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession

    /** 停止音频但保留隔离。幂等；绝不解除麦克风隔离。 */
    suspend fun stopKeepingIsolation()

    /** 在断开/接管确认之后恢复麦克风。仍持有活动流时返回 false。 */
    suspend fun restoreMicrophone(): Boolean

    /**
     * 取走一条待上报的音频工作进程故障（角色通道不可达/响应非法）。
     *
     * 这是监督端重启预算的生产来源：故障发生在音频工作进程，预算必须记在监督端，
     * 每条事件只上报一次。进程内实现（[AudioWorker] 自身）没有可上报的跨进程故障。
     */
    fun takeProcessFault(): String? = null
}

/**
 * 音频工作角色（独立 app_process 生命周期，spec §6.3）。
 *
 * 职责边界：
 * - 只持有 PCM 流与麦克风隔离状态，绝不下发通话命令（没有 CallControl 依赖）；
 * - 打开音频前同时核对最小租约与“系统读回的隔离状态”，缺一即拒绝；
 * - 重复 open 先停止旧会话，避免句柄/读取任务泄漏；
 * - 重启预算已移到监督端（I5），本进程不保存任何能“重启即清零”的预算；
 * - [stopKeepingIsolation] 只停流，不恢复麦克风；恢复必须走 [restoreMicrophone]。
 */
class AudioWorker(
    private val bridge: AudioBridge,
    private val isolation: MicrophoneIsolation,
    private val clock: MonotonicClock,
    private val leaseCheck: AudioLeaseCheck,
    val role: BridgeProcessRole = BridgeProcessRole.AUDIO_WORKER,
) : SupervisedAudio, Closeable {

    private val lock = Any()
    private var started = false
    private var state = AudioPathState.CLOSED
    private var activeSession: AudioSession? = null
    private var sessionId: SessionId? = null
    private var format: AudioFormatSpec? = null
    private var failure: AudioFailureCode? = null
    private var downlinkChunks = 0L
    private var uplinkWrites = 0L
    private var isolated = false

    /** 幂等的进程启动入口；[open] 也会自动调用，便于嵌入不同 app_process 入口。 */
    fun start() {
        synchronized(lock) { started = true }
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    override val microphoneIsolated: Boolean
        get() = synchronized(lock) { isolated }

    override suspend fun isolateMicrophone(): Boolean {
        val confirmed = try {
            isolation.isolate()
        } catch (t: Throwable) {
            false
        }
        synchronized(lock) { isolated = confirmed }
        return confirmed
    }

    override suspend fun verifyIsolation(): Boolean {
        val confirmed = try {
            isolation.isIsolated()
        } catch (t: Throwable) {
            false
        }
        synchronized(lock) { isolated = confirmed }
        return confirmed
    }

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        start()
        if (!leaseCheck.isAuthorized(id)) {
            throw AudioUnavailableException(
                code = "audio_lease_missing",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "no live lease for session; audio must not be opened",
            )
        }
        // I7：以系统读回为准，不能只用缓存布尔值。
        if (!verifyIsolation()) {
            throw AudioUnavailableException(
                code = "audio_not_isolated",
                failure = AudioFailureCode.OPEN_FAILED,
                message = "microphone must be isolated before opening call audio",
            )
        }

        // I3：新会话打开前先停止旧会话，不能泄漏句柄与读取任务。
        stopActiveSession()

        synchronized(lock) {
            state = AudioPathState.OPENING
            sessionId = id
            this.format = format
            failure = null
        }

        val session = try {
            bridge.open(id, format)
        } catch (error: AudioUnavailableException) {
            synchronized(lock) { state = AudioPathState.FAILED; failure = error.failure }
            throw error
        } catch (t: Throwable) {
            synchronized(lock) { state = AudioPathState.FAILED; failure = AudioFailureCode.OPEN_FAILED }
            throw AudioUnavailableException(
                code = "audio_open_failed",
                failure = AudioFailureCode.OPEN_FAILED,
                message = t.javaClass.simpleName,
                cause = t,
            )
        }

        val observed = ObservedSession(session)
        synchronized(lock) {
            activeSession = observed
            state = AudioPathState.OPEN
        }
        return observed
    }

    override suspend fun stopKeepingIsolation() {
        stopActiveSession()
        synchronized(lock) {
            if (state == AudioPathState.OPEN || state == AudioPathState.OPENING) {
                state = AudioPathState.CLOSED
            }
        }
    }

    private suspend fun stopActiveSession() {
        val session = synchronized(lock) {
            val current = activeSession
            activeSession = null
            current
        }
        try {
            session?.stop()
        } catch (t: Throwable) {
            // 停止失败也不能解除隔离或伪装成功；保留最后错误供快照诊断。
            synchronized(lock) { failure = AudioFailureCode.STOPPED }
        }
    }

    override suspend fun restoreMicrophone(): Boolean {
        if (synchronized(lock) { activeSession != null }) return false
        val confirmed = try {
            isolation.restore()
        } catch (t: Throwable) {
            false
        }
        if (confirmed) {
            synchronized(lock) { isolated = false }
        }
        return confirmed
    }

    /**
     * 当前已打开且属于 [id] 的音频会话 + 权威格式；未打开或不属于该会话返回 null。
     *
     * 音频工作进程的 PCM 服务只凭这个句柄取真实下行/上行：它绝不新开第二路音频，
     * 也不会把另一个会话的流当成本会话。
     */
    fun activeAudio(id: SessionId): PcmOpenedAudio? = synchronized(lock) {
        val session = activeSession ?: return null
        val currentFormat = format ?: return null
        if (state != AudioPathState.OPEN || sessionId != id) return null
        PcmOpenedAudio(session, currentFormat)
    }

    fun snapshot(): AudioStateSnapshot = synchronized(lock) {
        AudioStateSnapshot(
            role = role,
            state = state,
            sessionId = sessionId,
            format = format,
            microphoneIsolated = isolated,
            downlinkChunks = downlinkChunks,
            uplinkWrites = uplinkWrites,
            failure = failure,
            updatedAtMs = clock.nowMs(),
        )
    }

    override fun close() {
        // 不用 runBlocking 关闭（M8）：只收束状态，音频停止由拥有挂起上下文的调用方执行。
        synchronized(lock) { started = false }
    }

    /** 统计下行分片与上行写入的包装层；不改变 [AudioSession] 的停止/隔离语义。 */
    private inner class ObservedSession(private val delegate: AudioSession) : AudioSession {
        override val downlink: Flow<PcmChunk> = delegate.downlink.onEach {
            synchronized(lock) { downlinkChunks++ }
        }

        override suspend fun writeUplink(audio: PcmAudio) {
            delegate.writeUplink(audio)
            synchronized(lock) { uplinkWrites++ }
        }

        override suspend fun stop() {
            delegate.stop()
        }
    }
}

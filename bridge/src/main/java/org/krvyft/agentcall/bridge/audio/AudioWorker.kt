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
 * 监督端依赖的音频端口。生产实现是 [AudioWorker]，测试用假实现替换。
 */
interface SupervisedAudio {
    val microphoneIsolated: Boolean

    suspend fun isolateMicrophone(): Boolean

    /** 只有租约仍有效且已隔离时才允许打开；失败必须抛 [AudioUnavailableException]。 */
    suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession

    /** 停止音频但保留隔离。幂等；绝不解除麦克风隔离。 */
    suspend fun stopKeepingIsolation()

    /** 在断开/接管确认之后恢复麦克风。仍持有活动流时返回 false。 */
    suspend fun restoreMicrophone(): Boolean

    fun onProcessFault(code: String, nowMs: Long): ProcessFaultDecision

    fun snapshot(): AudioStateSnapshot
}

/**
 * 音频工作角色（独立 app_process 生命周期，spec §6.3）。
 *
 * 职责边界：
 * - 只持有 PCM 流与麦克风隔离状态，绝不下发通话命令（没有 CallControl 依赖）；
 * - 打开音频前同时核对最小租约与“已隔离”，缺一即拒绝，避免无租约的音频采集；
 * - 重启预算有界：窗口内超过 [maxRestarts] 次故障返回
 *   [ProcessFaultDecision.RestartExhausted]，由监督端终止通话，不能无限重启；
 * - [stopKeepingIsolation] 只停流，不恢复麦克风；恢复必须走 [restoreMicrophone]。
 */
class AudioWorker(
    private val bridge: AudioBridge,
    private val isolation: MicrophoneIsolation,
    private val clock: MonotonicClock,
    private val leaseCheck: AudioLeaseCheck,
    private val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
    private val restartWindowMs: Long = DEFAULT_RESTART_WINDOW_MS,
    val role: BridgeProcessRole = BridgeProcessRole.AUDIO_WORKER,
) : SupervisedAudio, Closeable {

    init {
        require(maxRestarts > 0) { "maxRestarts must be positive" }
        require(restartWindowMs > 0L) { "restartWindowMs must be positive" }
    }

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
    private val restartTimes = ArrayDeque<Long>()

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

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        start()
        if (!leaseCheck.isAuthorized(id)) {
            throw AudioUnavailableException(
                code = "audio_lease_missing",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "no live lease for session; audio must not be opened",
            )
        }
        if (!microphoneIsolated) {
            throw AudioUnavailableException(
                code = "audio_not_isolated",
                failure = AudioFailureCode.OPEN_FAILED,
                message = "microphone must be isolated before opening call audio",
            )
        }

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
        synchronized(lock) {
            if (state == AudioPathState.OPEN || state == AudioPathState.OPENING) {
                state = AudioPathState.CLOSED
            }
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

    override fun onProcessFault(code: String, nowMs: Long): ProcessFaultDecision = synchronized(lock) {
        val cutoff = nowMs - restartWindowMs
        while (restartTimes.isNotEmpty() && restartTimes.first() <= cutoff) {
            restartTimes.removeFirst()
        }
        failure = AudioFailureCode.PROCESS_LOST
        if (restartTimes.size >= maxRestarts) {
            ProcessFaultDecision.RestartExhausted(restartTimes.size)
        } else {
            restartTimes.addLast(nowMs)
            ProcessFaultDecision.RestartAllowed(restartTimes.size)
        }
    }

    override fun snapshot(): AudioStateSnapshot = synchronized(lock) {
        AudioStateSnapshot(
            role = role,
            state = state,
            sessionId = sessionId,
            format = format,
            microphoneIsolated = isolated,
            downlinkChunks = downlinkChunks,
            uplinkWrites = uplinkWrites,
            failure = failure,
            restartAttempts = restartTimes.size,
            updatedAtMs = clock.nowMs(),
        )
    }

    override fun close() {
        runBlocking { stopKeepingIsolation() }
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

    companion object {
        /** 默认重启预算（窗口内最多 3 次）。 */
        const val DEFAULT_MAX_RESTARTS: Int = 3

        /** 默认重启窗口 60 秒；旧故障不占用新预算。 */
        const val DEFAULT_RESTART_WINDOW_MS: Long = 60_000L
    }
}

package org.krvyft.agentcall.bridge.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import org.krvyft.agentcall.bridge.diagnostics.ReflectiveAudioApiProbe
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CapabilityStatus
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.PcmEncoding
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession

/**
 * 音频桥接的机器可读失败。[failure] 决定监督端保持隔离/终止/重启，[code] 进诊断，
 * 消息只含公开字段，不包含密钥或电话号码。
 */
class AudioUnavailableException(
    val code: String,
    val failure: AudioFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 受限通话音频框架的最小抽象层（Android 适配的唯一接缝）。
 *
 * 只暴露通话提取/注入两条通路，**没有环境麦克风方法**：适配层无法把
 * AudioRecord(MIC) 冒充成下行。所有方法允许返回 null 或抛异常，调用方必须映射为
 * 明确的不支持，而不是“静默成功”。
 */
interface CallAudioFramework {

    /** AOSP 受限方法名中实际可见的集合（来自反射探测）。 */
    fun visibleMethods(): Set<String>

    /** AOSP isPstnCallAudioInterceptable 的结果；方法不存在或调用失败返回 null。 */
    fun isInterceptable(): Boolean?

    /** getCallDownlinkExtractionAudioRecord 返回的通话提取录音；不可用返回 null。 */
    fun openCallExtractionDownlinkRecord(): Downlink?

    /** getCallUplinkInjectionAudioTrack 返回的通话注入播放器；不可用返回 null。 */
    fun openCallUplinkInjectionTrack(): Uplink?

    /** 通话下行录音句柄（只读，PCM16）。 */
    interface Downlink {
        val sampleRate: Int
        val channels: Int

        /** 返回实际读到的样本数；负值/0 表示失败或结束。 */
        fun read(samples: ShortArray, offset: Int, count: Int): Int

        fun close()
    }

    /** 通话上行注入播放器句柄（只写，PCM16）。 */
    interface Uplink {
        val sampleRate: Int
        val channels: Int

        /** 返回实际写入的样本数；负值/0 表示失败。 */
        fun write(samples: ShortArray, offset: Int, count: Int): Int

        fun close()
    }
}

/**
 * 能力分类（spec §9 五态）。这里绝不产生 [CapabilityStatus.VERIFIED]：对象创建成功只到
 * “待对端确认”，人工测试电话由 [org.krvyft.agentcall.core.policy.CalibrationPolicy] 解锁。
 */
object AudioPathPolicy {

    const val DOWNLINK_METHOD: String = ReflectiveAudioApiProbe.DOWNLINK_METHOD
    const val UPLINK_METHOD: String = ReflectiveAudioApiProbe.UPLINK_METHOD
    const val INTERCEPTABLE_METHOD: String = ReflectiveAudioApiProbe.INTERCEPTABLE_METHOD

    /**
     * @param visibleMethods 反射看到的受限方法名集合。
     * @param method 本条通路对应的方法名。
     * @param interceptable isPstnCallAudioInterceptable 的结果；null 表示无法判定。
     * @param creationSucceeded 是否已尝试创建对象并成功；null 表示尚未尝试。
     */
    fun classify(
        visibleMethods: Set<String>,
        method: String,
        interceptable: Boolean?,
        creationSucceeded: Boolean?,
    ): CapabilityStatus {
        if (method !in visibleMethods) {
            // 拦截判定明确为 false 时，方法缺失是可解释的“不支持”，而不是“未检测”。
            return if (interceptable == false) CapabilityStatus.UNSUPPORTED else CapabilityStatus.NOT_DETECTED
        }
        if (interceptable == false) return CapabilityStatus.UNSUPPORTED
        return when (creationSucceeded) {
            false -> CapabilityStatus.UNSUPPORTED
            true -> CapabilityStatus.PENDING_PEER_CONFIRMATION
            null -> CapabilityStatus.INTERFACE_VISIBLE
        }
    }

    /** 是否值得尝试打开：接口可见/待确认（以及未来的已验证）才允许，其余明确拒绝。 */
    fun canOpen(status: CapabilityStatus): Boolean =
        status == CapabilityStatus.INTERFACE_VISIBLE ||
            status == CapabilityStatus.PENDING_PEER_CONFIRMATION ||
            status == CapabilityStatus.VERIFIED
}

/**
 * 真实 AOSP 受限音频 API 的反射适配（薄层、未经真机验证）。
 *
 * 硬件/ROM 差异全部在 [CallAudioFramework] 边界内处理；失败一律返回 null，由
 * [FrameworkAudioBridge] 转成显式 UNSUPPORTED。绝不：
 * - 回退到环境麦克风；
 * - 猜测未公开的 mixer 数值或固定 Binder 编号；
 * - 关闭 SELinux / 修改 vendor 音频策略。
 */
class AndroidCallAudioFramework(
    private val audioManagerProvider: () -> AudioManager? = { null },
) : CallAudioFramework {

    private val probe = ReflectiveAudioApiProbe()

    override fun visibleMethods(): Set<String> = probe.visibleMethods()

    override fun isInterceptable(): Boolean? {
        val manager = audioManagerProvider() ?: return null
        val method = findRestrictedMethod(AudioPathPolicy.INTERCEPTABLE_METHOD, expectZeroParameters = true)
            ?: return null
        return try {
            method.invoke(manager) as? Boolean
        } catch (t: Throwable) {
            null
        }
    }

    override fun openCallExtractionDownlinkRecord(): CallAudioFramework.Downlink? {
        val manager = audioManagerProvider() ?: return null
        val method = findRestrictedMethod(AudioPathPolicy.DOWNLINK_METHOD, expectZeroParameters = false)
            ?: return null
        val record = try {
            method.invoke(manager, audioAttributes()) as? AudioRecord
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                null
            } else {
                RecordDownlink(record)
            }
        } catch (t: Throwable) {
            runCatching { record.release() }
            null
        }
    }

    override fun openCallUplinkInjectionTrack(): CallAudioFramework.Uplink? {
        val manager = audioManagerProvider() ?: return null
        val method = findRestrictedMethod(AudioPathPolicy.UPLINK_METHOD, expectZeroParameters = false)
            ?: return null
        val track = try {
            method.invoke(manager, audioAttributes()) as? AudioTrack
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.release()
                null
            } else {
                TrackUplink(track)
            }
        } catch (t: Throwable) {
            runCatching { track.release() }
            null
        }
    }

    /**
     * 只按方法名查找，并校验参数形状：0 参或 1 个 AudioAttributes。签名不匹配时返回
     * null（不支持），绝不按位置猜别的参数类型。
     */
    private fun findRestrictedMethod(name: String, expectZeroParameters: Boolean): java.lang.reflect.Method? {
        val candidates = try {
            AudioManager::class.java.declaredMethods.filter { it.name == name }
        } catch (t: Throwable) {
            return null
        }
        return candidates.firstOrNull { method ->
            when (method.parameterCount) {
                0 -> expectZeroParameters
                1 -> !expectZeroParameters && method.parameterTypes[0] == AudioAttributes::class.java
                else -> false
            }
        }?.apply {
            isAccessible = true
        }
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private class RecordDownlink(private val record: AudioRecord) : CallAudioFramework.Downlink {
        override val sampleRate: Int = record.format.sampleRate
        override val channels: Int = record.format.channelCount

        override fun read(samples: ShortArray, offset: Int, count: Int): Int =
            record.read(samples, offset, count)

        override fun close() {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private class TrackUplink(private val track: AudioTrack) : CallAudioFramework.Uplink {
        override val sampleRate: Int = track.format.sampleRate
        override val channels: Int = track.format.channelCount

        override fun write(samples: ShortArray, offset: Int, count: Int): Int =
            track.write(samples, offset, count)

        override fun close() {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}

/**
 * [AudioBridge] 的生产实现：只使用通话提取/注入通路，PCM16 单声道、显式重采样。
 *
 * 每个 [open] 都重新探测并重新创建对象；过去的校准成功不代表本次打开成功（spec §8）。
 * 任何一步失败都抛 [AudioUnavailableException]，不存在返回“无声成功会话”的路径。
 */
class FrameworkAudioBridge(
    private val framework: CallAudioFramework = AndroidCallAudioFramework(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioBridge {

    /** 只探测、不创建对象/不申请权限。 */
    fun probe(): AudioCapability {
        val visible = runCatching { framework.visibleMethods() }.getOrDefault(emptySet())
        val interceptable = runCatching { framework.isInterceptable() }.getOrNull()
        val downlink = AudioPathPolicy.classify(
            visible,
            AudioPathPolicy.DOWNLINK_METHOD,
            interceptable,
            creationSucceeded = null,
        )
        val uplink = AudioPathPolicy.classify(
            visible,
            AudioPathPolicy.UPLINK_METHOD,
            interceptable,
            creationSucceeded = null,
        )
        val interceptableStatus = when {
            AudioPathPolicy.INTERCEPTABLE_METHOD !in visible -> CapabilityStatus.NOT_DETECTED
            interceptable == false -> CapabilityStatus.UNSUPPORTED
            interceptable == true -> CapabilityStatus.INTERFACE_VISIBLE
            else -> CapabilityStatus.NOT_DETECTED
        }
        return AudioCapability(
            downlink = downlink,
            uplink = uplink,
            interceptable = interceptableStatus,
            visibleMethods = visible,
            notes = "audio:UNTESTED creation:NOT_ATTEMPTED",
        )
    }

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        requireSupportedFormat(format)
        val capability = probe()
        if (!capability.canOpen) {
            throw AudioUnavailableException(
                code = "audio_unsupported",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "call audio path unavailable: downlink=" + capability.downlink +
                    ", uplink=" + capability.uplink + ", interceptable=" + capability.interceptable,
            )
        }

        val downlink = try {
            framework.openCallExtractionDownlinkRecord()
        } catch (t: Throwable) {
            null
        } ?: throw AudioUnavailableException(
            code = "audio_downlink_unsupported",
            failure = AudioFailureCode.UNSUPPORTED,
            message = "call downlink extraction record could not be created",
        )

        val uplink = try {
            framework.openCallUplinkInjectionTrack()
        } catch (t: Throwable) {
            runCatching { downlink.close() }
            null
        } ?: run {
            downlink.close()
            throw AudioUnavailableException(
                code = "audio_uplink_unsupported",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "call uplink injection track could not be created",
            )
        }

        // 设备流也可能给出不可用的采样率/声道。必须在构造重采样器/读取循环之前
        // 明确拒绝并释放句柄，不能让 0 Hz 或 0 声道落到 Resampler/除法里。
        val invalidDeviceFormat = "device stream format invalid: downlink=" +
            downlink.sampleRate + "Hz/" + downlink.channels + "ch, uplink=" +
            uplink.sampleRate + "Hz/" + uplink.channels + "ch"
        val deviceFormatUsable = downlink.sampleRate in Resampler.MIN_RATE..Resampler.MAX_RATE &&
            downlink.channels > 0 &&
            uplink.sampleRate in Resampler.MIN_RATE..Resampler.MAX_RATE &&
            uplink.channels > 0
        if (!deviceFormatUsable) {
            runCatching { downlink.close() }
            runCatching { uplink.close() }
            throw AudioUnavailableException(
                code = "audio_device_format_invalid",
                failure = AudioFailureCode.UNSUPPORTED,
                message = invalidDeviceFormat,
            )
        }

        return FrameworkAudioSession(id, format, downlink, uplink, dispatcher)
    }

    private fun requireSupportedFormat(format: AudioFormatSpec) {
        val supported = format.encoding == PcmEncoding.PCM16 &&
            format.channels == AudioFormatSpec.INTERNAL_CHANNELS &&
            format.sampleRate in Resampler.MIN_RATE..Resampler.MAX_RATE
        if (!supported) {
            throw AudioUnavailableException(
                code = "audio_format_invalid",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "unsupported internal audio format: " + format,
            )
        }
    }
}

/** 一次真实双向音频会话：下行来自通话提取录音，上行写入通话注入播放器。 */
private class FrameworkAudioSession(
    private val id: SessionId,
    private val outputFormat: AudioFormatSpec,
    private val downlinkHandle: CallAudioFramework.Downlink,
    private val uplinkHandle: CallAudioFramework.Uplink,
    private val dispatcher: CoroutineDispatcher,
) : AudioSession {

    private val chunks = Channel<PcmChunk>(CHANNEL_CAPACITY)
    private val stopped = AtomicBoolean(false)
    private val handlesClosed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val downlinkResampler = Resampler(downlinkHandle.sampleRate, outputFormat.sampleRate)
    private val uplinkResampler = Resampler(outputFormat.sampleRate, uplinkHandle.sampleRate)
    private val uplinkChannels: Int = uplinkHandle.channels
    private val uplinkLock = Mutex()
    private val readJob: Job = scope.launch { readDownlink() }

    override val downlink: Flow<PcmChunk> = chunks.receiveAsFlow()

    private suspend fun readDownlink() {
        val channels = downlinkHandle.channels
        val buffer = ShortArray(READ_FRAMES * channels)
        try {
            while (currentCoroutineContext().isActive && !stopped.get()) {
                val read = downlinkHandle.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    fail(
                        "audio_downlink_failed",
                        AudioFailureCode.DOWNLINK_FAILED,
                        "downlink read returned " + read,
                    )
                    return
                }
                val frames = read / channels
                if (frames <= 0) continue
                val mono = if (channels == 1) {
                    buffer.copyOf(frames)
                } else {
                    ShortArray(frames) { frame ->
                        var sum = 0
                        for (channel in 0 until channels) {
                            sum += buffer[frame * channels + channel].toInt()
                        }
                        (sum / channels).toShort()
                    }
                }
                val resampled = downlinkResampler.resampleSamples(mono)
                if (resampled.isEmpty()) continue
                if (!chunks.trySend(PcmChunk(pcm16Le(resampled), outputFormat)).isSuccess) {
                    fail(
                        "audio_buffer_overflow",
                        AudioFailureCode.BUFFER_OVERFLOW,
                        "downlink queue is saturated",
                    )
                    return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            fail("audio_downlink_failed", AudioFailureCode.DOWNLINK_FAILED, t.javaClass.simpleName)
        }
    }

    private fun fail(code: String, failure: AudioFailureCode, message: String) {
        chunks.close(AudioUnavailableException(code, failure, message))
        closeHandles()
    }

    override suspend fun writeUplink(audio: PcmAudio) {
        uplinkLock.withLock {
            if (stopped.get()) {
                throw AudioUnavailableException("audio_stopped", AudioFailureCode.STOPPED, "audio session is stopped")
            }
            if (audio.format != outputFormat) {
                throw AudioUnavailableException(
                    "audio_format_invalid",
                    AudioFailureCode.UNSUPPORTED,
                    "uplink format " + audio.format + " does not match session format " + outputFormat,
                )
            }
            val samples = pcm16Samples(audio.data)
            val deviceSamples = uplinkResampler.resampleSamples(samples)
            withContext(dispatcher) {
                writeUplinkFrames(deviceSamples)
            }
        }
    }

    /**
     * 内部流固定单声道；若注入播放器是立体声，必须按帧复制到所有声道，否则
     * 直接写单声道样本会被当成交错帧播放（时长减半、声道错位）。
     */
    private fun writeUplinkFrames(monoSamples: ShortArray) {
        val channels = uplinkChannels
        val interleaved = if (channels == 1) {
            monoSamples
        } else {
            ShortArray(monoSamples.size * channels) { index ->
                monoSamples[index / channels]
            }
        }
        var offset = 0
        while (offset < interleaved.size) {
            if (stopped.get()) {
                throw AudioUnavailableException(
                    "audio_stopped",
                    AudioFailureCode.STOPPED,
                    "audio session is stopped",
                )
            }
            val written = uplinkHandle.write(interleaved, offset, interleaved.size - offset)
            if (written <= 0) {
                throw AudioUnavailableException(
                    "audio_uplink_failed",
                    AudioFailureCode.UPLINK_FAILED,
                    "uplink write returned " + written,
                )
            }
            // AudioTrack 以帧为单位写入；非整帧会破坏交错/循环边界，按失败处理。
            if (written % channels != 0) {
                throw AudioUnavailableException(
                    "audio_uplink_failed",
                    AudioFailureCode.UPLINK_FAILED,
                    "uplink write returned a partial frame: " + written,
                )
            }
            offset += written
        }
    }

    override suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        readJob.cancelAndJoin()
        chunks.close()
        closeHandles()
        scope.cancel()
    }

    private fun closeHandles() {
        if (!handlesClosed.compareAndSet(false, true)) return
        runCatching { downlinkHandle.close() }
        runCatching { uplinkHandle.close() }
    }

    private fun pcm16Samples(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0) { "PCM16 payload must have an even byte count: " + bytes.size }
        return ShortArray(bytes.size / 2) { index ->
            val low = bytes[2 * index].toInt() and 0xFF
            val high = bytes[2 * index + 1].toInt() and 0xFF
            ((high shl 8) or low).toShort()
        }
    }

    private fun pcm16Le(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val value = samples[index].toInt()
            bytes[2 * index] = (value and 0xFF).toByte()
            bytes[2 * index + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private companion object {
        /** 有界 PCM 队列：约 1.3 秒 @16 kHz，慢消费者按有界失败处理，不阻塞电话 UI。 */
        const val CHANNEL_CAPACITY: Int = 64

        /** 每次读取的帧数（mono 等价 40 ms @48 kHz）。 */
        const val READ_FRAMES: Int = 1_920
    }
}

/**
 * 麦克风隔离的 Android 实现（薄层、真机未验证）。
 *
 * 使用 AudioManager.isMicrophoneMute 读写；需要 MODIFY_AUDIO_SETTINGS。注意：
 * 系统级静音是否同时影响通话上行注入必须在目标 ROM 上实测（spec §9 明确禁止假设）。
 * 本类只报告读写结果，不据此宣称隔离已验证。
 */
class AndroidAudioManagerIsolation(
    private val audioManagerProvider: () -> AudioManager?,
) : MicrophoneIsolation {

    override suspend fun isolate(): Boolean = setMuted(true)

    override suspend fun restore(): Boolean = setMuted(false)

    override fun isIsolated(): Boolean = try {
        audioManagerProvider()?.isMicrophoneMute == true
    } catch (t: Throwable) {
        false
    }

    private fun setMuted(muted: Boolean): Boolean {
        val manager = audioManagerProvider() ?: return false
        return try {
            manager.isMicrophoneMute = muted
            manager.isMicrophoneMute == muted
        } catch (t: Throwable) {
            false
        }
    }
}

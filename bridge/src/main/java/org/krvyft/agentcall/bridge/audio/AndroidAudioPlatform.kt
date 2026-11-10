package org.krvyft.agentcall.bridge.audio

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 有界重试的惰性系统服务缓存（评审 I4）。
 *
 * app_process 没有 Application/Context，解析一次要走 ActivityThread.systemMain()；隔离
 * 读回是 500 ms 热路径，不能每次都重新初始化框架。这里把“解析”与“缓存”分开：
 * - 成功一次即复用；
 * - 失败按 [maxAttempts] 有界重试（工作进程/框架可能晚就绪），预算用尽后 fail closed；
 * - 解析抛异常按失败计，绝不缓存半个对象；
 * - [reset] 只用于测试或明确的框架重建信号。
 */
internal class BoundedServiceCache<T : Any>(
    private val maxAttempts: Int,
    private val resolve: () -> T?,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    private var value: T? = null
    private var attempts = 0

    @Synchronized
    fun get(): T? {
        value?.let { return it }
        if (attempts >= maxAttempts) return null
        attempts++
        val resolved = try {
            resolve()
        } catch (t: Throwable) {
            null
        }
        if (resolved != null) value = resolved
        return resolved
    }

    @Synchronized
    fun attempts(): Int = attempts

    @Synchronized
    fun reset() {
        value = null
        attempts = 0
    }
}

/**
 * app_process 容器里的 Android 音频平台事实（真机层、未验证）。
 *
 * 独立 app_process 没有 Application/Context；与 RootFramework 一样走
 * ActivityThread.systemMain() + getSystemContext()，但只解析一次并复用（评审 I4：
 * 隔离读回每 500 ms 一次，不能每次重新初始化框架）。解析失败按有界预算重试，
 * 之后返回 null，由适配层明确报告“不支持”，绝不猜其他 Binder 或降级用环境麦克风。
 */
object AndroidAudioPlatform {

    /** 解析失败的重试上限；预算用尽后 fail closed，直到进程重启。 */
    internal const val MAX_RESOLVE_ATTEMPTS: Int = 3

    private val audioManagerCache = BoundedServiceCache<AudioManager>(MAX_RESOLVE_ATTEMPTS) {
        resolveAudioManager()
    }

    fun audioManager(): AudioManager? = audioManagerCache.get()

    /** 与 [audioManager] 同源；provider 只做延迟取值，不再每次重新解析。 */
    fun audioManagerProvider(): () -> AudioManager? = { audioManager() }

    /** 已尝试解析的次数（诊断/测试用；不暴露任何系统服务内容）。 */
    internal fun resolutionAttempts(): Int = audioManagerCache.attempts()

    private fun resolveAudioManager(): AudioManager? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
        systemContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (t: Throwable) {
        null
    }

    /**
     * 跨进程可比较的单调时钟：SystemClock.elapsedRealtime 在整机内一致，重启后归零。
     * 监督端与音频工作端的心跳/超时必须用同一时钟域。
     */
    fun clock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
}

package org.krvyft.agentcall.bridge.audio

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * app_process 容器里的 Android 音频平台事实（真机层、未验证）。
 *
 * 独立 app_process 没有 Application/Context；与 RootFramework 一样走
 * ActivityThread.systemMain() + getSystemContext()。解析失败一律返回 null，由适配层
 * 明确报告“不支持”，绝不猜其他 Binder 或降级用环境麦克风。
 */
object AndroidAudioPlatform {

    fun audioManager(): AudioManager? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
        systemContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (t: Throwable) {
        null
    }

    /** 每次调用都重新解析：系统服务不可用时让上层得到“不支持”，而不是缓存一个空实现。 */
    fun audioManagerProvider(): () -> AudioManager? = { audioManager() }

    /**
     * 跨进程可比较的单调时钟：SystemClock.elapsedRealtime 在整机内一致，重启后归零。
     * 监督端与音频工作端的心跳/超时必须用同一时钟域。
     */
    fun clock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
}

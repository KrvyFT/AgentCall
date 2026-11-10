package org.krvyft.agentcall.bridge.audio

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 当前开机的稳定标识（/proc/sys/kernel/random/boot_id，内核提供的 UUID）。
 *
 * 监督端用它精确区分“同一开机内进程重启”（可能仍有活着的 AI 通话，绝不能解除隔离）
 * 与“设备重启过”（旧所有权一定已失效）。读不到返回 null，启动恢复退回
 * SystemClock.elapsedRealtime 的保守近似。
 */
object AndroidBootId {

    @Volatile
    private var cached: String? = null

    fun current(): String? {
        val known = cached
        if (known != null) return known
        val value = try {
            val file = File("/proc/sys/kernel/random/boot_id")
            if (!file.isFile) return null
            file.readText(StandardCharsets.UTF_8).trim().ifEmpty { null }
        } catch (t: Throwable) {
            null
        }
        if (value != null) cached = value
        return value
    }
}

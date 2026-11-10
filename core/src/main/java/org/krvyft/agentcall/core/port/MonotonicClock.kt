package org.krvyft.agentcall.core.port

/**
 * 单调时钟端口。所有超时都必须从注入的实例计算，测试使用虚拟时钟。
 */
fun interface MonotonicClock {
    /** 返回单调递增的毫秒数；不保证与墙上时间相关。 */
    fun nowMs(): Long
}

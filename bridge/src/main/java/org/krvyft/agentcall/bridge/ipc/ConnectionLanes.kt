package org.krvyft.agentcall.bridge.ipc

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 控制通道与 PCM 通道的独立执行资源（spec §6.4：PCM 与控制帧分开传输、有界失败）。
 *
 * 为什么必须分开：PCM 连接是长生命周期的读取循环（一条连接持续到通话结束，
 * 最长 180 s）。若它和控制请求共用 worker 池与连接信号量，两条 PCM 流就能占满
 * 全部 worker，让 shutdown、人工交还等控制命令等到通话结束。这里给 PCM 独立的
 * 执行器与独立的连接信号量，PCM 每条连接独占一个线程并有显式上限
 * （[maxPcmConnections]），控制 lane 永远不会被 PCM 流占用。
 *
 * 两队资源都是有界的：连接槽满或执行器队列满时 [submitControl]/[submitPcm]
 * 立即返回 false（由调用方关闭连接并计数），不阻塞 accept 线程。
 */
internal class ConnectionLanes(
    controlWorkers: Int,
    controlQueueDepth: Int,
    maxControlConnections: Int,
    maxPcmConnections: Int,
) : AutoCloseable {

    private val controlSlots = Semaphore(maxControlConnections)
    private val pcmSlots = Semaphore(maxPcmConnections)

    private val controlExecutor = pool("agentcall-control-worker", controlWorkers, controlQueueDepth)

    // PCM 每条连接独占一个线程：线程数就是并发 PCM 上限，队列不积压长任务。
    private val pcmExecutor = pool("agentcall-pcm-worker", maxPcmConnections, maxPcmConnections)

    private val rejected = AtomicLong(0)
    private val closed = AtomicBoolean(false)

    /** 因 lane 关闭、连接/队列饱和被拒绝的提交次数（控制与 PCM 合计）。 */
    val rejectedSubmissions: Long get() = rejected.get()

    fun submitControl(task: () -> Unit): Boolean = submit(controlExecutor, controlSlots, task)

    fun submitPcm(task: () -> Unit): Boolean = submit(pcmExecutor, pcmSlots, task)

    private fun submit(executor: ThreadPoolExecutor, slots: Semaphore, task: () -> Unit): Boolean {
        if (closed.get() || !slots.tryAcquire()) {
            rejected.incrementAndGet()
            return false
        }
        try {
            executor.execute {
                try {
                    task()
                } finally {
                    slots.release()
                }
            }
        } catch (rejectedExecution: RejectedExecutionException) {
            // 队列饱和：有界失败，槽位立即归还。
            slots.release()
            rejected.incrementAndGet()
            return false
        }
        return true
    }

    /** 停止接收新任务；已提交任务继续运行（close 的宽限期需要写回 shutdown 响应）。 */
    fun shutdown() {
        controlExecutor.shutdown()
        pcmExecutor.shutdown()
    }

    /** 在总预算内等待两个执行器结束；超时返回 false，由调用方强制关闭连接。 */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        val controlDone = awaitQuietly(controlExecutor, deadlineMs)
        val pcmDone = awaitQuietly(pcmExecutor, deadlineMs)
        return controlDone && pcmDone
    }

    fun shutdownNow() {
        controlExecutor.shutdownNow()
        pcmExecutor.shutdownNow()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        shutdown()
        awaitTermination(CLOSE_GRACE_MS)
        shutdownNow()
    }

    private fun awaitQuietly(executor: ThreadPoolExecutor, deadlineMs: Long): Boolean {
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0L) return executor.isTerminated
        return try {
            executor.awaitTermination(remaining, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun pool(name: String, threads: Int, queueDepth: Int): ThreadPoolExecutor =
        ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(maxOf(1, queueDepth)),
            ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        )

    companion object {
        /** 关闭时等待已在执行的请求写回响应的上限（与 ControlServer 的宽限期一致）。 */
        private const val CLOSE_GRACE_MS: Long = 500L
    }
}

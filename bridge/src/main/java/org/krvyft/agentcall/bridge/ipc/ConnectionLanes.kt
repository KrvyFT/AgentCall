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
 * 控制通道、PCM 通道与“长等待命令”的独立执行资源（spec §6.4：分开传输、有界失败）。
 *
 * 为什么必须分开：
 * - PCM 连接是长生命周期的读取循环（一条连接持续到通话结束，最长 180 s）。若它和控制
 *   请求共用 worker 池与连接信号量，两条 PCM 流就能占满全部 worker，让 shutdown、人工
 *   交还等控制命令等到通话结束。
 * - `session.command` / `session.request` 会同步等待 HOOK 回报的真实命令结果（最长
 *   [org.krvyft.agentcall.bridge.supervision.HookCommandCallControl.DEFAULT_TIMEOUT_MS]）。
 *   这类等待必须离开控制 worker：两个并发命令占满 2 个控制 worker 时，HOOK 心跳（取走
 *   排队命令的唯一通道）会排在它们后面，命令永远拿不到结果 → 必然 command_timeout。
 *   因此这里给长等待请求一条**专用命令 lane**（独立执行器 + 独立槽位上限），控制 lane
 *   在命令等待期间仍然空着服务 HOOK 心跳/诊断/关闭。
 *
 * 三队资源都是有界的：连接槽满或执行器队列满时 [submitControl]/[submitPcm] 立即返回
 * false（由调用方关闭连接并计数），不阻塞 accept 线程。控制连接的“接纳”与“执行”分开
 * （[tryAdmitControl] + [submitControlTask]），因为一条请求可能在读帧之后才决定交给命令
 * lane；连接槽必须一直占到连接真正结束，才能守住控制连接的硬上限。
 */
internal class ConnectionLanes(
    controlWorkers: Int,
    controlQueueDepth: Int,
    maxControlConnections: Int,
    maxPcmConnections: Int,
    commandWorkers: Int = COMMAND_WORKERS,
    commandQueueDepth: Int = COMMAND_QUEUE_DEPTH,
    maxCommandConnections: Int = MAX_COMMAND_CONNECTIONS,
) : AutoCloseable {

    private val controlSlots = Semaphore(maxControlConnections)
    private val pcmSlots = Semaphore(maxPcmConnections)
    private val commandSlots = Semaphore(maxCommandConnections)

    private val controlExecutor = pool("agentcall-control-worker", controlWorkers, controlQueueDepth)

    // PCM 每条连接独占一个线程：线程数就是并发 PCM 上限，队列不积压长任务。
    private val pcmExecutor = pool("agentcall-pcm-worker", maxPcmConnections, maxPcmConnections)

    private val commandExecutor = pool("agentcall-command-worker", commandWorkers, commandQueueDepth)

    private val rejected = AtomicLong(0)
    private val closed = AtomicBoolean(false)

    /** 因 lane 关闭、连接/队列饱和被拒绝的提交次数（控制、PCM、命令合计）。 */
    val rejectedSubmissions: Long get() = rejected.get()

    fun submitPcm(task: () -> Unit): Boolean = submitWithSlot(pcmExecutor, pcmSlots, task)

    // ------------------------------------------------------------------ 控制连接接纳

    /** 为一条控制连接占一个连接槽；连接真正结束时调用方必须 [releaseControlSlot]。 */
    fun tryAdmitControl(): Boolean = admit(controlSlots)

    fun releaseControlSlot() {
        controlSlots.release()
    }

    /** 在控制 worker 上执行一段连接处理；不占连接槽（槽由调用方在连接结束时释放）。 */
    fun submitControlTask(task: () -> Unit): Boolean = submitTask(controlExecutor, task)

    // ------------------------------------------------------------------ 命令 lane

    /** 为一条长等待请求占一个命令槽；饱和立即 false（调用方必须有界失败）。 */
    fun tryAdmitCommand(): Boolean = admit(commandSlots)

    fun releaseCommandSlot() {
        commandSlots.release()
    }

    /** 在命令 lane 上执行长等待请求；绝不占用控制 worker。 */
    fun submitCommandTask(task: () -> Unit): Boolean = submitTask(commandExecutor, task)

    // ------------------------------------------------------------------ 内部

    private fun admit(slots: Semaphore): Boolean {
        if (closed.get()) {
            rejected.incrementAndGet()
            return false
        }
        if (!slots.tryAcquire()) {
            rejected.incrementAndGet()
            return false
        }
        return true
    }

    private fun submitTask(executor: ThreadPoolExecutor, task: () -> Unit): Boolean {
        if (closed.get()) {
            rejected.incrementAndGet()
            return false
        }
        return try {
            executor.execute { task() }
            true
        } catch (rejectedExecution: RejectedExecutionException) {
            rejected.incrementAndGet()
            false
        }
    }

    private fun submitWithSlot(executor: ThreadPoolExecutor, slots: Semaphore, task: () -> Unit): Boolean {
        if (!admit(slots)) return false
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) slots.release()
        }
        val submitted = submitTask(executor) {
            try {
                task()
            } finally {
                release()
            }
        }
        if (!submitted) release()
        return submitted
    }

    /** 停止接收新任务；已提交任务继续运行（close 的宽限期需要写回 shutdown 响应）。 */
    fun shutdown() {
        controlExecutor.shutdown()
        pcmExecutor.shutdown()
        commandExecutor.shutdown()
    }

    /** 在总预算内等待三个执行器结束；超时返回 false，由调用方强制关闭连接。 */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        val controlDone = awaitQuietly(controlExecutor, deadlineMs)
        val pcmDone = awaitQuietly(pcmExecutor, deadlineMs)
        val commandDone = awaitQuietly(commandExecutor, deadlineMs)
        return controlDone && pcmDone && commandDone
    }

    fun shutdownNow() {
        controlExecutor.shutdownNow()
        pcmExecutor.shutdownNow()
        commandExecutor.shutdownNow()
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
        /** 命令 lane 的 worker 数：两条并发命令各自等待，不影响控制 lane。 */
        const val COMMAND_WORKERS: Int = 2

        /** 命令 lane 的排队上限（有界失败，绝不无界堆积）。 */
        const val COMMAND_QUEUE_DEPTH: Int = 8

        /**
         * 同时最多 4 条长等待请求。控制连接槽上限是
         * [ControlServer.MAX_CONNECTIONS]（8），因此命令等待最多占一半，剩下的连接槽
         * 永远留给 HOOK 心跳/诊断/关闭——命令不可能把所有控制连接名额吃光。
         */
        const val MAX_COMMAND_CONNECTIONS: Int = 4

        /** 关闭时等待已在执行的请求写回响应的上限（与 ControlServer 的宽限期一致）。 */
        private const val CLOSE_GRACE_MS: Long = 500L
    }
}

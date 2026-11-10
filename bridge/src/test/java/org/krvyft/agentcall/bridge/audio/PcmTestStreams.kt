package org.krvyft.agentcall.bridge.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PCM lane 的 JVM 测试替身：内存中的阻塞双向流，语义与 LocalSocket 流一致
 * （单读线程、读到 EOF 返回 -1、写可被阻塞以制造有界饱和）。
 *
 * 这些类型只用于 [AudioPcmRelay] / [AudioPcmSessionService] 的注入测试，
 * 绝不进入生产装配。
 */
class FakeInputStream : InputStream() {

    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var pending: ByteArray? = null
    private var pendingIndex = 0
    private val eofMarker = ByteArray(0)

    /** 追加一段可读字节；读取方按顺序消费。 */
    fun feed(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "feed must not be empty" }
        chunks.put(bytes)
    }

    /** 让读取方看到 EOF（相当于对端关闭连接）。 */
    fun endInput() {
        chunks.put(eofMarker)
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val current = pending
            if (current != null && pendingIndex < current.size) {
                val count = minOf(length, current.size - pendingIndex)
                System.arraycopy(current, pendingIndex, buffer, offset, count)
                pendingIndex += count
                return count
            }
            pending = null
            pendingIndex = 0
            val next = try {
                chunks.take()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            if (next === eofMarker) return -1
            pending = next
        }
    }

    override fun close() {
        endInput()
    }
}

/**
 * 记录写入字节的假输出流；[blockFromCurrentSize] 之后的所有写入都会阻塞，
 * 用来证明上行队列饱和时是**有界失败**而不是无限堆积。
 */
class FakeOutputStream : OutputStream() {

    private val data = ByteArrayOutputStream()
    private val dataLock = Any()
    private val gate = CountDownLatch(1)
    private val blocked = CountDownLatch(1)

    @Volatile
    private var blockFrom: Int = Int.MAX_VALUE

    @Volatile
    private var failing: Boolean = false

    fun blockFromCurrentSize() {
        blockFrom = size()
    }

    /** 之后的所有写入都抛 IOException（模拟 APP 读者已消失/链路已断）。 */
    fun failWrites() {
        failing = true
    }

    fun unblockWrites() {
        gate.countDown()
    }

    /** 写入方是否已经因为阻塞上限停下来（用于饱和测试的同步点）。 */
    fun awaitBlocked(timeoutMs: Long): Boolean = blocked.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun size(): Int = synchronized(dataLock) { data.size() }

    fun snapshot(): ByteArray = synchronized(dataLock) { data.toByteArray() }

    /** 取出并清空当前已写字节解析出的帧。 */
    fun takeFrames(): List<PcmLaneFrame> {
        val bytes = synchronized(dataLock) {
            val copy = data.toByteArray()
            data.reset()
            copy
        }
        return parseFrames(bytes)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (failing) throw java.io.IOException("fake app stream failed")
        while (blockFrom != Int.MAX_VALUE && size() + length > blockFrom) {
            blocked.countDown()
            try {
                if (gate.await(20, TimeUnit.MILLISECONDS)) break
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        synchronized(dataLock) { data.write(buffer, offset, length) }
    }
}

/** 把帧编码成字节，供假对端投喂。 */
fun frameBytes(type: Int, body: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    check(PcmLaneCodec.writeFrame(output, type, body)) { "test frame does not fit the lane cap" }
    return output.toByteArray()
}

/** 假音频工作进程的握手成功响应。 */
fun openOkBytes(format: org.krvyft.agentcall.core.model.AudioFormatSpec): ByteArray =
    frameBytes(PcmLaneType.CONTROL, PcmLaneCodec.encodeOpenOk(format))

/** 假音频工作进程的握手失败响应。 */
fun openErrorBytes(code: String, message: String): ByteArray =
    frameBytes(PcmLaneType.CONTROL, PcmLaneCodec.encodeError(code, message))

fun parseFrames(bytes: ByteArray): List<PcmLaneFrame> {
    val frames = ArrayList<PcmLaneFrame>()
    val input = ByteArrayInputStream(bytes)
    while (true) {
        when (val read = PcmLaneCodec.readFrame(input)) {
            is PcmLaneRead.Frame -> frames += read.frame
            PcmLaneRead.Closed -> return frames
            is PcmLaneRead.Invalid -> return frames
        }
    }
}

/** 轮询等待条件成立（测试里的有界等待，避免无限 sleep）。 */
fun awaitCondition(description: String = "condition", timeoutMs: Long = 3_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(5)
    }
    throw AssertionError("timed out waiting for " + description)
}
/** 从 [FakeOutputStream] 增量收集帧的测试侧缓冲（只由测试线程访问）。 */
class FrameCollector(private val output: FakeOutputStream) {

    val frames = ArrayList<PcmLaneFrame>()

    fun sync() {
        frames += output.takeFrames()
    }

    fun await(description: String = "frame", predicate: (List<PcmLaneFrame>) -> Boolean) {
        awaitCondition(description = description) {
            sync()
            predicate(frames)
        }
    }

    fun ofType(type: Int): List<PcmLaneFrame> {
        sync()
        return frames.filter { it.type == type }
    }

    fun controlBody(): List<ByteArray> {
        sync()
        return frames.filter { it.type == PcmLaneType.CONTROL }.map { it.body }
    }
}

package org.krvyft.agentcall.hook

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream

/**
 * 传输层对一条 abstract LocalSocket 连接的最小抽象（Task 11Q）。
 *
 * 旧实现把 [android.net.LocalSocket] 直接写死在 [HookTransport] 里，导致连接失败、
 * 服务端不可信、协议违规三条分支在 JVM 上完全不可测。接口只保留传输真正用到的能力：
 * 连接、读取对端 uid、设置读超时、双向流、关闭。
 *
 * **信任方向不变**：[HookTransport] 仍然先 [connect] 再读 [peerUid]，只有 uid 0
 * 才写请求；接口不提供“跳过校验直接写”的入口。
 */
interface HookSocketLink {

    /** 连接 abstract socket；失败抛 [java.io.IOException]。 */
    fun connect()

    /**
     * 服务端内核 uid；凭据不可读时返回 null（等价于不可信）。
     *
     * 只能在 [connect] 之后读取。
     */
    val peerUid: Int?

    /** 读超时（毫秒）。传输在核验服务端之后、写入之前设置。 */
    var soTimeoutMs: Int

    val output: OutputStream

    val input: InputStream

    fun close()
}

/** 生产实现：每条请求一条 abstract [LocalSocket]（与 Task 3 的一请求一响应一致）。 */
internal class AndroidHookSocketLink(
    private val socketName: String,
    timeoutMs: Int,
) : HookSocketLink {

    private val socket = LocalSocket()

    override var soTimeoutMs: Int = timeoutMs
        set(value) {
            field = value
            socket.soTimeout = value
        }

    override fun connect() {
        socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
    }

    override val peerUid: Int?
        get() = try {
            socket.peerCredentials?.uid
        } catch (failure: Throwable) {
            // 凭据不可读等价于不可信；不发送任何请求。
            null
        }

    override val output: OutputStream get() = socket.outputStream

    override val input: InputStream get() = socket.inputStream

    override fun close() {
        socket.close()
    }
}

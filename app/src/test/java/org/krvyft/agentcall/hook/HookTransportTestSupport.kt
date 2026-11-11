package org.krvyft.agentcall.hook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RpcCodec

/**
 * JVM 测试用的假 socket（Task 11Q）。
 *
 * 传输层只依赖 [HookSocketLink]，因此连接失败、服务端不可信、协议违规与正常应答都能在
 * 没有 android.net.LocalSocket 的 JVM 里确定性地复现；不会真的绑定 abstract 名字。
 */
internal class FakeHookSocketLink(
    private val peerUidValue: Int? = 0,
    private val connectFailure: Boolean = false,
    private val responder: (RpcRequest) -> ByteArray = { RpcCodec.encodeResponseFrame(RpcResponse(id = it.id)) },
) : HookSocketLink {

    val requests = CopyOnWriteArrayList<RpcRequest>()

    var closed: Boolean = false
        private set

    private val written = ByteArrayOutputStream()
    private var pendingResponse: ByteArrayInputStream? = null

    override var soTimeoutMs: Int = 0

    override fun connect() {
        if (connectFailure) throw IOException("connect refused")
    }

    override val peerUid: Int? get() = peerUidValue

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            written.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            written.write(b, off, len)
        }
    }

    override val input: InputStream
        get() {
            val current = pendingResponse
            if (current != null) return current
            val decoded = RpcCodec.readRequestFrame(ByteArrayInputStream(written.toByteArray()))
            val request = (decoded as? RpcCodec.RequestResult.Accepted)?.request
                ?: error("test request frame is not decodable")
            requests += request
            return ByteArrayInputStream(responder(request)).also { pendingResponse = it }
        }

    override fun close() {
        closed = true
    }
}

/** 在真实时间上限内等待假 socket 被使用（传输线程有自己的 200 ms 轮询节奏）。 */
internal fun awaitHookRequests(
    links: List<FakeHookSocketLink>,
    expected: Int,
    timeoutMs: Long = 5_000L,
): FakeHookSocketLink {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        // 连接先建立、请求后写入：必须等目标连接已记录请求帧，否则测试会在
        // “socket 已出现但 requests 仍为空”的窗口里读到空列表（真实竞态）。
        if (links.size >= expected && links[expected - 1].requests.isNotEmpty()) {
            return links[expected - 1]
        }
        Thread.sleep(10L)
    }
    throw AssertionError(
        "expected " + expected + " hook request(s), saw links=" + links.size +
            " requests=" + links.sumOf { it.requests.size },
    )
}

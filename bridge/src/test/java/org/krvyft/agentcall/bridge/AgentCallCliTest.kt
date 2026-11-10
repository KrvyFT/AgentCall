package org.krvyft.agentcall.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse

/**
 * root CLI 的纯 JVM 测试（注入 [CliTransport]，不触达 Android LocalSocket）。
 *
 * spec §6.4 要求请求 ID 强制验证：客户端也必须确认响应 ID 与请求 ID 一致，
 * 否则一条错配/串线的响应会被当成命令结果打印出来。
 */
class AgentCallCliTest {

    private fun invoke(args: List<String>, transport: CliTransport): Pair<Int, String> {
        val stdout = ByteArrayOutputStream()
        val exit = AgentCallCli.run(
            args.toTypedArray(),
            ByteArrayInputStream(ByteArray(0)),
            PrintStream(stdout, true, Charsets.UTF_8.name()),
            transport,
        )
        return exit to stdout.toString(Charsets.UTF_8.name())
    }

    @Test
    fun responseWithForeignRequestIdIsRejected() {
        val transport = CliTransport { request: RpcRequest ->
            RpcResponse(id = request.id + "-other", payload = "{\"marker\":\"do-not-print\"}")
        }

        val (exit, output) = invoke(listOf("status"), transport)

        assertEquals(AgentCallCli.EXIT_TRANSPORT, exit)
        assertTrue(output.contains("\"code\":\"response_id_mismatch\""))
        // 错配响应的负载绝不打印。
        assertFalse(output.contains("do-not-print"))
    }

    @Test
    fun responseWithMatchingRequestIdIsAccepted() {
        val transport = CliTransport { request: RpcRequest ->
            RpcResponse(id = request.id, payload = "{\"revision\":0}")
        }

        val (exit, output) = invoke(listOf("status"), transport)

        assertEquals(AgentCallCli.EXIT_OK, exit)
        assertTrue(output.contains("\"revision\":0"))
    }
}

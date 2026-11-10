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
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * root CLI 的纯 JVM 测试（注入 [CliTransport]，不触达 Android LocalSocket）。
 *
 * spec §6.4 要求请求 ID 强制验证：客户端也必须确认响应 ID 与请求 ID 一致，
 * 否则一条错配/串线的响应会被当成命令结果打印出来。
 */
class AgentCallCliTest {

    private fun invoke(
        args: List<String>,
        stdin: String = "",
        transport: CliTransport,
    ): Pair<Int, String> {
        val stdout = ByteArrayOutputStream()
        val exit = AgentCallCli.run(
            args.toTypedArray(),
            ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)),
            PrintStream(stdout, true, Charsets.UTF_8.name()),
            transport,
        )
        return exit to stdout.toString(Charsets.UTF_8.name())
    }

    private fun invoke(args: List<String>, transport: CliTransport): Pair<Int, String> =
        invoke(args, "", transport)

    // ------------------------------------------------------------------ calibrate

    private val validCalibratePayload = """
        {"steps":{"downlinkRecognized":true,"peerHeardSpeech":true,"microphoneIsolated":true,
        "manualRestoreVerified":true,"processFailureVerified":true}}
    """.trimIndent()

    @Test
    fun calibrateReadsThePayloadFromStdinAndCallsCalibrationRecord() {
        val seen = mutableListOf<RpcRequest>()
        val transport = CliTransport { request: RpcRequest ->
            seen += request
            RpcResponse(id = request.id, payload = "{\"recorded\":true}")
        }

        val (exit, output) = invoke(listOf("calibrate"), validCalibratePayload, transport)

        assertEquals(AgentCallCli.EXIT_OK, exit)
        assertEquals(1, seen.size)
        assertEquals(RolePolicy.METHOD_CALIBRATION_RECORD, seen[0].method)
        assertEquals("stdin 负载必须原样转交，命令行不得改写", validCalibratePayload, seen[0].payload)
        assertTrue(output.contains("\"recorded\":true"))
    }

    @Test
    fun calibrateRejectsInvalidJsonWithoutCallingTheBridge() {
        var called = false
        val transport = CliTransport { request: RpcRequest ->
            called = true
            RpcResponse(id = request.id, payload = "{}")
        }

        val (exit, output) = invoke(listOf("calibrate"), "{not-json", transport)

        assertEquals(AgentCallCli.EXIT_USAGE, exit)
        assertTrue(output.contains("\"code\":\"payload_invalid\""))
        assertFalse(called)
    }

    @Test
    fun calibrateRejectsNonObjectPayloads() {
        var called = false
        val transport = CliTransport { request: RpcRequest ->
            called = true
            RpcResponse(id = request.id, payload = "{}")
        }

        for (payload in listOf("[]", "\"steps\"", "42", "null")) {
            val (exit, output) = invoke(listOf("calibrate"), payload, transport)
            assertEquals("payload=$payload", AgentCallCli.EXIT_USAGE, exit)
            assertTrue(output.contains("\"code\":\"payload_invalid\""))
        }
        assertFalse(called)
    }

    @Test
    fun calibrateRejectsExtraKeys() {
        var called = false
        val transport = CliTransport { request: RpcRequest ->
            called = true
            RpcResponse(id = request.id, payload = "{}")
        }
        val extra = "{\"steps\":{},\"environment\":{}}"

        val (exit, output) = invoke(listOf("calibrate"), extra, transport)

        assertEquals(AgentCallCli.EXIT_USAGE, exit)
        assertTrue(output.contains("\"code\":\"payload_invalid\""))
        assertFalse("含额外键的负载绝不能到达 bridge", called)
    }

    @Test
    fun calibrateRejectsEmptyPayload() {
        var called = false
        val transport = CliTransport { request: RpcRequest ->
            called = true
            RpcResponse(id = request.id, payload = "{}")
        }

        val (exit, output) = invoke(listOf("calibrate"), "\n  \n", transport)

        assertEquals(AgentCallCli.EXIT_USAGE, exit)
        assertTrue(output.contains("\"code\":\"payload_invalid\""))
        assertFalse(called)
    }

    @Test
    fun calibrateNeverAcceptsThePayloadOnArgv() {
        var called = false
        val transport = CliTransport { request: RpcRequest ->
            called = true
            RpcResponse(id = request.id, payload = "{}")
        }

        val (exit, output) = invoke(
            listOf("calibrate", validCalibratePayload),
            validCalibratePayload,
            transport,
        )

        assertEquals(AgentCallCli.EXIT_USAGE, exit)
        assertTrue(output.contains("\"code\":\"usage_invalid\""))
        assertFalse("argv 中的负载绝不能被当成 stdin 负载", called)
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

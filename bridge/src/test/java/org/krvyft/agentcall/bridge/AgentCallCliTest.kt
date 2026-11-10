package org.krvyft.agentcall.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.bridge.ipc.ControlServer
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

    /**
     * Task 11E / C1：calibrate 的传输层只做外层形状校验，必须允许可选的
     * operatorAttestation 对象原样转交；语义（固定词表、root-only）由 bridge 判定。
     */
    @Test
    fun calibrateAcceptsTheOptionalOperatorAttestationEnvelope() {
        val seen = mutableListOf<RpcRequest>()
        val transport = CliTransport { request: RpcRequest ->
            seen += request
            RpcResponse(id = request.id, payload = "{\"recorded\":true}")
        }
        val payload = """
            {"steps":{"downlinkRecognized":true,"peerHeardSpeech":true,"microphoneIsolated":true,
            "manualRestoreVerified":true,"processFailureVerified":true},
            "operatorAttestation":{"transport":"CELLULAR","route":"EARPIECE","manualTestCompleted":true}}
        """.trimIndent()

        val (exit, output) = invoke(listOf("calibrate"), payload, transport)

        assertEquals(AgentCallCli.EXIT_OK, exit)
        assertEquals(1, seen.size)
        assertEquals("负载必须原样转交", payload, seen[0].payload)
        assertTrue(output.contains("\"recorded\":true"))
    }

    @Test
    fun calibrateRejectsANonObjectOperatorAttestationBeforeCallingTheBridge() {
        var called = false
        val transport = CliTransport { request: RpcRequest ->
            called = true
            RpcResponse(id = request.id, payload = "{}")
        }

        val (exit, output) = invoke(
            listOf("calibrate"),
            "{\"steps\":{},\"operatorAttestation\":\"CELLULAR\"}",
            transport,
        )

        assertEquals(AgentCallCli.EXIT_USAGE, exit)
        assertTrue(output.contains("\"code\":\"payload_invalid\""))
        assertFalse(called)
    }

    // ------------------------------------------------------------ shutdown 超时

    /**
     * Task 11E / I1：优雅关闭的监督端预算（5 s 终止 + 6 s 恢复 + 最多一次不可中断的
     * 5 s 角色通道往返）必须落在 CLI 的 shutdown 专用超时内。其他方法保持 10 s。
     */
    @Test
    fun shutdownUsesItsOwnLongerSocketTimeout() {
        val shutdownTimeout = LocalSocketCliTransport.SHUTDOWN_TIMEOUT_MS
        assertEquals(20_000, shutdownTimeout)
        assertEquals(10_000, ControlServer.READ_TIMEOUT_MS)
        assertEquals(
            "shutdown 必须使用 20 s",
            shutdownTimeout,
            LocalSocketCliTransport.timeoutMsFor(RolePolicy.METHOD_SHUTDOWN, ControlServer.READ_TIMEOUT_MS),
        )
        for (method in listOf(
            RolePolicy.METHOD_STATUS,
            RolePolicy.METHOD_DIAGNOSE,
            RolePolicy.METHOD_CALIBRATION_READ,
            RolePolicy.METHOD_CALIBRATION_RECORD,
            RolePolicy.METHOD_CONFIG_READ,
        )) {
            assertEquals(
                "只有 shutdown 放宽超时：" + method,
                ControlServer.READ_TIMEOUT_MS,
                LocalSocketCliTransport.timeoutMsFor(method, ControlServer.READ_TIMEOUT_MS),
            )
        }
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

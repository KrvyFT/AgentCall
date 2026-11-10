package org.krvyft.agentcall.core.protocol

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.krvyft.agentcall.core.model.AgentConfig
import org.krvyft.agentcall.core.model.ApiEndpoint
import org.krvyft.agentcall.core.model.CapabilityReport
import org.krvyft.agentcall.core.model.CapabilityStatus
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.EnvironmentKey
import org.krvyft.agentcall.core.model.RedactedConfig
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.RuleMode
import org.krvyft.agentcall.core.model.SecretState
import org.krvyft.agentcall.core.model.SecretUpdate
import org.krvyft.agentcall.core.model.SessionId

/**
 * 控制帧编解码与校验的行为测试。先于实现存在，用于记录红灯。
 *
 * 关键约束（spec §6.4）：控制帧最大 64 KiB、请求 ID/会话/版本/长度全部校验；
 * 截断或超长帧必须在读取正文前就失败，不能先分配内存。
 */
class RpcCodecTest {

    private val session = SessionId("epoch-1", "call-1")

    // 测试自己构造帧头，避免用被测实现生成输入。
    private fun rawHeader(declaredLength: Int): ByteArray = byteArrayOf(
        (declaredLength ushr 24).toByte(),
        (declaredLength ushr 16).toByte(),
        (declaredLength ushr 8).toByte(),
        declaredLength.toByte(),
    )

    private fun rawFrame(body: ByteArray): ByteArray = rawHeader(body.size) + body

    private fun jsonFrame(json: String): ByteArray = rawFrame(json.toByteArray(Charsets.UTF_8))

    private fun requestJson(
        id: String,
        method: String,
        version: Int = 1,
        sessionJson: String? = null,
    ): String {
        val builder = StringBuilder()
        builder.append("{\"id\":\"").append(id)
        builder.append("\",\"method\":\"").append(method)
        builder.append("\",\"version\":").append(version)
        if (sessionJson != null) {
            builder.append(",\"sessionId\":").append(sessionJson)
        }
        builder.append(",\"payload\":\"\"}")
        return builder.toString()
    }

    private fun rejected(result: RpcCodec.FrameResult): RpcCodec.FrameResult.Rejected {
        assertTrue("expected rejection, got " + result, result is RpcCodec.FrameResult.Rejected)
        return result as RpcCodec.FrameResult.Rejected
    }

    private fun requestRejected(result: RpcCodec.RequestResult): RpcCodec.RequestResult.Rejected {
        assertTrue("expected rejection, got " + result, result is RpcCodec.RequestResult.Rejected)
        return result as RpcCodec.RequestResult.Rejected
    }

    private fun acceptedRequest(result: RpcCodec.RequestResult): RpcRequest {
        assertTrue("expected acceptance, got " + result, result is RpcCodec.RequestResult.Accepted)
        return (result as RpcCodec.RequestResult.Accepted).request
    }

    private fun acceptedResponse(result: RpcCodec.ResponseResult): RpcResponse {
        assertTrue("expected acceptance, got " + result, result is RpcCodec.ResponseResult.Accepted)
        return (result as RpcCodec.ResponseResult.Accepted).response
    }

    @Test
    fun truncatedFrameRejected() {
        // 声明 32 字节正文，实际只有 8 字节：必须报截断。
        val truncated = RpcCodec.readFrame(ByteArrayInputStream(rawHeader(32) + ByteArray(8)))
        assertEquals(RpcCodec.ERROR_FRAME_TRUNCATED, rejected(truncated).code)

        // 只有帧头、没有任何正文也是截断。
        val headerOnly = RpcCodec.readFrame(ByteArrayInputStream(rawHeader(32)))
        assertEquals(RpcCodec.ERROR_FRAME_TRUNCATED, rejected(headerOnly).code)
    }

    @Test
    fun oversizedFrameRejected() {
        // 超过 64 KiB 上限时，连正文都不允许读取（在分配/阻塞前失败）。
        val bodyRead = booleanArrayOf(false)
        val headerOnly = object : InputStream() {
            private var index = 0
            private val header = rawHeader(RpcCodec.MAX_CONTROL_FRAME_BYTES + 1)

            override fun read(): Int {
                if (index < header.size) {
                    return header[index++].toInt() and 0xFF
                }
                bodyRead[0] = true
                throw AssertionError("oversized frame body must not be read")
            }
        }
        val oversized = RpcCodec.readFrame(headerOnly)
        assertEquals(RpcCodec.ERROR_FRAME_TOO_LARGE, rejected(oversized).code)
        assertFalse("body must not be read", bodyRead[0])

        // 非正长度同样非法。
        assertEquals(
            RpcCodec.ERROR_FRAME_LENGTH_INVALID,
            rejected(RpcCodec.readFrame(ByteArrayInputStream(rawHeader(0)))).code,
        )
        assertEquals(
            RpcCodec.ERROR_FRAME_LENGTH_INVALID,
            rejected(RpcCodec.readFrame(ByteArrayInputStream(rawHeader(-1)))).code,
        )

        // 写方向也不能生成超限帧。
        try {
            RpcCodec.encodeFrame(ByteArray(RpcCodec.MAX_CONTROL_FRAME_BYTES + 1))
            fail("encoding an oversized frame must fail")
        } catch (expected: RpcProtocolException) {
            assertEquals(RpcCodec.ERROR_FRAME_TOO_LARGE, expected.code)
        }
    }

    @Test
    fun frameAtExactCapIsAccepted() {
        val body = ByteArray(RpcCodec.MAX_CONTROL_FRAME_BYTES) { 'a'.code.toByte() }

        val encoded = RpcCodec.encodeFrame(body)
        val decoded = RpcCodec.readFrame(ByteArrayInputStream(encoded))

        assertTrue(decoded is RpcCodec.FrameResult.Accepted)
        assertTrue((decoded as RpcCodec.FrameResult.Accepted).body.contentEquals(body))
    }

    @Test
    fun requestRoundTripPreservesFields() {
        val request = RpcRequest(
            id = "req-1",
            method = RolePolicy.METHOD_HOOK_SNAPSHOT,
            sessionId = session,
            payload = "{\"state\":\"RINGING\"}",
        )

        val decoded = RpcCodec.readRequestFrame(ByteArrayInputStream(RpcCodec.encodeRequestFrame(request)))

        assertEquals(request, acceptedRequest(decoded))
    }

    @Test
    fun requestWithoutSessionRoundTrips() {
        val request = RpcRequest("req-2", RolePolicy.METHOD_CONFIG_READ)

        val decoded = RpcCodec.readRequestFrame(ByteArrayInputStream(RpcCodec.encodeRequestFrame(request)))

        assertEquals(request, acceptedRequest(decoded))
    }

    @Test
    fun malformedRequestJsonRejected() {
        assertEquals(
            RpcCodec.ERROR_REQUEST_MALFORMED,
            requestRejected(RpcCodec.readRequestFrame(ByteArrayInputStream(jsonFrame("not json")))).code,
        )
        assertEquals(
            RpcCodec.ERROR_REQUEST_MALFORMED,
            requestRejected(RpcCodec.readRequestFrame(ByteArrayInputStream(jsonFrame("[1,2,3]")))).code,
        )
    }

    @Test
    fun protocolVersionMismatchRejected() {
        val json = requestJson("req-1", "hook.heartbeat", version = 2)

        val rejected = requestRejected(RpcCodec.readRequestFrame(ByteArrayInputStream(jsonFrame(json))))

        assertEquals(RpcCodec.ERROR_PROTOCOL_VERSION, rejected.code)
    }

    @Test
    fun requestIdAndMethodAreValidated() {
        assertEquals(
            RpcCodec.ERROR_REQUEST_ID_INVALID,
            requestRejected(
                RpcCodec.readRequestFrame(ByteArrayInputStream(jsonFrame(requestJson("", "hook.heartbeat")))),
            ).code,
        )
        assertEquals(
            RpcCodec.ERROR_REQUEST_ID_INVALID,
            requestRejected(
                RpcCodec.readRequestFrame(
                    ByteArrayInputStream(
                        jsonFrame(requestJson("r".repeat(RpcCodec.MAX_REQUEST_ID_CHARS + 1), "hook.heartbeat")),
                    ),
                ),
            ).code,
        )
        assertEquals(
            RpcCodec.ERROR_METHOD_INVALID,
            requestRejected(
                RpcCodec.readRequestFrame(ByteArrayInputStream(jsonFrame(requestJson("req-1", "")))),
            ).code,
        )
        assertEquals(
            RpcCodec.ERROR_METHOD_INVALID,
            requestRejected(
                RpcCodec.readRequestFrame(
                    ByteArrayInputStream(
                        jsonFrame(requestJson("req-1", "m".repeat(RpcCodec.MAX_METHOD_CHARS + 1))),
                    ),
                ),
            ).code,
        )
    }

    @Test
    fun sessionIdIsValidated() {
        assertEquals(
            RpcCodec.ERROR_SESSION_ID_INVALID,
            requestRejected(
                RpcCodec.readRequestFrame(
                    ByteArrayInputStream(
                        jsonFrame(
                            requestJson(
                                "req-1",
                                "hook.heartbeat",
                                sessionJson = """{"processEpoch":"","callId":"call-1"}""",
                            ),
                        ),
                    ),
                ),
            ).code,
        )
        assertEquals(
            RpcCodec.ERROR_SESSION_ID_INVALID,
            requestRejected(
                RpcCodec.readRequestFrame(
                    ByteArrayInputStream(
                        jsonFrame(
                            requestJson(
                                "req-1",
                                "hook.heartbeat",
                                sessionJson = "{\"processEpoch\":\"" +
                                    "e".repeat(RpcCodec.MAX_SESSION_FIELD_CHARS + 1) +
                                    "\",\"callId\":\"call-1\"}",
                            ),
                        ),
                    ),
                ),
            ).code,
        )
    }

    @Test
    fun responseWithPayloadAndErrorIsRejected() {
        val contradictory = RpcResponse(
            id = "req-1",
            payload = "{}",
            error = RpcError("internal_error", "boom"),
        )
        try {
            RpcCodec.encodeResponseFrame(contradictory)
            fail("a response may not carry both payload and error")
        } catch (expected: RpcProtocolException) {
            assertEquals(RpcCodec.ERROR_RESPONSE_MALFORMED, expected.code)
        }

        val json = """{"id":"req-1","version":1,"payload":"{}","error":{"code":"x","message":"y"}}"""
        val decoded = RpcCodec.readResponseFrame(ByteArrayInputStream(jsonFrame(json)))
        assertTrue(decoded is RpcCodec.ResponseResult.Rejected)
        assertEquals(
            RpcCodec.ERROR_RESPONSE_MALFORMED,
            (decoded as RpcCodec.ResponseResult.Rejected).code,
        )
    }

    @Test
    fun validResponsesRoundTrip() {
        val withPayload = RpcResponse("req-1", payload = """{"ok":true}""")
        val withError = RpcResponse("req-2", error = RpcError("config_conflict", "revision mismatch"))

        val decodedPayload = RpcCodec.readResponseFrame(ByteArrayInputStream(RpcCodec.encodeResponseFrame(withPayload)))
        val decodedError = RpcCodec.readResponseFrame(ByteArrayInputStream(RpcCodec.encodeResponseFrame(withError)))

        assertEquals(withPayload, acceptedResponse(decodedPayload))
        assertEquals(withError, acceptedResponse(decodedError))
    }

    @Test
    fun agentConfigRoundTripsThroughJson() {
        val config = AgentConfig(
            enabled = true,
            blacklist = listOf("+8613900139000"),
            region = "CN",
            answerDelayMs = 2500,
            maxCallDurationMs = 120_000,
            asr = ApiEndpoint("https://asr.example.com/v1", "whisper-1"),
            llm = null,
            tts = ApiEndpoint("https://tts.example.com/v1", "tts-1"),
            voice = "alloy",
            speed = 1.25,
            prompt = "prompt",
            revision = 7,
        )

        val decoded = RpcCodec.decodeAgentConfig(RpcCodec.encodeAgentConfig(config))

        assertEquals(config, decoded)
        assertNull(RpcCodec.decodeAgentConfig("not json"))
    }

    @Test
    fun configWriteDecodesPatchAndSecrets() {
        val payload = """
            {
              "expectedRevision": 4,
              "patch": {
                "enabled": true,
                "mode": "WHITELIST",
                "answerDelayMs": 1200,
                "asr": {"baseUrl": "", "model": ""},
                "secrets": {
                  "ASR": {"action": "replace", "value": "sk-new"},
                  "LLM": {"action": "keep"},
                  "TTS": {"action": "clear"}
                }
              }
            }
        """.trimIndent()

        val write = RpcCodec.decodeConfigWrite(payload)

        assertEquals(4L, write!!.expectedRevision)
        assertEquals(true, write.patch.enabled)
        assertEquals(RuleMode.WHITELIST, write.patch.mode)
        assertEquals(1200, write.patch.answerDelayMs)
        assertNull(write.patch.region)
        assertEquals(ApiEndpoint("", ""), write.patch.asr)
        assertEquals(SecretUpdate.Replace("sk-new"), write.patch.secrets[EndpointKind.ASR])
        assertEquals(SecretUpdate.Keep, write.patch.secrets[EndpointKind.LLM])
        assertEquals(SecretUpdate.Clear, write.patch.secrets[EndpointKind.TTS])
    }

    @Test
    fun configWriteRejectsUnknownOrInvalidPayloads() {
        // 未知字段一律拒绝：固定命令表之外的输入不能借补丁通道生效。
        assertNull(
            RpcCodec.decodeConfigWrite(
                """{"expectedRevision":1,"patch":{"enabled":true,"shell":"rm -rf /"}}""",
            ),
        )
        // 负数 revision 不是合法的 CAS 期望值。
        assertNull(RpcCodec.decodeConfigWrite("""{"expectedRevision":-1,"patch":{"enabled":true}}"""))
        // 未知密钥动作拒绝。
        assertNull(
            RpcCodec.decodeConfigWrite(
                """{"expectedRevision":1,"patch":{"secrets":{"ASR":{"action":"export"}}}}""",
            ),
        )
        // 非对象负载拒绝。
        assertNull(RpcCodec.decodeConfigWrite("""[1,2,3]"""))
    }

    @Test
    fun redactedConfigExportCarriesOnlySecretState() {
        val redacted = RedactedConfig(
            config = AgentConfig(enabled = true, revision = 3),
            secrets = mapOf(
                EndpointKind.ASR to SecretState.CONFIGURED,
                EndpointKind.LLM to SecretState.MISSING,
                EndpointKind.TTS to SecretState.MISSING,
            ),
        )

        val json = RpcCodec.encodeRedacted(redacted)

        assertTrue(json.contains("\"ASR\":\"CONFIGURED\""))
        assertTrue(json.contains("\"LLM\":\"MISSING\""))
        assertTrue(json.contains("\"revision\":3"))
        assertFalse(json.contains("sk-"))
    }

    @Test
    fun capabilityReportKeepsUntestedFactsVisible() {
        val report = CapabilityReport(
            environment = EnvironmentKey(
                apiLevel = 37,
                romFingerprint = "SONY/xxx:17/yyy",
                kernelVersion = "6.1.0-test",
                dialerPackageVersion = "100.0",
                frameworkVersion = "UNKNOWN",
                bridgeVersion = "0.1",
                audioPolicyDigest = "UNKNOWN",
                simCarrier = "UNKNOWN",
                transport = "UNKNOWN",
                route = "UNKNOWN",
            ),
            downlinkExtraction = CapabilityStatus.NOT_DETECTED,
            uplinkInjection = CapabilityStatus.NOT_DETECTED,
            microphoneIsolation = CapabilityStatus.NOT_DETECTED,
            privilegedTermination = CapabilityStatus.NOT_DETECTED,
            checkedAtMs = 1_700_000_000_000L,
            notes = "audio:UNTESTED permissions:UNTESTED",
        )

        val json = RpcCodec.encodeCapabilityReport(report)

        assertTrue(json.contains("SONY/xxx:17/yyy"))
        assertTrue(json.contains("\"apiLevel\":37"))
        assertTrue(json.contains("NOT_DETECTED"))
        assertTrue(json.contains("audio:UNTESTED"))
        assertFalse(json.contains("VERIFIED"))
    }
}

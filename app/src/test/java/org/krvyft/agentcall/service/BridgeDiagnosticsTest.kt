package org.krvyft.agentcall.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.ipc.BridgeRead
import org.krvyft.agentcall.ipc.BridgeStatusFacts
import org.krvyft.agentcall.ipc.ControlTransport

/**
 * 只读诊断客户端的 JVM 测试：三态 HOOK 存在性与环境解析必须 fail closed。
 *
 * Task 7C 起桥接的 APP 固定表已包含 status/diagnose，生产装配（Task 7B3）改由
 * [org.krvyft.agentcall.ipc.BridgeClient] 读取同一份负载；本类保留为旧客户端的回归测试。
 * forbidden 仍可能来自旧桥接/角色表不匹配，必须表达为“不可用”，而不是在线或离线。
 */
class BridgeDiagnosticsTest {

    private class FakeTransport(
        private val respond: (RpcRequest) -> RpcResponse?,
    ) : ControlTransport {
        val methods = ArrayList<String>()

        override suspend fun request(request: RpcRequest): RpcResponse? {
            methods += request.method
            return respond(request)
        }

        override suspend fun close() = Unit
    }

    private fun statusResponse(payload: String): (RpcRequest) -> RpcResponse = { request ->
        RpcResponse(id = request.id, payload = payload)
    }

    private val environmentPayload = "\"environment\":{" +
        "\"apiLevel\":37," +
        "\"romFingerprint\":\"fp\"," +
        "\"kernelVersion\":\"6.1\"," +
        "\"dialerPackageVersion\":\"1\"," +
        "\"frameworkVersion\":\"82\"," +
        "\"bridgeVersion\":\"1.0\"," +
        "\"audioPolicyDigest\":\"digest\"," +
        "\"simCarrier\":\"carrier\"," +
        "\"transport\":\"VoLTE\"," +
        "\"route\":\"handset\"}"

    @Test
    fun statusExposesAHookPresenceThreeState() = runBlocking {
        val present = BridgeDiagnostics(FakeTransport(statusResponse(statusJson("true")))).status()
        assertEquals(HookPresence.PRESENT, (present as BridgeStatusResult.Available).status.hookConnected)

        val absent = BridgeDiagnostics(FakeTransport(statusResponse(statusJson("false")))).status()
        assertEquals(HookPresence.ABSENT, (absent as BridgeStatusResult.Available).status.hookConnected)

        val unknownString = BridgeDiagnostics(FakeTransport(statusResponse(statusJson("\"unknown\"")))).status()
        assertEquals(HookPresence.UNKNOWN, (unknownString as BridgeStatusResult.Available).status.hookConnected)

        val missing = BridgeDiagnostics(FakeTransport(statusResponse("{}"))).status()
        assertEquals(HookPresence.UNKNOWN, (missing as BridgeStatusResult.Available).status.hookConnected)
    }

    @Test
    fun statusCarriesRoleAndVersionFacts() = runBlocking {
        val result = BridgeDiagnostics(FakeTransport(statusResponse(statusJson("true")))).status()
        val status = (result as BridgeStatusResult.Available).status
        assertEquals(false, status.dialerDefaultRole)
        assertEquals(true, status.paired)
        assertEquals("IDLE", status.supervisionPhase)
        assertEquals("1.0", status.bridgeVersion)
        assertEquals(true, status.enabled)
    }

    @Test
    fun forbiddenStatusIsUnavailableInsteadOfOffline() = runBlocking {
        val transport = FakeTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "method is not in the role table"))
        }
        val result = BridgeDiagnostics(transport).status()
        assertEquals(BridgeStatusResult.Unavailable("forbidden"), result)
        assertEquals(listOf(RolePolicy.METHOD_STATUS), transport.methods)
    }

    @Test
    fun unreachableTransportIsUnavailable() = runBlocking {
        val result = BridgeDiagnostics(FakeTransport { null }).status()
        assertEquals(BridgeStatusResult.Unavailable(BridgeDiagnostics.ERROR_UNREACHABLE), result)
    }

    @Test
    fun diagnoseEnvironmentRequiresEveryField() = runBlocking {
        val complete = BridgeDiagnostics(
            FakeTransport(statusResponse("{" + environmentPayload + "}")),
        ).environment()
        val environment = (complete as EnvironmentResult.Available).environment
        assertEquals(37, environment.apiLevel)
        assertEquals("handset", environment.route)

        val incomplete = BridgeDiagnostics(
            FakeTransport(statusResponse("{\"environment\":{\"apiLevel\":37}}")),
        ).environment()
        assertEquals(EnvironmentResult.Unavailable(BridgeDiagnostics.ERROR_PAYLOAD), incomplete)
    }

    @Test
    fun diagnoseForbiddenIsUnavailable() = runBlocking {
        val result = BridgeDiagnostics(FakeTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "denied"))
        }).environment()
        assertEquals(EnvironmentResult.Unavailable("forbidden"), result)
    }

    @Test
    fun statusAndDiagnoseUseTheirFixedMethods() = runBlocking {
        val transport = FakeTransport(statusResponse("{}"))
        val diagnostics = BridgeDiagnostics(transport)
        diagnostics.status()
        diagnostics.environment()
        assertEquals(listOf(RolePolicy.METHOD_STATUS, RolePolicy.METHOD_DIAGNOSE), transport.methods)
        assertTrue(transport.methods.all { it.isNotEmpty() })
    }

    @Test
    fun liveFactsProbeNeverInventsTheDialerRole() = runBlocking {
        // true/false 按原值透传；不可用或字段缺失一律 null（就绪判定据此保持未就绪）。
        val defaultRole = BridgeLiveFactsProbe {
            BridgeRead.Available(BridgeStatusFacts(dialerDefaultRole = true))
        }
        assertEquals(true, defaultRole.isDefault())

        val notDefault = BridgeLiveFactsProbe {
            BridgeRead.Available(BridgeStatusFacts(dialerDefaultRole = false))
        }
        assertEquals(false, notDefault.isDefault())

        val missingField = BridgeLiveFactsProbe { BridgeRead.Available(BridgeStatusFacts()) }
        assertEquals(null, missingField.isDefault())

        // status 不可用（旧桥接/角色表不匹配）同样不得猜成“默认拨号器”。
        val forbidden = BridgeLiveFactsProbe { BridgeRead.Unavailable("forbidden") }
        assertEquals(null, forbidden.isDefault())
        assertEquals(HookPresence.UNKNOWN, forbidden.presence())
    }

    private fun statusJson(hookConnected: String): String =
        "{\"bridgeVersion\":\"1.0\",\"protocolVersion\":1,\"revision\":3,\"enabled\":true," +
            "\"paired\":true,\"uptimeMs\":1,\"activeConnections\":0,\"rejectedConnections\":0," +
            "\"supervisionPhase\":\"IDLE\",\"supervisionOwned\":false,\"microphoneIsolated\":false," +
            "\"supervisionBlocked\":null,\"supervisionLastFault\":null," +
            "\"hookConnected\":" + hookConnected + "," +
            "\"dialerDefaultRole\":false}"
}
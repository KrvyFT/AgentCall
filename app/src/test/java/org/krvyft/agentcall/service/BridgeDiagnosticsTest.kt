package org.krvyft.agentcall.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.protocol.RolePolicy
import org.krvyft.agentcall.ipc.ControlTransport

/**
 * 只读诊断客户端的 JVM 测试：三态 HOOK 存在性与环境解析必须 fail closed。
 *
 * 桥接目前不把 status/diagnose 开放给 APP（RolePolicy），生产会得到 forbidden；
 * 这里证明客户端把 forbidden 表达为“不可用”，而不是在线或离线。
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

    private fun statusJson(hookConnected: String): String =
        "{\"bridgeVersion\":\"1.0\",\"protocolVersion\":1,\"revision\":3,\"enabled\":true," +
            "\"paired\":true,\"uptimeMs\":1,\"activeConnections\":0,\"rejectedConnections\":0," +
            "\"supervisionPhase\":\"IDLE\",\"supervisionOwned\":false,\"microphoneIsolated\":false," +
            "\"supervisionBlocked\":null,\"supervisionLastFault\":null," +
            "\"hookConnected\":" + hookConnected + "," +
            "\"dialerDefaultRole\":false}"
}

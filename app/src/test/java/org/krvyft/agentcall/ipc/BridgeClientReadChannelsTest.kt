package org.krvyft.agentcall.ipc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RpcError
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * APP 只读通道（Task 7C 线格式）的客户端 JVM 测试（Task 7B3）。
 *
 * 负载逐字来自 7C 文档的 status/diagnose/session.events 示例；测试只断言线上事实：
 * 方法名、游标负载、机器码与解析结果。**任何错误都不许兜底成“在线/就绪/没有事件”**：
 * forbidden/internal_error/cursor_ahead 必须以机器码返回或按协议重试一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeClientReadChannelsTest {

    private val session = SessionId(processEpoch = "epoch-1", callId = "call-a")

    // ------------------------------------------------------------------ status

    @Test
    fun statusParsesTheDocumentedLiveFacts() = runTest {
        val control = FakeControlTransport { request ->
            RpcResponse(id = request.id, payload = STATUS_PAYLOAD)
        }
        val client = client(control)

        val result = client.status()

        val facts = (result as BridgeRead.Available).value
        assertEquals(true, facts.hookConnected)
        assertEquals(true, facts.dialerDefaultRole)
        assertEquals("IDLE", facts.supervisionPhase)
        assertEquals(null, facts.supervisionBlocked)
        assertEquals(null, facts.supervisionLastFault)
        assertEquals(false, facts.supervisionOwned)
        assertEquals(false, facts.microphoneIsolated)
        assertEquals(true, facts.paired)
        assertEquals(true, facts.enabled)
        assertEquals("1.0", facts.bridgeVersion)
        assertEquals("status", control.requests.single().method)
        assertNull("status must not carry a session id", control.requests.single().sessionId)
        client.close()
    }

    @Test
    fun statusUnknownValuesStayUnknownAndNeverBecomeSuccess() = runTest {
        val unknown = client(
            FakeControlTransport { request ->
                RpcResponse(
                    id = request.id,
                    payload = "{\"hookConnected\":\"unknown\",\"dialerDefaultRole\":\"unknown\"," +
                        "\"supervisionPhase\":null}",
                )
            },
        )
        val facts = (unknown.status() as BridgeRead.Available).value
        assertNull("unknown hookConnected must stay unknown", facts.hookConnected)
        assertNull("unknown dialer role must stay unknown", facts.dialerDefaultRole)
        assertNull(facts.supervisionPhase)
        unknown.close()

        // 已知字段整体缺失同样只能是 null，不能猜成 true（空负载 {} 另见下面的不可用用例）。
        val missing = client(
            FakeControlTransport { request ->
                RpcResponse(id = request.id, payload = "{\"supervisionPhase\":\"IDLE\"}")
            },
        )
        val empty = (missing.status() as BridgeRead.Available).value
        assertNull(empty.hookConnected)
        assertNull(empty.dialerDefaultRole)
        missing.close()
    }

    @Test
    fun forbiddenStatusIsAMachineCodeInsteadOfAFallback() = runTest {
        val control = FakeControlTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "method is not in the role table"))
        }
        val client = client(control)

        assertEquals(BridgeRead.Unavailable("forbidden"), client.status())
        assertEquals(1, control.requests.size)
        client.close()
    }

    @Test
    fun malformedStatusPayloadIsAMachineCode() = runTest {
        val client = client(FakeControlTransport { request -> RpcResponse(id = request.id, payload = "not json") })
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_STATUS_PAYLOAD), client.status())
        client.close()
    }

    @Test
    fun unreachableControlIsBridgeUnavailable() = runTest {
        val client = client(FakeControlTransport { null })
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_BRIDGE_UNAVAILABLE), client.status())
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_BRIDGE_UNAVAILABLE), client.diagnose())
        assertEquals(
            BridgeRead.Unavailable(BridgeClient.ERROR_BRIDGE_UNAVAILABLE),
            client.pollEvents(0L),
        )
        client.close()
    }

    // ------------------------------------------------------------------ diagnose

    @Test
    fun diagnoseParsesTheCompleteEnvironmentKey() = runTest {
        val control = FakeControlTransport { request ->
            RpcResponse(id = request.id, payload = DIAGNOSE_PAYLOAD)
        }
        val client = client(control)

        val environment = (client.diagnose() as BridgeRead.Available).value

        assertEquals(37, environment.apiLevel)
        assertEquals("fp", environment.romFingerprint)
        assertEquals("6.1", environment.kernelVersion)
        assertEquals("1", environment.dialerPackageVersion)
        assertEquals("82", environment.frameworkVersion)
        assertEquals("1.0", environment.bridgeVersion)
        assertEquals("digest", environment.audioPolicyDigest)
        assertEquals("carrier", environment.simCarrier)
        assertEquals("VoLTE", environment.transport)
        assertEquals("handset", environment.route)
        assertEquals("diagnose", control.requests.single().method)
        client.close()
    }

    @Test
    fun diagnoseWithAnIncompleteEnvironmentIsUnavailable() = runTest {
        val incomplete = client(
            FakeControlTransport { request ->
                RpcResponse(
                    id = request.id,
                    payload = "{\"environment\":{\"apiLevel\":37,\"romFingerprint\":\"fp\"}}",
                )
            },
        )
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_DIAGNOSE_PAYLOAD), incomplete.diagnose())
        incomplete.close()

        val forbidden = client(
            FakeControlTransport { request ->
                RpcResponse(id = request.id, error = RpcError("forbidden", "denied"))
            },
        )
        assertEquals(BridgeRead.Unavailable("forbidden"), forbidden.diagnose())
        forbidden.close()
    }

    // ------------------------------------------------------------------ session.events

    @Test
    fun eventsPollSendsTheCursorAndParsesTheBatch() = runTest {
        val control = FakeControlTransport { request -> RpcResponse(id = request.id, payload = EVENTS_PAYLOAD) }
        val client = client(control)

        val batch = (client.pollEvents(0L) as BridgeRead.Available).value

        assertEquals("session.events", control.requests.single().method)
        assertNull("session.events must not carry a session id", control.requests.single().sessionId)
        assertEquals("{\"sinceSeq\":0}", control.requests.single().payload)

        assertEquals(3L, batch.nextSeq)
        assertEquals(0L, batch.dropped)
        assertEquals(listOf(1L, 2L, 3L), batch.events.map { it.seq })
        val ringing = batch.events[0]
        assertEquals(session, ringing.sessionId)
        assertEquals("epoch-1", ringing.epoch)
        assertEquals(CallState.RINGING, ringing.state)
        assertEquals(Presentation.ALLOWED, ringing.presentation)
        assertEquals(CallDirection.INCOMING, ringing.direction)
        assertEquals("acct-1", ringing.phoneAccount)
        assertEquals(false, ringing.isEmergency)
        assertEquals(false, ringing.isConference)
        assertEquals(1, ringing.activeCallCount)
        assertEquals("+8613800138000", ringing.number)
        assertEquals("UNKNOWN", ringing.source)
        assertNull(ringing.handbackReason)
        assertNull(ringing.outcome)
        // 监督端事实：没有快照字段就是 null（7C 明确不补默认值）。
        val supervisor = batch.events[1]
        assertEquals("SUPERVISOR", supervisor.source)
        assertEquals("call_disconnected", supervisor.outcome)
        assertEquals(CallState.DISCONNECTED, supervisor.state)
        assertNull(supervisor.direction)
        assertNull(supervisor.presentation)
        client.close()
    }

    @Test
    fun eventsCursorAheadResetsToZeroAndRetriesExactlyOnce() = runTest {
        val control = FakeControlTransport { request ->
            when (request.payload) {
                "{\"sinceSeq\":9}" -> RpcResponse(
                    id = request.id,
                    error = RpcError(BridgeClient.ERROR_CURSOR_AHEAD, "cursor is ahead"),
                )
                else -> RpcResponse(id = request.id, payload = EVENTS_PAYLOAD)
            }
        }
        val client = client(control)

        val batch = client.pollEvents(9L)

        assertTrue("cursor_ahead must reset to 0 and read once more", batch is BridgeRead.Available)
        assertEquals(
            listOf("{\"sinceSeq\":9}", "{\"sinceSeq\":0}"),
            control.requests.map { it.payload },
        )
        client.close()
    }

    @Test
    fun eventsCursorAheadAtZeroIsAMachineCodeAndNeverLoops() = runTest {
        val control = FakeControlTransport { request ->
            RpcResponse(id = request.id, error = RpcError(BridgeClient.ERROR_CURSOR_AHEAD, "ahead"))
        }
        val client = client(control)

        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_CURSOR_AHEAD), client.pollEvents(0L))
        assertEquals("no retry when the cursor is already 0", 1, control.requests.size)
        client.close()
    }

    @Test
    fun eventsForbiddenIsAMachineCodeWithoutRetry() = runTest {
        val control = FakeControlTransport { request ->
            RpcResponse(id = request.id, error = RpcError("forbidden", "denied"))
        }
        val client = client(control)

        assertEquals(BridgeRead.Unavailable("forbidden"), client.pollEvents(3L))
        assertEquals(1, control.requests.size)
        client.close()
    }

    @Test
    fun malformedEventsPayloadIsAMachineCodeAndProducesNoEvents() = runTest {
        val notAnArray = client(
            FakeControlTransport { request ->
                RpcResponse(id = request.id, payload = "{\"events\":{},\"nextSeq\":0,\"dropped\":0}")
            },
        )
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_EVENTS_PAYLOAD), notAnArray.pollEvents(0L))
        notAnArray.close()

        // 缺少 nextSeq/dropped 的批次是整体非法的（游标不可信）。
        val badBatch = client(
            FakeControlTransport { request ->
                RpcResponse(id = request.id, payload = "{\"events\":[],\"nextSeq\":1}")
            },
        )
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_EVENTS_PAYLOAD), badBatch.pollEvents(0L))
        badBatch.close()
    }

    @Test
    fun negativeCursorIsRejectedBeforeAnyRequest() = runTest {
        val control = FakeControlTransport { request -> RpcResponse(id = request.id, payload = EVENTS_PAYLOAD) }
        val client = client(control)
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_EVENTS_PAYLOAD), client.pollEvents(-1L))
        assertTrue("an invalid cursor must never reach the bridge", control.requests.isEmpty())
        client.close()
    }

    // ------------------------------------------- 桥接进程代次（7B3 FIX ROUND 1 / CRITICAL）

    /**
     * status.uptimeMs 是**进程重启信标**：它此前被解析后丢弃，于是桥接重启后 APP 既收不到
     * cursor_ahead（新进程的 latest 可能已超过旧游标），也没有任何其它信号——新进程的前
     * lastSeq 条事件（通常包含新来电的 RINGING）被静默跳过。这里只锁定“信标被如实读出”，
     * 重启判定与基线重置在 SessionEventPollerTest 里用假桥接验证。
     */
    @Test
    fun statusSurfacesTheBridgeUptimeBeaconAndRejectsAnEmptyPayload() = runTest {
        val control = FakeControlTransport { request ->
            RpcResponse(id = request.id, payload = STATUS_PAYLOAD)
        }
        val client = client(control)
        assertEquals(1L, (client.status() as BridgeRead.Available).value.uptimeMs)
        assertEquals(BridgeRead.Available(1L), client.uptimeMs())
        client.close()

        // 空负载 {} 不是“status 可用，字段恰好缺失”：它必须整体不可用（statusAvailable=false）。
        val empty = client(FakeControlTransport { request -> RpcResponse(id = request.id, payload = "{}") })
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_STATUS_PAYLOAD), empty.status())
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_STATUS_PAYLOAD), empty.uptimeMs())
        empty.close()

        // 只有未知键的负载同样不是 status。
        val unknown = client(
            FakeControlTransport { request -> RpcResponse(id = request.id, payload = "{\"nope\":1}") },
        )
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_STATUS_PAYLOAD), unknown.status())
        unknown.close()
    }

    @Test
    fun aMissingUptimeBeaconIsUnavailableInsteadOfZero() = runTest {
        val client = client(
            FakeControlTransport { request ->
                RpcResponse(id = request.id, payload = "{\"supervisionPhase\":\"IDLE\"}")
            },
        )
        assertEquals(BridgeRead.Unavailable(BridgeClient.ERROR_STATUS_PAYLOAD), client.uptimeMs())
        client.close()
    }

    // ------------------------------------------- 批次容错（7B3 FIX ROUND 1 / minors）

    /**
     * 一条结构非法的事件（缺 callId/seq）只丢弃它自己并计数：绝不因此拒绝整批——否则一条
     * 坏事件会让同一批里的真实 RINGING 一起消失（并让轮询器反复读到同一批）。
     */
    @Test
    fun oneStructurallyInvalidEventIsSkippedAndCountedInsteadOfRejectingTheBatch() = runTest {
        val payload = "{\"events\":[" +
            "{\"seq\":1,\"sessionId\":{\"processEpoch\":\"epoch-1\",\"callId\":\"call-a\"}," +
            "\"state\":\"RINGING\",\"presentation\":\"ALLOWED\",\"direction\":\"INCOMING\"," +
            "\"phoneAccount\":\"acct-1\",\"isEmergency\":false,\"isConference\":false," +
            "\"activeCallCount\":1,\"number\":\"+8613800138000\",\"source\":\"UNKNOWN\"}," +
            "{\"seq\":2,\"sessionId\":{\"processEpoch\":\"epoch-1\"},\"state\":\"RINGING\"}," +
            "{\"seq\":3,\"sessionId\":{\"processEpoch\":\"epoch-1\",\"callId\":\"call-c\"}," +
            "\"state\":\"RINGING\"}" +
            "],\"nextSeq\":3,\"dropped\":0}"
        val control = FakeControlTransport { request -> RpcResponse(id = request.id, payload = payload) }
        val client = client(control)

        val batch = (client.pollEvents(0L) as BridgeRead.Available).value

        assertEquals(listOf(1L, 3L), batch.events.map { it.seq })
        assertEquals("the skipped event must be counted", 1, batch.skipped)
        assertEquals(1, client.invalidEventCount)
        client.close()
    }

    /** 会话 token 上界必须与 core 的 RpcCodec.MAX_SESSION_FIELD_CHARS 一致（64）。 */
    @Test
    fun sessionTokensLongerThanTheCoreLimitAreStructurallyInvalid() = runTest {
        assertEquals(64, BridgeClient.MAX_SESSION_TOKEN_CHARS)
        assertEquals(
            org.krvyft.agentcall.core.protocol.RpcCodec.MAX_SESSION_FIELD_CHARS,
            BridgeClient.MAX_SESSION_TOKEN_CHARS,
        )
        val tooLong = "e".repeat(65)
        val payload = "{\"events\":[{\"seq\":1,\"sessionId\":{\"processEpoch\":\"" + tooLong +
            "\",\"callId\":\"call-a\"},\"state\":\"RINGING\"}],\"nextSeq\":1,\"dropped\":0}"
        val client = client(FakeControlTransport { request -> RpcResponse(id = request.id, payload = payload) })
        val batch = (client.pollEvents(0L) as BridgeRead.Available).value
        assertEquals("an over-long token is a skipped event, not a fabricated one", emptyList<Long>(), batch.events.map { it.seq })
        assertEquals(1, batch.skipped)
        client.close()
    }

    /**
     * cursor_ahead 的 0 游标重读必须把“这是另一个进程的事件”告知调用方：轮询器要先清空
     * 本地基线（否则重读回来的 seq 1..lastSeq 会被当成重复而丢弃）。
     */
    @Test
    fun eventsCursorAheadMarksTheRetriedBatchAsAReset() = runTest {
        val control = FakeControlTransport { request ->
            when (request.payload) {
                "{\"sinceSeq\":9}" -> RpcResponse(
                    id = request.id,
                    error = RpcError(BridgeClient.ERROR_CURSOR_AHEAD, "cursor is ahead"),
                )
                else -> RpcResponse(id = request.id, payload = EVENTS_PAYLOAD)
            }
        }
        val client = client(control)

        val batch = (client.pollEvents(9L) as BridgeRead.Available).value

        assertTrue("the retried batch must tell the poller to clear its baseline", batch.reset)
        assertEquals(listOf(1L, 2L, 3L), batch.events.map { it.seq })
        client.close()
    }

    // ------------------------------------------------------------------ 日志

    @Test
    fun readChannelsLogOnlyMachineCodesNeverNumbersOrSessionTokens() = runTest {
        val logs = mutableListOf<String>()
        val control = FakeControlTransport { request ->
            when (request.method) {
                "session.events" -> RpcResponse(id = request.id, payload = EVENTS_PAYLOAD)
                "status" -> RpcResponse(id = request.id, payload = STATUS_PAYLOAD)
                else -> RpcResponse(id = request.id, error = RpcError("internal_error", "boom"))
            }
        }
        val client = BridgeClient(
            control = control,
            pcm = { null },
            scope = this,
            clock = MonotonicClock { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = { logs += it },
        )

        client.status()
        client.diagnose()
        client.pollEvents(0L)

        for (log in logs) {
            assertTrue("log must not contain the phone number: " + log, !log.contains("+8613800138000"))
            assertTrue("log must not contain a call id: " + log, !log.contains("call-a"))
            assertTrue("log must not contain a process epoch: " + log, !log.contains("epoch-1"))
            assertTrue("log must not contain a secret: " + log, !log.contains("sk-"))
        }
        client.close()
    }

    // ------------------------------------------------------------------ 装配/负载

    private fun TestScope.client(control: ControlTransport): BridgeClient = BridgeClient(
        control = control,
        pcm = { null },
        scope = this,
        clock = MonotonicClock { testScheduler.currentTime },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeControlTransport(
        private val handler: (RpcRequest) -> RpcResponse?,
    ) : ControlTransport {
        val requests = mutableListOf<RpcRequest>()

        override suspend fun request(request: RpcRequest): RpcResponse? {
            requests += request
            return handler(request)
        }

        override suspend fun close() = Unit
    }

    private companion object {
        /** Task 7C 线格式里的 status 示例（15 键，无密钥）。 */
        const val STATUS_PAYLOAD: String = "{\"bridgeVersion\":\"1.0\",\"protocolVersion\":1," +
            "\"revision\":3,\"enabled\":true,\"paired\":true,\"uptimeMs\":1," +
            "\"activeConnections\":0,\"rejectedConnections\":0," +
            "\"supervisionPhase\":\"IDLE\",\"supervisionOwned\":false," +
            "\"microphoneIsolated\":false,\"supervisionBlocked\":null," +
            "\"supervisionLastFault\":null,\"hookConnected\":true,\"dialerDefaultRole\":true}"

        /** Task 7C 线格式里的 diagnose 示例（与 CLI_ROOT 同形）。 */
        const val DIAGNOSE_PAYLOAD: String = "{\"environment\":{\"apiLevel\":37," +
            "\"romFingerprint\":\"fp\",\"kernelVersion\":\"6.1\"," +
            "\"dialerPackageVersion\":\"1\",\"frameworkVersion\":\"82\"," +
            "\"bridgeVersion\":\"1.0\",\"audioPolicyDigest\":\"digest\"," +
            "\"simCarrier\":\"carrier\",\"transport\":\"VoLTE\",\"route\":\"handset\"}," +
            "\"downlinkExtraction\":\"UNVERIFIED\",\"uplinkInjection\":\"UNVERIFIED\"," +
            "\"microphoneIsolation\":\"UNVERIFIED\",\"privilegedTermination\":\"UNVERIFIED\"," +
            "\"checkedAtMs\":1,\"notes\":\"audio:UNTESTED permissions:UNTESTED\"}"

        /** Task 7C 线格式里的 session.events 示例：两条 HOOK 事实 + 一条监督端事实。 */
        const val EVENTS_PAYLOAD: String = "{\"events\":[" +
            "{\"seq\":1,\"sessionId\":{\"processEpoch\":\"epoch-1\",\"callId\":\"call-a\"}," +
            "\"epoch\":\"epoch-1\",\"state\":\"RINGING\",\"presentation\":\"ALLOWED\"," +
            "\"direction\":\"INCOMING\",\"phoneAccount\":\"acct-1\"," +
            "\"isEmergency\":false,\"isConference\":false,\"activeCallCount\":1," +
            "\"number\":\"+8613800138000\",\"source\":\"UNKNOWN\"," +
            "\"handbackReason\":null,\"outcome\":null}," +
            "{\"seq\":2,\"sessionId\":{\"processEpoch\":\"epoch-1\",\"callId\":\"call-a\"}," +
            "\"epoch\":\"epoch-1\",\"state\":\"DISCONNECTED\",\"presentation\":null," +
            "\"direction\":null,\"phoneAccount\":null,\"isEmergency\":null," +
            "\"isConference\":null,\"activeCallCount\":null,\"number\":null," +
            "\"source\":\"SUPERVISOR\",\"handbackReason\":null," +
            "\"outcome\":\"call_disconnected\"}," +
            "{\"seq\":3,\"sessionId\":{\"processEpoch\":\"epoch-1\",\"callId\":\"call-a\"}," +
            "\"epoch\":\"epoch-1\",\"state\":\"RINGING\",\"presentation\":\"ALLOWED\"," +
            "\"direction\":\"INCOMING\",\"phoneAccount\":\"acct-1\"," +
            "\"isEmergency\":false,\"isConference\":false,\"activeCallCount\":1," +
            "\"number\":\"+8613800138001\",\"source\":\"UNKNOWN\"," +
            "\"handbackReason\":null,\"outcome\":null}]," +
            "\"nextSeq\":3,\"dropped\":0}"
    }
}

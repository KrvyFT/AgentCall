package org.krvyft.agentcall.ipc

import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * APP 角色桥接客户端的 JVM 协议测试（假控制传输 + 假 PCM lane）。
 *
 * 只断言线上可观测事实：帧类型/长度/序号、方法名与负载形状、心跳时刻、在途窗口与
 * 重连次数；不依赖 Android LocalSocket（真机未验证，记录在报告中）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeClientTest {

    private val session = SessionId(processEpoch = "epoch-1", callId = "call-a")
    private val sessionB = SessionId(processEpoch = "epoch-2", callId = "call-b")
    private val format = AudioFormatSpec()

    // ------------------------------------------------------------------ 心跳

    @Test
    fun heartbeatCadenceStaysWithinFiveSeconds() = runTest {
        val control = FakeControlTransport { request ->
            if (request.method == "session.heartbeat") {
                RpcResponse(id = request.id, payload = "{\"acknowledged\":true}")
            } else {
                null
            }
        }
        control.now = { testScheduler.currentTime }
        val client = client(control = control, pcm = FakePcmLaneTransport())

        val pump = launch { client.heartbeatWhileLive(session) }
        advanceTimeBy(11_000)
        runCurrent()
        pump.cancel()

        assertTrue("at least three heartbeats in 11 s", control.times.size >= 3)
        val gaps = control.times.zipWithNext { previous, next -> next - previous }
        assertTrue("every heartbeat gap must be <= 5000 ms, got " + gaps, gaps.all { it <= 5_000L })
        assertTrue(control.requests.all { it.method == "session.heartbeat" })
        assertTrue(control.requests.all { it.sessionId == session })

        client.close()
    }

    // ------------------------------------------------------------------ 命令

    @Test
    fun sessionCommandPayloadShapeAndAnswerRuling() = runTest {
        val control = FakeControlTransport { request ->
            when (request.method) {
                "session.request" -> RpcResponse(id = request.id, payload = "{\"outcome\":\"accepted\"}")
                "session.command" -> RpcResponse(id = request.id, payload = "{\"outcome\":\"accepted\"}")
                else -> null
            }
        }
        val client = client(control = control, pcm = FakePcmLaneTransport())

        assertEquals(CommandResult.Accepted, client.send(session, CallCommand.Answer))
        assertEquals(CommandResult.Accepted, client.send(session, CallCommand.Hangup))

        assertEquals(2, control.requests.size)

        // 裁决：answer 只走 session.request（监督端授权/取租约），绝不作为 session.command 发送。
        val answer = control.requests[0]
        assertEquals("session.request", answer.method)
        assertEquals(session, answer.sessionId)

        val hangup = control.requests[1]
        assertEquals("session.command", hangup.method)
        assertEquals(session, hangup.sessionId)
        assertEquals("{\"command\":\"hangup\"}", hangup.payload)

        assertTrue(
            "answer must never appear on session.command",
            control.requests.none { it.method == "session.command" && it.payload.contains("answer") },
        )

        client.close()
    }

    // ------------------------------------------------------------------ audio.channel + 握手成帧

    @Test
    fun audioChannelThenPcmHandshakeFraming() = runTest {
        val control = audioChannelControl()
        val pcm = FakePcmLaneTransport()
        pcm.onConnect = { lane -> lane.feed(openOkFrame()) }
        val client = client(control = control, pcm = pcm)

        val audio = client.open(session, format)

        assertEquals(PCM_SOCKET, pcm.lastSocketName)
        val channelRequest = control.requests.single()
        assertEquals("audio.channel", channelRequest.method)
        assertEquals(session, channelRequest.sessionId)
        val channelJson = JsonParser.parseString(channelRequest.payload).asJsonObject
        val requestedFormat = channelJson.getAsJsonObject("format")
        assertEquals(16000, requestedFormat.get("sampleRate").asInt)
        assertEquals(1, requestedFormat.get("channels").asInt)
        assertEquals("PCM16", requestedFormat.get("encoding").asString)

        // PCM lane 的第一帧必须是 open 握手，且重复同一 SessionId/格式。
        val lane = pcm.connections.single()
        assertEquals(1, lane.writes.size)
        val (handshakeType, handshakeBody) = parseFrame(lane.writes[0])
        assertEquals(TYPE_CONTROL, handshakeType)
        val openJson = JsonParser.parseString(String(handshakeBody, StandardCharsets.UTF_8)).asJsonObject
        assertEquals("open", openJson.get("type").asString)
        assertEquals(session.processEpoch, openJson.getAsJsonObject("sessionId").get("processEpoch").asString)
        assertEquals(session.callId, openJson.getAsJsonObject("sessionId").get("callId").asString)
        val handshakeFormat = openJson.getAsJsonObject("format")
        assertEquals(16000, handshakeFormat.get("sampleRate").asInt)
        assertEquals(1, handshakeFormat.get("channels").asInt)
        assertEquals("PCM16", handshakeFormat.get("encoding").asString)

        // 下行 0x02 → PcmChunk；正文就是原始 PCM，不经过 JSON。
        val received = mutableListOf<ByteArray>()
        val collector = launch { audio.downlink.collect { received += it.data } }
        val downlinkPcm = byteArrayOf(1, 2, 3, 4)
        lane.feed(frame(TYPE_DOWNLINK, downlinkPcm))
        runCurrent()
        assertEquals(1, received.size)
        assertArrayEquals(downlinkPcm, received.single())

        audio.stop()
        runCurrent()
        collector.cancel()
        client.close()
    }

    // ------------------------------------------------------------------ ACK 只在播放完成之后

    @Test
    fun uplinkWaitsForPlaybackAck() = runTest {
        val pcm = FakePcmLaneTransport()
        pcm.onConnect = { lane -> lane.feed(openOkFrame()) }
        val client = client(control = audioChannelControl(), pcm = pcm)
        val audio = client.open(session, format)
        val lane = pcm.connections.single()
        val pcmBytes = ByteArray(320) { (it % 97).toByte() }

        val uplink = launch { audio.writeUplink(PcmAudio(pcmBytes, format)) }
        runCurrent()
        assertFalse("writeUplink must not return before the playback ACK", uplink.isCompleted)

        val uplinkFrames = lane.writes.drop(1)
        assertEquals(1, uplinkFrames.size)
        val (type, body) = parseFrame(uplinkFrames.single())
        assertEquals(TYPE_UPLINK, type)
        assertEquals(0, intOf(body, 0))
        assertArrayEquals(pcmBytes, body.copyOfRange(4, body.size))

        lane.feed(frame(TYPE_ACK, ackJson(0).toByteArray(StandardCharsets.UTF_8)))
        runCurrent()
        assertTrue("ACK completes the playback wait", uplink.isCompleted)
        uplink.join()

        audio.stop()
        client.close()
    }

    // ------------------------------------------------------------------ 接入重试

    @Test
    fun retriesAttachWhenWorkerAnswersAudioPcmNotOpen() = runTest {
        val pcm = FakePcmLaneTransport()
        var attempt = 0
        pcm.onConnect = { lane ->
            attempt += 1
            if (attempt == 1) {
                // 工作进程还没有 OPEN 会话（ACTIVE 尚未到达）：可重试的机器码。
                lane.feed(
                    frame(
                        TYPE_CONTROL,
                        "{\"type\":\"error\",\"code\":\"audio_pcm_not_open\",\"message\":\"audio worker is not open\"}"
                            .toByteArray(StandardCharsets.UTF_8),
                    ),
                )
            } else {
                lane.feed(openOkFrame())
            }
        }
        val client = client(control = audioChannelControl(), pcm = pcm)

        client.open(session, format)

        assertEquals("attach must be retried on audio_pcm_not_open", 2, pcm.connections.size)
        assertTrue("the rejected lane is closed", pcm.connections[0].closed)
        // 两次握手都必须重复同一会话，绝不借用上一次的绑定。
        for (lane in pcm.connections) {
            val (type, body) = parseFrame(lane.writes.single())
            assertEquals(TYPE_CONTROL, type)
            val openJson = JsonParser.parseString(String(body, StandardCharsets.UTF_8)).asJsonObject
            assertEquals(session.callId, openJson.getAsJsonObject("sessionId").get("callId").asString)
        }

        client.close()
    }

    // ------------------------------------------------------------------ 不跨会话复用 lane

    @Test
    fun neverReusesThePcmLaneAcrossSessions() = runTest {
        val pcm = FakePcmLaneTransport()
        pcm.onConnect = { lane -> lane.feed(openOkFrame()) }
        val client = client(control = audioChannelControl(), pcm = pcm)

        val audioA = client.open(session, format)
        val laneA = pcm.connections[0]
        val writesOnA = laneA.writes.size

        // 打开会话 B 时必须新建一条 lane，并关闭 A 的 lane。
        val audioB = client.open(sessionB, format)
        assertEquals(2, pcm.connections.size)
        assertTrue("the lane of session A must be closed when B opens", laneA.closed)
        assertNotSame("B must run on its own lane, never A's", laneA, pcm.connections[1])

        // A 的会话句柄绝不能再写入任何 lane。
        val failure = runCatching { audioA.writeUplink(PcmAudio(ByteArray(64), format)) }.exceptionOrNull()
        assertTrue("stale session must fail with a machine code", failure is AudioLaneException)
        assertEquals("audio_lane_superseded", (failure as AudioLaneException).code)
        assertEquals("no uplink may be written to the old lane", writesOnA, laneA.writes.size)
        assertEquals("no uplink may leak into the new lane", 1, pcm.connections[1].writes.size)

        // B 的握手只携带 B 的会话。
        val (_, bodyB) = parseFrame(pcm.connections[1].writes.single())
        val openB = JsonParser.parseString(String(bodyB, StandardCharsets.UTF_8)).asJsonObject
        assertEquals(sessionB.callId, openB.getAsJsonObject("sessionId").get("callId").asString)

        audioB.stop()
        client.close()
    }

    // ------------------------------------------------------------------ 在途窗口有界

    @Test
    fun keepsInFlightUplinksWithinTheWorkerBound() = runTest {
        val pcm = FakePcmLaneTransport()
        pcm.onConnect = { lane -> lane.feed(openOkFrame()) }
        val client = client(control = audioChannelControl(), pcm = pcm)
        val audio = client.open(session, format)
        val lane = pcm.connections.single()

        val chunks = 20
        // 上行单帧正文 = 4 字节序号 + PCM，帧长（类型 + 正文）≤ 32764 → PCM 最大 32758（偶数）。
        val pcmBytes = ByteArray(UPLINK_CHUNK_BYTES * chunks) { index -> (index % 251).toByte() }
        val write = launch { audio.writeUplink(PcmAudio(pcmBytes, format)) }
        runCurrent()

        val writtenBeforeAck = lane.writes.size - 1
        assertEquals("in-flight window is 16 frames", 16, writtenBeforeAck)
        assertEquals(0, intOf(parseFrame(lane.writes[1]).second, 0))
        assertEquals(15, intOf(parseFrame(lane.writes[16]).second, 0))
        assertEquals(4 + UPLINK_CHUNK_BYTES, parseFrame(lane.writes[1]).second.size)

        // 每播放完一帧才允许再写一帧。
        for (seq in 0 until 16) {
            lane.feed(frame(TYPE_ACK, ackJson(seq).toByteArray(StandardCharsets.UTF_8)))
            runCurrent()
        }
        assertEquals(20, lane.writes.size - 1)
        assertFalse("the last four frames are still unacknowledged", write.isCompleted)

        for (seq in 16 until 20) {
            lane.feed(frame(TYPE_ACK, ackJson(seq).toByteArray(StandardCharsets.UTF_8)))
            runCurrent()
        }
        assertTrue(write.isCompleted)
        write.join()

        audio.stop()
        client.close()
    }

    // ------------------------------------------------------------------ 每方法超时预算（CRITICAL 1）

    /**
     * bridge 的 session.request / session.command 会等 HOOK 结果到 4000 ms
     * （supervisor prepareAnswer 与 APP_COMMAND_TIMEOUT_MS 同源），客户端超时必须严格大于它：
     * 否则“慢但成功”的 Answer 会被判成 bridge_unavailable，协调器随即交还一通已经被接起的电话。
     */
    @Test
    fun sessionTimeoutsOutliveTheBridgeFourSecondWindow() {
        val answer = ControlTimeouts.timeoutMsFor("session.request")
        val command = ControlTimeouts.timeoutMsFor("session.command")
        val handover = ControlTimeouts.timeoutMsFor("handover")
        val bridgeWindow = ControlTimeouts.BRIDGE_COMMAND_WAIT_MS
        assertTrue(
            "session.request timeout must exceed the bridge's " + bridgeWindow + " ms window, was " + answer,
            answer >= bridgeWindow + 2_000,
        )
        assertTrue(
            "session.command timeout must exceed the bridge's " + bridgeWindow + " ms window, was " + command,
            command >= bridgeWindow + 2_000,
        )
        assertTrue(
            "handover timeout must exceed the bridge's " + bridgeWindow + " ms window, was " + handover,
            handover >= bridgeWindow + 2_000,
        )
        assertTrue(
            "audio.channel is a session operation too, was " + ControlTimeouts.timeoutMsFor("audio.channel"),
            ControlTimeouts.timeoutMsFor("audio.channel") >= 6_000,
        )
        assertTrue(
            "the long budget must not be an unbounded wait",
            answer <= ControlTimeouts.MAX_LONG_TIMEOUT_MS,
        )
    }

    /** 心跳/配置读取是本地的快速方法：短超时，失败要立刻回到调用方。 */
    @Test
    fun heartbeatAndConfigKeepShortTimeouts() {
        assertTrue(ControlTimeouts.timeoutMsFor("session.heartbeat") <= 2_000)
        assertTrue(ControlTimeouts.timeoutMsFor("config.read") <= 2_000)
        assertTrue(ControlTimeouts.timeoutMsFor("config.secret.read") <= 2_000)
        // 安全默认：未知方法按短超时失败，不占用控制通道。
        assertTrue(ControlTimeouts.timeoutMsFor("hook.snapshot") <= 2_000)
    }

    /** HOOK 3 s 后才回 Accepted：bridge 尚未超时，客户端也不得提前判成传输失败。 */
    @Test
    fun slowButSuccessfulAnswerIsNotATransportFailure() = runTest {
        val control = FakeControlTransport { request ->
            when (request.method) {
                "session.request" -> RpcResponse(id = request.id, payload = "{\"outcome\":\"accepted\"}")
                "session.command" -> RpcResponse(id = request.id, payload = "{\"outcome\":\"accepted\"}")
                else -> null
            }
        }
        control.delayMs = { request -> if (request.method == "session.request") 3_000L else 0L }
        val client = client(control = control, pcm = FakePcmLaneTransport())

        val started = testScheduler.currentTime
        assertEquals(CommandResult.Accepted, client.send(session, CallCommand.Answer))
        val elapsed = testScheduler.currentTime - started
        assertTrue("the slow answer must really have waited, was " + elapsed + " ms", elapsed >= 3_000)
        assertTrue("a 3 s answer must stay inside the request budget, was " + elapsed + " ms", elapsed < 8_000)
        assertEquals(CommandResult.Accepted, client.send(session, CallCommand.Hangup))

        client.close()
    }

    /** 对端永不回答：请求必须在每方法预算内 fail closed，不能挂住协调器。 */
    @Test
    fun stalledControlRequestFailsClosedWithinItsBudget() = runTest {
        val control = FakeControlTransport { null }
        control.delayMs = { 60_000L }
        val client = client(control = control, pcm = FakePcmLaneTransport())

        val started = testScheduler.currentTime
        val result = client.send(session, CallCommand.Answer)
        val elapsed = testScheduler.currentTime - started

        assertEquals(CommandResult.Failed("bridge_unavailable", "bridge control channel is unavailable"), result)
        assertTrue("a stalled answer must be bounded by its budget, was " + elapsed + " ms", elapsed in 6_000..12_000)

        client.close()
    }

    // ------------------------------------------------------------------ PCM 握手/连接有界（IMPORTANT 2）

    /**
     * 生产 PcmLaneConnection 以 soTimeout=0 阻塞读；对端在 open_ok 前停住时，
     * attach 必须按握手预算失败并关闭这条 lane，而不是永远占住唯一的消费者。
     */
    @Test
    fun stalledPcmHandshakeIsBoundedAndClosed() = runTest {
        val pcm = FakePcmLaneTransport() // onConnect 什么都不发：open_ok 永不到达
        val client = client(control = audioChannelControl(), pcm = pcm)

        val started = testScheduler.currentTime
        val failure = runCatching { client.open(session, format) }.exceptionOrNull()
        val elapsed = testScheduler.currentTime - started

        assertTrue("a stalled handshake must fail with a machine code", failure is AudioLaneException)
        assertEquals(BridgeClient.ERROR_HANDSHAKE_TIMEOUT, (failure as AudioLaneException).code)
        assertTrue("bounded by the handshake budget, was " + elapsed + " ms", elapsed in 6_000..8_000)
        assertTrue("the stalled lane must be closed", pcm.connections.single().closed)

        client.close()
    }

    /** 连接 PCM lane 本身也可能挂住（abstract socket 对端不 accept）；连接必须同样有界。 */
    @Test
    fun stalledPcmConnectIsBounded() = runTest {
        val pcm = FakePcmLaneTransport()
        pcm.stallOpen = true
        val client = client(control = audioChannelControl(), pcm = pcm)

        val started = testScheduler.currentTime
        val failure = runCatching { client.open(session, format) }.exceptionOrNull()
        val elapsed = testScheduler.currentTime - started

        assertTrue(failure is AudioLaneException)
        assertEquals(BridgeClient.ERROR_CONNECT_TIMEOUT, (failure as AudioLaneException).code)
        assertTrue("bounded by the connect budget, was " + elapsed + " ms", elapsed in 5_000..7_000)
        assertTrue("no lane may be recorded for a failed connect", pcm.connections.isEmpty())

        client.close()
    }

    /**
     * bridge 的接入期限是 10000 ms：重试期间必须重发同一格式的 audio.channel 刷新期限，
     * 否则“工作进程稍后才 OPEN”的正常窗口会在最后几次重试里被判成 audio_pcm_bind_expired。
     */
    @Test
    fun attachRetriesRefreshTheBridgeAttachDeadline() = runTest {
        val control = audioChannelControl()
        val pcm = FakePcmLaneTransport()
        var attempt = 0
        pcm.onConnect = { lane ->
            attempt += 1
            if (attempt <= 10) {
                lane.feed(
                    frame(
                        TYPE_CONTROL,
                        "{\"type\":\"error\",\"code\":\"audio_pcm_not_open\"}".toByteArray(StandardCharsets.UTF_8),
                    ),
                )
            } else {
                lane.feed(openOkFrame())
            }
        }
        val client = BridgeClient(
            control = control,
            pcm = pcm,
            scope = this,
            clock = MonotonicClock { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            heartbeatIntervalMs = 2_000,
            ackTimeoutMs = 5_000,
            attachRetryAttempts = 12,
            attachRetryDelayMs = 50,
        )

        client.open(session, format)

        val channelRequests = control.requests.filter { it.method == "audio.channel" }
        assertEquals("retrying must refresh the 10 s bridge attach deadline", 2, channelRequests.size)
        assertEquals("a refresh must repeat the bound format", channelRequests[0].payload, channelRequests[1].payload)
        assertEquals(11, pcm.connections.size)
        assertTrue(client.protocolViolationCount == 0L)

        client.close()
    }

    // ------------------------------------------------------------------ 先验证新 lane 再关旧 lane（minor）

    /** 打开新会话失败时，仍活着的旧 lane 不能被提前拆掉：当前这通电话还要继续用音频。 */
    @Test
    fun failedAttachKeepsTheLiveLane() = runTest {
        val pcm = FakePcmLaneTransport()
        pcm.onConnect = { lane -> lane.feed(openOkFrame()) }
        val client = client(control = audioChannelControl(), pcm = pcm)

        val audioA = client.open(session, format)
        val laneA = pcm.connections[0]

        pcm.refuseOpen = true
        val failure = runCatching { client.open(sessionB, format) }.exceptionOrNull()
        assertTrue(failure is AudioLaneException)
        assertEquals(BridgeClient.ERROR_BRIDGE_UNAVAILABLE, (failure as AudioLaneException).code)
        assertFalse("a failed attach must not tear down the live lane", laneA.closed)

        // A 的 lane 仍然可用：上行照旧落到 A 的 socket。
        pcm.refuseOpen = false
        val write = launch { audioA.writeUplink(PcmAudio(ByteArray(64), format)) }
        runCurrent()
        assertEquals("the live lane still carries uplinks", 2, laneA.writes.size)
        laneA.feed(frame(TYPE_ACK, ackJson(0).toByteArray(StandardCharsets.UTF_8)))
        runCurrent()
        assertTrue(write.isCompleted)

        audioA.stop()
        client.close()
    }

    // ------------------------------------------------------------------ 心跳（minor 加强）

    /** 单次心跳耗时 1500 ms 时，发送起点之间的间隔仍必须 ≤5000 ms。 */
    @Test
    fun heartbeatCadenceSurvivesSlowControlRequests() = runTest {
        val control = FakeControlTransport { request ->
            if (request.method == "session.heartbeat") {
                RpcResponse(id = request.id, payload = "{\"acknowledged\":true}")
            } else {
                null
            }
        }
        control.now = { testScheduler.currentTime }
        control.delayMs = { 1_500L }
        val client = client(control = control, pcm = FakePcmLaneTransport())

        val pump = launch { client.heartbeatWhileLive(session) }
        advanceTimeBy(12_000)
        runCurrent()
        pump.cancel()

        assertTrue("at least three heartbeats in 12 s, got " + control.times.size, control.times.size >= 3)
        val gaps = control.times.zipWithNext { previous, next -> next - previous }
        assertTrue("every heartbeat gap must be <= 5000 ms, got " + gaps, gaps.all { it <= 5_000L })
        assertTrue("a slow request must not reset the failure counter", client.heartbeatFailureCount == 0L)

        client.close()
    }

    /** 5000 ms 及以上意味着监督端会先把 APP 判为失联，构造期就必须拒绝。 */
    @Test
    fun heartbeatIntervalMustBeStrictlyBelowTheSupervisorTimeout() {
        fun build(interval: Long) = runCatching {
            BridgeClient(
                control = FakeControlTransport { null },
                pcm = FakePcmLaneTransport(),
                scope = TestScope(),
                heartbeatIntervalMs = interval,
            )
        }

        assertTrue("4999 ms is a valid interval", build(4_999L).isSuccess)
        assertTrue("5000 ms must be rejected", build(5_000L).exceptionOrNull() is IllegalArgumentException)
        assertTrue("0 ms must be rejected", build(0L).exceptionOrNull() is IllegalArgumentException)
    }

    // ------------------------------------------------------------------ 装配/辅助

    private fun TestScope.client(control: ControlTransport, pcm: PcmLaneTransport): BridgeClient =
        BridgeClient(
            control = control,
            pcm = pcm,
            scope = this,
            clock = MonotonicClock { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            heartbeatIntervalMs = 2_000,
            ackTimeoutMs = 5_000,
            attachRetryAttempts = 3,
            attachRetryDelayMs = 50,
        )

    private fun audioChannelControl(): FakeControlTransport = FakeControlTransport { request ->
        when (request.method) {
            "audio.channel" -> RpcResponse(
                id = request.id,
                payload = "{\"opened\":true,\"socket\":\"" + PCM_SOCKET + "\"," +
                    "\"format\":{\"sampleRate\":16000,\"channels\":1,\"encoding\":\"PCM16\"}}",
            )
            else -> null
        }
    }

    private fun openOkFrame(): ByteArray = frame(
        TYPE_CONTROL,
        "{\"type\":\"open_ok\",\"format\":{\"sampleRate\":16000,\"channels\":1,\"encoding\":\"PCM16\"}}"
            .toByteArray(StandardCharsets.UTF_8),
    )

    private fun ackJson(seq: Int): String = "{\"type\":\"ack\",\"seq\":" + seq + ",\"ok\":true}"

    private fun frame(type: Int, body: ByteArray): ByteArray {
        val length = body.size + 1
        val header = byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
            type.toByte(),
        )
        return header + body
    }

    private fun parseFrame(bytes: ByteArray): Pair<Int, ByteArray> {
        assertTrue("frame must carry a 4-byte length", bytes.size >= 5)
        val length = intOf(bytes, 0)
        assertEquals("declared length covers type + body", bytes.size - 4, length)
        return (bytes[4].toInt() and 0xFF) to bytes.copyOfRange(5, bytes.size)
    }

    private fun intOf(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private class FakeControlTransport(
        private val handler: (RpcRequest) -> RpcResponse?,
    ) : ControlTransport {
        val requests = mutableListOf<RpcRequest>()
        val times = mutableListOf<Long>()
        var now: () -> Long = { 0L }

        /** 每次请求的模拟耗时（ms）；用来验证慢但成功的响应与心跳节奏。 */
        var delayMs: (RpcRequest) -> Long = { 0L }

        override suspend fun request(request: RpcRequest): RpcResponse? {
            requests += request
            times += now()
            val wait = delayMs(request)
            if (wait > 0) delay(wait)
            return handler(request)
        }

        override suspend fun close() = Unit
    }

    private class FakePcmLaneTransport : PcmLaneTransport {
        val connections = mutableListOf<FakeLaneConnection>()
        var lastSocketName: String? = null
        var onConnect: (FakeLaneConnection) -> Unit = {}

        /** 连接失败（对端未起）：open 返回 null，调用方 fail closed。 */
        var refuseOpen = false

        /** 连接挂住（abstract socket 不 accept）：open 永不返回。 */
        var stallOpen = false

        override suspend fun open(socketName: String): PcmLaneConnection? {
            if (stallOpen) awaitCancellation()
            if (refuseOpen) return null
            lastSocketName = socketName
            val connection = FakeLaneConnection()
            connections += connection
            onConnect(connection)
            return connection
        }
    }

    private class FakeLaneConnection : PcmLaneConnection {
        val writes = mutableListOf<ByteArray>()
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var pending = ByteArray(0)

        @Volatile
        var closed = false

        override suspend fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        override suspend fun readExact(length: Int): ByteArray? {
            while (pending.size < length) {
                val next = incoming.receiveCatching().getOrNull() ?: return null
                pending += next
            }
            val out = pending.copyOfRange(0, length)
            pending = pending.copyOfRange(length, pending.size)
            return out
        }

        override suspend fun close() {
            closed = true
            incoming.close()
        }

        fun feed(bytes: ByteArray) {
            incoming.trySend(bytes)
        }
    }

    private companion object {
        const val PCM_SOCKET = "org.krvyft.agentcall.pcm"
        const val TYPE_CONTROL = 0x01
        const val TYPE_DOWNLINK = 0x02
        const val TYPE_UPLINK = 0x03
        const val TYPE_ACK = 0x04

        /** 上行单帧 PCM 上限：帧长 32764 - 1 字节类型 - 4 字节序号，向下取偶数。 */
        const val UPLINK_CHUNK_BYTES = 32_758
    }
}

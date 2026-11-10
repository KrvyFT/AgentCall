package org.krvyft.agentcall.bridge.ipc

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.ControlDispatcher
import org.krvyft.agentcall.bridge.config.ConfigStore
import org.krvyft.agentcall.bridge.diagnostics.AudioApiProbe
import org.krvyft.agentcall.bridge.diagnostics.DeviceProbe
import org.krvyft.agentcall.bridge.diagnostics.PlatformFacts
import org.krvyft.agentcall.bridge.supervision.SessionEventBus
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcPort
import org.krvyft.agentcall.bridge.supervision.SupervisionRpcStatus
import org.krvyft.agentcall.bridge.supervision.TakeoverOutcome
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.ConfigPatch
import org.krvyft.agentcall.core.model.EndpointKind
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RpcRequest
import org.krvyft.agentcall.core.model.RpcResponse
import org.krvyft.agentcall.core.model.SecretUpdate
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.protocol.RolePolicy

/**
 * Task 7C：APP 面向的只读通道（status / diagnose / session.events）的 JVM 测试。
 *
 * 三件事必须同时成立：
 * 1. APP 的 status/diagnose 与 CLI_ROOT **完全同一份负载**（同一构建器，不可能漂移），且
 *    不含任何密钥原值、不写任何东西；
 * 2. session.events 是本进程真实事实（HOOK 快照 / 监督端交还/断开）的有界事件流，绝不
 *    为未知或伪造的会话编造事件；
 * 3. 角色隔离：HOOK/CLI_ROOT 不能读事件流，APP 不能调用 CLI 专属方法。
 */
class SessionEventsRpcTest {

    private lateinit var baseDir: File
    private lateinit var store: ConfigStore
    private val supervision = RecordingSupervision()
    private val sessionEvents = SessionEventBus()
    private val session = SessionId("epoch-1", "call-1")
    private val otherSession = SessionId("epoch-1", "call-2")

    private val appPeer = PeerIdentity(10_001, PeerRole.APP, "org.krvyft.agentcall", "00")
    private val hookPeer = PeerIdentity(10_002, PeerRole.HOOK, "com.google.android.dialer", "00")
    private val cliPeer = PeerIdentity(0, PeerRole.CLI_ROOT)

    private val packages = object : PackageIdentitySource {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signatureSha256(packageName: String): String? = null
        override fun versionName(packageName: String): String? = null
        override fun uidForPackage(packageName: String): Int? = null
    }

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("agentcall-session-events-").toFile()
        store = ConfigStore(File(baseDir, "agentcall"), clockMs = { 0L })
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun dispatcher(
        bus: SessionEventBus = sessionEvents,
        port: SupervisionRpcPort = supervision,
    ): ControlDispatcher = ControlDispatcher(
        store = store,
        packages = packages,
        probe = DeviceProbe(
            packages = packages,
            platform = object : PlatformFacts {
                override fun apiLevel(): Int = 37
                override fun romFingerprint(): String = "test"
                override fun kernelVersion(): String = "test"
                override fun frameworkVersion(): String = "test"
                override fun audioPolicyDigest(): String = "test"
            },
            audioApiProbe = object : AudioApiProbe {
                override fun visibleMethods(): Set<String> = emptySet()
            },
            clockMs = { 0L },
            bridgeVersion = "1.0",
        ),
        shutdown = CountDownLatch(1),
        supervision = port,
        startedAtMs = 0L,
        connectionStats = { 0 to 0L },
        sessionEvents = bus,
    )

    private fun appRequest(
        method: String,
        payload: String = "",
        id: String = "app-" + method,
    ): RpcRequest = RpcRequest(id = id, method = method, payload = payload)

    private fun hookSnapshot(payload: String, id: SessionId = session): RpcRequest = RpcRequest(
        id = "hook-snapshot",
        method = RolePolicy.METHOD_HOOK_SNAPSHOT,
        sessionId = id,
        payload = payload,
    )

    private fun poll(d: ControlDispatcher, sinceSeq: Long?): RpcResponse = d.handle(
        appPeer,
        appRequest(
            RolePolicy.METHOD_SESSION_EVENTS,
            if (sinceSeq == null) "" else "{\"sinceSeq\":" + sinceSeq + "}",
        ),
    )

    private fun pollJson(d: ControlDispatcher, sinceSeq: Long?): JsonObject {
        val response = poll(d, sinceSeq)
        assertNull(response.error?.code, response.error)
        return JsonParser.parseString(response.payload).asJsonObject
    }

    private fun eventsJson(d: ControlDispatcher, sinceSeq: Long? = null): List<JsonObject> {
        val array = pollJson(d, sinceSeq).getAsJsonArray("events")
        return (0 until array.size()).map { array.get(it).asJsonObject }
    }

    // ------------------------------------------------------------------ status / diagnose

    @Test
    fun appReadsTheSameStatusPayloadAsCliRoot() {
        supervision.statusValue = status()

        val fromCli = dispatcher().handle(cliPeer, appRequest(RolePolicy.METHOD_STATUS))
        val fromApp = dispatcher().handle(appPeer, appRequest(RolePolicy.METHOD_STATUS))

        assertNull(fromCli.error)
        assertNull(fromApp.error)
        // 同一份构建器的直接证据：除实时 uptimeMs 外逐字段相等（进程身份在同一进程里相同）。
        assertSamePayloadExceptUptime(fromCli.payload, fromApp.payload)

        // APK 的就绪探针按这些字段名解析（ServiceBootstrap/StatusText）：少一个就永远
        // READY 不了。这是跨模块线格式，必须显式锁住。
        val app = JsonParser.parseString(fromApp.payload).asJsonObject
        for (key in listOf(
            "bridgeVersion",
            "protocolVersion",
            "revision",
            "enabled",
            "paired",
            "uptimeMs",
            "processId",
            "activeConnections",
            "rejectedConnections",
            "supervisionPhase",
            "supervisionOwned",
            "microphoneIsolated",
            "supervisionBlocked",
            "supervisionLastFault",
            // Task 11D2a：优雅关闭的机器码（null = 未发生/干净关闭）。
            "shutdownCode",
            "hookConnected",
            "dialerDefaultRole",
            // Task 11E：校准视图内嵌（与 calibration.read 完全一致），APP 侧无需新 RPC 面。
            "calibration",
        )) {
            assertTrue("status 缺少字段 " + key, app.has(key))
        }
        assertEquals(1, app.get("protocolVersion").asInt)
        assertEquals(18, app.keySet().size)
    }

    /**
     * 重启信标必须是**每进程随机 id**，不是墙钟差：墙钟回拨会让 uptimeMs 变负（客户端
     * 判为状态负载非法 → 停摆）或变小（误判重启 → 重放 handback）。进程 id 在同一进程内
     * 恒定、与时间无关，桥接重启必然换成新值。
     */
    @Test
    fun theRestartBeaconIsAProcessIdAndNotAWallClockUptime() {
        fun statusJson(): JsonObject {
            val response = dispatcher().handle(cliPeer, appRequest(RolePolicy.METHOD_STATUS))
            assertNull(response.error)
            return JsonParser.parseString(response.payload).asJsonObject
        }

        val first = statusJson()
        val second = statusJson()
        val element = first.get("processId")
        assertTrue(
            "processId 必须是非空 JSON 字符串，实际为 " + element,
            element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString,
        )
        val id = element.asString
        assertTrue("processId 不得为空", id.isNotEmpty())
        assertTrue(
            "processId 不能是墙钟差（数字），实际为 " + id,
            !Regex("-?[0-9]+").matches(id),
        )
        assertEquals(
            "同一桥接进程内 processId 必须恒定（与时间/uptime 无关）",
            id,
            second.get("processId").asString,
        )
    }

    @Test
    fun appReadsTheSameDiagnosePayloadAsCliRoot() {
        val fromCli = dispatcher().handle(cliPeer, appRequest(RolePolicy.METHOD_DIAGNOSE))
        val fromApp = dispatcher().handle(appPeer, appRequest(RolePolicy.METHOD_DIAGNOSE))

        assertNull(fromCli.error)
        assertNull(fromApp.error)
        assertEquals(fromCli.payload, fromApp.payload)
        val json = JsonParser.parseString(fromApp.payload).asJsonObject
        val environment = json.getAsJsonObject("environment")
        for (key in listOf(
            "apiLevel",
            "romFingerprint",
            "kernelVersion",
            "dialerPackageVersion",
            "frameworkVersion",
            "bridgeVersion",
            "audioPolicyDigest",
            "simCarrier",
            "transport",
            "route",
        )) {
            assertTrue("diagnose.environment 缺少字段 " + key, environment.has(key))
        }
        assertTrue(json.has("downlinkExtraction"))
        assertTrue("diagnose 必须内嵌校准视图（含背书标记）", json.has("calibration"))
        assertFalse("diagnose 不得包含任何密钥字段", json.has("secrets"))
    }

    @Test
    fun appStatusAndDiagnoseNeverCarrySecretValuesAndNeverWriteAnything() {
        store.update(
            0L,
            ConfigPatch(secrets = mapOf(EndpointKind.ASR to SecretUpdate.Replace("sk-live-7c-do-not-leak"))),
        )
        val revisionBefore = store.read().config.revision

        val statusPayload = dispatcher().handle(appPeer, appRequest(RolePolicy.METHOD_STATUS)).payload
        val diagnosePayload = dispatcher().handle(appPeer, appRequest(RolePolicy.METHOD_DIAGNOSE)).payload

        assertFalse("status 不得包含密钥原值", statusPayload.contains("sk-live-7c-do-not-leak"))
        assertFalse("diagnose 不得包含密钥原值", diagnosePayload.contains("sk-live-7c-do-not-leak"))
        assertFalse("status 不得包含密钥段", statusPayload.contains("secrets"))
        // 只读：版本不变，密钥仍在（没有清空/改写）。
        assertEquals(revisionBefore, store.read().config.revision)
        assertEquals("sk-live-7c-do-not-leak", store.readSecret(EndpointKind.ASR))
    }

    private fun assertSamePayloadExceptUptime(cliPayload: String, appPayload: String) {
        val cli = JsonParser.parseString(cliPayload).asJsonObject
        val app = JsonParser.parseString(appPayload).asJsonObject
        assertEquals(cli.keySet(), app.keySet())
        for (key in cli.keySet()) {
            if (key == "uptimeMs") continue
            assertEquals("字段 " + key + " 必须与 CLI_ROOT 完全一致", cli.get(key), app.get(key))
        }
        assertTrue(cli.has("uptimeMs") && app.has("uptimeMs"))
    }

    // ------------------------------------------------------------------ 角色隔离

    @Test
    fun hookAndCliRootCannotReadTheAppEventFeed() {
        for (peer in listOf(hookPeer, cliPeer)) {
            assertFalse(
                peer.role.name + " must not poll the app event feed",
                RolePolicy.authorize(peer, appRequest(RolePolicy.METHOD_SESSION_EVENTS)),
            )
            // 伪造会话 ID 也不行：这个通道根本没有“会话参数”这回事。
            assertFalse(
                peer.role.name + " must not poll with a fake session id",
                RolePolicy.authorize(
                    peer,
                    RpcRequest(
                        id = "attempt",
                        method = RolePolicy.METHOD_SESSION_EVENTS,
                        sessionId = session,
                        payload = "{\"sinceSeq\":0}",
                    ),
                ),
            )
        }
        assertFalse(RolePolicy.methodsFor(PeerRole.HOOK).contains(RolePolicy.METHOD_SESSION_EVENTS))
        assertFalse(RolePolicy.methodsFor(PeerRole.CLI_ROOT).contains(RolePolicy.METHOD_SESSION_EVENTS))
    }

    @Test
    fun appCannotCallCliOnlyMethods() {
        for (method in listOf(
            RolePolicy.METHOD_CONFIG_WRITE,
            RolePolicy.METHOD_PAIR,
            RolePolicy.METHOD_SHUTDOWN,
        )) {
            assertFalse("APP 不得调用 " + method, RolePolicy.authorize(appPeer, appRequest(method)))
        }
        assertFalse(
            "APP 不得用伪造 uid 提权",
            RolePolicy.authorize(
                PeerIdentity(0, PeerRole.APP, "org.krvyft.agentcall", "00"),
                appRequest(RolePolicy.METHOD_CONFIG_WRITE),
            ),
        )
        // 三个新增读通道确实只有 APP（以及 CLI_ROOT 的既有 status/diagnose）。
        assertTrue(RolePolicy.authorize(appPeer, appRequest(RolePolicy.METHOD_STATUS)))
        assertTrue(RolePolicy.authorize(appPeer, appRequest(RolePolicy.METHOD_DIAGNOSE)))
        assertTrue(RolePolicy.authorize(appPeer, appRequest(RolePolicy.METHOD_SESSION_EVENTS)))
        assertFalse(RolePolicy.authorize(appPeer, appRequest(RolePolicy.METHOD_HOOK_SNAPSHOT, session = session)))
    }

    private fun appRequest(method: String, session: SessionId): RpcRequest =
        RpcRequest(id = "attempt", method = method, sessionId = session)

    // ------------------------------------------------------------------ HOOK → APP 事件

    @Test
    fun ringingThenDisconnectedReachesTheAppFeed() {
        val d = dispatcher()

        assertNull(
            d.handle(hookPeer, hookSnapshot(snapshotPayload(session, CallState.RINGING))).error,
        )
        assertNull(
            d.handle(hookPeer, hookSnapshot(snapshotPayload(session, CallState.DISCONNECTED))).error,
        )

        val payload = pollJson(d, 0L)
        val events = eventsJson(d, 0L)
        assertEquals(3, events.size)
        assertEquals(3L, payload.get("nextSeq").asLong)
        assertEquals(0L, payload.get("dropped").asLong)

        val ringing = events[0]
        assertEquals("RINGING", ringing.get("state").asString)
        assertEquals("epoch-1", ringing.get("epoch").asString)
        assertEquals("call-1", ringing.getAsJsonObject("sessionId").get("callId").asString)
        assertEquals("+8613800138000", ringing.get("number").asString)
        assertEquals("ALLOWED", ringing.get("presentation").asString)
        assertEquals("INCOMING", ringing.get("direction").asString)
        assertEquals("acct-1", ringing.get("phoneAccount").asString)
        assertEquals(1, ringing.get("activeCallCount").asInt)
        assertFalse(ringing.get("isEmergency").asBoolean)
        assertFalse(ringing.get("isConference").asBoolean)
        assertEquals("UNKNOWN", ringing.get("source").asString)
        assertTrue(ringing.get("handbackReason").isJsonNull)
        assertTrue(ringing.get("outcome").isJsonNull)

        assertEquals("DISCONNECTED", events[1].get("state").asString)
        // 监督端断开确认是一条独立事实（音频/隔离已释放）。
        assertEquals(SessionEventBus.SUPERVISOR_SOURCE, events[2].get("source").asString)
        assertEquals(SessionEventBus.OUTCOME_CALL_DISCONNECTED, events[2].get("outcome").asString)

        // 真实副作用与事件一致：RINGING/ACTIVE 走监督端记录，DISCONNECTED 走断开确认。
        assertEquals(
            listOf(CallState.RINGING, CallState.DISCONNECTED).map { it.name },
            supervision.snapshotStates.map { it.second?.name },
        )
        assertEquals(listOf(session), supervision.disconnects)
        assertTrue(supervision.activations.isEmpty())
    }

    @Test
    fun handbackSnapshotCarriesTheHookReasonAndTheSupervisorOutcome() {
        val d = dispatcher()
        supervision.takeoverOutcome = TakeoverOutcome.Confirmed

        assertNull(
            d.handle(
                hookPeer,
                hookSnapshot(snapshotPayload(session, CallState.ACTIVE, handbackReason = "user_unmuted")),
            ).error,
        )

        val events = eventsJson(d)
        assertEquals(2, events.size)
        // HOOK 事实原样到达：状态 + 交还原因。
        assertEquals("ACTIVE", events[0].get("state").asString)
        assertEquals("user_unmuted", events[0].get("handbackReason").asString)
        assertTrue(events[0].get("outcome").isJsonNull)
        // 监督端事实：同一原因 + 撤销/恢复结果。
        assertEquals(SessionEventBus.SUPERVISOR_SOURCE, events[1].get("source").asString)
        assertEquals("user_unmuted", events[1].get("handbackReason").asString)
        assertEquals(SessionEventBus.OUTCOME_HANDBACK_CONFIRMED, events[1].get("outcome").asString)
        assertEquals(listOf(session), supervision.takeovers)
        assertEquals(CallState.ACTIVE, events[1].get("state")?.let { CallState.valueOf(it.asString) })
    }

    @Test
    fun failedHandbackIsReportedAsFailureNotAsSuccess() {
        val bus = SessionEventBus()
        supervision.takeoverOutcome = TakeoverOutcome.RevokeFailed("lease_revoke_failed")
        val d = dispatcher(bus)

        assertNull(
            d.handle(
                hookPeer,
                hookSnapshot(snapshotPayload(session, CallState.ACTIVE, handbackReason = "route_change")),
            ).error,
        )

        val events = eventsJson(d)
        assertEquals(2, events.size)
        assertEquals(SessionEventBus.OUTCOME_HANDBACK_REVOKE_FAILED, events[1].get("outcome").asString)
        assertEquals("route_change", events[1].get("handbackReason").asString)
        // 撤销失败绝不能被写成“已交还成功”。
        for (event in events) {
            val outcome = event.get("outcome")
            if (!outcome.isJsonNull) {
                assertFalse(
                    "撤销失败不得报告为 confirmed",
                    outcome.asString == SessionEventBus.OUTCOME_HANDBACK_CONFIRMED,
                )
            }
        }
    }

    @Test
    fun malformedOrForeignSnapshotsNeverBecomeEvents() {
        val d = dispatcher()

        // 请求会话与负载会话不一致：解码拒绝，监督端与事件流都不得被推动。
        assertNull(d.handle(hookPeer, hookSnapshot(snapshotPayload(otherSession, CallState.RINGING))).error)
        // 非法状态。
        assertNull(
            d.handle(
                hookPeer,
                hookSnapshot(snapshotPayload(session, CallState.RINGING).replace("RINGING", "NOT_A_STATE")),
            ).error,
        )
        // 非 JSON / 空负载。
        assertNull(d.handle(hookPeer, hookSnapshot("not-json")).error)
        assertNull(d.handle(hookPeer, hookSnapshot("")).error)

        val payload = pollJson(d, 0L)
        assertEquals(0, payload.getAsJsonArray("events").size())
        assertEquals(0L, payload.get("nextSeq").asLong)
        assertTrue(supervision.snapshotStates.isEmpty())
        assertTrue(supervision.takeovers.isEmpty())
        assertTrue(supervision.disconnects.isEmpty())
    }

    @Test
    fun theFeedOnlyContainsSessionsThatReallyAppeared() {
        val d = dispatcher()

        // 没有任何真实快照：空事件流，nextSeq 保持 0（不编造占位事件）。
        val empty = pollJson(d, 0L)
        assertEquals(0, empty.getAsJsonArray("events").size())
        assertEquals(0L, empty.get("nextSeq").asLong)

        assertNull(
            d.handle(
                hookPeer,
                hookSnapshot(snapshotPayload(otherSession, CallState.RINGING), id = otherSession),
            ).error,
        )

        val events = eventsJson(d)
        assertEquals(1, events.size)
        assertEquals("call-2", events[0].getAsJsonObject("sessionId").get("callId").asString)
        assertFalse(events.any { it.getAsJsonObject("sessionId").get("callId").asString == "call-1" })
    }

    // ------------------------------------------------------------------ 游标

    @Test
    fun cursorSemanticsOverTheWire() {
        val bus = SessionEventBus(capacity = 4)
        val d = dispatcher(bus)
        repeat(6) { d.handle(hookPeer, hookSnapshot(snapshotPayload(session, CallState.RINGING))) }

        // 空负载 = sinceSeq 0；环形已丢 2 条，只返回仍然可用的部分，不报错。
        val fromZero = pollJson(d, null)
        assertEquals(listOf(3L, 4L, 5L, 6L), eventsJson(d, null).map { it.get("seq").asLong })
        assertEquals(6L, fromZero.get("nextSeq").asLong)
        assertEquals(2L, fromZero.get("dropped").asLong)

        // 中间游标：只返回更新的事件。
        assertEquals(listOf(5L, 6L), eventsJson(d, 4L).map { it.get("seq").asLong })

        // 最新游标：空批次，nextSeq 保持。
        val atLatest = pollJson(d, 6L)
        assertEquals(0, atLatest.getAsJsonArray("events").size())
        assertEquals(6L, atLatest.get("nextSeq").asLong)

        // 超前游标：机器可读拒绝（不是“暂时没有新事件”）。
        assertEquals("cursor_ahead", poll(d, 7L).error?.code)
        assertEquals("cursor_ahead", poll(dispatcher(SessionEventBus()), 1L).error?.code)

        // 负载边界：{} 视为默认 0；未知键/负数/小数/字符串/非对象一律 payload_invalid。
        assertNull(pollResponse(d, "{}").error)
        for (payload in listOf(
            "{\"sinceSeq\":-1}",
            "{\"sinceSeq\":1.5}",
            "{\"sinceSeq\":\"3\"}",
            "{\"sinceSeq\":null}",
            "{\"cursor\":1}",
            "{\"sinceSeq\":1,\"extra\":2}",
            "[]",
            "not-json",
            "{\"sinceSeq\":" + "1".repeat(200) + "}",
        )) {
            assertEquals(
                "payload 必须拒绝: " + payload,
                "payload_invalid",
                pollResponse(d, payload).error?.code,
            )
        }
    }

    private fun pollResponse(d: ControlDispatcher, payload: String): RpcResponse =
        d.handle(appPeer, appRequest(RolePolicy.METHOD_SESSION_EVENTS, payload))

    @Test
    fun batchIsCappedAtSixtyFourAndTheRingDropsOldest() {
        val d = dispatcher(sessionEvents)
        repeat(100) { d.handle(hookPeer, hookSnapshot(snapshotPayload(session, CallState.RINGING))) }

        val first = pollJson(d, 0L)
        assertEquals(SessionEventBus.MAX_BATCH, first.getAsJsonArray("events").size())
        assertEquals(64L, first.get("nextSeq").asLong)

        val second = pollJson(d, 64L)
        assertEquals(36, second.getAsJsonArray("events").size())
        assertEquals(100L, second.get("nextSeq").asLong)

        // 有界环：容量 8 时 12 条事件只剩最后 8 条，dropped 计数 4。
        val smallBus = SessionEventBus(capacity = 8)
        val small = dispatcher(smallBus)
        repeat(12) { small.handle(hookPeer, hookSnapshot(snapshotPayload(session, CallState.RINGING))) }
        val payload = pollJson(small, 0L)
        assertEquals(8, payload.getAsJsonArray("events").size())
        assertEquals(4L, payload.get("dropped").asLong)
        assertEquals(12L, payload.get("nextSeq").asLong)
        // 12 条记录、容量 8：环里只剩 seq 5..12，最旧的一条是被丢弃的边界。
        assertEquals(5L, payload.getAsJsonArray("events").get(0).asJsonObject.get("seq").asLong)
        assertEquals(12L, smallBus.latestSeq)
    }

    @Test
    fun pollingNeverBlocksTheHookPathOrTheControlPlane() {
        val bus = SessionEventBus(capacity = 16)
        val d = dispatcher(bus)
        val stop = AtomicBoolean(false)
        val pollFailures = AtomicInteger(0)
        val polls = AtomicInteger(0)
        val poller = Thread {
            while (!stop.get()) {
                val response = d.handle(appPeer, appRequest(RolePolicy.METHOD_SESSION_EVENTS, "{\"sinceSeq\":0}"))
                if (response.error != null) pollFailures.incrementAndGet()
                polls.incrementAndGet()
            }
        }.apply {
            isDaemon = true
            name = "agentcall-test-slow-app-poller"
            start()
        }
        try {
            val producerStartedAt = System.currentTimeMillis()
            repeat(300) {
                assertNull(d.handle(hookPeer, hookSnapshot(snapshotPayload(session, CallState.RINGING))).error)
            }
            val producerElapsed = System.currentTimeMillis() - producerStartedAt

            val controlStartedAt = System.currentTimeMillis()
            repeat(5) { assertNull(d.handle(cliPeer, appRequest(RolePolicy.METHOD_STATUS)).error) }
            val controlElapsed = System.currentTimeMillis() - controlStartedAt

            assertTrue("HOOK 事件入队被轮询阻塞: " + producerElapsed + " ms", producerElapsed < 10_000)
            assertTrue("控制面被轮询阻塞: " + controlElapsed + " ms", controlElapsed < 1_000)
            assertEquals("慢速 APP 轮询必须始终读到合法批次", 0, pollFailures.get())
            assertTrue("轮询线程必须真的跑过", polls.get() > 0)
        } finally {
            stop.set(true)
            poller.join(2_000)
        }
        // 慢消费者不会让内存增长：环固定 16 条，其余计入 dropped。
        assertEquals(300L, bus.latestSeq)
        assertEquals(284L, bus.droppedCount)
    }

    // ------------------------------------------------------------------ 辅助

    private fun snapshotPayload(
        id: SessionId,
        state: CallState,
        number: String? = "+8613800138000",
        presentation: Presentation = Presentation.ALLOWED,
        direction: CallDirection = CallDirection.INCOMING,
        phoneAccount: String? = "acct-1",
        isEmergency: Boolean = false,
        isConference: Boolean = false,
        activeCallCount: Int = 1,
        source: String = "UNKNOWN",
        handbackReason: String? = null,
    ): String {
        val json = JsonObject()
        json.addProperty("processEpoch", id.processEpoch)
        json.addProperty("callId", id.callId)
        if (number == null) json.add("number", JsonNull.INSTANCE) else json.addProperty("number", number)
        json.addProperty("state", state.name)
        json.addProperty("direction", direction.name)
        json.addProperty("presentation", presentation.name)
        if (phoneAccount == null) {
            json.add("phoneAccount", JsonNull.INSTANCE)
        } else {
            json.addProperty("phoneAccount", phoneAccount)
        }
        json.addProperty("isEmergency", isEmergency)
        json.addProperty("isConference", isConference)
        json.addProperty("activeCallCount", activeCallCount)
        json.addProperty("source", source)
        if (handbackReason == null) {
            json.add("handbackReason", JsonNull.INSTANCE)
        } else {
            json.addProperty("handbackReason", handbackReason)
        }
        return json.toString()
    }

    private fun status(): SupervisionRpcStatus = SupervisionRpcStatus(
        phase = "IDLE",
        owned = false,
        microphoneIsolated = false,
        blockedReason = null,
        lastFault = null,
        hookConnected = true,
    )

    private class RecordingSupervision : SupervisionRpcPort() {
        val snapshotStates = mutableListOf<Pair<SessionId, CallState?>>()
        val takeovers = mutableListOf<SessionId>()
        val disconnects = mutableListOf<SessionId>()
        val activations = mutableListOf<SessionId>()

        @Volatile
        var takeoverOutcome: TakeoverOutcome = TakeoverOutcome.NotOwned

        @Volatile
        var statusValue: SupervisionRpcStatus? = null

        override fun heartbeat(id: SessionId, role: PeerRole) = Unit

        override fun recordSnapshotState(id: SessionId, state: CallState?) {
            synchronized(snapshotStates) { snapshotStates += id to state }
        }

        override suspend fun onCallActive(id: SessionId) = org.krvyft.agentcall.core.model.CommandResult.Accepted

        override suspend fun onCallDisconnected(id: SessionId) {
            synchronized(disconnects) { disconnects += id }
        }

        override suspend fun onManualTakeover(id: SessionId): TakeoverOutcome {
            synchronized(takeovers) { takeovers += id }
            return takeoverOutcome
        }

        override fun status(): SupervisionRpcStatus? = statusValue
    }
}

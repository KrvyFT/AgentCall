package org.krvyft.agentcall.bridge.audio

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.krvyft.agentcall.bridge.BridgeWiring
import org.krvyft.agentcall.bridge.ipc.ControlServer
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PeerIdentity
import org.krvyft.agentcall.core.model.PeerRole
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 桥接侧 PCM 中继（APP ↔ bridge ↔ audio worker）的会话路由与有界失败测试。
 *
 * 覆盖的安全性质：
 * - 只有监督端当前持有（ownedSession）的会话才能开/接；
 * - 只能由发起 audio.channel 的同一个 APP 身份接入；
 * - 一条连接只绑一个会话：A 的帧绝不进入 B 的 worker 链路或 APP 连接；
 * - worker 链路异常断开后会话标记为 STALE，重新接入被拒绝，直到监督端显式释放；
 * - 上行队列饱和立即失败（不阻塞控制面）、越权/超长帧 fail closed。
 */
class AudioPcmRelayTest {

    private val format = AudioFormatSpec(sampleRate = 16_000)
    private val sessionA = SessionId("epoch-1", "call-A")
    private val sessionB = SessionId("epoch-1", "call-B")

    private val appPeer = PeerIdentity(
        uid = 10_001,
        role = PeerRole.APP,
        packageName = "org.krvyft.agentcall",
        signatureSha256 = "00",
    )
    private val otherAppPeer = PeerIdentity(
        uid = 10_002,
        role = PeerRole.APP,
        packageName = "org.krvyft.agentcall",
        signatureSha256 = "00",
    )

    private val clock = TestClock()
    private lateinit var owner: MutableOwner
    private lateinit var links: FakeWorkerLinkFactory
    private lateinit var relay: AudioPcmRelay
    private val attached = ArrayList<Attached>()
    private val appLaneFaults = ArrayList<Pair<SessionId, String>>()

    @Before
    fun setUp() {
        owner = MutableOwner()
        links = FakeWorkerLinkFactory(format)
        appLaneFaults.clear()
        // 生产装配点：socket 名必须来自 ControlServer 的 APP lane，不是 root 私有 worker lane。
        relay = AudioPcmRelay(
            owner = owner,
            workerLinks = links,
            clock = clock,
            socketName = BridgeWiring.pcmSocketName(),
            appSinkStallTimeoutMs = 200L,
            onAppLaneFault = { id, code -> appLaneFaults += id to code },
        )
    }

    @After
    fun tearDown() {
        relay.close()
        for (item in attached) {
            item.appInput.endInput()
            item.thread.join(1_000)
        }
    }

    // ------------------------------------------------------------------ open

    @Test
    fun openRequiresSupervisorOwnership() {
        val rejected = relay.open(sessionA, appPeer, format)
        assertTrue(rejected is PcmOpenResult.Rejected)
        assertEquals("audio_session_not_owned", (rejected as PcmOpenResult.Rejected).code)
        assertEquals(0, relay.boundSessionCount())
        assertEquals(0, links.connectAttempts.get())

        owner.own(sessionA)
        val opened = relay.open(sessionA, appPeer, format)
        assertTrue(opened is PcmOpenResult.Opened)
        // C1：APP 必须被指向 ControlServer 真正 bind 的 APP lane 字面值；
        // 共享常量会让“两边一起写成 worker lane”永远通过。
        assertEquals("org.krvyft.agentcall.pcm", (opened as PcmOpenResult.Opened).socketName)
        assertEquals(ControlServer.DEFAULT_PCM_SOCKET, opened.socketName)
        assertEquals(
            "生产装配点必须继续返回 ControlServer 真正 bind 的 APP lane 名",
            ControlServer.DEFAULT_PCM_SOCKET,
            BridgeWiring.pcmSocketName(),
        )
        assertFalse(
            "APP lane 绝不能是 root 私有的 worker lane",
            opened.socketName == AudioPcmProtocol.SOCKET_NAME,
        )
        assertEquals(format, opened.format)
    }

    @Test
    fun secondAppIdentityCannotTakeOverTheSameSession() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)

        val rejected = relay.open(sessionA, otherAppPeer, format)
        assertTrue(rejected is PcmOpenResult.Rejected)
        assertEquals("audio_session_denied", (rejected as PcmOpenResult.Rejected).code)
    }

    @Test
    fun openForAnAlreadyAttachedSessionIsRefusedWithoutTouchingTheLiveConnection() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val connection = attach(sessionA, appPeer)
        connection.awaitOpenOk()

        val rejected = relay.open(sessionA, appPeer, format)
        assertTrue(rejected is PcmOpenResult.Rejected)
        assertEquals("audio_pcm_already_attached", (rejected as PcmOpenResult.Rejected).code)
        // 活连接仍在转发。
        connection.link.fromWorker.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(1, 2)))
        connection.await("downlink") { frames ->
            frames.any { it.type == PcmLaneType.DOWNLINK && it.body.contentEquals(byteArrayOf(1, 2)) }
        }
    }

    @Test
    fun capacityIsRejectedAtTheExactLimit() {
        val bounded = (0 until AudioPcmRelay.MAX_SESSIONS).map { SessionId("epoch-1", "call-cap-" + it) }
        for (id in bounded) {
            owner.own(id)
            assertTrue(relay.open(id, appPeer, format) is PcmOpenResult.Opened)
        }
        assertEquals(AudioPcmRelay.MAX_SESSIONS, relay.boundSessionCount())

        val overflow = SessionId("epoch-1", "call-cap-overflow")
        owner.own(overflow)
        val rejected = relay.open(overflow, appPeer, format)
        assertTrue(rejected is PcmOpenResult.Rejected)
        assertEquals("audio_pcm_capacity", (rejected as PcmOpenResult.Rejected).code)
        // 有界失败：容量没有因为被拒绝而增加，也没有留下半个绑定。
        assertEquals(AudioPcmRelay.MAX_SESSIONS, relay.boundSessionCount())
        assertNull(relay.sessionState(overflow))

        // 未过期不回收：刚好等于接入期限仍算有效。
        clock.now += AudioPcmRelay.ATTACH_DEADLINE_MS
        val stillFull = relay.open(overflow, appPeer, format)
        assertEquals("audio_pcm_capacity", (stillFull as PcmOpenResult.Rejected).code)
    }

    @Test
    fun expiredBoundBindingsAreSweptButAttachedOnesSurvive() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        // A 是活连接：即使过了接入期限也必须保留。
        val attached = attach(sessionA, appPeer)
        attached.awaitOpenOk()

        owner.own(sessionB)
        assertTrue(relay.open(sessionB, appPeer, format) is PcmOpenResult.Opened)
        clock.now += AudioPcmRelay.ATTACH_DEADLINE_MS + 1

        val third = SessionId("epoch-1", "call-C")
        owner.own(third)
        assertTrue(relay.open(third, appPeer, format) is PcmOpenResult.Opened)

        // 过期的 BOUND（B）被回收；ATTACHED（A）与新绑定（C）留下。
        assertEquals(2, relay.boundSessionCount())
        assertEquals(PcmRelayState.ATTACHED, relay.sessionState(sessionA))
        assertNull(relay.sessionState(sessionB))
        assertNotNull(relay.sessionState(third))
    }

    @Test
    fun reopeningABindingWithADifferentFormatIsRejectedAndKeepsTheBoundFormat() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)

        val rejected = relay.open(sessionA, appPeer, AudioFormatSpec(sampleRate = 48_000))
        assertTrue(rejected is PcmOpenResult.Rejected)
        assertEquals("audio_pcm_format_mismatch", (rejected as PcmOpenResult.Rejected).code)

        // 绑定仍以第一次的格式为准：同格式可再次拿到 open（握手必须重复该格式）。
        val again = relay.open(sessionA, appPeer, format)
        assertTrue(again is PcmOpenResult.Opened)
        assertEquals(format, (again as PcmOpenResult.Opened).format)
    }

    @Test
    fun openFailsClosedOnceTheRelayIsClosed() {
        owner.own(sessionA)
        relay.close()

        val rejected = relay.open(sessionA, appPeer, format)
        assertTrue(rejected is PcmOpenResult.Rejected)
        assertEquals("audio_pcm_closed", (rejected as PcmOpenResult.Rejected).code)
        assertEquals(0, relay.boundSessionCount())
    }

    // ------------------------------------------------------------------ 接入

    @Test
    fun handshakeForAnUnboundSessionIsRejectedBeforeAnyWorkerLink() {
        val connection = attachRaw(sessionB, appPeer)
        connection.awaitControlError()
        assertEquals("audio_pcm_not_bound", connection.lastErrorCode)
        assertEquals(0, links.connectAttempts.get())
        connection.thread.join(1_000)
        assertFalse(connection.thread.isAlive)
    }

    @Test
    fun attachFromADifferentAppIdentityIsRejected() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)

        val connection = attachRaw(sessionA, otherAppPeer)
        connection.awaitControlError()
        assertEquals("audio_pcm_peer_mismatch", connection.lastErrorCode)
        assertEquals(0, links.connectAttempts.get())
        connection.thread.join(1_000)
        assertFalse(connection.thread.isAlive)
    }

    @Test
    fun attachAfterTheBindDeadlineIsRefused() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        clock.now += AudioPcmRelay.ATTACH_DEADLINE_MS + 1

        val connection = attachRaw(sessionA, appPeer)
        connection.awaitControlError()
        assertEquals("audio_pcm_bind_expired", connection.lastErrorCode)
        connection.thread.join(1_000)
    }

    // ------------------------------------------------------------------ 路由隔离

    @Test
    fun framesOfOneSessionNeverReachAnotherSessionsWorkerLink() {
        owner.own(sessionA)
        owner.own(sessionB)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        assertTrue(relay.open(sessionB, appPeer, format) is PcmOpenResult.Opened)
        // 顺序接入：一条连接对应一条 worker 链路，避免测试侧把链路张冠李戴。
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()
        val b = attach(sessionB, appPeer)
        b.awaitOpenOk()

        val pcm = byteArrayOf(11, 22, 33, 44)
        a.appInput.feed(frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(1, pcm)!!))
        awaitCondition("worker A receives uplink") {
            a.link.toWorker.takeFrames().any { it.type == PcmLaneType.UPLINK }
        }
        // B 的 worker 链路只收到自己的 open 握手，没有 A 的上行帧。
        b.link.toWorker.takeFrames().let { bFrames ->
            assertTrue(bFrames.none { it.type == PcmLaneType.UPLINK })
        }
        // B 的 APP 连接也没有 A 的任何下行/ack（只有自己的 open_ok 控制帧）。
        b.sync()
        assertTrue(b.frames.none { it.type != PcmLaneType.CONTROL })

        a.link.fromWorker.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(5, 6)))
        a.await("downlink to A") { frames ->
            frames.any { it.type == PcmLaneType.DOWNLINK && it.body.contentEquals(byteArrayOf(5, 6)) }
        }
        b.sync()
        assertTrue(
            "B 不得收到 A 的下行/ack: " + b.frames.filter { it.type != PcmLaneType.CONTROL } +
                " bLinkMatchesA=" + (b.link === a.link),
            b.frames.none { it.type != PcmLaneType.CONTROL },
        )
    }

    @Test
    fun downlinkEndsWhenSupervisorStopsOwningTheSession() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        owner.disown(sessionA)
        a.link.fromWorker.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(7, 8)))
        a.awaitClosedReason()
        assertEquals("session_released", a.lastClosedReason)
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
        // 不再有任何下行帧被投递。
        a.sync()
        assertTrue(a.frames.none { it.type == PcmLaneType.DOWNLINK })
    }

    @Test
    fun uplinkIsRefusedOnceOwnershipIsGone() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()
        owner.disown(sessionA)

        a.appInput.feed(frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(2, byteArrayOf(1, 2))!!))
        a.awaitControlError()
        assertEquals("audio_session_not_owned", a.lastErrorCode)
        assertTrue(a.link.toWorker.takeFrames().none { it.type == PcmLaneType.UPLINK })
        a.thread.join(2_000)
    }

    // ------------------------------------------------------------------ 有界失败

    @Test
    fun saturatedUplinkQueueFailsClosedInsteadOfBlocking() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()
        a.link.toWorker.blockFromCurrentSize()

        val startedAt = System.currentTimeMillis()
        repeat(AudioPcmRelay.UPLINK_QUEUE_CAPACITY + 4) { index ->
            a.appInput.feed(
                frameBytes(PcmLaneType.UPLINK, PcmLaneCodec.encodeUplink(index + 1, byteArrayOf(1, 2, 3, 4))!!),
            )
        }
        a.awaitControlError()
        assertEquals("audio_pcm_uplink_saturated", a.lastErrorCode)
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("饱和必须立即失败而不是无限等待: " + elapsed + " ms", elapsed < 3_000)
        a.link.toWorker.unblockWrites()
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
    }

    @Test
    fun invalidAppFrameIsAnsweredWithAMachineCodeAndTheConnectionIsClosed() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        // 帧头声明 32769 字节：超长在读取正文前失败。
        a.appInput.feed(byteArrayOf(0, 0x00, 0x80.toByte(), 0x01))
        a.awaitControlError()
        assertEquals(PcmLaneCodec.ERROR_FRAME_TOO_LARGE, a.lastErrorCode)
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
    }

    @Test
    fun secondControlFrameFromAppMustBeStopOtherwiseControlInvalid() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        // 接通之后再发 open 握手：控制帧语义不合法，必须机器码失败并关链路。
        a.appInput.feed(
            frameBytes(PcmLaneType.CONTROL, PcmLaneCodec.encodeOpen(sessionA, format)),
        )
        a.awaitControlError()
        assertEquals("audio_pcm_control_invalid", a.lastErrorCode)
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
    }

    @Test
    fun workerBoundFrameTypesFromAppAreRejected() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        // APP 只能发控制/上行；下行（0x02）与 ACK（0x04）都是 worker 方向。
        a.appInput.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(1, 2)))
        a.awaitControlError()
        assertEquals("audio_pcm_frame_type_invalid", a.lastErrorCode)
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
    }

    // ------------------------------------------------------------------ 有界写 / I3

    /**
     * I3：APP 读者卡死时，下行写入必须由专用写线程 + 有界队列承担；看门狗在无进展阈值后
     * 关闭连接、走 fault()/outcome 通路（STALE）并把音频故障上报给监督端。
     */
    @Test
    fun stalledAppWriterIsClosedByTheWatchdogAndReportedAsAnAudioFault() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        a.appOutput.blockFromCurrentSize()
        a.link.fromWorker.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(1, 2)))
        assertTrue("写线程必须真的卡在 APP 输出流上", a.appOutput.awaitBlocked(2_000))

        clock.now += 201
        awaitCondition("看门狗必须关闭卡死的连接", 3_000) {
            !a.thread.isAlive && relay.sessionState(sessionA) == PcmRelayState.STALE
        }
        assertEquals(
            listOf(sessionA to AudioPcmRelay.ERROR_APP_STREAM_STALLED),
            appLaneFaults.toList(),
        )
        a.appOutput.unblockWrites()
    }

    /**
     * I3：APP 写入卡死时，工作进程的 error/closed 帧仍必须能被读取线程消费：
     * 不能因为一个卡死的 APP 读者把整条连接（和 worker 故障）永久遮住。
     */
    @Test
    fun workerClosedFrameIsConsumedWhileTheAppWriterIsStalled() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        a.appOutput.blockFromCurrentSize()
        a.link.fromWorker.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(3, 4)))
        assertTrue(a.appOutput.awaitBlocked(2_000))

        a.link.fromWorker.feed(frameBytes(PcmLaneType.CONTROL, PcmLaneCodec.encodeClosed("worker_closed")))
        awaitCondition("工作进程 closed 帧必须被读取并按 SESSION_ENDED 收尾", 2_000) {
            relay.sessionState(sessionA) == null
        }
        a.appOutput.unblockWrites()
    }

    /** I3：APP 输出流直接写失败时必须有界收尾，不能等看门狗阈值。 */
    @Test
    fun failingAppWriterClosesTheConnectionWithoutWaitingForTheWatchdog() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()
        a.appOutput.failWrites()

        val startedAt = System.currentTimeMillis()
        a.link.fromWorker.feed(frameBytes(PcmLaneType.DOWNLINK, byteArrayOf(9, 9)))
        awaitCondition("写失败的连接必须有界关闭", 1_500) { !a.thread.isAlive }
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("写失败不得等 2 s 看门狗（实际 " + elapsed + " ms）", elapsed < 1_500)
        assertEquals(
            "写失败是可重试的 APP 断开，不是工作进程丢失",
            PcmRelayState.BOUND,
            relay.sessionState(sessionA),
        )
    }

    /**
     * Task 11G 2：旧实现先发布 writing=true 再更新时间戳；一次 tick 恰好落在两个赋值之间
     * 时，会读到“正在写 + 上一次的旧时间戳”，把刚开始的写误判成 stall。发布必须是原子的。
     */
    @Test
    fun aWatchdogTickBetweenWritingFlagAndTimestampCannotFalseFault() {
        val now = 10_000L
        lateinit var progress: AppWriteProgress
        var gapTicks = 0
        val clock = object : MonotonicClock {
            override fun nowMs(): Long {
                // clock.nowMs() 在两个赋值之间被求值：模拟看门狗恰好在此刻抽检。
                if (gapTicks == 0) {
                    gapTicks += 1
                    assertFalse(
                        "a tick inside the flag/timestamp publish gap must not fault",
                        progress.stalled(now + 201L, 200L),
                    )
                }
                return now
            }
        }
        progress = AppWriteProgress(clock)

        progress.begin()

        assertEquals(1, gapTicks)
        // 真正的 >2 s 无进展仍然必须 fault。
        assertTrue(progress.stalled(now + 201L, 200L))
    }

    @Test
    fun progressRefreshKeepsAFreshWriteAliveAndARealStallStillFaults() {
        val clock = TestClock(0L)
        val progress = AppWriteProgress(clock)

        progress.begin()
        clock.now = 100L
        progress.progress()
        clock.now = 300L
        assertFalse("exactly at the threshold is not a stall", progress.stalled(clock.now, 200L))
        clock.now = 301L
        assertTrue("more than the threshold without progress is a stall", progress.stalled(clock.now, 200L))

        progress.end()
        assertFalse("a finished write is never a stall", progress.stalled(Long.MAX_VALUE, 200L))
    }

    @Test
    fun sweepDisownedReleasesBindingsForSessionsTheSupervisorNoLongerOwns() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        // 监督端仍持有：不得误删活绑定。
        assertEquals(0, relay.sweepDisowned())
        assertEquals(PcmRelayState.ATTACHED, relay.sessionState(sessionA))

        // 故障终止/断开后所有权消失：轮询必须释放绑定并关掉活连接。
        owner.disown(sessionA)
        assertEquals(1, relay.sweepDisowned())
        assertNull(relay.sessionState(sessionA))
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
        awaitCondition("worker link closed") { a.link.isClosed }
    }

    // ------------------------------------------------------------------ 停止/重启

    @Test
    fun workerLinkLossMarksTheSessionStaleAndRefusesSilentReconnect() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        // 音频工作进程“重启”：链路被对端关闭。
        a.link.fromWorker.endInput()
        a.awaitControlError()
        assertEquals("audio_pcm_worker_lost", a.lastErrorCode)
        a.thread.join(2_000)
        assertEquals(PcmRelayState.STALE, relay.sessionState(sessionA))

        // 旧会话可能仍然活着：绝不悄悄重连并当成新的音频流。
        val reconnect = relay.open(sessionA, appPeer, format)
        assertTrue(reconnect is PcmOpenResult.Rejected)
        assertEquals("audio_pcm_session_stale", (reconnect as PcmOpenResult.Rejected).code)

        // 监督端显式释放（例如人工交还/故障终止）后，同一会话 id 可以重新绑定。
        relay.release(sessionA)
        assertNull(relay.sessionState(sessionA))
        val reopened = relay.open(sessionA, appPeer, format)
        assertTrue(reopened is PcmOpenResult.Opened)
    }

    @Test
    fun appDisconnectDropsQueuesWithoutStoppingTheWorkerSession() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        a.appInput.endInput()
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
        // 绑定回到 BOUND，允许同一 APP 在会话仍归 AI 时重新接入。
        assertEquals(PcmRelayState.BOUND, relay.sessionState(sessionA))
        awaitCondition("worker link is closed") { a.link.isClosed }
    }

    @Test
    fun releaseClosesTheLiveConnectionAndWorkerLink() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        val a = attach(sessionA, appPeer)
        a.awaitOpenOk()

        relay.release(sessionA)
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
        awaitCondition("worker link closed") { a.link.isClosed }
        assertNull(relay.sessionState(sessionA))
    }

    @Test
    fun workerHandshakeRejectionStaysRetryableAndNeverLeaksPcm() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        links.respondWith = openErrorBytes("audio_pcm_not_open", "audio session is not open yet")

        val a = attachRaw(sessionA, appPeer)
        a.awaitControlError()
        assertEquals("audio_pcm_not_open", a.lastErrorCode)
        a.thread.join(2_000)
        // 工作进程尚未打开音频（例如 ACTIVE 还没到）：绑定仍可重试，不标 STALE。
        assertEquals(PcmRelayState.BOUND, relay.sessionState(sessionA))
    }

    @Test
    fun workerUnreachableIsReportedAsBoundedFailure() {
        owner.own(sessionA)
        assertTrue(relay.open(sessionA, appPeer, format) is PcmOpenResult.Opened)
        links.failConnect = true

        val a = attachRaw(sessionA, appPeer)
        a.awaitControlError()
        assertEquals("audio_pcm_worker_unavailable", a.lastErrorCode)
        a.thread.join(2_000)
        assertFalse(a.thread.isAlive)
    }

    // ------------------------------------------------------------------ 辅助

    private fun attach(session: SessionId, peer: PeerIdentity): Attached {
        // 这条接入最多创建一条 worker 链路：按创建顺序取，避免与其它接入串线。
        val expectedIndex = links.links.size
        val connection = attachRaw(session, peer)
        attached += connection
        awaitCondition("worker link for " + session) { links.links.size > expectedIndex }
        connection.link = links.links[expectedIndex]
        return connection
    }

    private fun attachRaw(session: SessionId, peer: PeerIdentity): Attached {
        val input = FakeInputStream()
        val output = FakeOutputStream()
        val connection = Attached(input, output)
        val thread = Thread {
            relay.serve(
                peer,
                PcmLaneFrame(PcmLaneType.CONTROL, PcmLaneCodec.encodeOpen(session, format)),
                input,
                output,
            ) { input.endInput() }
        }.apply {
            isDaemon = true
            name = "agentcall-test-pcm-attach"
            start()
        }
        connection.thread = thread
        return connection
    }

    private inner class Attached(
        val appInput: FakeInputStream,
        val appOutput: FakeOutputStream,
    ) {
        lateinit var thread: Thread
        var link: FakeWorkerLink = FakeWorkerLink(openOkBytes(format))
        private val collector = FrameCollector(appOutput)

        val frames: List<PcmLaneFrame> get() = collector.frames

        @Volatile
        var lastErrorCode: String? = null

        @Volatile
        var lastClosedReason: String? = null

        fun sync() {
            collector.sync()
        }

        fun await(description: String, predicate: (List<PcmLaneFrame>) -> Boolean) {
            collector.await(description, predicate)
        }

        fun awaitOpenOk() {
            collector.await("open_ok") { frames ->
                frames.any { frame ->
                    frame.type == PcmLaneType.CONTROL &&
                        PcmLaneCodec.decodeControl(frame.body) is PcmControl.OpenOk
                }
            }
        }

        fun awaitClosedReason() {
            collector.await("control closed") { frames ->
                frames.mapNotNull { frame ->
                    if (frame.type != PcmLaneType.CONTROL) return@mapNotNull null
                    (PcmLaneCodec.decodeControl(frame.body) as? PcmControl.Closed)?.reason
                }.firstOrNull()?.let { reason ->
                    lastClosedReason = reason
                    true
                } ?: false
            }
        }

        fun awaitControlError() {
            collector.await("control error") { frames ->
                frames.mapNotNull { frame ->
                    if (frame.type != PcmLaneType.CONTROL) return@mapNotNull null
                    (PcmLaneCodec.decodeControl(frame.body) as? PcmControl.Error)?.code
                }.firstOrNull()?.let { code ->
                    lastErrorCode = code
                    true
                } ?: false
            }
        }
    }
}

private class TestClock(var now: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = now
}

private class MutableOwner : PcmSessionOwner {
    private val owned = CopyOnWriteArrayList<SessionId>()

    fun own(id: SessionId) {
        if (id !in owned) owned += id
    }

    fun disown(id: SessionId) {
        owned -= id
    }

    override fun owns(id: SessionId): Boolean = id in owned
}

private class FakeWorkerLink(initialResponse: ByteArray) : PcmWorkerLink {

    val fromWorker = FakeInputStream()
    val toWorker = FakeOutputStream()

    @Volatile
    var isClosed: Boolean = false

    init {
        fromWorker.feed(initialResponse)
    }

    override val input: InputStream get() = fromWorker
    override val output: OutputStream get() = toWorker

    override fun setReadTimeoutMs(ms: Int) = Unit

    override fun close() {
        isClosed = true
        runCatching { fromWorker.endInput() }
    }
}

private class FakeWorkerLinkFactory(private val format: AudioFormatSpec) : PcmWorkerLinkFactory {

    val links = CopyOnWriteArrayList<FakeWorkerLink>()
    val connectAttempts = AtomicInteger(0)

    @Volatile
    var failConnect: Boolean = false

    @Volatile
    var respondWith: ByteArray? = null

    override fun connect(): PcmWorkerLink {
        connectAttempts.incrementAndGet()
        if (failConnect) throw AudioPcmLinkException("audio_pcm_worker_unavailable", "worker is unreachable")
        val link = FakeWorkerLink(respondWith ?: openOkBytes(format))
        links += link
        return link
    }
}

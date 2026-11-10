package org.krvyft.agentcall.bridge.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioBridge
import org.krvyft.agentcall.core.port.AudioSession
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 监督进程 ↔ 音频工作进程之间最小生命周期通道的测试（C1：两个角色必须是独立进程，
 * 监督端不得在进程内再拥有一份麦克风隔离/音频句柄）。
 *
 * PCM 数据流不走这条通道（由 APP 音频代理按既有 PCM lane 处理）；这里只承载
 * 隔离、打开、停止、恢复与状态读回。
 */
class AudioRoleCodecTest {

    private val session = SessionId("epoch-1", "call-a")
    private val format = AudioFormatSpec(16_000)

    @Test
    fun requestsRoundTrip() {
        val requests = listOf(
            AudioRoleRequest.Isolate,
            AudioRoleRequest.Restore,
            AudioRoleRequest.VerifyIsolation,
            AudioRoleRequest.Stop,
            AudioRoleRequest.Status,
            AudioRoleRequest.Open(session, format),
        )
        for (request in requests) {
            val line = AudioRoleCodec.encodeRequest(request)
            assertEquals("line must be bounded: " + line, request, AudioRoleCodec.decodeRequest(line))
        }
    }

    @Test
    fun malformedOrOversizedLineIsRejected() {
        assertNull(AudioRoleCodec.decodeRequest(""))
        assertNull(AudioRoleCodec.decodeRequest("not-json"))
        assertNull(AudioRoleCodec.decodeRequest("{}"))
        assertNull(AudioRoleCodec.decodeRequest("{\"op\":\"unknown\"}"))
        assertNull(
            AudioRoleCodec.decodeRequest(
                "{\"op\":\"open\",\"processEpoch\":\"\",\"callId\":\"c\",\"sampleRate\":16000}",
            ),
        )
        assertNull(AudioRoleCodec.decodeRequest("x".repeat(AudioRoleCodec.MAX_LINE_CHARS + 1)))
        assertNull(AudioRoleCodec.decodeResponse("{"))
    }

    @Test
    fun responseCarriesFailureClassificationAndStatus() {
        val failure = AudioRoleCodec.decodeResponse(
            AudioRoleCodec.encodeResponse(
                AudioRoleResponse(
                    ok = false,
                    code = "audio_unsupported",
                    failure = AudioFailureCode.UNSUPPORTED,
                    message = "no downlink",
                ),
            ),
        )
        assertFalse(failure!!.ok)
        assertEquals(AudioFailureCode.UNSUPPORTED, failure.failure)

        val status = AudioRoleCodec.decodeResponse(
            AudioRoleCodec.encodeResponse(
                AudioRoleResponse(
                    ok = true,
                    isolated = true,
                    state = AudioPathState.OPEN,
                    sessionId = session,
                ),
            ),
        )
        assertEquals(AudioPathState.OPEN, status!!.state)
        assertEquals(session, status.sessionId)
        assertTrue(status.isolated)
    }
}

class ArmedSessionLeaseTest {

    private val session = SessionId("epoch-1", "call-a")
    private val other = SessionId("epoch-1", "call-b")

    @Test
    fun onlyTheArmedSessionIsAuthorized() {
        val lease = ArmedSessionLease()
        assertFalse(lease.isAuthorized(session))

        lease.arm(session)

        assertTrue(lease.isAuthorized(session))
        assertFalse("监督端只能打开它显式准备的会话", lease.isAuthorized(other))

        lease.disarm()
        assertFalse(lease.isAuthorized(session))
    }
}

class AudioWorkerDispatcherTest {

    private val session = SessionId("epoch-1", "call-a")
    private val format = AudioFormatSpec(16_000)

    private class DispatcherFixture {
        val clock = object : MonotonicClock {
            override fun nowMs(): Long = 0L
        }
        val isolation = DispatcherIsolation()
        val bridge = DispatcherBridge()
        val lease = ArmedSessionLease()
        val worker = AudioWorker(bridge, isolation, clock, lease)
        val dispatcher = AudioWorkerDispatcher(worker, lease)
    }

    @Test
    fun openIsRejectedUntilSupervisorIsolatesAndArmsTheSession() = runBlocking {
        val f = DispatcherFixture()

        // 未隔离：即使监督端请求 open 也必须被拒绝，绝不打开音频。
        val notIsolated = AudioRoleCodec.decodeResponse(
            f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.Open(session, format))),
        )!!
        assertFalse(notIsolated.ok)
        assertEquals("audio_not_isolated", notIsolated.code)
        assertEquals(0, f.bridge.openCalls)

        // 隔离后由分派器为本次会话 arm 最小租约，才允许打开。
        f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.Isolate))
        val opened = AudioRoleCodec.decodeResponse(
            f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.Open(session, format))),
        )!!
        assertTrue(opened.ok)
        assertEquals(1, f.bridge.openCalls)
        assertTrue(f.lease.isAuthorized(session))
    }

    @Test
    fun stopDisarmsLeaseAndKeepsIsolation() = runBlocking {
        val f = DispatcherFixture()
        f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.Isolate))
        f.lease.arm(session)
        f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.Open(session, format)))

        val stopped = AudioRoleCodec.decodeResponse(
            f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.Stop)),
        )!!

        assertTrue(stopped.ok)
        assertFalse(f.lease.isAuthorized(session))
        assertTrue(f.isolation.isolated)
    }

    @Test
    fun verifyIsolationReportsExternalLoss() = runBlocking {
        val f = DispatcherFixture()
        f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.Isolate))
        f.isolation.isolated = false

        val verified = AudioRoleCodec.decodeResponse(
            f.dispatcher.handle(AudioRoleCodec.encodeRequest(AudioRoleRequest.VerifyIsolation)),
        )!!

        assertFalse(verified.isolated)
    }

    private class DispatcherIsolation : MicrophoneIsolation {
        var isolated = false
        override suspend fun isolate(): Boolean {
            isolated = true
            return true
        }

        override suspend fun restore(): Boolean {
            isolated = false
            return true
        }

        override fun isIsolated(): Boolean = isolated
    }

    private class DispatcherBridge : AudioBridge {
        var openCalls = 0
        override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
            openCalls++
            return object : AudioSession {
                override val downlink: Flow<PcmChunk> = emptyFlow()
                override suspend fun writeUplink(audio: PcmAudio) = Unit
                override suspend fun stop() = Unit
            }
        }
    }
}

class RemoteSupervisedAudioTest {

    private val session = SessionId("epoch-1", "call-a")
    private val format = AudioFormatSpec(16_000)

    private class RecordingTransport(
        private val respond: (AudioRoleRequest, String) -> AudioRoleResponse,
    ) : AudioRoleTransport {
        val requests = mutableListOf<AudioRoleRequest>()

        override suspend fun exchange(requestLine: String): String {
            val request = AudioRoleCodec.decodeRequest(requestLine) ?: error("bad request line: " + requestLine)
            requests += request
            return AudioRoleCodec.encodeResponse(respond(request, requestLine))
        }
    }

    @Test
    fun isolateRestoreAndVerifyRoundTrip() = runBlocking {
        var isolated = false
        val transport = RecordingTransport { request, _ ->
            when (request) {
                AudioRoleRequest.Isolate -> {
                    isolated = true
                    AudioRoleResponse(ok = true, isolated = true)
                }
                AudioRoleRequest.Restore -> {
                    isolated = false
                    AudioRoleResponse(ok = true, isolated = false)
                }
                AudioRoleRequest.VerifyIsolation -> AudioRoleResponse(ok = true, isolated = isolated)
                else -> AudioRoleResponse(ok = false, code = "unexpected")
            }
        }
        val audio = RemoteSupervisedAudio(transport, object : MonotonicClock {
            override fun nowMs(): Long = 0L
        })

        assertTrue(audio.isolateMicrophone())
        assertTrue(audio.microphoneIsolated)

        // 系统侧隔离被解除后，读回必须反映真实状态而不是代理缓存。
        isolated = false
        assertFalse(audio.verifyIsolation())
        assertTrue(audio.restoreMicrophone())
        assertFalse(audio.microphoneIsolated)
    }

    @Test
    fun transportFailureFailsClosed() = runBlocking {
        val transport = object : AudioRoleTransport {
            override suspend fun exchange(requestLine: String): String =
                throw AudioRoleLinkException("audio_worker_unreachable", "no worker")
        }
        val audio = RemoteSupervisedAudio(transport, object : MonotonicClock {
            override fun nowMs(): Long = 0L
        })

        assertFalse(audio.isolateMicrophone())
        assertFalse(audio.restoreMicrophone())
        assertFalse(audio.verifyIsolation())
        try {
            audio.open(session, format)
            fail("音频工作进程不可达时 open 必须失败，不能返回假会话")
        } catch (expected: AudioUnavailableException) {
            assertEquals("audio_worker_unreachable", expected.code)
        }

        // 最小缺陷：传输失败必须作为“工作进程故障”事件上报，让监督端的重启预算
        // 有真实生产来源；每条事件只上报一次。
        assertEquals("audio_worker_unreachable", audio.takeProcessFault())
        assertNull("故障事件不得重复计入重启预算", audio.takeProcessFault())
    }

    /**
     * I1：三态读回。不可达/非法响应绝不能伪造成“隔离已解除”（false），否则一次瞬时
     * 不可达就会被监督端当成隔离丢失而 block + 终止；工作进程明确 ok=false 才是确定错误。
     */
    @Test
    fun probeIsolationSeparatesUnreachableFromVerifiedLoss() = runBlocking {
        val unreachable = RemoteSupervisedAudio(
            object : AudioRoleTransport {
                override suspend fun exchange(requestLine: String): String =
                    throw AudioRoleLinkException("audio_worker_unreachable", "no worker")
            },
            object : MonotonicClock {
                override fun nowMs(): Long = 0L
            },
        )
        val unverified = unreachable.probeIsolation()
        assertTrue("不可达只能算未验证：$unverified", unverified is IsolationProbe.Unverified)

        var isolated = false
        val live = RemoteSupervisedAudio(
            RecordingTransport { request, _ ->
                when (request) {
                    AudioRoleRequest.VerifyIsolation -> AudioRoleResponse(ok = true, isolated = isolated)
                    else -> AudioRoleResponse(ok = true, isolated = isolated)
                }
            },
            object : MonotonicClock {
                override fun nowMs(): Long = 0L
            },
        )
        assertTrue(live.probeIsolation() is IsolationProbe.Verified)
        isolated = true
        assertEquals(IsolationProbe.Verified(true), live.probeIsolation())
        isolated = false
        assertEquals("系统确认未隔离才是确定证据", IsolationProbe.Verified(false), live.probeIsolation())
    }

    @Test
    fun probeIsolationTreatsWorkerErrorAsDefiniteEvidence() = runBlocking {
        val audio = RemoteSupervisedAudio(
            RecordingTransport { _, _ ->
                AudioRoleResponse(ok = false, code = "audio_isolation_read_failed")
            },
            object : MonotonicClock {
                override fun nowMs(): Long = 0L
            },
        )

        assertEquals(
            IsolationProbe.WorkerError("audio_isolation_read_failed"),
            audio.probeIsolation(),
        )
    }

    @Test
    fun openFailureCarriesClassificationAndStopReachesWorker() = runBlocking {
        var failOpen = true
        val transport = RecordingTransport { request, _ ->
            when (request) {
                is AudioRoleRequest.Open -> if (failOpen) {
                    AudioRoleResponse(
                        ok = false,
                        code = "audio_downlink_unsupported",
                        failure = AudioFailureCode.UNSUPPORTED,
                        message = "no downlink",
                    )
                } else {
                    AudioRoleResponse(ok = true, isolated = true, state = AudioPathState.OPEN, sessionId = session)
                }
                AudioRoleRequest.Stop -> AudioRoleResponse(ok = true, isolated = true, state = AudioPathState.CLOSED)
                else -> AudioRoleResponse(ok = true)
            }
        }
        val audio = RemoteSupervisedAudio(transport, object : MonotonicClock {
            override fun nowMs(): Long = 0L
        })

        try {
            audio.open(session, format)
            fail("open failure must throw")
        } catch (expected: AudioUnavailableException) {
            assertEquals("audio_downlink_unsupported", expected.code)
            assertEquals(AudioFailureCode.UNSUPPORTED, expected.failure)
        }

        failOpen = false
        val remoteSession = audio.open(session, format)
        remoteSession.stop()
        assertTrue(transport.requests.contains(AudioRoleRequest.Stop))
    }
}
package org.krvyft.agentcall.bridge.audio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.krvyft.agentcall.core.model.AudioFormatSpec
import org.krvyft.agentcall.core.model.PcmAudio
import org.krvyft.agentcall.core.model.PcmChunk
import org.krvyft.agentcall.core.model.PcmEncoding
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.AudioSession

/**
 * 监督进程与音频工作进程之间的传输接缝（root 私有 abstract Unix socket 或测试假实现）。
 * 每次调用一请求一响应，行长有界。
 */
interface AudioRoleTransport {
    suspend fun exchange(requestLine: String): String
}

/** 角色通道不可用/协议错误；调用方必须 fail closed（不能以为隔离或停止成功）。 */
class AudioRoleLinkException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 音频工作进程生命周期请求。PCM 不走这条通道。 */
sealed interface AudioRoleRequest {
    data object Isolate : AudioRoleRequest
    data object Restore : AudioRoleRequest
    data object VerifyIsolation : AudioRoleRequest
    data object Stop : AudioRoleRequest
    data object Status : AudioRoleRequest
    data class Open(val id: SessionId, val format: AudioFormatSpec) : AudioRoleRequest
}

/** 一次生命周期请求的响应；失败必须携带机器可读分类。 */
data class AudioRoleResponse(
    val ok: Boolean,
    val isolated: Boolean = false,
    val code: String? = null,
    val failure: AudioFailureCode? = null,
    val message: String? = null,
    val state: AudioPathState? = null,
    val sessionId: SessionId? = null,
)

/**
 * 固定字段 JSON 行协议。绝不把 PCM、密钥或号码放进这条通道；行长上限
 * [MAX_LINE_CHARS]，非法/超长输入整体拒绝，不猜测语义。
 */
object AudioRoleCodec {

    const val MAX_LINE_CHARS: Int = 4096

    private const val MAX_SESSION_FIELD_CHARS = 64

    fun encodeRequest(request: AudioRoleRequest): String {
        val json = JsonObject()
        when (request) {
            AudioRoleRequest.Isolate -> json.addProperty("op", "isolate")
            AudioRoleRequest.Restore -> json.addProperty("op", "restore")
            AudioRoleRequest.VerifyIsolation -> json.addProperty("op", "verifyIsolation")
            AudioRoleRequest.Stop -> json.addProperty("op", "stop")
            AudioRoleRequest.Status -> json.addProperty("op", "status")
            is AudioRoleRequest.Open -> {
                json.addProperty("op", "open")
                json.addProperty("processEpoch", request.id.processEpoch)
                json.addProperty("callId", request.id.callId)
                json.addProperty("sampleRate", request.format.sampleRate)
                json.addProperty("channels", request.format.channels)
                json.addProperty("encoding", request.format.encoding.name)
            }
        }
        return json.toString()
    }

    fun decodeRequest(line: String): AudioRoleRequest? {
        if (line.isEmpty() || line.length > MAX_LINE_CHARS) return null
        val parsed = runCatching { JsonParser.parseString(line) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        return when (stringField(json, "op")) {
            "isolate" -> AudioRoleRequest.Isolate
            "restore" -> AudioRoleRequest.Restore
            "verifyIsolation" -> AudioRoleRequest.VerifyIsolation
            "stop" -> AudioRoleRequest.Stop
            "status" -> AudioRoleRequest.Status
            "open" -> {
                val epoch = stringField(json, "processEpoch") ?: return null
                val call = stringField(json, "callId") ?: return null
                if (!isSafeSessionToken(epoch) || !isSafeSessionToken(call)) return null
                val sampleRate = intField(json, "sampleRate") ?: return null
                val channels = intField(json, "channels") ?: return null
                if (stringField(json, "encoding") != PcmEncoding.PCM16.name) return null
                val format = runCatching { AudioFormatSpec(sampleRate = sampleRate, channels = channels) }
                    .getOrNull() ?: return null
                AudioRoleRequest.Open(SessionId(epoch, call), format)
            }
            else -> null
        }
    }

    fun encodeResponse(response: AudioRoleResponse): String {
        val json = JsonObject()
        json.addProperty("ok", response.ok)
        json.addProperty("isolated", response.isolated)
        response.code?.let { json.addProperty("code", it) }
        response.failure?.let { json.addProperty("failure", it.name) }
        response.message?.let { json.addProperty("message", it) }
        response.state?.let { json.addProperty("state", it.name) }
        response.sessionId?.let { id ->
            json.addProperty("processEpoch", id.processEpoch)
            json.addProperty("callId", id.callId)
        }
        return json.toString()
    }

    fun decodeResponse(line: String): AudioRoleResponse? {
        if (line.isEmpty() || line.length > MAX_LINE_CHARS) return null
        val parsed = runCatching { JsonParser.parseString(line) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        val ok = booleanField(json, "ok") ?: return null
        val epoch = stringField(json, "processEpoch")
        val call = stringField(json, "callId")
        val session = if (epoch != null && call != null) SessionId(epoch, call) else null
        return AudioRoleResponse(
            ok = ok,
            isolated = booleanField(json, "isolated") ?: false,
            code = stringField(json, "code"),
            failure = stringField(json, "failure")?.let { name ->
                AudioFailureCode.entries.firstOrNull { it.name == name }
            },
            message = stringField(json, "message"),
            state = stringField(json, "state")?.let { name ->
                AudioPathState.entries.firstOrNull { it.name == name }
            },
            sessionId = session,
        )
    }

    private fun stringField(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun booleanField(json: JsonObject, name: String): Boolean? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
        return element.asBoolean
    }

    private fun intField(json: JsonObject, name: String): Int? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val value = element.asDouble
        if (!value.isFinite() || value != Math.floor(value)) return null
        if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
        return value.toInt()
    }

    private fun isSafeSessionToken(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_SESSION_FIELD_CHARS &&
            value.none { it.isISOControl() || it.isWhitespace() }
}

/** 音频工作进程的最小租约：只有监督端显式 arm 的那个会话可以 open。 */
class ArmedSessionLease : AudioLeaseCheck {
    private val lock = Any()
    private var armed: SessionId? = null

    fun arm(id: SessionId) {
        synchronized(lock) { armed = id }
    }

    fun disarm() {
        synchronized(lock) { armed = null }
    }

    override fun isAuthorized(id: SessionId): Boolean = synchronized(lock) { armed == id }
}

/**
 * 音频工作进程侧的请求分派：纯逻辑，socket 层只做帧与凭据。
 */
class AudioWorkerDispatcher(
    private val worker: AudioWorker,
    private val lease: ArmedSessionLease,
) {

    suspend fun handle(line: String): String {
        val request = AudioRoleCodec.decodeRequest(line)
            ?: return AudioRoleCodec.encodeResponse(
                AudioRoleResponse(ok = false, code = ERROR_BAD_REQUEST, message = "audio role request is invalid"),
            )
        return when (request) {
            AudioRoleRequest.Isolate -> AudioRoleCodec.encodeResponse(
                AudioRoleResponse(ok = worker.isolateMicrophone(), isolated = worker.microphoneIsolated),
            )
            AudioRoleRequest.Restore -> AudioRoleCodec.encodeResponse(
                AudioRoleResponse(ok = worker.restoreMicrophone(), isolated = worker.microphoneIsolated),
            )
            AudioRoleRequest.VerifyIsolation -> AudioRoleCodec.encodeResponse(
                AudioRoleResponse(ok = true, isolated = worker.verifyIsolation()),
            )
            AudioRoleRequest.Stop -> {
                lease.disarm()
                worker.stopKeepingIsolation()
                AudioRoleCodec.encodeResponse(
                    AudioRoleResponse(ok = true, isolated = worker.microphoneIsolated, state = AudioPathState.CLOSED),
                )
            }
            AudioRoleRequest.Status -> {
                val snapshot = worker.snapshot()
                AudioRoleCodec.encodeResponse(
                    AudioRoleResponse(
                        ok = true,
                        isolated = worker.verifyIsolation(),
                        state = snapshot.state,
                        sessionId = snapshot.sessionId,
                    ),
                )
            }
            is AudioRoleRequest.Open -> {
                lease.arm(request.id)
                try {
                    worker.open(request.id, request.format)
                    AudioRoleCodec.encodeResponse(
                        AudioRoleResponse(
                            ok = true,
                            isolated = worker.microphoneIsolated,
                            state = worker.snapshot().state,
                            sessionId = request.id,
                        ),
                    )
                } catch (error: AudioUnavailableException) {
                    lease.disarm()
                    AudioRoleCodec.encodeResponse(
                        AudioRoleResponse(
                            ok = false,
                            isolated = worker.microphoneIsolated,
                            code = error.code,
                            failure = error.failure,
                            message = error.message,
                        ),
                    )
                } catch (t: Throwable) {
                    lease.disarm()
                    AudioRoleCodec.encodeResponse(
                        AudioRoleResponse(
                            ok = false,
                            isolated = worker.microphoneIsolated,
                            code = "audio_open_failed",
                            failure = AudioFailureCode.OPEN_FAILED,
                            message = t.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val ERROR_BAD_REQUEST: String = "audio_role_bad_request"
    }
}

/**
 * 监督进程侧的 [SupervisedAudio]：所有生命周期操作都转发给独立的音频工作进程。
 *
 * 传输失败一律 fail closed：隔离/恢复/读回伪造成功会造成麦克风未隔离却继续通话。
 * 本代理的 [AudioSession] 只承载 stop（PCM 由 APP 侧音频代理处理），因此
 * writeUplink 明确返回不支持，绝不静默吞掉音频。
 */
class RemoteSupervisedAudio(
    private val transport: AudioRoleTransport,
    private val clock: org.krvyft.agentcall.core.port.MonotonicClock,
) : SupervisedAudio {

    @Volatile
    private var isolated = false

    override val microphoneIsolated: Boolean
        get() = isolated

    override suspend fun isolateMicrophone(): Boolean {
        val response = call(AudioRoleRequest.Isolate) ?: return false
        isolated = response.ok && response.isolated
        return isolated
    }

    override suspend fun verifyIsolation(): Boolean {
        val response = call(AudioRoleRequest.VerifyIsolation) ?: return false
        isolated = response.ok && response.isolated
        return isolated
    }

    override suspend fun stopKeepingIsolation() {
        // 停止失败也不能解除隔离；保留最后一次确认的隔离意图。
        call(AudioRoleRequest.Stop)
    }

    override suspend fun restoreMicrophone(): Boolean {
        val response = call(AudioRoleRequest.Restore) ?: return false
        isolated = response.isolated
        return response.ok && !response.isolated
    }

    override suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession {
        val response = call(AudioRoleRequest.Open(id, format))
            ?: throw AudioUnavailableException(
                code = ERROR_UNREACHABLE,
                failure = AudioFailureCode.UNSUPPORTED,
                message = "audio worker did not answer the open request",
            )
        if (!response.ok) {
            throw AudioUnavailableException(
                code = response.code ?: "audio_open_failed",
                failure = response.failure ?: AudioFailureCode.OPEN_FAILED,
                message = response.message ?: "audio worker refused to open call audio",
            )
        }
        return RemoteControlSession(transport)
    }

    private suspend fun call(request: AudioRoleRequest): AudioRoleResponse? = try {
        AudioRoleCodec.decodeResponse(transport.exchange(AudioRoleCodec.encodeRequest(request)))
    } catch (t: Throwable) {
        null
    }

    /** 监督端不使用 PCM 数据；停止请求转发给工作进程。 */
    private class RemoteControlSession(private val transport: AudioRoleTransport) : AudioSession {
        override val downlink: Flow<PcmChunk> = emptyFlow()

        override suspend fun writeUplink(audio: PcmAudio) {
            throw AudioUnavailableException(
                code = "audio_pcm_not_routed",
                failure = AudioFailureCode.UNSUPPORTED,
                message = "PCM is routed through the APP audio lane, not the supervision lane",
            )
        }

        override suspend fun stop() {
            try {
                transport.exchange(AudioRoleCodec.encodeRequest(AudioRoleRequest.Stop))
            } catch (t: Throwable) {
                // 停止失败由监督端保持隔离并记录故障，绝不因此恢复麦克风。
            }
        }
    }

    companion object {
        const val ERROR_UNREACHABLE: String = "audio_worker_unreachable"
    }
}

/** 角色通道的固定名字与凭据约束。 */
object AudioRoleProtocol {
    /** root 私有 abstract Unix socket 名（不带 NUL 前缀）。 */
    const val SOCKET_NAME: String = "org.krvyft.agentcall.audio-role"

    /** 只有 root（两个模块角色都以 root app_process 运行）可以连接。 */
    const val ROOT_UID: Int = 0

    const val READ_TIMEOUT_MS: Int = 5_000
    const val MAX_LINE_BYTES: Int = AudioRoleCodec.MAX_LINE_CHARS * 4
}

/**
 * 音频工作进程的角色通道服务端（真机层、未验证）。
 *
 * - abstract Unix socket，不监听网络端口；
 * - 每个连接只处理一行请求，先核对内核 uid 必须是 root；
 * - 行长有界，超限/非法请求回固定错误并关闭连接；
 * - 分派走 [AudioWorkerDispatcher]（纯逻辑，已被 JVM 测试覆盖）。
 */
class AudioWorkerSocketServer(
    private val dispatcher: AudioWorkerDispatcher,
    private val socketName: String = AudioRoleProtocol.SOCKET_NAME,
) : Closeable {

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var serverSocket: android.net.LocalServerSocket? = null
    private var acceptThread: Thread? = null

    /** 绑定并开始接受连接；绑定失败抛 [IOException]（调用方必须退出，不得假装就绪）。 */
    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val server = android.net.LocalServerSocket(socketName)
        serverSocket = server
        acceptThread = Thread({ acceptLoop(server) }, "agentcall-audio-role-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: android.net.LocalServerSocket) {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (io: IOException) {
                if (closed.get()) return
                sleepQuietly()
                continue
            }
            try {
                serve(socket)
            } catch (t: Throwable) {
                // 单个连接失败只关闭该连接，accept 循环继续。
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: android.net.LocalSocket) {
        val uid = try {
            socket.peerCredentials?.uid
        } catch (io: IOException) {
            null
        }
        if (uid != AudioRoleProtocol.ROOT_UID) return
        socket.soTimeout = AudioRoleProtocol.READ_TIMEOUT_MS
        val line = readLine(socket.inputStream) ?: return
        val response = runBlocking { dispatcher.handle(line) }
        writeLine(socket.outputStream, response)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArray(AudioRoleProtocol.MAX_LINE_BYTES)
        var size = 0
        while (size < buffer.size) {
            val read = input.read()
            if (read < 0) return if (size == 0) null else String(buffer, 0, size, StandardCharsets.UTF_8)
            if (read == '\n'.code) return String(buffer, 0, size, StandardCharsets.UTF_8)
            if (read == '\r'.code) continue
            buffer[size++] = read.toByte()
        }
        return null
    }

    private fun writeLine(output: OutputStream, line: String) {
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > AudioRoleProtocol.MAX_LINE_BYTES) return
        output.write(bytes)
        output.write('\n'.code)
        output.flush()
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(100L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }
}
/**
 * 监督进程侧的 root 私有 abstract Unix socket 传输（真机层、未验证）。
 *
 * 每次请求新建连接（生命周期操作低频）；先核对服务端 uid 必须是 root，再发送任何内容
 * ——abstract 名字可以被别的进程抢先绑定，不能只凭名字信任对端。
 */
class LocalSocketAudioRoleTransport(
    private val socketName: String = AudioRoleProtocol.SOCKET_NAME,
) : AudioRoleTransport {

    override suspend fun exchange(requestLine: String): String {
        val bytes = requestLine.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > AudioRoleProtocol.MAX_LINE_BYTES) {
            throw AudioRoleLinkException(ERROR_LINE_TOO_LONG, "audio role request exceeds the bounded line length")
        }
        val socket = android.net.LocalSocket()
        try {
            socket.connect(android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT))
            val serverUid = try {
                socket.peerCredentials?.uid
            } catch (io: IOException) {
                null
            }
            if (serverUid != AudioRoleProtocol.ROOT_UID) {
                throw AudioRoleLinkException(ERROR_UNTRUSTED_SERVER, "audio role server uid is not root")
            }
            socket.soTimeout = AudioRoleProtocol.READ_TIMEOUT_MS
            socket.outputStream.write(bytes)
            socket.outputStream.write('\n'.code)
            socket.outputStream.flush()
            val line = readLine(socket.inputStream)
                ?: throw AudioRoleLinkException(ERROR_EMPTY_RESPONSE, "audio role server closed without a response")
            return line
        } catch (error: AudioRoleLinkException) {
            throw error
        } catch (t: Throwable) {
            throw AudioRoleLinkException(ERROR_UNREACHABLE, "audio role server is unreachable", t)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArray(AudioRoleProtocol.MAX_LINE_BYTES)
        var size = 0
        while (size < buffer.size) {
            val read = input.read()
            if (read < 0) return if (size == 0) null else String(buffer, 0, size, StandardCharsets.UTF_8)
            if (read == '\n'.code) return String(buffer, 0, size, StandardCharsets.UTF_8)
            if (read == '\r'.code) continue
            buffer[size++] = read.toByte()
        }
        return null
    }

    companion object {
        const val ERROR_UNREACHABLE: String = "audio_worker_unreachable"
        const val ERROR_UNTRUSTED_SERVER: String = "audio_worker_untrusted"
        const val ERROR_EMPTY_RESPONSE: String = "audio_worker_empty_response"
        const val ERROR_LINE_TOO_LONG: String = "audio_role_line_too_long"
    }
}

package org.krvyft.agentcall.bridge.supervision

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.delay
import org.krvyft.agentcall.bridge.audio.AudioFailureCode
import org.krvyft.agentcall.bridge.audio.ProcessFaultDecision
import org.krvyft.agentcall.bridge.audio.SupervisedAudio
import org.krvyft.agentcall.bridge.config.PrivateFilePermissions
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.SessionId
import org.krvyft.agentcall.core.port.CallControl
import org.krvyft.agentcall.core.port.MonotonicClock

/**
 * 监督端可核验的通话状态来源。
 *
 * 接听命令返回 [org.krvyft.agentcall.core.model.CommandResult.Failed]（响应丢失）时，不能
 * 假定“没接听”：必须查询真实状态。无法核验时返回 null，调用方按未知 fail closed 处理。
 */
fun interface CallStateProbe {
    suspend fun state(id: SessionId): CallState?
}

/**
 * 需要跨进程重启存活的最小监督状态（spec §8：监督进程重启不能把旧租约绑定到新通话）。
 *
 * 只保存安全决策所需的最小字段，不保存 PCM、密钥或号码。
 */
data class SupervisionPersistedState(
    val phase: SupervisorPhase,
    val ownedSession: SessionId?,
    /** 隔离*意图*：监督端最后一次确认过的系统隔离状态，用于启动时判断是否需要恢复。 */
    val microphoneIsolated: Boolean,
    val blockedReason: String?,
    val lastFault: String?,
    val updatedAtMs: Long,
)

/** 状态存储端口；生产实现是 [SupervisionStateStore]，测试注入假实现。 */
interface SupervisionStateStorePort {
    /** 缺失返回 null；损坏/更新 schema 必须抛 [SupervisionStateException]。 */
    fun load(): SupervisionPersistedState?

    fun save(state: SupervisionPersistedState)
}

object SupervisionStateErrorCode {
    const val CORRUPT: String = "supervision_state_corrupt"
    const val UNSUPPORTED_SCHEMA: String = "supervision_state_schema_unsupported"
    const val STORAGE_FAILED: String = "supervision_state_storage_failed"
}

class SupervisionStateException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * root 私有的原子状态文件（同目录临时文件 + fsync + rename）。
 *
 * 损坏/更新 schema 一律抛错，由启动恢复 fail closed；绝不把半份或未来的状态当成干净启动。
 */
class SupervisionStateStore(
    root: File,
    private val permissions: (File, Boolean) -> Unit = PrivateFilePermissions::enforce,
) : SupervisionStateStorePort {

    private val rootDirectory: File = root
    private val lock = Any()

    init {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw SupervisionStateException(
                SupervisionStateErrorCode.STORAGE_FAILED,
                "supervision state root could not be created",
            )
        }
        if (!rootDirectory.isDirectory) {
            throw SupervisionStateException(
                SupervisionStateErrorCode.STORAGE_FAILED,
                "supervision state root is not a directory",
            )
        }
        try {
            permissions(rootDirectory, true)
        } catch (io: IOException) {
            throw SupervisionStateException(
                SupervisionStateErrorCode.STORAGE_FAILED,
                "supervision state root permissions could not be restricted",
                io,
            )
        }
    }

    override fun load(): SupervisionPersistedState? = synchronized(lock) {
        val file = File(rootDirectory, FILE_NAME)
        if (!file.isFile) return null
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (io: IOException) {
            throw SupervisionStateException(
                SupervisionStateErrorCode.STORAGE_FAILED,
                "supervision state could not be read",
                io,
            )
        }
        decode(text)
    }

    override fun save(state: SupervisionPersistedState) = synchronized(lock) {
        val json = JsonObject().apply {
            addProperty("schema", SCHEMA_VERSION)
            addProperty("phase", state.phase.name)
            if (state.ownedSession == null) {
                add("ownedSession", null)
            } else {
                add(
                    "ownedSession",
                    JsonObject().apply {
                        addProperty("processEpoch", state.ownedSession.processEpoch)
                        addProperty("callId", state.ownedSession.callId)
                    },
                )
            }
            addProperty("microphoneIsolated", state.microphoneIsolated)
            if (state.blockedReason == null) add("blockedReason", null) else addProperty("blockedReason", state.blockedReason)
            if (state.lastFault == null) add("lastFault", null) else addProperty("lastFault", state.lastFault)
            addProperty("updatedAtMs", state.updatedAtMs)
        }
        atomicWrite(File(rootDirectory, FILE_NAME), json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun decode(text: String): SupervisionPersistedState {
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision state is not a JSON object")
        }
        val json = parsed.asJsonObject
        val schema = json.get("schema")
        if (schema == null || !schema.isJsonPrimitive || !schema.asJsonPrimitive.isNumber) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision state schema is invalid")
        }
        val schemaValue = schema.asDouble
        if (!schemaValue.isFinite() || schemaValue != Math.floor(schemaValue)) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision state schema is invalid")
        }
        if (schemaValue > SCHEMA_VERSION) {
            throw SupervisionStateException(
                SupervisionStateErrorCode.UNSUPPORTED_SCHEMA,
                "supervision state schema " + schemaValue.toInt() + " is newer than " + SCHEMA_VERSION,
            )
        }
        val phase = SupervisorPhase.entries.firstOrNull { it.name == string(json, "phase") }
            ?: throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision phase is invalid")
        val owned = json.get("ownedSession")
        val ownedSession = when {
            owned == null || owned.isJsonNull -> null
            owned.isJsonObject -> SessionId(
                processEpoch = string(owned.asJsonObject, "processEpoch"),
                callId = string(owned.asJsonObject, "callId"),
            )
            else -> throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "ownedSession is invalid")
        }
        return SupervisionPersistedState(
            phase = phase,
            ownedSession = ownedSession,
            microphoneIsolated = boolean(json, "microphoneIsolated"),
            blockedReason = optionalString(json, "blockedReason"),
            lastFault = optionalString(json, "lastFault"),
            updatedAtMs = long(json, "updatedAtMs"),
        )
    }

    private fun string(json: JsonObject, name: String): String {
        val element = json.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision field is invalid: " + name)
        }
        return element.asString
    }

    private fun optionalString(json: JsonObject, name: String): String? {
        val element = json.get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision field is invalid: " + name)
        }
        return element.asString
    }

    private fun boolean(json: JsonObject, name: String): Boolean {
        val element = json.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision field is invalid: " + name)
        }
        return element.asBoolean
    }

    private fun long(json: JsonObject, name: String): Long {
        val element = json.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision field is invalid: " + name)
        }
        val asDouble = element.asDouble
        val asLong = element.asLong
        if (!asDouble.isFinite() || asDouble != asLong.toDouble()) {
            throw SupervisionStateException(SupervisionStateErrorCode.CORRUPT, "supervision field is invalid: " + name)
        }
        return asLong
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            permissions(temp, false)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (io: IOException) {
            throw SupervisionStateException(
                SupervisionStateErrorCode.STORAGE_FAILED,
                "supervision state write failed: " + io.javaClass.simpleName,
                io,
            )
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val FILE_NAME: String = "supervisor-state.json"
    }
}

/**
 * 音频工作进程重启预算（**只在监督端存活**，I5）。
 *
 * 旧的实现把预算放在会死掉的音频工作进程里，worker 一重启预算就归零，恰好在该有限
 * 重启失效时失去约束。预算按滑动窗口记录重启时刻：窗口外的旧故障不占新预算。
 */
class RestartBudget(
    private val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    init {
        require(maxRestarts > 0) { "maxRestarts must be positive" }
        require(windowMs > 0L) { "windowMs must be positive" }
    }

    private val lock = Any()
    private val times = ArrayDeque<Long>()

    fun record(nowMs: Long): ProcessFaultDecision = synchronized(lock) {
        prune(nowMs)
        if (times.size >= maxRestarts) {
            ProcessFaultDecision.RestartExhausted(times.size)
        } else {
            times.addLast(nowMs)
            ProcessFaultDecision.RestartAllowed(times.size)
        }
    }

    fun used(nowMs: Long): Int = synchronized(lock) {
        prune(nowMs)
        times.size
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (times.isNotEmpty() && times.first() <= cutoff) {
            times.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_RESTARTS: Int = 3
        const val DEFAULT_WINDOW_MS: Long = 60_000L
    }
}

/** 启动恢复结果；[BLOCKED] 表示禁止新的 AI 会话，必须先人工处理。 */
enum class BootRecoveryOutcome {
    CLEAN,
    RESTORED,
    BLOCKED,
}

/**
 * 监督进程启动恢复（I5，spec §8）。
 *
 * 启动策略（fail closed）：
 * 1. 先**总是**尝试恢复麦克风并读回系统状态——上一任监督进程可能在任何时刻被杀，
 *    把系统麦克风留在静音状态；重启后不应等用户手动修；
 * 2. 恢复失败或读回仍为“已隔离” → [BLOCKED]，绝不启用任何 AI 会话；
 * 3. 持久状态损坏/更新 schema → 仍然先尝试恢复，然后 [BLOCKED]（不猜旧状态）；
 * 4. 持久状态里的旧所有权**绝不**绑定到新通话；成功恢复后写回一份干净状态。
 */
class SupervisorBootRecovery(
    private val audio: SupervisedAudio,
    private val stateStore: SupervisionStateStorePort,
    private val block: (String) -> Unit,
    private val clock: MonotonicClock,
) {

    suspend fun recover(): BootRecoveryOutcome {
        val loaded = try {
            LoadResult.Loaded(stateStore.load())
        } catch (error: SupervisionStateException) {
            LoadResult.Failed(error.code)
        }

        // 同一开机内的监督进程重启可能仍有活着的 AI 通话：不能为了“修麦克风”而把
        // 正在进行的 AI 通话解除隔离。检测方法：elapsedRealtime 未回退即同一开机。
        val sameBootState = (loaded as? LoadResult.Loaded)?.state
        val sameBoot = sameBootState != null && clock.nowMs() >= sameBootState.updatedAtMs
        if (sameBoot && sameBootState.ownedSession != null) {
            block(BLOCK_RESTART_WITH_OWNED_CALL)
            return BootRecoveryOutcome.BLOCKED
        }

        val restored = try {
            audio.restoreMicrophone()
        } catch (t: Throwable) {
            false
        }
        val stillIsolated = try {
            audio.verifyIsolation()
        } catch (t: Throwable) {
            true
        }
        if (!restored) {
            block(BLOCK_RESTORE_FAILED)
            return BootRecoveryOutcome.BLOCKED
        }
        if (stillIsolated) {
            block(BLOCK_STILL_ISOLATED)
            return BootRecoveryOutcome.BLOCKED
        }

        val previous = when (loaded) {
            is LoadResult.Failed -> {
                block(if (loaded.code == SupervisionStateErrorCode.CORRUPT) BLOCK_STATE_CORRUPT else BLOCK_STATE_UNSUPPORTED)
                return BootRecoveryOutcome.BLOCKED
            }
            is LoadResult.Loaded -> loaded.state
        }
        val stale = previous != null &&
            (previous.ownedSession != null || previous.microphoneIsolated || previous.phase != SupervisorPhase.IDLE)
        try {
            stateStore.save(
                SupervisionPersistedState(
                    phase = SupervisorPhase.IDLE,
                    ownedSession = null,
                    microphoneIsolated = false,
                    blockedReason = null,
                    lastFault = null,
                    updatedAtMs = clock.nowMs(),
                ),
            )
        } catch (t: Throwable) {
            block(BLOCK_STATE_WRITE_FAILED)
            return BootRecoveryOutcome.BLOCKED
        }
        return if (stale) BootRecoveryOutcome.RESTORED else BootRecoveryOutcome.CLEAN
    }

    private sealed interface LoadResult {
        data class Loaded(val state: SupervisionPersistedState?) : LoadResult
        data class Failed(val code: String) : LoadResult
    }

    companion object {
        const val BLOCK_RESTORE_FAILED: String = "microphone_restore_failed"
        const val BLOCK_STILL_ISOLATED: String = "microphone_still_isolated"
        const val BLOCK_STATE_CORRUPT: String = "supervision_state_corrupt"
        const val BLOCK_STATE_UNSUPPORTED: String = "supervision_state_schema_unsupported"
        const val BLOCK_STATE_WRITE_FAILED: String = "supervision_state_write_failed"

        /** 同一开机内重启且持久状态仍称“拥有通话”：保持隔离并阻止新会话，绝不能解除可能仍在进行的 AI 通话隔离。 */
        const val BLOCK_RESTART_WITH_OWNED_CALL: String = "supervision_restart_with_owned_call"
    }
}

/**
 * 生产周期轮询驱动（C1）：由角色入口在独立线程里每 [intervalMs] 调一次
 * [Supervisor.poll]，让 5000 ms 心跳失效与 10000 ms 人工接管窗口在真机上真正执行。
 *
 * 只做“驱动 + 上报”，不复制监督逻辑；[poll] 生产接 [Supervisor.poll]。
 */
class SupervisionLoop(
    private val poll: suspend (Long) -> List<SupervisorAction>,
    private val clock: MonotonicClock,
    private val intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val actionSink: (List<SupervisorAction>) -> Unit = {},
) {
    init {
        require(intervalMs > 0L) { "intervalMs must be positive" }
    }

    suspend fun pollOnce(nowMs: Long = clock.nowMs()): List<SupervisorAction> {
        val actions = poll(nowMs)
        if (actions.isNotEmpty()) {
            actionSink(actions)
        }
        return actions
    }

    suspend fun run(shouldStop: () -> Boolean = { false }) {
        while (!shouldStop()) {
            try {
                pollOnce()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // 轮询是唯一的心跳/窗口驱动：单轮异常不能让整个驱动线程静默退出，
                // 否则 5000 ms 故障终止就再也不会执行。监督端内部各端口调用都已做
                // fail closed 分类，这里只保证驱动继续。
            }
            delay(intervalMs)
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 500L
    }
}

/**
 * 监督角色生产装配接缝。所有 Android/真机细节通过本接口注入，JVM 测试用假实现构造
 * 同一张生产图（C1）：角色入口不得在测试之外另写一套监督逻辑。
 */
interface SupervisionPlatform {
    fun clock(): MonotonicClock

    fun supervisedAudio(): SupervisedAudio

    fun calls(): CallControl

    fun emergency(): EmergencyCallControl

    fun authorization(): AutoAnswerAuthorization

    fun stateStore(): SupervisionStateStorePort
}

/** 生产图：监督状态机 + 启动恢复 + 周期轮询。 */
class SupervisionGraph(
    val supervisor: Supervisor,
    val recovery: SupervisorBootRecovery,
    val loop: SupervisionLoop,
)

/**
 * 监督角色的唯一生产装配点（C1）。
 *
 * 顺序固定：状态存储 → 监督状态机（状态变化即原子持久化）→ 启动恢复 → 周期轮询。
 * 状态持久化失败会被 [Supervisor] 转成 BLOCKED，绝不静默继续。
 */
object SupervisorProductionGraph {

    fun build(platform: SupervisionPlatform): SupervisionGraph {
        val audio = platform.supervisedAudio()
        val store = platform.stateStore()
        val clock = platform.clock()
        val supervisor = Supervisor(
            clock = clock,
            audio = audio,
            calls = platform.calls(),
            emergency = platform.emergency(),
            authorization = platform.authorization(),
            stateSink = { state -> store.save(state) },
        )
        val recovery = SupervisorBootRecovery(audio, store, supervisor::block, clock)
        val loop = SupervisionLoop(
            poll = supervisor::poll,
            clock = clock,
        )
        return SupervisionGraph(supervisor, recovery, loop)
    }
}
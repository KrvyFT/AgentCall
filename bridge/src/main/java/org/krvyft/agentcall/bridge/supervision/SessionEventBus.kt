package org.krvyft.agentcall.bridge.supervision

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.SessionId

/**
 * HOOK → APP 会话事件流（Task 7C）：**每进程一份**的定长环形缓冲。
 *
 * 为什么需要它：真实 APK 只能通过控制面读通道发现新通话。会话 ID 是 HOOK 侧随机生成
 * 的，APP 无法预测，因此必须有一条“不带 SessionId”的只读轮询通道把真实事实送出来。
 *
 * 严格约束（派单裁定）：
 * - **只记录真实事实**：[recordSnapshot] 只接受已解码的 HOOK 快照；[recordHandback]/
 *   [recordDisconnect] 只接受监督端已经执行过的交还/断开结果。本类没有“设置状态”的
 *   接口，也不给缺失字段补默认值（缺失就是 JSON null）。
 * - **有界**：容量固定（默认 [DEFAULT_CAPACITY] = 256），满则丢最旧并累计 [droppedCount]。
 *   慢速 APP 消费者既不会让内存增长，也不会阻塞控制面（记录与轮询都在一个很短的
 *   监视器临界区里，没有 I/O、没有回调、没有等待）。
 * - **单次有界**：一次轮询最多返回 [MAX_BATCH] = 64 条事件。
 * - **游标语义**：seq 从 1 开始单调递增；游标 0 从头、陈旧游标（早于环里最旧的一条）
 *   返回仍可用的部分且**不报错**，游标大于本进程已记录的最大 seq 则 [Poll.CursorAhead]
 *   （机器码 `cursor_ahead`，不是“暂时没有新事件”）。
 *
 * 字段语义（线格式见 [encode]）：
 * - `source`：HOOK 快照里 HOOK 声明的 ActionSource（MODULE/USER/UNKNOWN）原样透传；
 *   监督端事实固定为 [SUPERVISOR_SOURCE]。
 * - `outcome`：HOOK 快照事件恒为 null；监督端事实携带
 *   [OUTCOME_HANDBACK_CONFIRMED] 等机器码（或 [OUTCOME_CALL_DISCONNECTED]）。
 * - `handbackReason`：HOOK 交还原因原样透传（监督端交还事件里同样保留同一字符串）。
 * - `number` 保留（APP 的 RuleMatcher 需要它）；除此之外不携带任何密钥、token 或音频。
 */
class SessionEventBus(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * 一条事件。[state]/[presentation]/... 为 null 表示“这条事实没有该观测值”，
     * 绝不用 false/0 之类的默认值替代。
     */
    data class Event(
        val seq: Long,
        val sessionId: SessionId,
        val state: CallState?,
        val presentation: Presentation?,
        val direction: CallDirection?,
        val phoneAccount: String?,
        val isEmergency: Boolean?,
        val isConference: Boolean?,
        val activeCallCount: Int?,
        val number: String?,
        val source: String?,
        val handbackReason: String?,
        val outcome: String?,
    )

    /** 一次轮询的结果：[nextSeq] 是下一次应携带的游标，[dropped] 是进程启动以来被丢弃的条数。 */
    data class Batch(
        val events: List<Event>,
        val nextSeq: Long,
        val dropped: Long,
    )

    /** 轮询结局；游标超前必须与“没有新事件”区分开。 */
    sealed interface Poll {
        data class Available(val batch: Batch) : Poll

        /** 游标晚于本进程已记录的最大 seq：调用方状态已损坏/来自别的进程，拒绝。 */
        data object CursorAhead : Poll
    }

    private val lock = Any()
    private val ring = ArrayDeque<Event>()
    private var sequence: Long = 0L
    private var dropped: Long = 0L

    /** 本进程已经记录的最大 seq（0 表示还没有任何事件）。 */
    val latestSeq: Long get() = synchronized(lock) { sequence }

    /** 因环形缓冲溢出被丢弃的事件总数（单调递增）。 */
    val droppedCount: Long get() = synchronized(lock) { dropped }

    /** 记录一条已解码的 HOOK 快照（source 沿用 HOOK 声明的 ActionSource）。 */
    fun recordSnapshot(sessionId: SessionId, snapshot: HookSnapshotDecoder.Decoded): Event = append { seq ->
        Event(
            seq = seq,
            sessionId = sessionId,
            state = snapshot.state,
            presentation = snapshot.presentation,
            direction = snapshot.direction,
            phoneAccount = snapshot.phoneAccount,
            isEmergency = snapshot.isEmergency,
            isConference = snapshot.isConference,
            activeCallCount = snapshot.activeCallCount,
            number = snapshot.number,
            source = snapshot.source,
            handbackReason = snapshot.handbackReason,
            outcome = null,
        )
    }

    /**
     * 记录一次监督端已经执行完的“交还人工”事实。
     *
     * [handbackReason] 是 HOOK 声明的原始原因（没有就 null），[outcome] 是监督端自己的
     * 结果机器码（[OUTCOME_HANDBACK_CONFIRMED] / [OUTCOME_HANDBACK_REVOKE_FAILED] …）。
     * [state] 只传本次快照真实观测到的状态，不猜。
     */
    fun recordHandback(
        sessionId: SessionId,
        state: CallState?,
        handbackReason: String?,
        outcome: String,
    ): Event = appendSupervisorFact(sessionId, state, handbackReason, outcome)

    /** 记录一次监督端已确认的通话断开（音频/隔离已释放）。 */
    fun recordDisconnect(sessionId: SessionId, state: CallState?): Event =
        appendSupervisorFact(sessionId, state, null, OUTCOME_CALL_DISCONNECTED)

    /**
     * 读取 [sinceSeq] 之后的事件（最多 [maxEvents] 条，且绝不超过 [MAX_BATCH]）。
     *
     * 陈旧游标返回环里仍可用的最旧事件并继续，不报错——APP 只需重新同步，而不是崩溃。
     */
    fun poll(sinceSeq: Long, maxEvents: Int = MAX_BATCH): Poll {
        require(sinceSeq >= 0L) { "sinceSeq must not be negative" }
        require(maxEvents > 0) { "maxEvents must be positive" }
        synchronized(lock) {
            if (sinceSeq > sequence) return Poll.CursorAhead
            val limit = minOf(maxEvents, MAX_BATCH)
            val collected = ArrayList<Event>(limit)
            for (event in ring) {
                if (event.seq <= sinceSeq) continue
                if (collected.size >= limit) break
                collected += event
            }
            val next = collected.lastOrNull()?.seq ?: sequence
            return Poll.Available(Batch(collected, next, dropped))
        }
    }

    private fun appendSupervisorFact(
        sessionId: SessionId,
        state: CallState?,
        handbackReason: String?,
        outcome: String,
    ): Event = append { seq ->
        Event(
            seq = seq,
            sessionId = sessionId,
            // 监督端事实不携带快照字段：没有观测值就是 null，不补默认值。
            state = state,
            presentation = null,
            direction = null,
            phoneAccount = null,
            isEmergency = null,
            isConference = null,
            activeCallCount = null,
            number = null,
            source = SUPERVISOR_SOURCE,
            handbackReason = handbackReason,
            outcome = outcome,
        )
    }

    private fun append(build: (Long) -> Event): Event = synchronized(lock) {
        sequence += 1
        val event = build(sequence)
        ring.addLast(event)
        while (ring.size > capacity) {
            ring.removeFirst()
            dropped += 1
        }
        event
    }

    companion object {
        /** 固定环大小：256 条事件可能覆盖的多通电话足够 APP 重新同步，且内存恒 ≤~1 MiB。 */
        const val DEFAULT_CAPACITY: Int = 256

        /** 单次轮询返回的事件上限。 */
        const val MAX_BATCH: Int = 64

        /** 监督端事实的 `source` 值（HOOK 事件的 `source` 是 HOOK 的 ActionSource）。 */
        const val SUPERVISOR_SOURCE: String = "SUPERVISOR"

        const val OUTCOME_HANDBACK_CONFIRMED: String = "handback_confirmed"
        const val OUTCOME_HANDBACK_REVOKE_FAILED: String = "handback_revoke_failed"
        const val OUTCOME_HANDBACK_RESTORE_FAILED: String = "handback_restore_failed"
        const val OUTCOME_HANDBACK_NOT_OWNED: String = "handback_not_owned"
        const val OUTCOME_CALL_DISCONNECTED: String = "call_disconnected"

        /**
         * 事件批次线格式（键集合固定，缺失值为 JSON null）：
         * `{"events":[{"seq","sessionId":{processEpoch,callId},"epoch","state","presentation",
         * "direction","phoneAccount","isEmergency","isConference","activeCallCount","number",
         * "source","handbackReason","outcome"}],"nextSeq":N,"dropped":D}`
         */
        fun encode(batch: Batch): String {
            val json = JsonObject()
            val array = JsonArray()
            for (event in batch.events) {
                array.add(encodeEvent(event))
            }
            json.add("events", array)
            json.addProperty("nextSeq", batch.nextSeq)
            json.addProperty("dropped", batch.dropped)
            return json.toString()
        }

        fun encodeEvent(event: Event): JsonObject {
            val json = JsonObject()
            json.addProperty("seq", event.seq)
            json.add(
                "sessionId",
                JsonObject().apply {
                    addProperty("processEpoch", event.sessionId.processEpoch)
                    addProperty("callId", event.sessionId.callId)
                },
            )
            json.addProperty("epoch", event.sessionId.processEpoch)
            addNullableString(json, "state", event.state?.name)
            addNullableString(json, "presentation", event.presentation?.name)
            addNullableString(json, "direction", event.direction?.name)
            addNullableString(json, "phoneAccount", event.phoneAccount)
            addNullableBoolean(json, "isEmergency", event.isEmergency)
            addNullableBoolean(json, "isConference", event.isConference)
            addNullableInt(json, "activeCallCount", event.activeCallCount)
            addNullableString(json, "number", event.number)
            addNullableString(json, "source", event.source)
            addNullableString(json, "handbackReason", event.handbackReason)
            addNullableString(json, "outcome", event.outcome)
            return json
        }

        private fun addNullableString(json: JsonObject, name: String, value: String?) {
            if (value == null) json.add(name, JsonNull.INSTANCE) else json.addProperty(name, value)
        }

        private fun addNullableBoolean(json: JsonObject, name: String, value: Boolean?) {
            if (value == null) json.add(name, JsonNull.INSTANCE) else json.addProperty(name, value)
        }

        private fun addNullableInt(json: JsonObject, name: String, value: Int?) {
            if (value == null) json.add(name, JsonNull.INSTANCE) else json.addProperty(name, value)
        }
    }
}

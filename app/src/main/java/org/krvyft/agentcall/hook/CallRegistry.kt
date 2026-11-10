package org.krvyft.agentcall.hook

import java.util.UUID
import org.krvyft.agentcall.core.model.CallCommand
import org.krvyft.agentcall.core.model.CallDirection
import org.krvyft.agentcall.core.model.CallSnapshot
import org.krvyft.agentcall.core.model.CallState
import org.krvyft.agentcall.core.model.CommandResult
import org.krvyft.agentcall.core.model.Presentation
import org.krvyft.agentcall.core.model.RejectReason
import org.krvyft.agentcall.core.model.SessionId

/**
 * 纯 Kotlin 的通话注册表：把 [CallHandle] 变化转成会话快照，并按会话/代次/状态裁决
 * 接听与挂断命令。它不 import 任何 Android 类型，也不直接操作 Telecom；真正的动作由
 * 门面实现（[AndroidCallHandle]）在拨号器主线程二次核验后执行。
 *
 * 关键规则（spec §6.1、§6.2、§8）：
 * - 会话标识 = 随机 [processEpoch] + 随机 callId，绝不用电话号码。
 * - 同一门面重复 observe 只注册一个回调，返回同一个 [SessionId]。
 * - 通话结束/显式移除后，所有命令按 [RejectReason.STALE_SESSION] 拒绝。
 * - Answer 只允许 RINGING、会话代次匹配、且本会话没有已下发的模块接听；
 *   重复 Answer 不会第二次触碰 Telecom。
 * - 用户先接听（非模块来源的 ACTIVE）或来源不明的路由变化会撤销待执行的模块接听，
 *   会话被标记为已交还人工，后续挂断命令被拒绝。
 * - Hangup 只允许模块自己接管的通话（[RejectReason.NOT_OWNED]），不会挂断人工通话。
 * - 每次命令尝试都写入注入的 [CommandSink]；注册表自身从不调用 Android。
 *
 * 线程模型：所有公开方法在同一把锁下串行化；[CommandSink]/[RegistryListener] 的
 * 实现必须非阻塞（生产实现只做有界入队/日志）。
 *
 * @param processEpoch 本次 Google 电话进程的随机代次；同一进程只创建一次注册表。
 * @param callIdFactory 测试注入的 callId 生成器；生产默认随机 UUID。
 */
class CallRegistry(
    val processEpoch: String = randomToken(),
    private val callIdFactory: () -> String = ::randomToken,
    private val sink: CommandSink = CommandSink.NONE,
    private val listener: RegistryListener = RegistryListener.NONE,
) {

    private class Registration(
        val handle: CallHandle,
        val session: SessionId,
        val callback: CallCallback,
        var snapshot: CallSnapshot,
        var pendingAnswer: Boolean = false,
        var moduleOwned: Boolean = false,
        var handedBack: Boolean = false,
    )

    private val lock = Any()
    private val byCallId = LinkedHashMap<String, Registration>()
    private val byIdentity = HashMap<String, Registration>()

    /**
     * 观察一个门面并返回会话。重复调用（同一 [CallHandle.identity]）只返回既有会话，
     * 不会注册第二个回调；初始快照同时交给 [RegistryListener]。
     */
    fun observe(handle: CallHandle): SessionId = synchronized(lock) {
        val existing = byIdentity[handle.identity]
        if (existing != null) {
            existing.session
        } else {
            val session = allocateSession()
            val callback = CallCallback { event -> onHandleEvent(session, event) }
            val registration = Registration(
                handle = handle,
                session = session,
                callback = callback,
                snapshot = runCatching { handle.snapshot().toCallSnapshot(session) }
                    .getOrElse { conservativeSnapshot(session) },
            )
            byIdentity[handle.identity] = registration
            byCallId[session.callId] = registration
            handle.bindSession(session)
            handle.addCallback(callback)
            // addCallback 之后重读一次，避免把回调到达前的旧状态当成初始快照。
            registration.snapshot = runCatching { handle.snapshot().toCallSnapshot(session) }
                .getOrDefault(registration.snapshot)
            if (byCallId[session.callId] === registration) {
                listener.onSnapshot(registration.snapshot, ActionSource.UNKNOWN)
            }
            session
        }
    }

    /** 显式移除（上层收到 InCallService.onCallRemoved 时调用）。重复移除返回 false。 */
    fun remove(handle: CallHandle): Boolean = synchronized(lock) {
        val registration = byIdentity[handle.identity]
        if (registration == null) {
            false
        } else {
            removeLocked(registration)
            true
        }
    }

    /** 门面当前对应的会话；已移除或从未 observe 时为 null。 */
    fun sessionOf(handle: CallHandle): SessionId? = synchronized(lock) {
        byIdentity[handle.identity]?.session
    }

    /** 当前存活会话的最新快照；会话不存在（含已移除、旧代次）时为 null。 */
    fun snapshot(id: SessionId): CallSnapshot? = synchronized(lock) {
        lookup(id)?.snapshot
    }

    /** 是否存在已下发、尚未观察到结果的模块接听。 */
    fun isPendingAnswer(id: SessionId): Boolean = synchronized(lock) {
        lookup(id)?.pendingAnswer ?: false
    }

    /** 模块控制是否已交还人工（用户先接听、来源不明的路由变化、二次核验失败）。 */
    fun isHandedBack(id: SessionId): Boolean = synchronized(lock) {
        lookup(id)?.handedBack ?: false
    }

    /** 存活会话列表，供心跳/快照转发使用。 */
    fun liveSessions(): List<SessionId> = synchronized(lock) {
        byCallId.values.map { it.session }
    }

    /**
     * 接听 [id]。拒绝条件：会话不存在/已被移除/代次不符（STALE_SESSION）、通话不是
     * RINGING（NOT_RINGING）、本会话已有待执行接听（NOT_OWNED，防止重复触碰 Telecom）、
     * 或控制已交还人工（NOT_OWNED）。
     */
    fun answer(id: SessionId): CommandResult = synchronized(lock) {
        val registration = lookup(id)
        if (registration == null) {
            reject(id, CallCommand.Answer, RejectReason.STALE_SESSION)
        } else if (registration.pendingAnswer || registration.handedBack) {
            reject(id, CallCommand.Answer, RejectReason.NOT_OWNED)
        } else if (registration.snapshot.state != CallState.RINGING) {
            reject(id, CallCommand.Answer, RejectReason.NOT_RINGING)
        } else {
            val result = registration.handle.answer()
            if (result == CommandResult.Accepted) {
                registration.pendingAnswer = true
                registration.moduleOwned = true
            }
            record(id, CallCommand.Answer, result)
            result
        }
    }

    /**
     * 挂断 [id]。只允许模块自己接管的通话；人工接听/已交还/未接管的通话一律
     * NOT_OWNED，已不存在的会话或已断开的通话 STALE_SESSION。
     */
    fun hangup(id: SessionId): CommandResult = synchronized(lock) {
        val registration = lookup(id)
        if (registration == null) {
            reject(id, CallCommand.Hangup, RejectReason.STALE_SESSION)
        } else if (!registration.moduleOwned || registration.handedBack) {
            reject(id, CallCommand.Hangup, RejectReason.NOT_OWNED)
        } else if (registration.snapshot.state == CallState.DISCONNECTED) {
            reject(id, CallCommand.Hangup, RejectReason.STALE_SESSION)
        } else {
            val result = registration.handle.disconnect()
            if (result == CommandResult.Accepted) {
                registration.pendingAnswer = false
            }
            record(id, CallCommand.Hangup, result)
            result
        }
    }

    /** 按共享契约的 [CallCommand] 分派；sealed 分支保证穷尽。 */
    fun command(id: SessionId, command: CallCommand): CommandResult = when (command) {
        CallCommand.Answer -> answer(id)
        CallCommand.Hangup -> hangup(id)
    }

    // ------------------------------------------------------------------ 回调

    private fun onHandleEvent(session: SessionId, event: HandleEvent) {
        synchronized(lock) {
            val registration = byCallId[session.callId] ?: return
            if (registration.session != session) return

            val snapshot = event.snapshot.toCallSnapshot(session)
            registration.snapshot = snapshot
            if (event.destroyed || snapshot.state == CallState.DISCONNECTED) {
                removeLocked(registration)
                return
            }

            if (registration.pendingAnswer) {
                when {
                    event.kind == EventKind.ACTION_FAILED -> cancelPendingLocked(
                        registration,
                        event.source,
                        event.reason.ifBlank { "action_failed" },
                    )
                    event.kind == EventKind.ROUTE_CHANGED -> cancelPendingLocked(
                        registration,
                        ActionSource.UNKNOWN,
                        "route_change",
                    )
                    event.kind == EventKind.STATE_CHANGED &&
                        snapshot.state == CallState.ACTIVE &&
                        event.source == ActionSource.MODULE -> {
                        registration.pendingAnswer = false
                        registration.moduleOwned = true
                    }
                    event.kind == EventKind.STATE_CHANGED && snapshot.state != CallState.RINGING -> cancelPendingLocked(
                        registration,
                        event.source,
                        if (snapshot.state == CallState.ACTIVE) "manual_answer" else "state_" + snapshot.state.name.lowercase(),
                    )
                }
            } else if (registration.moduleOwned &&
                event.kind == EventKind.STATE_CHANGED &&
                snapshot.state == CallState.HOLDING
            ) {
                // AI 通话被保持：来源可能是系统/人工，保守交还，不猜测。
                registration.moduleOwned = false
                registration.handedBack = true
                listener.onHandedBack(registration.session, event.source, "state_holding")
            }
            listener.onSnapshot(registration.snapshot, event.source)
        }
    }

    private fun cancelPendingLocked(registration: Registration, source: ActionSource, reason: String) {
        registration.pendingAnswer = false
        registration.moduleOwned = false
        registration.handedBack = true
        listener.onHandedBack(registration.session, source, reason)
    }

    private fun removeLocked(registration: Registration) {
        byCallId.remove(registration.session.callId)
        byIdentity.remove(registration.handle.identity)
        runCatching { registration.handle.removeCallback(registration.callback) }
        listener.onSessionRemoved(registration.session)
    }

    // ------------------------------------------------------------------ 辅助

    private fun lookup(id: SessionId): Registration? = byCallId[id.callId]?.takeIf { it.session == id }

    private fun allocateSession(): SessionId {
        repeat(MAX_CALL_ID_ATTEMPTS) {
            val callId = callIdFactory()
            if (isWireSafeToken(callId) && !byCallId.containsKey(callId)) {
                return SessionId(processEpoch = processEpoch, callId = callId)
            }
        }
        // 注入的生成器不可用时仍必须产生合法（可经 RpcCodec 发送）的会话标识。
        return SessionId(processEpoch = processEpoch, callId = randomToken())
    }

    private fun reject(id: SessionId, command: CallCommand, reason: RejectReason): CommandResult {
        val result = CommandResult.Rejected(reason)
        sink.onCommandAttempt(CommandAttempt(id, command, ActionSource.MODULE, result))
        return result
    }

    private fun record(id: SessionId, command: CallCommand, result: CommandResult) {
        sink.onCommandAttempt(CommandAttempt(id, command, ActionSource.MODULE, result))
    }

    private companion object {
        const val MAX_CALL_ID_ATTEMPTS = 4
        const val MAX_SESSION_FIELD_CHARS = 64

        /** RpcCodec 对会话字段的限制：1..64 且无控制字符。 */
        fun isWireSafeToken(value: String): Boolean =
            value.isNotEmpty() && value.length <= MAX_SESSION_FIELD_CHARS && value.none { it.isISOControl() }

        fun randomToken(): String = UUID.randomUUID().toString()
    }
}

/** 纯映射：门面快照 + 注册表分配的会话 = 共享契约快照。 */
internal fun HandleSnapshot.toCallSnapshot(id: SessionId): CallSnapshot = CallSnapshot(
    id = id,
    number = number,
    state = state,
    direction = direction,
    presentation = presentation,
    phoneAccount = phoneAccount,
    isEmergency = isEmergency,
    isConference = isConference,
    activeCallCount = activeCallCount,
)

/** 门面快照读取失败时的保守快照：不可接听、不可挂断。 */
private fun conservativeSnapshot(id: SessionId): CallSnapshot = CallSnapshot(
    id = id,
    number = null,
    state = CallState.ACTIVE,
    direction = CallDirection.OUTGOING,
    presentation = Presentation.UNKNOWN,
    phoneAccount = null,
    isEmergency = false,
    isConference = false,
    activeCallCount = 1,
)

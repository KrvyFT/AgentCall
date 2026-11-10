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
 * - Answer 只允许 RINGING、会话代次匹配、且本会话没有已下发的模块接听/挂断；
 *   重复 Answer 不会第二次触碰 Telecom。接听前的硬排除（[isSafeToAnswer]）只有：
 *   紧急、会议、非唯一一路（activeCallCount != 1，含 0）、非入站。号码可识别性
 *   （presentation/number）不在这里裁决：那是 core RuleMatcher 依据
 *   handleUnknownNumbers 的单一策略点，hook 拿不到配置，重复判一次会让 spec §7 的
 *   隐藏号码开关永远失效（I1）。
 * - 用户先接听（非模块来源的 ACTIVE）会撤销待执行的模块接听；模块自己接管的 ACTIVE
 *   通话遇到来源不明的真实路由变化/保持/动作失败时保守交还。会话被标记为已交还
 *   人工，后续挂断命令被拒绝。[ActionSource.MODULE] 的路由变化不交还。
 * - 路由事件只有“真的换了指纹”才交还：接听窗口内的路由事件只是重新取基线（框架会在
 *   接通前后重复上报音频状态），接通后重复上报同一指纹也不交还；无法取得指纹时无法
 *   证明变化，按 no-op 处理（I2）。指纹只含活动路由/端点（不含 isMuted/
 *   supportedRouteMask），且接通后有 [ROUTE_CHANGE_GRACE_NS] 的沉降窗口，窗口内的
 *   路由事件只重取基线（Task 11G 1）。
 * - 未映射的 Telecom 瞬时状态以 [EventKind.UNKNOWN_STATE] 上报：保留上一次已知状态，
 *   既不猜成 ACTIVE，也不撤销待执行接听。
 * - 终态（DISCONNECTED/DESTROYED）先向监听者发出最终快照，再移除会话，控制器不必等
 *   心跳超时；observe 一个已断开的门面不会留下僵尸会话。
 * - Hangup 只允许模块自己接管的通话（[RejectReason.NOT_OWNED]），不会挂断人工通话；
 *   已下发过一次的挂断不会重复触碰 Telecom。
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
    private val clockNs: () -> Long = System::nanoTime,
) {

    private class Registration(
        val handle: CallHandle,
        val session: SessionId,
        val callback: CallCallback,
        var snapshot: CallSnapshot,
        var pendingAnswer: Boolean = false,
        var pendingHangup: Boolean = false,
        var moduleOwned: Boolean = false,
        var handedBack: Boolean = false,
        /** 最近一次从门面读到的音频路由指纹（CallSnapshot 不携带它）。 */
        var latestRouteFingerprint: String? = null,
        /** 模块接听时的音频路由指纹；只有与它不同的事件才算“真的改了路由”。 */
        var routeBaseline: String? = null,
        /** 接通后的路由沉降窗口截止时刻（单调时钟）；窗口内的路由事件只重取基线。 */
        var routeGraceUntilNs: Long = 0L,
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
            registration.latestRouteFingerprint = runCatching { handle.snapshot().routeFingerprint }
                .getOrNull()
            if (byCallId[session.callId] === registration) {
                if (registration.snapshot.state == CallState.DISCONNECTED) {
                    // 已经结束的通话不能留下僵尸会话：先发终态快照，再撤销绑定。
                    listener.onSnapshot(registration.snapshot, ActionSource.UNKNOWN)
                    removeLocked(registration)
                } else {
                    listener.onSnapshot(registration.snapshot, ActionSource.UNKNOWN)
                }
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
        } else if (registration.pendingAnswer || registration.pendingHangup || registration.handedBack) {
            reject(id, CallCommand.Answer, RejectReason.NOT_OWNED)
        } else if (!isSafeToAnswer(registration.snapshot)) {
            // 纵深防御：核心 RuleMatcher 已在会话层排除紧急/会议/第二路/非入站/不可识别号码，
            // 注册表再用快照的安全字段复核一次，任何一层漏判都不会自动接听。
            reject(id, CallCommand.Answer, RejectReason.NOT_OWNED)
        } else if (registration.snapshot.state != CallState.RINGING) {
            reject(id, CallCommand.Answer, RejectReason.NOT_RINGING)
        } else {
            val result = registration.handle.answer()
            if (result == CommandResult.Accepted) {
                registration.pendingAnswer = true
                registration.moduleOwned = true
                // 接听时刻的音频状态是基线；接听窗口内的事件只更新基线、不交还（I2(b)(c)）。
                registration.routeBaseline = registration.latestRouteFingerprint
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
        } else if (registration.pendingHangup) {
            // 已经下发过一次挂断：重复命令不得第二次触碰 Telecom。
            reject(id, CallCommand.Hangup, RejectReason.NOT_OWNED)
        } else if (registration.snapshot.state == CallState.DISCONNECTED) {
            reject(id, CallCommand.Hangup, RejectReason.STALE_SESSION)
        } else {
            val result = registration.handle.disconnect()
            if (result == CommandResult.Accepted) {
                registration.pendingAnswer = false
                registration.pendingHangup = true
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

            val incoming = event.snapshot.toCallSnapshot(session)
            // 未映射的瞬时/未知状态不改变状态判定：保留上一次已知状态，绝不猜成 ACTIVE。
            val snapshot = if (event.kind == EventKind.UNKNOWN_STATE) {
                incoming.copy(state = registration.snapshot.state)
            } else {
                incoming
            }
            registration.snapshot = snapshot
            val fingerprint = event.routeFingerprint ?: event.snapshot.routeFingerprint
            registration.latestRouteFingerprint = fingerprint ?: registration.latestRouteFingerprint
            if (event.destroyed || snapshot.state == CallState.DISCONNECTED) {
                // 终态必须先传给控制器，再移除会话；否则控制器只能等心跳超时。
                registration.pendingAnswer = false
                registration.pendingHangup = false
                listener.onSnapshot(snapshot, event.source)
                removeLocked(registration)
                return
            }

            if (event.kind == EventKind.UNKNOWN_STATE) {
                // 未知状态既不撤销待执行接听，也不交还控制权；只转发未猜测的快照。
                listener.onSnapshot(registration.snapshot, event.source)
                return
            }

            if (registration.pendingAnswer) {
                when {
                    event.kind == EventKind.ACTION_FAILED -> cancelPendingLocked(
                        registration,
                        event.source,
                        event.reason.ifBlank { "action_failed" },
                    )
                    // 接听窗口内的路由/音频端点事件是框架在接通过程中重配音频：只重取基线，
                    // 既不撤销接听，也不把它当成人工接听（I2(c)）。
                    event.kind == EventKind.ROUTE_CHANGED -> {
                        registration.routeBaseline = fingerprint
                    }
                    event.kind == EventKind.STATE_CHANGED &&
                        snapshot.state == CallState.ACTIVE &&
                        event.source == ActionSource.MODULE -> {
                        registration.pendingAnswer = false
                        registration.moduleOwned = true
                        // 进入 ACTIVE 后以“当前”音频状态为基线：接通前的事件不再算变化。
                        registration.routeBaseline = fingerprint
                        // 框架在接通后还会重配音频（蓝牙可用性、静音开关等）：给一个短
                        // 沉降窗口，窗口内的路由事件只重取基线（Task 11G 1）。
                        registration.routeGraceUntilNs = clockNs() + ROUTE_CHANGE_GRACE_NS
                    }
                    event.kind == EventKind.STATE_CHANGED && snapshot.state != CallState.RINGING -> cancelPendingLocked(
                        registration,
                        event.source,
                        if (snapshot.state == CallState.ACTIVE) "manual_answer" else "state_" + snapshot.state.name.lowercase(),
                    )
                }
            } else if (registration.moduleOwned) {
                when {
                    // 只有确实变了的路由/音频端点才保守交还；框架重复上报同一状态不交还。
                    // “音频通道已打开”的权威在控制器进程，hook 不可见；模块接管的 ACTIVE
                    // 是控制器打开通道的前置条件，因此这里用 moduleOwned + ACTIVE 作为可
                    // 观察代理，避免把接通瞬间的框架重配误判成用户改路由。
                    event.kind == EventKind.ROUTE_CHANGED -> {
                        if (event.source == ActionSource.MODULE) {
                            // 模块自己的路由动作：更新基线，绝不交还。
                            registration.routeBaseline = fingerprint ?: registration.routeBaseline
                        } else if (snapshot.state == CallState.ACTIVE && fingerprint != null) {
                            if (clockNs() < registration.routeGraceUntilNs) {
                                // 接通后的沉降窗口：框架重配音频不是用户改路由；重取基线，
                                // 窗口结束后同一路由的重复上报也不会被误判（Task 11G 1）。
                                registration.routeBaseline = fingerprint
                            } else {
                                val baseline = registration.routeBaseline
                                if (baseline == null) {
                                    // 第一次观测到可比较的音频状态：没有“之前”可比，不交还。
                                    registration.routeBaseline = fingerprint
                                } else if (fingerprint != baseline) {
                                    handBackLocked(registration, event.source, "route_change")
                                }
                            }
                        }
                    }
                    // AI 通话被保持：来源可能是系统/人工，保守交还，不猜测。
                    event.kind == EventKind.STATE_CHANGED && snapshot.state == CallState.HOLDING ->
                        handBackLocked(registration, event.source, "state_holding")
                    // 模块动作在主线程二次核验失败：所有权不再可信，交还人工。
                    event.kind == EventKind.ACTION_FAILED ->
                        handBackLocked(registration, event.source, event.reason.ifBlank { "action_failed" })
                }
            }
            listener.onSnapshot(registration.snapshot, event.source)
        }
    }

    private fun cancelPendingLocked(registration: Registration, source: ActionSource, reason: String) {
        registration.pendingAnswer = false
        registration.pendingHangup = false
        handBackLocked(registration, source, reason)
    }

    private fun handBackLocked(registration: Registration, source: ActionSource, reason: String) {
        registration.moduleOwned = false
        registration.handedBack = true
        registration.pendingAnswer = false
        registration.pendingHangup = false
        listener.onHandedBack(registration.session, source, reason)
    }

    private fun removeLocked(registration: Registration) {
        byCallId.remove(registration.session.callId)
        byIdentity.remove(registration.handle.identity)
        runCatching { registration.handle.removeCallback(registration.callback) }
        listener.onSessionRemoved(registration.session, registration.handle)
    }

    // ------------------------------------------------------------------ 辅助

    private fun lookup(id: SessionId): Registration? = byCallId[id.callId]?.takeIf { it.session == id }

    /**
     * 接听的硬排除检查：只允许“唯一一路、入站、非紧急/会议”的 RINGING 通话。
     *
     * 号码可识别性刻意不在这里：core [org.krvyft.agentcall.core.rules.RuleMatcher]
     * 已按 handleUnknownNumbers 裁决 presentation/number，是唯一策略点。hook 不接收
     * 配置，若在这里再要求 presentation == ALLOWED，隐藏号码将永远无法自动接听，spec §7
     * 的开关变成死开关（I1）。这里只保留任何配置都不能放宽的硬排除；activeCallCount
     * 必须严格等于 1，0/未知/多路都 fail closed。
     */
    private fun isSafeToAnswer(snapshot: CallSnapshot): Boolean =
        !snapshot.isEmergency &&
            !snapshot.isConference &&
            snapshot.activeCallCount == 1 &&
            snapshot.direction == CallDirection.INCOMING

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

    internal companion object {
        /**
         * 接通后的路由沉降窗口（Task 11G 1）。
         *
         * 框架会在 ACTIVE/音频通道打开后的短暂时间内重复重配路由（蓝牙可用性、静音
         * 开关等良性事件）；窗口内的路由事件只重取基线，绝不交还。
         */
        const val ROUTE_CHANGE_GRACE_NS: Long = 1_000_000_000L

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
    activeCallCount = 0,
)

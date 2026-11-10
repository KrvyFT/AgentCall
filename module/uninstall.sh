#!/system/bin/sh
# AgentCall 卸载清理（spec §8/§11）。
#
# 能力边界（必须诚实）：
# - 桥接已经实现会话/租约（session.request / session.command / session.heartbeat /
#   session.events / session.handover 与租约管理）以及优雅关闭（任务 11D2a）。
# - 优雅关闭会先终止/交还拥有的通话（若有），确认断开后读回恢复麦克风，然后退出；该恢复路径
#   要求音频工作进程仍然存活。因此本脚本的顺序是：取跨脚本停止锁 → 请求固定 CLI shutdown
#   → 读回关闭结果/status（clean、shutdownCode、phase、隔离）→ 停音频工作进程 → 对残留
#   进程做有界 TERM→KILL → 释放锁 → 最后删除数据目录。
# - 等待预算与 11E 对齐：CLI shutdown 结果最多 25 s（11E 的 socket 超时是 20 s），桥接
#   退出最多 10 s，最终 status 探测最多 5 s。所有等待都有界。
# - 停止锁是 /data/adb/agentcall/stop.lock（flock，最多等 40 s）；与 service.sh 共用，
#   避免两个独立 CLI shutdown 在对方的恢复窗口内并发执行（I4）。
# - 若桥接不响应、已退出或无法确认（shutdown_call_unresolved /
#   shutdown_microphone_restore_failed），本脚本无法终止或交还正在进行的 AI 通话，
#   也无法自行解除麦克风隔离；它只打印机器可读警告，绝不谎称已终止/交还。
#
# 停止顺序（与脚本末尾的实际执行顺序一致）：
# 1. 分别探测 abstract socket 是否存在、固定 CLI 是否可执行（两者不是一回事）。
# 2. 取 stop.lock（有界等待）后，socket 存在且 CLI 可执行：执行固定 CLI 的 shutdown，
#    并分别有界等待 CLI 返回、桥接进程/socket 消失；随后（若桥接仍可响应）读 status 的
#    phase 与隔离状态。拿不到锁时跳过重复的关闭请求，只做有界 PID 回退。
# 3. 音频工作进程是独立 app_process（PID 文件 audio-worker.pid，由 service.sh 写入）：
#    核验 cmdline 后 TERM → 有界等待 → KILL，避免移除模块后残留持有麦克风隔离的
#    root 音频进程。必须在优雅关闭请求之后执行。
# 4. 残留桥接：PID 文件核验后 TERM → 有界等待 → KILL。
# 5. 无论是否确认停止，最后才删除 /data/adb/agentcall（配置、密钥、配对、日志、临时文件）；
#    删除会一并移除“所有权可能仍在”的粘性标记，之后重装不会记得这次未解决的通话。
# - 不改 Google 电话默认角色，不动 vendor 音频配置，不重置系统属性；Hook 自动控制
#   随模块移除退出，Google 电话仍是默认应用。

set -u
umask 077

# $0 可能是不含斜杠的相对调用；显式解析，避免 ${0%/*} 在裸调用时得到错误目录。
case "$0" in
    */*)
        MODDIR=${0%/*}
        ;;
    *)
        resolved=$(command -v "$0" 2>/dev/null || printf '%s' "$0")
        case "$resolved" in
            */*) MODDIR=${resolved%/*} ;;
            *) MODDIR=. ;;
        esac
        ;;
esac

# 测试/诊断注入点（与 service.sh 同名）：默认值就是真机路径；覆盖只改变本脚本读取
# 的 PID 文件与探测路径，删除仍受下面的固定路径守卫限制（覆盖值永远不会被 rm -rf）。
DATA_ROOT=${AGENTCALL_DATA_ROOT:-/data/adb/agentcall}
CONTROL_SOCKET=@org.krvyft.agentcall.control
CTL=$MODDIR/bin/agentcallctl
PID_FILE=$DATA_ROOT/bridge.pid
AUDIO_PID_FILE=$DATA_ROOT/audio-worker.pid
RESTART_REQUEST_FILE=$DATA_ROOT/restart.requested
SHUTDOWN_RESULT_FILE=$DATA_ROOT/shutdown.result
STATUS_RESULT_FILE=$DATA_ROOT/status.result
# 测试/诊断注入点：真机读内核的 /proc/net/unix；覆盖只改变本脚本的探测来源。
UNIX_LIST=${AGENTCALL_UNIX_LIST:-/proc/net/unix}
# 等待预算与 11E 的桥接对齐：CLI shutdown socket 超时 20 s（+余量），桥接退出 10 s；
# 最终 status 探测单列上限，避免读回把停止路径拖长。
STOP_WAIT_SECONDS=25
BRIDGE_EXIT_WAIT_SECONDS=10
STATUS_PROBE_WAIT_SECONDS=5
KILL_WAIT_SECONDS=5
# 跨脚本停止锁（I4）：与 service.sh 共用固定路径，优雅关闭序列只允许一个执行者。
STOP_LOCK_WAIT_SECONDS=40
STOP_LOCK_FILE=$DATA_ROOT/stop.lock
case "${AGENTCALL_STOP_WAIT_SECONDS:-}" in
    ''|*[!0-9]*) ;;
    *) STOP_WAIT_SECONDS=$AGENTCALL_STOP_WAIT_SECONDS ;;
esac
case "${AGENTCALL_BRIDGE_EXIT_WAIT_SECONDS:-}" in
    ''|*[!0-9]*) ;;
    *) BRIDGE_EXIT_WAIT_SECONDS=$AGENTCALL_BRIDGE_EXIT_WAIT_SECONDS ;;
esac
case "${AGENTCALL_STATUS_PROBE_WAIT_SECONDS:-}" in
    ''|*[!0-9]*) ;;
    *) STATUS_PROBE_WAIT_SECONDS=$AGENTCALL_STATUS_PROBE_WAIT_SECONDS ;;
esac
case "${AGENTCALL_STOP_LOCK_WAIT_SECONDS:-}" in
    ''|*[!0-9]*) ;;
    *) STOP_LOCK_WAIT_SECONDS=$AGENTCALL_STOP_LOCK_WAIT_SECONDS ;;
esac
stop_lock_held=0

say() {
    printf '%s\n' "$*"
}

# 跨脚本停止锁（I4）：与 service.sh 相同的固定路径与语义；等待有界，超时打印
# 机器可读原因。优先 flock；没有 flock 时退回 mkdir 原子目录锁。
acquire_stop_lock() {
    [ "$stop_lock_held" -eq 0 ] || return 0
    say "stop_lock_wait path=$STOP_LOCK_FILE timeout=${STOP_LOCK_WAIT_SECONDS}s"
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$STOP_LOCK_FILE" 2>/dev/null || {
            say "! stop_lock_unavailable path=$STOP_LOCK_FILE reason=open_failed"
            return 1
        }
        if flock -w "$STOP_LOCK_WAIT_SECONDS" 9 2>/dev/null; then
            stop_lock_held=1
            say "stop_lock_acquired path=$STOP_LOCK_FILE"
            return 0
        fi
        exec 9>&- 2>/dev/null
        say "! stop_lock_timeout waited=${STOP_LOCK_WAIT_SECONDS}s path=$STOP_LOCK_FILE"
        return 1
    fi
    lock_dir="$STOP_LOCK_FILE.d"
    waited=0
    while [ "$waited" -lt "$STOP_LOCK_WAIT_SECONDS" ]; do
        if mkdir "$lock_dir" 2>/dev/null; then
            stop_lock_held=2
            say "stop_lock_acquired path=$lock_dir fallback=mkdir"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    say "! stop_lock_timeout waited=${waited}s path=$lock_dir fallback=mkdir"
    return 1
}

release_stop_lock() {
    if [ "$stop_lock_held" -eq 1 ]; then
        flock -u 9 2>/dev/null
        exec 9>&- 2>/dev/null
    elif [ "$stop_lock_held" -eq 2 ]; then
        rmdir "$STOP_LOCK_FILE.d" 2>/dev/null
    fi
    stop_lock_held=0
}
control_socket_present() {
    grep -q "$CONTROL_SOCKET" "$UNIX_LIST" 2>/dev/null
}

# 只输出“可核验的 AgentCall 进程 PID”：数字、进程存在、cmdline 命中固定入口/进程名。
# PID 文件格式与 service.sh 的 read_pid 一致：首行 + 只接受十进制数字；缺失/损坏/越界
# 一律当作没有，绝不猜测 PID（PID 复用可能误杀无关进程）。
verified_pid_from_file() {
    file=$1
    shift
    [ -f "$file" ] || return 1
    # 先校验原始首行，再做数字规范化：否则 "12 34" 会被 tr 静默拼成 1234，
    # 损坏内容分支永远不可达（还可能误杀与拼接结果同号的无关进程）。
    raw=$(head -n 1 "$file" 2>/dev/null)
    case "$raw" in
        ''|*[!0-9]*) return 1 ;;
    esac
    candidate=$(printf '%s' "$raw" | tr -cd '0-9')
    [ "$candidate" -gt 1 ] || return 1
    [ -d "/proc/$candidate" ] || return 1
    cmdline=$(tr '\0' ' ' < "/proc/$candidate/cmdline" 2>/dev/null)
    for marker in "$@"; do
        case "$cmdline" in
            *"$marker"*)
                printf '%s\n' "$candidate"
                return 0
                ;;
        esac
    done
    return 1
}

pid_from_file() {
    verified_pid_from_file "$PID_FILE" org.krvyft.agentcall.bridge.BridgeMain agentcall-bridge
}

audio_pid_from_file() {
    verified_pid_from_file "$AUDIO_PID_FILE" org.krvyft.agentcall.bridge.audio.AudioWorkerMain agentcall-audio-worker
}

# 进程存在且不是僵尸：本脚本会启动固定 CLI 子进程，必须把已退出未回收的僵尸当作
# “已结束”，否则有界等待会被 /proc 里的僵尸条目误判成超时。
process_alive() {
    [ -d "/proc/$1" ] || return 1
    if [ -r "/proc/$1/status" ] && grep -q '^State:[[:space:]]*Z' "/proc/$1/status" 2>/dev/null; then
        return 1
    fi
    return 0
}

wait_pid_gone() {
    target=$1
    limit=$2
    waited=0
    while process_alive "$target"; do
        waited=$((waited + 1))
        if [ "$waited" -ge "$limit" ]; then
            return 1
        fi
        sleep 1
    done
    return 0
}

# 从固定 CLI 的单行 JSON 里取一个标量：只用于关闭结果的机器可读输出，
# 不是通用 JSON 解析器。键名由本脚本固定传入，不接受外部输入。
json_scalar() {
    printf '%s' "$1" | sed -n "s/.*\"$2\":\\([^,}]*\\).*/\\1/p" | head -n 1
}

# 去掉 JSON 字符串值的引号；null/缺失原样保留，由调用方归一化成 unknown。
json_text() {
    printf '%s' "$1" | sed 's/^"//; s/"$//'
}

# 有界等待本脚本启动的固定 CLI 子进程：超时则 TERM→KILL 结束它，避免停止路径被
# 一个不响应的 socket 读拖住。返回 CLI 的退出码（超时返回 1）。
wait_cli_bounded() {
    target=$1
    limit=$2
    waited=0
    while process_alive "$target" && [ "$waited" -lt "$limit" ]; do
        sleep 1
        waited=$((waited + 1))
    done
    if process_alive "$target"; then
        say "! 固定 CLI 在 ${limit}s 内未返回：终止该 CLI 进程（不等待桥接响应）"
        kill -TERM "$target" 2>/dev/null
        if ! wait_pid_gone "$target" "$KILL_WAIT_SECONDS"; then
            kill -KILL "$target" 2>/dev/null
            wait_pid_gone "$target" "$KILL_WAIT_SECONDS" || true
        fi
        wait "$target" 2>/dev/null
        return 1
    fi
    wait "$target" 2>/dev/null
    return $?
}

# 有界等待桥接退出：控制 socket 已消失，且 PID 文件不再指向可核验的桥接进程。
# 只依据可核验的事实，不在无法核验时宣称“已停止”。
wait_bridge_stopped() {
    waited=0
    while [ "$waited" -lt "$BRIDGE_EXIT_WAIT_SECONDS" ]; do
        socket_up=0
        control_socket_present && socket_up=1
        current=$(pid_from_file 2>/dev/null) || current=""
        if [ "$socket_up" -eq 0 ] && [ -z "$current" ]; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# 有界终止：TERM → 最多 KILL_WAIT_SECONDS 秒 → KILL → 再等待上限。
# 只在确认进程消失后返回 0。调用方在每次开始终止前从 PID 文件重新核验身份；
# TERM→KILL 升级沿用同一个已核验 PID（在 ≤KILL_WAIT_SECONDS 的窗口内不再读文件，
# 这是同一进程的强制终止，不是重新挑选目标）。
terminate_verified_pid() {
    target=$1
    label=$2
    [ -n "$target" ] || return 1
    say "- PID 回退：向 ${label} PID $target 发送 TERM"
    kill -TERM "$target" 2>/dev/null
    if ! wait_pid_gone "$target" "$KILL_WAIT_SECONDS"; then
        say "! ${label} PID $target 在 ${KILL_WAIT_SECONDS}s 内未退出，发送 KILL"
        kill -KILL "$target" 2>/dev/null
        wait_pid_gone "$target" "$KILL_WAIT_SECONDS" || true
    fi
    if process_alive "$target"; then
        say "! 无法确认 ${label} PID $target 已退出：可能存在仍在运行的进程，重启后会由 service.sh 重新拉起或由系统回收"
        return 1
    fi
    say "- ${label} 进程 $target 已终止"
    return 0
}

say "- AgentCall: 卸载开始"

# 先收回可能待处理的重启请求：否则 shutdown 之后 service.sh 会按请求把桥接重新拉起，
# 与卸载的“停止”意图冲突。这里只删除固定模块标记，不删除其它数据。
if [ -f "$RESTART_REQUEST_FILE" ]; then
    rm -f "$RESTART_REQUEST_FILE" 2>/dev/null
    say "- 已收回待处理的重启请求（避免卸载过程中桥接被重新拉起）"
fi

shutdown_state=not_requested
shutdown_result=""
status_result=""
clean=unknown
shutdown_code=unknown
phase=unknown
isolated=unknown
owned=unknown
bridge_stopped=0

socket_present=0
control_socket_present && socket_present=1
ctl_usable=0
[ -x "$CTL" ] && ctl_usable=1
bridge_pid=$(pid_from_file 2>/dev/null) || bridge_pid=""
audio_pid=$(audio_pid_from_file 2>/dev/null) || audio_pid=""

if [ "$socket_present" -eq 0 ] && [ -z "$bridge_pid" ] && [ -z "$audio_pid" ]; then
    say "- 桥接与音频工作进程都未运行（无控制 socket，PID 文件也不存在或不可核验），跳过停止请求"
    shutdown_state=not_running
else
    # I4：先取跨脚本停止锁，确保与 service.sh 的停止序列不会并发发起两个 CLI shutdown。
    if acquire_stop_lock; then
        stop_lock_ok=1
    else
        stop_lock_ok=0
    fi
    # 1) 先请求优雅关闭：桥接要终止/交还通话并读回恢复麦克风，音频工作进程必须还活着。
    if [ "$socket_present" -eq 1 ] && [ "$ctl_usable" -eq 1 ] && [ "$stop_lock_ok" -eq 1 ]; then
        say "- 控制 socket 存在：请求桥接优雅关闭（固定 CLI shutdown，不执行任意 shell）"
        rm -f "$SHUTDOWN_RESULT_FILE" 2>/dev/null
        "$CTL" shutdown > "$SHUTDOWN_RESULT_FILE" 2>/dev/null &
        cli_pid=$!
        if wait_cli_bounded "$cli_pid" "$STOP_WAIT_SECONDS"; then
            shutdown_state=accepted
        else
            shutdown_state=unconfirmed
        fi
        shutdown_result=$(cat "$SHUTDOWN_RESULT_FILE" 2>/dev/null)
        if wait_bridge_stopped; then
            bridge_stopped=1
            say "- 桥接进程/控制 socket 已消失"
        else
            say "! 未能在 ${STOP_WAIT_SECONDS}s 内确认桥接停止"
        fi
        # 2) 桥接仍可响应时读 status 的 phase 与隔离读回：关闭响应本身不含 phase。
        if [ "$bridge_stopped" -eq 0 ] && control_socket_present; then
            rm -f "$STATUS_RESULT_FILE" 2>/dev/null
            "$CTL" status > "$STATUS_RESULT_FILE" 2>/dev/null &
            cli_pid=$!
            wait_cli_bounded "$cli_pid" "$STATUS_PROBE_WAIT_SECONDS" || true
            status_result=$(cat "$STATUS_RESULT_FILE" 2>/dev/null)
        fi
        rm -f "$SHUTDOWN_RESULT_FILE" "$STATUS_RESULT_FILE" 2>/dev/null
    elif [ "$socket_present" -eq 1 ] && [ "$ctl_usable" -eq 1 ]; then
        say "! 未能取得停止锁（stop_lock_timeout）：跳过重复的优雅关闭请求，改用 PID 回退"
        shutdown_state=lock_timeout
    elif [ "$socket_present" -eq 1 ]; then
        say "! 控制 socket 存在，但固定 CLI 不可执行（$CTL）：无法请求优雅关闭，改用 PID 回退"
        shutdown_state=cli_unavailable
    else
        say "- 无控制 socket：尝试按 PID 文件终止残留的桥接/音频工作进程"
        shutdown_state=no_socket
    fi
fi

# 关闭结果归一化：clean / shutdownCode / phase / 隔离 / 是否曾拥有通话。
if [ -n "$shutdown_result" ]; then
    clean=$(json_scalar "$shutdown_result" clean)
    shutdown_code=$(json_text "$(json_scalar "$shutdown_result" shutdownCode)")
    owned=$(json_scalar "$shutdown_result" ownedSession)
    isolated=$(json_scalar "$shutdown_result" microphoneIsolated)
fi
if [ -n "$status_result" ]; then
    [ "$phase" = unknown ] && phase=$(json_text "$(json_scalar "$status_result" supervisionPhase)")
    [ "$isolated" = unknown ] && isolated=$(json_scalar "$status_result" microphoneIsolated)
    [ "$shutdown_code" = unknown ] && shutdown_code=$(json_text "$(json_scalar "$status_result" shutdownCode)")
fi
[ -z "$clean" ] && clean=unknown
[ -z "$shutdown_code" ] && shutdown_code=unknown
[ -z "$phase" ] && phase=unknown
[ -z "$isolated" ] && isolated=unknown
[ -z "$owned" ] && owned=unknown
# 桥接进程/socket 已消失（无法再读 status）时没有残留的监督阶段：记录 exited，
# 不猜测 ENDED；结论里把它当作“没有进行中的监督状态”。
if [ "$phase" = unknown ] && [ "$bridge_stopped" -eq 1 ]; then
    phase=exited
fi

# 只有三项都可核验且一致时才认定干净关闭：
# clean=true（桥接自己的裁决）、shutdownCode=null（无失败机器码）、
# microphoneIsolated=false（恢复已读回），且 phase 为 IDLE/ENDED（或进程已退出）。
verdict=unconfirmed
if [ "$clean" = "true" ] && [ "$shutdown_code" = "null" ] && [ "$isolated" = "false" ]; then
    case "$phase" in
        IDLE|ENDED|exited) verdict=clean ;;
    esac
fi

if [ "$verdict" = clean ]; then
    say "- 关闭结果：clean=true shutdownCode=null phase=$phase microphoneIsolated=false owned=$owned"
    if [ "$owned" = "true" ]; then
        say "- 桥接已确认终止/交还拥有的通话，并在退出前读回确认麦克风已恢复（未隔离）"
    else
        say "- 桥接已确认干净关闭；没有拥有中的通话（未执行终止/交还），麦克风恢复读回为未隔离"
    fi
else
    say "! shutdown_result clean=$clean shutdownCode=$shutdown_code phase=$phase microphoneIsolated=$isolated owned=$owned cli=$shutdown_state"
    say "! 未确认通话已终止或交还：不宣称已终止/交还；麦克风可能仍被隔离（静音）"
    say "  可能剩余：进行中的 AI 通话、粘性所有权标记、麦克风隔离（由音频工作进程/系统状态决定）"
    say "  建议：先在 Google 电话中手动结束通话；若仍担心麦克风被静音，重启设备后再验证"
fi

# 3) 停音频工作进程：必须在优雅关闭请求之后（恢复麦克风需要该进程存活）。
# 残留会继续持有 abstract socket 与麦克风隔离，所以要在删除数据目录之前结束。
audio_pid=$(audio_pid_from_file 2>/dev/null) || audio_pid=""
if [ -n "$audio_pid" ]; then
    if terminate_verified_pid "$audio_pid" "音频工作"; then
        rm -f "$AUDIO_PID_FILE" 2>/dev/null
    fi
else
    say "- 未发现可核验的音频工作进程 PID（不猜测 PID）"
fi

# 4) 每次杀进程前重新核验 PID：bridge 退出后 PID 可能被系统复用，不能沿用旧值。
residual_pid=$(pid_from_file 2>/dev/null) || residual_pid=""
if control_socket_present || [ -n "$residual_pid" ]; then
    bridge_pid=$residual_pid
    if [ -n "$bridge_pid" ]; then
        if terminate_verified_pid "$bridge_pid" "桥接"; then
            rm -f "$PID_FILE" 2>/dev/null
        fi
    else
        say "! 桥接似乎仍在运行，但 PID 文件缺失或不可核验：无法安全终止（不会猜测 PID）"
    fi
fi

# 停止序列结束：释放跨脚本锁（先于删除数据目录，锁文件随目录一起删除）。
release_stop_lock

say "- 说明：桥接已实现会话/租约（session.request/command/heartbeat/events/handover）与优雅关闭。"
say "  固定 CLI shutdown 会先终止/交还拥有的通话，确认断开后读回恢复麦克风，然后退出。"
say "  该恢复路径要求音频工作进程仍然存活，所以本脚本在 shutdown 请求之后才停止音频工作进程。"
say "  若桥接不响应、已退出或无法确认（shutdown_call_unresolved / shutdown_microphone_restore_failed），"
say "  本脚本无法终止或交还正在进行的 AI 通话，也无法自行解除麦克风隔离；它只能有界地停止进程。"
say "  重启设备会重新初始化系统音频/静音状态（本脚本无法读回验证这一点）；"
say "  但重启不会证明通话已结束，桥接下次启动会因粘性所有权标记保持 BLOCKED，直到人工确认。"
say "  卸载删除数据目录会一并移除粘性所有权标记：之后重新安装不会记得这次未解决的通话。"
say "! TERM 只结束当前进程：service.sh 的重启循环默认在 5 s（RESTART_DELAY_SECONDS）后重新拉起进程。"
say "  按 KernelSU 的标记语义，移除模块会先写 remove/disable 标记（真机卸载时序未验证）；service.sh 每次重启前重新检查标记，标记存在时不再拉起。"
say "  若在没有 remove/disable 标记的情况下手动执行本脚本，桥接可能在约 5 s 内被重新拉起。"

# 只允许删除固定的私有目录；解析后的绝对路径必须完全一致，避免误删。
case "$DATA_ROOT" in
    /data/adb/agentcall) ;;
    *)
        say "! 数据目录不是预期路径，已拒绝删除"
        exit 1
        ;;
esac

if [ -d "$DATA_ROOT" ]; then
    rm -rf "$DATA_ROOT"
    say "- 已清除 root 私有配置、密钥、配对记录与日志"
fi

say "- Google 电话默认角色、vendor 音频配置与系统属性均未改动"
say "- 如需彻底停用 Hook，请在 Xposed/Vector 管理器中关闭 AgentCall 作用域"
say "- AgentCall: 卸载清理完成"
#!/system/bin/sh
# AgentCall 卸载清理（spec §8/§11）。
#
# 能力边界（必须诚实）：
# - 桥接已经实现会话/租约（session.request / session.command / session.heartbeat /
#   session.events / session.handover 与租约管理都在桥接内）。
# - 但 shutdown 与卸载路径尚未接入终止/交还：shutdown 只关闭配置服务与 abstract
#   socket，不能终止或交还正在进行的 AI 通话。该交接由单独任务跟踪，不在本脚本内修复。
# - 如卸载时正在 AI 通话，请在 Google 电话中手动结束该通话，再继续清理。
#
# 停止顺序（与脚本末尾的实际执行顺序一致）：
# 1. 分别探测 abstract socket 是否存在、固定 CLI 是否可执行（两者不是一回事）。
# 2. socket 存在且 CLI 可执行：执行固定 CLI 的 shutdown，并在上限内等待 socket 消失。
# 3. 音频工作进程是独立 app_process（PID 文件 audio-worker.pid，由 service.sh 写入）：
#    核验 cmdline 后 TERM → 有界等待 → KILL，避免移除模块后残留持有麦克风隔离的
#    root 音频进程。
# 4. bridge 的 CLI 不可执行/等待超时/无 socket 时：回退到 PID 文件，核验进程身份后
#    TERM → 有界等待 → KILL。
# 5. 无论是否确认停止，最后才删除 /data/adb/agentcall（配置、密钥、配对、日志、临时文件）。
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
STOP_WAIT_SECONDS=15
KILL_WAIT_SECONDS=5

say() {
    printf '%s\n' "$*"
}

control_socket_present() {
    grep -q "$CONTROL_SOCKET" /proc/net/unix 2>/dev/null
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

wait_socket_gone() {
    waited=0
    while control_socket_present; do
        waited=$((waited + 1))
        if [ "$waited" -ge "$STOP_WAIT_SECONDS" ]; then
            return 1
        fi
        sleep 1
    done
    return 0
}

wait_pid_gone() {
    target=$1
    limit=$2
    waited=0
    while [ -d "/proc/$target" ]; do
        waited=$((waited + 1))
        if [ "$waited" -ge "$limit" ]; then
            return 1
        fi
        sleep 1
    done
    return 0
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
    if [ -d "/proc/$target" ]; then
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

socket_present=0
control_socket_present && socket_present=1
ctl_usable=0
[ -x "$CTL" ] && ctl_usable=1
bridge_pid=$(pid_from_file 2>/dev/null) || bridge_pid=""
audio_pid=$(audio_pid_from_file 2>/dev/null) || audio_pid=""

if [ "$socket_present" -eq 0 ] && [ -z "$bridge_pid" ] && [ -z "$audio_pid" ]; then
    say "- 桥接与音频工作进程都未运行（无控制 socket，PID 文件也不存在或不可核验），跳过停止请求"
else
    if [ "$socket_present" -eq 1 ] && [ "$ctl_usable" -eq 1 ]; then
        say "- 控制 socket 存在：请求桥接停止配置服务（shutdown，不执行任意 shell）"
        "$CTL" shutdown > /dev/null 2>&1
        if wait_socket_gone; then
            say "- 桥接配置服务已停止（socket 已消失）"
        else
            say "! 未能在 ${STOP_WAIT_SECONDS}s 内确认桥接停止：将尝试 PID 回退终止"
        fi
    elif [ "$socket_present" -eq 1 ]; then
        say "! 控制 socket 存在，但固定 CLI 不可执行（$CTL）：跳过 shutdown，改用 PID 回退"
    else
        say "- 无控制 socket：尝试按 PID 文件终止残留的桥接/音频工作进程"
    fi

    # 音频工作进程独立于 bridge 存活：模块移除后残留会继续持有 abstract socket 与
    # 麦克风隔离，下一次启动/重启前都难以清理，因此必须在删除数据目录之前结束。
    audio_pid=$(audio_pid_from_file 2>/dev/null) || audio_pid=""
    if [ -n "$audio_pid" ]; then
        if terminate_verified_pid "$audio_pid" "音频工作"; then
            rm -f "$AUDIO_PID_FILE" 2>/dev/null
        fi
    else
        say "- 未发现可核验的音频工作进程 PID（不猜测 PID）"
    fi

    # 每次杀进程前重新核验 PID：bridge 退出后 PID 可能被系统复用，不能沿用旧值。
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
fi

say "- 说明：桥接已实现会话/租约（session.request/command/heartbeat/events/handover），"
say "  但 shutdown/卸载尚未接入终止或交还路径：本脚本无法终止或交还正在进行的 AI 通话。"
say "  该交接缺口单独跟踪，不在本脚本内修复。"
say "  如卸载时正在 AI 通话，请在 Google 电话中手动结束后再清理。"
say "! 本脚本无法确认任何 AI 通话已被终止或交还（终止/交还路径尚未接入，单独跟踪）"
say "  如正在通话请先在 Google 电话中手动结束"
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

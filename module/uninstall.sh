#!/system/bin/sh
# AgentCall 卸载清理（spec §8/§11）。
#
# 能力边界（必须诚实）：
# - 本脚本只能“请求停止桥接进程”，不能终止或交还正在进行的 AI 通话。
#   当前桥接没有会话/租约实现（session.request 返回 method_not_implemented），
#   shutdown 只关闭配置服务与 abstract socket，不保证任何通话被交还。
# - 如卸载时正在 AI 通话，请在 Google 电话中手动结束该通话，再继续清理。
#
# 停止顺序：
# 1. 分别探测 abstract socket 是否存在、固定 CLI 是否可执行（两者不是一回事）。
# 2. socket 存在且 CLI 可执行：执行固定 CLI 的 shutdown，并在上限内等待 socket 消失。
# 3. CLI 不可执行或等待超时：回退到 PID 文件，核验进程身份后 TERM → 有界等待 → KILL。
# 4. 无论是否确认停止，最后才删除 /data/adb/agentcall（配置、密钥、配对、日志、临时文件）。
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

DATA_ROOT=/data/adb/agentcall
CONTROL_SOCKET=@org.krvyft.agentcall.control
CTL=$MODDIR/bin/agentcallctl
PID_FILE=$DATA_ROOT/bridge.pid
STOP_WAIT_SECONDS=15
KILL_WAIT_SECONDS=5

say() {
    printf '%s\n' "$*"
}

control_socket_present() {
    grep -q "$CONTROL_SOCKET" /proc/net/unix 2>/dev/null
}

# 只输出“可核验的 AgentCall 桥接 PID”：数字、进程存在、cmdline 命中固定入口/进程名。
pid_from_file() {
    [ -f "$PID_FILE" ] || return 1
    candidate=$(cat "$PID_FILE" 2>/dev/null)
    case "$candidate" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -d "/proc/$candidate" ] || return 1
    cmdline=$(tr '\0' ' ' < "/proc/$candidate/cmdline" 2>/dev/null)
    case "$cmdline" in
        *org.krvyft.agentcall.bridge.BridgeMain*|*agentcall-bridge*)
            printf '%s\n' "$candidate"
            return 0
            ;;
    esac
    return 1
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

say "- AgentCall: 卸载开始"

socket_present=0
control_socket_present && socket_present=1
ctl_usable=0
[ -x "$CTL" ] && ctl_usable=1
bridge_pid=$(pid_from_file 2>/dev/null) || bridge_pid=""

if [ "$socket_present" -eq 0 ] && [ -z "$bridge_pid" ]; then
    say "- 桥接未运行（无控制 socket，PID 文件也不存在或不可核验），跳过停止请求"
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
        say "- 无控制 socket，但发现可信 PID 文件：尝试 PID 回退终止残留桥接"
    fi

    # 每次杀进程前重新核验 PID：bridge 退出后 PID 可能被系统复用，不能沿用旧值。
    residual_pid=$(pid_from_file 2>/dev/null) || residual_pid=""
    if control_socket_present || [ -n "$residual_pid" ]; then
        bridge_pid=$residual_pid
        if [ -n "$bridge_pid" ]; then
            say "- PID 回退：向 PID $bridge_pid 发送 TERM"
            kill -TERM "$bridge_pid" 2>/dev/null
            if ! wait_pid_gone "$bridge_pid" "$KILL_WAIT_SECONDS"; then
                say "! PID $bridge_pid 在 ${KILL_WAIT_SECONDS}s 内未退出，发送 KILL"
                kill -KILL "$bridge_pid" 2>/dev/null
                wait_pid_gone "$bridge_pid" "$KILL_WAIT_SECONDS" || true
            fi
            if [ -d "/proc/$bridge_pid" ]; then
                say "! 无法确认 PID $bridge_pid 已退出：可能存在仍在运行的桥接进程，重启后会由 service.sh 重新拉起或由系统回收"
            else
                say "- 桥接进程 $bridge_pid 已终止"
                rm -f "$PID_FILE" 2>/dev/null
            fi
        else
            say "! 桥接似乎仍在运行，但 PID 文件缺失或不可核验：无法安全终止（不会猜测 PID）"
        fi
    fi
fi

say "- 说明：桥接当前没有会话/租约实现（session.request 为 method_not_implemented），"
say "  本脚本无法终止或交还正在进行的 AI 通话；shutdown 只关闭配置服务与 socket。"
say "  如卸载时正在 AI 通话，请在 Google 电话中手动结束后再清理。"
say "! 本脚本无法确认任何 AI 通话已被交还（桥接没有会话/租约能力）；如正在通话请先在 Google 电话中手动结束"

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

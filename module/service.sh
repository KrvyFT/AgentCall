#!/system/bin/sh
# AgentCall 特权桥接开机启动（late_start service；spec §6.3/§8/§11）。
#
# 行为边界：
# - 等待系统就绪（sys.boot_completed），有上限，避免开机流程被无限占用。
# - 只通过 app_process 启动模块内固定容器，绑定 abstract Unix socket，不监听任何网络端口。
# - 有限重启（默认最多 3 次），干净退出（shutdown 命令）后不再重启。
# - 日志写入 root 私有目录并有大小上限；日志不含密钥（桥接自身只输出脱敏内容）。
# - 不修改 Google 电话默认角色、vendor 音频配置、SELinux 或系统属性。
#
# 进程模型（任务 5 修复轮 1 / C1）：
# - bridge 容器同时承载控制面与监督角色（BridgeMain：Supervisor + 启动恢复 + 500 ms
#   周期轮询 + 状态持久化）；
# - 音频工作角色是独立 app_process（AudioWorkerMain），只持有 PCM 与麦克风隔离，
#   两者通过 root 私有 abstract socket 交互；
# - 两个角色各自有限重启；任一角色放弃后仍保留另一角色的日志与退出码，便于诊断。
#
# 进程所有权（任务 5 修复轮 2 / 新增重要缺陷 1）：
# - 音频工作进程的真正 PID（app_process，不是启动它的子 shell）写进独立的
#   audio-worker.pid。停止路径必须先结束子 shell 监督循环，再按 PID 文件结束真正的
#   app_process：只杀子 shell 会把 app_process 变成孤儿，继续持有 abstract socket 与
#   麦克风隔离，下一次模块启动会 bind 失败直到重启设备。
# - 停止一律是有界的 TERM → 等待 → KILL → 再等待 → 读回核验；干净退出、显式停止、
#   错误退出三条路径都执行同一套回收。
# - 启动时先回收上一次实例留下的音频工作进程（幂等），并按 cmdline 核验 PID 身份，
#   避免 PID 复用误杀无关进程。

set -u
umask 077

MODDIR=${0%/*}
# 测试/诊断注入点：默认值就是真机路径；覆盖只影响本脚本自己的进程与数据目录。
DATA_ROOT=${AGENTCALL_DATA_ROOT:-/data/adb/agentcall}
LOG_DIR=$DATA_ROOT/logs
LOG_FILE=$LOG_DIR/service.log
INBOX_DIR=$DATA_ROOT/inbox
PID_FILE=$DATA_ROOT/bridge.pid
AUDIO_PID_FILE=$DATA_ROOT/audio-worker.pid
BRIDGE_JAR=$MODDIR/bridge/agentcall-bridge.jar
MAIN_CLASS=org.krvyft.agentcall.bridge.BridgeMain
AUDIO_MAIN_CLASS=org.krvyft.agentcall.bridge.audio.AudioWorkerMain
APP_PROCESS=${AGENTCALL_APP_PROCESS:-/system/bin/app_process}
GETPROP=${AGENTCALL_GETPROP:-getprop}
CONTROL_SOCKET=@org.krvyft.agentcall.control
AUDIO_SOCKET=@org.krvyft.agentcall.audio-role

BOOT_WAIT_SECONDS=600
MAX_RESTARTS=3
RESTART_DELAY_SECONDS=5
READY_WAIT_SECONDS=20
MAX_LOG_BYTES=262144
# 停止/回收的上限：TERM 之后最多等 STOP_GRACE_SECONDS 秒，KILL 之后最多再等
# KILL_WAIT_SECONDS 秒；两个上限保证 on_stop 不会无限挂住。
STOP_GRACE_SECONDS=${AGENTCALL_STOP_GRACE_SECONDS:-3}
STALE_GRACE_SECONDS=${AGENTCALL_STALE_GRACE_SECONDS:-3}
KILL_WAIT_SECONDS=2

mkdir -p "$DATA_ROOT" "$LOG_DIR" "$INBOX_DIR" 2>/dev/null
chmod 0700 "$DATA_ROOT" "$LOG_DIR" "$INBOX_DIR" 2>/dev/null

log_line() {
    stamp=$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || echo unknown-time)
    printf '%s %s\n' "$stamp" "$*" >> "$LOG_FILE" 2>/dev/null
}

rotate_log() {
    if [ -f "$LOG_FILE" ]; then
        size=$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)
        if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
            rm -f "$LOG_FILE.1" 2>/dev/null
            mv "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null
        fi
    fi
}

bridge_pid=
audio_manager_pid=
stopping=0

# 进程存在且不是僵尸（已退出但未被回收的进程不能再被“停止”）。
pid_alive() {
    pid=$1
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -r "/proc/$pid/status" ] && grep -q '^State:[[:space:]]*Z' "/proc/$pid/status" 2>/dev/null; then
        return 1
    fi
    return 0
}

# 读取 PID 文件：只接受一个十进制 PID；缺失/损坏/越界一律当作没有。
read_pid() {
    file=$1
    [ -f "$file" ] || return 1
    candidate=$(head -n 1 "$file" 2>/dev/null | tr -cd '0-9')
    case "$candidate" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$candidate" -gt 1 ] || return 1
    printf '%s\n' "$candidate"
}

process_cmdline() {
    tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null
}

# PID 复用防护：只有 cmdline 命中固定音频工作入口/进程名的 PID 才允许终止。
is_audio_worker_pid() {
    cmdline=$(process_cmdline "$1")
    case "$cmdline" in
        *agentcall-audio-worker*|*AudioWorkerMain*) return 0 ;;
    esac
    return 1
}

is_bridge_pid() {
    cmdline=$(process_cmdline "$1")
    case "$cmdline" in
        *agentcall-bridge*|*BridgeMain*) return 0 ;;
    esac
    return 1
}

# 有界终止：TERM → 最多 grace 秒 → KILL → 最多 KILL_WAIT_SECONDS 秒 → 读回核验。
# 返回 0 只在真的确认进程已不存在时；调用方据此写“已停止/未能停止”。
terminate_pid() {
    target=$1
    grace=$2
    label=$3
    if ! pid_alive "$target"; then
        return 0
    fi
    kill -TERM "$target" 2>/dev/null
    waited=0
    while [ "$waited" -lt "$grace" ]; do
        pid_alive "$target" || break
        sleep 1
        waited=$((waited + 1))
    done
    if pid_alive "$target"; then
        log_line "${label}_kill pid=$target waited=${waited}s"
        kill -KILL "$target" 2>/dev/null
        waited=0
        while [ "$waited" -lt "$KILL_WAIT_SECONDS" ]; do
            pid_alive "$target" || break
            sleep 1
            waited=$((waited + 1))
        done
    fi
    if pid_alive "$target"; then
        log_line "${label}_stop_failed pid=$target"
        return 1
    fi
    log_line "${label}_stopped pid=$target"
    return 0
}

# 先停子 shell 监督循环：否则我们在杀掉 worker 之后，它还会按重启预算再拉起一个新的。
stop_audio_worker_supervisor() {
    if [ -n "$audio_manager_pid" ] && pid_alive "$audio_manager_pid"; then
        terminate_pid "$audio_manager_pid" "$STOP_GRACE_SECONDS" audio_worker_supervisor
        wait "$audio_manager_pid" 2>/dev/null
    fi
    audio_manager_pid=
}

# 再按独立 PID 文件停真正的音频工作 app_process。
stop_audio_worker() {
    worker=$(read_pid "$AUDIO_PID_FILE" 2>/dev/null) || worker=
    if [ -n "$worker" ] && pid_alive "$worker"; then
        terminate_pid "$worker" "$STOP_GRACE_SECONDS" audio_worker
    fi
    rm -f "$AUDIO_PID_FILE" 2>/dev/null
}

shutdown_children() {
    stop_audio_worker_supervisor
    stop_audio_worker
    if [ -n "$bridge_pid" ] && pid_alive "$bridge_pid"; then
        terminate_pid "$bridge_pid" "$STOP_GRACE_SECONDS" bridge
        wait "$bridge_pid" 2>/dev/null
    fi
    bridge_pid=
    rm -f "$PID_FILE" "$AUDIO_PID_FILE" 2>/dev/null
}

on_stop() {
    stopping=1
    # 停止过程本身必须跑完：忽略后续信号，避免半途 exit 留下孤儿。
    trap '' TERM INT HUP
    log_line "service_stop_signal"
    shutdown_children
    exit 0
}

trap on_stop TERM INT HUP

# 幂等启动：上一次实例被强杀后可能留下 PID 文件与仍在运行的音频工作 app_process。
reclaim_stale_worker() {
    stale=$(read_pid "$AUDIO_PID_FILE" 2>/dev/null) || stale=
    if [ -n "$stale" ]; then
        if pid_alive "$stale" && is_audio_worker_pid "$stale"; then
            terminate_pid "$stale" "$STALE_GRACE_SECONDS" stale_audio_worker
        else
            log_line "stale_audio_worker_pid_ignored pid=$stale"
        fi
    fi
    rm -f "$AUDIO_PID_FILE" 2>/dev/null

    stale_bridge=$(read_pid "$PID_FILE" 2>/dev/null) || stale_bridge=
    if [ -n "$stale_bridge" ]; then
        if pid_alive "$stale_bridge" && is_bridge_pid "$stale_bridge"; then
            terminate_pid "$stale_bridge" "$STALE_GRACE_SECONDS" stale_bridge
        else
            log_line "stale_bridge_pid_ignored pid=$stale_bridge"
        fi
    fi
    rm -f "$PID_FILE" 2>/dev/null
}

# 模块被禁用或标记移除时不启动（KernelSU 用 disable/remove 标志文件表达状态）。
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
    log_line "module_disabled_or_removed"
    exit 0
fi

rotate_log
log_line "service_start moddir=$MODDIR"

waited=0
while [ "$("$GETPROP" sys.boot_completed 2>/dev/null)" != "1" ]; do
    waited=$((waited + 1))
    if [ "$waited" -ge "$BOOT_WAIT_SECONDS" ]; then
        log_line "boot_wait_timeout waited=${waited}s"
        break
    fi
    sleep 1
done
if [ "$("$GETPROP" sys.boot_completed 2>/dev/null)" = "1" ]; then
    log_line "boot_completed waited=${waited}s"
fi

if [ ! -f "$BRIDGE_JAR" ]; then
    log_line "bridge_container_missing path=$BRIDGE_JAR"
    exit 1
fi
if [ ! -x "$APP_PROCESS" ]; then
    log_line "app_process_missing path=$APP_PROCESS"
    exit 1
fi

socket_present() {
    grep -q "$1" /proc/net/unix 2>/dev/null
}

wait_ready() {
    pid=$1
    socket=$2
    tries=0
    while [ "$tries" -lt "$READY_WAIT_SECONDS" ]; do
        if socket_present "$socket"; then
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            return 1
        fi
        sleep 1
        tries=$((tries + 1))
    done
    return 1
}

# 音频工作角色：独立 app_process + 自己的有限重启预算；PID 文件里必须是 app_process 本身。
audio_worker_supervisor() {
    restarts=0
    while [ "$restarts" -le "$MAX_RESTARTS" ]; do
        attempt=$((restarts + 1))
        rotate_log
        log_line "audio_worker_start attempt=$attempt"
        "$APP_PROCESS" -Djava.class.path="$BRIDGE_JAR" /system/bin --nice-name=agentcall-audio-worker "$AUDIO_MAIN_CLASS" >> "$LOG_FILE" 2>&1 &
        worker_pid=$!
        printf '%s\n' "$worker_pid" > "$AUDIO_PID_FILE" 2>/dev/null
        if wait_ready "$worker_pid" "$AUDIO_SOCKET"; then
            log_line "audio_worker_ready socket=$AUDIO_SOCKET"
        else
            log_line "audio_worker_not_ready attempt=$attempt"
        fi
        wait "$worker_pid"
        exit_code=$?
        rm -f "$AUDIO_PID_FILE" 2>/dev/null
        if [ "$stopping" -eq 1 ]; then
            exit 0
        fi
        if [ "$exit_code" -eq 0 ]; then
            log_line "audio_worker_exit_clean"
            exit 0
        fi
        log_line "audio_worker_exit attempt=$attempt code=$exit_code"
        restarts=$((restarts + 1))
        if [ "$restarts" -gt "$MAX_RESTARTS" ]; then
            break
        fi
        sleep "$RESTART_DELAY_SECONDS"
    done
    log_line "audio_worker_gave_up restarts=$restarts"
    exit 1
}

# bridge 容器（控制面 + 监督角色）：保持原有有限重启与干净退出语义。
bridge_container_supervisor() {
    restarts=0
    while [ "$restarts" -le "$MAX_RESTARTS" ]; do
        attempt=$((restarts + 1))
        # 上一次尝试已退出，轮转是安全的；单次运行期间桥接只输出少量脱敏行。
        rotate_log
        log_line "bridge_start attempt=$attempt"
        "$APP_PROCESS" -Djava.class.path="$BRIDGE_JAR" /system/bin --nice-name=agentcall-bridge "$MAIN_CLASS" >> "$LOG_FILE" 2>&1 &
        bridge_pid=$!
        printf '%s\n' "$bridge_pid" > "$PID_FILE" 2>/dev/null
        if wait_ready "$bridge_pid" "$CONTROL_SOCKET"; then
            log_line "bridge_ready socket=$CONTROL_SOCKET"
        else
            log_line "bridge_not_ready attempt=$attempt"
        fi
        wait "$bridge_pid"
        exit_code=$?
        bridge_pid=
        rm -f "$PID_FILE" 2>/dev/null
        if [ "$stopping" -eq 1 ]; then
            return 0
        fi
        if [ "$exit_code" -eq 0 ]; then
            log_line "bridge_exit_clean"
            return 0
        fi
        log_line "bridge_exit attempt=$attempt code=$exit_code"
        restarts=$((restarts + 1))
        if [ "$restarts" -gt "$MAX_RESTARTS" ]; then
            break
        fi
        sleep "$RESTART_DELAY_SECONDS"
    done
    log_line "bridge_gave_up restarts=$restarts"
    return 1
}

reclaim_stale_worker

audio_worker_supervisor &
audio_manager_pid=$!

bridge_container_supervisor
bridge_status=$?

if [ "$stopping" -eq 0 ]; then
    log_line "service_cleanup reason=bridge_exit status=$bridge_status"
fi
shutdown_children

exit "$bridge_status"

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

set -u
umask 077

MODDIR=${0%/*}
DATA_ROOT=/data/adb/agentcall
LOG_DIR=$DATA_ROOT/logs
LOG_FILE=$LOG_DIR/service.log
INBOX_DIR=$DATA_ROOT/inbox
PID_FILE=$DATA_ROOT/bridge.pid
AUDIO_PID_FILE=$DATA_ROOT/audio-worker.pid
BRIDGE_JAR=$MODDIR/bridge/agentcall-bridge.jar
MAIN_CLASS=org.krvyft.agentcall.bridge.BridgeMain
AUDIO_MAIN_CLASS=org.krvyft.agentcall.bridge.audio.AudioWorkerMain
APP_PROCESS=/system/bin/app_process
CONTROL_SOCKET=@org.krvyft.agentcall.control
AUDIO_SOCKET=@org.krvyft.agentcall.audio-role

BOOT_WAIT_SECONDS=600
MAX_RESTARTS=3
RESTART_DELAY_SECONDS=5
READY_WAIT_SECONDS=20
MAX_LOG_BYTES=262144

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

on_stop() {
    stopping=1
    log_line "service_stop_signal"
    if [ -n "$bridge_pid" ]; then
        kill -TERM "$bridge_pid" 2>/dev/null
        wait "$bridge_pid" 2>/dev/null
    fi
    if [ -n "$audio_manager_pid" ]; then
        kill -TERM "$audio_manager_pid" 2>/dev/null
    fi
    rm -f "$PID_FILE" "$AUDIO_PID_FILE" 2>/dev/null
    exit 0
}

trap on_stop TERM INT HUP

# 模块被禁用或标记移除时不启动（KernelSU 用 disable/remove 标志文件表达状态）。
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
    log_line "module_disabled_or_removed"
    exit 0
fi

rotate_log
log_line "service_start moddir=$MODDIR"

waited=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    waited=$((waited + 1))
    if [ "$waited" -ge "$BOOT_WAIT_SECONDS" ]; then
        log_line "boot_wait_timeout waited=${waited}s"
        break
    fi
    sleep 1
done
if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
    log_line "boot_completed waited=${waited}s"
fi

if [ ! -f "$BRIDGE_JAR" ]; then
    log_line "bridge_container_missing path=$BRIDGE_JAR"
    exit 1
fi
if [ ! -x "$APP_PROCESS" ]; then
    log_line "app_process_missing"
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

# 音频工作角色：独立 app_process + 自己的有限重启预算。
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

audio_worker_supervisor &
audio_manager_pid=$!

bridge_container_supervisor
bridge_status=$?

if [ "$stopping" -eq 0 ] && [ -n "$audio_manager_pid" ]; then
    kill -TERM "$audio_manager_pid" 2>/dev/null
    audio_manager_pid=
fi
rm -f "$PID_FILE" 2>/dev/null

exit "$bridge_status"

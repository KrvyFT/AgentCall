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
# - 停止顺序是硬契约（任务 11D2b）：先向桥接发出固定 CLI shutdown，并在有界时间内等它
#   优雅退出（它会终止/交还拥有的通话（若有）、读回恢复麦克风；音频工作进程必须仍然存活），
#   再停音频工作进程，最后才把仍未退出的进程做有界 TERM → 等待 → KILL → 再等待 →
#   读回核验。干净退出、显式停止、错误退出三条路径都执行同一套回收。
# - 等待预算与 11E 对齐：CLI shutdown 结果最多 25 s，桥接退出最多 10 s；桥接仍未退出时
#   再做一次有界 status 探测。麦克风隔离是独立读回事实：仍为 true 时打印
#   `! microphone_still_isolated ...` 与 `bridge_stop_unclean`，绝不宣称干净停止（I2）。
# - 退出码 0 不是干净关闭的证据（11H1）：桥接退出后读回 supervisor-state.json（schema 3）
#   的 shutdownCode/microphoneIsolated；不干净时打印 `! microphone_still_isolated` /
#   `! shutdown_unresolved` 与 `bridge_stop_unclean`，绝不记录 `bridge_exit_clean`。
# - 跨脚本停止锁（I4）：停止序列先取 /data/adb/agentcall/stop.lock（flock，最多等 40 s），
#   与 uninstall.sh 串行化；拿不到锁只记录 stop_lock_timeout 并跳过重复的优雅关闭请求。
# - 顺序不能颠倒：先杀音频工作进程会让桥接的恢复请求不可达，麦克风会保持全局静音。
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
CLI_MAIN_CLASS=org.krvyft.agentcall.bridge.AgentCallCli
# 优雅关闭等待（与 11E 的桥接预算对齐）：先等固定 CLI 返回（桥接在响应前完成
# 终止/交还 + 麦克风读回；11E 的 CLI socket 超时是 20 s，恢复路径上限约 6 s），
# 再等桥接进程退出。三个上限都独立有界，避免停止路径被不响应的进程拖住。
GRACEFUL_SHUTDOWN_WAIT_SECONDS=25
BRIDGE_EXIT_WAIT_SECONDS=10
# 桥接在退出等待后仍存活时，做一次最终 status 探测读回隔离状态（有界，不阻塞回收）；
# 已退出时改为读回持久监督状态（supervisor-state.json，schema 3）。
STATUS_PROBE_WAIT_SECONDS=5
# 跨脚本停止锁：uninstall.sh 与 service.sh 的优雅关闭序列必须串行化，否则第二个
# CLI shutdown 会在第一个的恢复窗口内杀音频工作进程。等待上限覆盖对端完整停止预算。
STOP_LOCK_WAIT_SECONDS=40
STOP_LOCK_FILE=$DATA_ROOT/stop.lock
case "${AGENTCALL_GRACEFUL_SHUTDOWN_WAIT_SECONDS:-}" in
    ''|*[!0-9]*) ;;
    *) GRACEFUL_SHUTDOWN_WAIT_SECONDS=$AGENTCALL_GRACEFUL_SHUTDOWN_WAIT_SECONDS ;;
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
SHUTDOWN_RESULT_FILE=$DATA_ROOT/shutdown.result
STATUS_RESULT_FILE=$DATA_ROOT/status.result
# 监督端持久状态（schema 3，见 SupervisionStateStore）：桥接退出后关闭响应与 status 都
# 不可达，这个文件是桥接自己写下的最终关闭裁决（shutdownCode / microphoneIsolated）。
SUPERVISION_STATE_FILE=$DATA_ROOT/supervisor-state.json
# 本脚本自己的 PID（agentcallctl restart 据此确认“重启请求有接收者”）；请求标记里保存
# 同一个 PID，跨实例/跨开机的过期标记不会被新实例消费。
SERVICE_PID_FILE=$DATA_ROOT/service.pid
RESTART_REQUEST_FILE=$DATA_ROOT/restart.requested
# 测试/诊断注入点：真机读内核的 /proc/net/unix；覆盖只改变本脚本的探测来源。
UNIX_LIST=${AGENTCALL_UNIX_LIST:-/proc/net/unix}

BOOT_WAIT_SECONDS=600
MAX_RESTARTS=3
# 操作者主动请求的重启不消耗故障预算，但也不能无限：每次请求只允许一次重启，且有总上限。
MAX_REQUESTED_RESTARTS=10
RESTART_DELAY_SECONDS=5
READY_WAIT_SECONDS=20
# BridgeMain 启动前等待音频角色 abstract socket 的总上限：覆盖音频监督进程的整个有限
# 重启预算（每轮 READY_WAIT_SECONDS + RESTART_DELAY_SECONDS）。超时则不启动半就绪桥接。
AUDIO_READY_TIMEOUT_SECONDS=${AGENTCALL_AUDIO_READY_TIMEOUT_SECONDS:-$((READY_WAIT_SECONDS * (MAX_RESTARTS + 1) + RESTART_DELAY_SECONDS * MAX_RESTARTS))}
MAX_LOG_BYTES=262144
# PID 文件首行允许的最大位数：Linux/Android 的 pid_max 是 7 位（默认 4194304），
# 更长的数字串一律按损坏内容处理，不解析成巨大的 PID 去 kill。
MAX_PID_DIGITS=7
# 停止/回收的上限：TERM 之后最多等 STOP_GRACE_SECONDS 秒，KILL 之后最多再等
# KILL_WAIT_SECONDS 秒；两个上限保证 on_stop 不会无限挂住。
STOP_GRACE_SECONDS=${AGENTCALL_STOP_GRACE_SECONDS:-3}
STALE_GRACE_SECONDS=${AGENTCALL_STALE_GRACE_SECONDS:-3}
KILL_WAIT_SECONDS=2
# 停止音频工作进程时最多重新读取几次 PID 文件：监督循环可能在终止窗口内写入新的 PID，
# 每轮都要重新核验身份；轮数有界，避免停止路径被拖长。
MAX_WORKER_STOP_ROUNDS=2

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

# WebUI 把一次性 config-set 负载（含明文密钥）写进 inbox 后，正常路径会在 CLI 返回后删除。
# WebView 崩溃或进程被杀时不会执行删除，明文文件会跨启动存活；启动时清理本模块拥有的
# 固定前缀文件（固定 config-set.json 与唯一命名的 config-set*.json），不碰其它内容。
clean_inbox_payloads() {
    cleaned=0
    for payload in "$INBOX_DIR"/config-set*.json; do
        [ -e "$payload" ] || continue
        if rm -f "$payload" 2>/dev/null; then
            cleaned=$((cleaned + 1))
        fi
    done
    if [ "$cleaned" -gt 0 ]; then
        log_line "inbox_payload_cleaned count=$cleaned"
    fi
}

bridge_pid=
audio_manager_pid=
stopping=0
# 0 = 未持有；1 = flock 文件描述符；2 = mkdir 回退目录。
stop_lock_held=0
# 上一次已记录的持久裁决：同一次退出里 bridge_container_supervisor 与 shutdown_children
# 会各读一次，值相同时不重复写日志。
PERSISTED_VERDICT_LOGGED=

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

# 读取 PID 文件：首行必须是单个十进制 PID（1..MAX_PID_DIGITS 位），缺失/损坏/越界一律当作没有。
# 先校验原始首行再做数字规范化：否则 "12 34" 会被 tr 静默拼成 1234（与 uninstall.sh 同一纪律）。
read_pid() {
    file=$1
    [ -f "$file" ] || return 1
    raw=$(head -n 1 "$file" 2>/dev/null)
    case "$raw" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "${#raw}" -le "$MAX_PID_DIGITS" ] || return 1
    candidate=$(printf '%s' "$raw" | tr -cd '0-9')
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

# 有界回收子进程（阶段 B）：只有确认进程消失后才 wait；否则 wait 会无限阻塞停止路径
# （进程卡在不可中断状态时，卸载的 15 s 等待会被它整个拖住）。
reap_child() {
    child=$1
    [ -n "$child" ] || return 0
    waited=0
    while [ "$waited" -lt "$KILL_WAIT_SECONDS" ] && pid_alive "$child"; do
        sleep 1
        waited=$((waited + 1))
    done
    if pid_alive "$child"; then
        log_line "reap_timeout pid=$child"
        return 1
    fi
    wait "$child" 2>/dev/null
    return 0
}

# 先停子 shell 监督循环：否则我们在杀掉 worker 之后，它还会按重启预算再拉起一个新的。
stop_audio_worker_supervisor() {
    if [ -n "$audio_manager_pid" ] && pid_alive "$audio_manager_pid"; then
        terminate_pid "$audio_manager_pid" "$STOP_GRACE_SECONDS" audio_worker_supervisor
        reap_child "$audio_manager_pid"
    fi
    audio_manager_pid=
}

# 再按独立 PID 文件停真正的音频工作 app_process。
# 与 reclaim_stale_worker 同一纪律：只有 cmdline 核验为音频工作进程时才终止；核验失败
# 只记录机器可读原因，绝不 kill（PID 可能已被系统分给无关进程）。
# 每轮都重新读取 PID 文件：终止窗口内监督循环可能写入新的工作进程 PID；轮数有界，
# 保证停止路径仍是有界等待。
stop_audio_worker() {
    rounds=0
    worker=$(read_pid "$AUDIO_PID_FILE" 2>/dev/null) || worker=
    while [ -n "$worker" ] && [ "$rounds" -lt "$MAX_WORKER_STOP_ROUNDS" ]; do
        rounds=$((rounds + 1))
        if ! pid_alive "$worker"; then
            break
        fi
        if ! is_audio_worker_pid "$worker"; then
            log_line "audio_worker_pid_ignored reason=identity_mismatch pid=$worker"
            break
        fi
        terminate_pid "$worker" "$STOP_GRACE_SECONDS" audio_worker
        # 读回核验：只有 PID 文件出现了不同的 PID（监督循环重启了工作进程）才继续。
        next=$(read_pid "$AUDIO_PID_FILE" 2>/dev/null) || next=
        if [ "$next" = "$worker" ]; then
            break
        fi
        worker=$next
    done
    rm -f "$AUDIO_PID_FILE" 2>/dev/null
}

# 从固定 CLI 的单行 JSON 里取一个标量：只用于把关闭结果写进机器可读日志，
# 不是通用 JSON 解析器。键名由本脚本固定传入，不接受外部输入。
json_scalar() {
    printf '%s' "$1" | sed -n "s/.*\"$2\":\\([^,}]*\\).*/\\1/p" | head -n 1
}

json_text() {
    printf '%s' "$1" | sed 's/^"//; s/"$//'
}

# 读回桥接持久关闭裁决（Task 11H1）：schema 3 的 supervisor-state.json 是桥接进程自己
# 原子写下的最终结论。只读固定标量（不是通用 JSON 解析器）。成功时设置
# PERSISTED_SCHEMA / PERSISTED_CODE（空 = null）/ PERSISTED_ISOLATED（true|false）；
# 文件缺失、损坏、非 schema 3+ 或字段非法都返回失败，绝不猜测干净。
read_persisted_shutdown_verdict() {
    PERSISTED_SCHEMA=
    PERSISTED_CODE=
    PERSISTED_ISOLATED=
    [ -f "$SUPERVISION_STATE_FILE" ] || return 1
    persisted=$(head -c 65536 "$SUPERVISION_STATE_FILE" 2>/dev/null) || return 1
    [ -n "$persisted" ] || return 1
    schema=$(json_scalar "$persisted" schema)
    case "$schema" in
        ''|*[!0-9]*) return 1 ;;
    esac
    # schema 3 才保证 shutdownCode 字段存在；更旧的 schema 不能据此断言干净。
    [ "$schema" -ge 3 ] || return 1
    isolated=$(json_scalar "$persisted" microphoneIsolated)
    case "$isolated" in
        true|false) ;;
        *) return 1 ;;
    esac
    code=$(json_text "$(json_scalar "$persisted" shutdownCode)")
    case "$code" in
        ''|null) PERSISTED_CODE= ;;
        *) PERSISTED_CODE=$code ;;
    esac
    PERSISTED_SCHEMA=$schema
    PERSISTED_ISOLATED=$isolated
    verdict="schema=$schema shutdownCode=${PERSISTED_CODE:-null} microphoneIsolated=$PERSISTED_ISOLATED"
    if [ "$PERSISTED_VERDICT_LOGGED" != "$verdict" ]; then
        PERSISTED_VERDICT_LOGGED=$verdict
        log_line "bridge_persisted_verdict $verdict"
    fi
    return 0
}

# 退出码 0 只说明进程正常结束，不证明关闭裁决干净（Task 11H1）：持久状态里
# shutdownCode 非 null 或隔离仍为 true 时返回失败，调用方绝不能记录 bridge_exit_clean。
bridge_exit_clean_verdict() {
    read_persisted_shutdown_verdict || return 0
    if [ -n "$PERSISTED_CODE" ] || [ "$PERSISTED_ISOLATED" = "true" ]; then
        return 1
    fi
    return 0
}

# 关闭响应/status 都不可用时用持久裁决给出显式告警：桥接已退出时这是唯一可读的
# 关闭结论。返回 0 = 已读且干净，1 = 已读且不干净，2 = 没有可读裁决。
report_persisted_shutdown_verdict() {
    read_persisted_shutdown_verdict || return 2
    if [ "$PERSISTED_ISOLATED" = "true" ]; then
        log_line "! microphone_still_isolated source=persisted shutdownCode=${PERSISTED_CODE:-null}"
        log_line "bridge_stop_unclean reason=microphone_isolated source=persisted"
        return 1
    fi
    if [ -n "$PERSISTED_CODE" ]; then
        log_line "! shutdown_unresolved source=persisted shutdownCode=$PERSISTED_CODE"
        log_line "bridge_stop_unclean reason=shutdown_unresolved source=persisted"
        return 1
    fi
    log_line "bridge_stop_clean clean=true shutdownCode=null microphoneIsolated=false source=persisted"
    return 0
}

# 跨脚本停止锁（I4）：uninstall.sh 与 service.sh 都可能发起优雅关闭，两个独立 CLI
# shutdown 会在对方的恢复窗口内互相踩踏。锁文件是 root 私有固定路径；等待有界，
# 超时只记录机器可读原因（绝不无限阻塞停止路径）。优先 flock；没有 flock 时退回
# mkdir 原子目录锁，语义相同（同一路径只允许一个持有者）。
acquire_stop_lock() {
    [ "$stop_lock_held" -eq 0 ] || return 0
    log_line "stop_lock_wait path=$STOP_LOCK_FILE timeout=${STOP_LOCK_WAIT_SECONDS}s"
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$STOP_LOCK_FILE" 2>/dev/null || {
            log_line "stop_lock_unavailable path=$STOP_LOCK_FILE reason=open_failed"
            return 1
        }
        if flock -w "$STOP_LOCK_WAIT_SECONDS" 9 2>/dev/null; then
            stop_lock_held=1
            log_line "stop_lock_acquired path=$STOP_LOCK_FILE"
            return 0
        fi
        exec 9>&- 2>/dev/null
        log_line "! stop_lock_timeout waited=${STOP_LOCK_WAIT_SECONDS}s path=$STOP_LOCK_FILE"
        return 1
    fi
    lock_dir="$STOP_LOCK_FILE.d"
    waited=0
    while [ "$waited" -lt "$STOP_LOCK_WAIT_SECONDS" ]; do
        if mkdir "$lock_dir" 2>/dev/null; then
            stop_lock_held=2
            log_line "stop_lock_acquired path=$lock_dir fallback=mkdir"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    log_line "! stop_lock_timeout waited=${waited}s path=$lock_dir fallback=mkdir"
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

# 最终 status 探测（桥接在退出等待后仍存活时）：读回 phase / shutdownCode /
# microphoneIsolated，供“仍被隔离”告警使用。探测本身有界；结果只写全局，
# 失败不阻断后续有界回收。
probe_bridge_status() {
    STATUS_PHASE=
    STATUS_ISOLATED=
    STATUS_CODE=
    if [ -z "$bridge_pid" ] || ! pid_alive "$bridge_pid"; then
        return 1
    fi
    if ! is_bridge_pid "$bridge_pid"; then
        log_line "bridge_status_probe_skipped reason=identity_mismatch pid=$bridge_pid"
        return 1
    fi
    rm -f "$STATUS_RESULT_FILE" 2>/dev/null
    "$APP_PROCESS" -Djava.class.path="$BRIDGE_JAR" /system/bin --nice-name=agentcall-cli "$CLI_MAIN_CLASS" status > "$STATUS_RESULT_FILE" 2>/dev/null &
    probe_pid=$!
    waited=0
    while pid_alive "$probe_pid" && [ "$waited" -lt "$STATUS_PROBE_WAIT_SECONDS" ]; do
        sleep 1
        waited=$((waited + 1))
    done
    if pid_alive "$probe_pid"; then
        log_line "bridge_status_probe_timeout pid=$probe_pid waited=${waited}s"
        kill -TERM "$probe_pid" 2>/dev/null
        waited=0
        while pid_alive "$probe_pid" && [ "$waited" -lt "$KILL_WAIT_SECONDS" ]; do
            sleep 1
            waited=$((waited + 1))
        done
        if pid_alive "$probe_pid"; then
            kill -KILL "$probe_pid" 2>/dev/null
        fi
        reap_child "$probe_pid"
        rm -f "$STATUS_RESULT_FILE" 2>/dev/null
        return 1
    fi
    reap_child "$probe_pid"
    status_result=$(cat "$STATUS_RESULT_FILE" 2>/dev/null)
    rm -f "$STATUS_RESULT_FILE" 2>/dev/null
    STATUS_PHASE=$(json_text "$(json_scalar "$status_result" supervisionPhase)")
    STATUS_ISOLATED=$(json_scalar "$status_result" microphoneIsolated)
    STATUS_CODE=$(json_text "$(json_scalar "$status_result" shutdownCode)")
    log_line "bridge_status_probe phase=${STATUS_PHASE:-unknown} microphoneIsolated=${STATUS_ISOLATED:-unknown} shutdownCode=${STATUS_CODE:-unknown}"
    case "$STATUS_ISOLATED" in
        true|false) return 0 ;;
    esac
    return 1
}
# 优雅关闭桥接（任务 11D2b 的硬顺序）：
# 1. 只在桥接确实由本实例拉起且 cmdline 核验通过时执行，绝不猜测 PID；
# 2. 用固定 CLI（AgentCallCli shutdown，无参数、不执行任意 shell）请求关闭；
#    桥接在返回响应前会终止/交还拥有的通话并读回恢复麦克风——此时音频工作进程必须活着；
# 3. CLI 与桥接进程的等待都独立有界；超时只记录机器码并返回失败，由调用方继续有界回收。
request_bridge_graceful_shutdown() {
    if [ -z "$bridge_pid" ] || ! pid_alive "$bridge_pid"; then
        log_line "bridge_shutdown_skipped reason=not_running"
        # 桥接已被外部关闭/自己退出：没有响应可读时，持久裁决是唯一剩下的证据。
        report_persisted_shutdown_verdict || true
        return 0
    fi
    if ! is_bridge_pid "$bridge_pid"; then
        log_line "bridge_shutdown_skipped reason=identity_mismatch pid=$bridge_pid"
        report_persisted_shutdown_verdict || true
        return 1
    fi
    shutdown_clean=
    shutdown_code=
    shutdown_isolated=
    bridge_gone=0
    cli_timed_out=0
    log_line "bridge_shutdown_request pid=$bridge_pid"
    rm -f "$SHUTDOWN_RESULT_FILE" 2>/dev/null
    "$APP_PROCESS" -Djava.class.path="$BRIDGE_JAR" /system/bin --nice-name=agentcall-cli "$CLI_MAIN_CLASS" shutdown > "$SHUTDOWN_RESULT_FILE" 2>/dev/null &
    cli_pid=$!
    waited=0
    while pid_alive "$cli_pid" && [ "$waited" -lt "$GRACEFUL_SHUTDOWN_WAIT_SECONDS" ]; do
        sleep 1
        waited=$((waited + 1))
    done
    if pid_alive "$cli_pid"; then
        log_line "bridge_shutdown_cli_timeout pid=$cli_pid waited=${waited}s"
        kill -TERM "$cli_pid" 2>/dev/null
        waited=0
        while pid_alive "$cli_pid" && [ "$waited" -lt "$KILL_WAIT_SECONDS" ]; do
            sleep 1
            waited=$((waited + 1))
        done
        if pid_alive "$cli_pid"; then
            kill -KILL "$cli_pid" 2>/dev/null
        fi
        reap_child "$cli_pid"
        rm -f "$SHUTDOWN_RESULT_FILE" 2>/dev/null
        cli_timed_out=1
    else
        reap_child "$cli_pid"
        result=$(cat "$SHUTDOWN_RESULT_FILE" 2>/dev/null)
        shutdown_clean=$(json_scalar "$result" clean)
        shutdown_code=$(json_text "$(json_scalar "$result" shutdownCode)")
        shutdown_isolated=$(json_scalar "$result" microphoneIsolated)
        log_line "bridge_shutdown_result clean=${shutdown_clean:-unknown} shutdownCode=${shutdown_code:-unknown} microphoneIsolated=${shutdown_isolated:-unknown}"
        rm -f "$SHUTDOWN_RESULT_FILE" 2>/dev/null
    fi
    # 两个等待彼此独立：CLI 超时后仍要等桥接进程退出；桥接仍未退出时做一次最终
    # status 探测，读回隔离状态（关闭响应缺失/不可信时这是唯一的读回来源）。
    waited=0
    while pid_alive "$bridge_pid" && [ "$waited" -lt "$BRIDGE_EXIT_WAIT_SECONDS" ]; do
        sleep 1
        waited=$((waited + 1))
    done
    if pid_alive "$bridge_pid"; then
        log_line "bridge_shutdown_unconfirmed pid=$bridge_pid waited=${waited}s limit=${BRIDGE_EXIT_WAIT_SECONDS}s"
        probe_bridge_status || true
    else
        bridge_gone=1
        log_line "bridge_shutdown_confirmed pid=$bridge_pid waited=${waited}s limit=${BRIDGE_EXIT_WAIT_SECONDS}s"
    fi
    # I2：麦克风隔离是独立事实，不能只看 clean/shutdownCode。桥接仍存活时以最终
    # status 读回为准；已退出时以关闭响应为准。Task 11H1：持久状态（schema 3）是
    # 桥接自己写下的最终裁决，响应/status 缺失时用它补证，冲突时取更严重的一项。
    isolated=$shutdown_isolated
    isolated_source=shutdown
    if [ "$bridge_gone" -eq 0 ]; then
        case "$STATUS_ISOLATED" in
            true|false)
                isolated=$STATUS_ISOLATED
                isolated_source=status
                ;;
        esac
    fi
    persisted_verdict=2
    read_persisted_shutdown_verdict && persisted_verdict=0
    persisted_unclean=0
    if [ "$persisted_verdict" -eq 0 ]; then
        if [ "$PERSISTED_ISOLATED" = "true" ]; then
            if [ "$isolated" != "true" ]; then
                isolated=$PERSISTED_ISOLATED
                isolated_source=persisted
            fi
        elif [ -n "$PERSISTED_CODE" ]; then
            persisted_unclean=1
        elif [ "$bridge_gone" -eq 1 ] && [ "$isolated" != "true" ]; then
            # 持久裁决干净且桥接已退出：补足缺失/不可信的关闭响应证据。
            isolated=false
            isolated_source=persisted
        fi
    fi
    if [ "$isolated" = "true" ]; then
        log_line "! microphone_still_isolated source=$isolated_source clean=${shutdown_clean:-unknown} shutdownCode=${shutdown_code:-unknown} persistedCode=${PERSISTED_CODE:-unknown}"
        log_line "bridge_stop_unclean reason=microphone_isolated source=$isolated_source bridgeGone=$bridge_gone"
        return 1
    fi
    if [ "$persisted_unclean" -eq 1 ]; then
        log_line "! shutdown_unresolved source=persisted shutdownCode=$PERSISTED_CODE"
        log_line "bridge_stop_unclean reason=shutdown_unresolved source=persisted bridgeGone=$bridge_gone"
        return 1
    fi
    if [ "$isolated" != "false" ]; then
        log_line "bridge_stop_unclean reason=microphone_isolation_unconfirmed source=$isolated_source bridgeGone=$bridge_gone"
    elif [ "$shutdown_clean" = "true" ] && [ "$shutdown_code" = "null" ] && [ "$bridge_gone" -eq 1 ]; then
        log_line "bridge_stop_clean clean=true shutdownCode=null microphoneIsolated=false"
    elif [ "$persisted_verdict" -eq 0 ] && [ "$bridge_gone" -eq 1 ]; then
        log_line "bridge_stop_clean clean=true shutdownCode=null microphoneIsolated=false source=persisted"
    fi
    if [ "$cli_timed_out" -eq 1 ]; then
        return 1
    fi
    [ "$bridge_gone" -eq 1 ] || return 1
    return 0
}

shutdown_children() {
    # 硬顺序：取跨脚本停止锁 → 桥接优雅关闭（恢复麦克风需要音频工作进程存活）→
    # 停音频工作进程 → 对仍未退出的进程做有界 TERM→KILL。任何一步失败都不能颠倒
    # 后面的顺序；锁确保 uninstall.sh 与本次停止只跑一次优雅关闭序列。
    if acquire_stop_lock; then
        request_bridge_graceful_shutdown
    else
        log_line "bridge_shutdown_skipped reason=stop_lock_timeout"
        # 拿不到锁时仍读回持久裁决：桥接可能已经退出，警告不能因此丢失。
        report_persisted_shutdown_verdict || true
    fi
    stop_audio_worker_supervisor
    stop_audio_worker
    if [ -n "$bridge_pid" ] && pid_alive "$bridge_pid"; then
        terminate_pid "$bridge_pid" "$STOP_GRACE_SECONDS" bridge
    fi
    if [ -n "$bridge_pid" ]; then
        reap_child "$bridge_pid"
    fi
    bridge_pid=
    release_stop_lock
    rm -f "$PID_FILE" "$AUDIO_PID_FILE" "$SERVICE_PID_FILE" "$SHUTDOWN_RESULT_FILE" "$STATUS_RESULT_FILE" 2>/dev/null
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

# 模块被禁用或标记移除时不启动/不重启（KernelSU 用 disable/remove 标志文件表达状态）。
# 按 KernelSU 的标记语义，卸载会先写 remove/disable 标记（设备上的实际卸载时序未验证）；
# 重启循环每一轮都要重新检查，标记存在时在飞的循环不得复活桥接。
module_marked_stopped() {
    [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]
}

if module_marked_stopped; then
    log_line "module_disabled_or_removed"
    exit 0
fi

rotate_log
log_line "service_start moddir=$MODDIR"
clean_inbox_payloads

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
    grep -q "$1" "$UNIX_LIST" 2>/dev/null
}

# 重启请求：内容是发起请求时的 service.sh PID（单行十进制）。只有本实例的请求才算数；
# 上一次开机/上一实例遗留的标记会被显式丢弃，绝不因为一个旧文件而多拉起一次桥接。
restart_requested() {
    [ -f "$RESTART_REQUEST_FILE" ] || return 1
    requested_by=$(head -n 1 "$RESTART_REQUEST_FILE" 2>/dev/null)
    case "$requested_by" in
        ''|*[!0-9]*) requested_by= ;;
    esac
    if [ "$requested_by" != "$$" ]; then
        log_line "bridge_restart_stale requested_by=${requested_by:-invalid} service_pid=$$"
        rm -f "$RESTART_REQUEST_FILE" 2>/dev/null
        return 1
    fi
    return 0
}

clear_restart_request() {
    rm -f "$RESTART_REQUEST_FILE" 2>/dev/null
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

# BridgeMain 启动门禁（I1）：音频工作角色是独立 app_process，需要 app_process + framework
# 初始化后才 bind abstract socket。旧实现后台启动音频后立刻启动 BridgeMain，正常开机可能
# 输掉竞争，桥接启动期读不到音频角色而进入 BLOCKED。这里在 BridgeMain 之前有界等待：
# - 上限覆盖音频监督进程的整个有限重启预算；
# - 等待期间每轮重新检查 remove/disable 标记；
# - 超时只记录并返回失败，调用方据此跳过 BridgeMain（绝不启动半就绪桥接）。
wait_for_audio_role() {
    waited=0
    while [ "$waited" -lt "$AUDIO_READY_TIMEOUT_SECONDS" ]; do
        if module_marked_stopped; then
            log_line "audio_worker_wait_skipped marked_stopped"
            return 1
        fi
        if socket_present "$AUDIO_SOCKET"; then
            log_line "audio_worker_ready socket=$AUDIO_SOCKET waited=${waited}s"
            return 0
        fi
        if [ -z "$audio_manager_pid" ] || ! pid_alive "$audio_manager_pid"; then
            log_line "audio_worker_wait_failed reason=supervisor_exit waited=${waited}s"
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
    log_line "audio_worker_ready_timeout waited=${waited}s"
    return 1
}

# 音频工作角色：独立 app_process + 自己的有限重启预算；PID 文件里必须是 app_process 本身。
audio_worker_supervisor() {
    restarts=0
    while [ "$restarts" -le "$MAX_RESTARTS" ]; do
        if module_marked_stopped; then
            log_line "audio_worker_start_skipped marked_stopped"
            return 0
        fi
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

# bridge 容器（控制面 + 监督角色）：保持原有有限重启与干净退出语义，并新增
# “操作者请求的重启”：桥接干净退出（退出码 0）后若存在本实例的 restart.requested，
# 消费标记并在同一有限循环内重新拉起（不消耗故障预算，但每次请求只换一次重启且有上限）。
bridge_container_supervisor() {
    restarts=0
    requested_restarts=0
    while [ "$restarts" -le "$MAX_RESTARTS" ]; do
        if module_marked_stopped; then
            log_line "bridge_start_skipped marked_stopped"
            return 0
        fi
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
            # 11H1：退出码 0 不是干净关闭的证据。桥接退出后先读回它写下的持久裁决；
            # shutdownCode 非 null 或隔离仍为 true 时打印告警、不记录 bridge_exit_clean，
            # 也绝不按“干净退出”重启。
            if ! bridge_exit_clean_verdict; then
                if restart_requested; then
                    clear_restart_request
                    log_line "bridge_restart_skipped reason=shutdown_unclean"
                fi
                log_line "bridge_exit_unclean code=0 shutdownCode=${PERSISTED_CODE:-null} microphoneIsolated=${PERSISTED_ISOLATED:-unknown}"
                return 1
            fi
            if restart_requested; then
                if module_marked_stopped; then
                    clear_restart_request
                    log_line "bridge_restart_skipped reason=marked_stopped"
                    log_line "bridge_exit_clean"
                    return 0
                fi
                if [ "$requested_restarts" -ge "$MAX_REQUESTED_RESTARTS" ]; then
                    clear_restart_request
                    log_line "bridge_restart_budget_exhausted requested=$requested_restarts"
                    return 1
                fi
                clear_restart_request
                requested_restarts=$((requested_restarts + 1))
                log_line "bridge_restart_requested requested=$requested_restarts"
                log_line "bridge_exit_clean"
                continue
            fi
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

# service.sh 自己的 PID：agentcallctl restart 在写请求前核验“接收者还在”。
printf '%s\n' "$$" > "$SERVICE_PID_FILE" 2>/dev/null

audio_worker_supervisor &
audio_manager_pid=$!

# I1：必须先等到音频角色 socket，再启动 BridgeMain。音频监督进程保留自己的有限重启预算；
# 总等待有上限，超时则跳过桥接并做有界回收（绝不让开机流程无限挂住）。
if wait_for_audio_role; then
    bridge_container_supervisor
    bridge_status=$?
else
    log_line "bridge_start_skipped reason=audio_not_ready"
    bridge_status=1
fi

if [ "$stopping" -eq 0 ]; then
    log_line "service_cleanup reason=bridge_exit status=$bridge_status"
fi
shutdown_children

exit "$bridge_status"

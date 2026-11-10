#!/system/bin/sh
# AgentCall 卸载清理（spec §8/§11）。
#
# 顺序（必须）：先请求桥接停止并交还/终止其托管的 AI 会话，再清理私有数据。
# - 停止请求走的是固定 CLI 的 shutdown，不执行任意 shell，也不读取 argv 之外的输入。
# - 桥接收到 shutdown 后先结束其会话/租约（由桥接自身的关闭路径负责），再关闭 socket。
# - 若桥接在限定时间内没有响应，卸载仍然继续（模块必须可移除），但会显式打印
#   “未能确认交还”的警告，不谎称已经交还。
# - 只删除 /data/adb/agentcall（配置、密钥、配对、日志、临时文件）。
# - 不改 Google 电话默认角色，不动 vendor 音频配置，不重置系统属性；Hook 自动控制
#   随模块移除退出，Google 电话仍是默认应用。

set -u
umask 077

MODDIR=${0%/*}
DATA_ROOT=/data/adb/agentcall
CONTROL_SOCKET=@org.krvyft.agentcall.control
CTL=$MODDIR/bin/agentcallctl
STOP_WAIT_SECONDS=15

say() {
    printf '%s\n' "$*"
}

control_socket_present() {
    grep -q "$CONTROL_SOCKET" /proc/net/unix 2>/dev/null
}

say "- AgentCall: 卸载开始"

if control_socket_present && [ -x "$CTL" ]; then
    say "- 请求桥接停止并交还其托管的通话会话"
    "$CTL" shutdown > /dev/null 2>&1
    waited=0
    while control_socket_present; do
        waited=$((waited + 1))
        if [ "$waited" -ge "$STOP_WAIT_SECONDS" ]; then
            break
        fi
        sleep 1
    done
    if control_socket_present; then
        say "! 未能在 ${STOP_WAIT_SECONDS}s 内确认桥接停止：可能存在尚未交还的 AI 通话，请在 Google 电话中手动结束后再清除数据"
    else
        say "- 桥接已停止"
    fi
else
    say "- 桥接未运行，跳过停止请求"
fi

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

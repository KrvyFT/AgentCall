#!/system/bin/sh
# AgentCall 安装定制脚本（由 KernelSU 安装器 source；见 KernelSU 模块文档）。
#
# 只做三件事：
# 1. 给固定命令包装器补可执行位（模块文件默认 0644）。
# 2. 校验桥接运行时容器存在（缺失则中止安装并给出可操作信息）。
# 3. 预创建 root 私有数据目录（0700），不迁移、不改写既有配置。
#
# 明确不做：不改 Google 电话默认角色，不改 vendor 音频策略，不改 webroot 权限
# （KernelSU 安装时自动设置，用户不要覆盖），不写 system.prop，不动 SELinux。

set -u

ui_print "- AgentCall: 校验模块内容与权限"

if [ ! -f "$MODPATH/bridge/agentcall-bridge.jar" ]; then
    ui_print "! 缺少 bridge/agentcall-bridge.jar（桥接运行时容器）"
    abort "AgentCall 模块缺少桥接运行时容器，请使用 tools/package.py 产出的模块 ZIP"
fi

if [ ! -f "$MODPATH/bin/agentcallctl" ]; then
    abort "AgentCall 模块缺少 bin/agentcallctl"
fi

if [ ! -f "$MODPATH/webroot/index.html" ]; then
    abort "AgentCall 模块缺少 webroot/index.html"
fi

set_perm "$MODPATH/bin/agentcallctl" 0 0 0755

if [ -d "$MODPATH/bridge" ]; then
    set_perm_recursive "$MODPATH/bridge" 0 0 0755 0644
fi

# webroot 的权限与 SELinux context 由 KernelSU 安装时统一设置；这里刻意不碰。

umask 077
DATA_ROOT=/data/adb/agentcall
mkdir -p "$DATA_ROOT" "$DATA_ROOT/inbox" "$DATA_ROOT/logs" 2>/dev/null
chmod 0700 "$DATA_ROOT" "$DATA_ROOT/inbox" "$DATA_ROOT/logs" 2>/dev/null

ui_print "- AgentCall: root 私有配置目录就绪（/data/adb/agentcall，0700）"
ui_print "- AgentCall: 未修改 Google 电话默认角色、vendor 音频配置或 SELinux"
ui_print "- AgentCall: 安装完成后请在 APK 内配对并完成校准，再开启总开关"

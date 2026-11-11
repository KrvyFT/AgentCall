# 排障手册

按“症状 → 证据 → 修复”组织。所有命令都假设设备已 root（KernelSU），并且模块目录是
`/data/adb/modules/agentcall`。**先收集证据，再改状态**；不要用“重装/重启”掩盖一个可读的
机器码。

## 0. 先收集最小证据集

```sh
# 1) 设备与模块
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell su -c 'cat /data/adb/modules/agentcall/module.prop'

# 2) 桥接/服务日志（最关键）
adb shell su -c 'tail -n 200 /data/adb/agentcall/logs/service.log'

# 3) 控制面是否活着
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl status'
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl diagnose'

# 4) 进程与 socket
adb shell ps -A | grep -E 'app_process|agentcall'
adb shell su -c 'grep @org.krvyft.agentcall /proc/net/unix'

# 5) Hook 日志（不含号码/负载的机器码）
adb logcat -d -s AgentCall:V | grep -E 'hook_transport|installed|RINGING'
```

日志里常见且**可安全公开**的机器码：

| 行 | 含义 |
| --- | --- |
| `package_provider=<context\|appglobals\|binder\|shell>` | 本次桥接选中的包事实来源 |
| `package_provider_attempt=<code> result=unavailable` | 该层构造失败，继续下一层 |
| `package_provider_unavailable candidates=...` | 四层全失败，桥接随后退出 |
| `bridge_ready socket=@org.krvyft.agentcall.control` | 控制面已就绪 |
| `hook_transport connect_failed\|server_untrusted\|protocol_violation failures=<n>` | Hook 传输失败（约 5 s 限流一条） |
| `stop_lock_wait` / `stop_lock_acquired` / `! stop_lock_busy` / `! stop_lock_timeout` | 停止锁状态 |

归档前删除真实号码、联系人、IP、API Key；`calibration.json` 若含号码先脱敏。

## 1. 桥接没有启动（`package_manager_unavailable`）

**症状**：`agentcallctl status` 返回 `transport_failed` 或 `bridge_container_missing`；
`ps` 里没有 `BridgeMain`；`/proc/net/unix` 没有控制 socket。

**证据**：

```sh
adb shell su -c 'grep -E "package_provider|package_manager_unavailable|bridge_start|bridge_exit" \
  /data/adb/agentcall/logs/service.log'
```

- 看到 `package_provider=<...>`：至少有一层成功；继续查控制 socket/权限。
- 看到 `package_provider_attempt=... result=unavailable` 多行，最后
  `package_provider_unavailable candidates=...`：四层全失败，桥接以
  `package_manager_unavailable` 退出（退出码 1，fail closed）。
- 只有 `package_manager_unavailable` 没有 attempt 行：模块/容器/入口不完整，先确认 ZIP 是
  `tools/package.py` 的产物，且 `bridge/agentcall-bridge.jar` 存在。

**修复**：

1. 确认模块是完整 ZIP 安装（不是手工拷文件），重启手机；
2. 确认 KernelSU 已启用模块、`/data/adb/modules/agentcall/bridge/agentcall-bridge.jar` 存在；
3. 仍失败时把完整 `service.log` 与 `getprop ro.build.fingerprint` 一起上报。**不要**
   通过改 SELinux/手工加 provider 绕过；失败关闭是设计。
4. 设备历史观测：11N 之后该设备落在 `package_provider=appglobals`；这是已观测落点，不表示
   其他 ROM 的 binder/shell 分支都可用。

## 2. WebUI 报 `transport_failed`

**症状**：页面能打开，但状态/保存/诊断都失败，错误码 `transport_failed`，
可能伴随 `response_invalid` 或 `io_unavailable`。

**证据**：

```sh
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl status'
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl diagnose'
adb shell su -c 'tail -n 100 /data/adb/agentcall/logs/service.log'
adb shell su -c 'grep @org.krvyft.agentcall.control /proc/net/unix'
adb shell ps -A | grep -E 'app_process|BridgeMain'
```

- root CLI 也失败：桥接进程/控制 socket 不在 → 先按第 1 节处理。
- root CLI 成功、只有 WebUI 失败：管理器注入/`ksu.exec`/模块目录问题 → 查 WebUI 诊断页
  `ioMode`、`moduleDir`；见第 6 节。
- CLI 返回 JSON 但页面 `response_invalid`：clue 在页面 console 或日志；确认不是旧版
  WebUI 缓存，刷新/重装模块 ZIP。

**修复**：

1. 先让 root CLI 恢复：`agentcallctl restart`（要求关闭裁决干净；不干净会返回
   `restart_refused`，不要强杀）；
2. 仍无控制面则重启手机；
3. 不要用 `adb shell`（uid 2000）直接执行 `agentcallctl`；它要求 root，会报
   `cli_requires_root`。

## 3. `hookConnected=false` / Hook 传输失败

**症状**：`status.hookConnected` 为 `false`（或 `"unknown"`），来电没有被观察/接管。

**背景**：旧实现只在有存活会话时发心跳，所以一次真实 RINGING 时 `hookConnected=false`。
11Q 起 HOOK 每 2 s 发无会话、空负载的 `hook.presence`，bridge 用 5000 ms 窗口判定；
**该修复尚未在设备重启后复测**。因此先区分“旧构建/未重启”与“真的没注入”。

**证据**：

```sh
adb logcat -d -s AgentCall:V | grep -E 'installed|hook_transport|hook.presence|RINGING'
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl status' | tr ',' '\n' | grep -E 'hookConnected|dialerDefaultRole'
```

- `installed 4 of 4 hook(s)`：注入成功。
- `hook_transport connect_failed`：Hook 连不上控制 socket（桥接没起/权限/socket 名）。
- `hook_transport server_untrusted`：连到的服务端内核 uid 不是 0（被抢绑/桥接异常）。
- `hook_transport protocol_violation`：帧/方法/版本不符（旧构建混装）。
- 完全没有 `installed`：框架没加载模块，转第 4 节。

**修复**：

1. 确认模块 ZIP 与 APK 是同一版本并重启（旧 Hook + 新 bridge 会协议/方法不匹配）；
2. 确认 Vector/LSPosed 作用域只有 `com.google.android.dialer`；
3. 强制停止 Google 电话或重启，让 Hook 重新加载；
4. 仍失败：抓 `logcat` 的 `AgentCall` 行与 `service.log`，不要只报“连不上”。

## 4. Vector / LSPosed 作用域

**症状**：`installed 0 of N hook(s)`、日志没有 `Loading legacy module`，或 Hook 加载到
错误进程。

**证据**：

```sh
adb logcat -d | grep -E 'VectorLegacyBridge|LSPosed|AgentCall|xposed'
adb shell su -c 'cat /data/adb/modules/zygisk_vector/module.prop 2>/dev/null'
adb shell su -c 'cat /data/adb/modules/zygisk_lsposed/module.prop 2>/dev/null'
```

**修复**：

1. 在 Vector/LSPosed 中启用 AgentCall 模块；
2. 作用域**只勾选** `com.google.android.dialer`；不要勾选“系统框架”/ `system_server`；
3. 强制停止 Google 电话（或重启），确认日志出现
   `Loading legacy module org.krvyft.agentcall`、`Loading class ...HookEntry`、
   `installed 4 of 4 hook(s)`；
4. 不要重签/替换 Google 电话；不要把它设为非默认拨号器来“试试”。

设备已观测：Vector v2.2（legacy Xposed API 82）在该 ROM 上可注入 4/4 Hook；这不等于
其他 ROM/框架版本也成立。

## 5. 默认拨号器显示 `unknown`

**症状**：`status.dialerDefaultRole` 为 `"unknown"`，或 WebUI 概览显示“未知”。

**证据**（root shell）：

```sh
adb shell su -c 'cmd role get-role-holders android.app.role.DIALER'
adb shell su -c 'cmd telecom get-default-dialer'
adb shell dumpsys package com.google.android.dialer | grep -m1 versionName
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl diagnose' | tr ',' '\n' | grep -E 'packageProvider|dialer'
```

- 两条命令都应输出 `com.google.android.dialer`；只认恰好一个合法包名，任何歧义/多包名都
  按 `unknown` 处理，不猜。
- RoleManager 不可读时 bridge 才走这两条 root 固定命令（2 s 超时、8 KiB 上限、30 s 缓存）；
  仍读不到就是 `unknown`。

**修复**：

1. 在系统设置中把 Google 电话设为默认电话应用（AgentCall 不会自动改角色）；
2. 确认没有第二个拨号器抢占默认角色；
3. 若 Google 电话被禁用/卸载，先恢复它；AgentCall 不替代拨号器。
4. 设备已通过 root shell 确认默认拨号器是 `com.google.android.dialer`；新构建的 boolean
   读回仍待复测。

## 6. WebUI 保存只读 / 分块 env 失败

**症状**：保存按钮禁用；错误 `secret_transport_unsupported` / `env_unavailable` /
`payload_too_large`；诊断页显示 `ioMode=none`。

**证据**：WebUI 诊断页那一行：

- `ioForm`：`object` / `function` / `none`
- `ioSupport`：`true` / `false` / `null`
- `ioVersion`、`hasFile`、`hasFileOutputStream`
- `ioMode`：`file` / `env` / `none`
- `envProbe`：`skipped` / `pending` / `ok` / `unavailable`
- 失败消息：`transport=env bytes=<n> chunks=<k>`

判读：

| 现象 | 结论 |
| --- | --- |
| `ioMode=file` | 首选通道可用；保存走唯一 inbox 文件 + stdin |
| `ioMode=env` + `envProbe=ok` | exec+env 回退可用；负载拆成 `AGENTCALL_CFG_PAYLOAD_0..N`（128 码元/块，最多 64） |
| `ioMode=none` + `env_unavailable` | 两条通道都不可用，只读；保存被拒绝 |
| `payload_too_large` | 超过 64 块；减少名单/提示词长度，或改用 root CLI stdin |
| `module_dir_unresolved` | `moduleInfo().moduleDir` 不可信；需要模块目录的命令禁用 |

设备历史证据：目标管理器的 `ksu.io` 是**函数**形式，没有 `File`/`FileOutputStream`
（`ioForm=function`、`ioSupport=null`、`ioVersion=0`、`hasFile=false`、
`hasFileOutputStream=false`），所以只能走 env 回退或只读；**该回退未在真机 WebView 验证**。

**修复**：

1. 首选升级 KernelSU 管理器，让 `ksu.io` 提供 `File`+`FileOutputStream`；
2. 不能升级时，确认管理器 exec 透传 `options.env`（探测 canary 通过后 `ioMode=env`）；
3. 两者都不行：在 root shell 用固定 CLI 从 stdin 提交，不要用 argv/base64：

   ```sh
   adb shell su -c 'cat > /data/adb/agentcall/inbox/config-set.cli.json' <<'JSON'
   {"expectedRevision":7,"patch":{"enabled":false}}
   JSON
   adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl config-set < /data/adb/agentcall/inbox/config-set.cli.json'
   adb shell su -c 'rm -f /data/adb/agentcall/inbox/config-set.cli.json'
   ```

   （`adb shell su -c 'cat > ...' <<'JSON'` 的 heredoc 由本机 shell 传给远端 stdin；
   如果设备端不继承 stdin，先把文件推到 `/data/local/tmp` 再用 root 重定向。）
4. 保存成功只以后端回读的 `revision` 与密钥状态为准；不要相信仅前端提示。

## 7. `stop.lock` 超时 / 停止卡住

**症状**：`service.log` 出现 `! stop_lock_timeout waited=40s`；停止/卸载很慢；或
`app_process` 持有锁 fd。

**证据**：

```sh
adb shell su -c 'grep -E "stop_lock|bridge_shutdown|bridge_exit|bridge_stop" \
  /data/adb/agentcall/logs/service.log'
adb shell su -c 'for p in $(pidof app_process); do ls -l /proc/$p/fd 2>/dev/null | grep -q stop.lock && echo "INHERITED pid=$p"; done'
```

期望（11N 起）：

- 桥接仍存活：`stop_lock_wait` → `stop_lock_acquired`（有界等待对端释放）；
- 桥接已退出：只做一次非阻塞 `flock -n`，允许 `stop_lock_acquired ... nonblocking=1` 或
  `! stop_lock_busy ... nonblocking=1`；
- 不应再出现 40 s 的 `! stop_lock_timeout`；
- `INHERITED` 不应有输出（每个 `app_process` 都 `9>&-`）。

**修复**：

1. 不要手动删除 `stop.lock` 后立刻重试；先确认是否还有桥接/音频进程在运行：
   `ps -A | grep app_process`；
2. 用 `agentcallctl restart` 走有界关闭；
3. 若确实卡死且通话已结束，重启设备是最干净的恢复；
4. 仍复现：保留 `service.log` 与 `ps`/`fd` 输出上报；这是设备时序问题，不要用
   `kill -9` 掩盖，可能留下麦克风隔离。

## 8. 卸载/停止警告：麦克风可能仍隔离

**症状**：`uninstall.sh` / `service.sh` 打印：

```text
! microphone_still_isolated ...
! shutdown_unresolved ...
bridge_stop_unclean
```

**含义**：桥接没有确认“通话已断开且麦克风已恢复”。脚本只能有界停止进程，**不能**替用户
结束正在进行的通话或解除系统隔离。

**证据**：

```sh
adb shell su -c 'cat /data/adb/agentcall/supervisor-state.json'
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl status'
adb shell dumpsys audio | grep -i -E 'mute|mode'
```

判读：`shutdownCode` 非 null、`microphoneIsolated=true`、phase `BLOCKED` 都表示未干净闭环；
不要因为进程退出就认为已恢复。

**修复**：

1. 先在 Google 电话里手动结束通话；
2. 确认麦克风状态（系统设置/其他录音应用）；必要时重启设备让系统音频状态重新初始化；
3. 重启后仍 `BLOCKED`：按 `blockedReason` 处理，确认没有旧通话/隔离后再重新启用模块；
4. 只有 `clean=true` + `shutdownCode=null` + `microphoneIsolated=false` + phase
   `IDLE`/`ENDED`（或进程/socket 已退出）才算干净关闭。

## 9. 升级后连不上：重新配对

**症状**：`peer_version_mismatch`、`peer_uid_mismatch`、`peer_unpaired`、
WebUI 显示“配对已失效”。

**原因**：配对记录绑定 uid + 签名 SHA-256 + `versionName`。APK 升级、Google 电话升级、
APK 卸载重装都会让旧记录失效。

**修复**：

```sh
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl pair'
# 可选：显式指定 APK 包名（默认 org.krvyft.agentcall）
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl pair org.krvyft.agentcall'
```

- 首次配对只能 root CLI；WebUI 的“重新配对”只是调用同一条固定命令。
- 同一 debug 签名、同一 `versionName`、同一 uid 的覆盖安装通常不需要重配；签名/版本/uid
  任一变化才需要。
- 配对失败先看第 1 节（桥接）与第 4 节（Hook/包事实）；shell provider 下签名不可验证会
  fail closed，不是可以忽略的告警。

## 10. 校准/环境字段 UNKNOWN

**症状**：`calibrate` 返回 `calibration_environment_unknown` /
`calibration_environment_mismatch`；或 `diagnose.environment` 里
`frameworkVersion`/`simCarrier`/`transport`/`route` 为 `UNKNOWN`。

**证据**：

```sh
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl diagnose'
adb shell su -c 'for m in zygisk_vector zygisk_lsposed lsposed vector edxposed; do \
  f=/data/adb/modules/$m/module.prop; [ -f "$f" ] && echo "== $m" && cat "$f"; done'
adb shell getprop | grep -E 'lsposed|vector|edxposed|gsm.sim.operator|ro.cdma.home.operator'
adb shell dumpsys telephony.registry | grep -i -E 'carrier|sim'
```

| 字段 | 观测路径 | 仍 UNKNOWN 时 |
| --- | --- | --- |
| `frameworkVersion` | root SystemProperties → 模块 `module.prop` 的 `version=` → XposedBridge | 可由 root 在 `operatorAttestation.frameworkVersion` 背书（1..128 字符、无控制字符） |
| `simCarrier` | TelephonyManager SIM 运营商名 → telephony 属性，逗号列表取首段 | 可由 root 背书 |
| `transport` / `route` | **从不观测**，始终 UNKNOWN | 只能由 root 背书；不背书就不能解锁 |
| 其他六个 | bridge 观测 | 不能背书；必须先让观测成功 |

设备期望（尚未在新构建读回）：`frameworkVersion` =
`v2.2 (3080-88f8e1fa-JingMatrix-Vector)`（来自 `zygisk_vector/module.prop`），
`simCarrier` = `China Unicom`（`China Unicom,China Unicom` 取首段）。

**修复**：

1. 先确保桥接能读到上述来源（Vector/LSPosed 模块存在、root 可读 `module.prop`、SIM 已插入）；
2. 仍 UNKNOWN 的字段，在 `calibrate` 的 `operatorAttestation` 中由 root 操作员背书；
   观测到的已知值优先，背书不能覆盖；
3. 五项人工测试未全部完成/环境不一致时，**不要**伪造；校准失败是预期行为。
4. 校准记录**不会**让自动接听直接开启：还有 `UnverifiedCallIdentityVerifier` 的产品闸门。

## 11. 常见错误码速查

| 码 | 含义 | 处理 |
| --- | --- | --- |
| `bridge_container_missing` | 模块缺少桥运行时容器 | 用 `tools/package.py` 的 ZIP 重装 |
| `cli_requires_root` | `agentcallctl` 不是 root | 用 `su -c` |
| `package_manager_unavailable` | 四层包事实全失败 | 重装完整 ZIP/重启；见第 1 节 |
| `peer_unpaired` | 未配对 | `agentcallctl pair` |
| `peer_version_mismatch` | APK/拨号器版本变了 | 重新 `pair` |
| `peer_uid_mismatch` | APK 卸载重装换了 uid | 重新 `pair` |
| `peer_untrusted` | 对端签名/包不可信（常见于 shell provider） | 检查 provider/重装；不要绕过 |
| `secret_transport_unsupported` | 文件与 env 两条保存通道都不可用 | 升级管理器或 root CLI stdin |
| `env_unavailable` | exec 不透传 env/探测失败 | 只读；升级管理器/root CLI |
| `payload_too_large` | 超过 64 个分块 | 缩短负载或 root CLI |
| `inbox_write_failed` | 无法写 root 私有 inbox | 检查 `/data/adb/agentcall` 权限 |
| `service_not_running` | `service.sh` 已退出 | 重启设备/重新启用模块 |
| `module_disabled` | 模块被 disable/remove | 先启用/重装 |
| `restart_unconfirmed` | 桥接没有接受 shutdown | 刷新状态；必要时重启 |
| `restart_refused` | 关闭裁决不干净/仍隔离/缺失 | 先手动结束通话并确认麦克风 |
| `shutdown_call_unresolved` | 5 s 内无法确认断开 | 手动结束通话；确认隔离；重启 |
| `shutdown_microphone_restore_failed` | 已断开但恢复读回失败 | 人工确认麦克风；不要当已恢复 |
| `calibration_unavailable` | 校准存储未接线/不可用 | 检查模块版本；不要伪造 |
| `calibration_environment_unknown` | 环境事实不完整 | 补齐观测或 root 背书 |
| `calibration_environment_mismatch` | 预期环境与当前不一致 | 在当前环境重测五项 |
| `config_conflict` | CAS 冲突 | 重新 `config-get` 后提交，不要自动重试 |
| `config_validation_unavailable` | 反射/校验依赖抛 Throwable | 检查模块版本；固定机器码，非 `internal_error` |

## 12. 设备当前不在线时

如果 `adb devices` 为空，以上步骤都无法执行；不要根据旧日志宣称“已修复”。把问题与已有
证据（型号、ROM 指纹、模块/APK 哈希、`service.log`、`logcat`）记录到 issue，等设备恢复后
按 [docs/device-validation.md](device-validation.md) 的翻绿规则重测。

# AgentCall 真机验证矩阵（任务 5：音频、校准与故障终止）

- 依据：`docs/superpowers/specs/2026-11-10-agentcall-design.md` §8（会话状态与故障恢复）、§9（音频与校准）。
- 范围：受限通话音频提取/注入、PCM16 单声道与重采样、麦克风隔离、心跳故障终止、人工接管、特权 Telecom 终止、持久校准证据。
- 记录时间：2026-11-10（Asia/Shanghai）。
- **当前结论：本文件所有验证项均为「未验证」。没有任何一行被真机执行过，也没有任何一行被标记为「已验证」。**
- JVM 单元测试（`:core:testDebugUnitTest`、`:bridge:testDebugUnitTest`）只证明无设备可测的逻辑；它们不是、也不能代替任何一行真机结论。

## 1. 状态定义与翻绿规则

| 状态 | 含义 |
| --- | --- |
| 未验证 | 没有在目标真机上执行；默认状态。 |
| 已验证通过 | 在目标真机上按本行步骤执行，全部通过判据成立，原始证据已归档。 |
| 已验证不支持 | 在目标真机上确认该 ROM/构建明确不支持（例如特权终止身份无法核验）；此时自动接听必须保持关闭，这是终态而不是失败。 |
| 回归失败 | 已执行但不符合判据；必须阻止发布，修复后重跑。 |
| 阻塞 | 因缺少进程入口、root、模块或对端设备而无法执行；不是通过，仍按未验证处理。 |

翻绿（把「未验证」改成「已验证通过/已验证不支持」）必须同时满足：

1. 由人工在目标设备上实际执行该行步骤，而不是静态阅读代码或运行 JVM 测试；
2. 本行「翻转所需证据」全部归档到 `docs/device-evidence/<日期>-<行号>/`（或外部证据库，本文件写链接/哈希）；
3. 记录设备型号、序列号、ROM 指纹、SDK、内核、Google 电话版本、模块版本、执行时间与执行人；
4. 在任务报告和本文件同时更新状态；只改本文件或只改报告都不算。

音频类结论还必须满足 spec §9：方法存在、权限通过、对象创建成功都不等于对端收到语音；只有「对端听到测试语音」与「本机麦克风隔离生效」同时成立，才能解锁对应环境。

## 2. 当前环境事实（只读观察，不是验证结果）

编写本文件时从本机执行了以下只读命令：

~~~console
$ adb devices
List of devices attached
QV77169GHY	device

$ adb shell getprop ro.product.model
XQ-DQ72
$ adb shell getprop ro.build.version.sdk
37
$ adb shell getprop ro.build.version.release
17
$ adb shell getprop ro.build.fingerprint
Sony/XQ-GE74/XQ-GE:17/73.1.A.2.74/073001A002007401400374815:user/release-keys
$ adb shell dumpsys package com.google.android.dialer | grep -m1 versionName
    versionName=245.0.1006864670
$ adb shell getprop ro.product.device
XQ-DQ72
$ adb shell getprop ro.product.name
XQ-DQ72
$ adb shell uname -r
5.15.189-android13-8-00004-g1c3825f8ac0a-ab14110541
$ adb shell id
uid=2000(shell) gid=2000(shell) ... context=u:r:shell:s0
$ adb shell su -c id
/system/bin/sh: su: inaccessible or not found
$ adb shell ls -ld /data/adb/modules/agentcall
ls: /data/adb/modules/agentcall: Permission denied
~~~

结论与限制：

- 本机曾观察到一台 API 37（Android 17）Xperia 设备；这只说明设备可用，**不构成任何验证结果**。本次没有在该设备上安装模块、启动桥接、发起真实通话或执行音频测试。
- `adb shell` 是 uid 2000 的 shell，没有 `su`；本会话无法通过 adb 读取 `/data/adb/modules/agentcall`。因此 **KernelSU 模块、`agentcallctl`、真实特权音频/终止路径都无法从当前 adb 会话执行**。需要设备侧 root 入口或人工在 KernelSU 管理器中完成安装后再验证。
- 设备/ROM 可能在后续任务中被替换；每行证据必须重新记录实际设备，不得沿用本节的只读信息。
- 型号与指纹字段说明（2026-11-10 用上表命令重新只读复核）：`ro.product.model` = `XQ-DQ72`，而 `ro.build.fingerprint` 的 `Sony/XQ-GE74/XQ-GE` 是 ROM 构建的 product/device 段；两者是不同系统属性，不是互相矛盾的观察。设备序列号与两个值均为只读观察，**仍不是验证结果**。

## 3. 通用前置条件（每一行都默认需要）

1. 目标设备已安装与本仓库提交对应的 KernelSU 模块，Google 电话 `com.google.android.dialer` 保持默认电话角色，未被重签。
2. 设备已 root；`agentcallctl` 可用；`/data/adb/agentcall` 为 root 私有目录。
3. 已准备第二部电话（对端）用于真实呼叫、听取 TTS 与判断本机麦克风是否泄露。
4. 总开关、名单、API 配置按测试目标设置；测试号码写入黑名单（或使用白名单内号码），不要用真实敏感号码归档。
5. 除专门测试「未校准」的行外，音频类验证需先完成第 6 节的五项人工校准。
6. 所有会改系统状态的步骤（安装模块、改默认电话角色、开关 VoWiFi/蓝牙）由人工确认后执行；本文件不授权自动化脚本改这些状态。

通用证据命令（在有 root 的验证会话中执行）：

~~~console
mkdir -p docs/device-evidence/YYYY-MM-DD-D01
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl status' \
  > docs/device-evidence/YYYY-MM-DD-D01/status.json
adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl diagnose' \
  > docs/device-evidence/YYYY-MM-DD-D01/diagnose.json
adb shell su -c 'cat /data/adb/agentcall/logs/service.log' \
  > docs/device-evidence/YYYY-MM-DD-D01/service.log
adb shell su -c 'cat /data/adb/agentcall/calibration.json' \
  > docs/device-evidence/YYYY-MM-DD-D01/calibration.redacted.json
adb logcat -d -v threadtime > docs/device-evidence/YYYY-MM-DD-D01/logcat.txt
adb shell dumpsys telecom > docs/device-evidence/YYYY-MM-DD-D01/telecom.txt
adb shell dumpsys audio > docs/device-evidence/YYYY-MM-DD-D01/audio.txt
adb shell dumpsys media.audio_flinger > docs/device-evidence/YYYY-MM-DD-D01/audio_flinger.txt
adb shell dumpsys telephony.registry > docs/device-evidence/YYYY-MM-DD-D01/telephony.txt
adb shell ps -A > docs/device-evidence/YYYY-MM-DD-D01/ps.txt
adb shell grep @org.krvyft.agentcall.control /proc/net/unix \
  > docs/device-evidence/YYYY-MM-DD-D01/socket.txt
~~~

归档前必须脱敏：删除真实电话号码、联系人、地址、IP、API Key；`calibration.json` 不应包含电话号码（若包含则删改后再归档）。

## 4. 验证矩阵总览

| 编号 | 场景 | 状态 | 主要判据 |
| --- | --- | --- | --- |
| D01 | 锁屏/Doze 下来电 | 未验证 | 锁屏不阻塞自动接听；仅接听一次；通话结束恢复 |
| D02 | 重启与进程重建 | 未验证 | 重启后桥接恢复；旧租约不绑定新通话；校准仍按环境绑定 |
| D03 | 双 SIM 与 PhoneAccount | 未验证 | 使用实际账户，不切卡；未校准 SIM 不自动接听 |
| D04 | VoWiFi | 未验证 | VoLTE 校准不继承到 VoWiFi；无证据时明确不支持/保持关闭 |
| D05 | 蓝牙/耳机路由 | 未验证 | 默认只校准手持路由；未校准路由不自动接听 |
| D06 | 音频建立失败（10000 ms 窗口） | 未验证 | 停 AI、保持隔离；窗口内接管；到期才终止；确认断开后才恢复 |
| D07 | 人工接管 | 未验证 | 先撤销所有权再恢复；不挂断；旧通知不能控制新通话 |
| D08 | 麦克风隔离与 TTS 注入 | 未验证 | 对端听不到本机环境声，但能听到 TTS；二者不能互相替代 |
| D09 | 心跳 5000 ms 故障终止 | 未验证 | 只终止自己租约下的精确通话；断开确认前不恢复麦克风 |
| D10 | Hook 死亡后的特权 Telecom 终止 | 未验证 | 身份不可核验时明确 UNSUPPORTED 且阻止自动接听 |
| D11 | 音频进程故障与有限重启 | 未验证 | 预算内重启；耗尽后故障终止；隔离保持 |
| D12 | 校准证据与环境绑定 | 未验证 | 五项全过且环境一致才解锁；任一字段变化立即失效 |

> 进程级注入（D06/D09/D11）的当前阻塞：`service.sh` 已同时启动 `BridgeMain`（控制面 + 监督角色 + 500 ms 轮询）与独立 `AudioWorkerMain`，但本机 adb 是 uid 2000 无 `su`，无法安装模块、定点 kill/stop 监督或音频工作进程。该阻塞不改变 JVM 测试结论，也不代表真机通过。

## 5. 逐行步骤、判据与翻转证据

### D01 锁屏/Doze 下来电

前置：模块已启用、总开关开、测试号码命中、五项校准完成、屏幕锁定（PIN/图案），设备不要接 USB 调试以外的特殊状态。

步骤：

1. 让设备进入锁屏并熄屏；如需，执行 `adb shell dumpsys deviceidle force-idle` 进入 Doze（记录命令与恢复方式）。
2. 用第二部电话呼叫测试号码。
3. 观察设备是否在 3000 ms 延迟后自动接听；不要手动解锁或点亮屏幕。
4. 对端应听到开场白；按 D08 检查麦克风隔离与上行注入。
5. 对端挂断；确认 AI 会话停止，麦克风恢复，通知消失。
6. 重复一次「用户先手动接听」场景：设备响铃时人工接听，确认 AI 不再下发任何自动命令。

通过判据：锁屏/Doze 不阻止自动接听；每通电话最多接听一次；无重复 Answer；人工先接听后无 AI 命令；断开后隔离恢复且只恢复一次。

翻转所需证据：锁屏状态录屏（含系统时间）、对端通话录音、`logcat` 与 `service.log`、`dumpsys telecom`、`dumpsys audio`、校准 JSON 哈希。

### D02 重启与进程重建

前置：模块已安装；记录重启前 `status.json`、`ps`、socket、校准文件哈希。

步骤：

1. `adb shell su -c '/data/adb/modules/agentcall/bin/agentcallctl status'` 保存基线。
2. `adb reboot`，等待 `sys.boot_completed=1`。
3. 检查 `ps -A | grep -i agentcall`、`grep @org.krvyft.agentcall.control /proc/net/unix`、`service.log` 的启动/重启次数。
4. 重启后立刻发起来电：在心跳/校准重新就绪前不得自动接听。
5. 完成一次正常通话，验证校准仍绑定同一环境；随后修改一个环境字段（如切 VoWiFi）验证失效。

通过判据：桥接恢复且 socket 存在；旧租约不映射到新通话；重启不被当成「已验证」；环境变化使旧证据失效；无未确认的麦克风恢复。

翻转所需证据：重启前后 `status.json`、`ps.txt`、`socket.txt`、`service.log`、`logcat`、两次 `diagnose.json`、校准文件前后对比。

### D03 双 SIM 与 PhoneAccount

前置：两张 SIM 均可用；默认电话角色为 Google 电话；分别记录两张 SIM 的 PhoneAccountHandle（`dumpsys telecom`）。

步骤：

1. 只校准 SIM1（手持/VoLTE）；SIM2 保持未校准。
2. 分别从第二部电话呼叫 SIM1、SIM2。
3. 每次记录实际接听的 PhoneAccount、AI 是否自动接听、是否切卡。
4. 在 SIM1 通话中观察是否只处理该通话；另一路活动/等待/会议不自动接管（spec §7）。

通过判据：SIM1 校准通过后仅 SIM1 自动接听；SIM2 未校准不得自动接听；不通过切卡规避校准；第二路不误接管。

翻转所需证据：两次 `dumpsys telecom`（含 PhoneAccountHandle）、`diagnose.json`、`telephony.txt`、对端录音、`logcat`/`service.log`、校准 JSON。

### D04 VoWiFi

前置：SIM 支持 Wi-Fi 通话；先完成 VoLTE/手持校准，再开启 VoWiFi。

步骤：

1. 记录当前承载（`dumpsys telephony.registry`、运营商设置页）与 `diagnose.json`。
2. 开启 Wi-Fi 通话（可开飞行模式 + Wi-Fi，按运营商能力），确认注册成功。
3. 从第二部电话呼叫；观察是否自动接听。
4. 如需验证 VoWiFi 本身，按第 6 节五项人工测试重新校准 VoWiFi 环境后重试。
5. 关闭 VoWiFi，回到 VoLTE，确认 VoWiFi 证据不反向污染 VoLTE。

通过判据：VoLTE 校准不适用于 VoWiFi；无 VoWiFi 证据时保持关闭或明确不支持，绝不因「有信号」就自动接听；重新校准后只解锁 VoWiFi 环境。

翻转所需证据：承载截图/`telephony.txt`、`diagnose.json`、`calibration.json`、两次呼叫录音、`logcat`/`service.log`。

### D05 蓝牙/耳机路由

前置：默认校准仅手持无耳机（spec §9）；准备一只已配对蓝牙耳机。

步骤：

1. 手持路由完成校准并验证一次正常通话。
2. 连接蓝牙耳机，确认 `dumpsys audio` 中通话路由为蓝牙；不要重新校准。
3. 发起来电，观察是否自动接听。
4. 在 AI 通话中切换路由（手持→蓝牙→手持），观察是否按 spec §8 交还/终止，而不是继续用未校准路由。
5. 若要验证蓝牙路由本身，重新完成五项人工校准后再测。

通过判据：未校准的蓝牙路由不自动接听；路由变化不静默继续 AI；重新校准后蓝牙证据只绑定蓝牙环境；麦克风隔离在耳机路由下仍成立（按 D08 方法）。

翻转所需证据：`dumpsys audio` 路由、`dumpsys bluetooth_manager`、路由切换录屏、对端录音、校准 JSON、`logcat`/`service.log`。

### D06 音频建立失败与 10000 ms 人工接管窗口

前置：ACTIVE 前/后可注入音频失败；本行至少覆盖「ANSWER_PENDING→ACTIVE 窗口」和「ACTIVE 后」两种时机。

步骤：

1. 发起 AI 通话，在 ACTIVE 前后让下行提取或上行注入失败（优先在已接入入口后定点 kill 音频工作进程；不要用环境麦克风替代）。
2. 观察监督端是否先停止音频、保持麦克风隔离，并提示人工接管。
3. 在 10000 ms 内人工接管：确认恢复麦克风、无挂断、AI 停止。
4. 另起一轮不接管：窗口到期后监督端按精确 SessionId 终止；发出挂断后仍保持隔离。
5. 对端确认断开后，确认麦克风只恢复一次。

通过判据：失败不产生「无声成功」；10000 ms 内不提前终止（心跳失效是独立触发条件，需在证据中区分）；到期才终止；断开确认前不恢复；恢复失败进入 BLOCKED 并禁止新会话。

翻转所需证据：注入命令/进程号、`logcat`+`service.log` 时间线、`dumpsys audio`/`dumpsys telecom`、接管界面录屏、对端录音、`status.json`（phase/blockedReason）。

当前阻塞：进程入口已接入——`service.sh` 以独立 app_process 启动 `AudioWorkerMain`，bridge 容器（`BridgeMain`）内构造监督角色并以 500 ms 周期调用 `Supervisor.poll`；但本机 adb 是 uid 2000 无 `su`，无法安装模块、定点 kill 监督/音频进程或执行本行。仍未验证；JVM `AudioWorker`/`Supervisor` 测试不能替代本行。

### D07 人工接管

前置：AI 通话 ACTIVE；通知/接管界面可用；记录开始时间。

步骤：

1. 在 AI 通话中触发人工接管（通知 action 或 Calibration/Takeover 界面）。
2. 观察日志顺序：先撤销租约，再停止音频，最后恢复麦克风；不得先恢复再撤权。
3. 确认没有下发 Hangup；对端通话保持。
4. 用旧通知/SessionId 触发第二次接管，确认不能控制新通话。
5. 在 D06 的失败窗口内重复接管，确认同样顺序且不挂断。

通过判据：撤销失败不得显示成功；恢复失败进入 BLOCKED；不挂断；旧通知/旧 SessionId 被拒绝；麦克风只恢复一次。

翻转所需证据：接管界面录屏、`logcat`+`service.log` 顺序、`dumpsys telecom`、`status.json`、旧通知操作录屏、对端录音。

### D08 麦克风隔离与 TTS 注入（核心）

前置：AI 通话 ACTIVE；对端能听到 TTS；本机麦克风未被其他应用占用；记录 `dumpsys audio` 的 mute 状态。

步骤：

1. 让 AI 播放一段 TTS（配置固定开场白或触发回复），对端确认听到。
2. 在本机麦克风附近说话/播放环境声，持续 5 秒；对端必须听不到这些环境声。
3. 检查 `dumpsys audio`（及 `media.audio_flinger`）中的通话路由与静音状态，记录系统静音是否同时影响注入。
4. 触发人工接管或对端挂断；确认恢复后本机麦克风对普通通话可用。
5. 若系统静音会同时压掉 TTS，则隔离实现不满足 spec §9，必须记录失败并停止解锁。

通过判据：**同时**满足「对端听到 TTS」与「对端听不到本机环境声」；接管/断开后恢复；不能只测其中一项。这一行通过前，不得把 `CalibrationEvidence.microphoneIsolated` 置为 true。

翻转所需证据：对端侧录音（TTS 段与环境声段，带时间戳）、本机录屏、`dumpsys audio`/`media.audio_flinger`、`logcat`/`service.log`、校准 JSON（五项布尔值与时间戳）。

### D09 心跳 5000 ms 故障终止

前置：AI 通话 ACTIVE；已知 APP 与 Hook 的进程/心跳角色；第二部电话保持通话。

步骤：

1. 记录当前通话 SessionId、`dumpsys telecom` 与开始时间。
2. 让 APP 心跳停止（优先 `adb shell am force-stop org.krvyft.agentcall`，或按进程入口定点 STOP/kill；记录实际命令）。
3. 观察 5000 ms 后监督端是否先停止音频、保持隔离，再只终止自己租约下的精确通话。
4. 记录从心跳停止到 Hangup 的时间；确认其他活动通话未被全局 endCall 影响。
5. 发出挂断但未确认断开期间，确认麦克风仍隔离；确认断开后只恢复一次。
6. 用 Hook 死亡场景重复：Hook 的 Hangup 不可用时必须走 D10 的独立路径。

通过判据：5000 ms 超时触发；目标 SessionId 精确；不误杀其他通话；断开确认前不恢复麦克风；无法确认终止时保持隔离、提示用户并禁止新 AI 会话。

翻转所需证据：高速录屏/系统时间、`logcat`+`service.log` 时间戳、`dumpsys telecom` 前后、进程号、对端录音、`status.json`（phase=TERMINATING/blockedReason）。

当前阻塞：监督/音频进程入口已接入（`BridgeMain` 内监督角色 + 独立 `AudioWorkerMain`，`service.sh` 分别有限重启）；心跳按 SessionId 记录，外来会话心跳不能给旧通话续命，`poll` 在同一轮最多终止同一会话一次。但本机 adb 无 root，APP/Hook 心跳与故障注入仍无法执行。仍未验证。

### D10 Hook 死亡后的独立特权 Telecom 终止

前置：AI 通话 ACTIVE；Hook 进程可被终止；默认身份核对实现 `UnverifiedCallIdentityVerifier` 在目标 ROM 上未验证。

步骤：

1. 终止 Hook（`am force-stop com.google.android.dialer` 会结束通话/影响原应用，必须人工确认并记录风险）。
2. 让监督端尝试 Hook 挂断；Hook 不可用时应调用 `EmergencyCallControl.disconnectOwned`。
3. 在默认构建上确认返回 `RejectReason.UNSUPPORTED`（权限/身份不可核验），没有对任何通话执行 `endCall`。
4. 确认自动接听被阻止（`blocksAutoAnswer=true` / `status.json` 的 `privilegedTerminationAvailable=false`），并保持麦克风隔离。
5. 只有在目标 ROM 上另行验证了「可核对精确通话身份」的机制后，才允许替换 `UnverifiedCallIdentityVerifier` 并重测精确终止；禁止使用无选择的全局 endCall 验证「成功」。

通过判据：默认实现下明确 UNSUPPORTED 且阻止自动接听；不误杀其他通话；隔离保持到确认断开；若未来实现身份核验，则必须证明终止的是租约对应通话。

翻转所需证据：终止前后的 `dumpsys telecom`、`status.json`、`logcat`/`service.log`、自动接听被拒记录、Hook 终止命令/时间、对端通话结果。

备注（修复轮 1 后）：`ReflectiveTelecomTermination` 已改为**永远返回** `Rejected(UNSUPPORTED)`，不再反射调用全局 `TelecomManager.endCall()`（I6：全局调用会挂断无关通话且无视已核验身份）。因此默认构建下本行预期先得到「已验证不支持」；只有目标 ROM 上取得并验证「可按本次通话身份定位」的终止 API 后才允许替换实现。

### D11 音频进程故障与有限重启

前置：音频工作进程入口可用；D06/D09 已能定点注入。

步骤：

1. 在 AI 通话中杀死音频工作进程，记录第一次重启。
2. 在 60 秒窗口内重复 3 次；确认每次只允许有界重启并记录 `restartAttempts`。
3. 第 4 次故障应返回 `RestartExhausted`，监督端故障终止当前租约通话并保持隔离。
4. 确认没有无限重启，也没有在未确认断开时恢复麦克风。

通过判据：最多 3 次重启；预算耗尽后终止且隔离保持；重启不把旧音频流/旧租约带入新进程；日志可读。

翻转所需证据：每次 kill 的进程号/时间、`service.log` 重启计数、`status.json`（restartAttempts）、`logcat`、`dumpsys telecom`、对端录音。

当前阻塞：音频工作进程入口已接入（独立 app_process，`service.sh` 有限重启）；重启预算已移到监督端（`RestartBudget`，60 s 窗口最多 3 次），音频工作进程重启不会清零预算，预算耗尽触发 `terminateOwned`。本机 adb 无 root，仍无法执行 kill/重启注入。仍未验证。

### D12 校准证据与环境绑定

前置：已能采集完整环境；五项人工测试可在一次/多次测试电话中完成。

步骤：

1. 采集环境字段：SDK、ROM 指纹、内核、Google 电话版本、框架版本、桥接版本、音频策略摘要、SIM/承载/路由（见第 6 节）。
2. 依次完成五项：下行识别、对端听到测试语音、麦克风隔离、人工恢复、进程故障注入；每步记录证据。
3. 检查 `calibration.json`：五个布尔值均为 true、`calibratedAtMs > 0`、环境字段与当前一致；确认 `isVerified` 为 true。
4. 只改变一个字段（例如切 VoWiFi 或换蓝牙路由），确认 `loadFor` 返回 null、`isVerified` 为 false、自动接听关闭。
5. 损坏文件或写入未来 schema，确认 fail closed：读操作报错/`isVerified=false`，不覆盖原文件。
6. 在 API 36 上安装并尝试继承 API 37 证据，确认不继承（策略固定 API 37）。

通过判据：仅完整且环境完全一致的证据解锁；环境任一字段变化立即失效；损坏/未来 schema 失败关闭；创建音频对象成功不算已完成任何人工步骤。

翻转所需证据：`diagnose.json`、`calibration.json`（脱敏）、五项测试的录屏/对端录音、环境变化前后 `isVerified`/自动接听结果、损坏文件读取错误码。

## 6. 校准环境采集与五项人工测试

`EnvironmentKey` 的字段（`core/src/main/java/org/krvyft/agentcall/core/model/CapabilityModels.kt`）与采集方式：

| 字段 | 采集方式 | 当前状态 |
| --- | --- | --- |
| apiLevel | `adb shell getprop ro.build.version.sdk` | 只读观察到 37；未验证 |
| romFingerprint | `adb shell getprop ro.build.fingerprint` | 只读观察到 XQ-GE74 指纹；未验证 |
| kernelVersion | `adb shell uname -a` 或 `diagnose` | 未验证 |
| dialerPackageVersion | `adb shell dumpsys package com.google.android.dialer \| grep versionName` | 只读观察到 245.0.1006864670；未验证 |
| frameworkVersion | `agentcallctl diagnose`（Xposed 不可见时 UNKNOWN） | 未验证 |
| bridgeVersion | `agentcallctl status`（当前 BridgeInfo.VERSION=1.0） | 未验证 |
| audioPolicyDigest | `agentcallctl diagnose`（vendor/system audio policy SHA-256） | 未验证 |
| simCarrier | 通话上下文/`dumpsys telecom` + `dumpsys telephony.registry` | 未验证；`DeviceProbe` 当前写 UNKNOWN |
| transport | 通话承载（VoLTE/VoWiFi/CS） | 未验证；`DeviceProbe` 当前写 UNKNOWN |
| route | `dumpsys audio` 通话路由（手持/蓝牙/耳机） | 未验证；`DeviceProbe` 当前写 UNKNOWN |

注意：`DeviceProbe` 在没有通话上下文时把 SIM/承载/路由写成 `UNKNOWN`，而 `CalibrationPolicy.isComplete()` 会拒绝 `UNKNOWN`。因此在通话上下文补全这三项之前，任何环境都无法通过自动接听判定；这是 fail closed 的预期行为，不是绕过校准的理由。

五项人工测试（全部完成才允许 `calibratedAtMs` 非零并可能解锁）：

1. **下行识别**：AI 能识别对端语音（ASR 返回合理文本），且来源是通话下行而非环境麦克风。
2. **对端听到语音**：第二部电话实际听到 TTS 测试语音。
3. **麦克风隔离**：对端听不到本机环境声（与第 2 项同时成立）。
4. **人工恢复**：人工接管/挂断后，本机麦克风恢复正常，且 AI 不再持有音频。
5. **进程故障注入**：按 D09/D11 杀死相关进程后，监督端保持隔离、精确终止并仅在确认断开后恢复。

## 7. 任务 5 的 JVM 证据（不是真机证据）

已执行（可复现）：

~~~console
$ flock /tmp/agentcall-gradle.lock ./gradlew :core:testDebugUnitTest :bridge:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL
~~~

- 结果统计（JUnit XML，修复轮 1 最终）：core 108 tests / 0 failures，bridge 152 tests / 0 failures。
- 四个必须测试均通过：`streamCreationDoesNotUnlockCalibration`、`environmentChangeInvalidatesEvidence`（core `CalibrationPolicyTest`）、`revokedLeaseCannotDisconnect`、`audioFailureKeepsMicrophoneIsolated`（bridge `SupervisorTest`）。
- 修复轮 1 新增覆盖（全部 JVM）：角色生产图装配（`SupervisorProductionGraph` + 假平台）、周期轮询驱动、启动恢复与同一开机重启策略、状态原子持久化/损坏 fail closed、重启预算（监督端持有）、外来会话心跳（I1）、接听结果不明确的状态核验（I2）、迟到/重复 ACTIVE（I3）、迁移串行化（I4）、隔离读回（I7）、奇数字节 PCM 分类（M2）、每轮只终止一次（M4）、特权终止 fail closed（I6）、音频角色通道协议/代理/分派、生产路径真的创建流也不解锁校准（M5）。
- 这些结果只覆盖 JVM 逻辑：PCM16/重采样、反射分类、租约/心跳/窗口顺序、校准策略与持久化 fail closed。它们**没有**覆盖真实 AudioRecord/AudioTrack、HAL、Telecom、SELinux、KernelSU app_process 或任何真实通话。

## 8. 当前未验证/阻塞清单

- 全部 D01–D12：未验证。
- 设备侧 root/模块：本次 adb 会话无 `su`，无法安装/启动模块或执行 `agentcallctl`；需人工准备 root 环境。
- 监督/音频工作进程：入口已接入。`service.sh` 启动 `BridgeMain`（控制面 + 监督角色 + 500 ms 轮询 + 状态持久化）与独立 `AudioWorkerMain`（PCM/隔离，root 私有 abstract socket），各自有限重启；但本机 adb 无 root，D06/D09/D11 的进程级注入**仍未执行**，SELinux 域、app_process 类加载、真实 AudioManager 反射均未验证。
- 自动接听仍被两道 fail-closed 闸门关闭：校准证据未验证 + `UnverifiedCallIdentityVerifier` 不可用（`blocksAutoAnswer=true`）。因此真机上监督端当前不会拥有任何 AI 通话；D06/D09 需要先完成 D10 的身份核验与 D12 校准。
- 监督端持久状态 `supervisor-state.json`（含 boot_id）、同一开机重启策略（有旧所有权时保持隔离并 BLOCKED）、重启预算与隔离读回均只有 JVM 证据；崩溃/重启时序与 `SystemClock.elapsedRealtime` 行为未在真机验证。
- 特权 Telecom 精确终止：没有经过真机验证的身份来源；默认 `UnverifiedCallIdentityVerifier` 阻止自动接听，`ReflectiveTelecomTermination` 未在目标 ROM 验证。
- 音频 HAL/受限方法：未在任何真机上确认三个 AOSP 受限方法可见、可调用、可创建对象，更未确认对端能听到注入。
- 锁屏、重启、双 SIM、VoWiFi、蓝牙、通知权限、人工接管界面、麦克风隔离与 TTS 共存：均未在真机执行。

## 9. 维护规则

- 新增设备/ROM 时复制一行而不是覆盖旧证据；旧行保留其环境绑定。
- 任何代码改动（音频适配、监督逻辑、校准字段、模块入口）都可能使旧结论失效；按改动范围重跑受影响行，并在报告中写重跑原因。
- 状态翻转必须附证据路径/哈希；没有证据的「已验证」视为伪造，必须回退为未验证。
- 本文件不记录电话号码、联系人、密钥或未脱敏日志。
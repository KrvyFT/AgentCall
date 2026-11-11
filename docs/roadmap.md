# 路线图与阻塞项

本文件只记录“已经有什么证据”“还缺什么”“什么明确不做”。状态以代码 HEAD `647a52f`
与 [docs/verification.md](verification.md) 为准；没有设备证据的项一律不写“已完成”。

## 1. 当前结论

- 代码、构建、打包与本地测试矩阵已完成：JVM **922**、WebUI node **149**、Python **59**、
  lint 0 errors / 15 warnings、R8、`make package-check` PASS。
- 设备侧只有**部分观测**：Vector 注入 4/4 Hook、一次真实 RINGING、默认拨号器确认、
  `package_provider=appglobals`。新 ZIP/APK 尚未安装、未重启，D01–D12 全部未验证。
- **自动接听有意保持关闭**：校准证据 + 按通话身份定位的特权 Telecom 终止，两者缺一不可；
  第二项当前不存在，默认 `UnverifiedCallIdentityVerifier` 永远 `isAvailable()=false`。
- APK 只有 debug 签名，不可分发。

## 2. 已完成（有代码/本地证据）

| 项 | 证据 |
| --- | --- |
| 三进程架构 | Hook / bridge（监督 + 控制面）/ 独立 audio worker / APK |
| 名单与接管规则 | 三种模式、未知号码开关、PhoneAccount 门槛、紧急/会议/多路排除、完整号码精确匹配 |
| 随机会话与租约 | 随机 `SessionId`、单次 Answer、撤销终态、不全局 `endCall` |
| 会话状态机 | `IDLE→ELIGIBLE→PREPARING→ANSWER_PENDING→ACTIVE_AI→HANDOFF/ENDING→ENDED`，READY 丢失永久放弃本通 |
| 音频中继 | 控制/PCM 分离、帧上限、播放完成 ack、APP↔bridge↔worker 归属复核 |
| 故障处理 | 5 s 心跳、10 s 人工接管窗口、180 s 上限、音频进程 60 s/3 次重启、隔离读回 |
| 远程 AI | 三个独立 OpenAI-compatible 端点、HTTPS、VAD、45 s 生成预算、8 轮/300 字/8 MiB |
| 校准 | 五项人工测试 + 十字段环境绑定、API 37、root CLI `calibrate`、fail closed |
| 配置/WebUI | CAS、密钥 Keep/Replace/Clear、六条固定 CLI + `restart`、file/env/readonly 三态 |
| 关闭/卸载 | 5 s 等待断开、6 s 恢复预算、stop.lock、持久裁决 schema 3、未确认警告 |
| 打包 | 确定性 ZIP、DEX manifest、版本单一来源、SHA-256、签名类型检查 |
| 本地矩阵 | 922 JVM / 149 node / 59 Python / lint / R8 / package-check |

## 3. P0：阻塞“可用”的两道闸门

### 3.1 设备校准证据

**现状**：写入通道已存在（root CLI `calibrate` → `calibration.record`），环境观测代码已实现；
但**没有在设备上执行过五项人工测试**，也没有把设备环境读回与证据绑定。

**完成定义**：

1. 在目标设备完成五项测试：下行识别、对端听到 TTS、麦克风隔离、人工恢复、进程故障注入；
2. 每项有对端录音/录屏/时间戳证据，不只是“创建对象成功”；
3. 用 `agentcallctl calibrate` 从 stdin 写入，`calibration.read` 返回
   `verified=true`、`environmentComplete=true`、`environmentMatchesEvidence=true`；
4. 改变一个环境字段（如切 VoWiFi/蓝牙）后，`verified` 立即变 false；
5. 证据归档满足 [docs/device-validation.md](device-validation.md) 的翻绿规则（D12）。

**注意**：校准完成**不会**单独打开自动接听；还有 3.2。

### 3.2 按通话身份定位的特权 Telecom 终止

**现状**：默认 `ReflectiveTelecomTermination` **永远返回** `Rejected(UNSUPPORTED)`；
`UnverifiedCallIdentityVerifier.isAvailable()` 永远 false，因此
`EmergencyCallControl.blocksAutoAnswer() == true`。

**为什么不能用全局 `endCall`**：`TelecomManager.endCall()` 会结束当前所有通话，可能挂断
用户无关的电话；它也无法证明终止的是租约对应的那一通。这是 I6 的明确修复，不是未完成项。

**完成定义**：

1. 找到目标 ROM 上可**按本次通话身份定位**的终止 API（或等价的特权路径）；
2. 身份来源能证明“要终止的 Call 就是租约对应的 SessionId”，不是号码/当前活动通话猜测；
3. Hook 死亡后仍能只终止该通话，其他活动/保持/等待通话不受影响；
4. 在真机上重复验证：Hook 杀死、Bridge 重启、旧 SessionId、外来 SessionId 都不能误杀；
5. 把 `UnverifiedCallIdentityVerifier` 替换为真实实现后，才允许
   `blocksAutoAnswer=false`。

**验收**：D10（身份不可核验时明确 UNSUPPORTED）与 D09（心跳故障只终止租约内通话）同时通过。
在完成前，自动接听保持关闭是**正确行为**，不是 bug。

## 4. P1：设备验收与待验证路径

### 4.1 D01–D12 全矩阵

| 编号 | 场景 | 当前 |
| --- | --- | --- |
| D01 | 锁屏/Doze 下来电 | 未验证 |
| D02 | 重启与进程重建 | 未验证 |
| D03 | 双 SIM 与 PhoneAccount | 未验证 |
| D04 | VoWiFi | 未验证 |
| D05 | 蓝牙/耳机路由 | 未验证 |
| D06 | 音频建立失败 + 10 s 窗口 | 未验证 |
| D07 | 人工接管 | 未验证 |
| D08 | 麦克风隔离 + TTS 注入 | 未验证 |
| D09 | 心跳 5 s 故障终止 | 未验证 |
| D10 | Hook 死亡后的特权 Telecom 终止 | 未验证（默认预期 UNSUPPORTED） |
| D11 | 音频进程故障与有限重启 | 未验证 |
| D12 | 校准证据与环境绑定 | 未验证 |

每行的步骤、判据与翻转证据见 [docs/device-validation.md](device-validation.md)。JVM 测试
不能替代任何一行。

### 4.2 其他待验证路径

- **音频/HAL**：受限方法可见性、`AudioRecord`/`AudioTrack` 创建、播放头、重采样、
  真实下行/上行；系统静音是否同时压掉 TTS。
- **锁屏/Doze/重启/FGS/通知/进程死亡**：前台服务类型 `specialUse` 在目标 ROM 的实际行为；
  不能只凭 manifest 声明推断。
- **KernelSU WebUI**：真机 WebView 的 `ksu.io` 文件通道；无文件接口时的 exec+env 分块
  （`AGENTCALL_CFG_PAYLOAD_0..N`、128 码元/块、最多 64）；`ioMode` 从 `none` 提升为
  `env` 的读回。
- **Hook 空闲存在性/设备探针**：重启后空闲 `hookConnected=true`、`dialerDefaultRole=true`
  的 boolean 形态、frameworkVersion/simCarrier 的设备读回。
- **停止/卸载时序**：`stop.lock` fd 不继承、桥接已退出后的非阻塞清理、卸载警告与隔离读回。
- **分层 PackageManager**：设备只确认 `appglobals` 落点；binder/shell 分支、失败回落与
  真实权限未验证。
- **双卡/VoWiFi/蓝牙**：D03–D05；未测组合不启用。
- **真实付费 API**：没有发起过真实 ASR/LLM/TTS 请求；连接测试会计费，需人工确认。

## 5. P0/P1 之外的发布工作

### 5.1 发布签名

- 当前 APK 用本机 debug keystore 签名（`CN=Android Debug`，证书 SHA-256
  `3974a6d9...2cd81085`），**不可分发**。
- 需要：生成/保管发布密钥 → 重签 APK → 重新生成 `SHA256SUMS` → 用
  `check_package.py --expected-signer` 强制校验 → 更新安装文档中的哈希与证书。
- 重签会改变 APK 摘要；旧 `SHA256SUMS` 必然失效，不能沿用。

### 5.2 可选：Google Play / 分发策略

如果未来要上架或面向普通用户分发，需要单独评估（当前**没有做**，也不在首版验收内）：

- 默认拨号器/通话类应用的政策与角色要求；AgentCall 明确不请求默认拨号器角色；
- 前台服务类型 `specialUse` 的申报与审核；
- 通话录音/音频、隐私政策、数据安全表单与供应商披露；
- root/KernelSU 依赖的分发限制（很可能不适合 Play 主渠道）；
- 发布密钥、CI 签名、可复现构建与供应链说明。

这些是分发工作，不改变“自动接听仍需校准 + 身份终止”的产品闸门。

## 6. 明确不做（Out of scope）

- 替换 Google 电话或自研默认拨号器；
- 注入 `system_server`、改 SELinux、绕过 Android 权限；
- 微信/WhatsApp 等应用内通话；
- 完整双工打断（barge-in）；首版是轮流说话；
- 多通电话同时由 AI 处理；
- 承诺紧急呼叫、银行/验证码、法律/医疗场景可用；
- 对同设备 root 进程做密钥加密/防取证；
- 自动导入通讯录、上传号码/联系人；
- 未经验证的 mixer 数值、固定 Binder 编号、关闭 SELinux 的“适配”。

## 7. 建议顺序

1. **先发布签名与安装复现**：让设备能装同一构建，排除签名/版本混装。
2. **设备基础观测**：安装新 ZIP/APK → 重启 → 空闲 `hookConnected=true`、
   `dialerDefaultRole=true`、`package_provider`、校准环境字段读回。
3. **D12 校准**：五项人工测试 + `calibrate` + 环境变化失效。
4. **D10/D09 终止**：先证明默认 UNSUPPORTED 的失败关闭，再实现身份定位终止。
5. **D06/D07/D08/D11 音频**：隔离/TTS/接管/重启预算。
6. **D01/D02/D03/D04/D05**：锁屏/重启/双卡/VoWiFi/蓝牙。
7. **WebUI/停止/卸载**：真机 WebView、chunked env、stop.lock、卸载时序。
8. 只有以上 P0/P1 全部有证据后，才讨论“可用/可分发”。

## 8. 退出标准

- 自动接听：校准证据 + 身份定位终止都通过，且默认配置下仍可随时关闭；
- 设备矩阵：D01–D12 每行有“已验证通过/已验证不支持”的证据，不允许“静态阅读通过”；
- 分发：发布签名、哈希、安装/回退文档一致；
- 文档：所有“未验证”要么翻绿并附证据，要么继续明确标注，不能用模糊措辞掩盖。

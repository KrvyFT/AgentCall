# AgentCall 配置 schema、CAS 与固定 CLI

本文是 AgentCall 配置主副本与 WebUI/CLI 之间的接口约定。适用代码：

- `bridge/src/main/java/org/krvyft/agentcall/bridge/config/ConfigStore.kt`（主副本、CAS、密钥）
- `core/src/main/java/org/krvyft/agentcall/core/protocol/RpcCodec.kt`（编解码与字段表）
- `core/src/main/java/org/krvyft/agentcall/core/rules/ConfigValidator.kt`（业务校验）
- `bridge/src/main/java/org/krvyft/agentcall/bridge/AgentCallCli.kt`（固定 CLI）
- `module/bin/agentcallctl`、`module/webroot/bridge.js`（WebUI 传输与前端校验）

**证据边界**：本文描述的是代码与本地测试所实现的行为。真实 KernelSU 管理器 WebView
中的桥接/写入路径尚未在设备上验证（本开发环境无设备、无浏览器），
详见文末“未验证与已知限制”。

---

## 1. 存储位置与权限

| 路径 | 内容 | 权限 |
| --- | --- | --- |
| `/data/adb/agentcall` | 配置主副本目录（root 私有） | 0700 |
| `/data/adb/agentcall/config.json` | 公开配置 + 密钥 + schema + revision | 0600 |
| `/data/adb/agentcall/pairing.json` | 配对记录（包名/UID/签名摘要/版本） | 0600 |
| `/data/adb/agentcall/config.schema-<n>.json.bak` | 迁移前旧 schema 备份 | 0600 |
| `/data/adb/agentcall/supervisor-state.json` | 监督端持久状态（schema 3，见 §8） | 0600 |
| `/data/adb/agentcall/inbox` | WebUI 写入的 `config-set` 一次性负载 | 0700（WebUI 创建时显式 ownerOnly rwx，不依赖 umask；`customize.sh` 预创建） |
| `/data/adb/agentcall/inbox/config-set.<token>-<seq>.json` | 单次写入负载（每次保存唯一文件名）；正常路径读写后立即删除 | 写入后收紧为 0000 |
| `/data/adb/agentcall/logs/service.log` | 服务日志（有大小上限） | 0700 目录，服务自身轮转 |

密钥以**明文**保存在 root 私有单文件里，依靠目录/文件权限与 root 隔离，不做加密。
这符合 spec §6.4/§11：密钥不进入普通应用备份、导出或日志，但不能防御同设备其它
已授权 root 进程。

inbox 中的一次性明文负载只在一次保存期间存在：正常路径在 CLI 返回后立即删除；WebView
崩溃或进程被杀时可能残留，由 `service.sh`/`customize.sh` 在启动时清理本模块拥有的
`config-set*.json`（不触碰 inbox 中其它文件）。固定路径 `config-set.json` 是旧版兼容
形态，启动清理同样覆盖。

写入使用“同目录临时文件 → fsync → rename”的原子替换；revision 与内容一起落盘，
不会读到半份配置。

---

## 2. 磁盘格式（schema 1）

`config.json` 是单个 JSON 对象：

```json
{
  "schema": 1,
  "revision": 7,
  "enabled": false,
  "mode": "BLACKLIST",
  "blacklist": ["+8613800138000"],
  "whitelist": [],
  "region": "CN",
  "handleUnknownNumbers": false,
  "answerDelayMs": 3000,
  "maxCallDurationMs": 180000,
  "enabledPhoneAccounts": [],
  "asr": { "baseUrl": "https://api.example.com/v1", "model": "whisper-1" },
  "llm": null,
  "tts": null,
  "voice": "alloy",
  "speed": 1.0,
  "openingLine": "您好，我是机主的 AI 电话助理，请问有什么事？",
  "prompt": "",
  "secrets": { "ASR": "<明文密钥>", "TTS": "<明文密钥>" }
}
```

| 字段 | 类型 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `schema` | int | 1 | 当前只支持 1；更大值拒绝读取/覆盖，更小值迁移 |
| `revision` | int ≥ 0 | 0 | CAS 版本；每次成功写入 +1 |
| `enabled` | bool | false | 总开关默认关闭 |
| `mode` | string | `BLACKLIST` | `BLACKLIST` / `WHITELIST` / `COMBINED` |
| `blacklist` | string[] | `[]` | 完整号码；空黑名单 = 不接管 |
| `whitelist` | string[] | `[]` | 完整号码；白名单模式下为空 = 接管所有可识别号码 |
| `region` | string | `CN` | ISO 3166-1 alpha-2；由 `NumberNormalizer.isValidRegion` 校验 |
| `handleUnknownNumbers` | bool | false | 未知/隐藏号码默认人工处理 |
| `answerDelayMs` | int | 3000 | 0–30000 |
| `maxCallDurationMs` | int | 180000 | 10000–600000 |
| `enabledPhoneAccounts` | string[] | `[]` | 已校准的 SIM/PhoneAccount；WebUI 不编辑，补丁不得覆盖 |
| `asr` / `llm` / `tts` | object 或 null | null | `{baseUrl, model}`；null 表示未配置 |
| `voice` | string | `""` | 供应商音色标识 |
| `speed` | number | 1.0 | WebUI 限 0.5–2.0；桥接 schema 不额外拒绝其它有限数值 |
| `openingLine` | string | 上述中文开场白 | 可配置 |
| `prompt` | string | `""` | 只生成对话，不执行工具/设备命令 |
| `secrets` | object | `{}` | 键只能是 `ASR`/`LLM`/`TTS`，值为非空字符串；只存在于磁盘/内存 |

规则：

- 端点的“未配置”在 `AgentConfig` 中**只能是 null**；空端点对象（baseUrl 与 model 均为空串）
  只作为补丁意图出现，应用后规范化为 null，绝不持久化空端点。
- 黑名单/白名单必须能用 `region` 规范化为完整号码；跨名单规范化后同一号码视为冲突。
  空白名单在 `WHITELIST`/`COMBINED` 下合法但会在 WebUI 醒目提示；空黑名单在
  `BLACKLIST` 下合法（语义就是不接管）。

---

## 3. 读视图（脱敏）

`config-get`、`config-set` 成功响应以及 `status` 都不返回密钥原值。

`config-get` 的负载：

```json
{
  "config": { "enabled": false, "revision": 7, "...": "同磁盘公开字段，无 secrets" },
  "secrets": { "ASR": "CONFIGURED", "LLM": "MISSING", "TTS": "MISSING" }
}
```

- `config` 含 `revision`，不含 `secrets`。
- `secrets` 的值为 `CONFIGURED` 或 `MISSING`，没有原值、没有长度、没有前缀。
- `status` 负载（**18 个顶层键**：17 个标量/布尔 + 1 个 `calibration` 对象）：
  `bridgeVersion`、`protocolVersion`、`revision`、`enabled`、`paired`、`uptimeMs`、
  `processId`、`activeConnections`、`rejectedConnections`、`supervisionPhase`、
  `supervisionOwned`、`microphoneIsolated`、`supervisionBlocked`、`supervisionLastFault`、
  `shutdownCode`、`hookConnected`、`dialerDefaultRole`、`calibration`。
  `shutdownCode` 是最近一次优雅关闭的机器码；未关闭或干净关闭为 JSON `null`。
- `calibration`：与 `calibration.read` 完全相同的只读校准视图（`available`、
  `supportedApiLevel`、`environment`、`effectiveEnvironment`、`environmentComplete`、
  `verified`、`blockers`、`environmentMatchesEvidence`、`evidenceEnvironment`、
  `operatorAttestation`、`steps`、`calibratedAtMs`）；存储未接线时为
  `{"available":false}`，读取失败时附 `error` 机器码。APP/HOOK 只读，写入只经
  `calibrate`（§8）。
  APP 的自动接听就绪**从这份视图派生**：`available && supportedApiLevel == 37 && verified
  && environmentComplete && environmentMatchesEvidence`，再叠加 APP 本机
  `CalibrationPolicy`（本机五项证据 + 环境精确绑定）；读取失败或任何字段未知都返回 false 并
  报 `calibration_unavailable`，绝不把上一轮的 true 缓存下来。
- `processId`：桥接进程每次启动生成的随机字符串，是判断“桥接是否重启过”的唯一信标。
  缺失、非字符串、含空白/控制字符或超长时，调用方必须按“代次未知”处理，不得当成同一进程；
  `uptimeMs` 只作展示，不能替代它。
- `hookConnected`：boolean，或字符串 `"unknown"`。表示 HOOK 角色是否在 **5000 ms**
  心跳窗口内报告过心跳，数据源是监督端按会话记录的内存状态（`Supervisor.peerAlive`），
  不发远程请求、不读密钥。未接入监督状态时返回 `"unknown"`。
- `dialerDefaultRole`：boolean，或字符串 `"unknown"`。只有系统 `ROLE_DIALER` 持有者
  是 Google 电话时才返回 `true`；角色查询不可读（无 `RoleManager`/隐藏 API 策略/权限）
  时，只有 `ACTION_DIAL + CATEGORY_DEFAULT` 的 `MATCH_DEFAULT_ONLY` 默认处理器明确是
  **其它**应用才返回 `false`；解析到 Google 电话本身、系统 Resolver、无候选或任何异常
  都返回 `"unknown"`。这是特权进程内的本地查询，不调用 Telecom、不修改角色。
- 阶段 B 之前这两个字段不存在于 status；现在 WebUI 只按真实布尔渲染，`"unknown"` 显示
  “未知”，缺失时同样显示未知，前端不伪造结论。
- `diagnose` 负载：`environment`（apiLevel/romFingerprint/kernelVersion/
  dialerPackageVersion/frameworkVersion/bridgeVersion/audioPolicyDigest/simCarrier/
  transport/route）、`downlinkExtraction`、`uplinkInjection`、`microphoneIsolation`、
  `privilegedTermination`、`checkedAtMs`、`notes`（已脱敏），以及**与 status 相同的
  `calibration` 对象**（同一份只读校准视图）。

---

## 4. 写入负载（只从 stdin）

`config-set` 的 stdin 是一个 JSON 对象，**只有两个顶层键**：

```json
{
  "expectedRevision": 7,
  "patch": {
    "enabled": true,
    "secrets": {
      "ASR": { "action": "keep" },
      "LLM": { "action": "replace", "value": "<新密钥>" },
      "TTS": { "action": "clear" }
    }
  }
}
```

约束：

- 顶层只允许 `expectedRevision` 与 `patch`；其它键整体拒绝（`payload_invalid`）。
- `expectedRevision` 必须是非负整数。
- `patch` 只允许以下键，未知键整体拒绝：
  `enabled`、`mode`、`blacklist`、`whitelist`、`region`、`handleUnknownNumbers`、
  `answerDelayMs`、`maxCallDurationMs`、`enabledPhoneAccounts`、`asr`、`llm`、`tts`、
  `voice`、`speed`、`openingLine`、`prompt`、`secrets`。
- 缺省字段表示“保持原值”；补丁是整体校验，不会“尽力应用一部分”。
- 端点只接受 `{"baseUrl": string, "model": string}`。**清除端点**用两者都为空串的对象；
  JSON `null` 会被拒绝（前端 bridge.js 与后端一致拒绝）。
- `enabledPhoneAccounts` 属于 APK 侧；WebUI 的补丁不包含它，避免清空 APK 写入的启用项。
- stdin 上限 `MAX_CONTROL_FRAME_BYTES - 1024 = 64512` 字节（UTF-8）。
  WebUI 在写文件前按同一上限检查；超限返回 `payload_invalid`。
- 配置内容只经 stdin 传输；命令行只有本次保存的**唯一**负载路径
  `<moduleDir>/bin/agentcallctl config-set < /data/adb/agentcall/inbox/config-set.<token>-<seq>.json`。
  路径由前端常量、每实例随机 token 与递增序号生成，不含用户输入；CLI 契约仍然是
  “从 stdin 读 config-set”。每次保存使用唯一文件名，两个 WebUI 实例不会互相覆盖负载
  （固定路径曾让 A 的 CLI 读到 B 的 payload，CAS 仍可能通过）。
  密钥永远不进入进程 argv、URL、日志或错误消息。
- **页面作用域**：WebUI 的每个页面保存只提交本页字段，校验也只针对本页；绝不夹带其它页面
  正在编辑（未确认）的内容，尤其是 AI 密钥意图：

  | 入口 | 提交的 patch 键 |
  | --- | --- |
  | 总开关（概览） | 只有 `enabled` |
  | 规则页 | `mode`、`blacklist`、`whitelist`、`region`、`handleUnknownNumbers`、`answerDelayMs`、`maxCallDurationMs` |
  | AI 页 | `asr`、`llm`、`tts`、`voice`、`secrets` |
  | 对话页 | `openingLine`、`prompt`、`speed` |

  页面补丁不包含 `enabled`（开关独立保存），也不包含 `enabledPhoneAccounts`。缺省字段对后端
  仍然表示“保持原值”，所以按页提交不会清空其它配置。

---

## 5. revision CAS 语义

1. 客户端先读取 `revision`（`config-get` 或上一次写入响应）。
2. 写入时把它作为 `expectedRevision` 提交。
3. 服务端比较磁盘上的 `revision`：
   - 相等：应用补丁、业务校验、写密钥，成功落盘时 `revision = revision + 1`；
     响应返回**新的**脱敏配置（含新 revision）。
   - 不相等：返回 `config_conflict`，**不写任何字节**，revision 不变。
4. 客户端必须丢弃本次编辑或重新加载，**不得**自动重试、不得自行抬高
   `expectedRevision` 覆盖他人更新。
5. WebUI 只有在后端回读确认 `config.revision === expectedRevision + 1`，并且
   每个 `replace` 变成 `CONFIGURED`、每个 `clear` 变成 `MISSING` 时，才显示“已保存”；
   否则显示 `save_unconfirmed`。
6. 校验失败（`config_invalid`）、空密钥（`secret_invalid`）、schema 不支持
   （`config_schema_unsupported`）同样不落盘，revision 不变。
7. 旧 schema 迁移是一次读时重写，不构成用户写入，**不增加 revision**。
8. 同一进程内的写入用锁串行化；特权桥接进程是唯一写入者。

---

## 6. 密钥 Keep / Replace / Clear

| 意图 | patch.secrets[kind] | 效果 | 允许的额外字段 |
| --- | --- | --- | --- |
| 保留 | `{"action":"keep"}` | 原值不动；空表单字段不会清除密钥 | 无 |
| 替换 | `{"action":"replace","value":"..."}` | 写入新值，状态变 `CONFIGURED` | 仅 `value` |
| 清除 | `{"action":"clear"}` | 删除密钥，状态变 `MISSING` | 无 |

- `kind` 只能是 `ASR`/`LLM`/`TTS`；未知类别整体拒绝。
- `keep`/`clear` 必须恰好只有一个键；`replace` 必须恰好 `action`+`value` 两个键。
- `replace` 的 `value` 为空白（含全空白）时返回 `secret_invalid`，不写任何内容；
  WebUI 在本地也阻止空替换。
- 响应与日志只出现 `CONFIGURED`/`MISSING`，不回显、不记录、不哈希原值。
- WebUI 表单默认 `keep`；只有用户显式点“替换”（并输入）或“清除”才发送对应动作。
  保存成功后表单按后端回读重建，密钥输入框清空、意图回到 `keep`。

---

## 7. 错误码

### 7.1 传输/前端（bridge.js 产生，未到后端）

| code | 含义 | WebUI 行为 |
| --- | --- | --- |
| `bridge_unavailable` | 没有 KernelSU 管理器注入（普通浏览器） | 演示/只读态，保存一律失败 |
| `secret_transport_unsupported` | 管理器没有 `ksu.io` 受控写入接口 | 只读态，拒绝 `config-set`，不执行命令 |
| `module_dir_unresolved` | `moduleInfo()` **明确给出**的 `moduleDir` 不符合固定模式（不可信） | 禁用所有需要执行 CLI 的命令与保存（fail closed）；固定路径日志仍可读 |
| `io_missing`（只读原因，不是命令错误码） | 管理器没有 `ksu.io()` 或 `version()<1` | 只读态：读取/诊断/配对/关闭可用，保存一律拒绝（`secret_transport_unsupported`） |
| `io_unavailable` | 文件接口不可用（读日志等） | 显示“无法读取”，不伪造数据 |
| `unsupported_operation` | 不在固定命令表内 | 拒绝，不拼 shell |
| `payload_not_allowed` | 无参数命令收到负载 | 拒绝，不执行 |
| `payload_invalid` | 负载不符合固定字段表/大小上限 | 拒绝，不执行 |
| `transport_failed` | 命令超时、socket 不可达或 CLI 退出码非 0 | 报错；不宣称保存 |
| `response_invalid` | CLI 输出不是合法 JSON | 报错；不宣称保存 |
| `save_unconfirmed` | 写入响应未确认新 revision/密钥状态 | 不显示成功 |
| `inbox_write_failed` | 无法写受控临时文件 | 拒绝保存 |
| `validation_failed` | 前端字段校验未通过 | 标红字段并列出问题 |

### 7.2 桥接/存储（后端返回）

| code | 含义 |
| --- | --- |
| `config_conflict` | CAS 冲突；未写入；客户端应重新加载 |
| `config_invalid` | 补丁应用后未通过业务校验；消息形如 `config rejected: RULE_NUMBER_OVERLAP@blacklist,whitelist,REGION_UNSUPPORTED@region` |
| `secret_invalid` | 替换密钥为空/全空白 |
| `config_schema_unsupported` | 磁盘 schema 比当前实现新，拒绝读取/覆盖 |
| `config_corrupt` | 配置或配对文件损坏 |
| `config_storage_failed` | 文件读写/权限失败 |
| `pairing_invalid` | 配对记录字段非法 |
| `config_root_rejected` | 配置根不在 `/data/adb/agentcall` 之下 |
| `forbidden` | 角色无权调用该方法 |
| `method_not_implemented` | 固定表中尚未实现的方法 |
| `calibration_unavailable` | 校准存储未接线或不可用；不得假装已验证 |
| `calibration_environment_unknown` | 当前/预期环境事实不完整，拒绝把校准绑定到不可复现环境 |
| `calibration_environment_mismatch` | 预期环境与当前设备环境不一致 |
| `internal_error` | 未预期异常（只回类名） |

业务校验 issue code（`config_invalid` 消息内）：`ANSWER_DELAY_OUT_OF_RANGE`、
`MAX_CALL_DURATION_OUT_OF_RANGE`、`REGION_UNSUPPORTED`、`RULE_NUMBER_UNPARSEABLE`、
`RULE_NUMBER_OVERLAP`。WebUI 把它们映射回对应字段；`RULE_NUMBER_OVERLAP` 同时标黑/白名单。

### 7.3 配对/身份（连接期）

`peer_unpaired`、`peer_version_mismatch`、`peer_credentials_invalid`、
`peer_unknown_uid`、`peer_untrusted`、`peer_uid_mismatch`。

- `peer_version_mismatch`/`peer_unpaired`：APK 或 Google 电话版本与配对记录不一致，
  必须重新配对。WebUI 对保存/读取失败显示“配对已失效 → 重新配对”横幅，
  不把失败伪装成成功。
- 重新配对只能由 root CLI（WebUI 的 `pair` 按钮经 `agentcallctl pair`）发起；
  签名摘要由桥接进程重新解析，消息不能自带签名或角色。

### 7.4 CLI 包装器

`usage_invalid`、`payload_invalid`、`payload_too_large`、`pair_requires_root`、
`transport_failed`、`response_id_mismatch`、`cli_failed`、`cli_requires_root`
（非 root 调用包装器）、`bridge_container_missing`、`app_process_missing`；
模块侧 `restart` 另有 `service_not_running`、`module_disabled`、`restart_unconfirmed`、
`restart_refused`（关闭裁决不干净/仍隔离/缺失）。

---

## 8. 固定 CLI 映射

包装器：`/data/adb/modules/agentcall/bin/agentcallctl`（0755，root）。
它只把参数转发给 `org.krvyft.agentcall.bridge.AgentCallCli`，自身不读、不拼、不回显
配置内容；`config-set` 的 stdin 由调用方重定向继承。

| 命令 | 参数 | stdin | 输出（成功） | CLI 退出码 | 角色 |
| --- | --- | --- | --- | --- | --- |
| `status` | 无 | 不用 | status JSON（见 §3） | 0 | CLI_ROOT |
| `config-get` | 无 | 不用 | `{config, secrets}` 脱敏视图 | 0 | CLI_ROOT |
| `config-set` | 无 | 必须：`{expectedRevision, patch}` JSON，≤64512 字节 | 写入后的 `{config, secrets}`（revision+1） | 0 | CLI_ROOT |
| `calibrate` | 无（argv 负载一律拒绝） | 必须：`{steps:{...五项布尔}, expectedEnvironment?, operatorAttestation?}` JSON | 校准记录结果 `{recorded, environment, operatorAttestation, steps, verified, blockers, calibratedAtMs}` | 0 | CLI_ROOT |
| `pair` | 可选一个包名（默认 `org.krvyft.agentcall`） | 不用 | 配对成功负载 | 0 | 仅 root |
| `diagnose` | 无 | 不用 | 能力/环境报告 | 0 | CLI_ROOT |
| `shutdown` | 无 | 不用 | `{"stopping":true,"clean":<bool>,"shutdownCode":<string|null>,"supervision":{"clean":<bool>,"ownedSession":<bool>,"termination":<string|null>,"ownershipResolved":<bool>,"disconnected":<bool>,"microphoneIsolated":<bool>,"code":<string|null>}}` | 0 | CLI_ROOT |

`restart` 是模块侧控制动作，不是 AgentCallCli 命令（见 §10）。

退出码：`0` 成功、`1` 服务端错误、`2` 用法/负载错误、`3` 传输失败。
错误输出始终是单行机器可读 JSON：`{"error":{"code":"...","message":"..."}}`。

**`calibrate` 包装器契约（任务 11D2b / 11E / 11H1）**：`module/bin/agentcallctl calibrate` 只
接受零参数；stdin 逐字节原样交给 `AgentCallCli calibrate`（包装器不读、不拼、不回显），任何
argv 负载都在启动 CLI 之前以 `usage_invalid`（退出码 2）拒绝。外层只做“JSON 对象 + 只允许
`steps`、可选 `expectedEnvironment`、可选 `operatorAttestation`，三者都是对象”的形状校验；
五项步骤是否齐全、环境是否未知/不一致、背书词表/角色、以及 `verified` 裁决仍由桥接的
`calibration.record` handler 独占（唯一权威）。环境与时间戳一律由桥接进程重新采集/写入；
root 背书只能填补桥接观测不到的 `transport`/`route`/`frameworkVersion`/`simCarrier`，
观测到的已知值永远优先。框架/SIM 先由桥接真实观测：`frameworkVersion` 依次读 root
SystemProperties（`ro.lsposed.version`/`persist.sys.lsposed.version`/`ro.vector.version`/
`persist.sys.vector.version`/`ro.edxposed.version`/`persist.sys.edxposed.version`）、
`/data/adb/modules/{lsposed,zygisk_lsposed,vector}/module.prop` 的 `version=`、XposedBridge
类检查；`simCarrier` 读 TelephonyManager SIM 运营商名并回退
`gsm.sim.operator.alpha`/`gsm.sim.operator.default-name`/`ro.cdma.home.operator.alpha`。未知或
不一致环境整体拒绝。校准存储未接线时返回 `calibration_unavailable`，绝不假装已验证。

WebUI 的实际命令行（完整字符串，无用户数据）：

```
/data/adb/modules/agentcall/bin/agentcallctl status
/data/adb/modules/agentcall/bin/agentcallctl config-get
/data/adb/modules/agentcall/bin/agentcallctl config-set < /data/adb/agentcall/inbox/config-set.<token>-<seq>.json
/data/adb/modules/agentcall/bin/agentcallctl pair
/data/adb/modules/agentcall/bin/agentcallctl diagnose
/data/adb/modules/agentcall/bin/agentcallctl shutdown
/data/adb/modules/agentcall/bin/agentcallctl restart
```

WebUI 使用上述七条固定操作；`calibrate` 只作为 root 操作员命令提供（见下方校准通道），
WebUI 不调用它。

- 没有任意 RPC（控制 socket 只接受固定方法表）、没有任意 shell、没有把用户输入拼进命令。
- 模块目录必须匹配 `^/data/adb/modules/[A-Za-z][A-Za-z0-9._-]+$`；无法确认时拒绝执行。
- 旧管理器没有 `moduleInfo()` 时回退到固定路径 `/data/adb/modules/agentcall`，
  不拼接任何外部输入。

**校准通道（11A/11D2b/11E）**：`calibration.read`（APP 只读，CLI_ROOT 也可读）与
`calibration.record`（仅 CLI_ROOT）是控制 socket 上的固定 RPC 方法。操作员入口是
`agentcallctl calibrate`（零参数，JSON 只从 stdin 进入）；WebUI 不提供该动作，也不伪造
校准证据。`calibration.record` 接受五项布尔人工测试步骤、可选的“预期环境”与可选的
`operatorAttestation`；环境与时间戳一律由桥接进程重新采集/写入（背书只补桥接观测不到的
`transport`/`route`/`frameworkVersion`/`simCarrier`），未知或不一致环境整体拒绝。校准存储
未接线时返回 `calibration_unavailable`，绝不假装已验证。

**`operatorAttestation`（11E / 11H1，root 操作员背书）**：对象允许五个键；
`transport`/`route`/`manualTestCompleted` 必填，`frameworkVersion`/`simCarrier` 可选。
多键/少键/错误类型都整体拒绝（`payload_invalid`）：

| 键 | 类型/词表 | 语义 |
| --- | --- | --- |
| `transport` | string；`CELLULAR` / `VOWIFI` / `UNKNOWN` | 通话承载；只有 bridge 观测为 UNKNOWN 时才采用 |
| `route` | string；`EARPIECE` / `SPEAKER` / `HEADSET` / `BLUETOOTH` / `UNKNOWN` | 音频路由；只有 bridge 观测为 UNKNOWN 时才采用 |
| `manualTestCompleted` | 必须是 JSON `true` | 操作员确认五项人工测试真实完成 |
| `frameworkVersion` | string；1..128 字符，前后无空白（已 trim），无控制字符，且 ≠ `UNKNOWN` | Xposed 框架版本；只有 bridge 观测为 UNKNOWN 时才采用 |
| `simCarrier` | 同上（有界自由文本） | SIM 运营商名；只有 bridge 观测为 UNKNOWN 时才采用 |

- APP/HOOK 不能调用 `calibration.record`（RolePolicy 已拒绝，handler 再按角色二次拒绝），
  因此不能提供背书。
- bridge 已观测到的已知 `transport`/`route`/`frameworkVersion`/`simCarrier` 永远优先，背书
  不能覆盖；`UNKNOWN` 不能解锁完整环境。
- 写入响应与 `calibration.read`/status/diagnose 的 `operatorAttestation` 为
  `{operatorAttested, transport, route, frameworkVersion, simCarrier}`；**每个**确实由 root
  背书、且对应观测值为 UNKNOWN 的字段都会标记（`operatorAttested=true` + 该字段值），
  不把操作员背书显示成桥接实测。
- 校准视图同时暴露 `effectiveEnvironment`（观测 + 背书后的有效环境），避免把“服务端观测”
  与“操作员背书”混为一谈。

**`shutdown` 结果语义（11D2a/11D2b/11E/11H1）**：响应固定为
`{"stopping":true,"clean":<bool>,"shutdownCode":<string|null>,"supervision":
{"clean":...,"ownedSession":...,"termination":...,"ownershipResolved":...,
"disconnected":...,"microphoneIsolated":...,"code":...}}`。三个顶层字段是
`clean` / `shutdownCode` / `supervision.microphoneIsolated`；`clean=true` 等价于
`shutdownCode=null`。拥有中的通话只有在终止/交还并确认断开、且恢复读回为未隔离时才得到
`null`。时间预算：`SHUTDOWN_TERMINATION_DEADLINE_MS` = 5 s，但该期限**只覆盖终止命令
下发之后等待 `awaitConfirmedDisconnect` 的时间**；终止命令先执行、其角色通道等待另有预算，
所以 5 s 不是“终止 + 确认”的总时长。恢复只有单一 `SHUTDOWN_RESTORE_BUDGET_MS` = 6 s；
CLI 只对 `shutdown` 把 socket 超时放宽到 20 s。

11E 起（最终 HEAD 保持）**没有拥有中的通话时也会处理观测到的隔离**：恢复成功才是 `clean=true`；仍隔离
返回 `clean=false` + `shutdown_microphone_still_isolated`（不再出现“`clean=true` 但
`microphoneIsolated=true`”的矛盾形状）。调用方仍应同时检查
`mic = supervision.microphoneIsolated`，不能只看 `clean`（旧版 bridge 可能仍有该形状）。
`shutdown_call_unresolved` = 期限内无法确认断开，保持隔离并保留粘性所有权标记；
`shutdown_microphone_restore_failed` = 已确认断开但恢复读回失败并进入 `BLOCKED`；
`shutdown_microphone_still_isolated` = 没有可恢复/未取得未隔离读回，隔离保持；
`shutdown_failed` = 监督端关闭自身抛异常。`status` 同时暴露 `shutdownCode`、
`supervisionPhase`（IDLE/…/ENDED/BLOCKED）与 `microphoneIsolated`。模块脚本据此读回
裁决：只有 `clean=true` + `shutdownCode=null` + `microphoneIsolated=false` + phase
`IDLE`/`ENDED`（或进程/socket 已退出）才宣称“已确认干净关闭”；否则打印机器可读警告。
`service.sh` 在关闭响应之外，只要桥接在退出等待后仍存活就再做一次最终 `status` 探测；
隔离仍为 true 时打印 `! microphone_still_isolated ...` 与 `bridge_stop_unclean`。
11H1 起，桥接退出（哪怕退出码 0）后还会读 `DATA_ROOT/supervisor-state.json`（schema ≥ 3）
作为持久裁决：`shutdownCode` 非 null 或 `microphoneIsolated=true` 时打印
`! microphone_still_isolated` / `! shutdown_unresolved` 与 `bridge_stop_unclean`，
**绝不打印 `bridge_exit_clean`**，以非零退出并拒绝重启。`agentcallctl restart` 同样只在
`clean=true` + `shutdownCode=null` + `microphoneIsolated=false` 时继续，否则
`restart_refused`。

**监督端持久状态（schema 3）**：`/data/adb/agentcall/supervisor-state.json` 以 `schema: 3`
原子落盘（同目录临时文件 + fsync + rename），字段含 `phase`、`ownedSession`、
`microphoneIsolated`、`blockedReason`、`lastFault`、`updatedAtMs`、`bootId`、
`ownedCallMayBeLive` 与 `shutdownCode`。schema 2 增加粘性所有权标记
`ownedCallMayBeLive`；**schema 3 增加 `shutdownCode`**，使最近一次关闭机器码跨重启仍可读。
损坏或更高 schema 一律 fail closed（`supervision_state_corrupt` /
`supervision_state_schema_unsupported`），绝不把旧状态当成干净启动。

**停止顺序（模块侧硬契约）**：`service.sh` 的停止/退出路径与 `uninstall.sh` 都先取
`/data/adb/agentcall/stop.lock`（`flock`，最多等待 40 s；无 flock 时退回 `mkdir` 原子目录锁），
再发固定 CLI `shutdown` 并等待桥接退出，再停音频工作进程，最后有界升级 TERM→KILL。
锁使两个脚本的优雅关闭序列串行化：没有它，第二个 CLI shutdown 可能在第一个的恢复窗口内
杀音频工作进程（11F / I4）。等待预算与 11E 的 20 s CLI socket 超时对齐：`service.sh` 等
CLI 结果最多 25 s、等桥接退出最多 10 s；`uninstall.sh` 相同，最终 status 探测单独最多 5 s。
顺序颠倒会让桥接的麦克风恢复请求不可达（音频工作进程已死），麦克风保持全局静音。

---

## 9. WebUI 传输与受限能力

`module/webroot/bridge.js` 只使用管理器注入的接口（与 KernelSU 上游
`js/index.js` 使用的原生桥一致）：

- 执行：`ksu.exec(command, "{}", callbackName)`，回调 `(errno, stdout, stderr)`；
  也兼容返回 Promise 的实现。
- 文件：`ksu.io()` 的 `version()`、`File()`、`FileOutputStream()`、
  `RandomAccessFile()`/`FileInputStream()`（日志尾部）。
- 模块目录：`ksu.moduleInfo()`。

三种运行状态：

| 状态 | 条件 | 能力 |
| --- | --- | --- |
| `kernelsu` | 有 `ksu.exec`、`ksu.io()` ≥ v1、模块目录可信（`moduleInfo` 缺少 `moduleDir` 时回退固定路径） | 全部固定操作（WebUI 使用其中七条：status/config-get/config-set/pair/diagnose/shutdown/restart；不含 root-only `calibrate`）；保存可用 |
| `readonly`（`io_missing`） | 有 `ksu.exec`，但没有 `ksu.io()`（或 `version()<1`） | 读取/诊断/配对/关闭可用；**保存一律拒绝**（`secret_transport_unsupported`），绝不降级为 base64/echo 命令行参数 |
| `readonly`（`module_dir_unresolved`） | `moduleInfo()` **明确给出**不可信的 `moduleDir` | 需要执行 CLI 的命令禁用；固定路径日志读取仍可用（日志不依赖模块目录）；保存拒绝 |
| `demo` | 普通浏览器，没有管理器注入 | 明确标注“演示数据”，保存、配对、日志均不可用；不发起任何网络请求 |

模块目录解析规则（版本偏差不再一刀切）：

- 没有 `moduleInfo()`、调用抛错、返回非法 JSON/非对象，或对象里**没有** `moduleDir`
  （旧版/分支管理器常见）→ 回退到固定常量 `/data/adb/modules/agentcall`，所有命令照常可用。
- `moduleDir` 存在但不符合 `^/data/adb/modules/[A-Za-z][A-Za-z0-9._-]+$` → 视为不可信，
  返回 `module_dir_unresolved`，只禁用需要模块目录的命令。
- 日志路径 `/data/adb/agentcall/logs/service.log` 是固定的，不因模块目录未解析而失效。

写入路径：

1. 前端把 `{expectedRevision, patch}` 序列化为 UTF-8 JSON。
2. 通过 `ksu.io().FileOutputStream()` 写入本次保存的唯一负载
   `/data/adb/agentcall/inbox/config-set.<token>-<seq>.json`。
   这里的 base64 只是 Java 文件桥的字节编码，不经过 shell、不进 argv。
3. 文件权限收紧为 0000；目录 0700。
4. 执行 `agentcallctl config-set < <本次唯一负载路径>`；CLI 契约未变，仍只从 stdin 读取。
5. 无论成功失败都删除本次临时文件；成功与否只以后端回读为准。WebView 崩溃残留由
   `service.sh`/`customize.sh` 启动清理兜底。
6. 唯一负载名取代固定文件名后，两个 WebUI 实例的写入不再交错；不需要额外的跨实例锁文件，
   也不存在“持锁等待”的失败模式。

WebUI 不加载任何在线 CDN、不发起 fetch/XHR、不用 `innerHTML` 渲染不受信任文本；
配置与日志一律 `textContent`。

**分页未保存标记**：规则页、AI 页、对话页各有独立的“未保存”chip。页内保存只提交该页
字段，成功时只清除该页标记（其它页面的未保存编辑与 chip 保留）；重新加载配置才清除全部
页面的标记。总开关是独立保存动作，不标记也不清除任何页面 chip。

**连接测试归属（控制器裁决）**：特权桥接进程**不得**代替用户发起远程 API 请求
（spec §6.3），WebUI 也不读取密钥。逐端点连接测试由 APK 侧诊断（Task 7）**实现**：

- AI 页只做本地“地址格式校验”（按钮文案为“校验地址格式（非连接测试）”）；
- AI 页的“连接测试”卡片明确写明“连接测试在 AgentCall APK 诊断页执行，需真实远程 API
  请求（会使用密钥并可能计费）；WebUI 不发起远程请求，也不伪造测试结果”，并标注状态为
  “APK 诊断页提供”；
- 当前没有 APK 的 deep link 约定，因此只提供路径说明（AgentCall APK → 诊断 → 连接测试），
  不提供会失败的链接。

**WebUI 自身不得显示假的“已连接/已就绪”**：任何一次真实请求都必须发生在 APK 进程。

---

## 10. 迁移与兼容

- `schema` 当前为 1。读取到更小的 schema：先写
  `config.schema-<n>.json.bak`，再按当前格式原地重写；revision 不变。
  当前实现把 schema 0 视为“无 secrets 段的旧格式”，迁移后 secrets 为空。
- 读取到更大的 schema：`config_schema_unsupported`，拒绝读取与覆盖，
  旧版模块不会降级覆盖新版配置。
- 模块升级：`customize.sh` 只补可执行位、校验桥接容器、预创建 0700 目录，
  并清理 inbox 中崩溃遗留的一次性负载；不迁移、不改写既有配置；配置迁移由桥接在读取时完成。
- 卸载：`uninstall.sh` 分开探测 abstract socket 是否存在与固定 CLI 是否可执行
  （socket 存在不等于 CLI 可执行），并固定以下顺序（任务 11D2b）：
  1. 收回待处理的 `restart.requested`；
  2. 取 `/data/adb/agentcall/stop.lock`（`flock`，最多等 40 s）与 `service.sh` 串行化；
     拿不到锁时打印 `stop_lock_timeout` 并跳过重复的关闭请求，只做有界 PID 回退；
  3. 两者都可用 → 固定 CLI `shutdown`，分别有界等待 CLI 返回（`STOP_WAIT_SECONDS`，
     默认 25 s，覆盖 11E 的 20 s socket 超时）与桥接进程/socket 消失（`BRIDGE_EXIT_WAIT_SECONDS`，
     默认 10 s）；桥接在响应前完成终止/交还 + 麦克风读回；
  4. 读回关闭裁决：shutdown 响应的 `clean`/`shutdownCode`/`supervision.microphoneIsolated`，
     桥接仍可响应时再读 `status` 的 `supervisionPhase`/`microphoneIsolated`（最多 5 s）。
     只有 `clean=true`、`shutdownCode=null`、`microphoneIsolated=false` 且 phase 为
     `IDLE`/`ENDED`（或进程/socket 已退出）才输出“已确认干净关闭”；否则输出机器可读
     警告 `! shutdown_result clean=... shutdownCode=... phase=... microphoneIsolated=... owned=... cli=...`
     并明确“未确认通话已终止或交还；麦克风可能仍被隔离”，绝不宣称已交还；
  5. 音频工作进程（`/data/adb/agentcall/audio-worker.pid`，由 `service.sh` 写入单个
     数字 PID）在优雅关闭请求**之后**才处理：核验 `AudioWorkerMain`/`agentcall-audio-worker`
     后 TERM → 有界等待 → KILL。顺序不能颠倒：先杀它会让恢复请求不可达；
  6. 残留桥接（`/data/adb/agentcall/bridge.pid`）同样核验 `BridgeMain`/`agentcall-bridge`
     后 TERM → 有界等待 → KILL；任一 PID 文件缺失/不可核验时**不猜测 PID**，显式警告；
  7. 释放停止锁，最后才删除 `/data/adb/agentcall`（连同“所有权可能仍在”的粘性标记）。
     两个 PID 文件的写/读格式由 `module-scripts.test.mjs` 绑定到 `service.sh`
     （首行 + 仅十进制数字）。

  脚本能保证：有界地停止进程、读回并打印关闭裁决。脚本不能保证：桥接不响应时的
  通话终止/交还、麦克风隔离解除，或重启后的系统静音状态（重启会重新初始化系统音频，
  且只有音频工作进程已停止后才不会被它再次设置；但重启不证明通话已结束，下次启动
  仍会因粘性所有权标记保持 `BLOCKED`，直到人工确认。删除数据目录会移除该标记，
  重装后不再记得这次未解决的通话）。

  **重启语义**：`service.sh` 的重启循环默认在 `RESTART_DELAY_SECONDS=5` 后重新拉起
  桥接/音频工作进程。按 KernelSU 的标记语义，移除模块会先写 `remove`/`disable` 标记
  （设备上的实际卸载时序未验证）；`service.sh` 在**每次重启前**重新检查该标记，
  标记存在时不再拉起（不会复活已移除模块）。
  在没有标记的情况下手动对进程执行 TERM，约 5 s 后可能被重新拉起——脚本会打印这一点。

  **操作者重启（`agentcallctl restart` / WebUI“重试服务”）**：桥接进入 `BLOCKED`
  后的人工恢复路径。契约固定如下：
  1. `agentcallctl restart` 只接受无参数调用；先核验 `disable`/`remove` 标记（存在即
     `module_disabled`，不写标记、不执行 CLI）。
  2. 读取 `/data/adb/agentcall/service.pid`（`service.sh` 写自己的 PID，单行十进制），
     按 `/proc/<pid>/cmdline` 核验它确实是 `service.sh`；缺失/不可核验返回
     `service_not_running`（本次开机没有重启循环，只能重启设备）。
  3. 写 `/data/adb/agentcall/restart.requested`（内容为 `service.sh` 的 PID，0600，
     先写临时文件再 rename），随后只执行固定 CLI `shutdown`（该 `shutdown` 会先优雅
     终止/交还通话并读回恢复麦克风）。
  4. 关闭裁决必须可核验：只有 `clean=true` + `shutdownCode=null` +
     `microphoneIsolated=false` 才继续重启；`clean=false`、麦克风仍隔离（包括没有拥有
     会话但隔离仍为 true 的旧形状）或裁决缺失都收回标记并返回 `restart_refused`，
     消息带 `clean=... shutdownCode=... microphoneIsolated=...` 或 `verdict_missing`。
     CLI 未接受关闭（退出码非 0）仍返回 `restart_unconfirmed`。
  5. `service.sh` 在桥接**干净退出（退出码 0）**后消费标记：内容必须等于本实例 PID，
     否则视为跨实例/跨开机的过期标记，记录 `bridge_restart_stale` 后丢弃；
     `remove`/`disable` 标记存在时记录 `bridge_restart_skipped reason=marked_stopped`
     并退出（不复活）。每次请求只允许一次重启，另有 `MAX_REQUESTED_RESTARTS` 上限；
     主动重启不消耗故障重启预算。
  6. 成功输出只有 `{"restart":"requested"}`：表示请求已被接受，不宣称阻塞条件已修复；
     WebUI 在真实桥接时启用该动作，演示态/模块目录不可信时保持禁用；`restart_refused`
     时 WebUI 明确显示“关闭未确认干净/麦克风可能仍被隔离，本次没有重启”。
  7. 卸载时 `uninstall.sh` 先收回待处理的 `restart.requested`，避免 `shutdown` 后
     又被重启循环拉起。

  **诚实边界**：桥接已实现会话/租约（`session.request`、`session.command`、
  `session.heartbeat`、`session.events`、`session.handover` 与租约管理）与优雅关闭
  （11D2a）：`shutdown` 会先尝试终止/交还拥有的通话，取得“已断开/已不归 AI”的事实后
  才读回恢复麦克风；期限内无法确认时保持隔离并以 `shutdown_call_unresolved`
  （`clean=false`）失败关闭，恢复读回失败时为 `shutdown_microphone_restore_failed`，
  没有拥有会话但隔离仍保持时为 `shutdown_microphone_still_isolated`（`clean=false`）。
  模块侧（11D2b/11F）只在音频工作进程仍存活时请求该关闭，并用 stop.lock 串行化
  service.sh/uninstall.sh 的停止序列；读回仍隔离时显式警告，不宣称干净停止。**脚本不能替代手动结束通话**：桥接已退出/不响应时优雅路径不可达，脚本也无法
  自行解除麦克风隔离。不修改 Google 电话默认角色、vendor 音频配置或 SELinux。

  **未验证**：上述 PID 核验/终止分支只有 `sh -n` 与宿主脚本断言/进程替身证据，
  未在真机 KernelSU 卸载流程中执行。

---

## 11. 未验证与已知限制

- **真实 KernelSU 管理器未验证**：本环境没有设备/浏览器。当前实现依赖管理器提供
  `ksu.exec` 与 `ksu.io()`（含 `FileOutputStream`）；若实际安装的管理器版本更旧或
  接口不同，WebUI 会进入 `readonly` 并拒绝保存——这是有意的 fail-closed 行为，
  不是“已保存”。文档不声称该路径已在真机 WebView 中工作。
- **stdin/密钥传输能力**：`agentcallctl` 从继承的 stdin 读取负载，因此需要调用方
  能把 JSON 放进 root 私有文件并做重定向。管理器的文件写入接口不可用时，没有
  不暴露密钥的替代通道；实现选择拒绝保存，而不是用 base64 命令行参数伪装保密。
- **KernelSU IO 版本**：`ksu.io()` 的 `version()` 必须 ≥ 1；不同管理器/分支的
  文件接口细节未逐一验证。版本为 0 时按“没有受控写入接口”处理（只读，不是保存成功）。
- **模块目录版本偏差**：`moduleInfo()` 缺少 `moduleDir` 时回退固定路径；只有明确给出
  非法目录才 fail closed。该项由 `transport.test.mjs` 覆盖，未在真机管理器上验证。
- **优雅关闭与卸载顺序未上机**：`service.sh`/`uninstall.sh` 的“先固定 CLI shutdown、
  再停音频工作进程、最后 TERM→KILL”只有宿主进程替身证据（`shutdown-order.test.mjs`、
  `module-scripts.test.mjs`）。真机 KernelSU 卸载时序、`app_process` 在停止/重启窗口内
  能否及时响应 `shutdown`、持久裁决（`supervisor-state.json` schema 3）在真机的落盘/读回、
  以及卸载时读到的 `supervisionPhase` 均**未验证**。
- **`calibrate` 包装器/背书未上机**：stdin 逐字节透传、argv 拒绝、`operatorAttestation`
  词表/有界自由文本与角色拒绝只有宿主/JVM 证据；真机 `app_process` 的 stdin 继承与校准
  环境探针**未验证**。`transport`/`route`/`frameworkVersion`/`simCarrier` 观测为 UNKNOWN 时，
  只有 root 操作员背书才能补齐；`UNKNOWN` 背书不完整时仍按
  `calibration_environment_unknown` 拒绝，不会伪造环境事实。APP/HOOK 不能背书。
- **产品阻塞项**：校准记录只是自动接听的一道闸门；默认 `UnverifiedCallIdentityVerifier`
  仍 `blocksAutoAnswer=true`，没有设备验证过的、按通话身份定位的特权 Telecom 终止路径，
  所以即使记录齐全自动接听也不会开启。APK 只有 debug 签名、不可分发。
- **概览实时字段**：`hookConnected`/`dialerDefaultRole` 已由桥接 status 提供，但只有
  JVM 假替身证据（`BridgeStatusLiveValuesTest`、`DialerDefaultRoleProbeTest`）。真机上
  `RoleManager` 隐藏方法（`getRoleHolders`）的反射可用性、root 的权限判定与真实取值
  均**未验证**；不可读时后端返回 `"unknown"`，页面显示未知，不猜测。
- **连接测试**：WebUI 不执行连接测试，也不代发任何远程请求；真实测试只能在 AgentCall APK
  诊断页进行（会计费），当前无设备证据。
- **WebView 存储**：`localStorage` 保存“最近错误”在 file:// 或受限 WebView 中可能
  不可用，代码已降级为本次会话内显示；未在真实 WebView 验证。
- **密钥明文落盘**：主副本是 root 私有明文文件，依赖 0700/0600；不提供加密，
  不能防御同设备其它已授权 root 进程（与 spec 的威胁模型一致）。
- **业务校验边界**：`speed` 的 0.5–2.0 仅由 WebUI 校验，桥接 schema 不拒绝其它有限数值；
  名单长度上限由后续任务/后端补充，当前未在本文承诺。
- **总开关写入**：WebUI 的“保存开关”只提交 `{enabled}`，避免把其它页面未保存的
  密钥/字段一并提交；保存后只更新本地 baseline 的 revision 与 enabled，
  不清除其它页面的未保存标记。

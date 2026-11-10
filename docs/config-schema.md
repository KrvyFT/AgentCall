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
| `/data/adb/agentcall/inbox` | WebUI 写入的 `config-set` 临时负载 | 0700 |
| `/data/adb/agentcall/inbox/config-set.json` | 单次写入负载；写后立即删除 | 写入后收紧为 0000 |
| `/data/adb/agentcall/logs/service.log` | 服务日志（有大小上限） | 0700 目录，服务自身轮转 |

密钥以**明文**保存在 root 私有单文件里，依靠目录/文件权限与 root 隔离，不做加密。
这符合 spec §6.4/§11：密钥不进入普通应用备份、导出或日志，但不能防御同设备其它
已授权 root 进程。

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
- `status` 负载：`bridgeVersion`、`protocolVersion`、`revision`、`enabled`、`paired`、
  `uptimeMs`、`activeConnections`、`rejectedConnections`。
- `diagnose` 负载：`environment`（apiLevel/romFingerprint/kernelVersion/
  dialerPackageVersion/frameworkVersion/bridgeVersion/audioPolicyDigest/simCarrier/
  transport/route）、`downlinkExtraction`、`uplinkInjection`、`microphoneIsolation`、
  `privilegedTermination`、`checkedAtMs`、`notes`（已脱敏）。

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
- 配置内容只经 stdin 传输；命令行只有固定的
  `<moduleDir>/bin/agentcallctl config-set < /data/adb/agentcall/inbox/config-set.json`。
  密钥永远不进入进程 argv、URL、日志或错误消息。

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
| `module_dir_unresolved` | `moduleInfo()` 无法给出可信模块目录 | 禁用所有命令与保存（fail closed） |
| `io_unavailable` | 文件接口不可用（读日志等） | 显示“无法读取”，不伪造数据 |
| `unsupported_operation` | 不在六条固定命令内 | 拒绝，不拼 shell |
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
（非 root 调用包装器）、`bridge_container_missing`、`app_process_missing`。

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
| `pair` | 可选一个包名（默认 `org.krvyft.agentcall`） | 不用 | 配对成功负载 | 0 | 仅 root |
| `diagnose` | 无 | 不用 | 能力/环境报告 | 0 | CLI_ROOT |
| `shutdown` | 无 | 不用 | `{"stopping":true}` | 0 | CLI_ROOT |

退出码：`0` 成功、`1` 服务端错误、`2` 用法/负载错误、`3` 传输失败。
错误输出始终是单行机器可读 JSON：`{"error":{"code":"...","message":"..."}}`。

WebUI 的实际命令行（完整字符串，无用户数据）：

```
/data/adb/modules/agentcall/bin/agentcallctl status
/data/adb/modules/agentcall/bin/agentcallctl config-get
/data/adb/modules/agentcall/bin/agentcallctl config-set < /data/adb/agentcall/inbox/config-set.json
/data/adb/modules/agentcall/bin/agentcallctl pair
/data/adb/modules/agentcall/bin/agentcallctl diagnose
/data/adb/modules/agentcall/bin/agentcallctl shutdown
```

- 没有任意 RPC、没有任意 shell、没有把用户输入拼进命令。
- 模块目录必须匹配 `^/data/adb/modules/[A-Za-z][A-Za-z0-9._-]+$`；无法确认时拒绝执行。
- 旧管理器没有 `moduleInfo()` 时回退到固定路径 `/data/adb/modules/agentcall`，
  不拼接任何外部输入。

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
| `kernelsu` | 有 `ksu.exec`、`ksu.io()` ≥ v1、模块目录可信 | 全部六条命令；保存可用 |
| `readonly` | 有 `ksu.exec`，但没有 `ksu.io()`（或版本不足/模块目录不可信） | 读取/诊断/配对/关闭可用；**保存一律拒绝**（`secret_transport_unsupported`），绝不降级为 base64/echo 命令行参数 |
| `demo` | 普通浏览器，没有管理器注入 | 明确标注“演示数据”，保存、配对、日志均不可用；不发起任何网络请求 |

写入路径：

1. 前端把 `{expectedRevision, patch}` 序列化为 UTF-8 JSON。
2. 通过 `ksu.io().FileOutputStream()` 写入 `/data/adb/agentcall/inbox/config-set.json`。
   这里的 base64 只是 Java 文件桥的字节编码，不经过 shell、不进 argv。
3. 文件权限收紧为 0000；目录 0700。
4. 执行固定的 `agentcallctl config-set < <inbox>`。
5. 无论成功失败都删除临时文件；成功与否只以后端回读为准。

WebUI 不加载任何在线 CDN、不发起 fetch/XHR、不用 `innerHTML` 渲染不受信任文本；
配置与日志一律 `textContent`。诊断页的“校验地址格式”只做本地格式检查，
真实连通性测试会使用密钥向远程地址发起真实请求（可能计费），必须在 AgentCall APK 内执行；
WebUI 无法读取密钥，因此不代发请求。

---

## 10. 迁移与兼容

- `schema` 当前为 1。读取到更小的 schema：先写
  `config.schema-<n>.json.bak`，再按当前格式原地重写；revision 不变。
  当前实现把 schema 0 视为“无 secrets 段的旧格式”，迁移后 secrets 为空。
- 读取到更大的 schema：`config_schema_unsupported`，拒绝读取与覆盖，
  旧版模块不会降级覆盖新版配置。
- 模块升级：`customize.sh` 只补可执行位、校验桥接容器、预创建 0700 目录，
  不迁移、不改写既有配置；配置迁移由桥接在读取时完成。
- 卸载：`uninstall.sh` 先请求 `shutdown` 并等待桥接退出（上限 15 秒），
  未确认时明确警告“可能存在尚未交还的 AI 通话”，然后才删除
  `/data/adb/agentcall`。不修改 Google 电话默认角色、vendor 音频配置或 SELinux。

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
  文件接口细节未逐一验证。
- **WebView 存储**：`localStorage` 保存“最近错误”在 file:// 或受限 WebView 中可能
  不可用，代码已降级为本次会话内显示；未在真实 WebView 验证。
- **密钥明文落盘**：主副本是 root 私有明文文件，依赖 0700/0600；不提供加密，
  不能防御同设备其它已授权 root 进程（与 spec 的威胁模型一致）。
- **业务校验边界**：`speed` 的 0.5–2.0 仅由 WebUI 校验，桥接 schema 不拒绝其它有限数值；
  名单长度上限由后续任务/后端补充，当前未在本文承诺。
- **总开关写入**：WebUI 的“保存开关”只提交 `{enabled}`，避免把其它页面未保存的
  密钥/字段一并提交；保存后只更新本地 baseline 的 revision 与 enabled，
  不清除其它页面的未保存标记。

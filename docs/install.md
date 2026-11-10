# AgentCall 安装、配置与回退

本文描述 AgentCall 的安装范围、配对、配置、校准、升级与卸载。**所有真机步骤均为「未验证」**：
交付时 `adb devices` 为空，本仓库没有真机 Google 电话、KernelSU WebView 或音频证据；
用户也明确禁止设备侧验证。最终本地矩阵（HEAD `438cbbd`）通过：JVM 单测 **845**
（core 126 / bridge 360 / app 359，0 failures / 0 errors / 0 skipped）、lint 0 errors /
15 warnings；release APK 与模块 ZIP 已在该 HEAD 上重新打包（大小与 SHA-256 见第 1 节）。
标注「未验证」的条目仍不能当作已可用。

## 1. 产物与前提

发布产物（`make build` 生成，`dist/` 不提交）：

| 文件 | 内容 |
| --- | --- |
| `dist/agentcall-<version>.zip` | KernelSU 模块（含 `bridge/agentcall-bridge.jar` 与 `webroot/`） |
| `dist/agentcall-<version>.apk` | AgentCall APK（Xposed 模块 + 远程语音回合） |
| `dist/SHA256SUMS` | 两个产物的 SHA-256 |

最终打包（代码 HEAD `438cbbd`，在最后的 bridge/module 修复之后重新构建；以
`dist/SHA256SUMS` 为准）：`agentcall-1.0.zip` 1,405,746 B（11 个条目），SHA-256
`31eedd1fd4ca99571b7267e181624f00eafdf53022e1d2415ed11a20828c19a4`；
`agentcall-1.0.apk` 2,274,772 B，SHA-256
`8fd9332de246baf31154f83ca5fc7bfe48e41e28d676023aeadd108e135e0dc6`。
`python3 tools/check_package.py --dist dist` 输出 `Status: PASS`：appId
`org.krvyft.agentcall`、版本 1.0 (1)、`module.prop` 1.0 (1)、dex `classes.dex`、
结构 module=1/scripts=3/bridge=2/bin=1/webroot=4。

版本单一来源是 APK 的 `versionName/versionCode`；ZIP 内 `module.prop` 由它渲染，不一致时打包直接失败。
桥接容器也读同一来源：`:bridge` 在构建时从 `:app` 的 `defaultConfig` 生成 `BuildConfig.VERSION_NAME`
（`BridgeInfo.VERSION`）并把同一对版本写进 dex manifest 的 `bridgeVersion`；打包比对 app APK，
检查器比对 `module.prop`，因此桥上报版本、模块版本与 APK 版本三者不可能各写一份而漂移。
ZIP 里的 `bridge/agentcall-bridge.dex-manifest.json` 记录容器每个 dex 的名称/SHA-256/大小、
bridge APK 摘要与 `bridgeVersion`；打包与检查器只按它校验 DEX 覆盖，不用 app APK。

先校验产物完整性（拿到 dist/ 后第一步）：

```sh
cd dist
sha256sum -c SHA256SUMS
```

**签名**：默认用本机 Android debug keystore 重签，是 **debug 签名**，不是发布密钥。
本次交付 APK 的签名证书为 `CN=Android Debug`，证书 SHA-256
`3974a6d9dc587fe200107ff1c90b5ff20b0bcf4edcfb61dc030e9c6d2cd81085`，**不可分发**。
检查器只如实报告签名者（debug / custom signer / unsigned / unverified），不猜测非 debug 证书
“是不是发布密钥”。不同签名的同名 APK 不能直接覆盖安装；正式分发前必须用你自己的发布密钥重签：

```sh
BT="$ANDROID_HOME/build-tools/36.0.0"   # 本机实际 build-tools 版本
"$BT/zipalign" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/agentcall-aligned.apk
"$BT/apksigner" sign --ks /path/to/release.jks --ks-key-alias <alias> \
  --out dist/agentcall-<version>.apk /tmp/agentcall-aligned.apk
# 重签改变了 APK 摘要，旧 SHA256SUMS 必然失效：在 dist/ 内重新生成
cd dist
sha256sum agentcall-<version>.apk agentcall-<version>.zip > SHA256SUMS
cd ..
python3 tools/check_package.py --dist dist --expected-signer 'CN=你的发布证书'
```

没有 SDK build-tools 时检查器 **必然 FAIL**：签名无法验证（默认 FAIL），`versionName/versionCode`
与 `applicationId` 也仍要用 SDK 的 `aapt2` 读取（缺 SDK 时同样 FAIL）。
`--allow-unverified-signing` **只**关闭签名校验（报告仍打印 warning），不改变版本/包名读取，
因此它不能让“没有 SDK build-tools”的机器通过检查；该场景只能在装有 SDK build-tools 的环境运行。

前提（未验证 = 未在本仓库验证）：Android 16/17（`minSdk 36`，targetSdk 37；实现期间只在
API 37 Xperia 上做过只读观察，**没有在该设备上安装或验证任何东西**）、已安装 KernelSU、
已安装 LSPosed 或 Vector、已安装官方 Google 电话 `com.google.android.dialer`。
模块不重签 Google 电话、不注入 `system_server`、不改 SELinux、不自动改默认拨号器角色。

## 2. 安装顺序（未验证）

1. **安装模块**：KernelSU 管理器 → 模块 → 从本地安装 → 选择 `dist/agentcall-<version>.zip`。
   安装器（`customize.sh`）会校验 `bridge/agentcall-bridge.jar`、`bin/agentcallctl` 与
   `webroot/index.html`，缺一即中止；随后给 `bin/agentcallctl` 补 0755，预创建
   `/data/adb/agentcall`（0700）。不会修改拨号器角色、vendor 音频策略或 SELinux。
2. **重启手机**（未验证）：模块的 `service.sh` 在启动时拉起特权桥；安装后不重启是否可用未验证。
3. **安装 APK**：`adb install -r dist/agentcall-<version>.apk`，或在文件管理器中安装。
   debug 签名与旧版本签名不一致时先卸载旧版（会清除配对记录）。
4. **启用 Xposed 作用域**（未验证）：在 LSPosed/Vector 中启用 AgentCall 模块，
   作用域**只勾选** `com.google.android.dialer`；不要勾选系统框架或 `system_server`。
   然后强制停止 Google 电话（或重启）让 Hook 生效。

安装后先确认桥在运行（需要 root）：

```sh
su -c /data/adb/modules/agentcall/bin/agentcallctl status
```

输出是单行 JSON；失败时形如 `{"error":{"code":"...","message":"..."}}`。常见码见第 8 节。
`status` 有 **18 个顶层键**（17 个标量/布尔 + `calibration` 对象），含
`shutdownCode`（最近一次优雅关闭的机器码，干净/未发生为 `null`）与
`calibration`（与 `calibration.read` 相同的只读视图，含 `operatorAttestation` /
`effectiveEnvironment`）。`diagnose` 携带同一份校准视图。
`status` 还包含两个概览实时字段（阶段 B）：`hookConnected`（HOOK 心跳 5000 ms 窗口）
与 `dialerDefaultRole`（boolean，或无法在特权进程可靠确定时的字符串 `"unknown"`）。
两者都是本地查询；真机取值**未验证**，`"unknown"` 时 WebUI 显示“未知”，不猜。

## 3. 作用域与边界

AgentCall APK 是传统 Xposed 模块（`xposedmodule`、`xposed_init`、`xposed_scope` 只包含
`com.google.android.dialer`），不是替代拨号器：

- 不请求默认拨号器角色，不伪用只授予默认拨号器的前台服务资格。
- 不插入 Google 电话内部布局；人工接管通过 APK 的接管页/通知（TakeoverActivity）。
- Hook 只在 Google 电话进程内观察/受限控制，任何权限不足或环境不符都 fail-closed。

## 4. 首次配对（未验证）

配对只能在 root 侧发起，写入配对记录，之后 APK 与 Hook 才能连接特权桥：

```sh
su -c /data/adb/modules/agentcall/bin/agentcallctl pair
# 也可显式指定要配对的 APK 包名（默认 org.krvyft.agentcall）：
su -c /data/adb/modules/agentcall/bin/agentcallctl pair org.krvyft.agentcall
```

配对记录绑定 **uid + 签名 SHA-256 + versionName**。连接时三者任一不符都拒绝：

- APK 升级导致 `versionName` 变化 → `peer_version_mismatch`；
- APK 卸载重装导致 uid 变化 → `peer_uid_mismatch`；
- Google 电话升级导致 `versionName` 变化 → `peer_version_mismatch`。

以上任一情况都**必须重新执行 `pair`**。WebUI 在读取/保存失败时显示「配对已失效」横幅并给出
「重新配对」入口（经固定 `agentcallctl pair`，见第 5 节）；APK 只显示配对/就绪状态，不发起配对。

## 5. 配置与 WebUI（未验证）

KernelSU 管理器里打开 AgentCall 模块的 WebUI（静态资源随模块安装，无在线 CDN）。
WebUI 只调用模块内的固定包装器 `bin/agentcallctl` 的**七条**固定操作，不接受任意 shell；
包装器另有 root 操作员命令 `calibrate`（JSON 只从 stdin 进入，WebUI 不调用）：

```
WebUI（七条）：status | config-get | config-set（JSON 从 stdin） | pair | diagnose | shutdown | restart
root 操作员（不供 WebUI）：calibrate（JSON 从 stdin）
```

`restart` 是模块侧控制动作：先写重启标记，再执行固定 CLI `shutdown`；只有关闭裁决为
`clean=true` + `shutdownCode=null` + `microphoneIsolated=false` 才继续。若关闭不干净、
麦克风仍隔离（包括没有拥有会话但隔离仍为 true）或裁决缺失，则收回标记并返回
`restart_refused`（带机器码/隔离原因），不盲目重启。

能力状态：

| 状态 | 条件 | 能力 |
| --- | --- | --- |
| `kernelsu` | `ksu.exec` + `ksu.io()` v1 + 模块目录可信 | WebUI 的七条固定操作，保存可用 |
| `readonly` | 缺少 `ksu.io()` | 读取/诊断/配对/关闭可用，**保存被拒绝** |
| `readonly` | `moduleInfo()` 明确给出不可信 `moduleDir` | 依赖模块目录的命令禁用，固定路径日志仍可读 |
| `demo` | 普通浏览器无管理器注入 | 明确标注模拟数据，不发起任何请求 |

**总开关默认关闭**。未配对、未校准、Hook/桥未连通或无法保持 READY 时不会自动接听。
概览的默认拨号角色/Hook 连通性来自 `status`；规则、AI、对话三页各有独立的“未保存”
标记，保存某页只清除该页标记（不会清掉其它页面的未保存编辑）。
密钥只写在 root 私有目录 `/data/adb/agentcall/config.json`（目录 0700），WebUI 只看到
`CONFIGURED/MISSING`，不读取原值。

## 6. API 配置

在 WebUI 的 AI 页填写三个端点（ASR / LLM / TTS）：每个端点 `baseUrl` + `model`；
另有 `voice`、`speed`、`openingLine`、`prompt`。**baseUrl 必须是 `https://`**
（APK 传输层拒绝非 HTTPS，并拒绝重定向到非 HTTPS 或跨主机地址）。

密钥（`ASR/LLM/TTS`）只经 stdin 写入，不进入 argv/URL/日志。手工写入示例（引号 heredoc，
不做 shell 展开；写完由 CLI 删除临时文件）：

```sh
su -c 'cat > /data/adb/agentcall/inbox/config-set.json' <<'JSON'
{
  "expectedRevision": 7,
  "patch": {
    "asr": { "baseUrl": "https://api.example.com/v1", "model": "whisper-1" },
    "llm": { "baseUrl": "https://api.example.com/v1", "model": "your-model" },
    "tts": { "baseUrl": "https://api.example.com/v1", "model": "tts-1" },
    "voice": "alloy",
    "secrets": {
      "ASR": { "action": "replace", "value": "<新密钥>" },
      "LLM": { "action": "keep" },
      "TTS": { "action": "clear" }
    }
  }
}
JSON
su -c '/data/adb/modules/agentcall/bin/agentcallctl config-set < /data/adb/agentcall/inbox/config-set.json'
```

`patch` 是整体校验：任一字段非法则整包拒绝，不会部分生效；`expectedRevision` 是 CAS 版本，
不匹配时需重新 `config-get` 读取当前 `revision`。写入成功返回新的脱敏配置（`revision+1`）。

WebUI 的「连接测试」按钮只做本地地址格式校验，**不发起远程请求**；特权桥接本身也不发起任何
远程 API 请求。逐端点真实连接测试在 **APK 诊断页**手动触发，每次都会先要求确认「将发起真实
付费请求」：ASR 发送 100 ms WAV、LLM 发送最小对话、TTS 发送短文本，每个已配置端点各一次；
不显示或记录密钥与响应正文，未配置端点不发请求。语音/文本会发送给你配置的供应商，可能产生费用。

## 7. 规则与校准

规则页控制三种模式（黑名单 / 白名单 / 组合）、名单、地区、未知号码、接听延迟（默认 3000 ms）
与最长通话（默认 180000 ms）。默认 **不接管**：空黑名单在 `BLACKLIST` 下语义就是不接管，
未知/隐藏号码默认人工处理。

校准依赖真实设备上的音频证据（downlink 抽取、uplink 注入、麦克风隔离、特权终止能力）。
APK 校准页可辅助执行五项人工测试，但**只写 APK 自己的 SharedPreferences，不会写入桥接**；
APP 的自动接听就绪同时要求桥接 `calibration.read`（`available && supportedApiLevel==37 &&
verified && environmentComplete && environmentMatchesEvidence`；`supportedApiLevel` 是策略
常量 `CalibrationPolicy.SUPPORTED_API_LEVEL`，不是设备 API 等级，API 36 会因 `verified=false`
且 blockers 含 `API_LEVEL_UNSUPPORTED` 保持阻断）与本机 `CalibrationPolicy`，
读取失败/未知一律 false（`calibration_unavailable`），绝不沿用上一轮的 true。
桥接证据的唯一写入通道是 root CLI 的固定命令 `calibrate`（`calibration.record` RPC）：
JSON 只从 stdin 进入，argv 携带负载会在启动 CLI 前以 `usage_invalid` 拒绝。

十个环境字段中，`apiLevel`、`romFingerprint`、`kernelVersion`、
`dialerPackageVersion`、`bridgeVersion`、`audioPolicyDigest` 由桥接真实观测；
四个可背书字段中，`frameworkVersion` 依次读 root SystemProperties
（`ro.lsposed.version` / `persist.sys.lsposed.version` / `ro.vector.version` /
`persist.sys.vector.version` / `ro.edxposed.version` / `persist.sys.edxposed.version`），再读
`/data/adb/modules/{lsposed,zygisk_lsposed,vector}/module.prop` 的 `version=`，最后做
XposedBridge 类检查（只接受 `getXposedVersion()` 的 int/long/String：整数且为正、long 不超过
`Int.MAX_VALUE`，Double/NaN/负数/零/溢出一律视为未知；String 有界 ≤128、无控制字符、非
UNKNOWN）；`simCarrier` 先取 TelephonyManager 的 SIM 运营商名，再回退
`gsm.sim.operator.alpha` / `gsm.sim.operator.default-name` /
`ro.cdma.home.operator.alpha`；两者观测不到时才由 root 背书。
`transport`/`route` 则**从不观测**（`PlatformFacts.transport()`/`route()` 默认即
`UNKNOWN`，DeviceProbe 只调用它们），因此始终为 `UNKNOWN`，只能在 `calibrate` 时由 root
操作员背书；设备测试者不能把它们当作观测项验证。任何观测到的已知值都优先于背书。

五项人工测试（全部必须由真人实测，不得用本机记录、静态阅读或创建音频对象伪造）：

| JSON 键（全为 boolean） | 人工测试 |
| --- | --- |
| `downlinkRecognized` | 本机识别到通话下行音频（不是环境麦克风） |
| `peerHeardSpeech` | 对端真的听到注入的测试语音（TTS 注入可用） |
| `microphoneIsolated` | 本机麦克风隔离实际生效（对端听不到本机声音） |
| `manualRestoreVerified` | 人工接管能解除隔离并交还麦克风 |
| `processFailureVerified` | 进程故障注入后仍能故障终止/保持隔离 |

记录示例（先写 root 私有文件，再重定向 stdin；`calibrate` 不会自动删除该文件，写完请手动删除）。
`operatorAttestation` 只有 root 操作员能提供，用于填补 bridge 观测不到的字段；
`transport`/`route`/`manualTestCompleted` 必填，`frameworkVersion`/`simCarrier` 可选：

```sh
su -c 'cat > /data/adb/agentcall/inbox/calibration.json' <<'JSON'
{
  "steps": {
    "downlinkRecognized": true,
    "peerHeardSpeech": true,
    "microphoneIsolated": true,
    "manualRestoreVerified": true,
    "processFailureVerified": true
  },
  "operatorAttestation": {
    "transport": "CELLULAR",
    "route": "EARPIECE",
    "manualTestCompleted": true,
    "frameworkVersion": "1.9.2",
    "simCarrier": "Example Carrier"
  }
}
JSON
su -c '/data/adb/modules/agentcall/bin/agentcallctl calibrate < /data/adb/agentcall/inbox/calibration.json'
su -c 'rm -f /data/adb/agentcall/inbox/calibration.json'
```

- 成功输出 `{recorded, environment, operatorAttestation, steps, verified, blockers, calibratedAtMs}`；
  `verified` 只在五项全部为 true 且环境完整时才是 true，不要用本机勾选替代。
  `operatorAttestation` 回显 `{operatorAttested, transport, route, frameworkVersion, simCarrier}`：
  只有确实由 root 操作员背书、且该字段原本是 UNKNOWN 时才标记；bridge 已观测到的已知值
  永远优先，背书不能覆盖。
- `operatorAttestation` 允许五个键：前三个必填，后两个可选：
  - `transport` ∈ `CELLULAR`、`VOWIFI`、`UNKNOWN`；
  - `route` ∈ `EARPIECE`、`SPEAKER`、`HEADSET`、`BLUETOOTH`、`UNKNOWN`；
  - `manualTestCompleted` 必须是 JSON `true`（操作员确认五项人工测试真实完成）；
  - `frameworkVersion` / `simCarrier` 可选，出现时必须是 1..128 字符、前后无空白、不含
    控制字符、且不等于 `UNKNOWN` 的字符串；前后带空白会被**整体拒绝**（`payload_invalid`），
    桥接不会替操作员 trim。
  其他形状/词表/长度整体拒绝（`payload_invalid`）；APP/HOOK 不能调用 `calibration.record`，
  也不能提供背书，服务端按角色二次拒绝。`UNKNOWN` 不能解锁完整环境。
- `calibration.read`/status/diagnose 会标记**每一个**被背书的字段（`operatorAttested` 与
  `frameworkVersion`/`simCarrier`/`transport`/`route`），不把操作员背书显示成桥接实测。
- 可选 `expectedEnvironment` 把这些证据绑定到预期环境（`apiLevel`、`romFingerprint`、
  `kernelVersion`、`dialerPackageVersion`、`frameworkVersion`、`bridgeVersion`、
  `audioPolicyDigest`、`simCarrier`、`transport`、`route`）；当前环境（含 root 背书后的
  effective environment）的任一字段未知时返回 `calibration_environment_unknown`，与预期
  不一致时返回 `calibration_environment_mismatch`，两者都整体拒绝、绝不假装已验证。
- 校准存储未接线时返回 `calibration_unavailable`。
- 字段名、词表与最终语义以 HEAD `438cbbd` 与 `docs/config-schema.md` 为准。
- **产品阻塞项**：即使校准记录齐全，默认 `UnverifiedCallIdentityVerifier` 仍无法按通话
  身份核验，保持 `blocksAutoAnswer=true`；不存在设备验证过的、按身份定位的特权 Telecom
  终止路径。因此自动接听仍保持关闭（`calibration_not_verified`）。

root 侧可查看能力/环境报告：

```sh
su -c /data/adb/modules/agentcall/bin/agentcallctl diagnose
```

**未验证**：`calibrate` 的 stdin 继承、frameworkVersion/simCarrier 的真实观测（root
SystemProperties、模块 `module.prop`、XposedBridge 类检查、TelephonyManager/telephony 属性）
与五项人工测试本身的设备行为均未上机；`transport`/`route` 从不观测、始终为
`UNKNOWN`，只能由 root 背书。
校准通道已能在文档所述的 LSPosed/Vector + KernelSU 栈上补齐环境，但这不等于设备验证；
在真机完成校准前，产物一律按
「待适配/验证」对待，不宣称音频可用。

## 8. 升级、回退与卸载

### 升级

1. 关闭总开关，结束进行中的会话。`shutdown` 会优雅停止桥接进程，且 `service.sh` 不会自动重启；
   除排障外不要用它代替总开关，误用后可用 `agentcallctl restart`（或 WebUI 诊断页的
   “重试服务”）在本次开机内恢复，见下节；否则需要重启手机。
2. 安装新模块 ZIP（KernelSU 会替换模块目录），重启。
3. 安装新 APK，重新执行 `pair`：APK 的 `versionName` 已变化，旧配对记录必然触发
   `peer_version_mismatch`，不重新配对就无法连接。
4. Google 电话升级后同样要重新 `pair`（拨号器 `versionName` 变了）。
5. 重新校验 `status` 与 `diagnose`，再按需开启总开关。

### 桥接 BLOCKED / 服务恢复（重试服务）

桥接在麦克风恢复失败、持久状态损坏或“重启时仍持有旧通话”等情况下会进入
`BLOCKED`（`status.json` 的 `phase=BLOCKED` 与 `blockedReason`）：此时禁止开启新的
AI 会话，等待人工处理。恢复路径：

1. **WebUI**：诊断 → 服务操作 → **重试服务**。它只执行固定模块命令
   `agentcallctl restart`（无参数、无任意 shell），成功仅表示“重启请求已被接受”。
2. **root CLI**（等价）：
   ```sh
   su -c /data/adb/modules/agentcall/bin/agentcallctl restart
   ```
3. 命令先写 `/data/adb/agentcall/restart.requested`（内容是当前 `service.sh` 的 PID，
   0600），再执行固定 CLI `shutdown` 让桥接干净退出；该 `shutdown` 会先尝试终止/交还
   拥有的通话，确认断开后读回恢复麦克风（因此 `service.sh` 的停止路径必须先请求它、
   再停音频工作进程）；`service.sh` 在本次开机的有限重启循环内消费该标记并重新拉起
   桥接（只消费一次，不消耗故障重启预算）。
4. **诚实边界**：
   - `service_not_running`：`service.sh` 已退出（例如之前用过“关闭桥接服务”），
     本次开机内没有重启循环；只能重启设备或重新启用模块，`restart` **不会**伪造成功。
   - `module_disabled`：模块被 `disable`/`remove` 标记，重启请求会被拒绝，
     不会复活已移除模块。
   - `restart_unconfirmed`：桥接没有接受 `shutdown`（已退出或不可达），命令会收回
     请求，本次没有重启。
   - `restart_refused`：桥接接受了 `shutdown`，但裁决不是干净关闭（`clean=false`、
     `shutdownCode` 非 null、`microphoneIsolated=true`，或裁决缺失）。命令会收回标记，
     **不会重启**；消息里带机器码与隔离读回。先手动结束通话/确认麦克风恢复并重启设备。
   - 真实 BLOCKED 恢复（重启后的启动恢复是否清除阻塞）尚未在真机验证；
     `restart` 只负责“干净重启”，不代表阻塞条件已被修复。

### 回退（保留模块但停止自动化）

1. WebUI 关闭总开关（总开关持久化，重启后仍保持关闭）。
   如需彻底停止桥接进程可执行 `agentcallctl shutdown`；它会让桥优雅退出且不再自动重启，
   之后可用 `agentcallctl restart`/“重试服务”恢复（需 `service.sh` 仍在运行），
   因此只用于回退/排障。
2. 在 LSPosed/Vector 中停用 AgentCall 作用域并强制停止 Google 电话——Hook 退出后
   Google 电话恢复原生行为。
3. 删除/停用配对记录由 `pair` 重新建立；不需要也不应卸载官方拨号器。

### 卸载

1. 关闭总开关并**先确认没有进行中的通话**（卸载路径不能替代手动结束通话）。
2. KernelSU 管理器 → 模块 → 移除 AgentCall → 重启。
   移除时 `uninstall.sh` 的固定顺序（任务 11D2b / 11F）：
   1. 收回待处理的 `restart.requested`；
   2. 取 `/data/adb/agentcall/stop.lock`（flock，最多等 40 s）与 `service.sh` 串行化；
      拿不到锁时打印 `stop_lock_timeout` 并跳过重复的关闭请求，避免两个 CLI shutdown
      在恢复窗口内互相踩踏；
   3. 固定 CLI `shutdown`（最多等 25 s；CLI 的 shutdown-only socket 超时是 20 s），再等桥接
      进程/socket 消失（最多 10 s）；桥接在响应前终止/交还拥有的通话并读回恢复麦克风
      ——此时音频工作进程仍然存活；
   4. 读回 shutdown 响应（`clean`/`shutdownCode`/`supervision.microphoneIsolated`），
      桥接仍可响应时再读 `status` 的 `supervisionPhase`/`microphoneIsolated`（最多 5 s）；
   5. 只有 `clean=true`、`shutdownCode=null`、`microphoneIsolated=false` 且 phase 为
      `IDLE`/`ENDED`（或进程已退出）才打印“已确认干净关闭”；否则打印机器可读警告
      `! shutdown_result clean=... shutdownCode=... phase=... microphoneIsolated=... owned=... cli=...`
      并明确“未确认通话已终止或交还；麦克风可能仍被隔离”，**不会**谎称已交还；
   6. 之后才按 PID 文件核验并结束音频工作进程与残留桥接（cmdline 不匹配就不猜、不杀），
      释放停止锁，最后才删除 `/data/adb/agentcall`（连同粘性所有权标记）。
   持久裁决是模块**停止/退出路径**（`service.sh`）的行为，不是 `uninstall.sh` 的分支：
   `service.sh` 在桥接退出（哪怕退出码 0）后读
   `DATA_ROOT/supervisor-state.json`（schema ≥ 3），`shutdownCode` 非 null 或
   `microphoneIsolated=true` 时打印 `! microphone_still_isolated` /
   `! shutdown_unresolved` 与 `bridge_stop_unclean`，绝不打印 `bridge_exit_clean`。
   “非零退出并拒绝重启”只适用于桥接退出容器路径（`bridge_container_supervisor`，它的
   返回值成为 `service.sh` 退出码）；信号触发的 `on_stop` 只记录同样的警告并 `exit 0`。
   持久裁决缺失/损坏/schema<3 时 `bridge_exit_clean_verdict` 回退到退出码证据（不猜测
   隔离）；`agentcallctl restart` 更严格，必须从关闭响应读到裁决，缺失即
   `restart_refused`（`verdict_missing`）。
   两个 PID 文件都是首行单个十进制 PID。按 KernelSU 的标记语义，卸载会先写
   `remove`/`disable` 标记（设备上的实际卸载时序未验证）；`service.sh` 在每次重启前
   重新检查该标记，标记存在时不会把模块重新拉起；若手动在无标记时对进程执行 TERM，
   约 5 s 后可能被重启循环拉起。
3. 卸载时的能力边界（脚本会原样打印）：若收到警告，说明关闭未被确认——进行中的通话、
   麦克风隔离状态与粘性所有权标记可能仍然存在。脚本只能有界地停止进程；它**不能终止或
   交还正在进行的 AI 通话，也不能自行解除麦克风隔离**。重启设备会重新初始化系统音频/
   静音状态（脚本无法读回验证），且只有音频工作进程已停止后才不会被它再次设置；但重启
   不证明通话已结束，**下一次启动**仍会因粘性标记保持 `BLOCKED`，直到人工确认。卸载会删除
   该标记，重新安装后不会记得这次未解决的通话。它不改写 Google 电话角色或系统音频配置。
4. 卸载 AgentCall APK：设计上模块侧会关闭会话（**未验证**）；Google 电话保持默认拨号器与原行为。
5. 如需彻底清理：确认 `/data/adb/agentcall` 已删除、WebUI 不再被管理器加载；
   残留的 Xposed 作用域记录在框架管理器中删除。

### 常见错误

| 码 | 含义 | 处理 |
| --- | --- | --- |
| `bridge_container_missing` | 模块缺少桥运行时容器 | 用 `tools/package.py` 产出的 ZIP 重装 |
| `cli_requires_root` | `agentcallctl` 不是以 root 运行 | 用 `su -c` 执行 |
| `peer_unpaired` | 尚未配对 | 执行 `agentcallctl pair` |
| `peer_version_mismatch` | APK/拨号器升级后版本变了 | 重新 `pair` |
| `peer_uid_mismatch` | APK 卸载重装换了 uid | 重新 `pair` |
| `io_missing` / `module_dir_unresolved` | WebUI 只读降级 | 更新 KernelSU 管理器或检查模块目录 |
| `inbox_write_failed` | 无法写入 root 私有 inbox | 检查 `/data/adb/agentcall` 权限 |
| `secret_transport_unsupported` | 只读能力下尝试保存密钥 | 升级管理器或在 root CLI 写入 |
| `service_not_running` | `service.sh` 已退出（本次开机没有重启循环） | 重启设备或重新启用模块；不要伪造重启成功 |
| `module_disabled` | 模块带 `disable`/`remove` 标记 | 先启用/重装模块；不会重启已移除模块 |
| `restart_unconfirmed` | 桥接未接受 `shutdown`（退出/不可达） | 刷新状态；仍不可达时重启设备 |
| `shutdown_call_unresolved` | 5 s 期限（只等待 `awaitConfirmedDisconnect`，不含终止命令本身）内无法确认拥有的通话已断开；桥接保持隔离并失败关闭（粘性标记保留） | 先在 Google 电话中手动结束通话；确认 `status` 的 `microphoneIsolated` 与 `supervisionPhase`，必要时重启设备 |
| `shutdown_microphone_restore_failed` | 已确认断开，但麦克风恢复读回失败；桥接 `BLOCKED` | 人工确认麦克风状态；不要把“进程退出”当成已恢复 |
| `calibration_environment_unknown` / `..._mismatch` | 校准环境事实不完整/与预期不一致 | 不要伪造；在完整环境上重测五项后重试 `calibrate` |

## 9. 验证状态

| 步骤 | 状态 |
| --- | --- |
| 模块 ZIP 结构、DEX 入口、版本、SHA-256、密钥泄漏检查 | 本地已验证（`make package-check`） |
| 最终矩阵（HEAD `438cbbd`） | 本地 PASS：225 个 Gradle task；JVM 845（core 126 / bridge 360 / app 359，0 failures / 0 errors / 0 skipped）、lint 0 errors / 15 warnings；APK/ZIP 已在该 HEAD 重新打包（哈希见第 1 节） |
| JVM/JS/Python/脚本检查 | JVM 845；WebUI node 115/115；Python 59/59；`sh -n` 模块脚本通过；`make test` / `make build` / `make package-check` 全 PASS |
| release 收缩（R8） | 本地已验证：`packageScope = setOf("**")`，mapping 99,205 行，release 未签名 APK 2.18 MB（debug 重签 2.27 MB）；**真机未验证** |
| 配对（WebUI/root CLI）与 APK 校准/诊断界面 | 已实现；root CLI `calibrate` 经 `calibration.record` 写入五项人工证据；十个环境字段中六个（apiLevel、romFingerprint、kernelVersion、dialerPackageVersion、bridgeVersion、audioPolicyDigest）由桥接观测；四个可背书字段里 frameworkVersion/simCarrier 有真实观测源、只在观测不到时才由 root 背书，transport/route 则从不观测、始终为 UNKNOWN、只能在 calibrate 时由 root 背书；APP 就绪另需 `calibration.read` 的 supported/verified/environmentComplete/environmentMatchesEvidence（supportedApiLevel 是策略常量 `CalibrationPolicy.SUPPORTED_API_LEVEL`=37，不是设备 API 等级；API 36 因 `API_LEVEL_UNSUPPORTED` 保持阻断；真机探针与五项实测未验证，见第 7 节） |
| 优雅关闭停止顺序（先取 stop.lock、shutdown、后停音频工作进程、再 TERM→KILL） | 本地宿主已验证（`shutdown-order.test.mjs`、`module-scripts.test.mjs` 进程/CLI 替身，含并发锁等待）；**真机未验证** |
| 关闭裁决/隔离读回与未确认警告（service.sh + uninstall.sh） | 本地宿主已验证（shutdown 响应与最终 status 探测的隔离读回、`! microphone_still_isolated`）；**真机 KernelSU 卸载时序未验证** |
| `agentcallctl restart` 关闭裁决拒绝 | 本地宿主已验证（clean=false、mic 仍隔离、裁决缺失都返回 `restart_refused` 并收回标记）；**真机未验证** |
| `agentcallctl calibrate` stdin 逐字节透传与 argv 拒绝 | 本地宿主已验证（假 app_process）；**真机 app_process stdin 未验证** |
| 模块安装、重启后 `service.sh` 拉起桥 | **未验证** |
| LSPosed/Vector 作用域与 Hook 生效 | **未验证** |
| 首次配对、重新配对、`peer_version_mismatch` 提示 | **未验证** |
| KernelSU WebUI（真机 WebView） | **未验证** |
| 真实 Google 电话来电、≥3 轮双向 AI 对话 | **未验证** |
| 人工接管、故障终止、麦克风隔离 | **未验证** |
| 双卡、VoWiFi、蓝牙 | **未验证**，未测组合不启用 |

结论：安装步骤按设计写成，但**除本地构建/打包/结构检查与宿主测试替身外，没有真机证据**；
交付时也没有设备连接。root CLI `calibrate` 已提供桥接校准写入通道：十个环境字段中六个
由桥接真实观测；四个可背书字段里 frameworkVersion/simCarrier 有真实观测源、只在观测不到
时才由 root 背书，`transport`/`route` 则从不观测、始终为 UNKNOWN、只能由 root 背书；但真实环境
探针、五项人工测试、优雅关闭的麦克风恢复读回与 KernelSU 卸载时序都未上机。
**剩余产品阻塞项（不是单纯的设备缺口）**：即使校准记录齐全，按通话身份定位的特权 Telecom
终止路径仍不存在（`UnverifiedCallIdentityVerifier` 保持 `blocksAutoAnswer=true`），自动接听
不会开启；APK 只有 debug 签名、不可分发。在完成 spec §12 设备验收与这两项阻塞前，不要把本
模块当作可用的电话助理。

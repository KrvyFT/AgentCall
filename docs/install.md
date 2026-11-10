# AgentCall 安装、配置与回退

本文描述 AgentCall 的安装范围、配对、配置、校准、升级与卸载。**所有真机步骤均为「未验证」**：
本仓库当前没有真机 Google 电话、KernelSU WebView 或音频证据，标注「未验证」的条目不能当作已可用。

## 1. 产物与前提

发布产物（`make build` 生成，`dist/` 不提交）：

| 文件 | 内容 |
| --- | --- |
| `dist/agentcall-<version>.zip` | KernelSU 模块（含 `bridge/agentcall-bridge.jar` 与 `webroot/`） |
| `dist/agentcall-<version>.apk` | AgentCall APK（Xposed 模块 + 远程语音回合） |
| `dist/SHA256SUMS` | 两个产物的 SHA-256 |

版本单一来源是 APK 的 `versionName/versionCode`；ZIP 内 `module.prop` 由它渲染，不一致时打包直接失败。
ZIP 里的 `bridge/agentcall-bridge.dex-manifest.json` 记录容器每个 dex 的名称/SHA-256/大小与
bridge APK 摘要；打包与检查器只按它校验 DEX 覆盖，不用 app APK。

先校验产物完整性（拿到 dist/ 后第一步）：

```sh
cd dist
sha256sum -c SHA256SUMS
```

**签名**：默认用本机 Android debug keystore 重签，是 **debug 签名**，不是发布密钥。
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

没有 SDK build-tools（无法验证签名）时检查器默认 **FAIL**；只有明确接受未验证签名才加
`--allow-unverified-signing`（报告仍打印 warning）。

前提（未验证 = 未在本仓库验证）：Android 16/17（`minSdk 36`，目标与唯一验证 ROM 为已知 API 37 Xperia）、
已安装 KernelSU、已安装 LSPosed 或 Vector、已安装官方 Google 电话 `com.google.android.dialer`。
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
`status` 现在包含两个概览实时字段（阶段 B）：`hookConnected`（HOOK 心跳 5000 ms 窗口）
与 `dialerDefaultRole`（boolean，或无法在特权进程可靠确定时的字符串 `"unknown"`）。
两者都是本地查询；真机取值**未验证**，`"unknown"` 时 WebUI 显示“未知”，不猜。

## 3. 作用域与边界

AgentCall APK 是传统 Xposed 模块（`xposedmodule`、`xposed_init`、`xposed_scope` 只包含
`com.google.android.dialer`），不是替代拨号器：

- 不请求默认拨号器角色，不伪用只授予默认拨号器的前台服务资格。
- 不插入 Google 电话内部布局；人工接管通过 APK 界面（界面未实现）。
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

以上任一情况都**必须重新执行 `pair`**（WebUI 与 APK 的重新配对提示见第 6 节）。

## 5. 配置与 WebUI（未验证）

KernelSU 管理器里打开 AgentCall 模块的 WebUI（静态资源随模块安装，无在线 CDN）。
WebUI 只调用模块内的固定包装器 `bin/agentcallctl` 六条命令，不接受任意 shell：

```
status | config-get | config-set（JSON 从 stdin） | pair | diagnose | shutdown
```

能力状态：

| 状态 | 条件 | 能力 |
| --- | --- | --- |
| `kernelsu` | `ksu.exec` + `ksu.io()` v1 + 模块目录可信 | 全部六条命令，保存可用 |
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

WebUI 的「连接测试」按钮只做本地地址格式校验，**不发起远程请求**；逐端点真实连接测试属于
APK 诊断页，当前**未实现**。语音/文本会发送给你配置的供应商，可能产生费用。

## 7. 规则与校准

规则页控制三种模式（黑名单 / 白名单 / 组合）、名单、地区、未知号码、接听延迟（默认 3000 ms）
与最长通话（默认 180000 ms）。默认 **不接管**：空黑名单在 `BLACKLIST` 下语义就是不接管，
未知/隐藏号码默认人工处理。

校准依赖真实设备上的音频证据（downlink 抽取、uplink 注入、麦克风隔离、特权终止能力）。
当前 APK 校准页**未实现**；root 侧只能查看能力/环境报告：

```sh
su -c /data/adb/modules/agentcall/bin/agentcallctl diagnose
```

在真机校准完成前，产物一律按「待适配/验证」对待，不宣称音频可用。

## 8. 升级、回退与卸载

### 升级

1. 关闭总开关，结束进行中的会话。`shutdown` 会优雅停止桥接进程，且 `service.sh` 不会自动重启；
   除排障外不要用它代替总开关，误用后需要重启手机才能恢复。
2. 安装新模块 ZIP（KernelSU 会替换模块目录），重启。
3. 安装新 APK，重新执行 `pair`：APK 的 `versionName` 已变化，旧配对记录必然触发
   `peer_version_mismatch`，不重新配对就无法连接。
4. Google 电话升级后同样要重新 `pair`（拨号器 `versionName` 变了）。
5. 重新校验 `status` 与 `diagnose`，再按需开启总开关。

### 回退（保留模块但停止自动化）

1. WebUI 关闭总开关（总开关持久化，重启后仍保持关闭）。
   如需彻底停止桥接进程可执行 `agentcallctl shutdown`；它会让桥优雅退出且不再自动重启，
   之后重启手机才能恢复，因此只用于回退/排障。
2. 在 LSPosed/Vector 中停用 AgentCall 作用域并强制停止 Google 电话——Hook 退出后
   Google 电话恢复原生行为。
3. 删除/停用配对记录由 `pair` 重新建立；不需要也不应卸载官方拨号器。

### 卸载

1. 关闭总开关并确认没有进行中的通话。
2. KernelSU 管理器 → 模块 → 移除 AgentCall → 重启。
   移除时 `uninstall.sh` 只能**请求**停止桥接进程，并按 PID 文件核验后结束残留的桥接与
   音频工作进程（两个 PID 文件都是首行单个十进制 PID；cmdline 不匹配就不猜、不杀），
   最后才删除 `/data/adb/agentcall`。KernelSU 会先写 `remove`/`disable` 标记，
   `service.sh` 在每次重启前重新检查该标记，因此不会在卸载过程中把模块重新拉起；
   若手动在无标记时对进程执行 TERM，约 5 s 后可能被重启循环拉起。
   它**不能终止或交还正在进行的 AI 通话**（脚本头部已声明该边界），如卸载时正在 AI 通话，
   请先在 Google 电话中手动结束。它不改写 Google 电话角色或系统音频配置。
3. 卸载 AgentCall APK：设计上模块侧会关闭会话（**未验证**）；Google 电话保持默认拨号器与原行为。
4. 如需彻底清理：确认 `/data/adb/agentcall` 已删除、WebUI 不再被管理器加载；
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

## 9. 验证状态

| 步骤 | 状态 |
| --- | --- |
| 模块 ZIP 结构、DEX 入口、版本、SHA-256、密钥泄漏检查 | 本地已验证（`make package-check`） |
| JVM/JS/Python/脚本检查 | 本地已验证（`make test`） |
| 模块安装、重启后 `service.sh` 拉起桥 | **未验证** |
| LSPosed/Vector 作用域与 Hook 生效 | **未验证** |
| 首次配对、重新配对、`peer_version_mismatch` 提示 | **未验证** |
| KernelSU WebUI（真机 WebView） | **未验证** |
| 真实 Google 电话来电、≥3 轮双向 AI 对话 | **未验证** |
| 人工接管、故障终止、麦克风隔离 | **未验证** |
| 双卡、VoWiFi、蓝牙 | **未验证**，未测组合不启用 |

结论：安装步骤按设计写成，但**除本地构建/打包/结构检查外，没有真机证据**；
在完成 spec §12 设备验收前，不要把本模块当作可用的电话助理。

# AgentCall

AgentCall 在不替换 Google 电话（`com.google.android.dialer`）的前提下，为它加上远程 AI 接听能力：
Xposed Hook 观察来电与通话状态，AgentCall APK 完成远程 ASR / LLM / TTS 语音回合，KernelSU 模块里的
`app_process` 特权桥执行 Telecom 控制、音频注入、root 侧配置与中文 WebUI。

> **状态：可构建、可打包，不等于可用。** 当前仓库没有任何真机通话、音频、人工接管或故障终止证据；
> APK 的配对 / 校准 / 诊断界面尚未实现。设备验收（spec §12）全部待做，见「状态与边界」。

## 架构

三个信任层、两个进程边界：

```
Google 电话进程 (com.google.android.dialer, Xposed/LSPosed/Vector 作用域)
  └─ org.krvyft.agentcall.hook.HookEntry  只观察/受限控制，保留拨号器原行为
        │ 本地抽象 socket，配对记录校验 uid + 签名 + 版本
        ▼
KernelSU 模块 / root 特权桥 (app_process，uid 0)
  ├─ BridgeMain       控制面 + 监督（5000 ms 心跳、10000 ms 人工接管窗口、180000 ms 上限）
  ├─ AgentCallCli     root 固定六条命令：status/config-get/config-set/pair/diagnose/shutdown
  ├─ AudioWorkerMain  音频工作角色（独立 app_process，只持有 PCM 与麦克风隔离）
  └─ WebUI            静态中文配置页，只调用固定 CLI，不经 shell 拼接、不发起远程请求
        ▲
        │ 本地 socket + 配对
AgentCall APK (org.krvyft.agentcall, 普通应用 uid)
  └─ 远程语音回合、会话状态机、配对/校准/诊断（界面待实现）
```

规则、时钟、会话状态机、协议与安全边界都在 `core`：它不依赖 Android，由 JVM 单测覆盖；
`app` 只能通过 `core` 的 contracts 与 `bridge` 通信，依赖方向固定为 `app -> core <- bridge`。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | Xposed 入口、Hook 传输、远程 AI 客户端（语音回合、WAV、VAD） |
| `core/` | 共享 contracts、协议/角色鉴权、名单规则、会话状态机、端口接口 |
| `bridge/` | root 特权桥：IPC、配置 CAS、诊断、监督、音频、固定 CLI 入口 |
| `module/` | KernelSU 模块：`module.prop`、`service.sh`、`customize.sh`、`uninstall.sh`、`bin/agentcallctl`、`webroot/` 中文 WebUI |
| `tools/` | `package.py` 打包、`check_package.py` 产物检查、`tests/` 打包失败测试 |
| `docs/` | 安装说明、配置 schema、验证记录、设备验证矩阵 |

## 构建与测试

先决条件（本机已验证环境）：Gradle Wrapper 与 Launcher JVM 17，Daemon JVM 由
`gradle-daemon-jvm.properties` 指定；Android SDK（compileSdk 37）；Node 26；Python 3.14。
不下载任何额外二进制：Android 工具只取本机 SDK `build-tools`，Gradle 依赖走现有仓库配置。

Gradle 调用必须串行化，仓库约定：

```sh
flock /tmp/agentcall-gradle.lock ./gradlew :core:testDebugUnitTest :bridge:testDebugUnitTest :app:testDebugUnitTest
```

统一入口：

| 命令 | 作用 |
| --- | --- |
| `make test` | JVM 单测（core/bridge/app）+ WebUI node 测试 + Python 打包测试 + 模块脚本 `sh -n` |
| `make build` | `:app:assembleRelease`、`:bridge:packageRuntime`、`tools/package.py` 打包 |
| `make package-check` | 校验 `dist/` 结构、DEX 入口、版本、签名、密钥泄漏与 SHA-256 |
| `make lint` | `:app:lintDebug`（完整验收矩阵的一部分，不计入 `make test`） |

## 打包与签名

```sh
flock /tmp/agentcall-gradle.lock ./gradlew :app:assembleRelease :bridge:packageRuntime
python3 tools/package.py          # 签名 + 确定性模块 ZIP + SHA256SUMS
python3 tools/check_package.py    # 失败即非零退出
```

产物（`dist/` 已在 `.gitignore` 中，不提交）：

- `dist/agentcall-<version>.zip`：KernelSU 模块 ZIP，条目排序、固定时间戳、脚本 0755；
  包含 `bridge/agentcall-bridge.jar`（AGP release APK 的全部 `classes*.dex`）与 `webroot/` 静态资源，
  不含 `webroot/tests/`、不含任何签名私钥。
- `dist/agentcall-<version>.apk`：`app-release-unsigned.apk` 用本机 debug keystore 重签。
- `dist/SHA256SUMS`：`sha256sum` 兼容格式。

**版本单一来源**是构建出的 APK 的 `versionName/versionCode`：ZIP 里的 `module.prop` 由它渲染，
源 `module/module.prop` 与之不一致时打包直接失败，文件命名也来自同一个版本号。

**签名类型**：只使用本机 Android debug keystore（`$HOME/.android/debug.keystore`），
产物是 **debug 签名**，不是发布密钥，不能作为正式分发版本。仓库不包含任何私钥；
检查器会拒绝打包任何 `*.keystore/*.jks/*.p12` 或私钥/令牌材料。

## 状态与边界

| 范围 | 状态 |
| --- | --- |
| JVM 单测（core/bridge/app）、WebUI node 测试、打包测试 | 本地执行，见 `make test` 与 Task 9 报告 |
| release APK、模块 ZIP、SHA256SUMS、打包检查 | 本地已验证 |
| `app_process` 容器入口（BridgeMain/AgentCallCli/AudioWorkerMain）与 Kotlin/coroutines/Gson/core 运行库 | 结构级已验证（DEX 解析，等价 dexdump/javap 检查） |
| 真机 Google 电话原功能、真实来电、≥3 轮双向对话 | **未验证** |
| 音频 downlink/uplink、麦克风隔离、真实 Google 电话 | **未验证**（无设备证据，不宣称可用） |
| 人工接管、故障终止、锁屏/重启握手 | **未验证** |
| KernelSU 真机安装、WebView WebUI | **未验证**（无真机） |
| APK 配对 / 校准 / 诊断界面 | **未实现**（Task 7 范围） |
| 双卡、VoWiFi、蓝牙 | **未验证**，未测组合不启用 |

多 DEX 说明：`app_process` 的 `PathClassLoader` 只从 classpath JAR 根部的 `classes.dex` 加载类。
打包任务会收进 release APK 的**所有** `classes*.dex`，但检查器要求全部必需入口/运行类必须在
`classes.dex` 内，否则打包失败——当前 release 恰好只有一个 `classes.dex`。
未来若必须拆 dex，需要重新设计容器加载方式，而不是默认多 dex 可用。

更细的本地/真机证据见 `docs/verification.md`、`docs/device-validation.md`；安装与回退步骤见
`docs/install.md`；配置 schema 与固定 CLI 见 `docs/config-schema.md`。

## 安全与隐私

- 总开关默认关闭；未配对、未校准或无法保持 READY 时不自动接听。
- 语音/文本会发送给用户配置的远程供应商；默认不保存原始录音或完整转写。
- 密钥只经 stdin 写入 root 私有目录（0700），不进入 argv、URL、日志或导出；WebUI 不读取密钥。
- 模块与 APK 都依赖用户自行安装的 Xposed 框架与 KernelSU；不重签 Google 电话、不注入
  `system_server`、不改 SELinux、不自动刷框架或重启。

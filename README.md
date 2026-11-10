# AgentCall

AgentCall 在不替换 Google 电话（`com.google.android.dialer`）的前提下，为它加上远程 AI 接听能力：
Xposed Hook 观察来电与通话状态，AgentCall APK 完成远程 ASR / LLM / TTS 语音回合，KernelSU 模块里的
`app_process` 特权桥执行 Telecom 控制、音频注入、root 侧配置与中文 WebUI。

> **状态：本地构建与验收矩阵通过，不等于可用。** 最终矩阵（HEAD `5638995`）JVM 单测
> core 126 / bridge 345 / app 344（合计 **815**，0 failures / 0 errors / 0 skipped），
> lint 0 errors / 15 warnings；release APK 与模块 ZIP 已在该 HEAD 上重新打包（大小与
> SHA-256 见下）。但**没有真机证据**：交付时 `adb devices` 为空，设备侧一行都没跑；
> 配对（WebUI/root CLI）、root CLI `calibrate` 与 APK 校准/诊断界面已实现，校准写入需要
> root 操作员对 transport/route 背书；生产就绪仍为 `NOT_READY`（`calibration_not_verified`），
> 设备验收（spec §12）全部待做，见「状态与边界」与 `docs/verification.md`。
>
> 公开仓库：<https://github.com/KrvyFT/AgentCall>（分支 `master`）；仓库与文档中不含任何令牌或凭据。

## 架构

三个信任层、两个进程边界：

```
Google 电话进程 (com.google.android.dialer, Xposed/LSPosed/Vector 作用域)
  └─ org.krvyft.agentcall.hook.HookEntry  只观察/受限控制，保留拨号器原行为
        │ 本地抽象 socket，配对记录校验 uid + 签名 + 版本
        ▼
KernelSU 模块 / root 特权桥 (app_process，uid 0)
  ├─ BridgeMain       控制面 + 监督（5000 ms 心跳、10000 ms 人工接管窗口、180000 ms 上限）
  ├─ AgentCallCli     root 固定 CLI：status/config-get/config-set/calibrate/pair/diagnose/shutdown（calibrate 只供 root 操作员）
  ├─ AudioWorkerMain  音频工作角色（独立 app_process，只持有 PCM 与麦克风隔离）
  └─ WebUI            静态中文配置页，只调用固定 CLI，不经 shell 拼接、不发起远程请求
        ▲
        │ 本地 socket + 配对
AgentCall APK (org.krvyft.agentcall, 普通应用 uid)
  └─ 远程语音回合、会话状态机、校准/诊断界面与配对（WebUI/root CLI；校准证据经 root CLI 写入）
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
`gradle-daemon-jvm.properties` 指定；Android SDK（compileSdk 37，platform `android-37.0`，build-tools 36.0.0）；Node 26.13.0；Python 3.14.9。
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
| `make package-check` | 校验 `dist/` 结构、DEX 容器与 manifest、applicationId、版本、签名、密钥泄漏与 SHA-256 |
| `make lint` | `:app:lintDebug`（完整验收矩阵的一部分，不计入 `make test`） |

## 打包与签名

```sh
flock /tmp/agentcall-gradle.lock ./gradlew :app:assembleRelease :bridge:packageRuntime
python3 tools/package.py          # 签名 + 确定性模块 ZIP + SHA256SUMS
python3 tools/check_package.py    # 失败即非零退出
```

产物（`dist/` 已在 `.gitignore` 中，不提交）：

- `dist/agentcall-<version>.zip`：KernelSU 模块 ZIP，条目按名排序、固定时间戳、脚本 0755、数据文件 0644；
  包含 `bridge/agentcall-bridge.jar`（bridge release APK 的全部 `classes*.dex`）、
  `bridge/agentcall-bridge.dex-manifest.json`（每个 dex 的 name/SHA-256/size 与来源 APK 摘要）
  与 `webroot/` 静态资源；不含 `webroot/tests/`、不含任何签名私钥。
- `dist/agentcall-<version>.apk`：`app-release-unsigned.apk` 用本机 debug keystore 重签；
  apksigner 的 v4 `.idsig` 旁文件不生成（不属于发布物）。
- `dist/SHA256SUMS`：`sha256sum` 兼容格式。

最终打包产物（在 HEAD `5638995` 的最终代码改动之后重新构建；`dist/` 不提交）：

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/agentcall-1.0.zip` | 1,400,028 B（11 个条目） | `7e285e63b35fd9563fafffc3282dd8ace8141810cee27f3f2459e3e80b7195ef` |
| `dist/agentcall-1.0.apk` | 2,266,580 B | `37ccd66c890aea749c885927a03faeb892a7c73a9a247afc48d3d2442ca52a65` |
| `dist/SHA256SUMS` | 168 B | 与上面两行一致 |

APK 由本机 debug keystore 签名（`CN=Android Debug`，证书 SHA-256
`3974a6d9dc587fe200107ff1c90b5ff20b0bcf4edcfb61dc030e9c6d2cd81085`），**不可分发**；
正式分发前必须用发布密钥重签，并重新生成 `SHA256SUMS`，步骤见 `docs/install.md` 第 1 节。

**DEX 覆盖只用容器 manifest**：`:bridge:packageRuntime` 从 bridge release APK 收集 dex 并写出
manifest，打包与 `check_package.py` 都拿容器与 manifest 比对（名称 + SHA-256 + size），
存在 bridge APK 时再交叉核对一次；**不使用 app APK**——app APK 是另一个产物，
单 dex 时比对无意义、多 dex 时会误报。

**版本单一来源**是构建出的 APK 的 `versionName/versionCode`：ZIP 里的 `module.prop` 由它渲染，
源 `module/module.prop` 与之不一致时打包直接失败，文件命名也来自同一个版本号。

**签名类型**：默认只使用本机 Android debug keystore（`$HOME/.android/debug.keystore`），
产物是 **debug 签名**，不是发布密钥，不能作为正式分发版本。检查器只如实报告签名者
（debug / custom signer / unsigned / unverified），不猜测某个非 debug 证书“是不是发布密钥”；
正式分发请用你自己的发布密钥重签并用 `--expected-signer 'CN=...'` 强制校验，
步骤见 `docs/install.md` 第 1 节（重签会改变 APK 摘要，必须重生成 `SHA256SUMS`）。
仓库不包含任何私钥；检查器会拒绝打包任何 `*.keystore/*.jks/*.p12/*.pepk` 或私钥/令牌材料。

## 固定操作与关闭语义

- **WebUI 可调用的操作是七条**：`status`、`config-get`、`config-set`（JSON 只从 stdin 进入）、
  `pair`、`diagnose`、`shutdown`、`restart`。WebUI 不发任意 RPC、不拼 shell、不加载在线资源。
  `restart` 是模块侧控制动作：先写重启标记，再执行固定 CLI `shutdown`；只有裁决为
  `clean=true` + `shutdownCode=null` + `microphoneIsolated=false` 才继续，否则收回标记并返回
  `restart_refused`（消息带机器码与隔离读回），不会盲目重启。
- **`calibrate` 不是 WebUI 操作**：它是 root 操作员的固定 CLI 命令——零参数，JSON 只从 stdin
  进入（argv 携带负载在启动 CLI 前以 `usage_invalid` 拒绝）。载荷是五项人工测试步骤 +
  可选 `expectedEnvironment` + 可选 `operatorAttestation`；WebUI 不写校准证据，也不伪造证据。
- **优雅关闭**：`shutdown` 在 5 s 内终止/交还拥有的通话（`SHUTDOWN_TERMINATION_DEADLINE_MS`
  = 5000 ms），等待确认断开或确认已不归 AI；随后在单一 6 s 预算
  （`SHUTDOWN_RESTORE_BUDGET_MS`）内恢复麦克风并读回验证。`clean=true` 只在隔离被确认解除时
  成立；否则 `clean=false` 并带 `shutdown_microphone_still_isolated` /
  `shutdown_microphone_restore_failed` / `shutdown_call_unresolved`。CLI 只对 `shutdown`
  把 socket 超时放宽到 20 s。崩溃/SIGKILL 不走该路径，可能留下麦克风隔离。
- **停止顺序**：模块停止时先请求桥接优雅关闭，再停音频工作进程（先杀音频会让恢复请求
  不可达）；`service.sh` / `uninstall.sh` 用跨脚本 `flock` 停止锁串行化。仍隔离时停止脚本
  打印机器可读警告（`! shutdown_result ...` / `! microphone_still_isolated ...`），
  **绝不谎称已干净关闭**。

## 状态与边界

| 范围 | 状态 |
| --- | --- |
| JVM 单测（core/bridge/app） | 最终本地矩阵（HEAD `5638995`）：126 / 345 / 344，合计 **815**，0 failures / 0 errors / 0 skipped |
| WebUI node 测试、Python 打包测试、模块脚本语法 | 本地已验证：node 113/113、Python 59/59、`sh -n` 通过 4 个模块脚本 |
| `:app:lintDebug` | 本地已验证：SUCCESSFUL，0 errors、15 warnings（13 条既有告警 + 2 条 `UseKtx`） |
| R8（release） | 启用：`packageScope = setOf("**")`，release mapping 约 98,580 行；release APK 由 6.44 MB 缩到 2.27 MB |
| release APK、模块 ZIP、SHA256SUMS、打包检查 | 本地已验证：`make build` / `make package-check` PASS（appId `org.krvyft.agentcall`，版本 1.0 (1)，dex `classes.dex`，结构 module=1/scripts=3/bridge=2/bin=1/webroot=4）；产物与哈希见上表 |
| `app_process` 容器入口（BridgeMain/AgentCallCli/AudioWorkerMain）与 Kotlin/coroutines/Gson/core 运行库 | 结构级已验证（自研 DEX 解析：只校验类描述符与方法名存在，不校验方法原型/字节码语义） |
| 真机 Google 电话原功能、真实来电、≥3 轮双向对话 | **未验证**（交付时无设备连接） |
| 音频 downlink/uplink、麦克风隔离、真实 Google 电话 | **未验证**（无设备证据，不宣称可用） |
| 人工接管、故障终止、锁屏/重启握手 | **未验证** |
| KernelSU 真机安装、WebView WebUI、`ksu.io` 路径 | **未验证**（无真机） |
| 配对（WebUI/root CLI）与 APK 校准/诊断界面 | 已实现；root CLI `calibrate` 经 `calibration.record` 写入五项人工证据，transport/route 需 root 操作员显式背书（固定词表；观测为 UNKNOWN 时才采用，已验证值优先）。真机证据仍缺失，自动接听保持关闭（`calibration_not_verified`） |
| 双卡、VoWiFi、蓝牙 | **未验证**，未测组合不启用 |

多 DEX 说明：`app_process` 的 `PathClassLoader` 只从 classpath JAR 根部的 `classes.dex` 加载类。
打包任务会收进 **bridge release APK** 的**所有** `classes*.dex` 并写入 manifest，但检查器要求
全部必需入口/运行类必须在 `classes.dex` 内，否则打包失败——当前 bridge release 恰好只有一个
`classes.dex`。
未来若必须拆 dex，需要重新设计容器加载方式，而不是默认多 dex 可用。

更细的本地/真机证据见 `docs/verification.md`、`docs/device-validation.md`；安装与回退步骤见
`docs/install.md`；配置 schema 与固定 CLI 见 `docs/config-schema.md`。

## 安全与隐私

- 总开关默认关闭；未配对、未校准或无法保持 READY 时不自动接听。
- 语音/文本会发送给用户配置的远程供应商；默认不保存原始录音或完整转写。
- 密钥只经 stdin 写入 root 私有目录（0700），不进入 argv、URL、日志或导出；WebUI 不读取密钥。
- 模块与 APK 都依赖用户自行安装的 Xposed 框架与 KernelSU；不重签 Google 电话、不注入
  `system_server`、不改 SELinux、不自动刷框架或重启。

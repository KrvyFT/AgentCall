# AgentCall

AgentCall 在不替换 Google 电话（`com.google.android.dialer`）的前提下，为它加上远程 AI 接听能力：
Xposed Hook 观察来电与通话状态，AgentCall APK 完成远程 ASR / LLM / TTS 语音回合，KernelSU 模块里的
`app_process` 特权桥执行 Telecom 控制、音频注入、root 侧配置与中文 WebUI。

> **状态：本地构建与验收矩阵通过，不等于可用。** 最终矩阵（代码 HEAD `ae8c70f`）JVM 单测
> core 126 / bridge 390 / app 359（合计 **875**，0 failures / 0 errors / 0 skipped），
> WebUI node 141/141、Python 打包 59/59、`sh -n` 通过，lint 0 errors / 15 warnings；
> release APK、bridge 容器与模块 ZIP 都在该 HEAD 上重新构建（大小与 SHA-256 见下）。
> 本轮是设备驱动修复（11N/11O）：桥接改为**分层 PackageManager 获取**
> （context → AppGlobals → ServiceManager/binder → shell 事实），
> `service.sh` 不再把 `stop.lock` 的 fd 继承给 `app_process` 子进程、桥接退出后的清理只做
> 非阻塞取锁，WebUI 增加 **exec+env 保存通道**。但**没有真机证据**：交付时
> `adb devices` 为空，设备侧一行都没跑；
> 配对（WebUI/root CLI）、root CLI `calibrate` 与 APK 校准/诊断界面已实现。校准环境的十个
> 字段中，`apiLevel`、`romFingerprint`、`kernelVersion`、`dialerPackageVersion`、
> `bridgeVersion`、`audioPolicyDigest` 由桥接真实观测；四个可背书字段里 `frameworkVersion`
> （root SystemProperties、模块 `module.prop` 的 `version=`、XposedBridge 类检查的
> int/long/String）与 `simCarrier`（TelephonyManager 的 SIM 运营商名与 telephony 属性）
> 有真实观测源，只在观测不到时才由 root 背书；`transport`/`route` 则**从不观测**（始终
> UNKNOWN），只能在 `calibrate` 时由 root 操作员背书。
> **产品阻塞项不变（不是单纯的设备缺口）**：自动接听需要校准记录**且**需要设备
> 验证过的、按通话身份定位的特权 Telecom 终止路径（`UnverifiedCallIdentityVerifier`
> 保持 `blocksAutoAnswer=true`）；APK 只有 debug 签名、不可分发。生产就绪仍为
> `NOT_READY`（`calibration_not_verified`），设备验收（spec §12）全部待做，见「状态与边界」与
> `docs/verification.md`。本轮修复来自设备侧失败证据（管理器只有函数形式的 `ksu.io`、
> 没有 `File`/`FileOutputStream`；`app_process` 桥接曾以 `package_manager_unavailable`
> 退出），这些证据在 `docs/verification.md` 5.4，**不构成任何验证结果**。
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

最终打包产物（在代码 HEAD `ae8c70f` 上重新构建；`dist/` 不提交）：

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/agentcall-1.0.zip` | 1,421,090 B（11 个条目） | `1bb871c255e6d1d6ea44fdf666d9d95bdfd764781faef8ccd2e4d3053dfccf8c` |
| `dist/agentcall-1.0.apk` | 2,274,772 B | `583b579529ce937f406d67510e0c844f074b6c927220f1054c44b5d3b9c46c11` |
| `dist/SHA256SUMS` | 168 B | 与上面两行一致 |

**哈希绑定这次构建**：APK 内嵌构建元数据，打包过程也不是逐字节可复现的，所以上表只对应
`ae8c70f` 的这次构建。在任何更晚的提交（包括本次 docs 提交之后）重建都会改变 APK/ZIP
字节，必须重新生成 `SHA256SUMS` 并以新文件为准；`dist/` 不进仓库，`sha256sum -c` 通过。

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
  可选 `expectedEnvironment` + 可选 `operatorAttestation`（必填 `transport`/`route`/
  `manualTestCompleted`，可选 `frameworkVersion`/`simCarrier`）；WebUI 不写校准证据，也不伪造证据。
- **优雅关闭**：`shutdown` 先下发终止/交还命令，然后在 5 s 期限
  （`SHUTDOWN_TERMINATION_DEADLINE_MS` = 5000 ms）内只等待“已断开/已不归 AI”的事实
  （`awaitConfirmedDisconnect`）。该 5 s **只约束这个等待**：终止命令先于期限下发，其自身
  等待另有预算，所以 5 s 不是“终止 + 确认”的总时长。确认后在单一 6 s 预算
  （`SHUTDOWN_RESTORE_BUDGET_MS`）内恢复麦克风并读回验证。`clean=true` 只在隔离被确认解除时
  成立；否则 `clean=false` 并带 `shutdown_microphone_still_isolated` /
  `shutdown_microphone_restore_failed` / `shutdown_call_unresolved`。CLI 只对 `shutdown`
  把 socket 超时放宽到 20 s。崩溃/SIGKILL 不走该路径，可能留下麦克风隔离。
- **停止顺序**：模块停止时先请求桥接优雅关闭，再停音频工作进程（先杀音频会让恢复请求
  不可达）；`service.sh` / `uninstall.sh` 用跨脚本 `flock` 停止锁串行化（最多等 40 s，
  CLI 25 s、桥接退出 10 s、最终探测 5 s）。桥接退出后 `service.sh` 读回
  `DATA_ROOT/supervisor-state.json`（schema ≥ 3）：`shutdownCode` 非 null 或
  `microphoneIsolated=true` 时打印 `! microphone_still_isolated ...` /
  `! shutdown_unresolved ...` 与 `bridge_stop_unclean`，**绝不打印 `bridge_exit_clean`**。
  这里的非零退出只属于桥接退出容器路径（`bridge_container_supervisor` 的返回值成为
  `service.sh` 退出码）；信号触发的 `on_stop` 只记录同样的警告并 `exit 0`。持久裁决
  缺失/损坏/schema<3 时回退到退出码证据（`bridge_exit_clean_verdict`）；`agentcallctl
  restart` 更严格，必须读到关闭响应裁决，缺失即 `restart_refused`（`verdict_missing`）。
  `agentcallctl restart` 也只在关闭裁决为 `clean=true` + `shutdownCode=null` +
  `microphoneIsolated=false` 时继续，否则返回 `restart_refused`。

## 状态与边界

| 范围 | 状态 |
| --- | --- |
| JVM 单测（core/bridge/app） | 最终本地矩阵（代码 HEAD `ae8c70f`）：126 / 390 / 359，合计 **875**，0 failures / 0 errors / 0 skipped |
| WebUI node 测试、Python 打包测试、模块脚本语法 | 本地已验证：node 141/141（含 exec+env 回退、env 探测、密钥卫生与 KernelSU source 安装回归）、Python 59/59、`sh -n` 通过 4 个模块脚本 |
| `:app:lintDebug` | 本地已验证：SUCCESSFUL，0 errors、15 warnings（13 条既有告警 + 2 条 `UseKtx`） |
| R8（release） | 启用：`packageScope = setOf("**")`，release mapping 约 99,205 行；release 未签名 APK 2.18 MB（debug 重签交付 2.27 MB） |
| release APK、模块 ZIP、SHA256SUMS、打包检查 | 本地已验证：`make build` / `make package-check` PASS（appId `org.krvyft.agentcall`，版本 1.0 (1)，dex `classes.dex`，结构 module=1/scripts=3/bridge=2/bin=1/webroot=4）；产物与哈希见上表 |
| 桥接分层 PackageManager 获取（11N） | 本地 JVM 已验证：provider 顺序/缓存/日志码、shell 解析、shell 签名显式 unsupported 的 fail closed；**真机未验证**（设备上旧的 context-only 路径曾以 `package_manager_unavailable` 退出） |
| WebUI 保存通道（11O） | 本地 node 已验证：file / exec+env / readonly 三态、env 探测每页会话一次、密钥不进命令/日志；**真机 KernelSU WebView 未验证**（设备管理器只有函数形式 `ksu.io`、没有 `File`/`FileOutputStream`） |
| `service.sh` 停止锁（11N） | 本地宿主已验证（真实 flock + 替身）：`app_process` 子进程不继承 fd 9、桥接退出后非阻塞清理、桥接存活时仍串行化；**真机未验证** |
| `app_process` 容器入口（BridgeMain/AgentCallCli/AudioWorkerMain）与 Kotlin/coroutines/Gson/core 运行库 | 结构级已验证（自研 DEX 解析：只校验类描述符与方法名存在，不校验方法原型/字节码语义） |
| 真机 Google 电话原功能、真实来电、≥3 轮双向对话 | **未验证**（交付时无设备连接） |
| 音频 downlink/uplink、麦克风隔离、真实 Google 电话 | **未验证**（无设备证据，不宣称可用） |
| 人工接管、故障终止、锁屏/重启握手 | **未验证** |
| KernelSU 真机安装、WebView WebUI、`ksu.io` 路径 | **未验证**（无真机） |
| 配对（WebUI/root CLI）与 APK 校准/诊断界面 | 已实现；root CLI `calibrate` 经 `calibration.record` 写入五项人工证据。十个环境字段中六个（apiLevel、romFingerprint、kernelVersion、dialerPackageVersion、bridgeVersion、audioPolicyDigest）由桥接观测；四个可背书字段里 frameworkVersion/simCarrier 有真实观测源、只在观测不到时才由 root 背书，transport/route 则从不观测、始终为 UNKNOWN、只能在 calibrate 时由 root 背书。APP 就绪要求 `calibration.read` 的 available && supportedApiLevel==37 && verified && environmentComplete && environmentMatchesEvidence，再叠加本机 `CalibrationPolicy`；`supportedApiLevel` 是策略常量 `CalibrationPolicy.SUPPORTED_API_LEVEL`（37），不是设备 API 等级——API 36 会因 `verified=false` 且 blockers 含 `API_LEVEL_UNSUPPORTED` 保持阻断。读取失败/未知一律 false（`calibration_unavailable`），不缓存旧 true。真机证据仍缺失，自动接听保持关闭（`calibration_not_verified`） |
| 双卡、VoWiFi、蓝牙 | **未验证**，未测组合不启用 |
| **产品阻塞项**：按通话身份定位的特权 Telecom 终止 | **不存在**：`UnverifiedCallIdentityVerifier` 保持 `blocksAutoAnswer=true`；即使校准记录齐全，自动接听也不会开启 |
| **产品阻塞项**：APK 签名 | 仅 debug 签名（`CN=Android Debug`），不可分发；必须用发布密钥重签并重生成 `SHA256SUMS` |

### 排障入口

真机上先分清“桥接没起来”“保存通道只读”“停止锁没串行化”三类问题（完整命令与期望见
`docs/install.md` 2.1）：

- **桥接启动**：`/data/adb/agentcall/logs/service.log` 应有一行
  `package_provider=<context|appglobals|binder|shell>`，每次失败各有一行
  `package_provider_attempt=<code> result=unavailable`。出现
  `package_provider_unavailable`（四层都失败）或 `package_manager_unavailable`
  （桥接退出码 1）说明包事实拿不到，属于 fail closed，不是可忽略的告警。
- **保存通道**：WebUI 诊断页给出 `ioForm`/`ioSupport`/`ioVersion`/`hasFile`/
  `hasFileOutputStream`/`ioMode`。`ioMode=env` 表示走固定命令 + `AGENTCALL_CFG_PAYLOAD`
  env；`ioMode=none` + `env_unavailable` 表示只读，保存被拒绝。
- **停止锁**：桥接已退出后的清理只允许 `stop_lock_acquired ... nonblocking=1` 或
  `! stop_lock_busy ... nonblocking=1`；不应再出现 `! stop_lock_timeout waited=40s`。
  桥接仍存活时才是有界等待的 `stop_lock_wait` → `stop_lock_acquired`。

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

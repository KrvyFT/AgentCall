# AgentCall 最终验证记录

本文件记录 AgentCall 在交付提交上的实际执行结果。只写可复现命令与原始结果；
没有执行的项一律进入「未运行」清单，不用静态阅读或 JVM 测试代替真机结论。

- 记录时间：2026-11-11（Asia/Shanghai）
- 仓库：`/home/krvy/Projects/AgentCall`
- 分支 / HEAD：`master` / `438cbbd`（最终代码提交：Xposed 版本只接受整数正数
  int/long，Double/NaN/负数/零/溢出保持 UNKNOWN；transport/route 更正为从不观测）
- 完整 225-task 验收矩阵、`make test`、`make build`、`make package-check` 都在这个 HEAD 上
  执行；`dist/` 也在其最终代码改动之后重新打包（哈希见 4.5），不是旧构建的遗留。
- 远程仓库：`https://github.com/KrvyFT/AgentCall`（public，默认分支 `master`）；文档中不含任何令牌或凭据。
- 验收执行者：控制器（controller）；本文件在交付时重新读取测试 XML、重跑 `make test` 与
  `make package-check`，并核对产物哈希、签名与工具链版本。
- 相关文档：安装与回退见 `docs/install.md`，真机矩阵见 `docs/device-validation.md`。

## 1. 工具链事实

| 项目 | 值 | 依据 |
| --- | --- | --- |
| Gradle | 9.6.0（Wrapper） | `gradle/wrapper/gradle-wrapper.properties`、`./gradlew --version` |
| Launcher JVM | 17.0.21.0.3（Arch Linux） | `./gradlew --version` |
| Daemon JVM | `/opt/android-studio/jbr`，Java 25.0.4 | `gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=25`；`/opt/android-studio/jbr/bin/java -version` |
| AGP | 9.5.1，内置 Kotlin（未应用 `kotlin-android`） | 模块 plugins 块与构建输出 |
| compileSdk | 37，platform `android-37.0` | `app/build.gradle.kts`、`bridge/build.gradle.kts`；本机 SDK platforms |
| targetSdk / minSdk | 37 / 36 | 同上 `defaultConfig` |
| build-tools | 36.0.0 | 本机 SDK `build-tools/36.0.0`（`aapt2`/`apksigner` 实际使用） |
| Node | 26.13.0 | `node --version` |
| Python | 3.14.9 | `python3 --version` |

说明：`./gradlew --version` 输出里的 `Kotlin: 2.3.21` 是 Gradle 构建脚本自带的 Kotlin 版本，
不是 Android 模块的 Kotlin 编译器；模块编译器来自 AGP 9.5.1 内置 Kotlin。

## 2. 环境与镜像

- 本机对 `DL.google.com` 与 `foojay` 的 TLS 访问被阻断，直接走官方仓库不可用。
- 因此使用**机器级** `~/.gradle/init.d` 镜像配置；该文件不属于仓库，仓库内没有镜像地址、
  凭据或令牌。构建可复现性依赖这台机器的该配置，换机需自行准备等价镜像。
- 所有 Gradle 调用按仓库约定串行化：`flock /tmp/agentcall-gradle.lock ./gradlew ...`，
  避免并发争抢 daemon 与 build 目录。
- 交付时 `adb devices` 为空；没有设备参与本次最终验收。

## 3. 最终验收矩阵

最终矩阵在 HEAD `438cbbd` 上完整重跑（`--rerun-tasks`，没有复用更早的结果）：

```sh
flock /tmp/agentcall-gradle.lock ./gradlew \
  :core:testDebugUnitTest :bridge:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug :app:assembleRelease :bridge:packageRuntime --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，**225 个 Gradle task 实际执行**。随后在同一 HEAD 上
`make test` / `make build` / `make package-check` 全部 PASS。

| 验证面 | 实际结果 |
| --- | --- |
| `:core:testDebugUnitTest` | 126 tests，0 failures / 0 errors / 0 skipped |
| `:bridge:testDebugUnitTest` | 360 tests，0 failures / 0 errors / 0 skipped |
| `:app:testDebugUnitTest` | 359 tests，0 failures / 0 errors / 0 skipped |
| 合计 JVM | **845 tests，全部通过**（0 failures / 0 errors / 0 skipped） |
| `:app:lintDebug` | SUCCESSFUL；0 errors，15 warnings（明细见 4.2） |
| `:app:assembleRelease` | SUCCESSFUL |
| `:bridge:packageRuntime` | SUCCESSFUL |
| `make test` | PASS（JVM + WebUI node + Python 打包测试 + shell 语法） |
| `make build` | PASS（release APK + bridge 运行时容器 + `dist/` 打包） |
| `make package-check` | PASS（结构 / DEX manifest / 版本 / 签名 / 密钥泄漏 / SHA-256） |

## 4. 分项证据

### 4.1 JVM 单元测试

测试计数不是抄报告，而是交付时从 JUnit XML 重新汇总
（`*/build/test-results/testDebugUnitTest/TEST-*.xml`，由上述矩阵生成）：

| 模块 | 测试类 XML 数 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| `:core` | 7 | 126 | 0 | 0 | 0 |
| `:bridge` | 49 | 360 | 0 | 0 | 0 |
| `:app` | 29 | 359 | 0 | 0 | 0 |
| 合计 | 85 | **845** | 0 | 0 | 0 |

这些是宿主 JVM 上的逻辑测试，**不能**代替任何真机结论（见第 5 节）。

### 4.2 Lint

`:app:lintDebug` 通过，报告末行为 `0 errors, 15 warnings`：

- 13 条是既有告警：`PrivateApi` 1（`ServiceDiscovery` 反射访问 `ActivityThread`）、
  `QueryPermissionsNeeded` 1（同文件），以及依赖/Gradle/AGP 版本提示
  （`GradleDependency` 5、`NewerVersionAvailable` 5、`AndroidGradlePluginVersion` 1）。
- 另有 2 条 `UseKtx`（`AgentCallService` 的 `SharedPreferences.edit` 提示），来自后续新增的服务代码。

15 条全部是 warning，没有 error，不阻塞本次验收。

### 4.3 构建与运行时容器

- `:app:assembleRelease`：SUCCESSFUL，产出 `app/build/outputs/apk/release/app-release-unsigned.apk`。
- `:bridge:packageRuntime`：SUCCESSFUL，从 bridge release APK 收集 dex 并生成运行时容器与
  `bridge/agentcall-bridge.dex-manifest.json`。
- `make build`：PASS，随后由 `tools/package.py` 完成签名、确定性模块 ZIP 与 `SHA256SUMS`。

### 4.4 脚本与 WebUI

交付时重跑 `make test` 通过（退出码 0）：

- JVM 单测：同上 845 项（HEAD `438cbbd`，完整重跑）。
- WebUI node 测试：`node --test module/webroot/tests/*.test.mjs`，115 tests / 115 pass / 0 fail / 0 skipped。
- Python 打包测试：`python3 -m unittest discover -s tools/tests -v`，59 tests，OK。
- 模块脚本语法：`sh -n` 检查 `customize.sh`、`service.sh`、`uninstall.sh`、`bin/agentcallctl`。
- `make package-check` 重跑：`Status: PASS`（结构、版本、签名、SHA-256 见 4.5）。

### 4.5 产物、哈希与签名

`dist/` 已被 `.gitignore` 忽略，不提交；本次交付产物：

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/agentcall-1.0.zip` | 1,405,746 B | `31eedd1fd4ca99571b7267e181624f00eafdf53022e1d2415ed11a20828c19a4` |
| `dist/agentcall-1.0.apk` | 2,274,772 B | `8fd9332de246baf31154f83ca5fc7bfe48e41e28d676023aeadd108e135e0dc6` |
| `dist/SHA256SUMS` | 168 B | 两行，分别匹配上面两个摘要 |

这些哈希对应**最终代码 HEAD `438cbbd` 上重新完成的打包**（在最后的 bridge 收紧之后），
与 `dist/SHA256SUMS` 一致；`sha256sum -c` 通过。重签 APK 后哈希必然变化，必须重生成
`SHA256SUMS` 并以新文件为准。

模块 ZIP 共 **11 个条目**（`bin/agentcallctl`、`bridge/agentcall-bridge.jar`、
`bridge/agentcall-bridge.dex-manifest.json`、`customize.sh`、`module.prop`、`service.sh`、
`uninstall.sh`、`webroot/` 4 个静态资源），含 dex manifest，不含 `webroot/tests/`、私钥或令牌。

**签名（重要）**：APK 由本机 debug keystore 签名，证书
`CN=Android Debug`，SHA-256
`3974a6d9dc587fe200107ff1c90b5ff20b0bcf4edcfb61dc030e9c6d2cd81085`。
这是 **debug 签名，不是发布密钥，不可分发**。正式分发前必须用发布密钥重签，并在重签后
重新生成 `SHA256SUMS`（重签会改变 APK 摘要）；步骤见 `docs/install.md` 第 1 节。
检查器只如实报告签名者，不会把 debug 证书猜成发布证书。

### 4.6 版本与打包结构

- **版本单一来源**是 `:app` 的 `versionName/versionCode`：`1.0` / `1`。
- `:bridge` 在构建时从 `:app` 的 `defaultConfig` 派生 `BuildConfig.VERSION_NAME`
  （`BridgeInfo.VERSION`），不另写一份版本号。
- 运行时容器携带 **dex manifest（schema 2）**：记录每个 dex 的 name/SHA-256/size、
  来源 bridge APK 摘要与 `bridgeVersion`；本次容器内为 `classes.dex`
  （sha256 `e8a3e4398505f75b438a3c230aa5de6acf78408e01cb970099ca60274a94b1b7`，
  size 4,036,372 B；来源 bridge APK sha256
  `3028f1d326ab35289b595cd12c0e2e479990aaef60225962c8bc1f63099db5c1`）。
- 模块 `module.prop` 为 `1.0` / `1`，与 APK 版本一致；不一致时打包直接失败。
- DEX 覆盖只用容器 manifest 校验，不用 app APK（两者是不同产物）。

### 4.7 R8 收缩（release）

- `:app` release 启用 R8：`optimization { enable = true }`，并显式
  `packageScope = setOf("**")`，让 app 自身代码（不只是 androidx/kotlin）真的进入收缩/改名。
- 本次 `app/build/outputs/mapping/release/mapping.txt` 共 **99,205 行**。
- release 未签名 APK 为 **2,184,465 B（2.18 MB）**；交付的 debug 重签 APK 为 2,274,772 B（2.27 MB）。
- R8 是本地构建行为；**没有在真机上验证** release 构建的运行时行为（见第 5.2 节）。

## 5. 本地已验证 / 设备未验证 / 阻塞项

### 5.1 本地已验证（宿主环境）

- 最终矩阵：BUILD SUCCESSFUL，225 个 Gradle task 实际执行（HEAD `438cbbd`）。
- JVM 单测：core 126 / bridge 360 / app 359，合计 845，0 failures / 0 errors / 0 skipped。
- Lint：`:app:lintDebug` SUCCESSFUL，0 errors、15 warnings（全部 warning）。
- R8：release 收缩启用（`packageScope = setOf("**")`），mapping 99,205 行；release 未签名
  APK 2.18 MB，debug 重签交付 APK 2.27 MB（见 4.7）。
- Release APK 与 bridge 运行时容器均构建成功。
- `make test` PASS：JVM（845）+ WebUI node（115）+ Python（59）+ 模块脚本 `sh -n`。
- `make build` PASS；`make package-check` PASS（appId `org.krvyft.agentcall`，版本 1.0 (1)，
  `module.prop` 1.0 (1)，dex `classes.dex`，结构 module=1/scripts=3/bridge=2/bin=1/webroot=4）。
- 产物结构、版本一致性、DEX manifest 覆盖、debug 签名类型、密钥泄漏检查与 SHA-256 均已核对。
- 容器 manifest 的版本、每个 dex 的哈希与大小、来源 APK 摘要已核对。
- `status` 暴露 **18 个顶层键**（17 个标量/布尔 + `calibration` 对象），含 `shutdownCode`；
  `diagnose` 携带同一份校准视图。监督状态 schema 为 3。
- 环境观测：`frameworkVersion` 依次读 root SystemProperties
  （`ro.lsposed.version`/`persist.sys.lsposed.version`/`ro.vector.version`/
  `persist.sys.vector.version`/`ro.edxposed.version`/`persist.sys.edxposed.version`），再读
  `/data/adb/modules/{lsposed,zygisk_lsposed,vector}/module.prop` 的 `version=`，最后做
  XposedBridge 类检查（`getXposedVersion()` 的 int/long/String 返回值；整数且为正、long
  不超过 `Int.MAX_VALUE`，Double/NaN/负数/零/溢出保持 UNKNOWN）；`simCarrier` 读
  TelephonyManager SIM 运营商名，失败后回退
  `gsm.sim.operator.alpha`/`gsm.sim.operator.default-name`/`ro.cdma.home.operator.alpha`。
- 关闭语义：5 s 期限只约束 `awaitConfirmedDisconnect`（终止命令先下发，其等待另有预算），
  恢复只有单一 6 s 预算，CLI 仅 `shutdown` 放宽到 20 s；`clean=true` 只在隔离确认解除时成立。
  `service.sh` 在桥接退出后读 `supervisor-state.json`（schema ≥ 3）：`shutdownCode` 非 null
  或 `microphoneIsolated=true` 时打印 `! microphone_still_isolated`/`! shutdown_unresolved`
  与 `bridge_stop_unclean`，绝不打印 `bridge_exit_clean`。非零退出只属于桥接退出容器路径
  （`bridge_container_supervisor` 的返回值成为 `service.sh` 退出码）；信号触发的 `on_stop`
  只记录同样的警告并 `exit 0`。持久裁决缺失/损坏/schema<3 时 `bridge_exit_clean_verdict`
  回退到退出码证据；`agentcallctl restart` 更严格，关闭响应裁决缺失即拒绝并报
  `verdict_missing`。
- APP 就绪从 `calibration.read` 派生：`available && supportedApiLevel==37 && verified &&
  environmentComplete && environmentMatchesEvidence` + 本机 `CalibrationPolicy`；其中
  `supportedApiLevel` 是策略常量 `CalibrationPolicy.SUPPORTED_API_LEVEL`，不是设备 API
  等级（API 36 的 `environment.apiLevel` 不匹配 → `API_LEVEL_UNSUPPORTED`、`verified=false`）；
  读取失败/未知一律 `calibration_unavailable` 并返回 false，绝不缓存上一轮的 true。

### 5.2 设备未验证（没有任何一行在真机上执行）

1. LSPosed/Vector 是否加载模块。
2. Google 电话 `InCallService` 的真实发现与回调。
3. Telecom 主线程行为。
4. 隐藏音频 API 是否可见可用，以及 HAL 行为。
5. `AudioRecord` / `AudioTrack` 播放头（playback head）。
6. 麦克风隔离的读回验证。
7. 两条传输上的 `LocalSocket` 对端凭据（peer credentials）。
8. `app_process` 的 bridge/audio-worker 启动与 `service.sh` 重启。
9. KernelSU WebView 的 `ksu.io` 路径与卸载时序。
10. FGS / 通知 / 锁屏 / 重启 / 进程死亡。
11. 真实付费 API 请求。
12. 重采样（resampler）的真实音频。

### 5.3 阻塞项（保持失败关闭，不伪造）

1. **自动接听仍是产品阻塞项（两道闸门，缺一不可）**：校准写入通道与观测源都已实现。
   `EnvironmentKey` 十个字段中，`apiLevel`、`romFingerprint`、`kernelVersion`、
   `dialerPackageVersion`、`bridgeVersion`、`audioPolicyDigest` 由桥接真实观测；四个可背书
   字段里，`frameworkVersion`（root SystemProperties、模块 `module.prop` 的 `version=`、
   XposedBridge 的 int/long/String）与 `simCarrier`（TelephonyManager SIM 运营商名与
   telephony 属性）有真实观测源、只在观测不到时才由 root 背书；`transport`/`route` 则
   **从不观测**、始终为 UNKNOWN，只能由 root 操作员在 `calibrate` 时用固定词表背书
   （`manualTestCompleted=true`），观测到的已知值永远优先。
   **但即使校准记录齐全，默认 `UnverifiedCallIdentityVerifier` 仍无法按通话身份核验，保持
   `blocksAutoAnswer=true`；不存在设备验证过的、按身份定位的特权 Telecom 终止路径。**
   因此生产就绪**有意保持 NOT_READY（`calibration_not_verified`）**，必须继续 fail closed。
2. **APK 只有 debug 签名**：不可分发；需发布密钥重签并重生成 `SHA256SUMS`。
3. **交付时没有连接设备**：`adb devices` 为空。此前实现期间曾只读观察到一台
   XQ-DQ72（API 37），但 `adb shell` 是 uid 2000，`su` 不可用，所以**没有在设备上运行
   任何东西**；用户也明确禁止设备侧验证。现在设备同样不在线。
4. 因此没有仪器测试、模块安装、Hook 加载、真实音频/通话或付费请求证据；
   `docs/device-validation.md` 的 D01–D12 全部仍为「未验证」；校准环境观测与四项背书也只有
   宿主/JVM 证据，未在真机确认。

## 6. 回合预算裁决（必须保留）

- **45 秒上限覆盖 ASR / LLM / TTS 的生成阶段**，不覆盖音频播放
  （`ApiTransport.TURN_TIMEOUT_MS = 45_000`）。
- 音频播放有**自己的有界等待**：名义 PCM 时长 + **15 秒宽限**
  （`ConversationEngine.PLAYBACK_GRACE_MS = 15_000`），并且可被取消立即中断。
- spec/plan 文本仍写「整个回合 45 秒」。交付文档在此明确裁决：播放**不**计入这 45 秒。
  后续维护者不得把播放静默塞回回合预算，否则长 TTS 会被误判超时。

## 7. 重新配对语义

配对记录绑定 **uid + 签名 SHA-256 + versionName**。任一项变化都必须重新配对：

- APK 升级 → `versionName` 变化 → `peer_version_mismatch`；
- Google 电话升级 → `versionName` 变化 → `peer_version_mismatch`；
- APK 卸载重装 → uid 变化 → `peer_uid_mismatch`。

WebUI 在保存/读取失败时显示「配对已失效」横幅，并给出「重新配对」入口
（经固定 `agentcallctl pair` 执行，首次配对仍只能用 root CLI）。重新配对不是可选的
排障步骤，而是升级后的必要条件。

## 8. 没有运行的项目

- 没有运行任何仪器测试（instrumented tests）；没有在设备上安装 APK 或 KernelSU 模块。
- 没有加载 LSPosed/Vector，也没有触发 Google 电话 Hook。
- 没有真实来电、真实音频、麦克风隔离、重采样或对端听音验证。
- 没有安装/卸载 KernelSU 模块，未验证 WebView、`service.sh` 重启或卸载时序。
- 没有锁屏、Doze、重启、通知、进程死亡或 FGS 行为验证。
- 没有发起任何真实付费 API 请求（ASR/LLM/TTS）。
- 没有写入任何校准证据；没有启用自动接听。
- 没有用发布密钥重签 APK。
- 没有重跑完整验收矩阵之外的设备检查；本文件中的矩阵结果来自 HEAD `438cbbd` 上的一次
  完整本地执行（225 个 Gradle task），不是旧提交结果的拼接。
- 没有在设备上执行任何一项：交付时 `adb devices` 为空，用户明确禁止设备侧验证；
  仪器测试、KernelSU 安装、LSPosed 加载、真实通话、真实音频与真实付费 API 全部未运行。

## 9. 复现入口

```sh
# 单一 Gradle 验收命令（串行化）
flock /tmp/agentcall-gradle.lock ./gradlew \
  :core:testDebugUnitTest :bridge:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug :app:assembleRelease :bridge:packageRuntime --rerun-tasks

# 统一入口
make test            # JVM + WebUI node + Python + shell 语法
make build           # release APK + bridge 运行时容器 + dist 打包
make package-check   # 结构 / DEX / 版本 / 签名 / 密钥泄漏 / SHA-256
```

产物会被 `dist/` 接收（不提交）。安装、配对、重签与卸载见 `docs/install.md`；
真机矩阵与翻绿证据要求见 `docs/device-validation.md`。

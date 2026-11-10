# AgentCall 最终验证记录

本文件记录 AgentCall 在交付提交上的实际执行结果。只写可复现命令与原始结果；
没有执行的项一律进入「未运行」清单，不用静态阅读或 JVM 测试代替真机结论。

- 记录时间：2026-11-11（Asia/Shanghai）
- 仓库：`/home/krvy/Projects/AgentCall`
- 分支 / HEAD：`master` / `2d924f4`（11E 已合入：关闭预算 + root 校准背书）
- 11E 只改 bridge/core；app 结果沿用 11E 之前的 337。产物尚未在 11E 后重新打包，
  最终 `dist/` 哈希需按第 9 节重跑替换。
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

最近一次矩阵（HEAD `2d924f4`；11E 在最后两个提交上重跑 core/bridge，app 未受 11E 影响；
产物仍是 11E 之前的构建，最终重新打包/哈希替换见 4.5）：

```sh
flock /tmp/agentcall-gradle.lock ./gradlew \
  :core:testDebugUnitTest :bridge:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug :app:assembleRelease :bridge:packageRuntime --rerun-tasks
```

结果：`BUILD SUCCESSFUL`（原完整矩阵 225 个 actionable task；11E 在 `2d924f4` 以
`:core:testDebugUnitTest :bridge:testDebugUnitTest :bridge:assembleDebug --rerun-tasks`
重跑 core/bridge，68 个 task 实际执行，没有复用旧结果）。

| 验证面 | 实际结果 |
| --- | --- |
| `:core:testDebugUnitTest` | 126 tests，0 failures / 0 errors / 0 skipped |
| `:bridge:testDebugUnitTest` | 343 tests，0 failures / 0 errors / 0 skipped（11E 关闭预算修复后重跑） |
| `:app:testDebugUnitTest` | 337 tests，0 failures / 0 errors / 0 skipped（11E 未改 app） |
| 合计 JVM | **806 tests，全部通过**（11E 最后重跑 core/bridge；app 未受 11E 影响） |
| `:app:lintDebug` | SUCCESSFUL；0 errors，14 warnings（明细见 4.2） |
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
| `:bridge` | 49 | 343 | 0 | 0 | 0 |
| `:app` | 28 | 337 | 0 | 0 | 0 |
| 合计 | 84 | **806** | 0 | 0 | 0 |

这些是宿主 JVM 上的逻辑测试，**不能**代替任何真机结论（见第 5 节）。

### 4.2 Lint

`:app:lintDebug` 通过，报告末行为 `0 errors, 14 warnings`：

- 13 条是既有告警：`PrivateApi`（`ServiceDiscovery` 反射访问 `ActivityThread`）、
  `QueryPermissionsNeeded`（同文件），以及依赖/Gradle/AGP 版本提示（`GradleDependency` 5、
  `NewerVersionAvailable` 5、`AndroidGradlePluginVersion` 1）。
- 第 14 条是 `UseKtx`（`AgentCallService` 的 `SharedPreferences.edit` 提示），来自后续新增的服务代码。

验收摘要曾把既有告警记为 13 条；lint 报告的完整计数是 14 条，差异就是这条 `UseKtx`。
14 条全部是 warning，没有 error，不阻塞本次验收。

### 4.3 构建与运行时容器

- `:app:assembleRelease`：SUCCESSFUL，产出 `app/build/outputs/apk/release/app-release-unsigned.apk`。
- `:bridge:packageRuntime`：SUCCESSFUL，从 bridge release APK 收集 dex 并生成运行时容器与
  `bridge/agentcall-bridge.dex-manifest.json`。
- `make build`：PASS，随后由 `tools/package.py` 完成签名、确定性模块 ZIP 与 `SHA256SUMS`。

### 4.4 脚本与 WebUI

交付时重跑 `make test` 通过（退出码 0）：

- JVM 单测：同上 806 项（HEAD `2d924f4`，11E 最后重跑 core/bridge）。
- WebUI node 测试：`node --test module/webroot/tests/*.test.mjs`，113 tests / 113 pass / 0 fail / 0 skipped（11F 新增停止锁、隔离读回与 restart 裁决用例）。
- Python 打包测试：`python3 -m unittest discover -s tools/tests -v`，52 tests，OK；`py_compile` 通过。
- 模块脚本语法：`sh -n` 检查 `customize.sh`、`service.sh`、`uninstall.sh`、`bin/agentcallctl`。
- `make package-check` 重跑：`Status: PASS`（结构、版本、签名、SHA-256 见 4.5）。

### 4.5 产物、哈希与签名

`dist/` 已被 `.gitignore` 忽略，不提交；本次交付产物：

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/agentcall-1.0.zip` | 1,389,833 B | `01f5a22da46b27f0d60de5f17b2076add74ee5ffc46b6e7fb079d885312e8be7` |
| `dist/agentcall-1.0.apk` | 2,266,580 B | `f5ac5b3ed55b9d092e188c10c549f56b9f3d68829a1e3d307019f5a8f66b94a6` |
| `dist/SHA256SUMS` | 168 B | 两行，分别匹配上面两个摘要 |

这些哈希对应**最近一次已完成打包**（代码 HEAD `0073cfe`，11E 之前）。11E（`cd2f4e5` /
`2d924f4`）改了 bridge（关闭预算 / 校准背书），但尚未重新打包；必须按第 9 节重新构建并
替换本节哈希，在那之前不要把本表当作最终产物。

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
  （sha256 `8228dad5888b2a9baa3aff9cf834451c98625ffbc4dacffefa46a9e07e31170f`）。
- 模块 `module.prop` 为 `1.0` / `1`，与 APK 版本一致；不一致时打包直接失败。
- DEX 覆盖只用容器 manifest 校验，不用 app APK（两者是不同产物）。

## 5. 本地已验证 / 设备未验证 / 阻塞项

### 5.1 本地已验证（宿主环境）

- 最近一次矩阵：BUILD SUCCESSFUL（11E 在 `2d924f4` 重跑 core/bridge）。
- JVM 单测：core 126 / bridge 343 / app 337，合计 806，0 failures / 0 errors / 0 skipped。
- Lint：`:app:lintDebug` SUCCESSFUL，0 errors、14 warnings（全部 warning）。
- Release APK 与 bridge 运行时容器均构建成功。
- `make test` PASS：JVM + WebUI node（113）+ Python（52）+ 4 个模块脚本语法。
- `make build` PASS；`make package-check` PASS。
- 产物结构、版本一致性、DEX manifest 覆盖、debug 签名类型、密钥泄漏检查与 SHA-256 均已核对。
- 容器 manifest 的版本、每个 dex 的哈希与大小、来源 APK 摘要已核对。

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

1. **校准证据仍缺真机验证（不是缺写入通道）**：root CLI `calibrate` 经
   `calibration.record`（仅 CLI_ROOT）写入五项人工测试证据；APK 校准页只写自身
   SharedPreferences，WebUI 不写校准。11E 增加 root 操作员对 bridge 观测不到的
   transport/route 的显式背书（固定词表，服务端构建其余环境与时间戳）。当前没有真机
   执行过任何一项人工测试，也没有设备可做 operator attestation，因此生产就绪**有意保持
   NOT_READY（`calibration_not_verified`）**。必须继续 fail closed，不能在本机记录层面
   伪造授权。
2. **APK 只有 debug 签名**：不可分发；需发布密钥重签并重生成 `SHA256SUMS`。
3. **交付时没有连接设备**：`adb devices` 为空。此前实现期间曾只读观察到一台
   XQ-DQ72（API 37），但 `adb shell` 是 uid 2000，`su` 不可用，所以**没有在设备上运行
   任何东西**；现在设备也已不在线。
4. 因此没有仪器测试、模块安装、Hook 加载、真实音频/通话或付费请求证据；
   `docs/device-validation.md` 的 D01–D12 全部仍为「未验证」。

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
- 没有重跑完整验收矩阵之外的设备检查；本文件中的矩阵结果来自最近一次本地执行
  （HEAD `2d924f4`；11E 在最后两个提交上重跑 core/bridge，app 沿用未受影响的 337）。
  11E 之后尚未重新打包，最终产物哈希必须重跑替换。

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

# AgentCall 最终验证记录

本文件记录 AgentCall 在交付提交上的实际执行结果。只写可复现命令与原始结果；
没有执行的项一律进入「未运行」清单，不用静态阅读或 JVM 测试代替真机结论。

- 记录时间：2026-11-11（Asia/Shanghai）
- 仓库：`/home/krvy/Projects/AgentCall`
- 分支 / 代码 HEAD：`master` / `647a52f`（11P/11Q 设备驱动修复后的代码提交：11P
  chunked exec+env 保存与 `config_validation_unavailable`，11Q 空闲 `hook.presence` 与
  `hook_transport` 机器码，11Q-b 默认拨号器/框架版本/SIM 运营商设备探针；docs 同步提交在
  它之后）
- 最终验收矩阵、`make test`、`make build`、`make package-check` 都在这个代码 HEAD 上执行；
  `dist/` 也在该 HEAD 上重新构建（哈希见 4.5），不是旧构建的遗留。
- **产物哈希绑定代码 HEAD `647a52f`**：APK 内嵌构建元数据、打包过程不是逐字节可复现，
  在任何更晚的提交（包括本次 docs 提交之后）重建都会改变 APK/ZIP 字节；那时必须重新生成
  `SHA256SUMS` 并以新文件为准。
- 设备侧不是零证据、但也**不是完整验收**：控制器通过 adb 在 XQ-DQ72/API 37 上观测到
  Vector 注入（4/4 Hook）与一次真实 `RINGING`，并用 root shell 确认默认拨号器与
  `package_provider=appglobals`。新 ZIP/APK 尚未安装、未重启，逐行设备验收（spec §12）
  仍未完成；这些观测在 5.4 单独列出，**不改变任何一行「未验证」**。
- 远程仓库：`https://github.com/KrvyFT/AgentCall`（public，默认分支 `master`）；文档中不含任何令牌或凭据。
- 验收执行者：控制器（controller）；本文件在最终 docs 同步时重新读取 JUnit XML 与产物
  哈希，重跑 WebUI node / Python 测试与 `make package-check`，并核对签名与工具链版本。
- 相关文档：安装与回退见 `docs/install.md`，真机矩阵见 `docs/device-validation.md`；
  架构、安全、排障与路线图见 `docs/architecture.md`、`docs/security.md`、
  `docs/troubleshooting.md`、`docs/roadmap.md`、`docs/faq.md`。

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

最终矩阵在代码 HEAD `647a52f` 上完整重跑（`--rerun-tasks`，没有复用更早的结果）：

```sh
flock /tmp/agentcall-gradle.lock ./gradlew \
  :core:testDebugUnitTest :bridge:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug :app:assembleRelease :bridge:packageRuntime --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，共执行 **225 个 Gradle 任务**。随后在同一 HEAD 上 `make test` /
`make build` / `make package-check` 全部 PASS。

| 验证面 | 实际结果 |
| --- | --- |
| `:core:testDebugUnitTest` | 127 tests，0 failures / 0 errors / 0 skipped |
| `:bridge:testDebugUnitTest` | 429 tests，0 failures / 0 errors / 0 skipped |
| `:app:testDebugUnitTest` | 366 tests，0 failures / 0 errors / 0 skipped |
| 合计 JVM | **922 tests，全部通过**（0 failures / 0 errors / 0 skipped） |
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

| 模块 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| `:core` | 127 | 0 | 0 | 0 |
| `:bridge` | 429 | 0 | 0 | 0 |
| `:app` | 366 | 0 | 0 | 0 |
| 合计 | **922** | 0 | 0 | 0 |

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

- JVM 单测：同上 922 项（代码 HEAD `647a52f`，完整重跑）。
- WebUI node 测试：`node --test module/webroot/tests/*.test.mjs`，149 tests / 149 pass / 0 fail / 0 skipped（含 chunked env 分块/截断/cap、env 探测、密钥卫生、KernelSU source 安装回归与 stop.lock 行为）。
- Python 打包测试：`python3 -m unittest discover -s tools/tests -v`，59 tests，OK。
- 模块脚本语法：`sh -n` 检查 `customize.sh`、`service.sh`、`uninstall.sh`、`bin/agentcallctl`。
- `make package-check` 重跑：`Status: PASS`（结构、版本、签名、SHA-256 见 4.5）。

### 4.5 产物、哈希与签名

`dist/` 已被 `.gitignore` 忽略，不提交；本次交付产物：

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/agentcall-1.0.zip` | 1,426,689 B（11 个条目） | `65902224bf5a65e8d0c7f2a045c8081e17c31832be0c634504f0254329577577` |
| `dist/agentcall-1.0.apk` | 2,274,772 B | `58f0a83c2247ed23ccea54264fbd0bc90aac3b2075d757d5c5f8cb5cc46876ca` |
| `dist/SHA256SUMS` | 168 B | 两行，分别匹配上面两个摘要 |

产物在代码 HEAD `647a52f` 上重新构建：11P 改了 `module/webroot/bridge.js` 与 WebUI 测试，
11Q 改了 `app/`（Hook 空闲 presence 与传输诊断）与 `bridge/`（按角色 presence、默认拨号器
兜底），因此 APK、bridge 容器与模块 ZIP 都换成了这次构建的字节（APK 内嵌构建元数据、
打包不是逐字节可复现，不能沿用旧摘要）。`dist/SHA256SUMS` 与两个产物一致，
`sha256sum -c` 通过。

**哈希只对应这次构建**：在任何更晚的提交重建（即使源码不变）都会改变 APK/ZIP 字节，
必须重新生成 `SHA256SUMS` 并以新文件为准。重签 APK 同样会改变摘要。

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
  来源 bridge APK 摘要与 `bridgeVersion`；本次（`647a52f`）容器内为 `classes.dex`。
  各 dex 的精确哈希/大小与来源 bridge APK 摘要以本次 `dist/agentcall-1.0.zip` 内的
  `bridge/agentcall-bridge.dex-manifest.json` 为准——它们随每次构建变化，本文不再固化
  旧构建的数值。
- 模块 `module.prop` 为 `1.0` / `1`，与 APK 版本一致；不一致时打包直接失败。
- DEX 覆盖只用容器 manifest 校验，不用 app APK（两者是不同产物）。

### 4.7 R8 收缩（release）

- `:app` release 启用 R8：`optimization { enable = true }`，并显式
  `packageScope = setOf("**")`，让 app 自身代码（不只是 androidx/kotlin）真的进入收缩/改名。
- 本次 `app/build/outputs/mapping/release/mapping.txt` 仍约 **99k 行**（99,000 行量级）。
- release 未签名 APK 约 **2.18 MB**；交付的 debug 重签 APK 为 2,274,772 B（2.27 MB）。
  以上 mapping 行数量级与 release 体积在 `647a52f` 的构建中重新核对，未经真机验证。
- R8 是本地构建行为；**没有在真机上验证** release 构建的运行时行为（见第 5.2 节）。

## 5. 本地已验证 / 设备未验证 / 阻塞项

### 5.1 本地已验证（宿主环境）

- 最终矩阵：BUILD SUCCESSFUL（代码 HEAD `647a52f`，225 个 Gradle 任务，`--rerun-tasks` 完整重跑）。
- JVM 单测：core 127 / bridge 429 / app 366，合计 922，0 failures / 0 errors / 0 skipped。
- Lint：`:app:lintDebug` SUCCESSFUL，0 errors、15 warnings（全部 warning）。
- R8：release 收缩启用（`packageScope = setOf("**")`），mapping 仍约 99k 行；release 未签名
  APK 约 2.18 MB，debug 重签交付 APK 2.27 MB（见 4.7）。
- Release APK 与 bridge 运行时容器均构建成功。
- `make test` PASS：JVM（922）+ WebUI node（149）+ Python（59）+ 模块脚本 `sh -n`。
- **本轮修复的本地证据**（全部是宿主/替身测试，不是设备结论）：
  - 桥接分层 provider：`PackageProviderChainTest` 覆盖“context 先试、失败逐层、
    binder 全失败才退 shell、每进程只解析一次、每层失败写
    `package_provider_attempt=<code> result=unavailable`、成功写
    `package_provider=<code>`、全失败写 `package_provider_unavailable candidates=...`”；
    `ShellPackageFactsSourceTest`/`PackageShellParsingTest` 覆盖 shell 输出解析与
    超时/非零退出即“没有事实”；`PeerVerifierShellFallbackTest` 覆盖 shell 路径
    `signatureSha256` 恒为 null（签名 unsupported）时对非 root 对端 fail closed；
    `DeviceProbeDialerFactsTest` 覆盖 `diagnose.notes` 里的 `packageProvider=` 与
    `DialerDefaultRole` 同一 provider（`dialHandlerPackage`）。
  - WebUI 分块 exec+env 保存（11O/11P）：`transport.test.mjs` 与
    `transport-chunk.test.mjs` 用管理器替身覆盖 `AGENTCALL_CFG_PAYLOAD_0..N` 分块
    （128 UTF-16 码元、代理对安全、最多 64 块）、`payload_too_large`、缺失/截断块 fail
    closed、探测每页会话只执行一次（`AGENTCALL_PROBE_0..2` 重组校验）、exec 忽略 env 或
    抛错时 `env_unavailable` 只读且不执行 `config-set`、存在 ksu.io 文件接口时不探测 env、
    `io.support === false` 但 env 可用时走回退；`app-ui.test.mjs` 覆盖密钥不回显到
    DOM/localStorage/toast、并发保存仍串行化。
  - 反射/校验硬化（11P）：`ConfigWriteReflectionHardeningTest` 覆盖隐藏 API 反射抛
    `Throwable`（含 `Error`）时 `config-set` 仍不逃逸、失败固定为
    `config_validation_unavailable` 而不是 `internal_error`，且 status 不因此失败。
  - HOOK 空闲 presence（11Q）：`HookPresenceStatusTest` 覆盖无会话的
    `hook.presence` 在 5000 ms 窗口内把 `status.hookConnected` 从 false/unknown 提升为
    true、过期后回落；`SupervisorTest` 覆盖 presence 不改变监督所有权/阶段；
    `HookTransportDiagnosticsTest` 覆盖固定机器码
    `hook_transport connect_failed|server_untrusted|protocol_violation failures=<n>`
    的计数与 5 s 限流（不输出负载/会话号）。
  - 设备探针（11Q-b）：`DeviceProbeModuleAndCarrierFactsTest` 覆盖
    `/data/adb/modules/{zygisk_vector,zygisk_lsposed,lsposed,vector,edxposed}/module.prop`
    的 `version=` 与逗号列表首段（`China Unicom,China Unicom` -> `China Unicom`）；
    `ShellDialerDefaultRoleProbeTest` 覆盖 `cmd role get-role-holders` ->
    `cmd telecom get-default-dialer` 的顺序、2 s 超时、8 KiB 上限、精确单一包名与 30 s
    缓存；`DialerShellRoleTest` 覆盖两段 root shell 输出与 `ACTION_DIAL` 负证据的优先级。
  - `service.sh` 停止锁（11N，真实 shell + 真实 `flock`）：每个 `app_process` 启动点
    都带 `9>&-`（锁 fd 不继承）；桥接已退出后的清理只做一次 `flock -n`，在锁被他人
    持有时仍 <35 s 结束并记录 `! stop_lock_busy` 与
    `bridge_shutdown_skipped reason=stop_lock_unavailable`，不再出现
    `! stop_lock_timeout waited=40s`；桥接仍存活时仍在 `stop.lock` 上等待持有者释放
    （`stop_lock_wait` → `stop_lock_acquired`）后才发 `shutdown`；
    `acquire_stop_lock`/`acquire_stop_lock_cleanup` 都在本进程已持锁时记录
    `stop_lock_reentrant` 而不重复申请。
- `make build` PASS；`make package-check` PASS（appId `org.krvyft.agentcall`，版本 1.0 (1)，
  `module.prop` 1.0 (1)，dex `classes.dex`，结构 module=1/scripts=3/bridge=2/bin=1/webroot=4）。
- 产物结构、版本一致性、DEX manifest 覆盖、debug 签名类型、密钥泄漏检查与 SHA-256 均已核对。
- 容器 manifest 的版本、每个 dex 的哈希与大小、来源 APK 摘要已核对。
- `status`/`diagnose`/`shutdown` 的字段集合与关闭语义未变；11Q 只改变 `hookConnected` 的
  派生（字段本身不变），11Q-b 增加默认拨号器探测来源。`status` 仍暴露 **18 个顶层键**
  （17 个标量/布尔 + `calibration` 对象），含 `shutdownCode`；`diagnose` 携带同一份校准
  视图。监督状态 schema 为 3。
- 环境观测：`frameworkVersion` 依次读 root SystemProperties
  （`ro.lsposed.version`/`persist.sys.lsposed.version`/`ro.vector.version`/
  `persist.sys.vector.version`/`ro.edxposed.version`/`persist.sys.edxposed.version`），再读
  `/data/adb/modules/{zygisk_vector,zygisk_lsposed,lsposed,vector,edxposed}/module.prop` 的
  `version=`（本设备是 `zygisk_vector`，值 `v2.2 (3080-88f8e1fa-JingMatrix-Vector)`），最后做
  XposedBridge 类检查（`getXposedVersion()` 的 int/long/String 返回值；整数且为正、long
  不超过 `Int.MAX_VALUE`，Double/NaN/负数/零/溢出保持 UNKNOWN）；`simCarrier` 读
  TelephonyManager SIM 运营商名，失败后回退
  `gsm.sim.operator.alpha`/`gsm.sim.operator.default-name`/`ro.cdma.home.operator.alpha`；
  逗号分隔列表只取第一个非空片段并去掉首尾标点（本设备 `China Unicom,China Unicom` ->
  `China Unicom`）。
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

### 5.2 设备部分验证与仍未验证

**已取得的部分观测（不是整行验收）**：Vector v2.2（legacy Xposed API 82）已把 AgentCall
注入 `com.google.android.dialer`，日志显示 `installed 4 of 4 hook(s)`，并观测到一次真实
`state=RINGING`；root shell 确认默认拨号器是 `com.google.android.dialer`，桥接启动日志为
`package_provider=appglobals`。这些观测**不翻转 D01–D12 的任何一行**：新 ZIP/APK 尚未
安装、未重启，完整通话、音频、校准与自动接听都没有证据。

仍未验证（按行/按能力）：

1. Hook 加载后的完整回调链（只观测到注入与一次 RINGING；未归档 onCallAdded 到音频的完整路径）。
2. Google 电话 `InCallService` 的其余回调与多通话行为。
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
13. 分层 PackageManager provider 的其余分支：设备上已观测 `package_provider=appglobals`
    落点，但 binder/shell 路径、失败回落顺序与真实权限仍只有宿主测试证据。
14. WebUI 的 chunked exec+env 保存通道（`AGENTCALL_CFG_PAYLOAD_0..N`、
    `AGENTCALL_PROBE_0..2`）在真实 KernelSU WebView 中是否透传 `options.env`：探测通过后
    才成立；设备上的管理器只提供函数形式 `ksu.io`，没有 `File`/`FileOutputStream`。
15. 空闲 `hook.presence` 在设备上把 `status.hookConnected` 保持为 true（修复前一次真实
    RINGING 时该值为 false，原因是旧实现只按会话心跳；新构建待重启复测）。
16. 设备复测的校准/角色预期值：`dialerDefaultRole=true`（boolean）、
    `frameworkVersion=v2.2 (3080-88f8e1fa-JingMatrix-Vector)`、
    `simCarrier=China Unicom`；均尚未在新构建上确认。

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
3. **设备验收仍未完成**：交付时 `adb devices` 为空；本轮修复期间控制器通过 adb
   （含 root shell）在 XQ-DQ72/API 37 上取得注入、4/4 Hook、一次 RINGING、
   `package_provider=appglobals` 与默认拨号器的**部分观测**（见 5.4），但没有安装本轮
   新构建、没有重启、没有跑 D01–D12 的任何整行验收。
4. 因此没有仪器测试、最终构建安装、完整 Hook 通话、真实音频/麦克风隔离或付费请求证据；
   `docs/device-validation.md` 的 D01–D12 全部仍为「未验证」；校准环境与四项背书只有
   宿主/JVM 证据。11P 的 chunked env、11Q 的空闲 presence 与 11Q-b 的探针同样只有
   宿主/替身测试，设备复测待做。

### 5.4 设备观测（部分证据，不是完整验收）

以下由控制器通过 adb 在 XQ-DQ72 / API 37 上取得，属于只读观测与一次真实来电的日志证据，
**不是** D01–D12 的任何整行验收：

1. **注入成立**：Vector v2.2（legacy Xposed API 82）把 AgentCall 注入
   `com.google.android.dialer`，logcat 可见：
   `VectorLegacyBridge: Loading legacy module org.krvyft.agentcall`、
   `Loading class org.krvyft.agentcall.hook.HookEntry`、
   `AgentCall: installed 4 of 4 hook(s): InCallServiceImpl#onCallAdded,onCallRemoved,onCallAudioStateChanged,InCallService#onCallEndpointChanged`。
2. **真实来电**：拨号器日志出现
   `AgentCall: observed callId=... state=RINGING direction=INCOMING`。这证明 HOOK 至少收到了
   一次真实 RINGING；没有自动接听、没有音频/对话证据。
3. **当时 hookConnected=false 的原因**：旧实现只在有存活会话时发心跳，空闲 Hook 对桥接
   静默，所以 RINGING 观测时 `status.hookConnected` 仍为 false。11Q 用固定方法
   `hook.presence`（每 2 s、无会话、空负载）修复；这是修复动机，不是 11Q 的设备验收。
4. **默认拨号器**：root shell 的
   `cmd role get-role-holders android.app.role.DIALER` / `cmd telecom get-default-dialer`
   确认默认拨号器是 `com.google.android.dialer`。
5. **桥接落点**：桥接启动日志为 `package_provider=appglobals`（11N 分层 provider 在
   这台 ROM 上的实际落点）。

仍未在设备上确认：安装本轮新 ZIP/APK、重启、空闲 `hookConnected=true`、
`dialerDefaultRole=true`、`frameworkVersion`/`simCarrier` 的校准读回、chunked env 的
规则/AI 保存，以及任何音频、Telecom 终止或自动接听行为。历史上两处失败（管理器只有函数
形式 `ksu.io`；`app_process` 曾以 `package_manager_unavailable` 退出）仍是 11O/11N 的
动机证据，本文不把它们当作新修复的验证。

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

- 没有运行任何仪器测试（instrumented tests）；没有在设备上安装**本轮**的 APK 或
  KernelSU 模块，也没有重启复测。
- 没有做 Hook 的完整验收：只观测到 Vector 注入（4/4 Hook）与一次真实 RINGING，没有归档
  onCallAdded 到音频的完整链路。
- 没有完成真实通话、真实音频、麦克风隔离、重采样或对端听音验证（仅有一次 RINGING 日志）。
- 没有安装/卸载本轮 KernelSU 模块，未验证 WebView、`service.sh` 重启或卸载时序。
- 没有锁屏、Doze、重启、通知、进程死亡或 FGS 行为验证。
- 没有发起任何真实付费 API 请求（ASR/LLM/TTS）。
- 没有写入任何校准证据；没有启用自动接听。
- 没有用发布密钥重签 APK。
- 没有重跑完整验收矩阵之外的设备检查；本文件中的矩阵结果来自代码 HEAD `647a52f` 上的
  一次完整本地执行（`--rerun-tasks`，225 个 Gradle 任务），不是旧提交结果的拼接。
- 没有在设备上确认空闲 `hook.presence` 让 `hookConnected` 保持 true，也没有确认
  `dialerDefaultRole` 的 boolean 形态与校准环境字段；修复前的一次 RINGING 观测中
  `hookConnected=false`。
- 没有在真机 KernelSU WebView 中确认 chunked exec+env 探测与保存（只剩宿主
  `fake-manager` 证据）。
- 没有在真机确认 `stop.lock` 的 fd 不被 `app_process` 子进程继承（只有宿主真实
  `flock` + 替身证据），也没有确认设备上不会出现 40 s 的 `stop_lock_timeout`。
- 设备侧只完成了 5.4 的只读观测与一次 RINGING；D01–D12、最终构建安装、完整通话、真实
  音频与真实付费 API 均未执行。

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

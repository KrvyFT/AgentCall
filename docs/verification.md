# AgentCall 验证记录

本文件记录实际执行过的检查与真实结果。未执行的项明确标注“未验证”，不由静态阅读代替构建通过。

- 记录时间：2026-11-10（Asia/Shanghai）
- 工作目录：`/home/krvy/Projects/AgentCall`
- 对应任务：任务 1 — 延续模板，建立 Kotlin 构建与契约

## 1. 工具链事实

```console
$ ./gradlew --version

------------------------------------------------------------
Gradle 9.6.0
------------------------------------------------------------

Build time:    2026-06-18 23:19:03 UTC
Revision:      3f750f03d77e42327c5f9fcb9992110088330a32

Kotlin:        2.3.21
Groovy:        4.0.32
Ant:           Apache Ant(TM) version 1.10.17 compiled on April 6 2026
Launcher JVM:  17.0.21.0.3 (Arch Linux 17.0.21.0.3+1)
Daemon JVM:    Compatible with Java 25, any vendor, nativeImageCapable=false (from gradle/gradle-daemon-jvm.properties)
OS:            Linux 7.3.3-x64v3-xanmod1-1 amd64
```

说明：

- `Kotlin: 2.3.21` 是 Gradle 内置用于构建脚本的 Kotlin 版本，不是 Android 模块的编译器版本。
- `Daemon JVM: Compatible with Java 25` 来自 `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=25`）。本任务未修改 Wrapper、Daemon 配置或仓库声明。
- `./gradlew -q javaToolchains` 显示可用 JDK：Arch Linux JDK 17（`/usr/lib/jvm/java-17-openjdk`）、Arch Linux JDK 21、JetBrains JDK 25（`/opt/android-studio/jbr`，Detected by: Current JVM）。

Android 构建事实：

| 项目 | 值 | 依据 |
| --- | --- | --- |
| AGP | 9.5.1（内置 Kotlin，未应用 kotlin-android） | `gradle/libs.versions.toml`、`:core` 与 `:app` 的 plugins 块 |
| compileSdk | 37 | `:app`、`:core` 的 `compileSdk { version = release(37) }` |
| minSdk | 36 | 同上 `defaultConfig` |
| Java 源/目标 | VERSION_11 | 两个模块的 `compileOptions` |
| Kotlin jvmTarget | JVM_11 | 两个模块的 `kotlin { compilerOptions { jvmTarget } }` |
| Kotlin stdlib（core 运行类路径解析结果） | 2.2.10 | `:core:dependencies --configuration debugRuntimeClasspath` |

### 字节码目标验证（不是假设）

```console
$ javap -v core/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/org/krvyft/agentcall/core/model/SessionId.class | grep -m1 'major version'
  major version: 55

$ javap -v app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/org/krvyft/agentcall/ExampleUnitTest.class | grep -m1 'major version'
  major version: 55
```

major version 55 = Java 11 字节码，与 `compileOptions` 的 `VERSION_11` 一致。`:app` 当前没有主源码 Kotlin，因此用其单元测试编译产物验证 jvmTarget 生效。

## 2. 依赖版本（固定，无动态版本）

版本目录 `gradle/libs.versions.toml` 新增/使用的固定版本：

| alias | 坐标 | 版本 | 模块内是否已使用 |
| --- | --- | --- | --- |
| `libs.plugins.android.library` | `com.android.library` | 9.5.1（与 android-application 同一 agp） | `:core` |
| `libs.kotlinx.coroutines.core` | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 | `:core` `implementation`（`AudioSession.downlink: Flow`） |
| `libs.kotlinx.coroutines.test` | `kotlinx-coroutines-test` | 1.9.0 | 否（任务 2 起的虚拟时间测试用） |
| `libs.libphonenumber` | `com.googlecode.libphonenumber:libphonenumber` | 9.0.43 | 否（任务 2 号码规范化） |
| `libs.gson` | `com.google.code.gson:gson` | 2.11.0 | 否（任务 3 协议/配置编码） |
| `libs.okhttp` | `com.squareup.okhttp3:okhttp` | 4.12.0 | 否（任务 6 才加 usage） |
| `libs.okhttp.mockwebserver` | `com.squareup.okhttp3:mockwebserver` | 4.12.0 | 否（任务 6 测试） |

选取依据：

- libphonenumber：`~/.gradle/caches/modules-2/files-2.1/com.googlecode.libphonenumber/libphonenumber` 中只有 9.0.43，故固定为 9.0.43，并在下面实测可解析。
- gson：2.11.0 已在本地缓存，按控制器决定优先 2.11.0。
- coroutines：1.9.0 已在本地缓存，按控制器决定优先 1.9.0。

### 真实解析结果

`:core` 实际运行类路径（`./gradlew :core:dependencies --configuration debugRuntimeClasspath`）：

```text
+--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10
\--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
     \--- org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
          +--- org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0
          \--- org.jetbrains.kotlin:kotlin-stdlib:2.0.0 -> 2.2.10
```

版本目录中尚未被任何模块引用的坐标，用与工程相同仓库声明（`google()` + `mavenCentral()` + 机器级镜像 init 脚本）的临时探针工程解析，确认可获取：

```console
$ ./gradlew -p /tmp/agentcall-depcheck resolveDeps --no-configuration-cache
> Task :resolveDeps
RESOLVED gson-2.11.0.jar
RESOLVED libphonenumber-9.0.43.jar
RESOLVED mockwebserver-4.12.0.jar
RESOLVED okhttp-4.12.0.jar
RESOLVED error_prone_annotations-2.27.0.jar
RESOLVED kotlinx-coroutines-test-jvm-1.9.0.jar
RESOLVED okio-jvm-3.6.0.jar
RESOLVED kotlinx-coroutines-core-jvm-1.9.0.jar
RESOLVED kotlin-stdlib-jdk7-1.9.10.jar
RESOLVED kotlin-stdlib-2.0.0.jar
RESOLVED kotlin-stdlib-jdk8-1.9.10.jar
RESOLVED junit-4.13.2.jar
RESOLVED annotations-23.0.0.jar
RESOLVED hamcrest-core-1.3.jar

BUILD SUCCESSFUL in 19s
```

探针工程是 `/tmp` 下的一次性验证目录，不进入仓库；其 `kotlin-stdlib 2.0.0` 只是该探针的传递解析结果，实际 `:core` 由 AGP 内置 Kotlin 升为 2.2.10（见上）。

## 3. 命令与结果

### 3.1 改动前基线

```console
$ ./gradlew :app:assembleDebug :app:testDebugUnitTest
...
BUILD SUCCESSFUL in 730ms
39 actionable tasks: 39 up-to-date
Configuration cache entry reused.
```

说明：该次运行复用控制器先前已生成的构建产物（全部 UP-TO-DATE），只证明模板基线可构建可测，不构成功能证明；`:app` 当时 `compileDebugKotlin` 为 `NO-SOURCE`（无主源码 Kotlin），只有模板 `ExampleUnitTest`（2+2）在跑。

### 3.2 TDD 红灯（契约测试先失败）

```console
$ ./gradlew :core:testDebugUnitTest --tests '*ContractTest'
> Task :core:compileDebugUnitTestKotlin FAILED
e: .../ContractTest.kt:76:45 Unresolved reference 'PeerRole'.
e: .../ContractTest.kt:101:13 Unresolved reference 'SessionState'.
e: .../ContractTest.kt:107:22 Unresolved reference 'AgentConfig'.
e: .../ContractTest.kt:125:22 Unresolved reference 'AudioFormatSpec'.
e: .../ContractTest.kt:130:13 Unresolved reference 'PcmChunk'.
e: .../ContractTest.kt:145:26 Unresolved reference 'SessionEvent'.
...
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':core:compileDebugUnitTestKotlin' (registered by plugin 'com.android.internal.library').
> A failure occurred while executing ... GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details
BUILD FAILED in 10s
```

失败原因符合预期：契约类型尚不存在，编译失败即“缺少类型时的红灯”。（首轮输出省略号处是同一批 `Unresolved reference`，退出码 1。）

### 3.3 TDD 绿灯

补齐 `C/model` 与 `C/port` 后：

```console
$ ./gradlew :core:testDebugUnitTest --tests '*ContractTest'
> Task :core:testDebugUnitTest

BUILD SUCCESSFUL in 1s
```

补齐契约后曾出现一次测试自身调用缺参（`snapshot(number = null)` 未传必填的 `processEpoch`/`callId`），修正该测试笔误后即通过；那次失败属于测试写法问题，不是契约实现缺失，未计入红灯证据。

测试报告 `core/build/test-results/testDebugUnitTest/TEST-org.krvyft.agentcall.core.model.ContractTest.xml`：

```text
tests= 11 failures= 0 errors= 0 skipped= 0
- sameNumberDifferentCallIdsAreDistinctSessions
- sameCallIdInAnotherProcessEpochIsDistinctSession
- snapshotCarriesTelecomFacts
- secretUpdateKeepAndClearAreDistinct
- protocolVersionDefaultsToOne
- peerRolesCoverThreeDevices
- stateChangedEventCarriesTheCallSnapshot
- sessionStatesFollowSpecPath
- agentConfigDefaultsKeepAutomationOff
- pcmContractsDeclarePcm16AndCompareByContent
- sealTypesExposeTheDocumentedEvents
```

### 3.4 app 引用 core

```console
$ ./gradlew :app:assembleDebug :core:testDebugUnitTest
...
> Task :app:mergeLibDexDebug
> Task :app:packageDebug
> Task :app:assembleDebug
> Task :core:testDebugUnitTest

BUILD SUCCESSFUL in 17s
57 actionable tasks: 42 executed, 15 up-to-date
```

额外确认 `:core` 实际进入 APK（不是只加依赖声明）：

```console
$ unzip -p app/build/outputs/apk/debug/app-debug.apk classes2.dex | strings | grep -m3 'agentcall/core/model'
/()Lorg/krvyft/agentcall/core/model/AgentConfig;
/()Lorg/krvyft/agentcall/core/model/ApiEndpoint;
3()Lorg/krvyft/agentcall/core/model/AudioFormatSpec;
```

### 3.5 提交前完整验证

按要求执行一次完整命令（结果通过，但 `:core:testDebugUnitTest` 因缓存为 UP-TO-DATE）：

```console
$ ./gradlew :app:assembleDebug :app:testDebugUnitTest :core:testDebugUnitTest
...
> Task :app:testDebugUnitTest
> Task :core:testDebugUnitTest

BUILD SUCCESSFUL in 946ms
64 actionable tasks: 6 executed, 58 up-to-date
Configuration cache entry reused.
GRADLE_EXIT:0
```

为避免“UP-TO-DATE 不等于测试真的跑过”，再强制执行全部任务：

```console
$ ./gradlew :app:assembleDebug :app:testDebugUnitTest :core:testDebugUnitTest --rerun-tasks
...
> Task :app:packageDebug
> Task :app:assembleDebug
> Task :core:testDebugUnitTest
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 2s
64 actionable tasks: 64 executed
Configuration cache entry reused.
GRADLE_EXIT:0
```

强制重跑后的报告：`:app` `ExampleUnitTest` tests=1 failures=0；`:core` `ContractTest` tests=11 failures=0。

`:app` 的 `ExampleUnitTest`（2+2）通过，但按计划不计入功能证明；功能证明是 `:core` 的 ContractTest。

## 4. 验证了与未验证的边界

已验证（本机、宿主 JVM）：

- Gradle 9.6.0 / Launcher JVM 17 / Daemon 需要 Java 25 的实际输出。
- `:core` Android library 与 `:app` 在 AGP 9.5.1 内置 Kotlin 下可配置、可编译、可打包，`:core` 类进入 APK dex。
- Kotlin 字节码目标为 Java 11（major version 55），app 与 core 一致。
- 版本目录中全部新坐标均可从本机镜像解析（含未被模块引用的 gson/libphonenumber/coroutines-test/okhttp/mockwebserver）。
- ContractTest 11 项断言通过：会话身份不含号码、进程代次、SecretUpdate 三态、协议版本 1、事件携带通话快照、PCM16 单声道与内容相等、默认配置关闭自动接管。

未验证 / 有意不做：

- 无真机：没有任何仪器测试或真实通话、音频、Hook、KernelSU 验证；本任务也不产生这些行为。
- 未运行 `assembleRelease` 与设备安装；任务 1 的收尾命令不含它们。
- 未验证 okhttp/mockwebserver 在模块中的实际使用：按控制器决定只固定版本目录，任务 6 才有 usage。
- 未添加 Xposed 仓库：本任务不引入 Xposed 依赖，按“不添加未使用配置/不新增工程级仓库”的约束，留到任务 4 引入 `compileOnly` 时再于 `settings.gradle.kts` 以精确 group 允许；任务 4 的 Modify 列表未列出 `settings.gradle.kts`，届时需要一并处理（见任务 1 报告“问题/风险”）。
- 镜像可达性依赖机器级 `~/.gradle/init.d/agentcall-mirrors.gradle.kts`；本任务未修改该文件，也未新增工程级仓库。

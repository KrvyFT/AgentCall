# AgentCall Kotlin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or, if the user chooses delegation, superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Android Studio 工程中，以 Kotlin 实现 Google 电话 Hook、远程 AI 接管、KernelSU 音频服务和中文 WebUI。

**Architecture:** 保留已有 `:app`，新增 `:core`（Android library，纯 Kotlin 业务逻辑）和 `:bridge`（用于 DEX 打包的 Android application）。Hook 与 APK 会话服务处于不同进程，通过特权桥接传递受限命令；真机校准决定能否自动接听。

**Tech Stack:** Kotlin、AGP 内置 Kotlin、Kotlin DSL、协程/Flow、现有 AGP 9.5.1 / Gradle 9.6.0；Daemon JDK 25、Java/Kotlin 字节码目标 11；compileSdk/targetSdk 37、minSdk 36；AppCompat/Material Views、传统 Xposed API 82、JUnit 4、HTML/CSS/JS、shell、Python。

**Spec:** [Kotlin 设计与验收要求](../specs/2026-11-10-agentcall-design.md)。本计划以实际工程文件为基线，未勾选步骤均尚未完成。

## Global Constraints

- “Android 应用、Hook、共享逻辑与特权桥接均使用 Kotlin；构建脚本使用 Kotlin DSL。”
- “配套 APK 的 applicationId / namespace 为 `org.krvyft.agentcall`。”
- “保留 Google 电话 `com.google.android.dialer`，通过运行时 Hook 接入，不开发替代拨号器。”
- “首版处理 Telecom 管理的蜂窝入站电话，手机直接请求远程 ASR、LLM、TTS，不依赖常驻电脑或自建中转服务器。”
- 当前 `minSdk=36` 保留；首版仅验证已知 API 37 Xperia ROM，不暗示 API 36 已可接管。
- 不降级现有 AGP/Wrapper，不重复添加 kotlin-android，不改成 Groovy；所有依赖和插件 alias 进入现有版本目录。
- Daemon JDK 25 与字节码目标 11 分离，`JAVA_HOME=17` 不等于覆盖 Daemon criteria。所有实际使用版本以任务 1 输出记录。
- 默认关闭总开关；接听 3000 ms、最长通话 180000 ms、心跳超时 5000 ms、故障人工接管窗口 10000 ms。
- 不重签 Google 电话、不注入 system_server、不关闭 SELinux、不在空闲时录音、不自动刷第三方框架或重启。
- 所有生产实现与测试替身分开；没有设备证据不能解锁音频或宣称完成真实通话。
- 当前没有可用 Git 仓库。不要删除/改写 `.git`；步骤记录到验证文档，有可用仓库再提交。

## Review Focus

1. 父/子类重复回调、进程代次变化、用户先接听：最多一次接听且不控制另一通电话（任务 2、4）。
2. JDK 25 下载/SDK 缺失、内置 Kotlin、release 收缩和 Kotlin stdlib：区分配置事实与构建通过，最终 DEX 可运行（任务 1、4、9）。
3. UI 密钥传输、伪造 socket 身份、配置并发覆盖和备份：不能泄漏密钥或提权（任务 3、7、8）。
4. 坏 WAV、跨域重定向、巨型响应、协程取消后迟到结果：不重复播放、不泄漏认证、不继续旧会话（任务 6、7）。
5. 锁屏服务未 READY、路由变更、Hook/APK 崩溃与人工接管竞态：保留原响铃或按租约恢复，不错误挂断（任务 5、7、9）。

## Current baseline and file conventions

已存在：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、Wrapper、Daemon 配置、`app/build.gradle.kts`、Manifest、res、`app/src/main/keepRules/rules.keep`、两个 Kotlin 模板测试。

尚不存在：业务 Kotlin 类、core、bridge、module、tools、打包任务、音频校准记录。

下文文件缩写是**精确路径前缀**，实施时按表展开；`{A,B}.kt` 明确表示同目录下 A.kt 与 B.kt，不代表占位文件。

| 前缀 | 路径 |
| --- | --- |
| `A` | `app/src/main/java/org/krvyft/agentcall` |
| `AT` | `app/src/test/java/org/krvyft/agentcall` |
| `AI` | `app/src/androidTest/java/org/krvyft/agentcall` |
| `C` | `core/src/main/java/org/krvyft/agentcall/core` |
| `CT` | `core/src/test/java/org/krvyft/agentcall/core` |
| `B` | `bridge/src/main/java/org/krvyft/agentcall/bridge` |
| `BT` | `bridge/src/test/java/org/krvyft/agentcall/bridge` |
| `BI` | `bridge/src/androidTest/java/org/krvyft/agentcall/bridge` |

依赖方向固定 `app → core ← bridge`。Kotlin 文件保留在 `src/*/java/`，不为改语言搬迁现有资源或测试。生产包使用 `org.krvyft.agentcall.*`；Google 电话包名只作为目标常量，不用作自身 namespace。

## Shared Kotlin contracts

任务 1 建立以下类型；其行为由后续任务填充，类型不得重复定义在不同模块：

- `C/model/CallModels.kt`：`SessionId(processEpoch: String, callId: String)`、`CallSnapshot`、`CallState`、`Presentation`、`CallCommand`、`CommandResult`。快照含 direction、phoneAccount、紧急/会议标志与 activeCallCount。
- `C/model/ConfigModels.kt`：`AgentConfig`、`ConfigPatch`、`RuleMode`、`ApiEndpoint`、`SecretUpdate`（Keep/Replace/Clear）、`RedactedConfig`。公开配置不携带密钥原值。
- `C/model/SessionModels.kt`：`SessionEvent`、`SessionEffect`、`SessionState`；定义 StateChanged、Tick、ReadyChanged、Takeover、PeerLost 等事件。
- `C/model/ProtocolModels.kt`：`PeerRole`（APP/HOOK/CLI_ROOT）、`PeerIdentity`、`RpcRequest`、`RpcResponse`、`RpcError`；协议版本固定 1。
- `C/model/CapabilityModels.kt`：`EnvironmentKey`、`CapabilityReport`、`CalibrationEvidence`；包含 spec 要求的所有环境绑定字段。
- `C/model/AudioModels.kt`：`AudioFormatSpec`、`PcmChunk`、`PcmAudio`；编码固定 PCM16，声明 sampleRate/channels。
- `C/port/CallControl.kt`：`suspend fun send(id: SessionId, command: CallCommand): CommandResult`。
- `C/port/AudioBridge.kt`：`suspend fun open(id: SessionId, format: AudioFormatSpec): AudioSession`；session 的 `downlink: Flow<PcmChunk>`、`suspend fun writeUplink(audio: PcmAudio)`、`suspend fun stop()`。stop 不自动解除麦克风隔离。
- `C/port/ConversationPort.kt`：`suspend fun run(id: SessionId, audio: AudioSession, config: AgentConfig)`，取消由调用者 Job 传播。
- `C/port/MonotonicClock.kt`：`fun nowMs(): Long`，所有超时从注入时钟计算。

## Task 1 — 延续模板，建立 Kotlin 构建与契约

**Modify:** `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`app/build.gradle.kts`。保留 Wrapper/Daemon 文件。

**Create:** `core/build.gradle.kts`、`core/src/main/AndroidManifest.xml`、上列 C/model 与 C/port 文件、`CT/model/ContractTest.kt`、`docs/verification.md`。

**Interfaces:** 输出共享契约、可由 app 引用的 `:core`；无业务自动接听。

- [ ] 执行 `./gradlew --version`，记录 Launcher/Daemon JVM 和 Gradle；执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest` 建立真实基线。缺依赖/网络失败记录原错，不伪造成功或直接降级。
- [ ] 在版本目录添加 android-library alias（同一 agp）、协程 core/test、libphonenumber、JSON 与后续 HTTP 测试依赖的固定版本。确认依赖兼容内置 Kotlin并记录解析版本；Android 模块不添加 kotlin-android。Xposed 仓库仅在 settings 中允许其确切 group，禁止模块独立仓库。
- [ ] 创建 `:core` Android library 骨架：compileSdk 37/minSdk 36；namespace `org.krvyft.agentcall.core`。app/core 的 Kotlin `compilerOptions.jvmTarget` 与 Java VERSION_11 对齐，不改变 Daemon JDK。
- [ ] 先写契约失败测试，再创建数据类型：断言两个相同号码但不同 callId 的 SessionId 不相等；`SecretUpdate.Keep` 与 Clear 不等；协议默认 version=1。`./gradlew :core:testDebugUnitTest --tests '*ContractTest'` 在缺少类型时应失败，补类型后应通过。
- [ ] 将 `implementation(project(":core"))` 加入现有 app。执行 `./gradlew :app:assembleDebug :core:testDebugUnitTest`，验证 Kotlin 编译和依赖；不将模板加法测试计入功能证明。

## Task 2 — 名单、状态机与租约

**Create:** `C/rules/{NumberNormalizer,RuleMatcher,ConfigValidator}.kt`、`C/session/{SessionReducer,LeaseRegistry}.kt`、`CT/fixtures/Fixtures.kt`、`CT/rules/RuleMatcherTest.kt`、`CT/session/{SessionReducerTest,LeaseRegistryTest}.kt`。

**Interfaces:** `RuleMatcher.matches(config: AgentConfig, call: CallSnapshot): Boolean`；`SessionReducer.reduce(event: SessionEvent, nowMs: Long): List<SessionEffect>`；`LeaseRegistry.revoke(id: SessionId): Boolean`；只输出副作用描述，不直接调用 Android。

- [ ] 创建固定时钟与通话/config fixtures，写失败测试 `emptyBlacklistDoesNotMatch`、`emptyWhitelistMatchesKnownNumber`、`unknownDefaultsToManual`、`normalizedOverlapRejectsConfig`、`secondCallIsManual`，分别断言 false/true/false/验证失败/false。
- [ ] 运行 `./gradlew :core:testDebugUnitTest --tests '*RuleMatcherTest'` 确认红灯，再实现 CN 默认地区的完整号码规范化、三种模式、名单冲突、未知/紧急/外拨/多路排除；延迟/时长范围严格按 spec。
- [ ] 写 `answerOnlyOnceAtDeadline`：使用 `assertEquals(0, answerEffectsAt(2999).size)` 与 `assertEquals(1, answerEffectsAt(3000).size)`；重复 Tick 不增加命令。写 `manualAnswerCancelsPending` 与 `staleEpochIgnored`，断言无 Answer。
- [ ] 实现 reducer 状态与单会话租约；ACTIVE 起计 180000 ms，用户交还后计时不能挂断；人工撤销与超时通过单写者序列裁决。
- [ ] 运行 `./gradlew :core:testDebugUnitTest`；检查 5000 ms 失联、10000 ms 失败接管窗口、配置热更新仅影响下通电话而停用立即生效。不能只测试正常路径。

## Task 3 — Kotlin 特权运行容器、IPC、配置与诊断

**Create:** `bridge/build.gradle.kts`、`bridge/src/main/AndroidManifest.xml`、`bridge/src/main/keepRules/rules.keep`；`B/{BridgeMain,AgentCallCli}.kt`、`B/ipc/{ControlServer,PeerVerifier}.kt`、`B/config/ConfigStore.kt`、`B/diagnostics/DeviceProbe.kt`；`C/protocol/{RpcCodec,RolePolicy}.kt`；`CT/protocol/{RpcCodecTest,RolePolicyTest}.kt`、`BT/config/ConfigStoreTest.kt`、`BI/ipc/PeerCredentialsTest.kt`。

**Modify:** settings include bridge，版本目录与根插件声明，app 的配置客户端依赖准备。

**Interfaces:** `RolePolicy.authorize(peer: PeerIdentity, request: RpcRequest): Boolean`；`ConfigStore.update(expectedRevision: Long, patch: ConfigPatch): RedactedConfig`；`DeviceProbe.inspect(): CapabilityReport`。CLI 固定命令 `status/config-get/config-set/pair/diagnose/shutdown`；配置仅 stdin。

- [ ] 创建 bridge application 模块，compileSdk/targetSdk 37、minSdk 36、JVM target 11、内置 Kotlin、implementation(core)。只用于打包，不声明 Launcher、不安装为常驻 APK。
- [ ] 先写 `hookCannotReadSecrets`、`claimedUidIsIgnored`、`truncatedFrameRejected`、`oversizedFrameRejected`、`staleRevisionDoesNotOverwrite`，断言拒绝且旧配置不变。运行对应 core/bridge JVM 测试确认失败。
- [ ] 实现最大 64 KiB 控制帧、独立 PCM 通道、请求 ID/会话/版本检查和有界连接队列；身份从 LocalSocket credentials 获取，并核验用户 ID、包与签名，APP/HOOK 权限分开。
- [ ] 实现 root 私有配置原子写入、schema 迁移备份、CAS、密钥 Keep/Replace/Clear 与脱敏导出。配对首次需 root CLI，普通请求不能自注册信任。
- [ ] 实现 Kotlin `@JvmStatic main` 入口及运行容器；错误包含机器可读错误码，不包含密钥。诊断报告真实 ROM/SDK/内核/包签名与隐藏 API 存在情况，未测权限/音频标为未测。
- [ ] 执行 `./gradlew :core:testDebugUnitTest :bridge:testDebugUnitTest :bridge:assembleDebug`；在有测试设备时用专用测试客户端验证不同 UID 被拒绝，记录 SELinux 可达性。JVM fake 不能证明内核对端凭据验证成功。

## Task 4 — Kotlin Hook，保持 Google 电话原行为

**Create:** `A/hook/{HookEntry,ServiceDiscovery,CallRegistry,AndroidCallHandle,HookTransport}.kt`、`app/src/main/assets/xposed_init`、`app/src/main/res/values/xposed_scope.xml`；`AT/hook/CallRegistryTest.kt`、`AI/hook/ServiceDiscoveryTest.kt`。

**Modify:** `app/build.gradle.kts`（Xposed compileOnly）、Manifest、已有 `app/src/main/keepRules/rules.keep`。

**Interfaces:** `ServiceDiscovery.resolve(loader: ClassLoader, services: List<ServiceInfo>): Set<Method>`；`CallRegistry.observe(handle: CallHandle): SessionId`；`CallHandle` 是 Hook 包内的可替换 Android Call 门面。实现任务 1 的 CallControl，命令经任务 3 路由。

- [ ] 写 registry 失败测试：重复 observe 只注册一个 callback、remove 后不能发送命令、旧 epoch/非 RINGING 拒绝 Answer、主动接听撤销待执行命令；由 fake CallHandle 记录实际次数。
- [ ] 写仪器测试服务覆写 onCallAdded 且不调用 super，断言 discovery 找到该实现；另测父子类链方法去重与异常不吞原回调。
- [ ] 实现仅包名匹配才加载的可实例化 `HookEntry`，entry 文件内容为 `org.krvyft.agentcall.hook.HookEntry`。Hook 回调仅入有界队列，网络/IPC阻塞移出电话线程。
- [ ] 实现 Call.Callback、快照、随机 epoch/callId，主线程核验后执行 answer/disconnect；来源标记区分用户和模块动作，未知路由变化保守交还。
- [ ] 补 keep 规则，运行 `./gradlew :app:testDebugUnitTest --tests '*CallRegistryTest'` 与 `./gradlew :app:assembleRelease`。检查实际 release DEX 包含可实例化入口，运行时不打包 Xposed framework 自身。
- [ ] 设备只观察模式校验 Google 电话实际服务、回调和原来电 UI；禁用 Hook 后原行为恢复。Google 电话升级需重新探测；缺设备标记未验证。

## Task 5 — 音频、校准和故障终止

**Create:** `B/audio/{FrameworkAudioBridge,AudioWorker,AudioStateSnapshot,Resampler}.kt`、`B/supervision/{Supervisor,EmergencyCallControl}.kt`、`B/config/CalibrationStore.kt`、`C/policy/CalibrationPolicy.kt`；`CT/policy/CalibrationPolicyTest.kt`、`BT/supervision/SupervisorTest.kt`、`BT/audio/ResamplerTest.kt`、`docs/device-validation.md`。

**Interfaces:** 实现 AudioBridge；`CalibrationPolicy.canAutoAnswer(environment: EnvironmentKey, evidence: CalibrationEvidence): Boolean`；`Supervisor.heartbeat(id: SessionId, role: PeerRole)`；`EmergencyCallControl.disconnectOwned(id: SessionId): CommandResult`。

- [ ] 写失败测试 `streamCreationDoesNotUnlockCalibration`、`environmentChangeInvalidatesEvidence`、`revokedLeaseCannotDisconnect`、`audioFailureKeepsMicrophoneIsolated`，断言 false/false/无挂断/不解除隔离。
- [ ] 实现反射方法探测与严格能力错误分类、PCM16 单声道流、重采样与取消；没有通路返回不支持，不能降级用环境麦克风冒充下行。
- [ ] 实现音频工作与监督角色的独立 app_process 生命周期、最小租约与状态快照，有限重启。心跳 5000 ms 失效后按精确通话身份故障终止，断开确认前不得恢复麦克风。
- [ ] 根据目标 framework 验证独立于 Hook 的特权 Telecom 终止路径；不能核对通话身份或没有权限时返回明确不支持并阻止自动接听。进程重建/接听到 ACTIVE 的窗口也要覆盖。
- [ ] 实现手动校准状态与持久证据：对端听到语音、下行识别、隔离、交还、进程故障测试都完成才写“已验证”；环境绑定包含 API 37、内核、ROM、包版本、框架、SIM/承载和路由。
- [ ] 运行 `./gradlew :core:testDebugUnitTest :bridge:testDebugUnitTest`；真机执行故障注入并记录事实。模拟监督测试通过不等于特权终止可用。

## Task 6 — Kotlin 协程 API 客户端与语音回合

**Create:** `A/ai/{TranscriptionClient,ChatClient,SpeechClient,ApiTransport,ConversationEngine,WavCodec,VoiceActivityDetector}.kt`、`AT/ai/{ApiTransportTest,WavCodecTest,ConversationEngineTest}.kt`、`app/src/test/resources/ai/`。

**Interfaces:** `suspend fun transcribe(wav: ByteArray): String`；`suspend fun reply(messages: List<ChatMessage>): String`；`suspend fun synthesize(text: String): PcmAudio`，ChatMessage 定义于 `A/ai/ChatClient.kt`。ConversationEngine 实现 ConversationPort；所有取消由调用者 CoroutineScope 传播。

- [ ] 写 HTTP fixture/WAV 失败测试：坏长度、非 WAV、大于 8 MiB、跨域跳转、鉴权失败、429/5xx、连接/回合超时；`cancelledTurnNeverWritesAudio` 使用虚拟调度器断言取消后 writeUplink 次数为 0。
- [ ] 运行 `./gradlew :app:testDebugUnitTest --tests '*ai*'` 确认失败，再实现三套独立 HTTPS 端点、multipart WAV、非流式聊天和 WAV TTS；日志拦截器不打印 header/body，重定向不泄漏 Authorization。
- [ ] 实现 PCM/VAD：单段 15 秒、静音 800 ms，播放期间停止分句触发；下行不得与本机 TTS 回声形成自循环。单元测试输入合成音频边界，不只比较实现内部变量。
- [ ] 实现结构化回合：连接 10 秒、整个回合 45 秒、上下文 8 轮、回复 300 字、单响应 8 MiB；可重试错误最多一次且不超过截止时间。CancellationException 原样传播，关闭网络与音频资源。
- [ ] 运行上述测试及全部 `:app:testDebugUnitTest`；添加通话结束清空上下文/音频缓冲测试。真实远程测试需要配置凭据并单独记录，不能使用 fixture 冒充。

## Task 7 — APK 服务、人工接管和真实权限

**Create:** `A/service/{AgentCallService,SessionCoordinator,ServiceBootstrap,CallNotification}.kt`、`A/ipc/BridgeClient.kt`、`A/ui/{MainActivity,CalibrationActivity,TakeoverActivity}.kt`、`app/src/main/res/layout/{activity_main,activity_calibration,activity_takeover}.xml`；`AT/service/SessionCoordinatorTest.kt`、`AI/service/BootstrapTest.kt`。

**Modify:** Manifest、strings、`res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml`、app 测试与依赖配置。

**Interfaces:** `SessionCoordinator.onEvent(event: SessionEvent)` 串行消费 reducer Effect；`suspend fun ServiceBootstrap.ensureReady(): Boolean`；BridgeClient 实现 CallControl 与音频代理。任务 6 ConversationPort 被注入协调器，生产使用真实实现。

- [ ] 写失败测试 `startRequestIsNotReady`、`takeoverRevokesBeforeRestore`、`oldApiResponseIgnored`、`manualCallCannotBeDisconnectedByTimeout`；验证副作用顺序和目标 SessionId，不只看 UI 文案。
- [ ] 实现独立 APK 服务启动握手、会话子 Job、通知及停止流程。按 Android 17 真实资格选择前台服务类型和启动路径，不因拿不到默认电话角色就滥用 phoneCall 类型；不满足运行条件返回 NOT_READY。
- [ ] 实现 Views 页面，沿用现有 AppCompat/Material/主题；Launcher Activity 明确 exported，内部组件默认不导出；通知接管 action 绑定 SessionId，旧通知不可控制新通话。
- [ ] 实现接听预检与 3000 ms 倒计时、ACTIVE 后的音频与 AI 会话、故障 10000 ms 接管窗口、180000 ms 上限；人工交还先确认监督撤权再恢复声音。
- [ ] 关闭应用备份并同步两份备份 XML；清理临时音频、拒绝在普通存储中持久保存 root 密钥，按需申请权限，不添加未使用权限。
- [ ] 执行 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；设备验证锁屏、重启、通知权限拒绝、APK/Hook/bridge 各进程退出；失败保留人工电话并显示具体诊断。

## Task 8 — KernelSU 生命周期和中文 WebUI

**Create:** `module/{module.prop,customize.sh,service.sh,uninstall.sh}`、`module/bin/agentcallctl`、`module/webroot/{index.html,styles.css,app.js,bridge.js}`、`module/webroot/tests/{config.test.mjs,transport.test.mjs}`、`docs/config-schema.md`。

**Interfaces:** `bridge.call(operation, payload): Promise<Result>` 只映射任务 3 固定 CLI；页面不执行任意 root shell。总开关/表单/导入均走后端 schema，前端校验不是唯一防线。

- [ ] 写 JS 失败测试：普通浏览器缺少真实桥时保存失败、密钥 Keep 不被空字段清除、CAS 冲突不覆盖、HTML 内容按文本呈现、空白名单警示、非法 URL/名单冲突阻止保存。
- [ ] 先验证所用 KernelSU 管理器确实支持的安全数据写入/stdin桥，并用测试证明密钥不在 shell/进程 argv、日志或 URL。若现有能力不能满足，明确记录阻塞，不能用 base64 shell 参数伪装保密。
- [ ] 实现固定 CLI 与静态 WebUI 的真实适配；无桥时进入明确的只读/演示状态。开发 preview fake 不能进入默认安装产物的生产路径。
- [ ] 实现概览、规则、三项 API、对话、诊断和校准入口；密钥仅展示状态，提供单独替换/清除；连接测试提示真实 API 请求；不添加在线 CDN。
- [ ] 实现开机启动、版本/配对检查、有限重启、配置迁移、停用/卸载清理；卸载先结束或交还 AI 会话。不要更改 Google 默认角色、vendor 音频配置或 webroot 官方管理的权限。
- [ ] 运行 `node --test module/webroot/tests/*.test.mjs` 和 `sh -n` 检查模块脚本；浏览器手机视口实测保存/错误反馈，并在 KernelSU WebView 单独验证真实桥。

## Task 9 — DEX 打包、整体检查与交付

**Create:** `tools/{package.py,check_package.py}`、`Makefile`、`README.md`、`docs/install.md`、`tools/tests/test_package.py`。

**Modify:** `bridge/build.gradle.kts` 新增 `packageRuntime` 任务、bridge keep 规则、`.gitignore`；更新任务 1/5 的验证文档。

**Interfaces:** `:bridge:packageRuntime` 通过 AGP artifacts 获取 release 容器，输出 `bridge/build/outputs/agentcall/agentcall-bridge.jar`；最终 `dist/agentcall-<version>.zip`、`dist/agentcall-<version>.apk`、`dist/SHA256SUMS`。路径不依赖 IDE 私有目录。

- [ ] 先写打包失败测试：缺 DEX/CLI、缺 webroot、版本不一致、混入密钥/签名私钥、漏掉一个 classesN.dex 时不能产出“成功 ZIP”。执行 `python -m unittest discover -s tools/tests` 确认红灯。
- [ ] 实现 release artifacts→全部 classes*.dex 的容器打包；Kotlin stdlib/core/桥接依赖必须可解析。验证 `BridgeMain.main` 与 CLI 的稳定 JVM 名称，不能只检查 ZIP 中有 classes.dex。
- [ ] 实现确定性模块 ZIP、权限与 SHA-256；单一版本来源为 app 的 versionName/versionCode，模块从构建元数据生成，不能各自写死。APK 明确标注签名类型，发布私钥不进仓库。
- [ ] 定义 `make test`（所有 JVM/JS/Python/脚本检查）、`make build`（app release、bridge runtime、打包）、`make package-check`（产物结构与摘要）；签名工具使用实际 SDK，不下载来历不明二进制。
- [ ] 执行 `./gradlew :core:testDebugUnitTest :bridge:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :bridge:packageRuntime` 与 `make test build package-check`。失败/未运行逐项记录，不用代码静态检查代替构建通过。
- [ ] 在真机运行 bridge 的只读 `diagnose`，验证 Kotlin 运行库、主入口、权限和 socket；使用真实 Google 电话完成三轮远程对话、人工交还与故障终止。双卡/VoWiFi/蓝牙按验证矩阵逐项列结果。
- [ ] 写安装、作用域、API、校准、开关、回退与卸载说明；完成整体代码审阅，修复问题后仅重跑受影响检查。未完成真机音频/故障控制时必须标“待适配/验证”，不宣称全功能完成。

## Plan self-review and evidence boundaries

| 设计内容 | 实施位置 |
| --- | --- |
| 现有目录、构建、包名与 Kotlin | 任务 1，所有新增文件遵守路径前缀表 |
| 规则、时钟、所有权 | 任务 2 |
| IPC、密钥、配置、身份 | 任务 3、7、8 |
| Google 电话 Hook 与保留原行为 | 任务 4 |
| 音频、校准、故障恢复 | 任务 5、7 |
| API、语音回合和取消 | 任务 6 |
| APK 服务/Views/权限 | 任务 7 |
| 模块、WebUI 与卸载 | 任务 8 |
| 打包、release、真机与交付 | 任务 9 |

- 所有共享数据类型、Flow/挂起接口与 SessionId 定义在 Shared Kotlin contracts，后续任务不得引入 Java CompletionStage 或另一套通话 ID。
- core 为 Android library，单测命令是 `:core:testDebugUnitTest`，不误用纯 JVM 插件的任务名。
- 所有 Create 项是计划新增，不因本次文档更新而创建源码目录；现有 Wrapper、图标、主题和两套测试源根继续使用。
- 现有模板状态与功能验收严格区分；本次只进行文档一致性校验，不运行 Gradle 下载或安装。
- 当前用户请求仅为重写 specs/plans；不在本次文档工作中启动实现或再次索要执行方式。后续明确进入实现时再应用相应执行流程。

## References

- [AGP 9.5 兼容表](https://developer.android.com/build/releases/agp-9-5-0-release-notes)
- [AGP 内置 Kotlin 与 compilerOptions](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Gradle Daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria)
- [XposedBridge hookMethod](https://api.xposed.info/reference/de/robv/android/xposed/XposedBridge.html)

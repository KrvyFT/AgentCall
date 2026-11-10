# AgentCall：Google 电话 Hook 与 KernelSU 模块设计（Kotlin）

日期：2026-11-10。工作目录：`/home/krvy/Projects/AgentCall`。

状态：依据当前 Android Studio 工程重新编写；架构沿用用户已选的 Google 电话 Hook 方案；本次只更新文档，不表示业务功能已实现。

## 1. 目标与边界

用户已确定：

- 设备为 Xperia 1 V，Android 17 移植 ROM；用户提供 `ro.build.version.sdk=37`。
- 内核为 `5.15.189-android13-8-00004-g1c3825f8ac0a-ab14110541`。内核分支名称不决定应用 SDK 级别。
- 使用 KernelSU；监听来电，按黑名单或白名单外号码规则自动接听，接入远程 AI 对话和语音合成。
- 保留 Google 电话 `com.google.android.dialer`，通过运行时 Hook 接入，不开发替代拨号器。
- 配套 APK 的 applicationId / namespace 为 `org.krvyft.agentcall`。
- Android 应用、Hook、共享逻辑与特权桥接均使用 Kotlin；构建脚本使用 Kotlin DSL。
- 提供中文前端配置页面，入口为 KernelSU 模块 WebUI。

首版处理 Telecom 管理的蜂窝入站电话，手机直接请求远程 ASR、LLM、TTS，不依赖常驻电脑或自建中转服务器。微信等应用通话、完整双工打断、多通电话同时由 AI 处理不在首版范围。

## 2. 当前工程事实

以下来自实际文件读取，而非之前的建议版本：

| 文件/位置 | 已有内容 | 实施约束 |
| --- | --- | --- |
| `settings.gradle.kts` | 根工程 AgentCall；仅 include(":app")；集中 google/mavenCentral 仓库；Foojay resolver 1.0.0 | 在现有 settings 增补模块和受限仓库，不另建 Groovy 配置 |
| `build.gradle.kts` | version catalog 引用 Android application 插件 | 继续使用版本目录 |
| `gradle/libs.versions.toml` | AGP 9.5.1；Core KTX 1.10.1、AppCompat 1.6.1、Material 1.10.0、JUnit 4.13.2 等 | 新依赖集中声明，禁止动态版本 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.6.0；已有 SHA-256 | 保留 Wrapper、下载地址和校验值 |
| `gradle/gradle-daemon-jvm.properties` | toolchainVersion=25，含各平台下载地址 | 当前 Daemon 需求为 JDK 25，不把 JAVA_HOME=17 当成等效配置 |
| `app/build.gradle.kts` | namespace/applicationId 已为 org.krvyft.agentcall；compileSdk release(37)、targetSdk 37、minSdk 36 | 保留现有安装下限，首版真机验收仅覆盖用户的 API 37 设备 |
| 同上 | Java source/target 为 VERSION_11；release optimization 已开启 | Kotlin JVM target 显式对齐 11；Daemon JDK 与字节码目标分开处理 |
| 同上 | 仅应用 Android application 插件 | 使用 AGP 内置 Kotlin，不重复应用 kotlin-android |
| `app/src/main/AndroidManifest.xml` | application 与图标/主题；allowBackup=true；无 Activity/Service | 后续添加真实组件与权限；备份策略必须覆盖敏感数据 |
| `app/src/main/keepRules/rules.keep` | 已有 R8 规则入口 | 保留路径，补 Hook/反射入口规则，不改用不存在的模板文件 |
| `app/src/test/java/org/krvyft/agentcall/ExampleUnitTest.kt` | 模板加法测试 | 不视为功能验收，后续由有意义的测试替换 |
| `app/src/androidTest/java/org/krvyft/agentcall/ExampleInstrumentedTest.kt` | 已核对包名的模板测试 | 保留并扩展安装/组件测试 |
| `app/src/main/res/` | AppCompat 主题、图标、strings、备份 XML | 沿用 Views/Material，不为简单控制页额外引入 Compose |

当前有 Android Studio 模板，尚无 AgentCall 业务类；`core/`、`bridge/`、`module/`、`tools/` 均为计划新增，不是已存在模块。`git status` 当前不能识别可用仓库，不承诺已提交或修改 `.git`。

AGP 9.5 支持 API 37，官方配套 Gradle 9.6.0、默认 Build Tools 36.0.0；AGP 的最低运行 JDK 与项目指定的 JDK 25 是不同概念。本轮未执行 Gradle 构建，因此不声明 SDK/JDK/依赖已经就绪。[AGP 兼容表](https://developer.android.com/build/releases/agp-9-5-0-release-notes)

## 3. 方案选择

| 方案 | 收益与代价 | 结论 |
| --- | --- | --- |
| Google 电话 Hook＋配套 APK＋KernelSU | 保留电话界面；依赖注入框架、Google 电话版本和厂商音频通路 | 用户选择，按此实现 |
| 自己实现默认拨号 APK | 公开通话控制更独立；需替换用户电话体验 | 不采用 |
| 仅 root 守护进程控制电话 | APK 较少；更多依赖内部 Telecom 机制 | 不采用为正常控制路径 |

配套 APK 普通安装，不重签名 Google 电话，不挂载为系统应用。KernelSU 模块负责用户态服务，不是需要编译的 Linux `.ko` 驱动。root 不等于已获得任意音频接口权限。

## 4. 目录与构建设计

保留已有 app 和构建文件，按职责添加以下内容：

```text
AgentCall/
├── app/                              # 已有 Android application
│   └── src/
│       ├── main/java/org/krvyft/agentcall/  # Kotlin .kt 放在现有 java 源根
│       │   ├── hook/                 # Google 电话进程内的轻量 Hook
│       │   ├── service/              # 独立 APK 进程的会话与网络生命周期
│       │   ├── ai/                   # ASR / LLM / TTS、PCM 和回合处理
│       │   ├── ipc/                  # 与特权进程通信
│       │   └── ui/                   # 安装诊断与人工接管 Views 界面
│       ├── main/assets/xposed_init
│       ├── main/keepRules/rules.keep # 已有，补入口保留规则
│       ├── test/java/org/krvyft/agentcall/
│       └── androidTest/java/org/krvyft/agentcall/
├── core/                             # 待建 Android library，Kotlin 源码不依赖 Android
│   └── src/{main,test}/java/org/krvyft/agentcall/core/
├── bridge/                           # 待建 Android application，用作 DEX 运行容器
│   └── src/{main,test,androidTest}/java/org/krvyft/agentcall/bridge/
├── module/                           # 待建 KernelSU 安装目录与静态 webroot
├── tools/                            # 待建构建产物检查和打包脚本
├── gradle/                           # 已有 Wrapper、版本目录、Daemon 配置
└── docs/                             # specs、plans 与后续安装/设备验证记录
```

依赖方向为 `app → core ← bridge`，不形成 app/bridge 构建循环。core 使用 Android library 插件以共享 AGP 内置 Kotlin，业务源码保持纯 Kotlin，单元测试在宿主 JVM 执行。新增插件 alias 仍引用 AGP 9.5.1。

bridge 使用 application 插件生成包含 Kotlin stdlib、core 及自身代码的 DEX 容器；其 applicationId/namespace 为 `org.krvyft.agentcall.bridge`，不安装为第二个用户应用。构建任务从实际 AGP 产物收集全部 `classes*.dex`，封装 `agentcall-bridge.jar`，由 `app_process` 的 CLASSPATH 加载。不能把普通 JVM JAR 直接当作 DEX 运行，也不能遗漏 Kotlin 运行库。

`BridgeMain` 与 CLI 使用 Kotlin `object` 中的 `@JvmStatic fun main(args: Array<String>)`。Hook 入口使用公开、可无参实例化的 class。反射/字符串加载的类名与方法必须通过 release 构建验证，保留规则分别位于 app/bridge 的 `src/main/keepRules/rules.keep`。

## 5. Kotlin 代码与并发约束

- 数据使用 `data class`，事件/效果使用 `sealed interface`，枚举表达通话状态、能力状态和错误类型。
- 异步接口使用 `suspend`、`Flow`、结构化协程和有界 `Channel`；不得使用 `GlobalScope` 或每条 Hook 回调创建永久线程。
- 状态机为无 Android 依赖的单写者 reducer；时间从可注入的单调时钟提供，测试使用虚拟时钟。
- Hook 不执行 ASR、网络请求或音频编解码；实际 Call 操作切到 Google 电话进程主线程，并在执行前再次核验会话。
- APK 会话拥有可取消的子 Job。取消挂断、人工接管或版本变化后，迟到响应不得产生播放/接听/挂断效果。
- Java 互操作限于 Android/Xposed API 调用；生产业务源文件使用 `.kt`。WebUI 使用 HTML/CSS/JavaScript，模块启动脚本使用 shell，打包工具可用 Python。

AGP 9 默认支持 Kotlin，当前没有 kotlin-android 插件不是遗漏。继续保留 `src/*/java/` 目录放置 Kotlin 文件。[内置 Kotlin 文档](https://developer.android.com/build/migrate-to-built-in-kotlin)

## 6. 进程职责与 IPC

### 6.1 Google 电话进程

通过兼容目标 ROM 的 Zygisk＋LSPosed/Vector 加载传统 Xposed API 82 模块。只作用于 `com.google.android.dialer`，不注入 system_server；框架由用户安装，项目不自动刷入/重启。

动态发现实际 InCallService 子类和覆写回调，保留原方法执行，不只 Hook 可能被绕过的基类空实现。对 Call 注册回调，处理重复 add/remove、进程重建、未知号码、父子类重复 Hook。

会话标识 `SessionId(processEpoch, callId)` 均为随机标识，不用电话号码作为身份。快照包含呈现类型、方向、状态、PhoneAccount、紧急/会议属性和活动通话数。正常接听/挂断由 Hook 针对原 Call 对象执行。Google 电话继续负责拨号、响铃、通话 UI、记录及人工控制。

### 6.2 AgentCall APK 进程

负责名单判断、接听倒计时、会话通知、远程 API 和人工接管。必须先完成独立服务 READY 握手才能自动接听；发出广播或启动请求不算服务已运行。

用户通过 AgentCall 通知/控制页“我来接听”。Google 电话中人工接听会取消未执行的 AI 接管；AI 通话中人工解除静音/切换路由也触发交还。系统路由变更来源不明时保守交还，模块自己的操作有来源标记。

### 6.3 KernelSU 特权进程

监督进程、音频工作进程分离。提供配置、配对、诊断、音频桥接、AI 租约和故障终止能力，不承担远程 API 请求。`service.sh` 等待系统就绪，有限重启；不开网络监听端口。

### 6.4 本地协议与信任

使用分角色 abstract Unix socket，Android 对端凭据作为身份依据，不能信任消息中的 UID。包名、用户 ID、签名及版本在配对时记录并核验。

| 角色 | 允许操作 |
| --- | --- |
| APP | 会话请求、受限配置读取、音频、人工交还 |
| HOOK | 通话快照、心跳、接受受限通话命令；不得读取 API Key |
| CLI_ROOT | 配对、配置写入、诊断、关闭服务 |

此模型信任 Google 电话同 UID 进程，不能区分其中不同代码。固定命令表、协议版本、请求 ID、会话 ID、超时与长度上限均强制验证。控制帧与 PCM 分开传输，队列饱和时有界失败，不阻塞电话 UI。

配置主副本位于 `/data/adb/agentcall`，root 私有、版本化原子写入。UI 响应与秘密配置分开：密钥只回显“已配置”，支持保留/替换/清除；CAS 版本冲突不覆盖他人更新。

WebUI 调用固定 CLI；实现前验证管理器实际支持的 stdin/受控文件传输接口，不猜测 API。密钥不得出现在进程 argv、URL、日志或 shell 插值中；若该管理器不能满足传输约束，记录缺失能力并先修订传输设计，不能显示保存成功。

## 7. 名单与接管规则

总开关默认关闭。三种模式：黑名单只接管命中项；白名单接管名单外号码；组合为“黑名单命中 OR 白名单外”。默认黑名单模式。

- 首版完整号码精确匹配，libphonenumber 按可配置地区规范化，初始 CN；不按后缀裁剪或模糊匹配。
- 空黑名单不接管；空白名单在白名单/组合模式下匹配所有可识别号码，保存时醒目提示。
- 两名单不能含规范化后的同一号码，导入冲突阻止启用。未知/隐藏/不可可靠解析号码独立开关，默认人工处理。
- 不自动导入通讯录，不上传号码和联系人。AgentCall 名单独立于 Google 电话阻止列表；已被原应用或运营商拒接的来电不能挽回。
- 仅新 RINGING 入站通话可接管。外拨、系统可识别的紧急/紧急回拨、会议、第二路活动/保持/等待电话不自动接管。
- 双卡使用实际 PhoneAccount，不切卡。默认只启用已校准的 SIM、承载和路由组合。

## 8. 会话状态与故障恢复

路径：`IDLE → ELIGIBLE → PREPARING → ANSWER_PENDING → ACTIVE_AI → HANDOFF/ENDING → ENDED`。

默认延迟 3000 ms，允许 0–30000 ms；最长 AI 会话 180000 ms，允许 10000–600000 ms，从 ACTIVE 起计时。每通电话最多一个计时器和一个会话。

接听前检查开关、名单、Google 电话默认角色、各进程心跳、网络、配置和校准结果；不为每通电话额外发付费 API 探测。缺一项就保留原响铃。用户先接听、拒接或对方挂断后，不再执行自动命令。

接听命令执行前已准备好麦克风隔离和故障租约；ACTIVE 后才打开实际双向流，每通电话重新检查错误。过去校准成功不代表当前打开必定成功。

| 事件 | 必须行为 |
| --- | --- |
| 用户人工接管 | 与监督端串行撤销 AI 所有权，取消请求/音频/计时器，确认后恢复人工路由与麦克风；撤销失败不得显示交还成功 |
| 音频建立失败 | 停 AI，保持隔离并提示人工接管；10000 ms 内未接管则终止 AI 通话，确认断开后恢复；无法维持隔离时立即终止 |
| API 超时/失败 | 有有效上行与缓存提示时说明故障，随后终止 AI 通话；不承诺无声通路能播放提示 |
| APK 或 Hook 心跳中断 5000 ms | 监督端先停止音频、保持隔离，再故障终止自己持有租约的精确通话，确认断开后恢复 |
| 音频进程失效 | APK/监督端按同一租约规则停止会话；不直接恢复活动通话麦克风 |
| 最长时长到期 | 只终止仍归 AI 的通话；已交还人工的不受影响 |
| 模块停用/卸载 | 先终止或明确交还正在托管的通话，再关闭桥接和清理 |

正常 Call 操作与故障终止均按会话/代次核验，不能用无选择的全局 endCall 操作其他通话。Hook 死亡时必须有独立且真机验证的特权 Telecom 终止路径；不可用则不允许启用自动接管。监督进程重启不能把旧租约绑定到新通话。失败无法结束通话时保持隔离、提示用户并禁止新的 AI 会话。

## 9. 音频与校准

优先探测 AOSP 受限方法 `isPstnCallAudioInterceptable`、`getCallDownlinkExtractionAudioRecord`、`getCallUplinkInjectionAudioTrack`。存在方法、权限通过、成功创建对象都不等于对端收到语音。

内部 PCM16 单声道、明确采样率，适配层负责重采样；只取通话下行，不能用环境麦克风替代。需要同时验证本机麦克风隔离和 TTS 注入可用，不能假设系统静音不影响注入。

默认只校准手持无耳机路由。记录绑定 ROM 指纹、SDK、内核、Google 电话版本、框架版本、桥接版本、音频策略摘要、SIM/承载/路由。变化后失效；API 36 虽允许安装，不继承 API 37 的校准结果。

能力分为未检测、接口可见、待对端确认、已验证、不支持。手动测试电话完成下行识别、对端听到测试语音、麦克风隔离、人工恢复及进程故障注入后才能解锁对应环境。

若厂商 HAL 不支持，收集脱敏诊断并明确阻塞；不尝试未经验证的 mixer 数值、固定 Binder 编号、不关闭 SELinux。需要修改 vendor 音频策略时另行修订适配设计。真机音频验证不能由模拟测试替代。

## 10. 远程 API 与语音回合

ASR、LLM、TTS 分别配置 HTTPS Base URL、API Key、模型；允许不同供应商。首版采用明确的 OpenAI-compatible HTTP 适配：`audio/transcriptions` multipart WAV、`chat/completions` 非流式文本、`audio/speech` WAV。只有聊天兼容的服务不视为支持语音。

采用轮流说话：VAD 分句 → ASR → LLM → TTS → 上行播放。播放期间暂停新回合触发并处理回声残留，不承诺双工打断。

默认中文开场白：“您好，我是机主的 AI 电话助理，请问有什么事？”提示词、开场白、音色和语速可配置。模型仅生成对话，不执行工具、设备命令或身份/付款操作。

资源上限：单段 15 秒、VAD 静音 800 ms、连接超时 10 秒、回合总超时 45 秒、响应上限 8 MiB、最多 8 轮上下文、回复 300 字。429/5xx 在截止时间内最多重试一次；已播放语音不重播。所有 HTTP 与音频操作响应协程取消，不吞掉 CancellationException。

校验证书、路径前缀与响应类型；跨主机重定向不能携带 Authorization。无 API Key 可运行 fixtures，但不算真实远程验证。

## 11. 界面、隐私与生命周期

### KernelSU WebUI

静态资源随模块打包，中文手机布局，无在线 CDN：

- 概览：Google 电话默认角色、Hook/服务连通性、版本、校准、开关和最近错误。
- 规则：三种模式、名单、未知号码、地区、延迟/时长、号码匹配试算。
- AI：三项地址、密钥状态、模型、音色与独立连接测试；提示测试会请求远程 API。
- 对话：提示词、开场白、语速、未保存与验证反馈。
- 诊断：脱敏日志/导出、校准入口、重试服务与停用。浏览器演示显式标注模拟数据。

### APK Views 界面

沿用 AppCompat/Material 和现有主题，提供启动向导、root/Hook 检查、校准页、会话状态及人工接管按钮。不插入 Google 电话内部布局、不请求默认拨号角色、不伪用只授予默认拨号器的前台服务资格。

后台启动、锁屏通知和实际前台服务类型按 Android 17 的真实权限与运行条件验证；不能仅添加 manifest permission 就声称可后台运行。无法保持 READY 时不自动接听。

### 数据与卸载

默认只保留有上限的脱敏事件，不保存原始录音或完整转写。语音/文本会发送给配置的供应商，页面明示。密钥不防御同设备其他已授权 root 进程，但不得进入普通应用备份、导出或日志。

现有 allowBackup=true 需要在实现任务中改为关闭 AgentCall 备份，并同步 data_extraction_rules/backup_rules，覆盖诊断与临时文件；不得把模板备份策略原样当作合格实现。

升级保留原子配置并迁移 schema；卸载清理私有密钥/配对记录。APK 被卸载时模块关闭会话；模块移除时 Hook 退出自动控制，Google 电话仍保持默认。用户通过框架管理器关闭作用域，不自动改电话角色。

## 12. 验收与交付

交付源代码、配套 APK、包含完整 Kotlin DEX 运行时的模块 ZIP、SHA-256、安装/回退与设备验证说明。

本地验收：名单语义、时间与竞态、角色鉴权、CAS 配置、密钥脱敏、API 错误/取消、WAV 边界、APK release 与 Hook 入口、真实 DEX 打包、WebUI 错误反馈。

设备验收：Google 电话原功能不变、锁屏/重启服务握手、真实匹配来电、至少三轮双向 AI 对话、人工接管、麦克风隔离、故障终止、升级失效/卸载恢复。双卡、VoWiFi、蓝牙分项验证，未测组合不启用。

报告分别列“本地已验证 / 真机已验证 / 未验证或阻塞”。能构建不等于可用；音频或故障控制未证实时产物标注待适配，不把 dummy backend 作为生产实现。

## 13. 实施对应与参考

[实施计划](../plans/2026-11-10-google-dialer-agentcall.md)按当前目录、Kotlin 类型和实际 Gradle 任务拆解。新增模块与文件均在计划中标注；这份文档的重写不安装依赖或改动业务工程。

- [Gradle Daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria)
- [KernelSU 模块开发](https://kernelsu.org/guide/module.html)、[WebUI](https://kernelsu.org/guide/module-webui.html)
- [Android InCallService](https://developer.android.com/reference/android/telecom/InCallService)
- [音频输入共享约束](https://developer.android.com/media/platform/sharing-audio-input)
- [AOSP AudioManager 受限接口参考，非目标 ROM 实测](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/AudioManager.java)
- [Vector 的传统/现代 Xposed 支持](https://github.com/JingMatrix/Vector)

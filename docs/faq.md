# FAQ

## 它会替换 Google 电话吗？

不会。AgentCall 保留 `com.google.android.dialer` 作为默认电话应用，不重签、不替换、不插入
它的内部布局；拨号、响铃、通话 UI、记录和人工控制仍由 Google 电话负责。AgentCall 只是
通过 Vector/LSPosed 在拨号器进程里观察来电，并在获得授权时执行受限的接听/挂断。

## 需要 root 吗？

需要。KernelSU 模块用 root `app_process` 运行特权桥（控制面、监督、音频中继、配置）与独立
音频工作进程。普通 APK 只负责会话与远程 AI；没有 root 就没有 Telecom/音频特权路径。

## 需要 LSPosed 或 Vector 吗？

需要其中一个（传统 Xposed API 82 兼容层），并且作用域**只勾选** `com.google.android.dialer`。
不要勾选系统框架或 `system_server`。框架由用户自行安装；AgentCall 不自动刷入/重启。

## 没有校准可以自动接听吗？

不可以。自动接听要求：与当前环境逐字段绑定的校准证据（五项人工测试）+ 设备验证过的、
按通话身份定位的特权 Telecom 终止能力。当前第二项不存在（默认
`UnverifiedCallIdentityVerifier` 永远不可用），所以即使校准记录齐全，自动接听也保持关闭。
这是有意的 fail-closed，不是可以通过设置绕过的开关。

## 它会把录音发出去吗？

默认不保存原始录音或完整转写。通话音频片段会发送给你配置的 ASR 供应商，对话文本会发送给
LLM，TTS 文本会发送给 TTS 供应商；这些请求使用你配置的 HTTPS 端点与密钥，可能产生费用。
AgentCall 侧没有“上传录音到项目服务器”的路径；但供应商是否记录由其政策决定。

## 什么数据会离开设备？

- 会：通话音频片段（ASR）、对话文本（LLM）、待合成文本（TTS）；以及这些 HTTPS 请求的
  常规元数据（目标主机、时间、大小）。
- 不会由 AgentCall 上传到项目/第三方服务器：通讯录、名单、配对记录。API Key 只发送给你
  配置的供应商用于认证，不发给项目侧。
- 号码在本地用于名单匹配，会话 ID 使用随机 `processEpoch + callId`，不用电话号码。
- 诊断/日志只保留机器码与有限字段；APK 关闭备份；不保存原始音频/完整转写。

具体字段与保留策略见 [docs/security.md](security.md) 与 [docs/config-schema.md](config-schema.md)。

## 银行/验证码电话会被 AI 接吗？

不要把它用于银行、支付、验证码、法律/医疗等敏感场景。规则层会排除紧急、会议、第二路通话；
未知/隐藏号码默认人工处理；但“规则没命中”不等于“安全”。建议把敏感号码放进黑名单，或保持
总开关关闭。模型只生成对话，不执行付款/设备命令，但它仍可能被话术诱导，不能替代人工判断。

## 为什么 APK 是 debug 签名？

当前是本地开发构建，用本机 Android debug keystore 签名（`CN=Android Debug`），便于安装验证；
它不是发布密钥，**不可分发**。正式分发前必须用发布密钥重签并重新生成 `SHA256SUMS`；
debug 签名也不构成发布者身份或安全边界。

## 支持哪些设备/ROM？

目标设备是 Xperia 1 V（XQ-DQ72）/ Android 17 / API 37；`minSdk 36`。其他 ROM/机型、
双卡、VoWiFi、蓝牙路由都**未验证**；未测组合不应启用。行为高度依赖厂商音频 HAL、Google
电话版本与 Xposed 框架实现。

## 会自动改默认拨号器或 SELinux 吗？

不会。APK 不请求 `ROLE_DIALER`，不伪用默认拨号器专属的前台服务资格；模块不注入
`system_server`、不重签 Google 电话、不改 SELinux、不自动刷框架。默认拨号器需要你手动确认。

## 卸载后 Google 电话会恢复吗？

Google 电话本身从未被修改，禁用 AgentCall 作用域/卸载模块后它保持原行为。但卸载脚本只能
有界地停止进程，不能替你了结正在进行的通话或解除麦克风隔离；看到
`microphone_still_isolated` / `shutdown_unresolved` 时按
[docs/troubleshooting.md](troubleshooting.md) 第 8 节处理。

## 为什么升级后要重新配对？

配对记录绑定 uid + 签名 SHA-256 + `versionName`。APK 升级、Google 电话升级或 APK 卸载重装
都会让旧记录失效，必须由 root CLI 重新 `pair`。这是 fail-closed 的版本锚定，不是可选步骤。

## 仓库有开源许可证吗？

当前仓库**没有 `LICENSE` 文件**，因此不声明任何开源许可证；在补充许可证前默认保留所有权利。
如需分发或复用，请先与维护者确认。

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { fakeManager, CTL, INBOX, LOG_PATH, MODULE_DIR } from "./fake-manager.mjs";

const require = createRequire(import.meta.url);
const B = require("../bridge.js");

const SECRET = "sk-live-DO-NOT-LEAK-9f8e7d6c";

function redacted(overrides, secrets) {
  const config = Object.assign(
    {
      enabled: false,
      mode: "BLACKLIST",
      blacklist: [],
      whitelist: [],
      region: "CN",
      handleUnknownNumbers: false,
      answerDelayMs: 3000,
      maxCallDurationMs: 180000,
      enabledPhoneAccounts: [],
      asr: null,
      llm: null,
      tts: null,
      voice: "",
      speed: 1.0,
      openingLine: "您好，我是机主的 AI 电话助理，请问有什么事？",
      prompt: "",
      revision: 0,
    },
    overrides || {},
  );
  return JSON.stringify({
    config,
    secrets: secrets || { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" },
  });
}

function bridgeWith(fake) {
  return B.createBridge({ scope: { ksu: fake.ksu } });
}

const statusHandler = {
  status: { errno: 0, stdout: JSON.stringify({ bridgeVersion: "1.0", protocolVersion: 1, revision: 3, enabled: false, paired: true, uptimeMs: 1200, activeConnections: 0, rejectedConnections: 0 }) },
};

test("普通浏览器缺少真实桥：进入明确只读/演示态，任何保存都失败且不宣称成功", async () => {
  const bridge = B.createBridge({ scope: {} });
  assert.equal(bridge.mode, "demo");
  assert.equal(bridge.support.hasBridge, false);

  const status = await bridge.call("status");
  assert.equal(status.ok, false);
  assert.equal(status.code, "bridge_unavailable");

  const save = await bridge.call("config-set", { expectedRevision: 0, patch: { enabled: true, secrets: { ASR: { action: "keep" }, LLM: { action: "keep" }, TTS: { action: "keep" } } } });
  assert.equal(save.ok, false);
  assert.equal(save.code, "bridge_unavailable");
  assert.equal(save.saved, undefined);

  assert.equal(B.DEMO.demo, true);
  assert.ok(String(B.DEMO.note).length > 0);
});

test("管理器缺少 io 受控文件接口：读取可用，写入被拒绝且不执行 config-set", async () => {
  const fake = fakeManager({ io: false, handlers: statusHandler });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.mode, "readonly");
  assert.equal(bridge.support.reason, "io_missing");

  const status = await bridge.call("status");
  assert.equal(status.ok, true);
  assert.equal(status.data.paired, true);

  const save = await bridge.call("config-set", { expectedRevision: 3, patch: { enabled: true, secrets: { ASR: { action: "keep" }, LLM: { action: "keep" }, TTS: { action: "keep" } } } });
  assert.equal(save.ok, false);
  assert.equal(save.code, "secret_transport_unsupported");
  assert.equal(fake.commands.some((c) => c.includes("config-set")), false);

  const log = await bridge.readLog();
  assert.equal(log.ok, false);
  assert.equal(log.code, "io_unavailable");
});

test("真实桥：密钥只经 root 私有文件 stdin 传输，绝不出现在 argv/日志/URL", async () => {
  let observedRevision = 4;
  const fake = fakeManager({
    handlers: {
      "config-set": () => ({
        errno: 0,
        stdout: redacted({ revision: observedRevision, enabled: true }, { ASR: "CONFIGURED", LLM: "MISSING", TTS: "MISSING" }),
        stderr: "",
      }),
    },
  });
  const bridge = bridgeWith(fake);
  const result = await bridge.call("config-set", {
    expectedRevision: 3,
    patch: {
      enabled: true,
      secrets: { ASR: { action: "replace", value: SECRET }, LLM: { action: "keep" }, TTS: { action: "keep" } },
    },
  });

  assert.equal(result.ok, true);
  assert.equal(result.data.config.revision, 4);

  // 唯一一条命令：CLI 契约仍然是 config-set + stdin，但负载路径按次唯一。
  assert.equal(fake.commands.length, 1);
  const inboxPath = fake.writes[0].path;
  assert.equal(fake.commands[0], CTL + " config-set < " + inboxPath);
  assert.match(inboxPath, new RegExp("^" + B.INBOX_DIR + "/config-set\\.[a-z0-9-]+\\.json$"));
  assert.notEqual(inboxPath, INBOX, "不得再共用固定负载路径（两个 WebUI 实例会互相覆盖）");
  const secretBase64 = B.toBase64Utf8(SECRET);
  for (const command of fake.commands) {
    assert.equal(command.includes(SECRET), false);
    assert.equal(command.includes(secretBase64), false);
    assert.equal(/echo|printf|base64|sh -c/.test(command), false);
  }

  // 密钥确实通过文件通道（base64 只是该 Java 桥字节写入方法的编码，不经过 shell）。
  assert.equal(fake.writes.length, 1);
  const written = JSON.parse(fake.writes[0].text);
  assert.equal(written.expectedRevision, 3);
  assert.equal(written.patch.secrets.ASR.action, "replace");
  assert.equal(written.patch.secrets.ASR.value, SECRET);
  // 写后立即删除临时文件。
  assert.equal(fake.files.has(inboxPath), false);

  const logText = JSON.stringify(bridge.logs());
  assert.equal(logText.includes(SECRET), false);
  assert.equal(logText.includes(secretBase64), false);
});

test("两个 WebUI 实例并发保存：唯一负载路径避免 A 的 CLI 读到 B 的负载", async () => {
  const fake = fakeManager();
  const commands = [];
  const callbacks = [];
  // 让两次写文件都先落盘、CLI 调用挂起：正固定路径下会互相覆盖，CAS 仍可能通过。
  fake.ksu.exec = (command, optionsJson, callbackName) => {
    commands.push(command);
    // 捕获回调函数本身：同一进程里两个 bridge 实例的 callbackName 可能撞名，
    // 但每个实例各自的回调仍必须收到自己的响应。
    callbacks.push({ command, callback: globalThis[callbackName] });
  };
  const firstBridge = B.createBridge({ scope: { ksu: fake.ksu } });
  const secondBridge = B.createBridge({ scope: { ksu: fake.ksu } });
  const firstSave = firstBridge.call("config-set", { expectedRevision: 0, patch: { enabled: true } });
  const secondSave = secondBridge.call("config-set", { expectedRevision: 0, patch: { enabled: false } });

  assert.equal(fake.writes.length, 2, "两个实例必须各自写入一个负载");
  assert.notEqual(fake.writes[0].path, fake.writes[1].path, "固定路径会让 A 的 CLI 读到 B 的负载");
  assert.equal(commands.length, 2);
  assert.equal(commands[0], CTL + " config-set < " + fake.writes[0].path);
  assert.equal(commands[1], CTL + " config-set < " + fake.writes[1].path);
  assert.equal(JSON.parse(fake.files.get(fake.writes[0].path)).patch.enabled, true);
  assert.equal(JSON.parse(fake.files.get(fake.writes[1].path)).patch.enabled, false);

  callbacks.forEach((entry, index) => {
    assert.equal(fake.files.has(fake.writes[index].path), true, "各自 CLI 执行时自己的负载仍在磁盘上");
    entry.callback(0, redacted({ enabled: index === 0, revision: 1 }), "");
  });
  const [first, second] = await Promise.all([firstSave, secondSave]);
  assert.equal(first.ok, true);
  assert.equal(second.ok, true);
  assert.equal(fake.files.size, 0, "两次保存后都不得留下明文负载");
});

test("inbox 目录由 WebUI 显式收紧为 0700（ownerOnly 读写执行、组/其它无权限）", async () => {
  const fake = fakeManager({ handlers: { "config-set": () => ({ errno: 0, stdout: redacted({ revision: 1 }), stderr: "" }) } });
  const result = await bridgeWith(fake).call("config-set", { expectedRevision: 0, patch: { enabled: false } });
  assert.equal(result.ok, true);
  assert.equal(fake.dirs.has(B.INBOX_DIR), true, "必须创建 inbox 目录");
  const flags = fake.perms.filter((entry) => entry.path === B.INBOX_DIR);
  for (const kind of ["readable", "writable", "executable"]) {
    assert.ok(
      flags.some((entry) => entry.kind === kind && entry.value === true && entry.ownerOnly === true),
      "目录必须以 ownerOnly 方式设置 " + kind,
    );
  }
});

test("写入中途失败时删除半成品 inbox 负载（文档承诺“总是删除”）", async () => {
  const fake = fakeManager({ failFlush: true, handlers: statusHandler });
  const bridge = bridgeWith(fake);
  const result = await bridge.call("config-set", { expectedRevision: 0, patch: { enabled: true } });
  assert.equal(result.ok, false);
  assert.equal(result.code, "inbox_write_failed");
  assert.equal(fake.writes.length, 1, "失败路径应先确认自己写了哪个唯一负载路径");
  assert.equal(fake.files.has(fake.writes[0].path), false, "失败后不得留下含密钥的半成品文件");
  assert.equal(fake.commands.length, 0, "写入失败时不得执行 config-set");
});

test("保存只有在后端回读确认新 revision 与密钥状态后才算成功", async () => {
  const unchanged = fakeManager({
    handlers: {
      "config-set": () => ({ errno: 0, stdout: redacted({ revision: 3 }, { ASR: "CONFIGURED", LLM: "MISSING", TTS: "MISSING" }), stderr: "" }),
    },
  });
  const first = await bridgeWith(unchanged).call("config-set", {
    expectedRevision: 3,
    patch: { secrets: { ASR: { action: "replace", value: SECRET }, LLM: { action: "keep" }, TTS: { action: "keep" } } },
  });
  assert.equal(first.ok, false);
  assert.equal(first.code, "save_unconfirmed");

  const wrongSecretState = fakeManager({
    handlers: {
      "config-set": () => ({ errno: 0, stdout: redacted({ revision: 4 }, { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" }), stderr: "" }),
    },
  });
  const second = await bridgeWith(wrongSecretState).call("config-set", {
    expectedRevision: 3,
    patch: { secrets: { ASR: { action: "replace", value: SECRET }, LLM: { action: "keep" }, TTS: { action: "keep" } } },
  });
  assert.equal(second.ok, false);
  assert.equal(second.code, "save_unconfirmed");
});

test("CAS 冲突不覆盖：只发一次写请求，不自动重试或抬升 revision，临时文件仍被删除", async () => {
  const conflict = fakeManager({
    handlers: {
      "config-set": () => ({
        errno: 1,
        stdout: JSON.stringify({ error: { code: "config_conflict", message: "config revision conflict: expected 3, current 5" } }),
        stderr: "",
      }),
    },
  });
  const bridge = bridgeWith(conflict);
  const result = await bridge.call("config-set", {
    expectedRevision: 3,
    patch: { enabled: true, secrets: { ASR: { action: "keep" }, LLM: { action: "keep" }, TTS: { action: "keep" } } },
  });
  assert.equal(result.ok, false);
  assert.equal(result.code, "config_conflict");
  assert.equal(result.conflict, true);
  assert.equal(conflict.commands.length, 1);
  assert.equal(bridge.lastKnownRevision(), null);
  assert.equal(conflict.writes.length, 1);
  assert.equal(conflict.files.has(conflict.writes[0].path), false);
});

test("未知操作、参数走私与非法负载都在执行前被拒绝", async () => {
  const fake = fakeManager({ handlers: statusHandler });
  const bridge = bridgeWith(fake);

  const bad = await bridge.call("rm -rf /");
  assert.equal(bad.ok, false);
  assert.equal(bad.code, "unsupported_operation");

  const noPayload = await bridge.call("status", { evil: true });
  assert.equal(noPayload.ok, false);
  assert.equal(noPayload.code, "payload_not_allowed");

  const notObject = await bridge.call("config-set", "enabled=true");
  assert.equal(notObject.ok, false);
  assert.equal(notObject.code, "payload_invalid");

  const extraKey = await bridge.call("config-set", { expectedRevision: 0, patch: {}, extra: 1 });
  assert.equal(extraKey.ok, false);
  assert.equal(extraKey.code, "payload_invalid");

  const unknownPatchKey = await bridge.call("config-set", { expectedRevision: 0, patch: { shell: "id" } });
  assert.equal(unknownPatchKey.ok, false);
  assert.equal(unknownPatchKey.code, "payload_invalid");

  const unknownSecret = await bridge.call("config-set", { expectedRevision: 0, patch: { secrets: { KEY: { action: "clear" } } } });
  assert.equal(unknownSecret.ok, false);
  assert.equal(unknownSecret.code, "payload_invalid");

  const negativeRevision = await bridge.call("config-set", { expectedRevision: -1, patch: {} });
  assert.equal(negativeRevision.ok, false);
  assert.equal(negativeRevision.code, "payload_invalid");

  // 后端 RpcCodec 只接受 {baseUrl,model} 对象：null 会被 payload_invalid 拒绝。
  const nullEndpoint = await bridge.call("config-set", { expectedRevision: 0, patch: { asr: null } });
  assert.equal(nullEndpoint.ok, false);
  assert.equal(nullEndpoint.code, "payload_invalid");

  assert.deepEqual(fake.commands, []);
});

test("moduleInfo 给出的模块目录不可信时拒绝执行命令（fail closed）", async () => {
  const fake = fakeManager({ moduleDir: "/tmp/attacker" });
  const bridge = bridgeWith(fake);
  const result = await bridge.call("status");
  assert.equal(result.ok, false);
  assert.equal(result.code, "module_dir_unresolved");
  assert.deepEqual(fake.commands, []);
});

test("moduleInfo 存在但缺少 moduleDir（版本偏差）时回退固定模块路径，而不是拒绝所有命令", async () => {
  for (const moduleInfo of [{ id: "agentcall", version: "1.0" }, {}, () => JSON.stringify({ id: "agentcall" })]) {
    const fake = fakeManager({ moduleInfo, handlers: statusHandler });
    const bridge = bridgeWith(fake);
    assert.equal(bridge.mode, "kernelsu", "缺少 moduleDir 不得降级为只读");
    assert.equal(bridge.support.moduleDir, MODULE_DIR);
    const status = await bridge.call("status");
    assert.equal(status.ok, true);
    assert.equal(fake.commands[0], CTL + " status");
  }
});

test("moduleInfo 抛错或返回非法 JSON 时也回退固定模块路径", async () => {
  for (const moduleInfo of [
    () => {
      throw new Error("manager version skew");
    },
    () => "not-json",
    () => 42,
  ]) {
    const fake = fakeManager({ moduleInfo, handlers: statusHandler });
    const bridge = bridgeWith(fake);
    assert.equal(bridge.mode, "kernelsu");
    assert.equal(bridge.support.moduleDir, MODULE_DIR);
    assert.equal((await bridge.call("status")).ok, true);
  }
});

test("io.support === false：视为没有受控文件接口（readonly io_missing），保存被拒绝", async () => {
  const fake = fakeManager({ ioForm: "object", ioSupport: false, ioVersionStyle: "number", ioVersion: 0, handlers: statusHandler });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.mode, "readonly");
  assert.equal(bridge.support.reason, "io_missing");
  assert.equal(bridge.support.ioForm, "object");
  assert.equal(bridge.support.ioSupport, false);
  assert.equal(bridge.support.ioVersion, 0);
  assert.equal(bridge.support.hasIo, false);

  const save = await bridge.call("config-set", { expectedRevision: 0, patch: { enabled: true } });
  assert.equal(save.ok, false);
  assert.equal(save.code, "secret_transport_unsupported");
  assert.equal(fake.commands.some((command) => command.indexOf(" config-set ") !== -1), false);
});

test("对象形式 ksu.io（support: true、version: 1）识别为 kernelsu，保存可用", async () => {
  const fake = fakeManager({
    ioForm: "object",
    ioSupport: true,
    ioVersionStyle: "number",
    ioVersion: 1,
    handlers: {
      ...statusHandler,
      "config-set": () => ({ errno: 0, stdout: redacted({ enabled: true, revision: 1 }), stderr: "" }),
    },
  });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.mode, "kernelsu");
  assert.equal(bridge.support.reason, "ok");
  assert.equal(bridge.support.ioForm, "object");
  assert.equal(bridge.support.ioSupport, true);
  assert.equal(bridge.support.ioVersion, 1);
  assert.equal(bridge.support.hasFile, true);
  assert.equal(bridge.support.hasFileOutputStream, true);

  const save = await bridge.call("config-set", { expectedRevision: 0, patch: { enabled: true } });
  assert.equal(save.ok, true);
  assert.equal(fake.commands.filter((command) => command.indexOf(" config-set ") !== -1).length, 1);
});

test("对象形式 io 的 version 缺失：不抛错，support: true 仍为 kernelsu（诊断版本为 0）", async () => {
  const fake = fakeManager({ ioForm: "object", ioSupport: true, ioVersionStyle: "missing", handlers: statusHandler });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.mode, "kernelsu");
  assert.equal(bridge.support.ioForm, "object");
  assert.equal(bridge.support.ioSupport, true);
  assert.equal(bridge.support.ioVersion, 0);
});

test("对象形式 io 的 version 为数字/数字字符串均可解析，且不当作方法调用", async () => {
  const numeric = bridgeWith(fakeManager({ ioForm: "object", ioSupport: true, ioVersionStyle: "number", ioVersion: 2, handlers: statusHandler }));
  assert.equal(numeric.mode, "kernelsu");
  assert.equal(numeric.support.ioVersion, 2);

  const stringVersion = bridgeWith(fakeManager({ ioForm: "object", ioSupport: true, ioVersionStyle: "string", ioVersion: "3", handlers: statusHandler }));
  assert.equal(stringVersion.mode, "kernelsu");
  assert.equal(stringVersion.support.ioVersion, 3);
});

test("旧式函数形式 ksu.io() 仍可用：version() 返回数字时识别为 kernelsu", async () => {
  const fake = fakeManager({ handlers: statusHandler });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.mode, "kernelsu");
  assert.equal(bridge.support.ioForm, "function");
  assert.equal(bridge.support.ioSupport, null);
  assert.equal(bridge.support.ioVersion, 1);
  assert.equal(bridge.support.hasFile, true);
  assert.equal(bridge.support.hasFileOutputStream, true);
});

test("io 存在但缺少 FileOutputStream：readonly io_missing，保存被拒绝（诊断指出缺类）", async () => {
  const fake = fakeManager({ ioForm: "object", ioSupport: true, ioVersionStyle: "number", ioVersion: 1, fileOutputStream: false, handlers: statusHandler });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.mode, "readonly");
  assert.equal(bridge.support.reason, "io_missing");
  assert.equal(bridge.support.hasFile, true);
  assert.equal(bridge.support.hasFileOutputStream, false);

  const save = await bridge.call("config-set", { expectedRevision: 0, patch: { enabled: true } });
  assert.equal(save.ok, false);
  assert.equal(save.code, "secret_transport_unsupported");
  assert.equal(fake.commands.some((command) => command.indexOf(" config-set ") !== -1), false);
});

test("对象形式 io 可用但模块目录不可信：仍以 module_dir_unresolved 为准（不放宽）", async () => {
  const fake = fakeManager({ ioForm: "object", ioSupport: true, ioVersionStyle: "number", ioVersion: 1, moduleDir: "/tmp/attacker" });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.mode, "readonly");
  assert.equal(bridge.support.reason, "module_dir_unresolved");
  assert.equal(bridge.support.hasIo, true);
  assert.deepEqual(fake.commands, []);
});

test("对象形式 ksu.io 同样可读取日志：不再把 io 当函数调用（Task 11M）", async () => {
  const fake = fakeManager({ ioForm: "object", ioSupport: true, ioVersionStyle: "number", ioVersion: 1, logText: "object-form-log\n" });
  const bridge = bridgeWith(fake);
  const result = await bridge.readLog(4096);
  assert.equal(result.ok, true);
  assert.equal(result.text, "object-form-log\n");
  assert.equal(fake.commands.length, 0);
});

test("readLog 通过 RandomAccessFile 读取尾部并标记 truncated，且做脱敏", async () => {
  const fake = fakeManager({ logText: "prefix\napi_key=sk-live-abcdefgh" });
  const bridge = bridgeWith(fake);
  const result = await bridge.readLog(24);
  assert.equal(result.ok, true);
  assert.equal(result.truncated, true);
  assert.equal(result.text, "api_key=***", "只读尾部 24 字节，密钥被脱敏");
  assert.equal(fake.commands.length, 0, "读日志不得执行 shell 命令");
});

test("没有 RandomAccessFile 时 readLog 回退 FileInputStream", async () => {
  const fake = fakeManager({ randomAccess: false, logText: "hello agentcall log\n" });
  const bridge = bridgeWith(fake);
  const result = await bridge.readLog(4096);
  assert.equal(result.ok, true);
  assert.equal(result.truncated, false);
  assert.equal(result.text, "hello agentcall log\n");
});

test("文件接口可用时，即使 moduleInfo 目录不可信也仍能读取固定路径日志", async () => {
  const fake = fakeManager({ moduleDir: "/tmp/attacker", logText: "tail-of-log" });
  const bridge = bridgeWith(fake);
  assert.equal(bridge.support.reason, "module_dir_unresolved");
  const result = await bridge.readLog();
  assert.equal(result.ok, true);
  assert.equal(result.text, "tail-of-log");
});

test("没有 moduleInfo 的旧管理器回退到固定模块路径，不拼接任何外部输入", async () => {
  const fake = fakeManager({ moduleInfo: false, handlers: statusHandler });
  const bridge = bridgeWith(fake);
  const result = await bridge.call("status");
  assert.equal(result.ok, true);
  assert.equal(fake.commands[0], CTL + " status");
});

test("页面不做任何网络请求：密钥不可能进入 URL", async () => {
  const fake = fakeManager({ handlers: { ...statusHandler, "config-get": { errno: 0, stdout: redacted({ revision: 2 }), stderr: "" } } });
  const bridge = bridgeWith(fake);
  const originalFetch = globalThis.fetch;
  let fetchCalls = 0;
  globalThis.fetch = () => {
    fetchCalls += 1;
    throw new Error("network must not be used");
  };
  try {
    await bridge.call("status");
    await bridge.call("config-get");
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(fetchCalls, 0);
});

test("不受信任的 HTML 一律按文本呈现：setText 只写 textContent", () => {
  const payload = '<img src=x onerror="alert(1)"><b>粗体</b>';
  const element = {
    textContent: "",
    get innerHTML() {
      throw new Error("innerHTML must never be used");
    },
    set innerHTML(_value) {
      throw new Error("innerHTML must never be used");
    },
  };
  B.setText(element, payload);
  assert.equal(element.textContent, payload);
  // 转义 helper 已删除（此前无人使用）：展示层唯一通道是 textContent。
  assert.equal("escapeHtml" in B, false);
});

test("静态资源不含在线 CDN、内联事件处理器，也不使用 innerHTML", () => {
  const webroot = dirname(fileURLToPath(import.meta.url)) + "/..";
  const html = readFileSync(webroot + "/index.html", "utf8");
  const css = readFileSync(webroot + "/styles.css", "utf8");
  const app = readFileSync(webroot + "/app.js", "utf8");
  const bridgeSource = readFileSync(webroot + "/bridge.js", "utf8");

  for (const source of [html, css, app, bridgeSource]) {
    assert.equal(/https?:\/\//.test(source), false, "不得引用在线资源或在线 CDN");
  }
  assert.equal(/<script[^>]+src\s*=\s*["']https?:/i.test(html), false);
  assert.equal(/<link[^>]+href\s*=\s*["']https?:/i.test(html), false);
  assert.equal(/\son[a-z]+\s*=/i.test(html), false, "不得使用内联事件处理器");
  assert.equal(/\.innerHTML\b/.test(app), false, "app.js 只能写 textContent");
  assert.equal(/\.innerHTML\b/.test(bridgeSource), false, "bridge.js 只能写 textContent");
  assert.equal(/fetch\s*\(|XMLHttpRequest/.test(app), false, "页面不得发起网络请求");
  assert.equal(/<meta[^>]+name=["']viewport["']/i.test(html), true);
  assert.equal(/<script[^>]+src=["']bridge\.js["']/i.test(html), true);
});

// 渲染路径（DOM 层）由 app-ui.test.mjs 用极简 DOM 垫片真实执行；此处只保留静态扫描。

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const B = require("../bridge.js");

const CTL = "/data/adb/modules/agentcall/bin/agentcallctl";
const INBOX = "/data/adb/agentcall/inbox/config-set.json";
const SECRET = "sk-live-DO-NOT-LEAK-9f8e7d6c";
const MODULE_DIR = "/data/adb/modules/agentcall";

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

/**
 * 只模拟 KernelSU 管理器文档化的注入接口（WebViewInterface.kt / js/index.js）：
 * exec(cmd, optionsJson, callbackName) 与 io().version()/File()/FileOutputStream()。
 * 绝不接触真实 shell。
 */
function fakeManager(options) {
  const opts = options || {};
  const commands = [];
  const files = new Map();
  const writes = [];
  const dirs = new Set();
  const handlers = opts.handlers || {};

  const io = {
    version: () => 1,
    File(path) {
      return {
        exists: () => files.has(path) || dirs.has(path),
        isFile: () => files.has(path),
        isDirectory: () => dirs.has(path),
        mkdirs: () => {
          dirs.add(path);
          return true;
        },
        delete: () => files.delete(path),
        isSymlink: () => false,
        setReadable: () => true,
        setWritable: () => true,
      };
    },
    FileOutputStream() {
      const streams = new Map();
      return {
        open(path) {
          const id = "stream-" + (streams.size + 1);
          streams.set(id, { path, chunks: [] });
          return id;
        },
        write(id, base64) {
          const stream = streams.get(id);
          if (!stream) return false;
          stream.chunks.push(base64);
          return true;
        },
        flush: () => true,
        close(id) {
          const stream = streams.get(id);
          if (!stream) return false;
          const text = Buffer.from(stream.chunks.join(""), "base64").toString("utf8");
          files.set(stream.path, text);
          writes.push({ path: stream.path, text });
          return true;
        },
      };
    },
  };

  const ksu = {
    exec(command, optionsJson, callbackName) {
      commands.push(command);
      const tokens = command.split(/\s+/);
      const op = tokens[1];
      const handler = handlers[op];
      const result = typeof handler === "function" ? handler(command) : handler || { errno: 0, stdout: "{}", stderr: "" };
      setTimeout(() => {
        if (typeof globalThis[callbackName] === "function") {
          globalThis[callbackName](result.errno, result.stdout, result.stderr);
        }
      }, 0);
    },
  };

  if (opts.moduleInfo !== false) {
    ksu.moduleInfo = () => JSON.stringify({ id: "agentcall", moduleDir: opts.moduleDir || MODULE_DIR });
  }
  if (opts.io !== false) ksu.io = () => io;

  return { ksu, commands, files, writes, dirs, io };
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

  // 唯一一条命令，且完全固定：不接受任何用户数据作为参数。
  assert.deepEqual(fake.commands, [CTL + " config-set < " + INBOX]);
  const secretBase64 = B.toBase64Utf8(SECRET);
  for (const command of fake.commands) {
    assert.equal(command.includes(SECRET), false);
    assert.equal(command.includes(secretBase64), false);
    assert.equal(/echo|printf|base64|sh -c/.test(command), false);
  }

  // 密钥确实通过文件通道（base64 只是该 Java 桥字节写入方法的编码，不经过 shell）。
  assert.equal(fake.writes.length, 1);
  assert.equal(fake.writes[0].path, INBOX);
  const written = JSON.parse(fake.writes[0].text);
  assert.equal(written.expectedRevision, 3);
  assert.equal(written.patch.secrets.ASR.action, "replace");
  assert.equal(written.patch.secrets.ASR.value, SECRET);
  // 写后立即删除临时文件。
  assert.equal(fake.files.has(INBOX), false);

  const logText = JSON.stringify(bridge.logs());
  assert.equal(logText.includes(SECRET), false);
  assert.equal(logText.includes(secretBase64), false);
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

test("CAS 冲突不覆盖：只发一次写请求，不自动重试或抬升 revision", async () => {
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

test("模块目录不可信时拒绝执行任何命令（fail closed）", async () => {
  const fake = fakeManager({ moduleDir: "/tmp/attacker" });
  const bridge = bridgeWith(fake);
  const result = await bridge.call("status");
  assert.equal(result.ok, false);
  assert.equal(result.code, "module_dir_unresolved");
  assert.deepEqual(fake.commands, []);
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

test("不受信任的 HTML 一律按文本呈现，绝不经由 innerHTML", () => {
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
  assert.equal(B.escapeHtml('<a href="x">&</a>'), "&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;");
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

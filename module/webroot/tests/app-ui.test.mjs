import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { installDom, installGlobalDom } from "./dom-shim.mjs";
import { fakeManager, CTL, INBOX } from "./fake-manager.mjs";

const require = createRequire(import.meta.url);
const B = require("../bridge.js");

const SECRET = "sk-live-DO-NOT-LEAK-9f8e7d6c";

function redacted(overrides, secrets) {
  return JSON.stringify({
    config: Object.assign(
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
        revision: 4,
      },
      overrides || {},
    ),
    secrets: secrets || { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" },
  });
}

function tick(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms === undefined ? 10 : ms));
}

/** 以给定管理器/处理器启动 app.js；返回 DOM 句柄与 app 内部状态。 */
async function boot(options) {
  const opts = options || {};
  const fake = opts.fake;
  const dom = installDom({ ksu: fake ? fake.ksu : undefined, bridge: B, readyState: "loading" });
  installGlobalDom(dom);
  delete require.cache[require.resolve("../app.js")];
  const app = require("../app.js");
  await app.init();
  await tick(0);
  return { app, dom, fake, doc: dom.document };
}

function defaultHandlers(fakeRef, statusOverrides) {
  return {
    status: () => ({
      errno: 0,
      stdout: JSON.stringify(
        Object.assign(
          {
            bridgeVersion: "1.0",
            protocolVersion: 1,
            revision: 4,
            enabled: false,
            paired: true,
            uptimeMs: 65000,
            activeConnections: 0,
            rejectedConnections: 0,
          },
          statusOverrides || {},
        ),
      ),
      stderr: "",
    }),
    "config-get": () => ({ errno: 0, stdout: redacted({}, { ASR: "CONFIGURED", LLM: "MISSING", TTS: "MISSING" }), stderr: "" }),
    diagnose: () => ({
      errno: 0,
      stdout: JSON.stringify({
        environment: {
          apiLevel: 37,
          romFingerprint: "XQ-DQ72/demo",
          kernelVersion: "5.15.0-demo",
          dialerPackageVersion: "120.0-demo",
          frameworkVersion: "82",
          bridgeVersion: "1.0",
          audioPolicyDigest: "abc123",
          simCarrier: "Demo",
          transport: "CELLULAR",
          route: "EARPIECE",
        },
        downlinkExtraction: "INTERFACE_VISIBLE",
        uplinkInjection: "NOT_DETECTED",
        microphoneIsolation: "NOT_DETECTED",
        privilegedTermination: "NOT_DETECTED",
        checkedAtMs: 1700000000000,
        notes: "demo",
      }),
      stderr: "",
    }),
    "config-set": () => {
      const fake = fakeRef.current;
      const last = fake.writes[fake.writes.length - 1];
      const payload = last ? JSON.parse(last.text) : { expectedRevision: 0, patch: {} };
      const config = Object.assign(
        { enabled: false, revision: payload.expectedRevision + 1, asr: null, llm: null, tts: null },
        payload.patch,
        { revision: payload.expectedRevision + 1 },
      );
      const secrets = { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" };
      const intents = (payload.patch && payload.patch.secrets) || {};
      for (const kind of ["ASR", "LLM", "TTS"]) {
        if (intents[kind] && intents[kind].action === "replace") secrets[kind] = "CONFIGURED";
      }
      if (payload.patch && payload.patch.asr) config.asr = payload.patch.asr;
      return { errno: 0, stdout: redacted(config, secrets), stderr: "" };
    },
  };
}

test("KernelSU 真实路径：init 后无需任何页面保存即可保存总开关（Critical 1）", async () => {
  const holder = {};
  const fake = fakeManager({ handlers: defaultHandlers(holder) });
  holder.current = fake;
  const { doc } = await boot({ fake });

  const switchButton = doc.getElementById("ov-save-switch");
  assert.equal(switchButton.disabled, false, "配置加载完成后总开关保存按钮必须可用，不能永久 disabled");

  doc.getElementById("ov-enabled").checked = true;
  switchButton.click();
  await tick(20);

  const configSetCommands = fake.commands.filter((command) => command.indexOf(" config-set ") !== -1);
  assert.equal(configSetCommands.length, 1, "总开关必须真的发起一次 config-set");
  assert.equal(configSetCommands[0], CTL + " config-set < " + INBOX);
  assert.equal(fake.writes.length, 1);
  const payload = JSON.parse(fake.writes[0].text);
  assert.deepEqual(payload, { expectedRevision: 4, patch: { enabled: true } });
  assert.equal("secrets" in payload.patch, false);
  assert.equal(switchButton.disabled, false, "保存完成后按钮必须重新可用");
});

test("配置加载失败时保存按钮保持禁用；加载前点停用不会抛 TypeError（Minor 守卫）", async () => {
  const fake = fakeManager({
    handlers: {
      status: { errno: 0, stdout: JSON.stringify({ bridgeVersion: "1.0", protocolVersion: 1, revision: 0, enabled: false, paired: true }), stderr: "" },
      "config-get": { errno: 1, stdout: JSON.stringify({ error: { code: "config_corrupt", message: "config file corrupt" } }), stderr: "" },
      diagnose: { errno: 1, stdout: JSON.stringify({ error: { code: "transport_failed", message: "unreachable" } }), stderr: "" },
    },
  });
  const { doc, app } = await boot({ fake });

  for (const id of ["ov-save-switch", "rules-save", "ai-save", "dlg-save"]) {
    assert.equal(doc.getElementById(id).disabled, true, id + " 在配置未加载时必须禁用");
  }
  assert.equal(app.state.baseline, null);

  // 模拟“config-get 尚未解析就点停用”：不应因为 state.form 为 null 抛错。
  doc.getElementById("diag-disable").click();
  await tick(10);
  assert.equal(
    fake.commands.filter((command) => command.indexOf(" config-set ") !== -1).length,
    0,
    "未加载配置时停用不得写入",
  );
});

test("演示态：保存按钮全部禁用，点击不发命令", async () => {
  const { doc } = await boot({});
  for (const id of ["ov-save-switch", "rules-save", "ai-save", "dlg-save"]) {
    assert.equal(doc.getElementById(id).disabled, true, id + " 演示态必须禁用");
  }
  doc.getElementById("rules-save").click();
  await tick(10);
  assert.equal(doc.getElementById("toast").textContent.includes("不能保存"), true);
});

test("页面渲染真实状态与诊断数据（textContent，不用 innerHTML）", async () => {
  const holder = {};
  const fake = fakeManager({ handlers: defaultHandlers(holder) });
  holder.current = fake;
  const { doc } = await boot({ fake });

  assert.match(doc.getElementById("ov-bridge-state").textContent, /status 成功/);
  assert.equal(doc.getElementById("ov-bridge-version").textContent, "1.0");
  assert.equal(doc.getElementById("ov-protocol-version").textContent, "1");
  assert.equal(doc.getElementById("ov-paired").textContent, "已配对");
  assert.equal(doc.getElementById("ov-uptime").textContent, "1 分 5 秒");

  const env = doc.getElementById("diag-env");
  assert.ok(env.children.length >= 10, "诊断环境必须渲染出真实行");
  assert.equal(env.children[1].textContent, "37");
  assert.match(env.children[3].textContent, /XQ-DQ72/);
  assert.match(env.children[7].textContent, /120\.0-demo/);

  // AI 页保存非法 URL：阻断项必须渲染到 AI 页与对应端点卡片。
  doc.getElementById("ai-asr-url").value = "http://insecure.example.com";
  doc.getElementById("ai-asr-model").value = "whisper-1";
  doc.getElementById("ai-save").click();
  await tick(20);
  const rendered = doc.getElementById("ai-asr-issues").children.map((item) => item.textContent).join(" | ");
  assert.match(rendered, /HTTPS/);
  assert.equal(
    fake.commands.filter((command) => command.indexOf(" config-set ") !== -1).length,
    0,
    "前端校验阻止时必须不写后端",
  );
});

test("页面保存只提交本页字段：规则页保存不得夹带 AI 密钥", async () => {
  const holder = {};
  const fake = fakeManager({ handlers: defaultHandlers(holder) });
  holder.current = fake;
  const { doc } = await boot({ fake });

  // 在 AI 页输入一个密钥（未保存），然后去规则页保存。
  doc.getElementById("ai-asr-replace").click();
  doc.getElementById("ai-asr-secret").value = SECRET;
  doc.getElementById("ai-asr-url").value = "https://api.example.com/v1";
  doc.getElementById("ai-asr-model").value = "whisper-1";

  doc.getElementById("rules-save").click();
  await tick(20);

  assert.equal(fake.writes.length, 1);
  const rulesPayload = JSON.parse(fake.writes[0].text);
  assert.equal("secrets" in rulesPayload.patch, false, "规则页保存不得携带密钥意图");
  assert.equal("asr" in rulesPayload.patch, false, "规则页保存不得携带 AI 端点");
  for (const key of ["mode", "blacklist", "whitelist", "region", "handleUnknownNumbers", "answerDelayMs", "maxCallDurationMs"]) {
    assert.ok(key in rulesPayload.patch, "规则页补丁应包含 " + key);
  }
  assert.equal("enabled" in rulesPayload.patch, false, "页面保存不写总开关（开关独立保存）");

  doc.getElementById("ai-save").click();
  await tick(20);
  assert.equal(fake.writes.length, 2);
  const aiPayload = JSON.parse(fake.writes[1].text);
  assert.equal(aiPayload.patch.secrets.ASR.action, "replace");
  assert.equal(aiPayload.patch.secrets.ASR.value, SECRET);
  assert.equal(aiPayload.patch.asr.baseUrl, "https://api.example.com/v1");
  assert.equal("mode" in aiPayload.patch, false, "AI 页保存不得携带规则字段");

  // 密钥从未进入命令行。
  for (const command of fake.commands) {
    assert.equal(command.includes(SECRET), false);
    assert.equal(command.includes(B.toBase64Utf8(SECRET)), false);
  }
});

test("概览的默认拨号角色/Hook 连通性：后端未提供时显示未知，提供真实值时按值渲染", async () => {
  const holder = {};
  const fake = fakeManager({ handlers: defaultHandlers(holder) });
  holder.current = fake;
  const first = await boot({ fake });
  assert.match(first.doc.getElementById("ov-dialer-role").textContent, /未知/);
  assert.match(first.doc.getElementById("ov-hook-state").textContent, /未知/);

  const holder2 = {};
  const fake2 = fakeManager({
    handlers: defaultHandlers(holder2, { dialerDefaultRole: true, hookConnected: false }),
  });
  holder2.current = fake2;
  const second = await boot({ fake: fake2 });
  assert.equal(second.doc.getElementById("ov-dialer-role").textContent, "已设为默认拨号器");
  assert.equal(second.app.state.status.hookConnected, false);
  assert.match(second.doc.getElementById("ov-hook-state").textContent, /未连接/);
});


test("移动端顶部布局：tabs 不再硬编码 56px 偏移，头部与 tabs 同处一个 sticky 容器", () => {
  const webroot = dirname(fileURLToPath(import.meta.url)) + "/..";
  const css = readFileSync(join(webroot, "styles.css"), "utf8");
  const html = readFileSync(join(webroot, "index.html"), "utf8");

  assert.equal(/top:\s*calc\(56px/.test(css), false, "不得再硬编码 app-header 高度作为 tabs 偏移");
  assert.match(html, /class="topbar"[\s\S]*class="app-header"[\s\S]*class="tabs"/, "头部与 tabs 必须共享 sticky 容器");
  assert.match(css, /\.topbar\s*\{[^}]*position:\s*sticky;[^}]*top:\s*0/, "只有 topbar 负责 sticky");
  assert.equal(/\.tabs\s*\{[^}]*position:\s*sticky/.test(css), false, "tabs 不再单独 sticky，避免与头部 z-index 相互覆盖");
  assert.match(css, /\.app-header\s*\{[^}]*position:\s*static/, "头部在 topbar 内改回静态定位");
});

test("死代码清理：未渲染的 issues 容器已移除，保存按钮初始为禁用（加载后再启用）", () => {
  const webroot = dirname(fileURLToPath(import.meta.url)) + "/..";
  const html = readFileSync(join(webroot, "index.html"), "utf8");
  assert.equal(/id="ov-issues"/.test(html), false, "未渲染的 #ov-issues 必须移除");
  assert.equal(/id="diag-issues"/.test(html), false, "未渲染的 #diag-issues 必须移除");
  for (const id of ["ov-save-switch", "rules-save", "ai-save", "dlg-save"]) {
    const pattern = new RegExp('<button[^>]*id="' + id + '"[^>]*\\bdisabled\\b');
    assert.match(html, pattern, id + " 必须在配置加载完成前保持禁用");
  }
});

test("连接测试归属：WebUI 只显示诚实状态，明确真实请求由 AgentCall APK 诊断页执行", () => {
  const webroot = dirname(fileURLToPath(import.meta.url)) + "/..";
  const html = readFileSync(join(webroot, "index.html"), "utf8");
  const app = readFileSync(join(webroot, "app.js"), "utf8");
  assert.match(html, /连接测试在 AgentCall APK 诊断页执行，需真实远程 API 请求/);
  assert.match(app, /AgentCall APK 诊断页/);
  assert.equal(/WebUI (执行|发起)连接测试/.test(html + app), false, "不得宣称 WebUI 执行连接测试");
  assert.equal(/本页(会)?发起真实请求/.test(html + app), false);
});



test("saveEnabledOnly 在 state.form 缺失时也不抛错（Minor 守卫）", async () => {
  const holder = {};
  const fake = fakeManager({ handlers: defaultHandlers(holder) });
  holder.current = fake;
  const { app, doc } = await boot({ fake });

  app.state.form = null;
  doc.getElementById("ov-enabled").checked = true;
  await app.saveEnabledOnly();
  await tick(20);

  assert.equal(fake.writes.length, 1);
  const payload = JSON.parse(fake.writes[0].text);
  assert.deepEqual(payload, { expectedRevision: 4, patch: { enabled: true } });
});

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fakeManager, CTL } from "./fake-manager.mjs";

const require = createRequire(import.meta.url);
const B = require("../bridge.js");

const ENV_CHUNK_SIZE = 128;
const ENV_CHUNK_CAP = 64;
const ENV_PROBE_CHUNKS = 3;
const SECRET = "sk-live-chunked-DO-NOT-LEAK-7a1b2c3d";

function envChunk(name, index) {
  return '"$' + name + "_" + index + '"';
}

function expectedConfigCommand(chunks) {
  const args = [];
  for (let index = 0; index < chunks; index += 1) args.push(envChunk("AGENTCALL_CFG_PAYLOAD", index));
  return "printf '%s' " + args.join(" ") + " | " + CTL + " config-set";
}

function expectedProbeCommand() {
  const args = [];
  for (let index = 0; index < ENV_PROBE_CHUNKS; index += 1) args.push(envChunk("AGENTCALL_PROBE", index));
  return "printf '%s' " + args.join(" ");
}

function chunked(fake, index, name = "AGENTCALL_CFG_PAYLOAD") {
  const env = fake.envs[index] || {};
  const pattern = new RegExp("^" + name + "_(\\d+)$");
  return Object.keys(env)
    .map((key) => {
      const match = pattern.exec(key);
      return match ? { index: Number(match[1]), value: env[key] } : null;
    })
    .filter(Boolean)
    .sort((left, right) => left.index - right.index);
}

function redacted(revision, secrets) {
  return JSON.stringify({
    config: {
      enabled: true,
      mode: "BLACKLIST",
      blacklist: [],
      whitelist: [],
      region: "CN",
      handleUnknownNumbers: false,
      answerDelayMs: 3000,
      maxCallDurationMs: 180000,
      enabledPhoneAccounts: [],
      asr: { baseUrl: "https://asr.example.com/v1", model: "asr-large" },
      llm: { baseUrl: "https://llm.example.com/v1", model: "chat-large" },
      tts: { baseUrl: "https://tts.example.com/v1", model: "voice-large" },
      voice: "zh-CN-XiaoxiaoNeural",
      speed: 1.0,
      openingLine: "您好，我是机主的 AI 电话助理。",
      prompt: "",
      revision,
    },
    secrets: secrets || { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" },
  });
}

function largeWrite() {
  const openingLine = "您好，我是机主的 AI 电话助理，请问有什么事？".repeat(18);
  const prompt = "你是电话助理。遇到问题先确认身份，再记录需求。参考 https://example.com/policy 与 https://example.com/faq 。".repeat(12);
  return {
    expectedRevision: 7,
    patch: {
      asr: { baseUrl: "https://asr.example.com/v1", model: "asr-large" },
      llm: { baseUrl: "https://llm.example.com/v1", model: "chat-large" },
      tts: { baseUrl: "https://tts.example.com/v1", model: "voice-large" },
      voice: "zh-CN-XiaoxiaoNeural",
      openingLine,
      prompt,
      secrets: {
        ASR: { action: "replace", value: SECRET },
        LLM: { action: "keep" },
        TTS: { action: "clear" },
      },
    },
  };
}

function bridgeWith(fake) {
  return B.createBridge({ scope: { ksu: fake.ksu } });
}

test("2 KB 中文+URL 负载经分块 env 逐字节往返：CLI stdin 与 write JSON 完全一致", async () => {
  const fake = fakeManager({
    io: false,
    handlers: {
      "config-set": () => ({
        errno: 0,
        stdout: redacted(8, { ASR: "CONFIGURED", LLM: "MISSING", TTS: "MISSING" }),
        stderr: "",
      }),
    },
  });
  const bridge = bridgeWith(fake);
  const write = largeWrite();
  const result = await bridge.call("config-set", write);

  assert.equal(result.ok, true);
  assert.equal(result.data.config.revision, 8);

  const chunks = chunked(fake, 1);
  assert.ok(chunks.length > 1, "2 KB 负载必须拆成多块");
  assert.ok(chunks.length <= ENV_CHUNK_CAP);
  assert.deepEqual(chunks.map((entry) => entry.index), Array.from({ length: chunks.length }, (_, index) => index));
  assert.equal(chunks.map((entry) => entry.value).join(""), JSON.stringify(write));
  assert.equal(fake.envPayloads[0], JSON.stringify(write));
  assert.equal(fake.commands[1], expectedConfigCommand(chunks.length));
  assert.deepEqual(fake.commands, [expectedProbeCommand(), expectedConfigCommand(chunks.length)]);

  for (const chunk of chunks) {
    assert.ok(Buffer.byteLength(chunk.value, "utf8") <= ENV_CHUNK_SIZE * 3);
    assert.equal(chunk.value.length <= ENV_CHUNK_SIZE, true, "每个分块不得超过 128 UTF-16 码元");
  }
  for (const command of fake.commands) {
    assert.equal(command.includes(SECRET), false);
    assert.equal(command.includes("https://"), false);
    assert.equal(command.includes("您好"), false);
  }
  assert.equal(JSON.stringify(bridge.logs()).includes(SECRET), false);
});

test("分块边界不拆代理对：emoji 只完整落在单个分块里", async () => {
  const fake = fakeManager({
    io: false,
    handlers: {
      "config-set": () => ({
        errno: 0,
        stdout: redacted(4, { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" }),
        stderr: "",
      }),
    },
  });
  const bridge = bridgeWith(fake);
  const write = {
    expectedRevision: 3,
    patch: { openingLine: "😀".repeat(80) + "中文结尾", prompt: "p" },
  };
  const result = await bridge.call("config-set", write);
  assert.equal(result.ok, true);

  const chunks = chunked(fake, 1);
  assert.ok(chunks.length > 1);
  assert.equal(chunks.map((entry) => entry.value).join(""), JSON.stringify(write));
  for (const chunk of chunks) {
    assert.equal(chunk.value.length <= ENV_CHUNK_SIZE, true);
    const first = chunk.value.charCodeAt(0);
    const last = chunk.value.charCodeAt(chunk.value.length - 1);
    assert.equal(first >= 0xdc00 && first <= 0xdfff, false, "分块不得以低位代理开头");
    assert.equal(last >= 0xd800 && last <= 0xdbff, false, "分块不得以高位代理结尾");
  }
  assert.equal(fake.envPayloads[0].includes("😀"), true);
});

test("env 值被管理器截断：探测 fail closed 为 env_unavailable，绝不执行 config-set", async () => {
  const fake = fakeManager({ io: false, envValueMaxChars: 100 });
  const bridge = bridgeWith(fake);

  assert.equal(await bridge.probeEnvTransport(), false);
  assert.equal(bridge.mode, "readonly");
  assert.equal(bridge.support.reason, "env_unavailable");
  assert.equal(bridge.support.ioMode, "none");
  assert.deepEqual(fake.commands, [expectedProbeCommand()]);

  const save = await bridge.call("config-set", { expectedRevision: 0, patch: { enabled: true } });
  assert.equal(save.ok, false);
  assert.equal(save.code, "secret_transport_unsupported");
  assert.equal(fake.commands.some((command) => /\sconfig-set$/.test(command)), false);
  assert.equal(fake.envPayloads.length, 0);
});

test("缺少任一分块环境变量：探测不匹配 => env_unavailable", async () => {
  const fake = fakeManager({ io: false, envDropKeys: ["AGENTCALL_PROBE_1"] });
  const bridge = bridgeWith(fake);

  assert.equal(await bridge.probeEnvTransport(), false);
  assert.equal(bridge.support.envProbe, "unsupported");
  assert.equal(bridge.support.reason, "env_unavailable");
  assert.deepEqual(fake.commands, [expectedProbeCommand()]);
  assert.equal(fake.envPayloads.length, 0);
});

test("超过 64 块上限：显式 payload_too_large，命令不执行，诊断只含字节/块数", async () => {
  const fake = fakeManager({ io: false });
  const bridge = bridgeWith(fake);
  const write = {
    expectedRevision: 0,
    patch: { openingLine: "x".repeat(ENV_CHUNK_SIZE * ENV_CHUNK_CAP + 1) },
  };
  const result = await bridge.call("config-set", write);

  assert.equal(result.ok, false);
  assert.equal(result.code, "payload_too_large");
  assert.match(result.message, /transport=env bytes=\d+ chunks=\d+/);
  assert.match(result.message, /64/);
  assert.equal(result.message.includes("xxx"), false, "诊断不得包含负载内容");
  assert.deepEqual(fake.commands, [], "cap 失败必须在探测/执行前拒绝");
  assert.equal(fake.envPayloads.length, 0);
});

test("后端失败消息带 transport=env bytes/chunks 诊断，且不含负载内容", async () => {
  const fake = fakeManager({
    io: false,
    handlers: {
      "config-set": () => ({
        errno: 0,
        stdout: JSON.stringify({ error: { code: "config_invalid", message: "config rejected: RULE_NUMBER_UNPARSEABLE@blacklist" } }),
        stderr: "",
      }),
    },
  });
  const bridge = bridgeWith(fake);
  const result = await bridge.call("config-set", {
    expectedRevision: 0,
    patch: { openingLine: "诊断用中文" },
  });

  assert.equal(result.ok, false);
  assert.equal(result.code, "config_invalid");
  assert.match(result.message, /transport=env bytes=\d+ chunks=\d+/);
  assert.equal(result.message.includes("诊断用中文"), false);
});

test("小负载仍走第 0 块并成功保存（单块边界）", async () => {
  const fake = fakeManager({
    io: false,
    handlers: {
      "config-set": () => ({
        errno: 0,
        stdout: redacted(8, { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" }),
        stderr: "",
      }),
    },
  });
  const bridge = bridgeWith(fake);
  const write = { expectedRevision: 7, patch: { enabled: true } };
  const result = await bridge.call("config-set", write);

  assert.equal(result.ok, true);
  const chunks = chunked(fake, 1);
  assert.equal(chunks.length, 1);
  assert.equal(chunks[0].value, JSON.stringify(write));
  assert.deepEqual(fake.commands, [expectedProbeCommand(), expectedConfigCommand(1)]);
});

test("transport 模块导出的分块契约常量固定", () => {
  assert.equal(B.ENV_CHUNK_SIZE, ENV_CHUNK_SIZE);
  assert.equal(B.ENV_CHUNK_CAP, ENV_CHUNK_CAP);
  assert.equal(B.ENV_PROBE_CHUNKS, ENV_PROBE_CHUNKS);
});

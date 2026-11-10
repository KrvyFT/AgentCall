import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const B = require("../bridge.js");

const SECRET = "sk-live-DO-NOT-LEAK-9f8e7d6c";
const CTL = "/data/adb/modules/agentcall/bin/agentcallctl";

function redacted(overrides, secrets) {
  return {
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
  };
}

function newForm(overrides, secrets) {
  const form = B.formFromRedacted(redacted(overrides, secrets));
  return form;
}

function codes(issues) {
  return issues.map((issue) => issue.code);
}

test("脱敏配置转表单：密钥只有状态，表单里没有密钥原值", () => {
  const form = newForm({ revision: 9 }, { ASR: "CONFIGURED", LLM: "MISSING", TTS: "CONFIGURED" });
  assert.equal(form.asr.secretState, "CONFIGURED");
  assert.equal(form.asr.secretIntent, "keep");
  assert.equal(form.asr.secretValue, "");
  assert.equal(form.llm.secretState, "MISSING");
  assert.equal(JSON.stringify(form).includes("sk-"), false);
});

test("密钥 Keep 不被空字段清除", () => {
  const form = newForm({ revision: 6 }, { ASR: "CONFIGURED", LLM: "CONFIGURED", TTS: "CONFIGURED" });
  // 用户打开了 AI 页但没有填任何密钥字段
  form.asr.secretValue = "";
  form.llm.secretValue = "";
  form.tts.secretValue = "";

  const built = B.buildConfigPatch(form, redacted({ revision: 6 }));
  assert.equal(built.ok, true);
  const secrets = built.write.patch.secrets;
  for (const kind of ["ASR", "LLM", "TTS"]) {
    assert.deepEqual(secrets[kind], { action: "keep" });
  }
  assert.equal(JSON.stringify(built.write.patch).includes("clear"), false);
  assert.equal(JSON.stringify(built.write.patch).includes(SECRET), false);
});

test("密钥替换与清除是显式动作，空白替换被本地阻止", () => {
  const form = newForm({ revision: 6 }, { ASR: "CONFIGURED" });
  form.asr.secretIntent = "replace";
  form.asr.secretValue = SECRET;
  form.tts.secretIntent = "clear";
  let built = B.buildConfigPatch(form, redacted({ revision: 6 }));
  assert.equal(built.ok, true);
  assert.deepEqual(built.write.patch.secrets.ASR, { action: "replace", value: SECRET });
  assert.deepEqual(built.write.patch.secrets.TTS, { action: "clear" });
  assert.deepEqual(built.write.patch.secrets.LLM, { action: "keep" });

  const blank = newForm({ revision: 6 });
  blank.llm.secretIntent = "replace";
  blank.llm.secretValue = "   ";
  const blocked = B.buildConfigPatch(blank, redacted({ revision: 6 }));
  assert.equal(blocked.ok, false);
  assert.ok(codes(blocked.blocking).includes("SECRET_EMPTY"));
  assert.equal(blocked.write, undefined);
});

test("每次保存都带 expectedRevision 并走后端 CAS 期望值", () => {
  const form = newForm({ revision: 12 });
  form.enabled = true;
  const built = B.buildConfigPatch(form, redacted({ revision: 12 }));
  assert.equal(built.ok, true);
  assert.equal(built.write.expectedRevision, 12);
  assert.equal(built.write.patch.enabled, true);
  assert.equal(Object.keys(built.write).sort().join(","), "expectedRevision,patch");
});

test("非法 URL、非 HTTPS 与凭据内嵌地址阻止保存", () => {
  const cases = [
    ["http://api.example.com/v1", "ENDPOINT_HTTPS_REQUIRED"],
    ["javascript:alert(1)", "ENDPOINT_HTTPS_REQUIRED"],
    ["api.example.com/v1", "ENDPOINT_URL_INVALID"],
    ["https://user:pass@api.example.com", "ENDPOINT_URL_INVALID"],
    ["https://", "ENDPOINT_URL_INVALID"],
    ["ftp://api.example.com", "ENDPOINT_HTTPS_REQUIRED"],
  ];
  for (const [url, expected] of cases) {
    const form = newForm();
    form.asr.baseUrl = url;
    form.asr.model = "whisper-1";
    const built = B.buildConfigPatch(form, redacted());
    assert.equal(built.ok, false, url + " 应当被阻止");
    assert.ok(codes(built.blocking).includes(expected), url + " 期望 " + expected + "，实际 " + codes(built.blocking));
  }

  const okForm = newForm();
  okForm.asr.baseUrl = "https://api.openai.com/v1";
  okForm.asr.model = "whisper-1";
  const ok = B.buildConfigPatch(okForm, redacted());
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.write.patch.asr, { baseUrl: "https://api.openai.com/v1", model: "whisper-1" });
});

test("端点必须同时填写地址与模型；同时留空表示清除端点", () => {
  const half = newForm();
  half.tts.baseUrl = "https://tts.example.com/v1";
  const built = B.buildConfigPatch(half, redacted());
  assert.equal(built.ok, false);
  assert.ok(codes(built.blocking).includes("ENDPOINT_INCOMPLETE"));

  const empty = newForm();
  const cleared = B.buildConfigPatch(empty, redacted());
  assert.equal(cleared.ok, true);
  assert.deepEqual(cleared.write.patch.asr, { baseUrl: "", model: "" });
  assert.deepEqual(cleared.write.patch.tts, { baseUrl: "", model: "" });
});

test("名单规范化后冲突阻止保存", () => {
  const form = newForm();
  form.blacklistText = "+8613800138000";
  form.whitelistText = "+86 138 0013 8000";
  form.mode = "COMBINED";
  const built = B.buildConfigPatch(form, redacted());
  assert.equal(built.ok, false);
  assert.ok(codes(built.blocking).includes("RULE_NUMBER_OVERLAP"));
  assert.equal(built.write, undefined);

  const separated = newForm();
  separated.blacklistText = "+8613800138000\n+8613900139000";
  separated.whitelistText = "+8613700137000";
  const ok = B.buildConfigPatch(separated, redacted());
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.write.patch.blacklist, ["+8613800138000", "+8613900139000"]);
});

test("无法解析的名单项阻止保存", () => {
  const form = newForm();
  form.blacklistText = "not-a-number";
  const built = B.buildConfigPatch(form, redacted());
  assert.equal(built.ok, false);
  assert.ok(codes(built.blocking).includes("RULE_NUMBER_UNPARSEABLE"));
});

test("空白名单在白名单/组合模式下给出醒目警示但不阻止保存", () => {
  for (const mode of ["WHITELIST", "COMBINED"]) {
    const form = newForm();
    form.mode = mode;
    form.whitelistText = "";
    const built = B.buildConfigPatch(form, redacted());
    assert.equal(built.ok, true, mode + " 不应阻止保存");
    const warning = built.warnings.find((issue) => issue.code === "WHITELIST_EMPTY");
    assert.ok(warning, mode + " 必须提示空白名单");
    assert.equal(warning.blocking, false);
    assert.match(warning.message, /白名单/);
  }

  const blacklistMode = newForm();
  blacklistMode.blacklistText = "";
  const built = B.buildConfigPatch(blacklistMode, redacted());
  assert.equal(built.ok, true);
  assert.equal(codes(built.warnings).includes("WHITELIST_EMPTY"), false);
  assert.ok(codes(built.warnings).includes("BLACKLIST_EMPTY"));
});

test("延迟与最长通话时长按 spec 范围校验", () => {
  const cases = [
    ["answerDelayMs", "30001", "ANSWER_DELAY_OUT_OF_RANGE"],
    ["answerDelayMs", "-1", "ANSWER_DELAY_OUT_OF_RANGE"],
    ["answerDelayMs", "3.5", "ANSWER_DELAY_NOT_INTEGER"],
    ["maxCallDurationMs", "9999", "MAX_CALL_DURATION_OUT_OF_RANGE"],
    ["maxCallDurationMs", "600001", "MAX_CALL_DURATION_OUT_OF_RANGE"],
  ];
  for (const [field, value, expected] of cases) {
    const form = newForm();
    form[field] = value;
    const built = B.buildConfigPatch(form, redacted());
    assert.equal(built.ok, false, field + "=" + value);
    assert.ok(codes(built.blocking).includes(expected), field + "=" + value + " 实际 " + codes(built.blocking));
  }

  const boundary = newForm();
  boundary.answerDelayMs = "0";
  boundary.maxCallDurationMs = "600000";
  const ok = B.buildConfigPatch(boundary, redacted());
  assert.equal(ok.ok, true);
  assert.equal(ok.write.patch.answerDelayMs, 0);
  assert.equal(ok.write.patch.maxCallDurationMs, 600000);
});

test("地区码规范化与非法地区阻止保存", () => {
  const lower = newForm();
  lower.region = " cn ";
  const ok = B.buildConfigPatch(lower, redacted());
  assert.equal(ok.ok, true);
  assert.equal(ok.write.patch.region, "CN");

  for (const region of ["CHINA", "1A", ""]) {
    const form = newForm();
    form.region = region;
    const built = B.buildConfigPatch(form, redacted());
    assert.equal(built.ok, false, region);
    assert.ok(codes(built.blocking).includes("REGION_UNSUPPORTED"), region);
  }
});

test("语速越界与非法数值阻止保存，音色与开场白保留文本", () => {
  for (const speed of ["2.5", "0.1", "abc", ""]) {
    const form = newForm();
    form.speed = speed;
    const built = B.buildConfigPatch(form, redacted());
    assert.equal(built.ok, false, "speed=" + speed);
    assert.ok(codes(built.blocking).some((code) => code === "SPEED_INVALID" || code === "SPEED_OUT_OF_RANGE"), "speed=" + speed);
  }

  const form = newForm();
  form.speed = "1.25";
  form.voice = "alloy";
  form.openingLine = "您好，这里是助理。";
  form.prompt = "只做对话，不执行任何设备命令。";
  const built = B.buildConfigPatch(form, redacted());
  assert.equal(built.ok, true);
  assert.equal(built.write.patch.speed, 1.25);
  assert.equal(built.write.patch.voice, "alloy");
  assert.equal(built.write.patch.openingLine, "您好，这里是助理。");
  assert.equal(built.write.patch.prompt, "只做对话，不执行任何设备命令。");
});

test("补丁键集合与后端 RpcCodec.PATCH_KEYS 完全一致（漂移即失败）", () => {
  assert.deepEqual(
    B.PATCH_KEYS.slice().sort(),
    [
      "enabled",
      "mode",
      "blacklist",
      "whitelist",
      "region",
      "handleUnknownNumbers",
      "answerDelayMs",
      "maxCallDurationMs",
      "enabledPhoneAccounts",
      "asr",
      "llm",
      "tts",
      "voice",
      "speed",
      "openingLine",
      "prompt",
      "secrets",
    ].sort(),
  );

  const built = B.buildConfigPatch(newForm(), redacted());
  assert.equal(built.ok, true);
  for (const key of Object.keys(built.write.patch)) {
    assert.ok(B.PATCH_KEYS.includes(key), "补丁不得包含后端未知字段：" + key);
  }
  // 页面不编辑 PhoneAccount，因此不得在补丁里覆盖它。
  assert.equal("enabledPhoneAccounts" in built.write.patch, false);
});

test("CAS 冲突：不覆盖、不重试、不抬升基线 revision", async () => {
  const calls = [];
  const bridge = {
    mode: "kernelsu",
    call(operation, payload) {
      calls.push({ operation, payload });
      return Promise.resolve({ ok: false, code: "config_conflict", message: "config revision conflict: expected 4, current 7", conflict: true, source: "bridge" });
    },
  };
  const form = newForm({ revision: 4 });
  form.enabled = true;
  const baseline = redacted({ revision: 4 });
  const result = await B.saveConfig(bridge, form, baseline);

  assert.equal(result.ok, false);
  assert.equal(result.code, "config_conflict");
  assert.equal(result.conflict, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].payload.expectedRevision, 4);
  assert.ok(codes(result.issues).includes("CONFIG_CONFLICT"));

  // 冲突后再次构建补丁仍使用旧基线，绝不静默抬高 revision 去覆盖他人更新。
  const again = B.buildConfigPatch(form, baseline);
  assert.equal(again.write.expectedRevision, 4);
});

test("后端 config_invalid 的机器可读问题被映射回表单并阻止启用", async () => {
  const bridge = {
    mode: "kernelsu",
    call() {
      return Promise.resolve({
        ok: false,
        code: "config_invalid",
        message: "config rejected: RULE_NUMBER_OVERLAP@blacklist,whitelist,REGION_UNSUPPORTED@region",
        source: "bridge",
      });
    },
  };
  const form = newForm({ revision: 1 });
  form.enabled = true;
  const result = await B.saveConfig(bridge, form, redacted({ revision: 1 }));
  assert.equal(result.ok, false);
  assert.equal(result.code, "config_invalid");
  const issues = result.issues.filter((issue) => issue.blocking);
  assert.ok(issues.some((issue) => issue.code === "RULE_NUMBER_OVERLAP" && issue.field === "blacklist,whitelist"));
  assert.ok(issues.some((issue) => issue.code === "REGION_UNSUPPORTED" && issue.field === "region"));
});

test("pairing 失效错误带重新配对提示", async () => {
  const bridge = {
    mode: "kernelsu",
    call() {
      return Promise.resolve({ ok: false, code: "peer_version_mismatch", message: "package version changed since pairing; re-pair required", source: "bridge" });
    },
  };
  const form = newForm({ revision: 3 });
  const result = await B.saveConfig(bridge, form, redacted({ revision: 3 }));
  assert.equal(result.ok, false);
  assert.equal(result.repair, true);
  assert.ok(codes(result.issues).includes("PEER_VERSION_MISMATCH"));
  assert.equal(B.isRepairRequired("peer_version_mismatch"), true);
  assert.equal(B.isRepairRequired("peer_unpaired"), true);
  assert.equal(B.isRepairRequired("config_conflict"), false);
});

test("总开关保存只提交 enabled，不夹带其它表单字段或密钥意图", async () => {
  const built = B.buildEnabledPatch(true, redacted({ revision: 7 }));
  assert.equal(built.ok, true);
  assert.deepEqual(built.write, { expectedRevision: 7, patch: { enabled: true } });
  assert.equal(JSON.stringify(built.write).includes("secrets"), false);
  assert.equal(Object.keys(built.write.patch).length, 1);

  const missing = B.buildEnabledPatch(false, null);
  assert.deepEqual(missing.write, { expectedRevision: 0, patch: { enabled: false } });

  assert.equal(B.buildEnabledPatch("true", redacted()).ok, false);

  const calls = [];
  const bridge = {
    mode: "kernelsu",
    call(operation, payload) {
      calls.push({ operation, payload });
      return Promise.resolve({ ok: true, source: "bridge", data: redacted({ revision: 8, enabled: true }) });
    },
  };
  const result = await B.saveEnabled(bridge, true, redacted({ revision: 7 }));
  assert.equal(result.ok, true);
  assert.equal(result.form.enabled, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].operation, "config-set");
  assert.deepEqual(calls[0].payload, { expectedRevision: 7, patch: { enabled: true } });
  assert.equal(JSON.stringify(calls[0].payload).includes("secrets"), false);
});

test("总开关保存的 CAS 冲突与配对失效仍然映射为可操作反馈", async () => {
  const conflictBridge = {
    mode: "kernelsu",
    call() {
      return Promise.resolve({ ok: false, code: "config_conflict", message: "config revision conflict", conflict: true, source: "bridge" });
    },
  };
  const conflict = await B.saveEnabled(conflictBridge, true, redacted({ revision: 4 }));
  assert.equal(conflict.ok, false);
  assert.equal(conflict.conflict, true);
  assert.ok(codes(conflict.issues).includes("CONFIG_CONFLICT"));

  const repairBridge = {
    mode: "kernelsu",
    call() {
      return Promise.resolve({ ok: false, code: "peer_version_mismatch", message: "re-pair required", source: "bridge" });
    },
  };
  const repair = await B.saveEnabled(repairBridge, false, redacted({ revision: 4 }));
  assert.equal(repair.repair, true);
  assert.ok(codes(repair.issues).includes("PEER_VERSION_MISMATCH"));
});

test("普通浏览器（演示态）保存失败且不产生任何命令", async () => {
  const bridge = B.createBridge({ scope: {} });
  const form = newForm({ revision: 0 });
  form.enabled = true;
  const result = await B.saveConfig(bridge, form, redacted({ revision: 0 }));
  assert.equal(result.ok, false);
  assert.equal(result.code, "bridge_unavailable");
  assert.equal(result.data, undefined);
});

test("成功保存后表单按后端回读重建（密钥输入被清空、意图回到 keep）", async () => {
  const returned = redacted({ revision: 5, enabled: true }, { ASR: "CONFIGURED", LLM: "MISSING", TTS: "MISSING" });
  const bridge = {
    mode: "kernelsu",
    call() {
      return Promise.resolve({ ok: true, source: "bridge", data: returned });
    },
  };
  const form = newForm({ revision: 4 }, { ASR: "MISSING" });
  form.enabled = true;
  form.asr.secretIntent = "replace";
  form.asr.secretValue = SECRET;
  const result = await B.saveConfig(bridge, form, redacted({ revision: 4 }, { ASR: "MISSING" }));
  assert.equal(result.ok, true);
  assert.equal(result.form.asr.secretValue, "");
  assert.equal(result.form.asr.secretIntent, "keep");
  assert.equal(result.form.asr.secretState, "CONFIGURED");
  assert.equal(result.form.enabled, true);
  assert.equal(JSON.stringify(result.form).includes(SECRET), false);
  assert.equal(CTL.includes("/bin/"), true);
});

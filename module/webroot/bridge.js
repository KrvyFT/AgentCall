/**
 * AgentCall WebUI 的桥接层：固定 CLI 映射 + 配置 schema + 展示安全helpers。
 *
 * 设计边界（spec §6.4/§11）：
 * - 只调用模块内固定包装器 agentcallctl 的六条命令；不存在任意 shell 拼接路径，
 *   任何用户输入都不会进入命令行字符串。
 * - 首选配置写入：本文件把补丁 JSON 写入 root 私有目录下本次唯一的临时文件，
 *   命令行里只有该次保存的重定向路径（内部常量 + 随机 token/序号，不含用户输入），
 *   因此密钥不可能出现在 argv。
 * - 管理器没有可用的受控文件写入接口（ksu.io 的 File + FileOutputStream 不可用）时，
 *   若 ksu.exec 透传 options.env，则用完全固定的命令
 *   `printf '%s' "$AGENTCALL_CFG_PAYLOAD" | <moduleDir>/bin/agentcallctl config-set`：
 *   JSON 只经 env map 进入子进程 stdin，命令字符串里只有环境变量名；不写 URL/日志，
 *   不把负载 base64 进 argv，也不写 inbox 文件。能力由一条无密钥探测确认（每页会话一次）。
 * - 两种通道都不可用时进入 readonly（env_unavailable）：读取可用，保存一律拒绝并提示
 *   “升级管理器（推荐）或在 root CLI 用 config-set 从 stdin 提交”；绝不降级成
 *   argv/echo，也绝不显示保存成功。
 * - 没有管理器注入接口（普通浏览器）时进入 demo：数据显式标注为演示，保存失败。
 * - 保存成功与否以后端回读为准（revision 与密钥状态同时核对），不伪造成功。
 */
(function (root, factory) {
  "use strict";
  var api = factory(root);
  root.AgentCallBridge = api;
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function (root) {
  "use strict";

  var MODULE_ID = "agentcall";
  var MODULE_ROOT = "/data/adb/modules/" + MODULE_ID;
  var DATA_ROOT = "/data/adb/agentcall";
  var INBOX_DIR = DATA_ROOT + "/inbox";
  // 旧版固定负载路径：仅作为兼容/清理常量保留，新的保存不再使用它。
  var INBOX_PATH = INBOX_DIR + "/config-set.json";
  // 每次保存使用唯一负载文件名（同一实例内递增，跨实例由随机 token 区分）。固定路径会
  // 让两个 WebUI 实例互相覆盖负载（A 的 CLI 读 B 的 payload 仍可能通过 CAS）。
  var INBOX_FILE_PREFIX = "config-set.";
  var LOG_PATH = DATA_ROOT + "/logs/service.log";
  var MODULE_DIR_PATTERN = /^\/data\/adb\/modules\/[A-Za-z][A-Za-z0-9._-]+$/;
  // exec + env 回退（Task 11O）：负载只放在 exec options 的 env map，固定命令仅引用变量名。
  // 变量名与探测命令都是常量；命令字符串永不拼接负载。
  var ENV_PAYLOAD_KEY = "AGENTCALL_CFG_PAYLOAD";
  var ENV_PROBE_KEY = "AGENTCALL_PROBE";
  var ENV_PROBE_COMMAND = "printf '%s' \"$" + ENV_PROBE_KEY + "\"";
  var IO_MODE_FILE = "file";
  var IO_MODE_ENV = "env";
  var IO_MODE_NONE = "none";
  var ENV_PROBE_SKIPPED = "skipped";
  var ENV_PROBE_PENDING = "pending";
  var ENV_PROBE_SUPPORTED = "supported";
  var ENV_PROBE_UNSUPPORTED = "unsupported";
  // restart 是模块侧控制动作：agentcallctl 写重启请求后执行固定 CLI shutdown，
  // 由 service.sh 在本次开机的有限循环内重新拉起。它不是 AgentCallCli 的 RPC。
  var OPERATIONS = ["status", "config-get", "config-set", "pair", "diagnose", "shutdown", "restart"];
  /** 与 AgentCallCli.MAX_STDIN_BYTES 一致：控制帧 64 KiB 预留 1 KiB。 */
  var CONFIG_WRITE_LIMIT_BYTES = 64 * 1024 - 1024;
  var COMMAND_TIMEOUT_MS = 20000;
  var LOG_TAIL_BYTES = 64 * 1024;
  var MAX_LOG_ENTRIES = 50;
  var DEFAULT_OPENING_LINE = "您好，我是机主的 AI 电话助理，请问有什么事？";
  var MODES = ["BLACKLIST", "WHITELIST", "COMBINED"];
  var SECRET_KINDS = ["ASR", "LLM", "TTS"];
  var ENDPOINT_FIELDS = { ASR: "asr", LLM: "llm", TTS: "tts" };
  var SPEED_MIN = 0.5;
  var SPEED_MAX = 2.0;
  /**
   * 页面保存作用域：任何一次页面保存只提交该页面的字段。
   * 总开关单独保存（只有 enabled），页面补丁不包含 enabled，避免一个页面夹带
   * 另一个页面未确认的编辑（尤其是 AI 密钥意图）。
   */
  var PAGE_PATCH_KEYS = {
    rules: ["mode", "blacklist", "whitelist", "region", "handleUnknownNumbers", "answerDelayMs", "maxCallDurationMs"],
    ai: ["asr", "llm", "tts", "voice", "secrets"],
    dialog: ["openingLine", "prompt", "speed"],
  };
  /** 与后端 RpcCodec.PATCH_KEYS 保持一致的固定字段表（漂移由测试守护）。 */
  var PATCH_KEYS = [
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
  ];

  var CODE = {
    BRIDGE_UNAVAILABLE: "bridge_unavailable",
    SECRET_TRANSPORT_UNSUPPORTED: "secret_transport_unsupported",
    MODULE_DIR_UNRESOLVED: "module_dir_unresolved",
    UNSUPPORTED_OPERATION: "unsupported_operation",
    PAYLOAD_NOT_ALLOWED: "payload_not_allowed",
    PAYLOAD_INVALID: "payload_invalid",
    TRANSPORT_FAILED: "transport_failed",
    RESPONSE_INVALID: "response_invalid",
    SAVE_UNCONFIRMED: "save_unconfirmed",
    INBOX_WRITE_FAILED: "inbox_write_failed",
    IO_UNAVAILABLE: "io_unavailable",
    VALIDATION_FAILED: "validation_failed",
  };

  var ERROR_TEXT = {
    bridge_unavailable: "未检测到 KernelSU 管理器的桥接接口（普通浏览器或未注入）。当前为只读演示状态，无法保存。",
    secret_transport_unsupported:
      "当前管理器既不支持 ksu.io 受控文件写入，也不支持 ksu.exec 的环境变量通道，无法在不暴露密钥的前提下提交配置，已拒绝保存。请升级管理器（推荐，升级后自动回到受控文件通道），或在 root shell 用 agentcallctl config-set 从 stdin 提交负载；密钥绝不会放进 argv/base64/URL。",
    module_dir_unresolved: "无法确认模块安装目录，已拒绝执行任何命令。",
    unsupported_operation: "只允许 status/config-get/config-set/pair/diagnose/shutdown/restart 七条固定命令。",
    payload_not_allowed: "该命令不接受参数。",
    payload_invalid: "请求内容不符合后端固定字段表，已被拒绝。",
    transport_failed: "无法连接 AgentCall 桥接服务（未启动或已退出）。",
    response_invalid: "桥接服务返回的内容无法解析。",
    save_unconfirmed: "写入结果未被后端回读确认，因此不视为已保存。",
    inbox_write_failed: "无法写入受控临时文件，已拒绝保存。",
    io_unavailable: "当前管理器没有可用的文件接口。",
    validation_failed: "表单未通过校验。",
    config_conflict: "配置已被其他入口修改（版本冲突），本次没有覆盖写入。请重新加载后再保存。",
    config_invalid: "配置未通过后端校验，请按提示修正。",
    secret_invalid: "密钥不能为空。",
    peer_version_mismatch: "APK 或 Google 电话已升级，配对已失效，必须重新配对。",
    peer_unpaired: "桥接服务尚未配对，请先重新配对。",
    pair_requires_root: "首次配对必须由 root CLI 执行。",
    cli_container_missing: "模块内缺少桥接运行时容器，请重新安装模块。",
    config_schema_unsupported: "配置 schema 比当前桥接版本更新，已拒绝覆盖。",
    config_corrupt: "配置文件损坏，需要人工处理。",
    config_storage_failed: "配置文件读写失败。",
    config_root_rejected: "配置目录不在允许范围内。",
    pairing_invalid: "配对记录字段非法，需要重新配对。",
    forbidden: "当前角色无权调用该命令。",
    usage_invalid: "命令用法不正确。",
    response_id_mismatch: "响应与请求不匹配，已丢弃。",
    service_not_running: "service.sh 不在运行：本次开机内已没有重启循环，只能重启设备或重新启用模块。",
    module_disabled: "模块已被禁用或标记移除：不会重启桥接。",
    restart_unconfirmed: "桥接没有接受重启请求（可能已退出或不可达），本次没有重启；请刷新状态后再试。",
    restart_refused: "桥接关闭未确认干净（可能仍隔离麦克风），已拒绝重启；请先手动结束通话并重启设备后再试。",
    restart_request_failed: "无法写入 root 私有重启请求，本次没有重启。",
  };

  // ---------------------------------------------------------------- 通用工具

  function hasOwn(object, key) {
    return Object.prototype.hasOwnProperty.call(object, key);
  }

  function toText(value) {
    return value === null || value === undefined ? "" : String(value);
  }

  function trimText(value) {
    return toText(value).replace(/^\s+|\s+$/g, "");
  }

  function isPlainObject(value) {
    return !!value && typeof value === "object" && !Array.isArray(value);
  }

  function isIntegerValue(value) {
    return typeof value === "number" && isFinite(value) && Math.floor(value) === value;
  }

  function utf8ByteLength(text) {
    var source = toText(text);
    if (typeof TextEncoder !== "undefined") {
      return new TextEncoder().encode(source).length;
    }
    if (typeof encodeURIComponent === "function") {
      return encodeURIComponent(source).replace(/%[0-9A-F]{2}/gi, "_").length;
    }
    return source.length;
  }

  function toBase64Utf8(text) {
    var source = toText(text);
    if (typeof TextEncoder !== "undefined" && typeof btoa === "function") {
      var bytes = new TextEncoder().encode(source);
      var binary = "";
      for (var index = 0; index < bytes.length; index += 1) {
        binary += String.fromCharCode(bytes[index]);
      }
      return btoa(binary);
    }
    if (typeof Buffer !== "undefined") {
      return Buffer.from(source, "utf8").toString("base64");
    }
    throw new Error("no base64 encoder");
  }

  function fromBase64Utf8(text) {
    var source = toText(text);
    if (source === "") return "";
    if (typeof TextDecoder !== "undefined" && typeof atob === "function") {
      var binary = atob(source);
      var bytes = new Uint8Array(binary.length);
      for (var index = 0; index < binary.length; index += 1) {
        bytes[index] = binary.charCodeAt(index);
      }
      return new TextDecoder("utf-8").decode(bytes);
    }
    if (typeof Buffer !== "undefined") {
      return Buffer.from(source, "base64").toString("utf8");
    }
    throw new Error("no base64 decoder");
  }

  // 展示层：文本一律写 textContent，HTML 只做转义字符串，任何地方都不拼 innerHTML。
  function setText(element, value) {
    if (!element) return;
    element.textContent = value === null || value === undefined ? "" : String(value);
  }

  /** 脱敏文本：把疑似凭据字段的值替换为 ***，用于日志/导出前的二次过滤。 */
  function redactText(value) {
    return toText(value)
      .replace(/((?:api[_-]?key|apikey|authorization|bearer|token|secret|password)\s*[:=]\s*)("?[^\s",}]+)/gi, "$1***")
      .replace(/(sk-[A-Za-z0-9_-]{8,})/g, "sk-***");
  }

  function parseList(text) {
    var raw = toText(text);
    if (trimText(raw) === "") return { entries: [], dropped: 0 };
    var pieces = raw.split(/[\n,;]+/);
    var entries = [];
    var dropped = 0;
    for (var index = 0; index < pieces.length; index += 1) {
      var value = trimText(pieces[index]);
      if (value === "") {
        dropped += 1;
        continue;
      }
      entries.push(value);
    }
    return { entries: entries, dropped: dropped };
  }

  /** 保守的号码比较键：去掉空白/标点，00 前缀按国际写法展开；无法比较返回 null。 */
  function normalizeDigits(raw) {
    var source = toText(raw);
    var kept = source.replace(/[^0-9+]/g, "");
    if (kept === "") return null;
    var plusIndex = kept.indexOf("+");
    if (plusIndex > 0) return null;
    if (kept.split("+").length > 2) return null;
    var digits = kept.replace(/\+/g, "");
    if (digits === "") return null;
    if (kept.charAt(0) !== "+" && digits.indexOf("00") === 0) {
      digits = digits.slice(2);
    }
    if (digits === "") return null;
    return digits;
  }

  function isPhoneLike(raw) {
    var source = trimText(raw);
    if (source === "") return false;
    if (!/^\+?[0-9\s\-().]+$/.test(source)) return false;
    var digits = source.replace(/[^0-9]/g, "");
    return digits.length >= 3;
  }

  function parseIntegerField(value) {
    var text = trimText(value);
    if (text === "" || !/^-?[0-9]+$/.test(text)) return null;
    var parsed = Number(text);
    return isIntegerValue(parsed) ? parsed : null;
  }

  function validateBaseUrl(url) {
    var value = trimText(url);
    if (value === "") return null;
    if (/\s/.test(value)) {
      return { code: "ENDPOINT_URL_INVALID", message: "地址中不能包含空白字符" };
    }
    var parsed;
    try {
      parsed = new URL(value);
    } catch (error) {
      return { code: "ENDPOINT_URL_INVALID", message: "地址不是合法 URL（需要完整 scheme 与主机名）" };
    }
    if (parsed.protocol !== "https:") {
      return { code: "ENDPOINT_HTTPS_REQUIRED", message: "远程 API 必须使用 HTTPS" };
    }
    if (!parsed.hostname) {
      return { code: "ENDPOINT_URL_INVALID", message: "地址缺少主机名" };
    }
    if (parsed.username || parsed.password) {
      return { code: "ENDPOINT_URL_INVALID", message: "地址中不能内嵌用户名或密码" };
    }
    return null;
  }

  // ---------------------------------------------------------------- 管理器探测

  /**
   * 解析模块目录：
   * - 管理器没有 moduleInfo、抛错、返回不可解析/非对象，或对象里没有 moduleDir（旧版/分支管理器
   *   常见的版本偏差）→ 回退到固定路径 /data/adb/modules/agentcall。该值是常量，不含外部输入。
   * - 明确给出 moduleDir 但不符合固定模式 → 视为不可信，返回 null（fail closed）。
   * 只有“明确提供却不可信”才应禁止命令；缺少字段不等于不可信。
   */
  function readModuleDir(ksu) {
    if (!ksu || typeof ksu.moduleInfo !== "function") return MODULE_ROOT;
    var raw;
    try {
      raw = ksu.moduleInfo();
    } catch (error) {
      return MODULE_ROOT;
    }
    var parsed;
    try {
      parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
    } catch (error) {
      return MODULE_ROOT;
    }
    if (!isPlainObject(parsed)) return MODULE_ROOT;
    if (!hasOwn(parsed, "moduleDir") || parsed.moduleDir === null || parsed.moduleDir === undefined) {
      return MODULE_ROOT;
    }
    var directory = parsed.moduleDir;
    if (typeof directory === "string" && MODULE_DIR_PATTERN.test(directory)) return directory;
    return null;
  }

  /**
   * 解析管理器注入的 io 句柄（Task 11M）。
   *
   * 上游 KernelSU 的 JS API 声明是 `declare const io: KsuIO`：io 是对象，support 是布尔，
   * version 是数字。只有少数旧管理器/分支把 io 暴露成函数。两种形式都必须接受，
   * 且解析失败只按“缺失”处理，不能抛出。
   */
  function resolveIoHandle(ksu) {
    var raw = ksu ? ksu.io : undefined;
    if (raw === undefined || raw === null) return { form: "none", io: null };
    var form = "object";
    var io = raw;
    if (typeof raw === "function") {
      form = "function";
      try {
        io = raw();
      } catch (error) {
        io = null;
      }
    }
    if (!io || typeof io !== "object") return { form: form, io: null };
    return { form: form, io: io };
  }

  function resolveIoSupport(io) {
    if (!io) return null;
    var value = io.support;
    if (typeof value === "boolean") return value;
    if (typeof value === "function") {
      try {
        var returned = value.call(io);
        return typeof returned === "boolean" ? returned : null;
      } catch (error) {
        return null;
      }
    }
    return null;
  }

  /** 版本只用于诊断：数字、数字字符串、返回数字的函数都接受；缺失/异常一律 0。 */
  function resolveIoVersion(io) {
    if (!io) return 0;
    try {
      var value = io.version;
      if (typeof value === "function") value = value.call(io);
      if (typeof value === "number" && isFinite(value)) return value;
      if (typeof value === "string" && trimText(value) !== "") {
        var parsed = Number(value);
        if (isFinite(parsed)) return parsed;
      }
    } catch (error) {
      /* 仅诊断用途：版本不可读时退回 0，不影响能力判定。 */
    }
    return 0;
  }

  function inspectIo(ksu) {
    var handle = resolveIoHandle(ksu);
    var io = handle.io;
    var hasFile = !!(io && typeof io.File === "function");
    var hasFileOutputStream = !!(io && typeof io.FileOutputStream === "function");
    var ioSupport = resolveIoSupport(io);
    // 上游语义：support === false 明确不支持；support === true（含 version 为 0/缺失）
    // 或旧版/分支没有 support 字段时，只要两个必需类存在即支持。
    var supported = !!(io && hasFile && hasFileOutputStream && ioSupport !== false);
    return {
      ioForm: handle.form,
      ioSupport: ioSupport,
      ioVersion: resolveIoVersion(io),
      hasFile: hasFile,
      hasFileOutputStream: hasFileOutputStream,
      supported: supported,
    };
  }

  /**
   * 能力矩阵（Task 11O）：
   * - 文件接口可用（File + FileOutputStream，且 support !== false）→ kernelsu / ioMode=file；
   * - 文件接口不可用、但 exec + 模块目录可信 → 先标记 env 候选（readonly/env_probe_pending，
   *   ioMode=env），由一次无密钥探测决定是否升级为 kernelsu / ioMode=env；
   * - 探测失败 → readonly/env_unavailable、ioMode=none；
   * - 模块目录不可信或没有 exec → 只读/演示，ioMode=none。
   */
  function detectSupport(scope) {
    var source = scope || root || {};
    var ksu = source.ksu;
    if (!ksu || typeof ksu !== "object") {
      return { mode: "demo", hasBridge: false, hasExec: false, hasIo: false, ioVersion: 0, moduleDir: null, reason: "no_manager_bridge", ioForm: "none", ioSupport: null, hasFile: false, hasFileOutputStream: false, ioMode: IO_MODE_NONE, envProbe: ENV_PROBE_SKIPPED };
    }
    if (typeof ksu.exec !== "function") {
      return { mode: "demo", hasBridge: false, hasExec: false, hasIo: false, ioVersion: 0, moduleDir: null, reason: "exec_missing", ioForm: "none", ioSupport: null, hasFile: false, hasFileOutputStream: false, ioMode: IO_MODE_NONE, envProbe: ENV_PROBE_SKIPPED };
    }
    var moduleDir = readModuleDir(ksu);
    var inspected = inspectIo(ksu);
    var hasFileTransport = inspected.supported;
    var support = {
      // 文件通道可用才立即 kernelsu；env 候选必须等探测成功后才能宣称可保存。
      mode: hasFileTransport ? "kernelsu" : "readonly",
      hasBridge: true,
      hasExec: true,
      hasIo: hasFileTransport,
      ioVersion: inspected.ioVersion,
      moduleDir: moduleDir,
      reason: hasFileTransport ? "ok" : "env_probe_pending",
      ioForm: inspected.ioForm,
      ioSupport: inspected.ioSupport,
      hasFile: inspected.hasFile,
      hasFileOutputStream: inspected.hasFileOutputStream,
      ioMode: hasFileTransport ? IO_MODE_FILE : IO_MODE_ENV,
      envProbe: hasFileTransport ? ENV_PROBE_SKIPPED : ENV_PROBE_PENDING,
    };
    // 模块目录不可信优先于 io/env：它决定命令路径是否可执行，绝不放宽。
    if (moduleDir === null) {
      support.mode = "readonly";
      support.moduleDir = null;
      support.reason = "module_dir_unresolved";
      support.ioMode = IO_MODE_NONE;
      support.envProbe = ENV_PROBE_SKIPPED;
      return support;
    }
    return support;
  }

  // ---------------------------------------------------------------- 固定命令执行

  function parseCliResult(raw) {
    if (!raw) return { ok: false, code: CODE.TRANSPORT_FAILED, message: ERROR_TEXT.transport_failed };
    var stdout = toText(raw.stdout);
    var lines = stdout.split(/\r?\n/);
    var line = "";
    for (var index = 0; index < lines.length; index += 1) {
      if (trimText(lines[index]) !== "") line = trimText(lines[index]);
    }
    if (line === "") {
      return { ok: false, code: CODE.TRANSPORT_FAILED, message: ERROR_TEXT.transport_failed };
    }
    var parsed;
    try {
      parsed = JSON.parse(line);
    } catch (error) {
      return { ok: false, code: CODE.RESPONSE_INVALID, message: ERROR_TEXT.response_invalid };
    }
    if (isPlainObject(parsed) && isPlainObject(parsed.error)) {
      var code = toText(parsed.error.code) || "unknown_error";
      return { ok: false, code: code, message: toText(parsed.error.message) || errorText(code) };
    }
    if (raw.errno !== 0 && raw.errno !== undefined) {
      return { ok: false, code: CODE.TRANSPORT_FAILED, message: ERROR_TEXT.transport_failed };
    }
    if (!isPlainObject(parsed)) {
      return { ok: false, code: CODE.RESPONSE_INVALID, message: ERROR_TEXT.response_invalid };
    }
    return { ok: true, data: parsed };
  }

  function errorText(code) {
    if (hasOwn(ERROR_TEXT, code)) return ERROR_TEXT[code];
    return "桥接返回错误：" + toText(code);
  }

  function createBridge(options) {
    var opts = options || {};
    var scope = opts.scope || root || {};
    var ksu = hasOwn(opts, "ksu") ? opts.ksu : scope.ksu;
    var support = detectSupport({ ksu: ksu });
    var state = { revision: null, logs: [], sequence: 0 };
    // env 回退能力每页会话只探测一次；探测结果写回 support（ioMode/envProbe/reason）。
    var envProbePromise = null;
    // 每个 WebUI 实例一个随机 token；路径只由常量 + 该 token + 递增序号组成，
    // 不含任何用户输入，也不进入 argv 之外的任何位置。
    var instanceToken = Date.now().toString(36) + "-" + (Math.random().toString(36).slice(2, 10) || "0");

    function nextInboxPath() {
      state.sequence += 1;
      return INBOX_DIR + "/" + INBOX_FILE_PREFIX + instanceToken + "-" + state.sequence + ".json";
    }

    function recordLog(operation, errno, code) {
      state.logs.push({
        at: Date.now(),
        operation: operation,
        errno: typeof errno === "number" ? errno : -1,
        code: toText(code),
      });
      if (state.logs.length > MAX_LOG_ENTRIES) state.logs.shift();
    }

    function reject(code, message) {
      recordLog("client", -1, code);
      return Promise.resolve({ ok: false, code: code, message: message || errorText(code), source: "none" });
    }

    function controlPath() {
      return support.moduleDir + "/bin/agentcallctl";
    }

    function commandFor(operation, inboxPath) {
      var control = controlPath();
      if (operation === "config-set") {
        // CLI 契约不变：config-set 只从 stdin 读；重定向路径是本次保存的唯一负载，
        // 由内部常量 + 随机 token/序号生成，没有用户数据，也没有任何密钥。
        return control + " config-set < " + inboxPath;
      }
      return control + " " + operation;
    }

    /**
     * env 回退的固定命令：只有常量壳词、环境变量名与已校验的模块路径。
     * moduleDir 由 MODULE_DIR_PATTERN 限定为 [A-Za-z0-9._/-]，不含引号或 shell 元字符；
     * 双引号保证 printf 只读取完整的环境变量值。负载永远不参与字符串拼接。
     */
    function envConfigCommand() {
      return "printf '%s' \"$" + ENV_PAYLOAD_KEY + "\" | " + controlPath() + " config-set";
    }

    function markEnvUnavailable() {
      support.ioMode = IO_MODE_NONE;
      support.envProbe = ENV_PROBE_UNSUPPORTED;
      support.mode = "readonly";
      support.reason = "env_unavailable";
    }

    function markEnvAvailable() {
      support.ioMode = IO_MODE_ENV;
      support.envProbe = ENV_PROBE_SUPPORTED;
      support.mode = "kernelsu";
      support.reason = "ok";
    }

    /**
     * 用一条无密钥 canary 命令确认管理器确实把 options.env 传给子进程（每页会话一次）。
     * 忽略 env 的管理器会得到空输出/错误输出，exec 抛错则直接失败；两种情况都 fail closed，
     * 不执行 config-set，更不回退到 argv/base64。
     */
    function probeEnvTransport() {
      if (support.ioMode === IO_MODE_FILE) return Promise.resolve(true);
      if (support.envProbe === ENV_PROBE_SUPPORTED) return Promise.resolve(true);
      if (support.envProbe === ENV_PROBE_UNSUPPORTED) return Promise.resolve(false);
      if (support.ioMode !== IO_MODE_ENV) return Promise.resolve(false);
      if (support.moduleDir === null) {
        markEnvUnavailable();
        return Promise.resolve(false);
      }
      if (envProbePromise) return envProbePromise;

      var canary = "agentcall-env-probe-" + Date.now().toString(36) + "-" + (Math.random().toString(36).slice(2, 10) || "0");
      var probeEnv = {};
      probeEnv[ENV_PROBE_KEY] = canary;
      envProbePromise = runCommand(ENV_PROBE_COMMAND, probeEnv).then(
        function (raw) {
          // canary 不是密钥，但仍不保留环境对象与字符串引用。
          probeEnv = null;
          if (raw.errno === 0 && trimText(raw.stdout) === canary) {
            markEnvAvailable();
            return true;
          }
          markEnvUnavailable();
          return false;
        },
        function () {
          probeEnv = null;
          markEnvUnavailable();
          return false;
        },
      );
      return envProbePromise;
    }

    function runCommand(command, env) {
      return new Promise(function (resolve) {
        var callbackName = "__agentcall_cb_" + Date.now() + "_" + state.sequence;
        state.sequence += 1;
        var settled = false;
        var timer = null;
        var optionsJson = "{}";
        var envMap = null;

        if (env && typeof env === "object") {
          // 只接受字符串值；名称/值都不进入 command 字符串。
          envMap = {};
          Object.keys(env).forEach(function (key) {
            envMap[key] = toText(env[key]);
          });
          optionsJson = JSON.stringify({ env: envMap });
        }

        function finish(result) {
          if (settled) return;
          settled = true;
          if (timer && typeof timer.unref === "function") timer.unref();
          // 负载只在 exec 调用期间存在于 options 字符串；回调后立即断开引用。
          optionsJson = null;
          envMap = null;
          try {
            delete root[callbackName];
          } catch (error) {
            /* 清理失败不影响结果 */
          }
          resolve(result);
        }

        root[callbackName] = function (errno, stdout, stderr) {
          finish({ errno: Number(errno) || 0, stdout: toText(stdout), stderr: toText(stderr) });
        };

        timer = setTimeout(function () {
          finish({ errno: -1, stdout: "", stderr: "" });
        }, COMMAND_TIMEOUT_MS);
        if (timer && typeof timer.unref === "function") timer.unref();

        try {
          var returned = ksu.exec(command, optionsJson, callbackName);
          if (returned && typeof returned.then === "function") {
            returned.then(
              function (value) {
                finish({ errno: Number(value && value.errno) || 0, stdout: toText(value && value.stdout), stderr: toText(value && value.stderr) });
              },
              function () {
                finish({ errno: -1, stdout: "", stderr: "" });
              },
            );
          }
        } catch (error) {
          finish({ errno: -1, stdout: "", stderr: "" });
        }
      });
    }

    function writeInbox(text, inboxPath) {
      var io = resolveIoHandle(ksu).io;
      if (!io) return { ok: false, reason: "io" };
      try {
        var directory = io.File(INBOX_DIR);
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
          return { ok: false, reason: "directory" };
        }
        // 明文密钥负载落在该目录：显式 0700（ownerOnly rwx），不依赖 mkdirs 的默认 umask。
        try {
          if (typeof directory.setReadable === "function") directory.setReadable(true, true);
          if (typeof directory.setWritable === "function") directory.setWritable(true, true);
          if (typeof directory.setExecutable === "function") directory.setExecutable(true, true);
        } catch (error) {
          /* 管理器不支持 chmod 时由 agentcallctl/customize.sh 预创建的 0700 目录兜底 */
        }
        var target = io.File(inboxPath);
        if (target.exists() && typeof target.isSymlink === "function" && target.isSymlink()) {
          target.delete();
        } else if (target.exists()) {
          target.delete();
        }
        var output = io.FileOutputStream();
        var streamId = output.open(inboxPath, false);
        if (!streamId) return { ok: false, reason: "open" };
        var written = output.write(streamId, toBase64Utf8(text));
        var flushed = output.flush(streamId);
        var closed = output.close(streamId);
        if (!written || !flushed || !closed) return { ok: false, reason: "write" };
        // 纵深防御：临时文件从 0644 收紧到 0000，只有 root（CAP_DAC_OVERRIDE）能读；
        // 目录本身由 agentcallctl 以 0700 创建，未授权进程无法穿越。
        try {
          var created = io.File(inboxPath);
          if (created && typeof created.setReadable === "function") {
            created.setReadable(false, false);
            created.setWritable(false, false);
          }
        } catch (error) {
          /* 权限收紧失败不阻断保存：目录仍为 0700 且文件随即删除 */
        }
        return { ok: true };
      } catch (error) {
        return { ok: false, reason: "exception" };
      }
    }

    function deleteInbox(inboxPath) {
      try {
        var io = resolveIoHandle(ksu).io;
        if (!io) return;
        var file = io.File(inboxPath);
        if (file.exists()) file.delete();
      } catch (error) {
        /* 删除失败不改变保存结论 */
      }
    }

    function verifyWrite(data, expectedRevision, secrets) {
      if (!isPlainObject(data) || !isPlainObject(data.config)) return CODE.SAVE_UNCONFIRMED;
      if (data.config.revision !== expectedRevision + 1) return CODE.SAVE_UNCONFIRMED;
      var states = isPlainObject(data.secrets) ? data.secrets : {};
      if (isPlainObject(secrets)) {
        for (var index = 0; index < SECRET_KINDS.length; index += 1) {
          var kind = SECRET_KINDS[index];
          var intent = secrets[kind];
          if (!isPlainObject(intent)) continue;
          if (intent.action === "replace" && states[kind] !== "CONFIGURED") return CODE.SAVE_UNCONFIRMED;
          if (intent.action === "clear" && states[kind] !== "MISSING") return CODE.SAVE_UNCONFIRMED;
        }
      }
      return null;
    }

    /** 首选通道：ksu.io 写入本次唯一负载文件，CLI 从 stdin 读，随后立即删除。 */
    function saveConfigViaIo(payload) {
      var validated = validateConfigWritePayload(payload);
      if (!validated.ok) return reject(CODE.PAYLOAD_INVALID, validated.message);
      var text = JSON.stringify(validated.write);
      if (utf8ByteLength(text) > CONFIG_WRITE_LIMIT_BYTES) {
        return reject(CODE.PAYLOAD_INVALID, "配置补丁超过 stdin 上限（" + CONFIG_WRITE_LIMIT_BYTES + " 字节）");
      }
      var inboxPath = nextInboxPath();
      var written = writeInbox(text, inboxPath);
      if (!written.ok) {
        // 失败路径也可能留下已经创建/部分写入的负载（含密钥），必须在这里删除。
        deleteInbox(inboxPath);
        recordLog("config-set", -1, CODE.INBOX_WRITE_FAILED);
        return reject(CODE.INBOX_WRITE_FAILED, errorText(CODE.INBOX_WRITE_FAILED) + "（" + written.reason + "）");
      }
      return runCommand(commandFor("config-set", inboxPath))
        .then(function (raw) {
          deleteInbox(inboxPath);
          var parsed = parseCliResult(raw);
          recordLog("config-set", raw.errno, parsed.ok ? "ok" : parsed.code);
          if (!parsed.ok) {
            var failure = { ok: false, code: parsed.code, message: parsed.message, source: "bridge" };
            if (parsed.code === "config_conflict") failure.conflict = true;
            return failure;
          }
          var problem = verifyWrite(parsed.data, validated.write.expectedRevision, validated.write.patch && validated.write.patch.secrets);
          if (problem) {
            return { ok: false, code: problem, message: errorText(problem), source: "bridge" };
          }
          state.revision = parsed.data.config.revision;
          return { ok: true, source: "bridge", data: parsed.data };
        })
        .then(function (result) {
          deleteInbox(inboxPath);
          return result;
        });
    }

    /**
     * 回退通道：先探测（每页会话一次）再执行完全固定的命令；JSON 只经 exec options 的
     * env map 进入子进程 stdin。不写 inbox，也没有需要删除的磁盘负载；成功判据与 io 路径
     * 完全一致（revision + 密钥状态回读）。
     */
    function saveConfigViaEnv(payload) {
      var validated = validateConfigWritePayload(payload);
      if (!validated.ok) return reject(CODE.PAYLOAD_INVALID, validated.message);
      var write = validated.write;
      var text = JSON.stringify(write);
      if (utf8ByteLength(text) > CONFIG_WRITE_LIMIT_BYTES) {
        return reject(CODE.PAYLOAD_INVALID, "配置补丁超过 stdin 上限（" + CONFIG_WRITE_LIMIT_BYTES + " 字节）");
      }
      return probeEnvTransport().then(function (supported) {
        if (!supported || support.ioMode !== IO_MODE_ENV) {
          text = null;
          return reject(CODE.SECRET_TRANSPORT_UNSUPPORTED);
        }
        var env = {};
        env[ENV_PAYLOAD_KEY] = text;
        // env 已持有唯一副本：立即断开本地字符串引用。
        text = null;
        return runCommand(envConfigCommand(), env).then(function (raw) {
          try {
            delete env[ENV_PAYLOAD_KEY];
          } catch (error) {
            /* 清理失败不影响结果 */
          }
          env = null;
          var parsed = parseCliResult(raw);
          recordLog("config-set", raw.errno, parsed.ok ? "ok" : parsed.code);
          if (!parsed.ok) {
            var failure = { ok: false, code: parsed.code, message: parsed.message, source: "bridge" };
            if (parsed.code === "config_conflict") failure.conflict = true;
            return failure;
          }
          var problem = verifyWrite(parsed.data, write.expectedRevision, write.patch && write.patch.secrets);
          if (problem) {
            return { ok: false, code: problem, message: errorText(problem), source: "bridge" };
          }
          state.revision = parsed.data.config.revision;
          return { ok: true, source: "bridge", data: parsed.data };
        });
      });
    }

    function saveConfig(payload) {
      if (support.ioMode === IO_MODE_FILE) return saveConfigViaIo(payload);
      if (support.ioMode === IO_MODE_ENV) return saveConfigViaEnv(payload);
      return reject(CODE.SECRET_TRANSPORT_UNSUPPORTED);
    }

    function call(operation, payload) {
      var name = toText(operation);
      if (OPERATIONS.indexOf(name) === -1) {
        return reject(CODE.UNSUPPORTED_OPERATION, errorText(CODE.UNSUPPORTED_OPERATION) + "（收到：" + name.slice(0, 32) + "）");
      }
      if (support.mode === "demo") return reject(CODE.BRIDGE_UNAVAILABLE);
      if (support.moduleDir === null) return reject(CODE.MODULE_DIR_UNRESOLVED);
      if (name === "config-set") {
        // 通道选择在 saveConfig 内：file / env（先探测）/ none（拒绝）。
        return saveConfig(payload);
      }
      if (payload !== undefined && payload !== null) {
        return reject(CODE.PAYLOAD_NOT_ALLOWED);
      }
      return runCommand(commandFor(name)).then(function (raw) {
        var parsed = parseCliResult(raw);
        recordLog(name, raw.errno, parsed.ok ? "ok" : parsed.code);
        if (!parsed.ok) return { ok: false, code: parsed.code, message: parsed.message, source: "bridge" };
        if (name === "config-get" && parsed.data.config && typeof parsed.data.config.revision === "number") {
          state.revision = parsed.data.config.revision;
        }
        return { ok: true, source: "bridge", data: parsed.data };
      });
    }

    function readLog(maxBytes) {
      if (support.mode === "demo") return Promise.resolve({ ok: false, code: CODE.BRIDGE_UNAVAILABLE, message: errorText(CODE.BRIDGE_UNAVAILABLE) });
      // 日志是固定路径 /data/adb/agentcall/logs/service.log，不需要模块目录；
      // 模块目录不可信只应禁用需要执行 CLI 的命令，不应连带禁用只读日志。
      if (!support.hasIo) {
        return Promise.resolve({ ok: false, code: CODE.IO_UNAVAILABLE, message: errorText(CODE.IO_UNAVAILABLE) });
      }
      try {
        // 与 detectSupport 相同：上游 io 是对象，旧管理器才暴露成函数。
        var io = resolveIoHandle(ksu).io;
        if (!io) return Promise.resolve({ ok: false, code: CODE.IO_UNAVAILABLE, message: errorText(CODE.IO_UNAVAILABLE) });
        var file = io.File(LOG_PATH);
        if (!file.exists() || !file.isFile()) return Promise.resolve({ ok: true, text: "", truncated: false });
        var limit = typeof maxBytes === "number" && maxBytes > 0 ? Math.min(maxBytes, LOG_TAIL_BYTES) : LOG_TAIL_BYTES;
        var text = "";
        var truncated = false;
        if (typeof io.RandomAccessFile === "function") {
          var access = io.RandomAccessFile();
          var handle = access.open(LOG_PATH, "r");
          if (handle) {
            var length = access.length(handle);
            var start = Math.max(0, length - limit);
            truncated = start > 0;
            if (typeof access.seek === "function") access.seek(handle, start);
            text = fromBase64Utf8(access.readBytes(handle, Math.min(limit, length - start)));
            access.close(handle);
          }
        } else {
          var input = io.FileInputStream();
          var stream = input.open(LOG_PATH);
          if (stream) {
            text = fromBase64Utf8(input.read(stream, limit));
            input.close(stream);
          }
        }
        return Promise.resolve({ ok: true, text: redactText(text), truncated: truncated });
      } catch (error) {
        return Promise.resolve({ ok: false, code: CODE.IO_UNAVAILABLE, message: errorText(CODE.IO_UNAVAILABLE) });
      }
    }

    return {
      // mode/ioMode 会随一次成功的 env 探测从 readonly/env_probe_pending 变为
      // kernelsu/env；必须用 getter，不能捕获初始值。
      get mode() {
        return support.mode;
      },
      get ioMode() {
        return support.ioMode;
      },
      support: support,
      probeEnvTransport: probeEnvTransport,
      call: call,
      readLog: readLog,
      logs: function () {
        return state.logs.slice();
      },
      lastKnownRevision: function () {
        return state.revision;
      },
      controlPath: controlPath,
    };
  }

  // ---------------------------------------------------------------- 负载校验

  function validateSecretsPatch(secrets) {
    if (!isPlainObject(secrets)) return "secrets 必须是对象";
    var kinds = Object.keys(secrets);
    for (var index = 0; index < kinds.length; index += 1) {
      var kind = kinds[index];
      if (SECRET_KINDS.indexOf(kind) === -1) return "未知密钥类别：" + kind;
      var intent = secrets[kind];
      if (!isPlainObject(intent)) return "密钥动作必须是对象";
      var keys = Object.keys(intent);
      if (intent.action === "keep" || intent.action === "clear") {
        if (keys.length !== 1) return "keep/clear 动作不接受额外字段";
      } else if (intent.action === "replace") {
        if (keys.length !== 2 || keys.indexOf("value") === -1) return "replace 动作只接受 value";
        if (typeof intent.value !== "string" || trimText(intent.value) === "") return "替换密钥的值不能为空";
      } else {
        return "密钥动作必须是 keep/replace/clear";
      }
    }
    return null;
  }

  function validateEndpointPatch(value, field) {
    if (!isPlainObject(value)) return field + " 必须是对象或 null";
    var keys = Object.keys(value);
    for (var index = 0; index < keys.length; index += 1) {
      if (keys[index] !== "baseUrl" && keys[index] !== "model") return field + " 只接受 baseUrl/model";
    }
    if (typeof value.baseUrl !== "string" || typeof value.model !== "string") return field + " 字段必须是字符串";
    return null;
  }

  function validateConfigWritePayload(payload) {
    if (!isPlainObject(payload)) return { ok: false, message: "需要 { expectedRevision, patch } 对象" };
    var keys = Object.keys(payload);
    if (keys.length !== 2 || !hasOwn(payload, "expectedRevision") || !hasOwn(payload, "patch")) {
      return { ok: false, message: "只接受 expectedRevision 与 patch 两个字段" };
    }
    if (!isIntegerValue(payload.expectedRevision) || payload.expectedRevision < 0) {
      return { ok: false, message: "expectedRevision 必须是非负整数" };
    }
    if (!isPlainObject(payload.patch)) return { ok: false, message: "patch 必须是对象" };
    var patch = payload.patch;
    var patchKeys = Object.keys(patch);
    for (var index = 0; index < patchKeys.length; index += 1) {
      if (PATCH_KEYS.indexOf(patchKeys[index]) === -1) return { ok: false, message: "未知配置字段：" + patchKeys[index] };
    }
    if (hasOwn(patch, "enabled") && typeof patch.enabled !== "boolean") return { ok: false, message: "enabled 必须是布尔值" };
    if (hasOwn(patch, "mode") && MODES.indexOf(patch.mode) === -1) return { ok: false, message: "mode 取值非法" };
    if (hasOwn(patch, "region") && typeof patch.region !== "string") return { ok: false, message: "region 必须是字符串" };
    if (hasOwn(patch, "handleUnknownNumbers") && typeof patch.handleUnknownNumbers !== "boolean") {
      return { ok: false, message: "handleUnknownNumbers 必须是布尔值" };
    }
    if (hasOwn(patch, "answerDelayMs") && !isIntegerValue(patch.answerDelayMs)) return { ok: false, message: "answerDelayMs 必须是整数" };
    if (hasOwn(patch, "maxCallDurationMs") && !isIntegerValue(patch.maxCallDurationMs)) {
      return { ok: false, message: "maxCallDurationMs 必须是整数" };
    }
    if (hasOwn(patch, "voice") && typeof patch.voice !== "string") return { ok: false, message: "voice 必须是字符串" };
    if (hasOwn(patch, "openingLine") && typeof patch.openingLine !== "string") return { ok: false, message: "openingLine 必须是字符串" };
    if (hasOwn(patch, "prompt") && typeof patch.prompt !== "string") return { ok: false, message: "prompt 必须是字符串" };
    if (hasOwn(patch, "speed") && (typeof patch.speed !== "number" || !isFinite(patch.speed))) {
      return { ok: false, message: "speed 必须是有限数值" };
    }
    if (hasOwn(patch, "enabledPhoneAccounts")) {
      if (!Array.isArray(patch.enabledPhoneAccounts)) return { ok: false, message: "enabledPhoneAccounts 必须是数组" };
      for (var phoneIndex = 0; phoneIndex < patch.enabledPhoneAccounts.length; phoneIndex += 1) {
        if (typeof patch.enabledPhoneAccounts[phoneIndex] !== "string") return { ok: false, message: "enabledPhoneAccounts 必须是字符串数组" };
      }
    }
    var lists = ["blacklist", "whitelist"];
    for (var listIndex = 0; listIndex < lists.length; listIndex += 1) {
      var listName = lists[listIndex];
      if (!hasOwn(patch, listName)) continue;
      if (!Array.isArray(patch[listName])) return { ok: false, message: listName + " 必须是数组" };
      for (var itemIndex = 0; itemIndex < patch[listName].length; itemIndex += 1) {
        if (typeof patch[listName][itemIndex] !== "string") return { ok: false, message: listName + " 必须是字符串数组" };
      }
    }
    var endpoints = ["asr", "llm", "tts"];
    for (var endpointIndex = 0; endpointIndex < endpoints.length; endpointIndex += 1) {
      if (!hasOwn(patch, endpoints[endpointIndex])) continue;
      // 后端只接受 {baseUrl,model} 对象；null 会被 RpcCodec 拒绝，清除端点必须用空对象值。
      var endpointProblem = validateEndpointPatch(patch[endpoints[endpointIndex]], endpoints[endpointIndex]);
      if (endpointProblem) return { ok: false, message: endpointProblem };
    }
    if (hasOwn(patch, "secrets")) {
      var secretProblem = validateSecretsPatch(patch.secrets);
      if (secretProblem) return { ok: false, message: secretProblem };
    }
    return { ok: true, write: { expectedRevision: payload.expectedRevision, patch: patch } };
  }

  // ---------------------------------------------------------------- 表单与补丁

  function formFromRedacted(redacted) {
    var config = (redacted && redacted.config) || {};
    var secrets = (redacted && redacted.secrets) || {};

    function endpointFor(kind) {
      var raw = config[ENDPOINT_FIELDS[kind]];
      return {
        baseUrl: raw && typeof raw.baseUrl === "string" ? raw.baseUrl : "",
        model: raw && typeof raw.model === "string" ? raw.model : "",
        secretState: secrets[kind] === "CONFIGURED" ? "CONFIGURED" : "MISSING",
        secretIntent: "keep",
        secretValue: "",
      };
    }

    return {
      enabled: config.enabled === true,
      mode: MODES.indexOf(config.mode) === -1 ? "BLACKLIST" : config.mode,
      blacklistText: Array.isArray(config.blacklist) ? config.blacklist.join("\n") : "",
      whitelistText: Array.isArray(config.whitelist) ? config.whitelist.join("\n") : "",
      region: typeof config.region === "string" && config.region !== "" ? config.region : "CN",
      handleUnknownNumbers: config.handleUnknownNumbers === true,
      answerDelayMs: isIntegerValue(config.answerDelayMs) ? String(config.answerDelayMs) : "3000",
      maxCallDurationMs: isIntegerValue(config.maxCallDurationMs) ? String(config.maxCallDurationMs) : "180000",
      asr: endpointFor("ASR"),
      llm: endpointFor("LLM"),
      tts: endpointFor("TTS"),
      voice: typeof config.voice === "string" ? config.voice : "",
      speed: typeof config.speed === "number" && isFinite(config.speed) ? String(config.speed) : "1",
      openingLine: typeof config.openingLine === "string" ? config.openingLine : DEFAULT_OPENING_LINE,
      prompt: typeof config.prompt === "string" ? config.prompt : "",
    };
  }

  function issue(code, field, message, blocking) {
    return { code: code, field: field, message: message, blocking: blocking !== false };
  }

  /** 字段归属页面（与 app.js 的渲染分桶共用同一套规则）。 */
  function pageForField(field) {
    var name = toText(field);
    if (/^(asr|llm|tts|voice|secrets)/.test(name)) return "ai";
    if (/^(openingLine|prompt|speed)/.test(name)) return "dialog";
    return "rules";
  }

  /** scope 为空/all 时返回全部问题；否则只保留本页字段的问题。 */
  function scopeIssues(issues, scope) {
    if (!scope || scope === "all") return issues;
    return issues.filter(function (item) {
      return pageForField(item.field) === scope;
    });
  }

  function issueText(code, field) {
    var label = field ? "（" + field + "）" : "";
    if (code === "RULE_NUMBER_OVERLAP") return "同一号码规范化后同时出现在黑名单与白名单" + label;
    if (code === "RULE_NUMBER_UNPARSEABLE") return "号码无法可靠解析或规范化为完整号码" + label;
    if (code === "REGION_UNSUPPORTED") return "地区代码不受支持" + label;
    if (code === "ANSWER_DELAY_OUT_OF_RANGE") return "接听延迟必须在 0–30000 ms" + label;
    if (code === "MAX_CALL_DURATION_OUT_OF_RANGE") return "最长 AI 通话必须在 10000–600000 ms" + label;
    if (code === "CONFIG_CONFLICT") return "配置版本冲突，本次没有覆盖写入；请重新加载再保存";
    if (code === "PEER_VERSION_MISMATCH") return "APK 或 Google 电话已升级，配对失效，需要重新配对";
    if (code === "SECRET_EMPTY") return "密钥不能为空";
    return "后端拒绝该配置" + label;
  }

  function validateForm(form, scope) {
    var issues = [];
    var source = form || {};

    if (MODES.indexOf(source.mode) === -1) {
      issues.push(issue("MODE_INVALID", "mode", "名单模式只能是黑名单/白名单/组合"));
    }

    var region = trimText(source.region).toUpperCase();
    if (!/^[A-Z]{2}$/.test(region)) {
      issues.push(issue("REGION_UNSUPPORTED", "region", "地区必须是两位 ISO 国家/地区代码，例如 CN"));
    }

    var delay = parseIntegerField(source.answerDelayMs);
    if (delay === null) {
      issues.push(issue("ANSWER_DELAY_NOT_INTEGER", "answerDelayMs", "接听延迟必须是整数毫秒"));
    } else if (delay < 0 || delay > 30000) {
      issues.push(issue("ANSWER_DELAY_OUT_OF_RANGE", "answerDelayMs", "接听延迟必须在 0–30000 ms"));
    }

    var duration = parseIntegerField(source.maxCallDurationMs);
    if (duration === null) {
      issues.push(issue("MAX_CALL_DURATION_NOT_INTEGER", "maxCallDurationMs", "最长通话必须是整数毫秒"));
    } else if (duration < 10000 || duration > 600000) {
      issues.push(issue("MAX_CALL_DURATION_OUT_OF_RANGE", "maxCallDurationMs", "最长 AI 通话必须在 10000–600000 ms"));
    }

    var blacklist = parseList(source.blacklistText);
    var whitelist = parseList(source.whitelistText);
    [["blacklist", blacklist], ["whitelist", whitelist]].forEach(function (pair) {
      pair[1].entries.forEach(function (entry) {
        if (!isPhoneLike(entry)) {
          issues.push(issue("RULE_NUMBER_UNPARSEABLE", pair[0], "名单项无法解析为号码：" + entry.slice(0, 32)));
        }
      });
    });
    var blacklistKeys = {};
    blacklist.entries.forEach(function (entry) {
      var key = normalizeDigits(entry);
      if (key) blacklistKeys[key] = entry;
    });
    whitelist.entries.forEach(function (entry) {
      var key = normalizeDigits(entry);
      if (key && hasOwn(blacklistKeys, key)) {
        issues.push(issue("RULE_NUMBER_OVERLAP", "blacklist,whitelist", "号码同时出现在两个名单：" + entry.slice(0, 32)));
      }
    });

    SECRET_KINDS.forEach(function (kind) {
      var field = ENDPOINT_FIELDS[kind];
      var endpoint = source[field] || {};
      var baseUrl = trimText(endpoint.baseUrl);
      var model = trimText(endpoint.model);
      var urlProblem = validateBaseUrl(baseUrl);
      if (urlProblem) issues.push(issue(urlProblem.code, field + ".baseUrl", urlProblem.message));
      if ((baseUrl === "") !== (model === "")) {
        issues.push(issue("ENDPOINT_INCOMPLETE", field, "地址与模型必须同时填写或同时留空；同时留空表示清除该项 API"));
      }
      var intent = endpoint.secretIntent || "keep";
      if (intent === "replace") {
        if (trimText(endpoint.secretValue) === "") {
          issues.push(issue("SECRET_EMPTY", field + ".secretValue", "替换密钥时不能留空"));
        } else if (trimText(endpoint.secretValue) !== toText(endpoint.secretValue)) {
          issues.push(issue("SECRET_WHITESPACE", field + ".secretValue", "密钥首尾包含空白，已按去除空白后的值提交", false));
        }
      } else if (intent === "clear") {
        if (trimText(endpoint.secretValue) !== "") {
          issues.push(issue("SECRET_CONFLICT", field + ".secretValue", "不能同时清除并替换密钥"));
        }
      } else if (intent !== "keep") {
        issues.push(issue("SECRET_INTENT_INVALID", field + ".secretIntent", "密钥动作非法"));
      }
    });

    var speed = toText(source.speed).trim();
    if (speed === "" || !isFinite(Number(speed))) {
      issues.push(issue("SPEED_INVALID", "speed", "语速必须是数值"));
    } else if (Number(speed) < SPEED_MIN || Number(speed) > SPEED_MAX) {
      issues.push(issue("SPEED_OUT_OF_RANGE", "speed", "语速必须在 " + SPEED_MIN + "–" + SPEED_MAX));
    }

    if (toText(source.openingLine).length > 200) {
      issues.push(issue("OPENING_LINE_LONG", "openingLine", "开场白超过 200 字，可能影响回合时延", false));
    }
    if (toText(source.prompt).length > 4000) {
      issues.push(issue("PROMPT_LONG", "prompt", "提示词超过 4000 字，可能超出回合预算", false));
    }
    if (toText(source.voice).length > 64) {
      issues.push(issue("VOICE_LONG", "voice", "音色标识超过 64 字符", false));
    }

    if (source.mode === "WHITELIST" || source.mode === "COMBINED") {
      if (whitelist.entries.length === 0) {
        issues.push(issue("WHITELIST_EMPTY", "whitelist", "空白名单：该模式下会接管所有可识别的号码", false));
      }
    }
    if (source.mode === "BLACKLIST" && blacklist.entries.length === 0) {
      issues.push(issue("BLACKLIST_EMPTY", "blacklist", "空黑名单：不会自动接管任何号码", false));
    }

    var scoped = scopeIssues(issues, scope);
    return {
      issues: scoped,
      blocking: scoped.filter(function (item) {
        return item.blocking;
      }),
      warnings: scoped.filter(function (item) {
        return !item.blocking;
      }),
    };
  }

  function endpointPatch(endpoint) {
    var baseUrl = trimText((endpoint || {}).baseUrl);
    var model = trimText((endpoint || {}).model);
    // 空端点表示清除（后端会规范化为 null，绝不持久化空端点）。
    return { baseUrl: baseUrl, model: model };
  }

  function secretsPatch(form) {
    var patch = {};
    SECRET_KINDS.forEach(function (kind) {
      var endpoint = (form || {})[ENDPOINT_FIELDS[kind]] || {};
      var intent = endpoint.secretIntent || "keep";
      if (intent === "replace") {
        patch[kind] = { action: "replace", value: trimText(endpoint.secretValue) };
      } else if (intent === "clear") {
        patch[kind] = { action: "clear" };
      } else {
        patch[kind] = { action: "keep" };
      }
    });
    return patch;
  }

  function buildConfigPatch(form, baseline, scope) {
    var validation = validateForm(form, scope);
    if (validation.blocking.length > 0) {
      return {
        ok: false,
        code: CODE.VALIDATION_FAILED,
        issues: validation.issues,
        blocking: validation.blocking,
        warnings: validation.warnings,
      };
    }
    var baseConfig = baseline && baseline.config ? baseline.config : {};
    var revision = isIntegerValue(baseConfig.revision) ? baseConfig.revision : 0;
    var patch = {
      enabled: source(form, "enabled") === true,
      mode: form.mode,
      blacklist: parseList(form.blacklistText).entries,
      whitelist: parseList(form.whitelistText).entries,
      region: trimText(form.region).toUpperCase(),
      handleUnknownNumbers: form.handleUnknownNumbers === true,
      answerDelayMs: parseIntegerField(form.answerDelayMs),
      maxCallDurationMs: parseIntegerField(form.maxCallDurationMs),
      asr: endpointPatch(form.asr),
      llm: endpointPatch(form.llm),
      tts: endpointPatch(form.tts),
      voice: trimText(form.voice),
      speed: Number(toText(form.speed).trim()),
      openingLine: toText(form.openingLine),
      prompt: toText(form.prompt),
      secrets: secretsPatch(form),
    };
    // 页面不编辑 PhoneAccount，因此补丁绝不覆盖它：避免把 APK 写入的启用项清空。
    var pageKeys = scope && PAGE_PATCH_KEYS[scope] ? PAGE_PATCH_KEYS[scope] : null;
    if (pageKeys) {
      var scopedPatch = {};
      pageKeys.forEach(function (key) {
        if (hasOwn(patch, key)) scopedPatch[key] = patch[key];
      });
      patch = scopedPatch;
    }
    return {
      ok: true,
      issues: validation.issues,
      warnings: validation.warnings,
      write: { expectedRevision: revision, patch: patch },
    };
  }

  function source(object, key) {
    return object ? object[key] : undefined;
  }

  function issuesFromBackend(code, message) {
    var text = toText(message);
    var name = toText(code);
    if (name === "config_invalid") {
      var marker = "config rejected:";
      var index = text.indexOf(marker);
      if (index === -1) {
        return [issue("CONFIG_INVALID", "", "配置未通过后端校验", true)];
      }
      var rest = trimText(text.slice(index + marker.length));
      var pieces = rest.split(/,(?=[A-Z][A-Z0-9_]*@)/);
      var mapped = [];
      pieces.forEach(function (piece) {
        var entry = trimText(piece);
        if (entry === "") return;
        var at = entry.indexOf("@");
        var issueCode = at === -1 ? entry : entry.slice(0, at);
        var field = at === -1 ? "" : entry.slice(at + 1);
        mapped.push(issue(issueCode, field, issueText(issueCode, field), true));
      });
      return mapped.length > 0 ? mapped : [issue("CONFIG_INVALID", "", "配置未通过后端校验", true)];
    }
    if (name === "config_conflict") {
      return [issue("CONFIG_CONFLICT", "", issueText("CONFIG_CONFLICT"), true)];
    }
    if (name === "secret_invalid") {
      return [issue("SECRET_EMPTY", "secrets", issueText("SECRET_EMPTY"), true)];
    }
    if (name === "peer_version_mismatch" || name === "peer_unpaired") {
      return [issue("PEER_VERSION_MISMATCH", "pairing", issueText("PEER_VERSION_MISMATCH"), true)];
    }
    if (name === "transport_failed" || name === "bridge_unavailable") {
      return [issue("BRIDGE_UNREACHABLE", "", "桥接服务不可达，配置没有保存", true)];
    }
    return [issue(name || "UNKNOWN_ERROR", "", errorText(name), true)];
  }

  function isRepairRequired(code) {
    return code === "peer_version_mismatch" || code === "peer_unpaired";
  }

  /**
   * 保存按钮可用性状态机（Critical 1 的可测试核心）：
   * 只有真实桥 + 可保存 + 已加载基线（已知 revision）+ 没有正在进行的保存时才允许保存。
   * 缺少基线时禁止保存，而不是回退到 DEMO revision 0 去覆盖真实配置。
   */
  function computeSaveAvailability(input) {
    var source = input || {};
    var mode = toText(source.mode);
    if (mode !== "kernelsu") return { enabled: false, reason: mode === "readonly" ? "readonly" : "demo" };
    if (source.canSave !== true) return { enabled: false, reason: "readonly" };
    if (source.saving === true) return { enabled: false, reason: "saving" };
    if (source.loaded !== true) return { enabled: false, reason: "not_loaded" };
    if (!isIntegerValue(source.revision) || source.revision < 0) return { enabled: false, reason: "revision_unknown" };
    return { enabled: true, reason: "ready" };
  }

  /**
   * 总开关是独立写入：只提交 { enabled }，不夹带同一页面上其它未保存字段，
   * 尤其不提交正在编辑的密钥意图。开关不应因别的表单字段非法而被阻断。
   */
  function buildEnabledPatch(enabled, baseline) {
    if (typeof enabled !== "boolean") {
      return { ok: false, code: CODE.PAYLOAD_INVALID, message: "enabled 必须是布尔值" };
    }
    var baseConfig = baseline && baseline.config ? baseline.config : {};
    var revision = isIntegerValue(baseConfig.revision) ? baseConfig.revision : 0;
    return { ok: true, write: { expectedRevision: revision, patch: { enabled: enabled } } };
  }

  function saveEnabled(bridge, enabled, baseline) {
    var built = buildEnabledPatch(enabled, baseline);
    if (!built.ok) {
      return Promise.resolve({ ok: false, code: built.code, message: built.message, issues: [], warnings: [], source: "client" });
    }
    return Promise.resolve(bridge.call("config-set", built.write)).then(function (result) {
      if (!result || result.ok !== true) {
        var code = (result && result.code) || CODE.TRANSPORT_FAILED;
        return {
          ok: false,
          code: code,
          message: (result && result.message) || errorText(code),
          issues: issuesFromBackend(code, result && result.message),
          warnings: [],
          source: (result && result.source) || "none",
          conflict: code === "config_conflict",
          repair: isRepairRequired(code),
        };
      }
      return {
        ok: true,
        source: "bridge",
        data: result.data,
        form: formFromRedacted(result.data),
        warnings: [],
        issues: [],
      };
    });
  }

  function saveConfig(bridge, form, baseline, scope) {
    var built = buildConfigPatch(form, baseline, scope);
    if (!built.ok) {
      return Promise.resolve({
        ok: false,
        code: built.code,
        issues: built.blocking,
        warnings: built.warnings,
        source: "client",
      });
    }
    return Promise.resolve(bridge.call("config-set", built.write)).then(function (result) {
      if (!result || result.ok !== true) {
        var code = (result && result.code) || CODE.TRANSPORT_FAILED;
        return {
          ok: false,
          code: code,
          message: (result && result.message) || errorText(code),
          issues: issuesFromBackend(code, result && result.message),
          warnings: built.warnings,
          source: (result && result.source) || "none",
          conflict: code === "config_conflict",
          repair: isRepairRequired(code),
        };
      }
      return {
        ok: true,
        source: "bridge",
        data: result.data,
        form: formFromRedacted(result.data),
        warnings: built.warnings,
        issues: [],
      };
    });
  }

  // ---------------------------------------------------------------- 演示数据

  var DEMO = {
    demo: true,
    note: "演示数据：当前没有连接 KernelSU 管理器的真实桥接，下列数值为模拟示例，任何保存都会被拒绝。",
    status: {
      bridgeVersion: "0.0-demo",
      protocolVersion: 1,
      revision: 0,
      enabled: false,
      paired: false,
      uptimeMs: 0,
      activeConnections: 0,
      rejectedConnections: 0,
    },
    config: {
      config: {
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
        openingLine: DEFAULT_OPENING_LINE,
        prompt: "",
        revision: 0,
      },
      secrets: { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" },
    },
    diagnose: {
      environment: {
        apiLevel: 0,
        romFingerprint: "demo",
        kernelVersion: "demo",
        dialerPackageVersion: "demo",
        frameworkVersion: "demo",
        bridgeVersion: "0.0-demo",
        audioPolicyDigest: "demo",
        simCarrier: "unknown",
        transport: "unknown",
        route: "unknown",
      },
      downlinkExtraction: "NOT_DETECTED",
      uplinkInjection: "NOT_DETECTED",
      microphoneIsolation: "NOT_DETECTED",
      privilegedTermination: "NOT_DETECTED",
      checkedAtMs: 0,
      notes: "demo",
    },
  };

  return {
    MODULE_ID: MODULE_ID,
    MODULE_ROOT: MODULE_ROOT,
    DATA_ROOT: DATA_ROOT,
    INBOX_DIR: INBOX_DIR,
    INBOX_PATH: INBOX_PATH,
    LOG_PATH: LOG_PATH,
    OPERATIONS: OPERATIONS.slice(),
    PATCH_KEYS: PATCH_KEYS.slice(),
    SECRET_KINDS: SECRET_KINDS.slice(),
    ENDPOINT_FIELDS: ENDPOINT_FIELDS,
    MODES: MODES.slice(),
    CONFIG_WRITE_LIMIT_BYTES: CONFIG_WRITE_LIMIT_BYTES,
    DEFAULT_OPENING_LINE: DEFAULT_OPENING_LINE,
    CODE: CODE,
    DEMO: DEMO,
    detectSupport: detectSupport,
    createBridge: createBridge,
    validateConfigWritePayload: validateConfigWritePayload,
    formFromRedacted: formFromRedacted,
    validateForm: validateForm,
    buildConfigPatch: buildConfigPatch,
    buildEnabledPatch: buildEnabledPatch,
    saveEnabled: saveEnabled,
    saveConfig: saveConfig,
    computeSaveAvailability: computeSaveAvailability,
    pageForField: pageForField,
    issuesFromBackend: issuesFromBackend,
    isRepairRequired: isRepairRequired,
    errorText: errorText,
    validateBaseUrl: validateBaseUrl,
    normalizeDigits: normalizeDigits,
    isPhoneLike: isPhoneLike,
    parseList: parseList,
    setText: setText,
    redactText: redactText,
    toBase64Utf8: toBase64Utf8,
    fromBase64Utf8: fromBase64Utf8,
  };
});

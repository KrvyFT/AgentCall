/**
 * AgentCall 中文 WebUI 交互层。
 *
 * 约束（spec §11 / 任务 8）：
 * - 只通过 bridge.js 的固定 CLI 映射读写；本文件不拼接任何 shell 命令，也不发网络请求。
 * - 所有展示文本一律写 textContent；配置中可能出现 < > & 等字符，绝不作为 HTML 解释。
 * - 没有真实桥接时进入明确只读/演示态，禁用保存并显式标注演示数据，绝不伪造成功。
 */
(function () {
  "use strict";

  var B = window.AgentCallBridge;
  var bridge = B.createBridge({ scope: window });

  var state = {
    baseline: null,
    form: null,
    status: null,
    diagnose: null,
    secretState: { ASR: "MISSING", LLM: "MISSING", TTS: "MISSING" },
    secretIntent: { ASR: "keep", LLM: "keep", TTS: "keep" },
    // 分页未保存标记：保存某一页只清除该页的 chip，绝不连带清除其它页面。
    dirty: { rules: false, ai: false, dialog: false },
    saving: false,
    loaded: false,
    saveEnabled: false,
    demo: bridge.mode === "demo",
    canSave: bridge.mode === "kernelsu",
  };

  var KIND_LABEL = { ASR: "语音识别（ASR）", LLM: "对话模型（LLM）", TTS: "语音合成（TTS）" };
  var CAPABILITY_TEXT = {
    NOT_DETECTED: "未检测",
    INTERFACE_VISIBLE: "接口可见（不等于可用）",
    PENDING_PEER_CONFIRMATION: "待对端确认",
    VERIFIED: "已验证",
    UNSUPPORTED: "不支持",
  };
  var CAPABILITY_KEY = {
    downlinkExtraction: "下行提取",
    uplinkInjection: "上行注入",
    microphoneIsolation: "麦克风隔离",
    privilegedTermination: "特权终止",
  };

  var FIELD_INPUTS = {
    mode: "rules-mode",
    blacklist: "rules-blacklist",
    whitelist: "rules-whitelist",
    "blacklist,whitelist": "rules-blacklist",
    region: "rules-region",
    answerDelayMs: "rules-delay",
    maxCallDurationMs: "rules-duration",
    "asr.baseUrl": "ai-asr-url",
    "llm.baseUrl": "ai-llm-url",
    "tts.baseUrl": "ai-tts-url",
    asr: "ai-asr-url",
    llm: "ai-llm-url",
    tts: "ai-tts-url",
    "asr.secretValue": "ai-asr-secret",
    "llm.secretValue": "ai-llm-secret",
    "tts.secretValue": "ai-tts-secret",
    voice: "ai-voice",
    speed: "dlg-speed",
    openingLine: "dlg-opening",
    prompt: "dlg-prompt",
  };

  function $(id) {
    return document.getElementById(id);
  }

  function setText(element, value) {
    B.setText(element, value);
  }

  function clearChildren(element) {
    if (!element) return;
    while (element.firstChild) element.removeChild(element.firstChild);
  }

  function setBadge(element, text, kind) {
    if (!element) return;
    element.className = "badge badge-" + kind;
    setText(element, text);
  }

  function formatDuration(ms) {
    var value = Number(ms);
    if (!isFinite(value) || value < 0) return "—";
    var seconds = Math.floor(value / 1000);
    if (seconds < 60) return seconds + " 秒";
    var minutes = Math.floor(seconds / 60);
    var restSeconds = seconds % 60;
    if (minutes < 60) return minutes + " 分 " + restSeconds + " 秒";
    var hours = Math.floor(minutes / 60);
    return hours + " 小时 " + (minutes % 60) + " 分";
  }

  function formatTime(ms) {
    var value = Number(ms);
    if (!isFinite(value) || value <= 0) return "—";
    var date = new Date(value);
    function pad(item) {
      return item < 10 ? "0" + item : String(item);
    }
    return pad(date.getMonth() + 1) + "-" + pad(date.getDate()) + " " + pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
  }

  // -------------------------------------------------------------- 提示与横幅

  var toastTimer = null;

  function toast(message, isError) {
    var element = $("toast");
    setText(element, message);
    element.className = isError ? "toast is-err" : "toast";
    element.hidden = false;
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      element.hidden = true;
    }, 2600);
  }

  function hideBanner() {
    var banner = $("banner");
    banner.hidden = true;
    banner.className = "banner";
    clearChildren($("banner-actions"));
    setText($("banner-text"), "");
  }

  function showBanner(kind, text, actions) {
    var banner = $("banner");
    banner.hidden = false;
    banner.className = "banner is-" + kind;
    setText($("banner-text"), text);
    var container = $("banner-actions");
    clearChildren(container);
    (actions || []).forEach(function (action) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = action.primary ? "btn btn-primary btn-small" : "btn btn-secondary btn-small";
      setText(button, action.label);
      button.addEventListener("click", action.onClick);
      container.appendChild(button);
    });
  }

  /** WebView 中 window.confirm 可能被禁用：用横幅做二次确认，避免按钮看起来无效。 */
  function confirmAction(message, confirmLabel, onConfirm) {
    showBanner("warn", message, [
      {
        label: confirmLabel,
        primary: true,
        onClick: function () {
          hideBanner();
          onConfirm();
        },
      },
      { label: "取消", onClick: hideBanner },
    ]);
  }

  // -------------------------------------------------------------- 最近错误

  var ERROR_STORAGE_KEY = "agentcall.recentErrors";
  var ERROR_LIMIT = 10;

  function readRecentErrors() {
    try {
      var raw = window.localStorage.getItem(ERROR_STORAGE_KEY);
      var parsed = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed.slice(-ERROR_LIMIT) : [];
    } catch (error) {
      return [];
    }
  }

  function recordError(code, message) {
    var entries = readRecentErrors();
    entries.push({ at: Date.now(), code: String(code || "unknown"), message: B.redactText(String(message || "")).slice(0, 240) });
    while (entries.length > ERROR_LIMIT) entries.shift();
    try {
      window.localStorage.setItem(ERROR_STORAGE_KEY, JSON.stringify(entries));
    } catch (error) {
      /* 存储不可用时仅在本次会话内可见 */
    }
    renderRecentErrors();
  }

  function renderRecentErrors() {
    var list = $("ov-errors");
    clearChildren(list);
    var entries = readRecentErrors();
    if (entries.length === 0) {
      var empty = document.createElement("li");
      empty.className = "issue issue-info";
      setText(empty, "本次没有记录到错误。");
      list.appendChild(empty);
      return;
    }
    entries
      .slice()
      .reverse()
      .forEach(function (entry) {
        var item = document.createElement("li");
        setText(item, formatTime(entry.at) + " · " + entry.code + " · " + entry.message);
        list.appendChild(item);
      });
  }

  function noteFailure(result) {
    if (!result) return;
    recordError(result.code || "unknown", result.message || "");
    if (result.repair || B.isRepairRequired(result.code)) showRepairBanner();
  }

  function showRepairBanner() {
    showBanner("err", "配对已失效：" + B.errorText("peer_version_mismatch"), [
      { label: "重新配对", primary: true, onClick: repairPairing },
      { label: "稍后", onClick: hideBanner },
    ]);
  }

  // -------------------------------------------------------------- 问题渲染

  function renderIssues(listElement, issues) {
    if (!listElement) return;
    clearChildren(listElement);
    (issues || []).forEach(function (item) {
      var entry = document.createElement("li");
      entry.className = "issue " + (item.blocking ? "issue-block" : item.severity === "info" ? "issue-info" : "issue-warn");
      var text = document.createElement("span");
      setText(text, item.message || item.code);
      entry.appendChild(text);
      if (item.code) {
        var code = document.createElement("span");
        code.className = "issue-code";
        setText(code, item.code);
        entry.appendChild(code);
      }
      listElement.appendChild(entry);
    });
  }

  function markInvalidFields(issues) {
    Object.keys(FIELD_INPUTS).forEach(function (field) {
      var element = $(FIELD_INPUTS[field]);
      if (element) element.className = element.className.replace(/\s*is-invalid/g, "");
    });
    (issues || []).forEach(function (item) {
      if (!item.blocking) return;
      var element = $(FIELD_INPUTS[item.field]);
      if (element) element.className += " is-invalid";
    });
  }

  /**
   * 阻断项与非阻断提醒按字段归到各自页面；提醒绝不覆盖阻断项
   * （否则一次保存失败后用户只看到“空白名单”而看不到真正要修的错误）。
   */
  function renderPageIssues(blocking, warnings) {
    var items = (blocking || []).concat(warnings || []);
    var buckets = { rules: [], ai: [], dialog: [] };
    items.forEach(function (item) {
      buckets[B.pageForField(item.field)].push(item);
    });
    renderIssues($("rules-issues"), buckets.rules);
    renderIssues($("dlg-issues"), buckets.dialog);
    renderIssues($("ai-issues"), buckets.ai);
    // 每个端点卡片只显示与自己相关的条目
    ["ASR", "LLM", "TTS"].forEach(function (kind) {
      var lower = kind.toLowerCase();
      renderIssues(
        $("ai-" + lower + "-issues"),
        buckets.ai.filter(function (item) {
          return String(item.field || "").indexOf(lower) === 0;
        }),
      );
    });
    markInvalidFields(blocking);
  }

  /** 名单语义提醒单独放在规则页顶部，保持醒目；不改写已渲染的阻断项。 */
  function renderWarnings(warnings) {
    var relevant = (warnings || []).filter(function (item) {
      return item.code === "WHITELIST_EMPTY" || item.code === "BLACKLIST_EMPTY";
    });
    var box = $("rules-warn");
    if (!box) return;
    if (relevant.length === 0) {
      box.hidden = true;
      setText(box, "");
      return;
    }
    box.hidden = false;
    box.className = "notice notice-warn";
    setText(box, relevant.map(function (item) { return item.message; }).join("；"));
  }

  // -------------------------------------------------------------- 表单读写

  function endpointFromDom(kind) {
    var lower = kind.toLowerCase();
    return {
      baseUrl: $("ai-" + lower + "-url").value,
      model: $("ai-" + lower + "-model").value,
      secretState: state.secretState[kind],
      secretIntent: state.secretIntent[kind],
      secretValue: $("ai-" + lower + "-secret").value,
    };
  }

  function readForm() {
    return {
      enabled: $("ov-enabled").checked,
      mode: $("rules-mode").value,
      blacklistText: $("rules-blacklist").value,
      whitelistText: $("rules-whitelist").value,
      region: $("rules-region").value,
      handleUnknownNumbers: $("rules-unknown").checked,
      answerDelayMs: $("rules-delay").value,
      maxCallDurationMs: $("rules-duration").value,
      asr: endpointFromDom("ASR"),
      llm: endpointFromDom("LLM"),
      tts: endpointFromDom("TTS"),
      voice: $("ai-voice").value,
      speed: $("dlg-speed").value,
      openingLine: $("dlg-opening").value,
      prompt: $("dlg-prompt").value,
    };
  }

  function resetSecretControls(kind) {
    var lower = kind.toLowerCase();
    state.secretIntent[kind] = "keep";
    $("ai-" + lower + "-secret").value = "";
    $("ai-" + lower + "-secret-wrap").hidden = true;
    $("ai-" + lower + "-replace").hidden = false;
    $("ai-" + lower + "-clear").hidden = false;
    $("ai-" + lower + "-cancel").hidden = true;
    updateSecretBadge(kind);
  }

  function updateSecretBadge(kind) {
    var lower = kind.toLowerCase();
    var badge = $("ai-" + lower + "-secret-state");
    var intent = state.secretIntent[kind];
    if (intent === "clear") {
      setBadge(badge, "将清除", "warn");
    } else if (intent === "replace") {
      setBadge(badge, "将替换", "warn");
    } else if (state.secretState[kind] === "CONFIGURED") {
      setBadge(badge, "已配置", "ok");
    } else {
      setBadge(badge, "未配置", "neutral");
    }
  }

  /** 分页面回填：保存某一页后只刷新该页，避免清掉其它页面尚未保存的编辑。 */
  function fillRules(form) {
    $("rules-mode").value = form.mode;
    $("rules-blacklist").value = form.blacklistText;
    $("rules-whitelist").value = form.whitelistText;
    $("rules-region").value = form.region;
    $("rules-unknown").checked = form.handleUnknownNumbers === true;
    $("rules-delay").value = form.answerDelayMs;
    $("rules-duration").value = form.maxCallDurationMs;
  }

  function fillAi(form) {
    $("ai-voice").value = form.voice;
    ["ASR", "LLM", "TTS"].forEach(function (kind) {
      var lower = kind.toLowerCase();
      var endpoint = form[lower];
      $("ai-" + lower + "-url").value = endpoint.baseUrl;
      $("ai-" + lower + "-model").value = endpoint.model;
      state.secretState[kind] = endpoint.secretState;
      resetSecretControls(kind);
    });
  }

  function fillDialog(form) {
    $("dlg-speed").value = form.speed;
    $("dlg-opening").value = form.openingLine;
    $("dlg-prompt").value = form.prompt;
  }

  function fillForm(form) {
    $("ov-enabled").checked = form.enabled === true;
    fillRules(form);
    fillAi(form);
    fillDialog(form);
    renderEnabledState();
  }

  function fillSavedPage(scope, form) {
    if (scope === "rules") fillRules(form);
    else if (scope === "ai") fillAi(form);
    else if (scope === "dialog") fillDialog(form);
    else fillForm(form);
  }

  function renderEnabledState() {
    var enabled = $("ov-enabled").checked;
    setText($("ov-enabled-state"), enabled ? "已开启（自动接管按规则执行）" : "已关闭（不自动接听）");
  }

  // -------------------------------------------------------------- 状态渲染

  function setModeBadge() {
    var badge = $("mode-badge");
    var note = $("mode-note");
    if (bridge.mode === "kernelsu") {
      setBadge(badge, "真实桥接", "ok");
      setText(note, "已连接 KernelSU 管理器：配置经固定 CLI 与 stdin 文件通道提交，保存前需通过后端 CAS 校验。");
    } else if (bridge.mode === "readonly") {
      setBadge(badge, "只读", "warn");
      setText(
        note,
        bridge.support.reason === "io_missing"
          ? "当前管理器没有受控文件写入接口：可读取状态，但保存已禁用（密钥不会通过 base64 命令行参数提交）。"
          : "moduleInfo() 给出的模块目录不可信：已禁用需要 CLI 的命令与保存；固定路径日志仍可读取。",
      );
    } else {
      setBadge(badge, "演示（只读）", "warn");
      setText(note, "未检测到 KernelSU 管理器注入的真实桥接：本页显示演示数据，任何保存都会被拒绝。");
    }
  }

  /**
   * 保存按钮可用性：唯一入口。真实桥 + 已加载基线（已知 revision）+ 非保存中才启用；
   * demo/readonly/加载失败/未知 revision 一律禁用（fail closed）。
   */
  function applySaveAvailability() {
    var baselineConfig = state.baseline && state.baseline.config ? state.baseline.config : null;
    var availability = B.computeSaveAvailability({
      mode: bridge.mode,
      canSave: state.canSave,
      saving: state.saving,
      loaded: state.loaded === true,
      revision: baselineConfig && typeof baselineConfig.revision === "number" ? baselineConfig.revision : null,
    });
    state.saveEnabled = availability.enabled;
    ["ov-save-switch", "rules-save", "ai-save", "dlg-save"].forEach(function (id) {
      var button = $(id);
      if (button) button.disabled = !availability.enabled;
    });
    return availability;
  }

  /** 只读/演示态：配置输入与密钥动作都不可用，避免“能改但存不了”的误导。 */
  function disableConfigEditing() {
    Array.prototype.forEach.call(
      document.querySelectorAll("#page-overview input, #page-rules input, #page-rules select, #page-rules textarea, #page-ai input, #page-dialog input, #page-dialog textarea"),
      function (element) {
        element.disabled = true;
      },
    );
    ["ASR", "LLM", "TTS"].forEach(function (kind) {
      ["replace", "clear", "cancel", "test"].forEach(function (action) {
        var button = $("ai-" + kind.toLowerCase() + "-" + action);
        if (button) button.disabled = true;
      });
    });
  }

  function renderStatus(status) {
    if (!status) {
      setText($("ov-bridge-state"), "不可达");
      renderLiveOverview();
      return;
    }
    setText($("ov-bridge-state"), "status 成功（" + formatTime(Date.now()) + "）");
    setText($("ov-paired"), status.paired === true ? "已配对" : "未配对（需要 root CLI 配对）");
    setText($("ov-bridge-version"), status.bridgeVersion || "—");
    setText($("ov-protocol-version"), status.protocolVersion === undefined ? "—" : String(status.protocolVersion));
    setText($("ov-uptime"), formatDuration(status.uptimeMs));
    setText(
      $("ov-connections"),
      String(status.activeConnections === undefined ? "—" : status.activeConnections) +
        " / " +
        String(status.rejectedConnections === undefined ? "—" : status.rejectedConnections),
    );
    renderLiveOverview();
  }

  /** 只读取后端确实返回的字段；没有就是“未知”，绝不填充猜测值。 */
  function liveStatusValue(key) {
    if (!state.status || !Object.prototype.hasOwnProperty.call(state.status, key)) return undefined;
    var value = state.status[key];
    return value === undefined || value === null ? undefined : value;
  }

  /**
   * 概览实时值（阶段 B 起由桥接 status 提供）：
   * - dialerDefaultRole: boolean，或字符串 "unknown"（特权进程无法可靠确定）；
   * - hookConnected: boolean，或字符串 "unknown"（未获得监督状态）；
   * 只按真实布尔值渲染，其余一律显示“未知”，不显示猜测结论。
   */
  function renderLiveOverview() {
    var role = liveStatusValue("dialerDefaultRole");
    if (role === true) {
      setText($("ov-dialer-role"), "已设为默认拨号器");
      setBadge($("ov-dialer-badge"), "已确认", "ok");
      setText($("ov-dialer-note"), "该值来自桥接 status 的真实字段。");
    } else if (role === false) {
      setText($("ov-dialer-role"), "未设为默认拨号器");
      setBadge($("ov-dialer-badge"), "不满足", "warn");
      setText($("ov-dialer-note"), "自动接管需要 Google 电话为默认拨号器；该值来自桥接 status。");
    } else {
      setText($("ov-dialer-role"), "未知（后端无法确定）");
      setBadge($("ov-dialer-badge"), "未知", "neutral");
      setText($("ov-dialer-note"), "桥接没有给出确定的默认拨号器角色（status 不可达或返回 unknown）：只显示“未知”，不显示猜测结论。");
    }

    var hook = liveStatusValue("hookConnected");
    if (hook === true) {
      setText($("ov-hook-state"), "已连接");
      setBadge($("ov-hook-badge"), "已确认", "ok");
      setText($("ov-hook-note"), "该值来自桥接 status 的真实字段。");
    } else if (hook === false) {
      setText($("ov-hook-state"), "未连接");
      setBadge($("ov-hook-badge"), "未连接", "warn");
      setText($("ov-hook-note"), "桥接报告 Hook 未连接；未连接时不自动接管。");
    } else {
      setText($("ov-hook-state"), "未知（后端未上报）");
      setBadge($("ov-hook-badge"), "未知", "neutral");
      setText($("ov-hook-note"), "桥接未返回 Hook 心跳状态（或值为 unknown）：只显示“未知”，不伪造“已连接/未连接”结论。");
    }
  }

  function renderStatusUnavailable(result) {
    setText($("ov-bridge-state"), "不可达：" + (result && result.code ? result.code : "unknown"));
    setText($("ov-paired"), "—");
    setText($("ov-bridge-version"), "—");
    setText($("ov-protocol-version"), "—");
    setText($("ov-uptime"), "—");
    setText($("ov-connections"), "—");
    // status 刷新失败后不得继续回显上一次的实时值（否则死掉的桥接仍显示“已连接/
    // 已设为默认拨号器”）。清空后 renderLiveOverview 统一显示“未知”。
    state.status = null;
    renderLiveOverview();
  }

  function renderDiagnose(report) {
    var env = (report && report.environment) || {};
    var envList = $("diag-env");
    clearChildren(envList);
    var envRows = [
      ["Android SDK", String(env.apiLevel === undefined ? "—" : env.apiLevel)],
      ["ROM 指纹", env.romFingerprint || "—"],
      ["内核版本", env.kernelVersion || "—"],
      ["Google 电话版本", env.dialerPackageVersion || "—"],
      ["Xposed 框架版本", env.frameworkVersion || "—"],
      ["桥接版本", env.bridgeVersion || "—"],
      ["音频策略摘要", env.audioPolicyDigest || "—"],
      ["SIM/承载/路由", [env.simCarrier || "—", env.transport || "—", env.route || "—"].join(" / ")],
      ["探测时间", formatTime(report && report.checkedAtMs)],
      ["备注（脱敏）", B.redactText(report && report.notes ? report.notes : "—")],
    ];
    envRows.forEach(function (row) {
      appendDefinition(envList, row[0], row[1]);
    });

    var capList = $("diag-cap");
    clearChildren(capList);
    Object.keys(CAPABILITY_KEY).forEach(function (key) {
      var status = report ? report[key] : undefined;
      appendDefinition(capList, CAPABILITY_KEY[key], CAPABILITY_TEXT[status] || "未检测");
    });

    setText($("ov-dialer-version"), env.dialerPackageVersion || "—");
    setText($("ov-api-level"), env.apiLevel === undefined ? "—" : String(env.apiLevel));
    setText($("ov-framework-version"), env.frameworkVersion || "—");
    setText($("ov-kernel-version"), env.kernelVersion || "—");
    setText($("ov-audio-policy"), env.audioPolicyDigest || "—");

    setText($("ov-cal-downlink"), CAPABILITY_TEXT[report && report.downlinkExtraction] || "未检测");
    setText($("ov-cal-uplink"), CAPABILITY_TEXT[report && report.uplinkInjection] || "未检测");
    setText($("ov-cal-mic"), CAPABILITY_TEXT[report && report.microphoneIsolation] || "未检测");
    setText($("ov-cal-term"), CAPABILITY_TEXT[report && report.privilegedTermination] || "未检测");
  }

  function appendDefinition(list, term, description) {
    var dt = document.createElement("dt");
    setText(dt, term);
    var dd = document.createElement("dd");
    setText(dd, description);
    list.appendChild(dt);
    list.appendChild(dd);
  }

  // -------------------------------------------------------------- 加载与保存

  function loadAll() {
    return bridge
      .call("status")
      .then(function (result) {
        if (result.ok) {
          state.status = result.data;
          renderStatus(result.data);
        } else {
          renderStatusUnavailable(result);
          noteFailure(result);
        }
        return bridge.call("config-get");
      })
      .then(function (result) {
        if (!result.ok) {
          state.loaded = false;
          applySaveAvailability();
          noteFailure(result);
          if (result.code === "config_conflict") {
            showBanner("err", "配置读取失败：" + B.errorText(result.code), [{ label: "重试", primary: true, onClick: loadAll }]);
          }
          return null;
        }
        state.baseline = result.data;
        state.form = B.formFromRedacted(result.data);
        state.loaded = true;
        fillForm(state.form);
        clearDirty();
        applySaveAvailability();
        var validation = B.validateForm(state.form);
        renderWarnings(validation.warnings);
        renderPageIssues([], validation.warnings);
        return bridge.call("diagnose");
      })
      .then(function (result) {
        if (!result) return;
        if (!result.ok) {
          noteFailure(result);
          return;
        }
        state.diagnose = result.data;
        renderDiagnose(result.data);
      })
      .catch(function (error) {
        recordError("client_error", String(error && error.message ? error.message : error));
      });
  }

  var DIRTY_PAGES = ["rules", "ai", "dialog"];
  var DIRTY_CHIPS = { rules: "rules-dirty", ai: "ai-dirty", dialog: "dlg-dirty" };

  /**
   * 分页“未保存”标记：page 必须是本页作用域。总开关独立保存且没有 chip，
   * 因此 page 为空时不改变任何页面标记。
   */
  function markDirty(page) {
    if (!page || !Object.prototype.hasOwnProperty.call(state.dirty, page)) return;
    state.dirty[page] = true;
    var chip = $(DIRTY_CHIPS[page]);
    if (chip) chip.hidden = false;
  }

  /** 无参数清除全部页面（重新加载配置后）；带 page 只清除该页。 */
  function clearDirty(page) {
    var pages = page ? [page] : DIRTY_PAGES;
    pages.forEach(function (name) {
      state.dirty[name] = false;
      var chip = $(DIRTY_CHIPS[name]);
      if (chip) chip.hidden = true;
    });
  }

  /** 输入控件所属页面：保存作用域与未保存 chip 的唯一映射。 */
  function pageForInput(id) {
    if (id.indexOf("rules-") === 0) return "rules";
    if (id.indexOf("ai-") === 0) return "ai";
    if (id.indexOf("dlg-") === 0) return "dialog";
    // ov-enabled 是总开关，独立保存，没有页面 chip。
    return null;
  }

  /** 串行化保存：临时文件路径固定，双击不能把两次写入交错到同一个 inbox 文件。 */
  function beginSave() {
    if (state.saving) return false;
    state.saving = true;
    applySaveAvailability();
    return true;
  }

  function endSave() {
    state.saving = false;
    applySaveAvailability();
  }

  function refuseSave(message, code) {
    toast(message, true);
    if (code) recordError(code, message);
    return Promise.resolve(null);
  }

  function saveConfig(scope) {
    if (!state.canSave) {
      return refuseSave("当前为只读/演示状态，不能保存", "bridge_unavailable");
    }
    if (!state.baseline) {
      return refuseSave("配置尚未加载完成，不能保存；请先等待或重新加载配置", "baseline_missing");
    }
    if (!beginSave()) {
      return refuseSave("上一项保存还在进行，请稍候");
    }
    var form = readForm();
    return B.saveConfig(bridge, form, state.baseline, scope).then(function (result) {
      if (result && result.ok) {
        state.baseline = result.data;
        state.form = result.form;
        state.loaded = true;
        fillSavedPage(scope, result.form);
        clearDirty(scope);
        renderPageIssues([], result.warnings || []);
        renderWarnings(result.warnings);
        hideBanner();
        applySaveAvailability();
        toast("已保存；后端回读确认 revision " + (result.data && result.data.config ? result.data.config.revision : "?"));
        return result;
      }
      var failure = result || { ok: false, code: "unknown_error", issues: [] };
      renderPageIssues(failure.issues || [], failure.warnings || []);
      renderWarnings(failure.warnings || []);
      recordError(failure.code, failure.message || "");
      var blockingCount = (failure.issues || []).filter(function (item) {
        return item.blocking;
      }).length;
      if (failure.conflict) {
        showBanner("err", B.errorText("config_conflict"), [
          { label: "重新加载配置", primary: true, onClick: loadAll },
          { label: "稍后", onClick: hideBanner },
        ]);
      } else if (failure.repair || B.isRepairRequired(failure.code)) {
        showRepairBanner();
      } else {
        showBanner("err", "保存失败（" + String(failure.code || "unknown") + "）：" + (failure.message || "") + (blockingCount > 0 ? "；共 " + blockingCount + " 项需要修正。" : ""), [
          { label: "关闭提示", onClick: hideBanner },
        ]);
      }
      toast("保存失败：" + (failure.code || "unknown"), true);
      return failure;
    }).then(function (result) {
      endSave();
      return result;
    }, function (error) {
      endSave();
      recordError("client_error", String(error && error.message ? error.message : error));
      return null;
    });
  }

  function saveEnabledOnly() {
    if (!state.canSave) {
      return refuseSave("当前为只读/演示状态，不能保存", "bridge_unavailable");
    }
    // 配置还没加载完（没有可信 revision）时绝不保存：不能用 DEMO revision 0 覆盖真实配置。
    if (!state.baseline) {
      return refuseSave("配置尚未加载完成，不能保存开关；请稍后再试或重新加载", "baseline_missing");
    }
    if (!beginSave()) {
      return refuseSave("上一项保存还在进行，请稍候");
    }
    // 只写 enabled：不提交其它页面正在编辑的字段，尤其不提交未确认的密钥意图。
    return B.saveEnabled(bridge, $("ov-enabled").checked, state.baseline).then(function (result) {
      if (result && result.ok) {
        state.baseline = result.data;
        state.loaded = true;
        if (state.form) {
          state.form.enabled = result.form.enabled;
        } else {
          state.form = result.form;
        }
        hideBanner();
        applySaveAvailability();
        toast(result.form.enabled ? "已开启自动接管" : "已关闭自动接管");
        // 其它页面的未保存编辑不受影响，因此不在这里清除 dirty 标记。
      } else {
        var failure = result || { ok: false, code: "unknown_error" };
        recordError(failure.code, failure.message || "");
        renderPageIssues(failure.issues || [], []);
        if (failure.conflict) {
          showBanner("err", B.errorText("config_conflict"), [
            { label: "重新加载配置", primary: true, onClick: loadAll },
            { label: "稍后", onClick: hideBanner },
          ]);
        } else if (failure.repair || B.isRepairRequired(failure.code)) {
          showRepairBanner();
        } else {
          showBanner("err", "开关保存失败：" + (failure.message || B.errorText(failure.code)), [
            { label: "重新加载", primary: true, onClick: loadAll },
          ]);
        }
        toast("开关保存失败：" + (failure.code || "unknown"), true);
      }
      return result;
    }).then(function (result) {
      endSave();
      return result;
    }, function (error) {
      endSave();
      recordError("client_error", String(error && error.message ? error.message : error));
      return null;
    });
  }

  // -------------------------------------------------------------- 号码试算

  function runTrial() {
    var raw = $("rules-trial-number").value;
    var form = readForm();
    var resultList = $("rules-trial-result");
    clearChildren(resultList);
    var key = B.normalizeDigits(raw);
    function entries(text) {
      return B.parseList(text).entries;
    }
    var blacklist = entries(form.blacklistText);
    var whitelist = entries(form.whitelistText);
    function find(list) {
      for (var index = 0; index < list.length; index += 1) {
        if (key && B.normalizeDigits(list[index]) === key) return list[index];
      }
      return null;
    }
    var blackHit = find(blacklist);
    var whiteHit = find(whitelist);
    function add(text, blocking) {
      var item = document.createElement("li");
      item.className = "issue " + (blocking ? "issue-block" : "issue-info");
      setText(item, text);
      resultList.appendChild(item);
    }
    if (B.isPhoneLike(raw) === false || key === null) {
      add("无法本地解析：按未知号码处理（" + (form.handleUnknownNumbers ? "当前开关为接管" : "当前开关为人工处理") + "）。", false);
      return;
    }
    add("本地规范化比较键：" + key + "（最终以 libphonenumber 后端规范化为准）", false);
    add(blackHit ? "命中黑名单：" + blackHit : "未命中黑名单", false);
    add(whiteHit ? "在白名单内：" + whiteHit : "不在白名单内", false);
    var mode = form.mode;
    var decision;
    if (mode === "BLACKLIST") {
      decision = blackHit ? "结果：命中黑名单 → 接管" : "结果：未命中黑名单 → 不接管";
    } else if (mode === "WHITELIST") {
      decision = whiteHit ? "结果：在白名单内 → 不接管" : "结果：白名单外 → 接管";
    } else {
      decision = blackHit || !whiteHit ? "结果：组合模式条件成立 → 接管" : "结果：组合模式条件不成立 → 不接管";
    }
    add(decision, true);
  }

  // -------------------------------------------------------------- 诊断动作

  function repairPairingConfirm() {
    confirmAction(
      "重新配对会记录当前 AgentCall APK 与 Google 电话的包名、UID、签名与版本；升级后配对失效时必须重新配对。确认继续？",
      "确认重新配对",
      function () {
        bridge.call("pair").then(function (result) {
          if (result.ok) {
            toast("配对成功");
            return loadAll();
          }
          recordError(result.code, result.message || "");
          showBanner("err", "配对失败（" + result.code + "）：" + (result.message || B.errorText(result.code)), [
            { label: "关闭", onClick: hideBanner },
          ]);
          return null;
        });
      },
    );
  }

  function repairPairing() {
    if (!state.canSave && bridge.mode === "demo") {
      toast("演示状态不能配对", true);
      return;
    }
    repairPairingConfirm();
  }

  function reloadLog() {
    var pre = $("diag-log");
    bridge.readLog().then(function (result) {
      if (result.ok) {
        setText(pre, result.text && result.text !== "" ? result.text : "（日志为空）");
      } else {
        setText(pre, "无法读取日志：" + B.errorText(result.code));
      }
    });
  }

  function copyText(text) {
    var payload = B.redactText(String(text || ""));
    if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
      navigator.clipboard.writeText(payload).then(
        function () {
          toast("已复制脱敏文本");
        },
        function () {
          fallbackCopy(payload);
        },
      );
      return;
    }
    fallbackCopy(payload);
  }

  function fallbackCopy(text) {
    var area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "readonly");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try {
      ok = document.execCommand("copy");
    } catch (error) {
      ok = false;
    }
    document.body.removeChild(area);
    toast(ok ? "已复制脱敏文本" : "复制失败，请长按选择文本", !ok);
  }

  function buildReport() {
    var lines = [];
    lines.push("AgentCall 诊断（脱敏）");
    lines.push("生成时间：" + formatTime(Date.now()));
    lines.push("桥接模式：" + bridge.mode + "（" + bridge.support.reason + "）");
    if (state.status) {
      lines.push("桥接版本：" + (state.status.bridgeVersion || "—"));
      lines.push("协议版本：" + state.status.protocolVersion);
      lines.push("配对状态：" + (state.status.paired ? "已配对" : "未配对"));
      lines.push("活动/拒绝连接：" + state.status.activeConnections + "/" + state.status.rejectedConnections);
    }
    var report = state.diagnose;
    if (report) {
      var env = report.environment || {};
      lines.push("SDK：" + env.apiLevel + " 内核：" + env.kernelVersion);
      lines.push("Google 电话：" + env.dialerPackageVersion + " 框架：" + env.frameworkVersion);
      lines.push("音频策略摘要：" + env.audioPolicyDigest);
      Object.keys(CAPABILITY_KEY).forEach(function (key) {
        lines.push(CAPABILITY_KEY[key] + "：" + (CAPABILITY_TEXT[report[key]] || "未检测"));
      });
      lines.push("备注：" + (report.notes || "—"));
    }
    lines.push("校验：真实桥接路径未在真机 WebView 验证；以上不含密钥。");
    return lines.join("\n");
  }

  // -------------------------------------------------------------- 事件绑定

  function wireTabs() {
    var tabs = $("tabs");
    tabs.addEventListener("click", function (event) {
      var button = event.target;
      while (button && button.getAttribute && !button.getAttribute("data-page")) {
        button = button.parentNode;
      }
      if (!button || !button.getAttribute) return;
      var page = button.getAttribute("data-page");
      if (!page) return;
      Array.prototype.forEach.call(document.querySelectorAll(".tab"), function (item) {
        var active = item === button;
        item.className = active ? "tab is-active" : "tab";
        if (active) {
          item.setAttribute("aria-current", "page");
        } else {
          item.removeAttribute("aria-current");
        }
      });
      Array.prototype.forEach.call(document.querySelectorAll(".page"), function (section) {
        section.className = section.id === "page-" + page ? "page is-active" : "page";
      });
      if (page === "diag") reloadLog();
    });
  }

  function wireInputs() {
    var ids = [
      "ov-enabled",
      "rules-mode",
      "rules-blacklist",
      "rules-whitelist",
      "rules-region",
      "rules-delay",
      "rules-duration",
      "rules-unknown",
      "ai-voice",
      "dlg-speed",
      "dlg-opening",
      "dlg-prompt",
      "ai-asr-url",
      "ai-asr-model",
      "ai-asr-secret",
      "ai-llm-url",
      "ai-llm-model",
      "ai-llm-secret",
      "ai-tts-url",
      "ai-tts-model",
      "ai-tts-secret",
    ];
    ids.forEach(function (id) {
      var element = $(id);
      if (!element) return;
      var handler = function () {
        markDirty(pageForInput(id));
        if (id === "ov-enabled") renderEnabledState();
      };
      element.addEventListener("input", handler);
      element.addEventListener("change", handler);
    });
  }

  function wireSecretButtons() {
    ["ASR", "LLM", "TTS"].forEach(function (kind) {
      var lower = kind.toLowerCase();
      $("ai-" + lower + "-replace").addEventListener("click", function () {
        state.secretIntent[kind] = "replace";
        $("ai-" + lower + "-secret-wrap").hidden = false;
        $("ai-" + lower + "-replace").hidden = true;
        $("ai-" + lower + "-clear").hidden = true;
        $("ai-" + lower + "-cancel").hidden = false;
        $("ai-" + lower + "-secret").focus();
        updateSecretBadge(kind);
        markDirty("ai");
      });
      $("ai-" + lower + "-clear").addEventListener("click", function () {
        state.secretIntent[kind] = "clear";
        $("ai-" + lower + "-secret").value = "";
        $("ai-" + lower + "-secret-wrap").hidden = true;
        $("ai-" + lower + "-replace").hidden = true;
        $("ai-" + lower + "-clear").hidden = true;
        $("ai-" + lower + "-cancel").hidden = false;
        updateSecretBadge(kind);
        markDirty("ai");
      });
      $("ai-" + lower + "-cancel").addEventListener("click", function () {
        resetSecretControls(kind);
        markDirty("ai");
      });
      $("ai-" + lower + "-test").addEventListener("click", function () {
        var endpoint = endpointFromDom(kind);
        var issues = [];
        var url = String(endpoint.baseUrl || "").replace(/^\s+|\s+$/g, "");
        var model = String(endpoint.model || "").replace(/^\s+|\s+$/g, "");
        if (url !== "" || model !== "") {
          var urlProblem = B.validateBaseUrl(url);
          if (urlProblem) issues.push({ code: urlProblem.code, field: lower + ".baseUrl", message: urlProblem.message, blocking: true });
          if (url === "" || model === "") {
            issues.push({ code: "ENDPOINT_INCOMPLETE", field: lower, message: "地址与模型必须同时填写", blocking: true });
          }
        }
        var list = $("ai-" + lower + "-issues");
        renderIssues(list, issues);
        if (issues.length === 0) {
          renderIssues(list, [
            {
              code: "LOCAL_FORMAT_ONLY",
              field: lower,
              message: "地址格式通过。WebUI 不执行连接测试；连接测试在 AgentCall APK 诊断页执行，需真实远程 API 请求（会使用密钥并可能计费）。",
              blocking: false,
            },
          ]);
        }
        markInvalidFields(issues);
      });
    });
  }

  function wireButtons() {
    $("ov-save-switch").addEventListener("click", saveEnabledOnly);
    $("ov-refresh").addEventListener("click", function () {
      loadAll();
    });
    $("ov-clear-errors").addEventListener("click", function () {
      try {
        window.localStorage.removeItem(ERROR_STORAGE_KEY);
      } catch (error) {
        /* 忽略存储错误 */
      }
      renderRecentErrors();
    });
    $("ov-goto-cal").addEventListener("click", function () {
      var tab = document.querySelector('.tab[data-page="diag"]');
      if (tab) tab.click();
      $("diag-cal-list").hidden = false;
    });
    // 每个页面只提交本页字段；总开关走独立保存。
    $("rules-save").addEventListener("click", function () {
      saveConfig("rules");
    });
    $("rules-trial-btn").addEventListener("click", runTrial);
    $("ai-save").addEventListener("click", function () {
      saveConfig("ai");
    });
    $("dlg-save").addEventListener("click", function () {
      saveConfig("dialog");
    });
    $("diag-refresh").addEventListener("click", function () {
      toast("正在重试连接…");
      loadAll().then(function () {
        toast("已重试");
      });
    });
    $("diag-repair").addEventListener("click", repairPairing);
    $("diag-reload").addEventListener("click", function () {
      loadAll();
    });
    $("diag-disable").addEventListener("click", function () {
      if (!state.canSave) {
        toast("当前为只读/演示状态，不能保存", true);
        return;
      }
      // config-get 尚未解析时没有可信 revision：不得用 DEMO revision 0 覆盖真实配置。
      if (!state.baseline) {
        toast("配置尚未加载完成，不能停用", true);
        return;
      }
      confirmAction("停用后不会自动接管任何来电，已托管的通话不受影响。确认停用？", "确认停用", function () {
        $("ov-enabled").checked = false;
        renderEnabledState();
        saveEnabledOnly();
      });
    });
    $("diag-shutdown").addEventListener("click", function () {
      confirmAction("关闭桥接服务会终止当前配置服务；下次开机由 service.sh 重新启动。确认关闭？", "确认关闭", function () {
        bridge.call("shutdown").then(function (result) {
          if (result.ok) {
            toast("已请求关闭桥接服务");
            setText($("ov-bridge-state"), "已请求关闭");
          } else {
            recordError(result.code, result.message || "");
            showBanner("err", "关闭失败：" + (result.message || B.errorText(result.code)), [{ label: "关闭", onClick: hideBanner }]);
          }
        });
      });
    });
    $("diag-log-refresh").addEventListener("click", reloadLog);
    $("diag-log-copy").addEventListener("click", function () {
      copyText($("diag-log").textContent || "");
    });
    $("diag-copy-report").addEventListener("click", function () {
      copyText(buildReport());
    });
    $("diag-cal-open").addEventListener("click", function () {
      var list = $("diag-cal-list");
      list.hidden = !list.hidden;
    });
  }

  // -------------------------------------------------------------- 启动

  function init() {
    setModeBadge();
    wireTabs();
    wireInputs();
    wireSecretButtons();
    wireButtons();
    renderRecentErrors();
    ["ASR", "LLM", "TTS"].forEach(function (kind) {
      updateSecretBadge(kind);
    });
    // 配置加载完成前保存按钮一律禁用（HTML 里也是 disabled）。
    applySaveAvailability();
    renderLiveOverview();

    if (bridge.mode === "demo") {
      state.baseline = B.DEMO.config;
      state.status = B.DEMO.status;
      state.diagnose = B.DEMO.diagnose;
      state.form = B.formFromRedacted(B.DEMO.config);
      fillForm(state.form);
      renderStatus(B.DEMO.status);
      renderDiagnose(B.DEMO.diagnose);
      applySaveAvailability();
      disableConfigEditing();
      mountDemoNote();
      setText($("diag-log"), "演示状态：未连接真实桥接，无法读取模块日志。");
      return Promise.resolve();
    }

    if (bridge.mode === "readonly") {
      applySaveAvailability();
      disableConfigEditing();
      if (bridge.support.reason === "io_missing") {
        showBanner(
          "warn",
          "当前 KernelSU 管理器不提供 ksu.io 受控文件接口：配置可以读取，但保存已禁用。密钥不会以 base64 命令行参数的方式提交；请升级管理器或使用带安全 stdin/文件通道的版本。",
          [{ label: "知道了", onClick: hideBanner }],
        );
      } else {
        showBanner("err", "moduleInfo() 给出的模块目录不可信：已禁用需要 CLI 的命令与保存（固定路径日志仍可读取）。", [{ label: "知道了", onClick: hideBanner }]);
      }
    }

    return loadAll().then(function () {
      reloadLogIfDiagnose();
      applySaveAvailability();
    });
  }

  function reloadLogIfDiagnose() {
    var diag = $("page-diag");
    if (diag && diag.className.indexOf("is-active") !== -1) reloadLog();
  }

  function mountDemoNote() {
    showBanner("warn", B.DEMO.note, [
      {
        label: "了解",
        onClick: hideBanner,
      },
    ]);
  }

  var ui = {
    init: init,
    state: state,
    bridge: bridge,
    saveConfig: saveConfig,
    saveEnabledOnly: saveEnabledOnly,
    applySaveAvailability: applySaveAvailability,
    loadAll: loadAll,
  };

  // 仅测试环境导出（生产 WebView 没有 module，导出分支不会执行；页面行为不变）。
  if (typeof module !== "undefined" && module.exports) {
    module.exports = ui;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();

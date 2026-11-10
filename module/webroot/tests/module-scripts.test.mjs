import test from "node:test";
import assert from "node:assert/strict";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, spawnSync } from "node:child_process";
import { tmpdir } from "node:os";

const MODULE = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const SCRIPTS = ["customize.sh", "service.sh", "uninstall.sh", "bin/agentcallctl"];

function read(relative) {
  return readFileSync(join(MODULE, relative), "utf8");
}

function propFields() {
  const fields = {};
  for (const line of read("module.prop").split("\n")) {
    const at = line.indexOf("=");
    if (at > 0) fields[line.slice(0, at)] = line.slice(at + 1);
  }
  return fields;
}

test("module.prop 的 id/名称/版本与 app 的 versionName/versionCode 一致", () => {
  const fields = propFields();
  const gradle = readFileSync(join(MODULE, "..", "app", "build.gradle.kts"), "utf8");
  const versionName = /versionName\s*=\s*"([^"]+)"/.exec(gradle)[1];
  const versionCode = Number(/versionCode\s*=\s*(\d+)/.exec(gradle)[1]);

  assert.equal(fields.id, "agentcall");
  assert.equal(fields.name, "AgentCall");
  assert.equal(fields.version, versionName, "模块 version 必须跟随 app versionName");
  assert.equal(Number(fields.versionCode), versionCode, "模块 versionCode 必须跟随 app versionCode");
  assert.ok(fields.author && fields.author.length > 0);
  assert.ok(fields.description && fields.description.length > 0);
  assert.equal(read("module.prop").includes("\r"), false, "module.prop 不得含 CR");
});

test("模块脚本通过 sh -n 语法检查，且不监听网络端口", (t) => {
  const missing = spawnSync("sh", ["-c", "command -v sh"], { encoding: "utf8" });
  if (missing.status !== 0) {
    t.skip("host has no sh");
    return;
  }
  for (const script of SCRIPTS) {
    const result = spawnSync("sh", ["-n", join(MODULE, script)], { encoding: "utf8" });
    assert.equal(result.status, 0, script + " 语法错误：" + result.stderr);
  }

  const service = read("service.sh");
  assert.equal(/\bnc\b[^\n]*\s-l\b/.test(service), false, "service.sh 不得监听端口");
  assert.equal(/\bsocat\b|\bncat\b/.test(service), false, "service.sh 不得使用中继监听工具");
  assert.match(service, /app_process/, "只通过 app_process 启动桥接容器");
  assert.equal(/\b(0\.0\.0\.0|127\.0\.0\.1):\d+/.test(service), false, "service.sh 不得出现网络地址");
});

test("service.sh：等待系统就绪、有限重启，干净退出后不再拉起", () => {
  const service = read("service.sh");
  assert.match(service, /MAX_RESTARTS=\d+/);
  const maxRestarts = Number(/MAX_RESTARTS=(\d+)/.exec(service)[1]);
  assert.ok(maxRestarts >= 1 && maxRestarts <= 5, "重启次数必须是非常小的有界值");
  assert.match(service, /restarts" -le "\$MAX_RESTARTS"/);
  assert.match(service, /sys\.boot_completed/);
  assert.match(service, /BOOT_WAIT_SECONDS=\d+/);
  assert.match(service, /trap on_stop TERM INT HUP/);
  assert.match(service, /bridge_exit_clean/, "退出码 0 视为干净退出，不应无限重启");
  assert.match(service, /\$MODDIR\/disable/, "模块被停用时不得启动");
  assert.equal(/while true/.test(service), false, "禁止无界重启循环");
});

test("uninstall.sh：先尝试停止桥接（CLI 或 PID 回退），再清理私有数据", () => {
  const uninstall = read("uninstall.sh");
  const shutdownAt = uninstall.indexOf('"$CTL" shutdown');
  const killAt = uninstall.indexOf("kill -KILL");
  const cleanupAt = uninstall.indexOf('rm -rf "$DATA_ROOT"');
  assert.ok(shutdownAt > -1, "卸载必须优先走固定 CLI 的 shutdown");
  assert.ok(cleanupAt > -1, "卸载必须清理私有数据目录");
  assert.ok(shutdownAt < cleanupAt, "shutdown 必须发生在删除数据之前");
  assert.ok(killAt > -1, "CLI 不可执行/无响应时必须有 PID 回退杀进程");
  assert.ok(killAt < cleanupAt, "PID 回退必须在删除数据之前完成");
  assert.match(uninstall, /STOP_WAIT_SECONDS=\d+/, "等待停止必须有上限");
  assert.match(uninstall, /KILL_WAIT_SECONDS=\d+/, "等待强杀必须有上限");
  assert.match(uninstall, /未能.*确认.*(停止|交还)|未能在/, "未确认停止时必须显式警告，不得谎称成功");
  assert.match(uninstall, /case "\$DATA_ROOT" in[\s\S]*\/data\/adb\/agentcall\)/, "只允许删除固定私有目录");
  assert.match(uninstall, /Google 电话默认角色/, "必须声明未改动默认拨号角色");
  assert.equal(/chmod|set_perm/.test(uninstall), false, "卸载脚本不修改模块/webroot 权限");
});

test("uninstall.sh 诚实性：桥接已实现会话/租约与优雅关闭，但只在桥接可确认时才能终止/交还", () => {
  const uninstall = read("uninstall.sh");
  // 桥接已经实现会话/租约（session.request/command/heartbeat/events/handover），
  // 不得再复述“没有会话/租约、session.request 为 method_not_implemented”的旧结论。
  assert.match(uninstall, /(已实现|已有|已经实现)[\s\S]{0,80}(会话|租约)/, "必须如实说明桥接已实现会话/租约");
  assert.match(uninstall, /session\.(request|command|heartbeat|events|handover)/, "必须点名桥接已有的会话方法");
  assert.equal(/(没有|无)[^。\n]{0,40}(会话|租约)/.test(uninstall), false, "不得再宣称桥接没有会话/租约");
  assert.equal(/method_not_implemented/.test(uninstall), false, "不得再把 session.request 说成 method_not_implemented");
  // 11D2a 已把终止/交还接入优雅关闭：卸载必须先请求固定 CLI shutdown，再停音频工作进程。
  assert.match(uninstall, /"\$CTL" shutdown/, "必须执行固定 CLI shutdown，不得跳过优雅关闭");
  assert.match(uninstall, /(优雅关闭|优雅停止)/, "必须说明优雅关闭路径");
  assert.match(uninstall, /shutdown_call_unresolved/, "必须点名未确认关闭的机器码");
  // 但桥接不响应/已退出时，脚本仍无法终止或交还，也不得反过来夸大能力。
  assert.match(uninstall, /(无法|不能)[\s\S]{0,80}(终止|交还)[\s\S]{0,80}(通话|AI)/, "必须明确无法终止/交还正在进行的通话");
  assert.match(uninstall, /未确认[\s\S]{0,40}(终止|交还)/, "未确认时不得宣称已终止/交还");
  assert.equal(/交还\/终止其托管的 AI 会话/.test(uninstall), false, "不得复述虚假的交还契约");
  assert.match(uninstall, /socket_present/, "必须单独探测 abstract socket 是否存在");
  assert.match(uninstall, /ctl_usable/, "必须单独判断固定 CLI 是否可执行");
  assert.equal(/\] && \[ -x "\$CTL" \]/.test(uninstall), false, "不得把 socket 存在与 CLI 可执行混在同一个条件里");
});

test("docs/config-schema.md 不再宣称桥接没有会话/租约（与实现对齐）", () => {
  const docs = readFileSync(join(MODULE, "..", "docs", "config-schema.md"), "utf8");
  assert.equal(/当前桥接没有会话\/租约实现/.test(docs), false, "不得再宣称桥接没有会话/租约");
  assert.equal(/session\.request[\s\S]{0,40}method_not_implemented/.test(docs), false, "不得再把 session.request 说成未实现");
  assert.match(docs, /会话\/租约/, "必须如实说明桥接已有会话/租约能力");
  assert.match(docs, /session\.(request|command|heartbeat|events|handover)/, "必须点名桥接已有的会话方法");
  assert.match(docs, /processId/, "status 字段表必须包含 processId");
  assert.equal(/连接测试[\s\S]{0,60}未实现/.test(docs), false, "APK 已实现逐端点连接测试，文档不得再写未实现");
});

test("uninstall.sh / agentcallctl：$0 不含斜杠时仍解析出正确目录", () => {
  for (const file of ["uninstall.sh", "bin/agentcallctl"]) {
    const source = read(file);
    assert.match(source, /case "\$0" in/, file + " 必须显式处理 $0 不含斜杠的情况");
    assert.match(source, /\*\/\*/, file + " 必须有带斜杠分支");
    assert.equal(/^MODDIR=\$\{0%\/\*\}$/m.test(source), false, file + " 不得只依赖百分号斜杠展开");
  }
});

test("customize.sh 只补固定包装器可执行位，不改 webroot 官方权限", () => {
  const customize = read("customize.sh");
  assert.match(customize, /set_perm "\$MODPATH\/bin\/agentcallctl" 0 0 0755/);
  assert.equal(/chmod[^\n]*webroot|set_perm[^\n]*webroot/.test(customize), false, "webroot 权限由 KernelSU 安装器统一管理");
  assert.match(customize, /abort/, "缺失桥接容器/命令包装器时必须中止安装而非半装");
  assert.equal(/sys\.prop|resetprop|setenforce|settings put/.test(customize), false, "安装脚本不得改系统属性、SELinux 或默认应用");
});

test("customize.sh：安装时清理 inbox 中 WebView 崩溃遗留的 config-set 负载", () => {
  const customize = read("customize.sh");
  assert.match(
    customize,
    /for payload in "\$DATA_ROOT"\/inbox\/config-set\*\.json; do/,
    "安装时必须遍历本模块拥有的固定前缀负载",
  );
  assert.match(customize, /rm -f "\$payload"/, "安装时必须删除遗留的明文负载");
});

test("agentcallctl：固定六条命令，配置只从 stdin 进入，未知命令在启动进程前拒绝", () => {
  const ctl = read("bin/agentcallctl");
  assert.match(ctl, /status\|config-get\|config-set\|diagnose\|shutdown/);
  assert.match(ctl, /usage_invalid/);
  assert.match(ctl, /\"\$#\" -ne 1/, "无参数命令必须拒绝额外参数");
  assert.match(ctl, /run_cli config-set\n/, "config-set 只运行固定 CLI，命令行不携带配置内容");
  assert.equal(/config-set *"?\$[A-Za-z]/.test(ctl), false, "不得把 stdin 内容或变量拼进 config-set 参数");
  assert.match(ctl, /id -u/, "必须先检查 root");
  assert.match(ctl, /exit "\$result"|exit \$\?/, "必须转发 CLI 退出码");
  assert.equal(/cli_requires_root/.test(ctl), true, "非 root 必须给出机器可读错误");
});


// ---------------------------------------------------------------------------------------------
// service.sh 进程所有权（修复轮 2 / 新增重要缺陷 1）
//
// 背景：模块停止时只杀 audio_worker_supervisor 子 shell 会把真正的 app_process 变成孤儿：
// 它继续持有 abstract socket 与麦克风隔离，下一次模块启动会 bind 失败直到重启设备。
// 下面的 host 级测试用假 app_process / 假 getprop 真正运行 service.sh，断言停止路径
// 杀掉的是**音频工作进程自己的 PID**，并且干净退出也会回收它。
// ---------------------------------------------------------------------------------------------

function functionBody(source, name) {
  // 必须从行首匹配：stop_audio_worker_supervisor 也以 audio_worker_supervisor() { 结尾。
  const at = source.indexOf("\n" + name + "() {");
  assert.ok(at > -1, "缺少函数 " + name);
  const end = source.indexOf("\n}\n", at);
  assert.ok(end > at, "函数 " + name + " 未正常结束");
  return source.slice(at, end);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await sleep(50);
  }
  throw new Error("timed out waiting for " + label);
}

function processAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function readPidFile(path) {
  return Number(readFileSync(path, "utf8").trim());
}

function killQuietly(child) {
  if (!child || !child.pid) return;
  try {
    if (processAlive(child.pid)) child.kill("SIGKILL");
  } catch {
    // 测试清理：进程已退出时忽略。
  }
}

function serviceEnv(harness, extra = {}) {
  return {
    ...process.env,
    AGENTCALL_APP_PROCESS: harness.appProcess,
    AGENTCALL_GETPROP: harness.getprop,
    AGENTCALL_DATA_ROOT: harness.dataRoot,
    AGENTCALL_UNIX_LIST: harness.unixList,
    // 新停止顺序会先执行固定 CLI shutdown；默认把两个优雅等待调短，避免旧用例超时。
    AGENTCALL_GRACEFUL_SHUTDOWN_WAIT_SECONDS: "5",
    AGENTCALL_BRIDGE_EXIT_WAIT_SECONDS: "2",
    ...extra,
  };
}

/**
 * 临时模块目录 + 假 app_process：
 * - 参数里出现 BridgeMain 时，AGENTCALL_FAKE_BRIDGE_STAY=1 就常驻，否则 1 秒后干净退出；
 * - 其余（音频工作角色）常驻，模拟冻结在通话音频里的 app_process。
 */
function serviceHarness() {
  const root = mkdtempSync(join(tmpdir(), "agentcall-service-"));
  const moduleDir = join(root, "module");
  const dataRoot = join(root, "data");
  const binDir = join(root, "bin");
  // 假 /proc/net/unix：service.sh 通过 AGENTCALL_UNIX_LIST 注入点读取该文件，
  // 替身 app_process 在“绑定 socket”时把 abstract 名字追加进去（真机上是内核表）。
  const unixList = join(root, "proc-net-unix.txt");
  mkdirSync(join(moduleDir, "bridge"), { recursive: true });
  mkdirSync(join(dataRoot, "logs"), { recursive: true });
  mkdirSync(binDir, { recursive: true });
  writeFileSync(join(moduleDir, "bridge", "agentcall-bridge.jar"), "stub");
  writeFileSync(unixList, "");
  copyFileSync(join(MODULE, "service.sh"), join(moduleDir, "service.sh"));

  const appProcess = join(binDir, "app_process");
  writeFileSync(
    appProcess,
    [
      "#!/bin/sh",
      'case "$*" in',
      "  *AgentCallCli*)",
      '    if [ -n "${AGENTCALL_FAKE_SHUTDOWN_TRIGGER:-}" ]; then : > "$AGENTCALL_FAKE_SHUTDOWN_TRIGGER"; fi',
      '    if [ -n "${AGENTCALL_FAKE_SHUTDOWN_JSON:-}" ]; then printf "%s\\n" "$AGENTCALL_FAKE_SHUTDOWN_JSON"; fi',
      '    exit "${AGENTCALL_FAKE_CLI_EXIT:-0}"',
      "    ;;",
      "  *BridgeMain*)",
      '    if [ "${AGENTCALL_FAKE_BRIDGE_NO_SOCKET:-0}" != "1" ] && [ -n "${AGENTCALL_UNIX_LIST:-}" ]; then',
      '      printf \'@org.krvyft.agentcall.control\\n\' >> "$AGENTCALL_UNIX_LIST"',
      "    fi",
      '    if [ "${AGENTCALL_FAKE_BRIDGE_STAY:-0}" = "1" ]; then',
      "      while :; do",
      '        if [ -n "${AGENTCALL_FAKE_SHUTDOWN_TRIGGER:-}" ] && [ -f "$AGENTCALL_FAKE_SHUTDOWN_TRIGGER" ]; then',
      "          exit 0",
      "        fi",
      '        if [ -n "${AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER:-}" ] && [ -f "$AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER" ]; then',
      '          rm -f "$AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER"',
      "          exit 0",
      "        fi",
      "        sleep 1",
      "      done",
      "    else",
      "      sleep 1",
      '      exit "${AGENTCALL_FAKE_BRIDGE_EXIT:-0}"',
      "    fi",
      "    exit 0",
      "    ;;",
      "esac",
      'if [ "${AGENTCALL_FAKE_AUDIO_NO_SOCKET:-0}" != "1" ] && [ -n "${AGENTCALL_UNIX_LIST:-}" ]; then',
      '  printf \'@org.krvyft.agentcall.audio-role\\n\' >> "$AGENTCALL_UNIX_LIST"',
      "fi",
      'if [ "${AGENTCALL_FAKE_AUDIO_IGNORE_TERM:-0}" = "1" ]; then trap "" TERM; fi',
      "while :; do sleep 1; done",
      "",
    ].join("\n"),
  );
  chmodSync(appProcess, 0o755);

  const getprop = join(binDir, "getprop");
  writeFileSync(getprop, "#!/bin/sh\nprintf '1\\n'\n");
  chmodSync(getprop, 0o755);

  return {
    root,
    moduleDir,
    dataRoot,
    appProcess,
    getprop,
    unixList,
    script: join(moduleDir, "service.sh"),
    audioPidFile: join(dataRoot, "audio-worker.pid"),
    bridgePidFile: join(dataRoot, "bridge.pid"),
    servicePidFile: join(dataRoot, "service.pid"),
    restartRequestFile: join(dataRoot, "restart.requested"),
    logFile: join(dataRoot, "logs", "service.log"),
  };
}

function waitForExit(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("service.sh did not exit in " + timeoutMs + " ms")), timeoutMs);
    child.once("exit", (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal });
    });
  });
}

test(
  "service.sh：SIGTERM 停止路径必须杀掉真实的音频工作进程 PID，而不是只杀子 shell",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_STAY: "1" }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.audioPidFile) && existsSync(harness.bridgePidFile),
        20000,
        "bridge/audio pid files",
      );
      const workerPid = readPidFile(harness.audioPidFile);
      const bridgePid = readPidFile(harness.bridgePidFile);
      assert.ok(workerPid > 0, "音频工作进程必须有独立 PID 文件");
      assert.ok(bridgePid > 0);
      assert.notEqual(workerPid, bridgePid);

      child.kill("SIGTERM");
      await waitForExit(child, 20000);

      await waitFor(() => !processAlive(workerPid), 5000, "audio worker termination");
      assert.equal(
        processAlive(workerPid),
        false,
        "停止后不得留下音频工作 app_process（会继续持有 abstract socket 与麦克风隔离）",
      );
      assert.equal(existsSync(harness.audioPidFile), false, "停止后必须收回音频 PID 文件");
      assert.equal(existsSync(harness.bridgePidFile), false, "停止后必须收回 bridge PID 文件");

      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /service_stop_signal/);
      assert.match(
        log,
        new RegExp("audio_worker_stopped pid=" + workerPid + "\\b"),
        "停止路径必须记录并验证音频工作进程已终止（有界 TERM→KILL 只作用于已核验的 PID）",
      );
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：bridge 干净退出后也必须回收音频工作进程",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_STAY: "0" }),
        stdio: "ignore",
      });
      await waitFor(() => existsSync(harness.audioPidFile), 20000, "audio worker pid file");
      const workerPid = readPidFile(harness.audioPidFile);

      const { code } = await waitForExit(child, 30000);

      assert.equal(code, 0, "干净退出必须保留 0 退出码");
      await waitFor(() => !processAlive(workerPid), 5000, "audio worker termination on clean exit");
      assert.equal(
        processAlive(workerPid),
        false,
        "bridge 干净退出后残留的音频工作进程会独占麦克风隔离与 socket",
      );
      assert.equal(existsSync(harness.audioPidFile), false);

      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /bridge_exit_clean/);
      assert.match(log, /audio_worker_stopped/);
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：启动时清理 inbox 中崩溃遗留的 config-set 明文负载（只删本模块拥有的文件）",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      const inbox = join(harness.dataRoot, "inbox");
      mkdirSync(inbox, { recursive: true });
      const fixed = join(inbox, "config-set.json");
      const stale = join(inbox, "config-set.instance-a-1.json");
      const foreign = join(inbox, "notes.txt");
      writeFileSync(fixed, '{"expectedRevision":0,"patch":{}}');
      writeFileSync(stale, '{"expectedRevision":0,"patch":{}}');
      writeFileSync(foreign, "keep me");

      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_STAY: "1" }),
        stdio: "ignore",
      });
      await waitFor(() => existsSync(harness.bridgePidFile), 20000, "bridge pid file");

      assert.equal(existsSync(fixed), false, "固定路径的崩溃残留必须在启动时删除");
      assert.equal(existsSync(stale), false, "唯一命名的崩溃残留必须在启动时删除");
      assert.equal(existsSync(foreign), true, "不得删除 inbox 中不属于本模块的其它文件");
      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /inbox_payload_cleaned count=2/, "清理必须记录数量：" + log);
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：启动时先回收上一次实例留下的音频工作进程（幂等启动）",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    let stale = null;
    try {
      // 上一次模块实例被强杀后留下的 app_process：PID 文件与进程都还在。
      stale = spawn(
        "sh",
        [harness.appProcess, "--nice-name=agentcall-audio-worker", "org.krvyft.agentcall.bridge.audio.AudioWorkerMain"],
        { env: serviceEnv(harness), stdio: "ignore" },
      );
      await sleep(200);
      writeFileSync(harness.audioPidFile, String(stale.pid) + "\n");

      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_STAY: "0" }),
        stdio: "ignore",
      });
      const { code } = await waitForExit(child, 30000);
      assert.equal(code, 0);

      await waitFor(() => !processAlive(stale.pid), 5000, "stale audio worker termination");
      assert.equal(processAlive(stale.pid), false, "启动前必须回收上一次实例的音频工作进程");

      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /stale_audio_worker_stopped/);
    } finally {
      killQuietly(child);
      killQuietly(stale);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);


// ---------------------------------------------------------------------------------------------
// 阶段 B：模块生命周期硬化（有界停止、移除标记、音频工作进程 PID 契约）
// ---------------------------------------------------------------------------------------------

test("service.sh：停止路径有界，不在 shutdown 里裸 wait（卡死时无界阻塞）", () => {
  const service = read("service.sh");
  for (const name of ["stop_audio_worker_supervisor", "shutdown_children"]) {
    assert.equal(
      /\bwait\s+"\$/.test(functionBody(service, name)),
      false,
      name + " 不得直接 wait：进程卡住时会无限阻塞卸载的停止路径",
    );
  }
  const reap = functionBody(service, "reap_child");
  assert.match(reap, /KILL_WAIT_SECONDS/, "回收等待必须有上限");
  assert.match(reap, /reap_timeout/, "回收超时必须记录并有界放弃");
  assert.match(service, /terminate_pid "\$bridge_pid" "\$STOP_GRACE_SECONDS" bridge/);
  assert.match(service, /terminate_pid "\$worker" "\$STOP_GRACE_SECONDS" audio_worker/);
});

test("service.sh：每次重启前重新检查 remove/disable 标记，不复活已移除模块", () => {
  const service = read("service.sh");
  const markedAt = service.indexOf("module_marked_stopped() {");
  assert.ok(markedAt > -1, "必须有统一的 remove/disable 标记判定");
  for (const name of ["bridge_container_supervisor", "audio_worker_supervisor"]) {
    const body = functionBody(service, name);
    const loopAt = body.indexOf("while [");
    assert.ok(loopAt > -1, name + " 必须有有限重启循环");
    const checkAt = body.indexOf("module_marked_stopped", loopAt);
    assert.ok(checkAt > loopAt, name + " 必须在重启循环内重新检查 remove/disable 标记");
    assert.ok(checkAt < body.indexOf('"$APP_PROCESS"', loopAt), name + " 必须在拉起进程之前检查标记");
  }
  assert.match(service, /marked_stopped/, "跳过重启时必须记录原因");
});

test("uninstall.sh：按 service.sh 的 PID 文件契约结束音频工作进程，且早于删除数据目录", () => {
  const service = read("service.sh");
  const uninstall = read("uninstall.sh");

  assert.match(service, /^PID_FILE=\$DATA_ROOT\/bridge\.pid$/m, "service.sh 的桥接 PID 文件路径");
  assert.match(service, /^AUDIO_PID_FILE=\$DATA_ROOT\/audio-worker\.pid$/m, "service.sh 的音频 PID 文件路径");
  // 真机路径必须是默认值；允许 service.sh 同名的测试/诊断注入（删除守卫仍是固定路径）。
  assert.match(uninstall, /^DATA_ROOT=(\/data\/adb\/agentcall|\$\{AGENTCALL_DATA_ROOT:-\/data\/adb\/agentcall\})$/m);
  assert.match(uninstall, /case "\$DATA_ROOT" in[\s\S]*?\/data\/adb\/agentcall\)/, "删除守卫必须只接受固定路径");
  assert.match(uninstall, /^PID_FILE=\$DATA_ROOT\/bridge\.pid$/m, "uninstall 必须读同一个桥接 PID 文件");
  assert.match(uninstall, /^AUDIO_PID_FILE=\$DATA_ROOT\/audio-worker\.pid$/m, "uninstall 必须读同一个音频 PID 文件");

  // PID 文件格式绑定：首行、只接受十进制数字（与 service.sh 的 read_pid 一致）。
  for (const [label, source] of [["service.sh", service], ["uninstall.sh", uninstall]]) {
    assert.match(source, /head -n 1/, label + " 必须只读 PID 文件首行");
    assert.match(source, /tr -cd '0-9'/, label + " 必须只接受十进制数字");
  }
  assert.match(service, /printf '%s\\n' "\$worker_pid" > "\$AUDIO_PID_FILE"/, "service.sh 写单个音频 PID");
  assert.match(service, /printf '%s\\n' "\$bridge_pid" > "\$PID_FILE"/, "service.sh 写单个桥接 PID");

  // 同一套核验纪律：cmdline 命中固定入口/进程名才允许终止（防 PID 复用误杀）。
  assert.match(
    uninstall,
    /verified_pid_from_file "\$AUDIO_PID_FILE"[\s\S]{0,180}org\.krvyft\.agentcall\.bridge\.audio\.AudioWorkerMain[\s\S]{0,120}agentcall-audio-worker/,
    "音频 PID 必须核验 AudioWorkerMain/agentcall-audio-worker",
  );
  assert.match(
    uninstall,
    /verified_pid_from_file "\$PID_FILE"[\s\S]{0,160}org\.krvyft\.agentcall\.bridge\.BridgeMain[\s\S]{0,120}agentcall-bridge/,
    "桥接 PID 必须核验 BridgeMain/agentcall-bridge",
  );
  assert.match(uninstall, /terminate_verified_pid/, "音频与桥接必须共用核验终止路径");
  assert.match(uninstall, /kill -KILL/, "必须有有界 TERM→KILL");

  // 顺序断言必须锚定“从 audio-worker.pid 核验后终止”的真实调用点。
  // 不能用 AudioWorkerMain 的首次出现：那是 audio_pid_from_file 的函数定义。
  const fnAt = uninstall.indexOf("audio_pid_from_file() {");
  const call = /audio_pid=\$\(audio_pid_from_file[\s\S]{0,240}?terminate_verified_pid "\$audio_pid"/.exec(uninstall);
  assert.ok(fnAt > -1, "必须有音频 PID 核验函数");
  assert.ok(call, "必须有真实调用点：读 audio-worker.pid 并终止该 PID（只断言路径/存在性不算）");
  const callAt = call.index;
  const guardAt = uninstall.indexOf('case "$DATA_ROOT" in');
  const cleanupAt = uninstall.indexOf('rm -rf "$DATA_ROOT"');
  assert.ok(callAt > fnAt, "调用点必须位于函数定义之后");
  assert.ok(guardAt > -1 && cleanupAt > -1);
  assert.ok(callAt < guardAt, "音频工作进程必须在删除数据目录的判断之前结束");
  assert.ok(callAt < cleanupAt, "音频工作进程必须在 rm -rf 之前结束");
  assert.match(uninstall, /AudioWorkerMain[\s\S]*?Google 电话/, "仍保留“无法交还通话”的诚实说明");
});

// 真实执行 uninstall.sh：用宿主进程替身证明 PID 文件里的音频工作进程真的被结束，
// 且结束发生在固定的删除守卫之前（替身数据目录永远不会被 rm -rf）。
function startFakeAudioWorker(root) {
  const script = join(root, "agentcall-audio-worker.sh");
  writeFileSync(script, "#!/bin/sh\nwhile :; do sleep 1; done\n");
  const launched = spawnSync(
    "sh",
    [
      "-c",
      '"sh" "$1" org.krvyft.agentcall.bridge.audio.AudioWorkerMain agentcall-audio-worker >/dev/null 2>&1 & echo $!',
      "sh",
      script,
    ],
    { encoding: "utf8" },
  );
  assert.equal(launched.status, 0, "无法启动音频工作进程替身：" + launched.stderr);
  const pid = Number(launched.stdout.trim());
  assert.ok(pid > 1, "音频工作进程替身必须有 PID");
  return pid;
}

test(
  "uninstall.sh：真实执行时结束 audio-worker.pid 指向的进程，并拒绝删除非固定数据目录",
  { timeout: 60000 },
  async () => {
    const root = mkdtempSync(join(tmpdir(), "agentcall-uninstall-"));
    const moduleDir = join(root, "module");
    const dataRoot = join(root, "data");
    mkdirSync(moduleDir, { recursive: true });
    mkdirSync(dataRoot, { recursive: true });
    // 只复制脚本本身：模块目录没有可执行 CLI，脚本不会在宿主上调用 shutdown。
    copyFileSync(join(MODULE, "uninstall.sh"), join(moduleDir, "uninstall.sh"));

    const workerPid = startFakeAudioWorker(root);
    try {
      await sleep(100);
      assert.equal(processAlive(workerPid), true, "音频工作进程替身必须存活");
      writeFileSync(join(dataRoot, "audio-worker.pid"), workerPid + "\n");

      const result = spawnSync("sh", [join(moduleDir, "uninstall.sh")], {
        env: { ...process.env, AGENTCALL_DATA_ROOT: dataRoot },
        encoding: "utf8",
        timeout: 20000,
      });
      const stdout = result.stdout + result.stderr;

      // 替身数据目录不是固定路径：删除守卫拒绝并 exit 1。这同时证明脚本已经走完音频
      // 终止并到达删除判断（若终止被删除或挪到删除之后，进程会仍然存活）。
      assert.equal(result.status, 1, "非固定数据目录必须被拒绝删除：" + stdout);
      assert.match(stdout, /音频工作 进程 \d+ 已终止/, "真实执行必须走核验终止路径：" + stdout);
      assert.match(stdout, /数据目录不是预期路径/, "终止后必须到达删除守卫：" + stdout);
      assert.equal(processAlive(workerPid), false, "PID 文件里的音频工作进程必须真的被结束");
      assert.equal(existsSync(join(dataRoot, "audio-worker.pid")), false, "确认终止后才删除 PID 文件");
      assert.equal(existsSync(dataRoot), true, "固定路径守卫必须阻止删除替身数据目录");
    } finally {
      try {
        if (processAlive(workerPid)) process.kill(workerPid, "SIGKILL");
      } catch {
        // 测试清理：进程已退出时忽略。
      }
      rmSync(root, { recursive: true, force: true });
    }
  },
);

test("uninstall.sh：PID 文件首行在规范化之前校验，损坏内容不会变成另一个 PID", () => {
  const uninstall = read("uninstall.sh");
  const fn = functionBody(uninstall, "verified_pid_from_file") + "\n}\n";
  const dir = mkdtempSync(join(tmpdir(), "agentcall-pid-"));
  const pidFile = join(dir, "pid");
  try {
    for (const [label, content] of [
      ["带空格的数字", "12 34\n"],
      ["负号", "-12\n"],
      ["数字混字母", "123abc\n"],
      ["空首行", "\n"],
      ["cmdline 不匹配", process.pid + "\n"],
    ]) {
      writeFileSync(pidFile, content);
      const result = spawnSync("sh", ["-c", fn + '\nverified_pid_from_file "$1" VerifiedPidMarker\n', "sh", pidFile], {
        encoding: "utf8",
      });
      assert.equal(result.status, 1, label + " 必须被拒绝（不得规范化成别的 PID）");
      assert.equal(result.stdout.trim(), "", label + " 不得输出任何 PID");
    }

    // 合法 PID 仍必须通过：用 sh 自己的 PID，其 cmdline 含下面的标记。
    const valid = spawnSync(
      "sh",
      [
        "-c",
        fn + '\nprintf \'%s\\n\' "$$" > "$1"\nverified_pid_from_file "$1" VerifiedPidMarker\n',
        "sh",
        pidFile,
      ],
      { encoding: "utf8" },
    );
    assert.equal(valid.status, 0, "合法 PID 必须通过：" + valid.stderr);
    assert.match(valid.stdout.trim(), /^\d+$/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("uninstall.sh：说明 TERM 之后约 5 s 可能被 service.sh 重新拉起，remove/disable 标记存在时不复活", () => {
  const service = read("service.sh");
  const uninstall = read("uninstall.sh");
  assert.match(service, /^RESTART_DELAY_SECONDS=5$/m, "重启延迟必须仍是 5 s");
  assert.match(uninstall, /TERM[\s\S]{0,240}5 s[\s\S]{0,240}(重新拉起|重启)/, "必须说明 TERM 之后的重启延迟");
  assert.match(uninstall, /remove\/disable|remove[\s\S]{0,40}disable/, "必须说明卸载会写 remove/disable 标记");
});

test(
  "service.sh：停止路径对忽略 TERM 的音频工作进程仍有界升级到 KILL",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_STAY: "1", AGENTCALL_FAKE_AUDIO_IGNORE_TERM: "1" }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.audioPidFile) && existsSync(harness.bridgePidFile),
        20000,
        "bridge/audio pid files",
      );
      const workerPid = readPidFile(harness.audioPidFile);

      const startedAt = Date.now();
      child.kill("SIGTERM");
      await waitForExit(child, 20000);
      assert.ok(Date.now() - startedAt < 15000, "停止路径必须有界，不能无限等待");

      await waitFor(() => !processAlive(workerPid), 5000, "audio worker termination");
      const log = readFileSync(harness.logFile, "utf8");
      assert.match(
        log,
        new RegExp("audio_worker_kill pid=" + workerPid + "\\b"),
        "TERM 无效时必须升级到 KILL（有界）",
      );
      assert.match(
        log,
        new RegExp("audio_worker_stopped pid=" + workerPid + "\\b"),
        "有界 TERM→KILL 必须作用于 PID 文件里那个已核验的音频工作进程",
      );
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：remove 标记出现后不再重启桥接（卸载时不复活模块）",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_EXIT: "1" }),
        stdio: "ignore",
      });
      await waitFor(
        () => {
          try {
            return readFileSync(harness.logFile, "utf8").includes("bridge_exit attempt=1");
          } catch {
            return false;
          }
        },
        25000,
        "first bridge failure",
      );

      writeFileSync(join(harness.moduleDir, "remove"), "");
      const { code } = await waitForExit(child, 40000);

      assert.equal(code, 0, "标记移除后必须有界退出，而不是继续重启");
      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /marked_stopped/, "跳过重启必须写入原因");
      assert.equal(/bridge_start attempt=2/.test(log), false, "remove 标记存在时不得再次拉起桥接");
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

// ---------------------------------------------------------------------------------------------
// 阶段 C：service.sh 遗留缺陷硬化（PID 文件原始行校验、音频 PID 身份核验、有界 TERM→KILL）
// ---------------------------------------------------------------------------------------------

test("service.sh：read_pid 在数字规范化之前校验原始首行，损坏内容不会变成另一个 PID", () => {
  const service = read("service.sh");
  const maxDigits = /^MAX_PID_DIGITS=(\d+)$/m.exec(service);
  assert.ok(maxDigits, "service.sh 必须声明 PID 位数上限");
  const fn = "MAX_PID_DIGITS=" + maxDigits[1] + "\n" + functionBody(service, "read_pid") + "\n}\n";
  const dir = mkdtempSync(join(tmpdir(), "agentcall-service-pid-"));
  const pidFile = join(dir, "pid");
  try {
    for (const [label, content] of [
      ["带空格的数字", "12 34\n"],
      ["负号", "-12\n"],
      ["数字混字母", "123abc\n"],
      ["空首行", "\n"],
      ["超出位数上限", "12345678901\n"],
    ]) {
      writeFileSync(pidFile, content);
      const result = spawnSync("sh", ["-c", fn + '\nread_pid "$1"\n', "sh", pidFile], { encoding: "utf8" });
      assert.equal(result.status, 1, label + " 必须被拒绝（「12 34」不得被 tr 静默拼成 1234）：" + result.stdout);
      assert.equal(result.stdout.trim(), "", label + " 不得输出任何 PID");
    }

    writeFileSync(pidFile, "4321\n");
    const valid = spawnSync("sh", ["-c", fn + '\nread_pid "$1"\n', "sh", pidFile], { encoding: "utf8" });
    assert.equal(valid.status, 0, "合法 PID 必须通过：" + valid.stderr);
    assert.equal(valid.stdout.trim(), "4321", "合法 PID 必须原样输出");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("service.sh：stop_audio_worker 与 reclaim_stale_worker 使用同一套 PID 身份核验", () => {
  const service = read("service.sh");
  const body = functionBody(service, "stop_audio_worker");
  assert.match(body, /read_pid "\$AUDIO_PID_FILE"/, "停止路径必须从 PID 文件读取目标");
  assert.match(body, /is_audio_worker_pid "\$worker"/, "终止前必须按 cmdline 核验音频工作进程身份");
  assert.match(body, /audio_worker_pid_ignored reason=/, "身份无法核验时必须记录机器可读的拒绝原因");
  assert.match(body, /terminate_pid "\$worker" "\$STOP_GRACE_SECONDS" audio_worker/, "核验通过后走同一套有界 TERM→KILL");
  assert.ok(
    (body.match(/read_pid "\$AUDIO_PID_FILE"/g) || []).length >= 2,
    "终止之后必须重新读取 PID 文件（监督循环可能在终止窗口内写入新的 PID）",
  );
});

test(
  "service.sh：停止路径拒绝终止 cmdline 不是音频工作进程的 PID（PID 复用防护）",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    let foreign = null;
    let workerPid = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_STAY: "1" }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.audioPidFile) && existsSync(harness.bridgePidFile),
        20000,
        "bridge/audio pid files",
      );
      workerPid = readPidFile(harness.audioPidFile);
      assert.equal(processAlive(workerPid), true, "音频工作进程替身必须存活");
      await sleep(200);

      // 模拟 PID 文件被改写或 PID 复用：文件里是一个 cmdline 与音频工作入口无关的存活进程。
      foreign = spawn("sh", ["-c", "while :; do sleep 1; done"], { stdio: "ignore" });
      await sleep(100);
      assert.equal(processAlive(foreign.pid), true, "无关进程替身必须存活");
      writeFileSync(harness.audioPidFile, String(foreign.pid) + "\n");

      child.kill("SIGTERM");
      await waitForExit(child, 20000);
      await sleep(200);

      assert.equal(
        processAlive(foreign.pid),
        true,
        "cmdline 不匹配的 PID 不得被终止（PID 复用会误杀无关进程）",
      );
      const log = readFileSync(harness.logFile, "utf8");
      assert.match(
        log,
        new RegExp("audio_worker_pid_ignored reason=identity_mismatch pid=" + foreign.pid + "\\b"),
        "拒绝终止必须记录机器可读原因：" + log,
      );
      assert.equal(existsSync(harness.audioPidFile), false, "停止后仍须收回音频 PID 文件");
    } finally {
      killQuietly(child);
      killQuietly(foreign);
      if (workerPid !== null) {
        try {
          if (processAlive(workerPid)) process.kill(workerPid, "SIGKILL");
        } catch {
          // 测试清理：进程已退出时忽略。
        }
      }
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);


// ---------------------------------------------------------------------------------------------
// 任务 11B：音频角色就绪门禁（I1）与 in-product 重启（I2）
//
// I1：service.sh 过去在后台启动音频 app_process 后立刻拉起 BridgeMain；音频侧要等
//     app_process + framework 初始化才 bind abstract socket，正常开机可能输掉竞争，
//     让桥接进入永久 BLOCKED。现在 BridgeMain 之前必须有界等待音频 socket；超时则
//     不启动半就绪的桥接（音频监督进程仍保留自己的有限重启预算）。
// I2：agentcallctl restart 写 restart.requested（内容是本次 service.sh 的 PID）后执行
//     固定 CLI shutdown；service.sh 在桥接干净退出后消费该标记并在同一有限循环内重新
//     拉起。remove/disable 标记优先：不得复活已移除模块。
// ---------------------------------------------------------------------------------------------

test(
  "service.sh：BridgeMain 之前有界等待音频角色 socket；超时不启动半就绪桥接",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      const startedAt = Date.now();
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, {
          AGENTCALL_FAKE_AUDIO_NO_SOCKET: "1",
          AGENTCALL_AUDIO_READY_TIMEOUT_SECONDS: "2",
        }),
        stdio: "ignore",
      });
      await waitFor(() => existsSync(harness.audioPidFile), 15000, "audio worker pid file");
      const workerPid = readPidFile(harness.audioPidFile);
      assert.equal(processAlive(workerPid), true, "音频工作进程替身必须存活");

      const { code } = await waitForExit(child, 30000);
      assert.notEqual(code, 0, "音频未就绪时不得假装启动成功");
      assert.ok(Date.now() - startedAt < 20000, "等待必须有界，不能挂住开机流程");

      assert.equal(existsSync(harness.bridgePidFile), false, "音频未就绪时不得启动 BridgeMain");
      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /audio_worker_ready_timeout waited=2s/, "超时必须记录有界等待的时长：" + log);
      assert.match(log, /bridge_start_skipped reason=audio_not_ready/, "跳过 BridgeMain 必须记录机器可读原因");
      assert.equal(/bridge_start attempt=/.test(log), false, "不得启动半就绪的桥接");
      await waitFor(() => !processAlive(workerPid), 10000, "audio worker termination after timeout");
      assert.equal(processAlive(workerPid), false, "超时退出也不得留下持有的音频 app_process");
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：音频 socket 就绪后才拉起桥接（真实日志顺序）",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, { AGENTCALL_FAKE_BRIDGE_STAY: "1" }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.bridgePidFile) && existsSync(harness.servicePidFile),
        20000,
        "bridge/service pid files",
      );
      const log = readFileSync(harness.logFile, "utf8");
      const audioReadyAt = log.indexOf("audio_worker_ready socket=@org.krvyft.agentcall.audio-role");
      const bridgeStartAt = log.indexOf("bridge_start attempt=");
      assert.ok(audioReadyAt > -1, "必须记录音频角色 socket 就绪：" + log);
      assert.ok(bridgeStartAt > -1, "音频就绪后必须启动桥接：" + log);
      assert.ok(audioReadyAt < bridgeStartAt, "BridgeMain 必须在音频角色就绪之后启动");
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：restart.requested（本服务 PID）在桥接干净退出后重新拉起一次",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    const trigger = join(harness.dataRoot, "restart-trigger");
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, {
          AGENTCALL_FAKE_BRIDGE_STAY: "1",
          AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER: trigger,
        }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.bridgePidFile) && existsSync(harness.servicePidFile),
        20000,
        "bridge/service pid files",
      );
      const firstBridgePid = readPidFile(harness.bridgePidFile);
      const servicePid = readPidFile(harness.servicePidFile);
      assert.ok(servicePid > 0, "service.sh 必须写自己的 PID 供 restart 命令核验");

      writeFileSync(harness.restartRequestFile, servicePid + "\n");
      writeFileSync(trigger, "");

      await waitFor(
        () => existsSync(harness.bridgePidFile) && readPidFile(harness.bridgePidFile) !== firstBridgePid,
        20000,
        "bridge relaunch after restart request",
      );
      const secondBridgePid = readPidFile(harness.bridgePidFile);
      assert.notEqual(secondBridgePid, firstBridgePid, "重启必须拉起新的桥接进程");
      assert.equal(processAlive(firstBridgePid), false, "旧桥接进程必须已经干净退出");
      assert.equal(existsSync(harness.restartRequestFile), false, "重启请求只能被消费一次");

      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /bridge_restart_requested/, "必须记录重启请求被消费：" + log);
      assert.match(log, /bridge_exit_clean/, "干净退出（退出码 0）也必须被记录");

      child.kill("SIGTERM");
      await waitForExit(child, 20000);
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：remove 标记存在时 restart 请求不得复活桥接",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    const trigger = join(harness.dataRoot, "restart-trigger");
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, {
          AGENTCALL_FAKE_BRIDGE_STAY: "1",
          AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER: trigger,
        }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.bridgePidFile) && existsSync(harness.servicePidFile),
        20000,
        "bridge/service pid files",
      );
      const firstBridgePid = readPidFile(harness.bridgePidFile);
      const servicePid = readPidFile(harness.servicePidFile);

      writeFileSync(harness.restartRequestFile, servicePid + "\n");
      writeFileSync(join(harness.moduleDir, "remove"), "");
      writeFileSync(trigger, "");

      const { code } = await waitForExit(child, 30000);
      assert.equal(code, 0, "标记移除后必须有界退出，而不是继续重启");
      assert.equal(processAlive(firstBridgePid), false);
      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /bridge_restart_skipped reason=marked_stopped/, "必须记录因标记而拒绝重启：" + log);
      assert.equal(/bridge_start attempt=/.test(log.split("bridge_restart_skipped")[1] || ""), false, "remove 标记存在时不得再次拉起桥接");
      assert.equal(existsSync(harness.restartRequestFile), false, "被拒绝的重启请求也必须被收回");
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：不属于当前 service.sh 的过期 restart 标记被丢弃，不触发重启",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    const trigger = join(harness.dataRoot, "restart-trigger");
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, {
          AGENTCALL_FAKE_BRIDGE_STAY: "1",
          AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER: trigger,
        }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.bridgePidFile) && existsSync(harness.servicePidFile),
        20000,
        "bridge/service pid files",
      );
      const firstBridgePid = readPidFile(harness.bridgePidFile);

      // 上一次开机/上一次实例遗留的请求：内容是别的 PID。
      writeFileSync(harness.restartRequestFile, process.pid + "\n");
      writeFileSync(trigger, "");

      const { code } = await waitForExit(child, 30000);
      assert.equal(code, 0, "过期请求不得让服务继续重启");
      assert.equal(processAlive(firstBridgePid), false);
      const log = readFileSync(harness.logFile, "utf8");
      assert.match(log, /bridge_restart_stale/, "过期请求必须被显式丢弃并记录：" + log);
      assert.match(log, /bridge_exit_clean/, "过期请求应落到普通干净退出路径");
      assert.equal(/bridge_restart_requested/.test(log), false, "过期请求不得被消费");
      assert.equal(existsSync(harness.restartRequestFile), false, "过期请求文件必须被收回");
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test("agentcallctl：restart 是固定模块命令，写重启标记后只执行固定 shutdown", () => {
  const ctl = read("bin/agentcallctl");
  assert.match(ctl, /status|config-get|config-set|diagnose|shutdown|restart/, "restart 必须是固定命令之一");
  assert.match(
    ctl,
    /^RESTART_REQUEST_FILE=\$DATA_ROOT\/restart\.requested$/m,
    "重启请求必须是 root 私有目录下的固定文件",
  );
  assert.match(ctl, /^SERVICE_PID_FILE=\$DATA_ROOT\/service\.pid$/m, "必须核验正在运行的 service.sh");
  assert.match(ctl, /service_not_running/, "service.sh 未运行时必须给出机器可读错误");
  assert.match(ctl, /module_disabled/, "remove/disable 标记存在时必须拒绝重启");
  assert.match(ctl, /run_cli shutdown/, "只允许执行固定 CLI shutdown，绝不执行任意 shell");
  assert.match(ctl, /restart_refused/, "关闭裁决不干净/仍隔离时必须拒绝重启并给出机器码");
  assert.equal(/run_cli restart/.test(ctl), false, "restart 不得转发给 AgentCallCli（它不是 RPC 命令）");
  assert.equal(/eval |sh -c|system\(/.test(ctl), false, "包装器不得新增任意 shell 路径");
  const restartAt = ctl.indexOf("restart_bridge");
  const genericAt = ctl.indexOf('run_cli "\$COMMAND"');
  assert.ok(restartAt > -1 && genericAt > restartAt, "restart 必须在通用转发之前单独处理");
});


// ---------------------------------------------------------------------------------------------
// 任务 11B / I2：agentcallctl restart 的真实执行契约
//
// 宿主上没有 root，也没有 /system/bin/app_process；测试用 PATH 前置的假 id（仅替身）与
// 文档化的 AGENTCALL_APP_PROCESS / AGENTCALL_DATA_ROOT 注入点执行真实包装器，断言：
// - 成功只在“标记已写 + 固定 shutdown 已执行”后返回 restart requested；
// - service.sh 不在运行时明确拒绝（不会留下将来生效的标记）；
// - remove/disable 标记优先，不写标记也不执行任何 CLI；
// - shutdown 失败时收回标记并返回 restart_unconfirmed。
// ---------------------------------------------------------------------------------------------

function ctlHarness() {
  const root = mkdtempSync(join(tmpdir(), "agentcall-ctl-"));
  const moduleDir = join(root, "module");
  const dataRoot = join(root, "data");
  const binDir = join(root, "bin");
  const serviceDir = join(root, "fake-service");
  mkdirSync(join(moduleDir, "bin"), { recursive: true });
  mkdirSync(join(moduleDir, "bridge"), { recursive: true });
  mkdirSync(join(dataRoot, "logs"), { recursive: true });
  mkdirSync(binDir, { recursive: true });
  mkdirSync(serviceDir, { recursive: true });
  copyFileSync(join(MODULE, "bin", "agentcallctl"), join(moduleDir, "bin", "agentcallctl"));
  writeFileSync(join(moduleDir, "bridge", "agentcall-bridge.jar"), "stub");

  const fakeId = join(binDir, "id");
  writeFileSync(fakeId, "#!/bin/sh\nprintf '0\\n'\n");
  chmodSync(fakeId, 0o755);

  const argsFile = join(root, "app-process-args.txt");
  const appProcess = join(binDir, "app_process");
  writeFileSync(
    appProcess,
    [
      "#!/bin/sh",
      'if [ -n "$AGENTCALL_FAKE_ARGS_FILE" ]; then',
      '  printf "%s\\n" "$*" >> "$AGENTCALL_FAKE_ARGS_FILE"',
      "fi",
      'if [ -n "$AGENTCALL_FAKE_STDOUT" ]; then printf "%s\\n" "$AGENTCALL_FAKE_STDOUT"; fi',
      'exit "${AGENTCALL_FAKE_CLI_EXIT:-0}"',
      "",
    ].join("\n"),
  );
  chmodSync(appProcess, 0o755);

  return {
    root,
    moduleDir,
    dataRoot,
    binDir,
    serviceDir,
    argsFile,
    appProcess,
    ctl: join(moduleDir, "bin", "agentcallctl"),
    servicePidFile: join(dataRoot, "service.pid"),
    restartRequestFile: join(dataRoot, "restart.requested"),
  };
}

function runCtl(harness, env = {}) {
  return spawnSync("sh", [harness.ctl, "restart"], {
    encoding: "utf8",
    timeout: 20000,
    env: {
      ...process.env,
      PATH: harness.binDir + ":" + process.env.PATH,
      AGENTCALL_DATA_ROOT: harness.dataRoot,
      AGENTCALL_APP_PROCESS: harness.appProcess,
      AGENTCALL_FAKE_ARGS_FILE: harness.argsFile,
      ...env,
    },
  });
}

function startFakeService(harness) {
  const script = join(harness.serviceDir, "service.sh");
  writeFileSync(script, "#!/bin/sh\nwhile :; do sleep 1; done\n");
  return spawn("sh", [script], { stdio: "ignore" });
}

function ctlErrorCode(result) {
  const line = result.stdout.trim().split("\n").pop();
  return JSON.parse(line).error.code;
}

test("agentcallctl restart：写本服务 PID 的重启标记并只执行固定 shutdown", () => {
  const harness = ctlHarness();
  let service = null;
  try {
    service = startFakeService(harness);
    writeFileSync(harness.servicePidFile, String(service.pid) + "\n");

    const result = runCtl(harness, {
      AGENTCALL_FAKE_STDOUT: JSON.stringify({
        stopping: true,
        clean: true,
        shutdownCode: null,
        supervision: { clean: true, ownedSession: true, microphoneIsolated: false },
      }),
    });
    assert.equal(result.status, 0, "restart 必须成功：" + result.stdout + result.stderr);
    assert.equal(result.stdout.trim(), '{"restart":"requested"}', "成功只输出机器可读的请求结果");
    assert.equal(readFileSync(harness.restartRequestFile, "utf8").trim(), String(service.pid), "标记必须指向当前 service.sh");
    const args = readFileSync(harness.argsFile, "utf8").trim();
    assert.match(args, /AgentCallCli shutdown$/, "只允许固定 CLI shutdown：" + args);
    assert.equal(/ restart/.test(args), false, "restart 不得转发给 AgentCallCli");
  } finally {
    killQuietly(service);
    rmSync(harness.root, { recursive: true, force: true });
  }
});

test("agentcallctl restart：关闭裁决 clean=false 时拒绝重启、收回标记并返回 restart_refused", () => {
  const harness = ctlHarness();
  let service = null;
  try {
    service = startFakeService(harness);
    writeFileSync(harness.servicePidFile, String(service.pid) + "\n");

    const result = runCtl(harness, {
      AGENTCALL_FAKE_STDOUT: JSON.stringify({
        stopping: true,
        clean: false,
        shutdownCode: "shutdown_call_unresolved",
        supervision: { clean: false, ownedSession: true, microphoneIsolated: true },
      }),
    });
    assert.notEqual(result.status, 0, "不干净的关闭不得重启：" + result.stdout);
    assert.equal(ctlErrorCode(result), "restart_refused");
    const message = JSON.parse(result.stdout.trim()).error.message;
    assert.match(message, /clean=false/, "必须给出裁决原因：" + message);
    assert.match(message, /shutdown_call_unresolved/, "必须给出机器码：" + message);
    assert.match(message, /microphoneIsolated=true/, "必须给出隔离读回：" + message);
    assert.equal(existsSync(harness.restartRequestFile), false, "拒绝重启必须收回标记");
    const args = readFileSync(harness.argsFile, "utf8").trim();
    assert.match(args, /AgentCallCli shutdown$/, "仍只执行固定 shutdown");
  } finally {
    killQuietly(service);
    rmSync(harness.root, { recursive: true, force: true });
  }
});

test("agentcallctl restart：clean=true 但 microphoneIsolated=true（无拥有会话）也拒绝重启", () => {
  const harness = ctlHarness();
  let service = null;
  try {
    service = startFakeService(harness);
    writeFileSync(harness.servicePidFile, String(service.pid) + "\n");

    const result = runCtl(harness, {
      AGENTCALL_FAKE_STDOUT: JSON.stringify({
        stopping: true,
        clean: true,
        shutdownCode: null,
        supervision: { clean: true, ownedSession: false, microphoneIsolated: true },
      }),
    });
    assert.notEqual(result.status, 0, "麦克风仍隔离时不得重启：" + result.stdout);
    assert.equal(ctlErrorCode(result), "restart_refused");
    const message = JSON.parse(result.stdout.trim()).error.message;
    assert.match(message, /microphoneIsolated=true/, "不能只看 clean：" + message);
    assert.equal(existsSync(harness.restartRequestFile), false, "拒绝重启必须收回标记");
  } finally {
    killQuietly(service);
    rmSync(harness.root, { recursive: true, force: true });
  }
});

test("agentcallctl restart：缺少关闭裁决时也拒绝（fail closed，不猜测干净）", () => {
  const harness = ctlHarness();
  let service = null;
  try {
    service = startFakeService(harness);
    writeFileSync(harness.servicePidFile, String(service.pid) + "\n");

    const result = runCtl(harness, { AGENTCALL_FAKE_STDOUT: '{"stopping":true}' });
    assert.notEqual(result.status, 0, "缺少裁决不得重启：" + result.stdout);
    assert.equal(ctlErrorCode(result), "restart_refused");
    assert.match(JSON.parse(result.stdout.trim()).error.message, /verdict_missing/, "必须点名缺失的裁决");
    assert.equal(existsSync(harness.restartRequestFile), false, "拒绝重启必须收回标记");
  } finally {
    killQuietly(service);
    rmSync(harness.root, { recursive: true, force: true });
  }
});

test("agentcallctl restart：service.sh 不在运行时拒绝，不留下将来生效的标记", () => {
  const harness = ctlHarness();
  try {
    const result = runCtl(harness);
    assert.notEqual(result.status, 0);
    assert.equal(ctlErrorCode(result), "service_not_running");
    assert.equal(existsSync(harness.restartRequestFile), false, "拒绝重启时不得写标记");
    assert.equal(existsSync(harness.argsFile), false, "拒绝重启时不得启动 CLI");
  } finally {
    rmSync(harness.root, { recursive: true, force: true });
  }
});

test("agentcallctl restart：remove/disable 标记优先，绝不复活已移除模块", () => {
  for (const marker of ["remove", "disable"]) {
    const harness = ctlHarness();
    let service = null;
    try {
      service = startFakeService(harness);
      writeFileSync(harness.servicePidFile, String(service.pid) + "\n");
      writeFileSync(join(harness.moduleDir, marker), "");

      const result = runCtl(harness);
      assert.notEqual(result.status, 0, marker + " 标记必须阻止重启");
      assert.equal(ctlErrorCode(result), "module_disabled");
      assert.equal(existsSync(harness.restartRequestFile), false, marker + " 标记存在时不得写重启请求");
      assert.equal(existsSync(harness.argsFile), false, "被标记移除时不得执行任何 CLI");
    } finally {
      killQuietly(service);
      rmSync(harness.root, { recursive: true, force: true });
    }
  }
});

test("agentcallctl restart：固定 shutdown 失败时收回请求并报 restart_unconfirmed", () => {
  const harness = ctlHarness();
  let service = null;
  try {
    service = startFakeService(harness);
    writeFileSync(harness.servicePidFile, String(service.pid) + "\n");

    const result = runCtl(harness, { AGENTCALL_FAKE_CLI_EXIT: "1" });
    assert.notEqual(result.status, 0);
    assert.equal(ctlErrorCode(result), "restart_unconfirmed");
    assert.equal(existsSync(harness.restartRequestFile), false, "失败的请求不得留在磁盘上");
    const args = readFileSync(harness.argsFile, "utf8").trim();
    assert.match(args, /AgentCallCli shutdown$/);
  } finally {
    killQuietly(service);
    rmSync(harness.root, { recursive: true, force: true });
  }
});

test("uninstall.sh：先收回待处理的重启请求，避免卸载过程中桥接被重新拉起", () => {
  const uninstall = read("uninstall.sh");
  const clearAt = uninstall.indexOf('rm -f "$RESTART_REQUEST_FILE"');
  const shutdownAt = uninstall.indexOf('"$CTL" shutdown');
  const cleanupAt = uninstall.indexOf('rm -rf "$DATA_ROOT"');
  assert.ok(clearAt > -1, "卸载必须先收回 restart.requested");
  assert.ok(shutdownAt > -1 && cleanupAt > -1);
  assert.ok(clearAt < shutdownAt, "收回请求必须早于 shutdown（否则 shutdown 后仍可能被拉起）");
  assert.ok(clearAt < cleanupAt, "收回请求必须早于删除数据目录");
});

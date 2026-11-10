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

test("uninstall.sh 诚实性：不宣称桥接没有的“交还会话”能力，并区分 socket 与 CLI 可执行性", () => {
  const uninstall = read("uninstall.sh");
  // 桥接当前没有会话/租约（session.request 为 method_not_implemented），脚本必须说明它无法终止/交还通话。
  assert.match(uninstall, /(没有|无).*(会话|租约)/, "必须说明桥接没有会话/租约实现");
  assert.match(uninstall, /(无法|不能).*(终止|交还).*(通话|AI)/, "必须明确无法保证终止/交还正在进行的通话");
  assert.equal(/交还\/终止其托管的 AI 会话/.test(uninstall), false, "不得复述虚假的交还契约");
  assert.match(uninstall, /socket_present/, "必须单独探测 abstract socket 是否存在");
  assert.match(uninstall, /ctl_usable/, "必须单独判断固定 CLI 是否可执行");
  assert.equal(/\] && \[ -x "\$CTL" \]/.test(uninstall), false, "不得把 socket 存在与 CLI 可执行混在同一个条件里");
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
  mkdirSync(join(moduleDir, "bridge"), { recursive: true });
  mkdirSync(join(dataRoot, "logs"), { recursive: true });
  mkdirSync(binDir, { recursive: true });
  writeFileSync(join(moduleDir, "bridge", "agentcall-bridge.jar"), "stub");
  copyFileSync(join(MODULE, "service.sh"), join(moduleDir, "service.sh"));

  const appProcess = join(binDir, "app_process");
  writeFileSync(
    appProcess,
    [
      "#!/bin/sh",
      'case "$*" in',
      "  *BridgeMain*)",
      '    if [ "${AGENTCALL_FAKE_BRIDGE_STAY:-0}" = "1" ]; then sleep 300; else sleep 1; fi',
      "    exit 0",
      "    ;;",
      "esac",
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
    script: join(moduleDir, "service.sh"),
    audioPidFile: join(dataRoot, "audio-worker.pid"),
    bridgePidFile: join(dataRoot, "bridge.pid"),
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
      assert.match(log, /audio_worker_stopped/, "停止路径必须记录并验证音频工作进程已终止");
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

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

// Task 11D2b host 级契约测试：
// - service.sh 停止顺序：先请求桥接优雅关闭（固定 CLI shutdown，有界等待退出），
//   再停音频工作进程，最后才做有界 TERM→KILL 兜底；
// - 桥接不响应时仍有界升级，且不阻塞停止路径；
// - uninstall.sh 在删除数据目录之前读回关闭结果/status，并显式警告未确认的关闭；
// - agentcallctl calibrate 只从 stdin 透传负载、拒绝 argv 负载。
const MODULE = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const NL = String.fromCharCode(10);

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

function waitForExit(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("process did not exit in " + timeoutMs + " ms")), timeoutMs);
    child.once("exit", (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal });
    });
  });
}

function stopFakeProcess(pid) {
  try {
    if (pid && processAlive(pid)) process.kill(pid, "SIGKILL");
  } catch {
    // 测试清理：进程已退出时忽略。
  }
}

// ---------------------------------------------------------------------------------------------
// service.sh：假的 app_process / getprop / /proc/net/unix
// ---------------------------------------------------------------------------------------------

function serviceHarness() {
  const root = mkdtempSync(join(tmpdir(), "agentcall-order-"));
  const moduleDir = join(root, "module");
  const dataRoot = join(root, "data");
  const binDir = join(root, "bin");
  const unixList = join(root, "proc-net-unix.txt");
  mkdirSync(join(moduleDir, "bridge"), { recursive: true });
  mkdirSync(join(dataRoot, "logs"), { recursive: true });
  mkdirSync(binDir, { recursive: true });
  writeFileSync(join(moduleDir, "bridge", "agentcall-bridge.jar"), "stub");
  writeFileSync(unixList, "");
  copyFileSync(join(MODULE, "service.sh"), join(moduleDir, "service.sh"));

  // 假 app_process：AgentCallCli 分支模拟“固定 CLI shutdown”；BridgeMain 分支模拟桥接，
  // 只有看到 CLI 写下的 trigger 才干净退出（用于验证顺序），否则一直存活。
  const appProcess = join(binDir, "app_process");
  writeFileSync(
    appProcess,
    [
      "#!/bin/sh",
      'case "$*" in',
      "  *AgentCallCli*)",
      '    if [ -n "$AGENTCALL_FAKE_CLI_LOG" ]; then echo "$*" >> "$AGENTCALL_FAKE_CLI_LOG"; fi',
      '    if [ "$AGENTCALL_FAKE_CLI_HANG" = "1" ]; then while :; do sleep 1; done; fi',
      '    if [ -n "$AGENTCALL_FAKE_SHUTDOWN_TRIGGER" ] && [ "$AGENTCALL_FAKE_CLI_IGNORE_SHUTDOWN" != "1" ]; then echo ready > "$AGENTCALL_FAKE_SHUTDOWN_TRIGGER"; fi',
      '    if [ -n "$AGENTCALL_FAKE_SHUTDOWN_JSON" ]; then echo "$AGENTCALL_FAKE_SHUTDOWN_JSON"; fi',
      '    exit "$AGENTCALL_FAKE_CLI_EXIT"',
      "    ;;",
      "  *BridgeMain*)",
      '    if [ "$AGENTCALL_FAKE_BRIDGE_NO_SOCKET" != "1" ] && [ -n "$AGENTCALL_UNIX_LIST" ]; then echo "@org.krvyft.agentcall.control" >> "$AGENTCALL_UNIX_LIST"; fi',
      "    while :; do",
      '      if [ -n "$AGENTCALL_FAKE_SHUTDOWN_TRIGGER" ] && [ -f "$AGENTCALL_FAKE_SHUTDOWN_TRIGGER" ]; then exit 0; fi',
      '      if [ -n "$AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER" ] && [ -f "$AGENTCALL_FAKE_BRIDGE_RESTART_TRIGGER" ]; then exit 0; fi',
      "      sleep 1",
      "    done",
      "    ;;",
      "esac",
      'if [ "$AGENTCALL_FAKE_AUDIO_NO_SOCKET" != "1" ] && [ -n "$AGENTCALL_UNIX_LIST" ]; then echo "@org.krvyft.agentcall.audio-role" >> "$AGENTCALL_UNIX_LIST"; fi',
      "while :; do sleep 1; done",
      "",
    ].join(NL),
  );
  chmodSync(appProcess, 0o755);

  const getprop = join(binDir, "getprop");
  writeFileSync(getprop, "#!/bin/sh" + NL + "echo 1" + NL);
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
    logFile: join(dataRoot, "logs", "service.log"),
  };
}

function serviceEnv(harness, extra = {}) {
  return {
    ...process.env,
    AGENTCALL_APP_PROCESS: harness.appProcess,
    AGENTCALL_GETPROP: harness.getprop,
    AGENTCALL_DATA_ROOT: harness.dataRoot,
    AGENTCALL_UNIX_LIST: harness.unixList,
    AGENTCALL_FAKE_CLI_EXIT: "0",
    AGENTCALL_FAKE_CLI_HANG: "0",
    AGENTCALL_FAKE_CLI_IGNORE_SHUTDOWN: "0",
    AGENTCALL_FAKE_SHUTDOWN_EXIT: "0",
    AGENTCALL_FAKE_STATUS_EXIT: "0",
    ...extra,
  };
}

test(
  "service.sh：停止路径先请求桥接优雅关闭（固定 CLI shutdown），确认退出后才停音频工作进程",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    const trigger = join(harness.root, "shutdown-trigger");
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, {
          AGENTCALL_FAKE_BRIDGE_STAY: "1",
          AGENTCALL_FAKE_SHUTDOWN_TRIGGER: trigger,
          AGENTCALL_FAKE_SHUTDOWN_JSON: JSON.stringify({
            stopping: true,
            clean: true,
            shutdownCode: null,
            supervision: { clean: true, ownedSession: true, microphoneIsolated: false },
          }),
          AGENTCALL_GRACEFUL_SHUTDOWN_WAIT_SECONDS: "5",
          AGENTCALL_BRIDGE_EXIT_WAIT_SECONDS: "3",
          AGENTCALL_STOP_GRACE_SECONDS: "1",
        }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.audioPidFile) && existsSync(harness.bridgePidFile),
        20000,
        "bridge/audio pid files",
      );
      const workerPid = readPidFile(harness.audioPidFile);
      const bridgePid = readPidFile(harness.bridgePidFile);

      child.kill("SIGTERM");
      await waitForExit(child, 30000);

      assert.equal(processAlive(workerPid), false, "停止后不得留下音频工作 app_process");
      assert.equal(processAlive(bridgePid), false, "优雅关闭后桥接进程必须退出");

      const log = readFileSync(harness.logFile, "utf8");
      const requestAt = log.indexOf("bridge_shutdown_request pid=" + bridgePid);
      const resultAt = log.indexOf("bridge_shutdown_result clean=true shutdownCode=null");
      const confirmedAt = log.indexOf("bridge_shutdown_confirmed pid=" + bridgePid);
      const workerAt = log.indexOf("audio_worker_stopped pid=" + workerPid);
      assert.ok(requestAt > -1, "停止路径必须先请求桥接优雅关闭：" + log);
      assert.ok(resultAt > requestAt, "必须记录固定 CLI 的关闭结果：" + log);
      assert.ok(confirmedAt > resultAt, "必须确认桥接进程已退出：" + log);
      assert.ok(workerAt > confirmedAt, "音频工作进程必须在桥接优雅关闭确认之后才停止：" + log);
      assert.equal(/bridge_kill/.test(log), false, "优雅关闭成功时不得升级到 KILL");
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "service.sh：桥接不响应优雅关闭时仍有界升级，且桥接强杀兜底在音频工作进程之后",
  { timeout: 60000 },
  async () => {
    const harness = serviceHarness();
    let child = null;
    try {
      child = spawn("sh", [harness.script], {
        env: serviceEnv(harness, {
          AGENTCALL_FAKE_BRIDGE_STAY: "1",
          AGENTCALL_FAKE_CLI_HANG: "1",
          AGENTCALL_GRACEFUL_SHUTDOWN_WAIT_SECONDS: "2",
          AGENTCALL_BRIDGE_EXIT_WAIT_SECONDS: "1",
          AGENTCALL_STOP_GRACE_SECONDS: "1",
        }),
        stdio: "ignore",
      });
      await waitFor(
        () => existsSync(harness.audioPidFile) && existsSync(harness.bridgePidFile),
        20000,
        "bridge/audio pid files",
      );
      const workerPid = readPidFile(harness.audioPidFile);
      const bridgePid = readPidFile(harness.bridgePidFile);

      const startedAt = Date.now();
      child.kill("SIGTERM");
      await waitForExit(child, 30000);
      assert.ok(Date.now() - startedAt < 20000, "停止路径必须有界，不能等待不响应的 CLI/桥接");

      assert.equal(processAlive(workerPid), false, "有界升级后不得留下音频工作进程");
      assert.equal(processAlive(bridgePid), false, "有界升级后不得留下桥接进程");

      const log = readFileSync(harness.logFile, "utf8");
      const timeoutAt = log.indexOf("bridge_shutdown_cli_timeout");
      const workerAt = log.indexOf("audio_worker_stopped pid=" + workerPid);
      const bridgeAt = log.indexOf("bridge_stopped pid=" + bridgePid);
      assert.ok(timeoutAt > -1, "CLI 超时必须记录机器可读原因：" + log);
      assert.ok(workerAt > timeoutAt, "CLI 超时后仍要继续停音频工作进程：" + log);
      assert.ok(bridgeAt > workerAt, "TERM→KILL 兜底必须在音频工作进程停止之后：" + log);
    } finally {
      killQuietly(child);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

// ---------------------------------------------------------------------------------------------
// uninstall.sh：假固定 CLI（module/bin/agentcallctl）与假 socket/PID 进程
// ---------------------------------------------------------------------------------------------

function uninstallHarness() {
  const root = mkdtempSync(join(tmpdir(), "agentcall-uninstall-order-"));
  const moduleDir = join(root, "module");
  const dataRoot = join(root, "data");
  mkdirSync(join(moduleDir, "bin"), { recursive: true });
  mkdirSync(dataRoot, { recursive: true });
  copyFileSync(join(MODULE, "uninstall.sh"), join(moduleDir, "uninstall.sh"));

  const unixList = join(root, "proc-net-unix.txt");
  writeFileSync(unixList, "@org.krvyft.agentcall.control" + NL);
  const ctlLog = join(root, "ctl.log");
  const ctl = join(moduleDir, "bin", "agentcallctl");
  writeFileSync(
    ctl,
    [
      "#!/bin/sh",
      'echo "$*" >> "$AGENTCALL_FAKE_CTL_LOG"',
      'case "$1" in',
      "  shutdown)",
      '    if [ -n "$AGENTCALL_FAKE_SHUTDOWN_JSON" ]; then echo "$AGENTCALL_FAKE_SHUTDOWN_JSON"; fi',
      '    if [ "$AGENTCALL_FAKE_KEEP_SOCKET" != "1" ]; then echo "" > "$AGENTCALL_FAKE_SOCKET_FILE"; fi',
      '    exit "$AGENTCALL_FAKE_SHUTDOWN_EXIT"',
      "    ;;",
      "  status)",
      '    if [ -n "$AGENTCALL_FAKE_STATUS_JSON" ]; then echo "$AGENTCALL_FAKE_STATUS_JSON"; fi',
      '    exit "$AGENTCALL_FAKE_STATUS_EXIT"',
      "    ;;",
      "esac",
      "exit 0",
      "",
    ].join(NL),
  );
  chmodSync(ctl, 0o755);

  return {
    root,
    moduleDir,
    dataRoot,
    unixList,
    ctlLog,
    ctl,
    script: join(moduleDir, "uninstall.sh"),
    audioPidFile: join(dataRoot, "audio-worker.pid"),
    bridgePidFile: join(dataRoot, "bridge.pid"),
  };
}

function uninstallEnv(harness, extra = {}) {
  return {
    ...process.env,
    AGENTCALL_DATA_ROOT: harness.dataRoot,
    AGENTCALL_UNIX_LIST: harness.unixList,
    AGENTCALL_FAKE_CTL_LOG: harness.ctlLog,
    AGENTCALL_FAKE_SOCKET_FILE: harness.unixList,
    AGENTCALL_FAKE_SHUTDOWN_JSON: "",
    AGENTCALL_FAKE_STATUS_JSON: "",
    AGENTCALL_FAKE_SHUTDOWN_EXIT: "0",
    AGENTCALL_FAKE_STATUS_EXIT: "0",
    AGENTCALL_FAKE_KEEP_SOCKET: "0",
    AGENTCALL_STOP_WAIT_SECONDS: "1",
    AGENTCALL_KILL_WAIT_SECONDS: "1",
    ...extra,
  };
}

// 通过一次性的 sh 在后台拉起替身并立即退出：替身会被 reparent，避免 spawnSync 阻塞
// Node 事件循环时留下僵尸进程，导致 /proc/<pid> 在停止后仍然存在（误判“未退出”）。
function startFakeProcess(root, marker, label) {
  const script = join(root, label + ".sh");
  writeFileSync(script, "#!/bin/sh" + NL + "while :; do sleep 1; done" + NL);
  const launched = spawnSync(
    "sh",
    ["-c", '"sh" "$1" "$2" "$3" >/dev/null 2>&1 & echo $!', "sh", script, marker, label],
    { encoding: "utf8" },
  );
  assert.equal(launched.status, 0, "无法启动替身进程：" + launched.stderr);
  const pid = Number(launched.stdout.trim());
  assert.ok(pid > 1, "替身进程必须有 PID");
  return pid;
}

test(
  "uninstall.sh：干净关闭时读回 clean/shutdownCode/隔离状态，再停音频工作进程",
  { timeout: 60000 },
  async () => {
    const harness = uninstallHarness();
    const workerPid = startFakeProcess(harness.root, "org.krvyft.agentcall.bridge.audio.AudioWorkerMain", "agentcall-audio-worker");
    try {
      await sleep(100);
      writeFileSync(harness.audioPidFile, String(workerPid) + NL);

      const result = spawnSync("sh", [harness.script], {
        env: uninstallEnv(harness, {
          AGENTCALL_FAKE_SHUTDOWN_JSON: JSON.stringify({
            stopping: true,
            clean: true,
            shutdownCode: null,
            supervision: { clean: true, ownedSession: true, microphoneIsolated: false },
          }),
        }),
        encoding: "utf8",
        timeout: 30000,
      });
      const stdout = result.stdout + result.stderr;
      assert.equal(result.status, 1, "替身数据目录必须被删除守卫拒绝：" + stdout);

      const shutdownAt = stdout.indexOf("请求桥接优雅关闭");
      const cleanAt = stdout.indexOf("关闭结果：clean=true");
      const workerAt = stdout.indexOf("音频工作 进程 " + workerPid + " 已终止");
      const guardAt = stdout.indexOf("数据目录不是预期路径");
      assert.ok(shutdownAt > -1, "必须先请求桥接优雅关闭：" + stdout);
      assert.ok(cleanAt > shutdownAt, "必须读回并打印关闭结果：" + stdout);
      assert.ok(workerAt > cleanAt, "关闭结果读回后才停音频工作进程：" + stdout);
      assert.ok(guardAt > workerAt, "必须走完停止路径才到删除守卫：" + stdout);
      assert.equal(/! shutdown_result/.test(stdout), false, "干净关闭不得打印未确认警告：" + stdout);
      assert.match(stdout, /读回确认麦克风已恢复（未隔离）/, "干净关闭必须声明读回证据：" + stdout);
      assert.equal(processAlive(workerPid), false, "音频工作进程必须被结束");
      assert.deepEqual(readFileSync(harness.ctlLog, "utf8").trim().split(NL), ["shutdown"]);
    } finally {
      stopFakeProcess(workerPid);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

test(
  "uninstall.sh：关闭结果不干净时打印机器可读警告，不宣称通话已终止/交还",
  { timeout: 60000 },
  async () => {
    const harness = uninstallHarness();
    const bridgePid = startFakeProcess(harness.root, "org.krvyft.agentcall.bridge.BridgeMain", "agentcall-bridge");
    const workerPid = startFakeProcess(harness.root, "org.krvyft.agentcall.bridge.audio.AudioWorkerMain", "agentcall-audio-worker");
    try {
      await sleep(100);
      writeFileSync(harness.bridgePidFile, String(bridgePid) + NL);
      writeFileSync(harness.audioPidFile, String(workerPid) + NL);

      const result = spawnSync("sh", [harness.script], {
        env: uninstallEnv(harness, {
          AGENTCALL_FAKE_KEEP_SOCKET: "1",
          AGENTCALL_FAKE_SHUTDOWN_JSON: JSON.stringify({
            stopping: true,
            clean: false,
            shutdownCode: "shutdown_call_unresolved",
            supervision: {
              clean: false,
              ownedSession: true,
              termination: "HOOK_ACCEPTED",
              ownershipResolved: false,
              disconnected: false,
              microphoneIsolated: true,
              code: "shutdown_call_unresolved",
            },
          }),
          AGENTCALL_FAKE_STATUS_JSON: JSON.stringify({
            supervisionPhase: "ENDED",
            microphoneIsolated: true,
            shutdownCode: "shutdown_call_unresolved",
          }),
        }),
        encoding: "utf8",
        timeout: 30000,
      });
      const stdout = result.stdout + result.stderr;
      assert.equal(result.status, 1, "替身数据目录必须被删除守卫拒绝：" + stdout);

      assert.match(
        stdout,
        /! shutdown_result clean=false shutdownCode=shutdown_call_unresolved phase=ENDED microphoneIsolated=true owned=true/,
        "未确认关闭必须打印完整机器可读结果：" + stdout,
      );
      assert.match(stdout, /未确认通话已终止或交还/, "不得谎称通话已终止/交还：" + stdout);
      assert.match(stdout, /麦克风可能仍被隔离/, "必须明确麦克风可能仍被静音：" + stdout);
      assert.equal(/已由桥接确认干净关闭/.test(stdout), false, "未确认关闭不得宣称干净关闭：" + stdout);
      assert.equal(/麦克风恢复读回为未隔离/.test(stdout), false, "未确认关闭不得宣称已恢复：" + stdout);

      const warningAt = stdout.indexOf("! shutdown_result");
      const workerAt = stdout.indexOf("音频工作 进程 " + workerPid + " 已终止");
      const bridgeAt = stdout.indexOf("桥接 进程 " + bridgePid + " 已终止");
      assert.ok(warningAt > -1 && workerAt > warningAt, "警告必须在停止音频工作进程之前打印：" + stdout);
      assert.ok(bridgeAt > workerAt, "桥接的 PID 兜底必须在音频工作进程之后：" + stdout);

      assert.equal(processAlive(workerPid), false, "音频工作进程必须被结束");
      assert.equal(processAlive(bridgePid), false, "桥接 PID 兜底必须被结束");
      const ctlCalls = readFileSync(harness.ctlLog, "utf8").trim().split(NL);
      assert.deepEqual(ctlCalls, ["shutdown", "status"], "必须先 shutdown，再读 status：" + ctlCalls.join(","));
    } finally {
      stopFakeProcess(bridgePid);
      stopFakeProcess(workerPid);
      rmSync(harness.root, { recursive: true, force: true });
    }
  },
);

// ---------------------------------------------------------------------------------------------
// agentcallctl calibrate：stdin 逐字节透传、argv 负载拒绝
// ---------------------------------------------------------------------------------------------

function ctlHarness() {
  const root = mkdtempSync(join(tmpdir(), "agentcall-calibrate-"));
  const moduleDir = join(root, "module");
  const dataRoot = join(root, "data");
  const binDir = join(root, "bin");
  mkdirSync(join(moduleDir, "bin"), { recursive: true });
  mkdirSync(join(moduleDir, "bridge"), { recursive: true });
  mkdirSync(join(dataRoot, "logs"), { recursive: true });
  mkdirSync(binDir, { recursive: true });
  copyFileSync(join(MODULE, "bin", "agentcallctl"), join(moduleDir, "bin", "agentcallctl"));
  writeFileSync(join(moduleDir, "bridge", "agentcall-bridge.jar"), "stub");

  const fakeId = join(binDir, "id");
  writeFileSync(fakeId, "#!/bin/sh" + NL + "echo 0" + NL);
  chmodSync(fakeId, 0o755);

  const argsFile = join(root, "args.txt");
  const stdinFile = join(root, "stdin.bin");
  const appProcess = join(binDir, "app_process");
  writeFileSync(
    appProcess,
    [
      "#!/bin/sh",
      'if [ -n "$AGENTCALL_FAKE_ARGS_FILE" ]; then echo "$*" >> "$AGENTCALL_FAKE_ARGS_FILE"; fi',
      'if [ -n "$AGENTCALL_FAKE_STDIN_FILE" ]; then cat > "$AGENTCALL_FAKE_STDIN_FILE"; fi',
      'if [ -n "$AGENTCALL_FAKE_STDOUT" ]; then echo "$AGENTCALL_FAKE_STDOUT"; fi',
      'exit "$AGENTCALL_FAKE_CLI_EXIT"',
      "",
    ].join(NL),
  );
  chmodSync(appProcess, 0o755);

  return {
    root,
    moduleDir,
    dataRoot,
    binDir,
    argsFile,
    stdinFile,
    appProcess,
    ctl: join(moduleDir, "bin", "agentcallctl"),
    dataRootOut: dataRoot,
  };
}

function ctlEnv(harness, extra = {}) {
  return {
    ...process.env,
    PATH: harness.binDir + ":" + process.env.PATH,
    AGENTCALL_DATA_ROOT: harness.dataRoot,
    AGENTCALL_APP_PROCESS: harness.appProcess,
    AGENTCALL_FAKE_ARGS_FILE: harness.argsFile,
    AGENTCALL_FAKE_STDIN_FILE: harness.stdinFile,
    AGENTCALL_FAKE_CLI_EXIT: "0",
    ...extra,
  };
}

test("agentcallctl calibrate：stdin 逐字节透传给固定 CLI，argv 里没有任何负载", () => {
  const harness = ctlHarness();
  try {
    const payload = Buffer.concat([
      Buffer.from('{"steps":{"downlinkRecognized":true,"peerHeardSpeech":true,"microphoneIsolated":true,"manualRestoreVerified":true,"processFailureVerified":true}}'),
      Buffer.from([10]),
      Buffer.from([0]),
      Buffer.from("tail-bytes"),
    ]);
    const result = spawnSync("sh", [harness.ctl, "calibrate"], {
      input: payload,
      env: ctlEnv(harness, { AGENTCALL_FAKE_STDOUT: '{"recorded":true,"verified":false}' }),
      encoding: "buffer",
      timeout: 20000,
    });
    assert.equal(result.status, 0, "calibrate 必须把 CLI 退出码原样转发：" + result.stderr.toString());
    assert.deepEqual(
      readFileSync(harness.stdinFile),
      payload,
      "stdin 必须逐字节（含 NUL 与尾随字节）原样到达固定 CLI",
    );
    const forwarded = readFileSync(harness.argsFile, "utf8").trim();
    assert.match(forwarded, /AgentCallCli calibrate$/, "calibrate 只作为固定命令转发：" + forwarded);
    assert.equal(/"steps"|expectedEnvironment/.test(forwarded), false, "argv 不得携带校准负载：" + forwarded);
    assert.equal(result.stdout.toString(), '{"recorded":true,"verified":false}' + NL, "输出必须原样转发 CLI JSON");
  } finally {
    rmSync(harness.root, { recursive: true, force: true });
  }
});

test("agentcallctl calibrate：拒绝任何 argv 负载且不启动 CLI", () => {
  const harness = ctlHarness();
  try {
    for (const extra of [['{"steps":{}}'], ['{"steps":{}}', "more"]]) {
      const result = spawnSync("sh", [harness.ctl, "calibrate", ...extra], {
        env: ctlEnv(harness),
        encoding: "utf8",
        timeout: 20000,
      });
      assert.equal(result.status, 2, "argv 负载必须按用法错误拒绝：" + result.stdout);
      const error = JSON.parse(result.stdout.trim());
      assert.equal(error.error.code, "usage_invalid");
      assert.equal(existsSync(harness.argsFile), false, "被拒绝的 argv 负载不得启动固定 CLI");
    }
  } finally {
    rmSync(harness.root, { recursive: true, force: true });
  }
});
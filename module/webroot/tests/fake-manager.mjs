/**
 * 测试用 KernelSU 管理器替身：只模拟管理器文档化注入接口
 * （ksu.exec / ksu.io / ksu.moduleInfo），绝不接触真实 shell 或设备。
 *
 * 只允许测试使用；生产 WebUI 不会加载此文件。
 */
export const MODULE_DIR = "/data/adb/modules/agentcall";
export const CTL = MODULE_DIR + "/bin/agentcallctl";
export const INBOX = "/data/adb/agentcall/inbox/config-set.json";
export const LOG_PATH = "/data/adb/agentcall/logs/service.log";

export function fakeManager(options) {
  const opts = options || {};
  const commands = [];
  const envs = [];
  const envPayloads = [];
  const files = new Map();
  const writes = [];
  const dirs = new Set();
  const perms = [];
  const handlers = opts.handlers || {};
  const ioVersion = opts.ioVersion === undefined ? 1 : opts.ioVersion;
  const logText = opts.logText;
  const failFlush = opts.failFlush === true;
  // undefined = 管理器透传 options.env（默认）；false = 静默忽略 env；"error" = exec 抛错。
  const envSupport = opts.envSupport === undefined ? true : opts.envSupport;
  // 模拟真实管理器对 exec env 的两种已知限制：单个值被截断、某个变量根本没传进来。
  const envValueMaxChars = typeof opts.envValueMaxChars === "number" ? opts.envValueMaxChars : null;
  const envDropKeys = new Set(Array.isArray(opts.envDropKeys) ? opts.envDropKeys : []);

  function deliveredEnv(env) {
    const delivered = {};
    for (const key of Object.keys(env)) {
      if (envDropKeys.has(key)) continue;
      const raw = String(env[key]);
      delivered[key] = envValueMaxChars === null ? raw : raw.slice(0, envValueMaxChars);
    }
    return delivered;
  }

  /** 按前缀 + 数字后缀收集分块环境变量，并按索引顺序拼接。 */
  function reassemble(env, prefix) {
    const pattern = new RegExp("^" + prefix + "(\\d+)$");
    return Object.keys(env)
      .map((key) => {
        const match = pattern.exec(key);
        return match ? { index: Number(match[1]), value: String(env[key]) } : null;
      })
      .filter((entry) => entry !== null)
      .sort((left, right) => left.index - right.index)
      .map((entry) => entry.value)
      .join("");
  }

  function parseOptions(optionsJson) {
    if (!optionsJson) return {};
    if (typeof optionsJson === "object") return optionsJson;
    try {
      const parsed = JSON.parse(optionsJson);
      return parsed && typeof parsed === "object" ? parsed : {};
    } catch (error) {
      return {};
    }
  }

  if (logText !== undefined) files.set(LOG_PATH, logText);

  function permsRecorder(path) {
    return {
      setReadable(readable, ownerOnly) {
        perms.push({ path, kind: "readable", value: readable === true, ownerOnly: ownerOnly === true });
        return true;
      },
      setWritable(writable, ownerOnly) {
        perms.push({ path, kind: "writable", value: writable === true, ownerOnly: ownerOnly === true });
        return true;
      },
      setExecutable(executable, ownerOnly) {
        perms.push({ path, kind: "executable", value: executable === true, ownerOnly: ownerOnly === true });
        return true;
      },
    };
  }

  const io = {
    version: () => ioVersion,
    File(path) {
      return Object.assign(permsRecorder(path), {
        path,
        exists: () => files.has(path) || dirs.has(path),
        isFile: () => files.has(path),
        isDirectory: () => dirs.has(path),
        mkdirs: () => {
          dirs.add(path);
          return true;
        },
        delete: () => files.delete(path),
        isSymlink: () => false,
      });
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
        flush: () => failFlush === false,
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
    RandomAccessFile() {
      const handles = new Map();
      return {
        open(path, mode) {
          if (!files.has(path) || mode !== "r") return null;
          const handle = "raf-" + (handles.size + 1);
          handles.set(handle, { path, offset: 0 });
          return handle;
        },
        length(handle) {
          const entry = handles.get(handle);
          return entry ? Buffer.byteLength(files.get(entry.path), "utf8") : 0;
        },
        seek(handle, offset) {
          const entry = handles.get(handle);
          if (!entry) return false;
          entry.offset = offset;
          return true;
        },
        readBytes(handle, count) {
          const entry = handles.get(handle);
          if (!entry) return "";
          const bytes = Buffer.from(files.get(entry.path), "utf8");
          const slice = bytes.subarray(entry.offset, entry.offset + count);
          return slice.toString("base64");
        },
        close(handle) {
          return handles.delete(handle);
        },
      };
    },
    FileInputStream() {
      const handles = new Map();
      return {
        open(path) {
          if (!files.has(path)) return null;
          const handle = "fis-" + (handles.size + 1);
          handles.set(handle, { path });
          return handle;
        },
        read(handle, count) {
          const entry = handles.get(handle);
          if (!entry) return "";
          const bytes = Buffer.from(files.get(entry.path), "utf8");
          return bytes.subarray(0, count).toString("base64");
        },
        close(handle) {
          return handles.delete(handle);
        },
      };
    },
  };

  if (opts.randomAccess === false) delete io.RandomAccessFile;
  if (opts.fileInputStream === false) delete io.FileInputStream;
  // Task 11M：上游 KernelSU 的 ksu.io 是对象（不是函数），support 是布尔、version 是数字。
  // 这里用 ioForm / ioSupport / ioVersionStyle 覆盖对象形式、缺失版本与缺类等变体。
  if (opts.fileOutputStream === false) delete io.FileOutputStream;
  if (opts.ioSupport !== undefined) io.support = opts.ioSupport;
  const versionStyle = opts.ioVersionStyle || "function";
  if (versionStyle === "missing") delete io.version;
  else if (versionStyle === "number") io.version = ioVersion;
  else if (versionStyle === "string") io.version = String(ioVersion);
  else io.version = () => ioVersion;

  const ksu = {
    exec(command, optionsJson, callbackName) {
      commands.push(command);
      const options = parseOptions(optionsJson);
      const env = options.env && typeof options.env === "object" ? options.env : {};
      envs.push(env);

      if (envSupport === "error") {
        // 管理器拒绝 options 参数：与真实管理器抛错的行为一致。
        throw new Error("manager rejected exec options");
      }

      let result;
      if (command.indexOf("$AGENTCALL_PROBE_") !== -1) {
        // 能力探测：分块回显；忽略 env 或分块缺失时 stdout 与 canary 不等。
        const delivered = envSupport === false ? {} : deliveredEnv(env);
        result = { errno: 0, stdout: reassemble(delivered, "AGENTCALL_PROBE_"), stderr: "" };
      } else if (command.indexOf("$AGENTCALL_CFG_PAYLOAD_") !== -1 && /\sconfig-set$/.test(command)) {
        if (envSupport === false) {
          // 忽略 env：子进程读到空 stdin，CLI 报负载非法；这是“不该发生”的成功。
          result = opts.envIgnoredResult || {
            errno: 1,
            stdout: JSON.stringify({ error: { code: "payload_invalid", message: "empty stdin" } }),
            stderr: "",
          };
        } else {
          // 只按命令实际引用的分块变量（前缀 + 索引）重组 stdin，顺序由索引决定。
          const payload = reassemble(deliveredEnv(env), "AGENTCALL_CFG_PAYLOAD_");
          envPayloads.push(payload);
          // 兼容既有 app 测试：env 负载也记录成一次“写入”。
          writes.push({ path: "(exec-env)", text: payload });
          const handler = handlers["config-set"];
          result = typeof handler === "function" ? handler(command, env) : handler || { errno: 0, stdout: "{}", stderr: "" };
        }
      } else {
        const tokens = command.split(/\s+/);
        const op = tokens[1];
        const handler = handlers[op];
        result = typeof handler === "function" ? handler(command, env) : handler || { errno: 0, stdout: "{}", stderr: "" };
      }

      setTimeout(() => {
        if (typeof globalThis[callbackName] === "function") {
          globalThis[callbackName](result.errno, result.stdout, result.stderr);
        }
      }, 0);
    },
  };

  if (opts.moduleInfo !== false) {
    if (typeof opts.moduleInfo === "function") {
      ksu.moduleInfo = opts.moduleInfo;
    } else if (opts.moduleInfo !== undefined) {
      const fixed = opts.moduleInfo;
      ksu.moduleInfo = () => (typeof fixed === "string" ? fixed : JSON.stringify(fixed));
    } else {
      ksu.moduleInfo = () => JSON.stringify({ id: "agentcall", moduleDir: opts.moduleDir || MODULE_DIR });
    }
  }
  if (opts.io !== false) {
    // 默认保持旧的函数形式；ioForm: "object" 模拟当前上游声明 `declare const io: KsuIO`。
    if (opts.ioForm === "object") ksu.io = io;
    else ksu.io = () => io;
  }

  return { ksu, commands, envs, envPayloads, files, writes, dirs, perms, io };
}

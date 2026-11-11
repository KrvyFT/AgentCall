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
  const files = new Map();
  const writes = [];
  const dirs = new Set();
  const perms = [];
  const handlers = opts.handlers || {};
  const ioVersion = opts.ioVersion === undefined ? 1 : opts.ioVersion;
  const logText = opts.logText;
  const failFlush = opts.failFlush === true;

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

  return { ksu, commands, files, writes, dirs, perms, io };
}

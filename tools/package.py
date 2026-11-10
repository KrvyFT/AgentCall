#!/usr/bin/env python3
"""AgentCall release packaging (Task 9 Part A).

Single version source: the built app APK.  The module manifest written into the
ZIP is rendered from the APK versionName/versionCode, so the module, the ZIP file
name and the APK cannot drift apart; a hand-edited module.prop that disagrees is
a packaging failure, not something to paper over.

The bridge runtime container is the AGP release APK's own dex set, copied entry
by entry into bridge/build/outputs/agentcall/agentcall-bridge.jar by
:bridge:packageRuntime.  Packaging re-verifies that every APK classesN.dex made
it into the container and that the runtime entry points survive with their
stable JVM names.

Signing: the release APK produced by AGP is unsigned, so the local Android debug
keystore ($HOME/.android/debug.keystore) is used.  No private key is ever copied
into the repository or the module ZIP, and the signing type is reported honestly
by tools/check_package.py.
"""

import argparse
import hashlib
import os
import re
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import NamedTuple

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODULE_DIR = REPO_ROOT / "module"
DEFAULT_APK = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
DEFAULT_BRIDGE_JAR = REPO_ROOT / "bridge" / "build" / "outputs" / "agentcall" / "agentcall-bridge.jar"
DEFAULT_DIST_DIR = REPO_ROOT / "dist"
DEFAULT_KEYSTORE = Path.home() / ".android" / "debug.keystore"

# Fixed DOS timestamp for deterministic archives (1980-01-01T00:00:00).
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
ZIP_COMPRESSLEVEL = 9

REQUIRED_MODULE_FILES = (
    "module.prop",
    "customize.sh",
    "service.sh",
    "uninstall.sh",
    "bin/agentcallctl",
    "webroot/index.html",
    "webroot/app.js",
    "webroot/bridge.js",
    "webroot/styles.css",
)
EXECUTABLE_MODULE_FILES = frozenset(
    ("customize.sh", "service.sh", "uninstall.sh", "bin/agentcallctl")
)
FORBIDDEN_KEY_SUFFIXES = frozenset((".keystore", ".jks", ".p12", ".pepk"))
EXCLUDED_SUFFIXES = frozenset((".pyc", ".pyo"))
EXCLUDED_DIRS = ("webroot/tests", "__pycache__")

# Entry points the module starts with app_process and their stable JVM names.
REQUIRED_ENTRY_POINTS = {
    "org/krvyft/agentcall/bridge/BridgeMain": "main",
    "org/krvyft/agentcall/bridge/AgentCallCli": "main",
    "org/krvyft/agentcall/bridge/audio/AudioWorkerMain": "main",
}
# Runtime libraries that must be resolvable inside the container itself.
REQUIRED_RUNTIME_CLASSES = (
    "kotlin/Unit",
    "kotlin/jvm/internal/Intrinsics",
    "kotlinx/coroutines/BuildersKt",
    "com/google/gson/Gson",
    "org/krvyft/agentcall/core/model/RpcRequest",
)

DEX_NAME = re.compile(r"^classes\d*\.dex$")

SECRET_PATTERNS = (
    ("private key", re.compile(rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----")),
    ("ghp_", re.compile(rb"(?<![A-Za-z0-9_])gh[pousr]_[A-Za-z0-9]{20,}")),
    ("sk-", re.compile(rb"(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}")),
    ("AKIA", re.compile(rb"(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![A-Za-z0-9])")),
    ("AIza", re.compile(rb"(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{35}(?![A-Za-z0-9])")),
    ("xox", re.compile(rb"(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{10,}")),
)


class PackagingError(RuntimeError):
    """A packaging precondition failed; no artifact was produced."""


class Version(NamedTuple):
    name: str
    code: int


class EntryInfo(NamedTuple):
    name: str
    size: int
    mode: int


@dataclass
class PackageResult:
    zip_path: Path
    apk_path: Path
    sums_path: Path
    version: Version
    signing: str
    sha256: dict
    entries: list
    module_prop: str


# ---------------------------------------------------------------------------
# DEX container verification (no SDK, dexdump-equivalent structural check)
# ---------------------------------------------------------------------------

DEX_HEADER_SIZE = 0x70


class DexError(ValueError):
    pass


def _read_uleb128(data, position):
    result = 0
    shift = 0
    while True:
        if position >= len(data):
            raise DexError("truncated uleb128")
        byte = data[position]
        position += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, position
        shift += 7
        if shift > 35:
            raise DexError("uleb128 too long")


class DexFile:
    """Minimal DEX reader: class definitions and their method names.

    This is the dexdump/javap-equivalent used by the packaging checks.  It
    validates the header (magic, sizes, SHA-1 signature, Adler-32 checksum) and
    the id tables, then walks class_data to collect method names, so a container
    that merely contains a file named classes.dex cannot pass.
    """

    def __init__(self, name, data):
        self.name = name
        self.data = data
        self.class_methods = {}
        self._parse()

    def _u4(self, offset):
        if offset + 4 > len(self.data):
            raise DexError(self.name + ": truncated header or table")
        return struct.unpack_from("<I", self.data, offset)[0]

    def _parse(self):
        data = self.data
        if len(data) < DEX_HEADER_SIZE:
            raise DexError(self.name + ": file is smaller than a DEX header")
        if data[0:4] != b"dex\n" or data[7] != 0:
            raise DexError(self.name + ": bad DEX magic")
        if self._u4(36) != DEX_HEADER_SIZE:
            raise DexError(self.name + ": unsupported DEX header size")
        if self._u4(32) != len(data):
            raise DexError(
                self.name + ": header file_size " + str(self._u4(32))
                + " != actual " + str(len(data)))
        signature = data[12:32]
        if hashlib.sha1(data[32:]).digest() != signature:
            raise DexError(self.name + ": SHA-1 header signature mismatch")
        if (zlib.adler32(data[12:]) & 0xFFFFFFFF) != self._u4(8):
            raise DexError(self.name + ": Adler-32 checksum mismatch")

        string_ids_size = self._u4(56)
        string_ids_off = self._u4(60)
        type_ids_size = self._u4(64)
        type_ids_off = self._u4(68)
        method_ids_size = self._u4(88)
        method_ids_off = self._u4(92)
        class_defs_size = self._u4(96)
        class_defs_off = self._u4(100)

        tables = (
            ("string_ids", string_ids_size, string_ids_off, 4),
            ("type_ids", type_ids_size, type_ids_off, 4),
            ("method_ids", method_ids_size, method_ids_off, 8),
            ("class_defs", class_defs_size, class_defs_off, 32),
        )
        for label, size, offset, width in tables:
            if size and (offset < DEX_HEADER_SIZE or offset + size * width > len(data)):
                raise DexError(self.name + ": " + label + " table is out of bounds")

        strings = []
        for index in range(string_ids_size):
            offset = self._u4(string_ids_off + 4 * index)
            if offset >= len(data):
                raise DexError(self.name + ": string_data offset out of bounds")
            _, position = _read_uleb128(data, offset)
            end = data.find(b"\x00", position)
            if end < 0:
                raise DexError(self.name + ": unterminated string_data entry")
            strings.append(data[position:end].decode("utf-8", "replace"))

        types = []
        for index in range(type_ids_size):
            descriptor_index = self._u4(type_ids_off + 4 * index)
            if descriptor_index >= len(strings):
                raise DexError(self.name + ": type_id descriptor index out of bounds")
            types.append(strings[descriptor_index])

        method_names = []
        for index in range(method_ids_size):
            name_index = self._u4(method_ids_off + 8 * index + 4)
            if name_index >= len(strings):
                raise DexError(self.name + ": method_id name index out of bounds")
            method_names.append(strings[name_index])

        for index in range(class_defs_size):
            base = class_defs_off + 32 * index
            class_index = self._u4(base)
            if class_index >= len(types):
                raise DexError(self.name + ": class_def type index out of bounds")
            descriptor = types[class_index]
            class_data_off = self._u4(base + 24)
            methods = set()
            if class_data_off:
                methods = self._parse_class_data(class_data_off, method_names)
            self.class_methods.setdefault(descriptor, set()).update(methods)

    def _parse_class_data(self, position, method_names):
        data = self.data
        counts = []
        for _ in range(4):
            value, position = _read_uleb128(data, position)
            counts.append(value)
        for _ in range(counts[0] + counts[1]):
            _, position = _read_uleb128(data, position)
            _, position = _read_uleb128(data, position)
        methods = set()
        # method_idx_diff is relative to the previous method *in the same list*;
        # the first method of a list is relative to NO_INDEX, i.e. the diff is the
        # absolute method index.  Direct and virtual lists are accumulated
        # independently (verified against the AGP release dex).
        for count in (counts[2], counts[3]):
            method_index = 0
            first = True
            for _ in range(count):
                diff, position = _read_uleb128(data, position)
                _, position = _read_uleb128(data, position)
                _, position = _read_uleb128(data, position)
                method_index = diff if first else method_index + diff
                first = False
                if method_index >= len(method_names):
                    raise DexError(self.name + ": class_data method index out of bounds")
                methods.add(method_names[method_index])
        return methods

    def has_class(self, descriptor):
        return descriptor in self.class_methods

    def has_method(self, descriptor, method):
        return method in self.class_methods.get(descriptor, ())


def dex_sort_key(name):
    match = re.match(r"^classes(\d*)\.dex$", name)
    suffix = match.group(1) if match else ""
    return (0, 0) if not suffix else (1, int(suffix))


def _zip_dex_entries(path):
    path = Path(path)
    try:
        with zipfile.ZipFile(path) as archive:
            return {
                info.filename: archive.read(info.filename)
                for info in archive.infolist()
                if DEX_NAME.match(info.filename)
            }
    except (zipfile.BadZipFile, OSError) as exc:
        raise PackagingError("cannot read dex entries from " + str(path) + ": " + str(exc))


def list_dex_entries(path):
    return sorted(_zip_dex_entries(path), key=dex_sort_key)


def load_dex_files(path):
    entries = _zip_dex_entries(path)
    return [DexFile(name, entries[name]) for name in sorted(entries, key=dex_sort_key)]


def verify_container(jar_path):
    """Return problems that make the bridge runtime container unusable."""
    problems = []
    try:
        entries = _zip_dex_entries(jar_path)
    except PackagingError as exc:
        return [str(exc)]
    if not entries:
        return ["container " + str(jar_path) + " has no classes*.dex entries"]
    names = sorted(entries, key=dex_sort_key)
    if "classes.dex" not in entries:
        problems.append("container has no classes.dex (ART only loads classes.dex from a classpath jar)")
    classes = {}
    primary = {}
    for name in names:
        try:
            dex = DexFile(name, entries[name])
        except DexError as exc:
            problems.append("container dex " + name + " is invalid: " + str(exc))
            continue
        if name == "classes.dex":
            primary = dex.class_methods
        for descriptor, methods in dex.class_methods.items():
            classes.setdefault(descriptor, set()).update(methods)

    def require(descriptor, label):
        if descriptor not in classes:
            problems.append(label + " is missing from the container")
            return False
        if len(names) > 1 and descriptor not in primary:
            problems.append(
                label + " resolves only from a non-primary dex entry; app_process only loads "
                "classes.dex from a classpath JAR, so it must be in classes.dex")
            return False
        return True

    for class_name, method in REQUIRED_ENTRY_POINTS.items():
        descriptor = "L" + class_name + ";"
        if not require(descriptor, "entry point class " + class_name):
            continue
        if method not in primary.get(descriptor, ()):
            problems.append(
                "entry point " + class_name + "." + method
                + " is missing from classes.dex (unstable JVM name)")
    for class_name in REQUIRED_RUNTIME_CLASSES:
        require("L" + class_name + ";", "runtime class " + class_name)
    return problems


def verify_dex_coverage(apk_path, jar_path):
    """Every dex entry the release APK contains must reach the container."""
    problems = []
    try:
        apk_dex = list_dex_entries(apk_path)
        jar_dex = list_dex_entries(jar_path)
    except PackagingError as exc:
        return [str(exc)]
    if not apk_dex:
        return ["release APK " + str(apk_path) + " has no classes*.dex entries"]
    for name in apk_dex:
        if name not in jar_dex:
            problems.append("container is missing APK dex entry " + name)
    return problems


# ---------------------------------------------------------------------------
# Secrets and module material
# ---------------------------------------------------------------------------

def scan_for_secrets(entries):
    """Scan (name, bytes) pairs for credential-like material."""
    problems = []
    for name, data in entries:
        if isinstance(data, str):
            data = data.encode("utf-8", "replace")
        for label, pattern in SECRET_PATTERNS:
            match = pattern.search(data)
            if match:
                snippet = match.group(0)[:48].decode("utf-8", "replace")
                problems.append(
                    label + " material found in " + name + " (" + snippet + ")")
    return problems


def parse_module_prop(text):
    fields = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields[key.strip()] = value.strip()
    return fields


def module_version(prop):
    name = prop.get("version")
    code = prop.get("versionCode")
    if not name:
        raise PackagingError("module.prop has no version field")
    if not code or not re.match(r"^\d+$", code):
        raise PackagingError("module.prop versionCode is missing or not an integer")
    return Version(name, int(code))


def render_module_prop(text, version):
    """Render module.prop with version fields taken from the built APK."""
    lines = []
    wrote_version = False
    wrote_code = False
    for line in text.splitlines():
        if line.startswith("version="):
            lines.append("version=" + version.name)
            wrote_version = True
        elif line.startswith("versionCode="):
            lines.append("versionCode=" + str(version.code))
            wrote_code = True
        else:
            lines.append(line)
    if not wrote_version:
        lines.append("version=" + version.name)
    if not wrote_code:
        lines.append("versionCode=" + str(version.code))
    return "\n".join(lines) + "\n"


def _is_excluded(relative):
    for directory in EXCLUDED_DIRS:
        if relative == directory or relative.startswith(directory + "/"):
            return True
    return Path(relative).suffix.lower() in EXCLUDED_SUFFIXES


def collect_module_files(module_dir):
    """Return {relative path: source path}, refusing signing material."""
    module_dir = Path(module_dir)
    if not module_dir.is_dir():
        raise PackagingError("module directory is missing: " + str(module_dir))
    files = {}
    for path in sorted(module_dir.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(module_dir).as_posix()
        if _is_excluded(relative):
            continue
        if path.suffix.lower() in FORBIDDEN_KEY_SUFFIXES:
            raise PackagingError(
                "signing private key material must never be packaged: " + relative)
        files[relative] = path
    missing = [name for name in REQUIRED_MODULE_FILES if name not in files]
    if missing:
        raise PackagingError("required module file is missing: " + ", ".join(missing))
    return files


# ---------------------------------------------------------------------------
# Versions and Android SDK tooling
# ---------------------------------------------------------------------------

def _sdk_dir():
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(variable)
        if value:
            return Path(value)
    properties = REPO_ROOT / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip())
    return None


def _version_key(name):
    parts = []
    for part in re.split(r"[.-]", name):
        parts.append((0, int(part)) if part.isdigit() else (1, part))
    return parts


def find_build_tools(sdk_dir=None):
    sdk = Path(sdk_dir) if sdk_dir else _sdk_dir()
    if not sdk:
        raise PackagingError(
            "Android SDK not found; set ANDROID_HOME or sdk.dir in local.properties")
    build_tools = sdk / "build-tools"
    if not build_tools.is_dir():
        raise PackagingError("Android SDK has no build-tools directory: " + str(build_tools))
    candidates = sorted(
        (path for path in build_tools.iterdir() if path.is_dir()),
        key=lambda path: _version_key(path.name), reverse=True)
    for candidate in candidates:
        if all((candidate / tool).is_file() for tool in ("aapt2", "apksigner", "zipalign")):
            return candidate
    raise PackagingError("no Android build-tools with aapt2/apksigner/zipalign under " + str(build_tools))


def read_apk_version(apk_path, build_tools=None):
    apk_path = Path(apk_path)
    if not apk_path.is_file():
        raise PackagingError("release APK is missing: " + str(apk_path))
    tools = Path(build_tools) if build_tools else find_build_tools()
    aapt2 = tools / "aapt2"
    completed = subprocess.run(
        [str(aapt2), "dump", "badging", str(apk_path)],
        capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise PackagingError(
            "aapt2 could not read " + str(apk_path) + ": " + completed.stderr.strip())
    code_match = re.search(r"versionCode='(\d+)'", completed.stdout)
    name_match = re.search(r"versionName='([^']*)'", completed.stdout)
    if not code_match or not name_match:
        raise PackagingError("release APK has no versionCode/versionName: " + str(apk_path))
    return Version(name_match.group(1), int(code_match.group(1)))


def sign_apk(apk_path, out_path, keystore=None, alias=None, build_tools=None):
    """Sign with the local debug keystore and report the signing type."""
    apk_path = Path(apk_path)
    out_path = Path(out_path)
    keystore_path = Path(keystore) if keystore else DEFAULT_KEYSTORE
    if not keystore_path.is_file():
        raise PackagingError(
            "local debug keystore is missing: " + str(keystore_path)
            + " (build a debug APK once or create one with keytool)")
    tools = Path(build_tools) if build_tools else find_build_tools()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="agentcall-sign-") as workspace:
        aligned = Path(workspace) / "aligned.apk"
        zipalign = subprocess.run(
            [str(tools / "zipalign"), "-p", "-f", "4", str(apk_path), str(aligned)],
            capture_output=True, text=True, check=False)
        if zipalign.returncode != 0:
            raise PackagingError("zipalign failed: " + zipalign.stderr.strip())
        command = [
            str(tools / "apksigner"), "sign",
            "--ks", str(keystore_path),
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
        ]
        if alias:
            command.extend(["--ks-key-alias", alias])
        command.extend(["--out", str(out_path), str(aligned)])
        signed = subprocess.run(command, capture_output=True, text=True, check=False)
        if signed.returncode != 0:
            raise PackagingError("apksigner failed: " + signed.stderr.strip())
    if keystore_path.name == "debug.keystore":
        return "debug keystore (local, not a release key)"
    return "local keystore " + keystore_path.name + " (not a release key)"


# ---------------------------------------------------------------------------
# Packaging
# ---------------------------------------------------------------------------

def _write_zip(entries, out_path):
    """Write (name, data, mode) entries as a deterministic ZIP."""
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
            dir=str(out_path.parent), prefix=".agentcall-zip-", suffix=".tmp",
            delete=False) as handle:
        temporary = Path(handle.name)
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED,
                             compresslevel=ZIP_COMPRESSLEVEL) as archive:
            for name, data, mode in sorted(entries, key=lambda item: item[0]):
                info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
                info.create_system = 3  # UNIX
                info.external_attr = (stat.S_IFREG | mode) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
        temporary.replace(out_path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _entry_mode(relative):
    return 0o755 if relative in EXECUTABLE_MODULE_FILES else 0o644


def _sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write_sha256sums(dist_dir, names):
    lines = []
    for name in sorted(names):
        lines.append(_sha256(dist_dir / name) + "  " + name)
    path = Path(dist_dir) / "SHA256SUMS"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def package_artifact(
        module_dir=None,
        apk_path=None,
        bridge_jar=None,
        dist_dir=None,
        keystore=None,
        signer=None,
        version_reader=None):
    """Assemble the signed APK, the deterministic module ZIP and SHA256SUMS."""
    module_dir = Path(module_dir) if module_dir else DEFAULT_MODULE_DIR
    apk_path = Path(apk_path) if apk_path else DEFAULT_APK
    bridge_jar = Path(bridge_jar) if bridge_jar else DEFAULT_BRIDGE_JAR
    dist_dir = Path(dist_dir) if dist_dir else DEFAULT_DIST_DIR
    reader = version_reader if version_reader else read_apk_version

    if not apk_path.is_file():
        raise PackagingError("release APK is missing: " + str(apk_path))
    if not bridge_jar.is_file():
        raise PackagingError("bridge runtime container is missing: " + str(bridge_jar))

    module_files = collect_module_files(module_dir)
    version = reader(apk_path)
    if not version.name or any(character in version.name for character in "/\\:"):
        raise PackagingError("app versionName cannot be used in an artifact file name: " + repr(version.name))
    prop_text = (Path(module_dir) / "module.prop").read_text(encoding="utf-8")
    source_version = module_version(parse_module_prop(prop_text))
    if source_version != version:
        raise PackagingError(
            "module.prop version " + source_version.name + " (" + str(source_version.code)
            + ") does not match app versionName " + version.name + " (" + str(version.code) + ")")

    problems = verify_dex_coverage(apk_path, bridge_jar)
    problems.extend(verify_container(bridge_jar))
    if problems:
        raise PackagingError("bridge runtime container is not packageable:\n- " + "\n- ".join(problems))

    jar_bytes = bridge_jar.read_bytes()
    scanned = [(relative, path.read_bytes()) for relative, path in module_files.items()]
    scanned.append(("bridge/agentcall-bridge.jar", jar_bytes))
    secret_problems = scan_for_secrets(scanned)
    if secret_problems:
        raise PackagingError("secret material must not be packaged:\n- " + "\n- ".join(secret_problems))

    rendered_prop = render_module_prop(prop_text, version)
    entries = []
    for relative, path in module_files.items():
        data = rendered_prop.encode("utf-8") if relative == "module.prop" else path.read_bytes()
        entries.append((relative, data, _entry_mode(relative)))
    entries.append(("bridge/agentcall-bridge.jar", jar_bytes, 0o644))

    dist_dir.mkdir(parents=True, exist_ok=True)
    apk_out = dist_dir / ("agentcall-" + version.name + ".apk")
    if signer is None:
        signing = sign_apk(apk_path, apk_out, keystore=keystore)
    else:
        signing = signer(apk_path, apk_out)
    if not apk_out.is_file() or apk_out.stat().st_size == 0:
        raise PackagingError("signer produced no APK at " + str(apk_out))

    zip_out = dist_dir / ("agentcall-" + version.name + ".zip")
    _write_zip(entries, zip_out)
    sums_path = _write_sha256sums(dist_dir, (zip_out.name, apk_out.name))

    return PackageResult(
        zip_path=zip_out,
        apk_path=apk_out,
        sums_path=sums_path,
        version=version,
        signing=signing,
        sha256={zip_out.name: _sha256(zip_out), apk_out.name: _sha256(apk_out)},
        entries=[EntryInfo(name, len(data), mode) for name, data, mode in entries],
        module_prop=rendered_prop,
    )


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Package AgentCall: sign the release APK, build the KernelSU module ZIP, write SHA256SUMS.")
    parser.add_argument("--module", type=Path, default=DEFAULT_MODULE_DIR)
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--bridge-jar", type=Path, default=DEFAULT_BRIDGE_JAR)
    parser.add_argument("--dist", type=Path, default=DEFAULT_DIST_DIR)
    parser.add_argument("--keystore", type=Path, default=None,
                        help="local debug keystore (default: $HOME/.android/debug.keystore)")
    args = parser.parse_args(argv)
    try:
        result = package_artifact(
            module_dir=args.module, apk_path=args.apk, bridge_jar=args.bridge_jar,
            dist_dir=args.dist, keystore=args.keystore)
    except PackagingError as exc:
        print("packaging failed: " + str(exc), file=sys.stderr)
        return 1

    print("packaged " + str(result.zip_path) + " (" + str(result.zip_path.stat().st_size)
          + " bytes, " + str(len(result.entries)) + " entries)")
    print("packaged " + str(result.apk_path) + " (" + str(result.apk_path.stat().st_size)
          + " bytes)")
    print("version " + result.version.name + " (" + str(result.version.code)
          + "), signing: " + result.signing)
    for line in result.sums_path.read_text(encoding="utf-8").splitlines():
        print("sha256 " + line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
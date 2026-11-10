"""Packaging and package-checker tests (Task 9 Part A).

These tests are hermetic: they build synthetic module trees, APK/JAR fixtures and
structurally valid synthetic DEX files, and never invoke Gradle, the Android SDK
or the network.  The synthetic DEX containers are parseable by the production DEX
reader but are not loadable by ART; real-container verification is exercised by
make build/package-check against the AGP release artifacts.
"""

import hashlib
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import check_package  # noqa: E402
import package  # noqa: E402


def _uleb128(value):
    out = bytearray()
    while True:
        low = value & 0x7F
        value >>= 7
        if value:
            out.append(low | 0x80)
        else:
            out.append(low)
            return bytes(out)


def minimal_dex(class_names, method_name="main"):
    """Build a syntactically valid DEX holding one direct method per class."""
    descriptors = ["L" + name + ";" for name in class_names]
    types = descriptors + ["V"]
    strings = types + [method_name]
    assert len(set(strings)) == len(strings)

    string_ids_off = 0x70
    type_ids_off = string_ids_off + 4 * len(strings)
    proto_ids_off = type_ids_off + 4 * len(types)
    method_ids_off = proto_ids_off + 12
    class_defs_off = method_ids_off + 8 * len(descriptors)
    data_off = class_defs_off + 32 * len(descriptors)

    data = bytearray()
    string_offsets = []
    for text in strings:
        string_offsets.append(data_off + len(data))
        data += _uleb128(len(text)) + text.encode("ascii") + b"\x00"

    class_data_offsets = []
    for index in range(len(descriptors)):
        class_data_offsets.append(data_off + len(data))
        data += _uleb128(0) + _uleb128(0) + _uleb128(1) + _uleb128(0)
        data += _uleb128(index) + _uleb128(0x0009) + _uleb128(0)

    total = data_off + len(data)
    buf = bytearray(total)
    buf[0:8] = b"dex\n035\x00"
    struct.pack_into("<I", buf, 32, total)
    struct.pack_into("<I", buf, 36, 0x70)
    struct.pack_into("<I", buf, 40, 0x12345678)
    struct.pack_into("<I", buf, 44, 0)
    struct.pack_into("<I", buf, 48, 0)
    struct.pack_into("<I", buf, 52, 0)
    struct.pack_into("<I", buf, 56, len(strings))
    struct.pack_into("<I", buf, 60, string_ids_off)
    struct.pack_into("<I", buf, 64, len(types))
    struct.pack_into("<I", buf, 68, type_ids_off)
    struct.pack_into("<I", buf, 72, 1)
    struct.pack_into("<I", buf, 76, proto_ids_off)
    struct.pack_into("<I", buf, 80, 0)
    struct.pack_into("<I", buf, 84, 0)
    struct.pack_into("<I", buf, 88, len(descriptors))
    struct.pack_into("<I", buf, 92, method_ids_off)
    struct.pack_into("<I", buf, 96, len(descriptors))
    struct.pack_into("<I", buf, 100, class_defs_off)
    struct.pack_into("<I", buf, 104, len(data))
    struct.pack_into("<I", buf, 108, data_off)

    for index, offset in enumerate(string_offsets):
        struct.pack_into("<I", buf, string_ids_off + 4 * index, offset)
    for index, descriptor in enumerate(descriptors):
        struct.pack_into("<I", buf, type_ids_off + 4 * index, strings.index(descriptor))
    struct.pack_into("<I", buf, type_ids_off + 4 * len(descriptors), strings.index("V"))
    proto = proto_ids_off
    struct.pack_into("<I", buf, proto, strings.index("V"))
    struct.pack_into("<I", buf, proto + 4, len(descriptors))
    struct.pack_into("<I", buf, proto + 8, 0)
    for index in range(len(descriptors)):
        struct.pack_into("<H", buf, method_ids_off + 8 * index, index)
        struct.pack_into("<H", buf, method_ids_off + 8 * index + 2, 0)
        struct.pack_into("<I", buf, method_ids_off + 8 * index + 4, strings.index(method_name))
    for index in range(len(descriptors)):
        base = class_defs_off + 32 * index
        struct.pack_into("<I", buf, base, index)
        struct.pack_into("<I", buf, base + 4, 0x0001)
        struct.pack_into("<I", buf, base + 8, 0xFFFFFFFF)
        struct.pack_into("<I", buf, base + 12, 0)
        struct.pack_into("<I", buf, base + 16, 0xFFFFFFFF)
        struct.pack_into("<I", buf, base + 20, 0)
        struct.pack_into("<I", buf, base + 24, class_data_offsets[index])
        struct.pack_into("<I", buf, base + 28, 0)
    buf[data_off:data_off + len(data)] = data

    buf[12:32] = hashlib.sha1(bytes(buf[32:])).digest()
    struct.pack_into("<I", buf, 8, zlib.adler32(bytes(buf[12:])) & 0xFFFFFFFF)
    return bytes(buf)


ENTRY_POINTS = {
    "org/krvyft/agentcall/bridge/BridgeMain": "main",
    "org/krvyft/agentcall/bridge/AgentCallCli": "main",
    "org/krvyft/agentcall/bridge/audio/AudioWorkerMain": "main",
}
RUNTIME_CLASSES = [
    "kotlin/Unit",
    "kotlin/jvm/internal/Intrinsics",
    "kotlinx/coroutines/BuildersKt",
    "com/google/gson/Gson",
    "org/krvyft/agentcall/core/model/RpcRequest",
]


def all_classes():
    return list(ENTRY_POINTS) + RUNTIME_CLASSES


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)


def write_apk(path, dex_names=("classes.dex",), classes=None):
    classes = classes if classes is not None else all_classes()
    write_zip(path, {
        "AndroidManifest.xml": b"\x03\x00~x08\x00manifest-fixture",
        "resources.arsc": b"\x02\x00\x0c\x00arsc-fixture",
        **{name: minimal_dex(classes) for name in dex_names},
    })


def write_container(path, dex_layout):
    write_zip(path, {name: minimal_dex(classes) for name, classes in dex_layout.items()})


def write_module(root, version="1.0", version_code=1, extra=None):
    files = {
        "module.prop": (
            "id=agentcall\n"
            "name=AgentCall\n"
            f"version={version}\n"
            f"versionCode={version_code}\n"
            "author=krvyft\n"
            "description=fixture\n"
        ),
        "customize.sh": "#!/system/bin/sh\n",
        "service.sh": "#!/system/bin/sh\n",
        "uninstall.sh": "#!/system/bin/sh\n",
        "bin/agentcallctl": "#!/system/bin/sh\n",
        "webroot/index.html": "<!doctype html><title>fixture</title>\n",
        "webroot/app.js": "// fixture\n",
        "webroot/bridge.js": "// fixture\n",
        "webroot/styles.css": "body{}\n",
    }
    files.update(extra or {})
    for relative, text in files.items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    for relative in ("customize.sh", "service.sh", "uninstall.sh", "bin/agentcallctl"):
        (root / relative).chmod(0o755)
    (root / "bridge").mkdir(exist_ok=True)


class PackagingFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="agentcall-pkg-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.module = self.tmp / "module"
        self.module.mkdir()
        write_module(self.module)
        self.apk = self.tmp / "app-release-unsigned.apk"
        write_apk(self.apk)
        self.jar = self.tmp / "agentcall-bridge.jar"
        write_container(self.jar, {"classes.dex": all_classes()})
        self.dist = self.tmp / "dist"
        def signer(source, target):
            shutil.copyfile(source, target)
            return "test-signer"

        self.signer = signer
        self.version = package.Version("1.0", 1)

    def package(self, **overrides):
        kwargs = dict(
            module_dir=self.module,
            apk_path=self.apk,
            bridge_jar=self.jar,
            dist_dir=self.dist,
            signer=self.signer,
            version_reader=lambda path: self.version,
        )
        kwargs.update(overrides)
        return package.package_artifact(**kwargs)

    def assert_packaging_fails(self, fragment, **overrides):
        with self.assertRaises(package.PackagingError) as raised:
            self.package(**overrides)
        self.assertIn(fragment, str(raised.exception))
        self.assertFalse((self.dist / ("agentcall-%s.zip" % self.version.name)).exists())

    def inspect(self, **overrides):
        kwargs = dict(
            version_reader=lambda path: self.version,
            signing_probe=lambda path: "debug (CN=Android Debug)",
        )
        kwargs.update(overrides)
        return check_package.inspect_package(
            self.dist / "agentcall-1.0.zip", self.dist / "agentcall-1.0.apk", **kwargs)


class PackagingTests(PackagingFixture):
    def test_successful_package_layout_versions_and_digests(self):
        result = self.package()

        zip_path = self.dist / "agentcall-1.0.zip"
        apk_path = self.dist / "agentcall-1.0.apk"
        sums_path = self.dist / "SHA256SUMS"
        self.assertEqual(result.zip_path, zip_path)
        self.assertEqual(result.apk_path, apk_path)
        self.assertTrue(zip_path.is_file() and apk_path.is_file() and sums_path.is_file())
        self.assertEqual(result.version, package.Version("1.0", 1))
        self.assertEqual(result.signing, "test-signer")

        with zipfile.ZipFile(zip_path) as archive:
            names = archive.namelist()
            self.assertEqual(names, sorted(names), "ZIP entries must be ordered")
            self.assertIn("bridge/agentcall-bridge.jar", names)
            self.assertIn("bin/agentcallctl", names)
            self.assertIn("webroot/index.html", names)
            props = archive.read("module.prop").decode("utf-8")
            self.assertIn("version=1.0", props)
            self.assertIn("versionCode=1", props)
            self.assertEqual(archive.read("bridge/agentcall-bridge.jar"), self.jar.read_bytes())

        sums = sums_path.read_text(encoding="utf-8").splitlines()
        expected = {
            name: hashlib.sha256((self.dist / name).read_bytes()).hexdigest()
            for name in ("agentcall-1.0.apk", "agentcall-1.0.zip")
        }
        self.assertEqual(len(sums), 2)
        for line in sums:
            digest, name = line.split("  ")
            self.assertEqual(expected[name], digest)
        self.assertEqual(result.sha256["agentcall-1.0.zip"], expected["agentcall-1.0.zip"])

    def test_module_scripts_are_executable_and_data_files_are_not(self):
        result = self.package()
        modes = {entry.name: entry.mode for entry in result.entries}
        for script in ("customize.sh", "service.sh", "uninstall.sh", "bin/agentcallctl"):
            self.assertEqual(modes[script], 0o755, script)
        for data in ("module.prop", "webroot/index.html", "bridge/agentcall-bridge.jar"):
            self.assertEqual(modes[data], 0o644, data)

    def test_packaging_is_byte_deterministic(self):
        first = self.package()
        first_bytes = first.zip_path.read_bytes()
        first_apk = first.apk_path.read_bytes()
        second_dir = self.tmp / "dist2"
        self.package(dist_dir=second_dir)
        self.assertEqual(first_bytes, (second_dir / "agentcall-1.0.zip").read_bytes())
        self.assertEqual(first_apk, (second_dir / "agentcall-1.0.apk").read_bytes())

    def test_module_tests_and_keystores_are_not_packaged(self):
        (self.module / "webroot" / "tests").mkdir()
        (self.module / "webroot" / "tests" / "fixture.test.mjs").write_text(
            'const SECRET = "sk-live-DO-NOT-LEAK-9f8e7d6c";\n', encoding="utf-8")
        self.package()
        with zipfile.ZipFile(self.dist / "agentcall-1.0.zip") as archive:
            self.assertFalse(any(name.startswith("webroot/tests/") for name in archive.namelist()))

    def test_missing_dex_entry_fails(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("README.txt", "no dex here")
        self.assert_packaging_fails("classes")

    def test_missing_cli_wrapper_fails(self):
        (self.module / "bin" / "agentcallctl").unlink()
        self.assert_packaging_fails("bin/agentcallctl")

    def test_missing_cli_class_fails(self):
        write_container(self.jar, {"classes.dex": [
            name for name in all_classes() if not name.endswith("AgentCallCli")]})
        self.assert_packaging_fails("AgentCallCli")

    def test_missing_webroot_fails(self):
        (self.module / "webroot" / "index.html").unlink()
        self.assert_packaging_fails("webroot/index.html")

    def test_version_mismatch_fails(self):
        write_module(self.module, version="2.0", version_code=2)
        self.assert_packaging_fails("does not match")

    def test_leaked_private_key_fails(self):
        (self.module / "webroot" / "leaked.pem").write_text(
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----\n",
            encoding="utf-8")
        self.assert_packaging_fails("private key")

    def test_leaked_github_token_fails(self):
        (self.module / "webroot" / "leak.js").write_text(
            "const token = 'ghp_" + "a" * 36 + "';\n", encoding="utf-8")
        self.assert_packaging_fails("ghp_")

    def test_leaked_keystore_file_fails(self):
        (self.module / "webroot" / "signing.jks").write_bytes(b"not a real keystore")
        self.assert_packaging_fails("signing.jks")

    def test_missing_secondary_dex_fails(self):
        write_apk(self.apk, dex_names=("classes.dex", "classes2.dex"))
        write_container(self.jar, {"classes.dex": all_classes()})
        self.assert_packaging_fails("classes2.dex")

    def test_required_class_only_in_secondary_dex_fails(self):
        write_apk(self.apk, dex_names=("classes.dex", "classes2.dex"))
        classes = all_classes()
        write_container(self.jar, {
            "classes.dex": [name for name in classes if name != "kotlin/Unit"],
            "classes2.dex": ["kotlin/Unit"],
        })
        self.assert_packaging_fails("classes.dex")

    def test_missing_kotlin_runtime_fails(self):
        classes = [name for name in all_classes() if name != "kotlin/Unit"]
        write_container(self.jar, {"classes.dex": classes})
        self.assert_packaging_fails("kotlin/Unit")

    def test_unsigned_apk_is_reported_by_checker(self):
        self.package()
        inspection = self.inspect(signing_probe=lambda path: "unsigned")
        self.assertFalse(inspection.ok)
        self.assertTrue(any("sign" in problem for problem in inspection.problems))


class CheckPackageTests(PackagingFixture):
    def test_valid_package_passes(self):
        self.package()
        inspection = self.inspect()
        self.assertTrue(inspection.ok, inspection.problems)
        self.assertEqual(inspection.facts["version"], "1.0")
        self.assertEqual(inspection.facts["version_code"], 1)
        self.assertIn("debug", inspection.facts["signing"])
        self.assertEqual(inspection.facts["sha256"]["agentcall-1.0.zip"],
                         hashlib.sha256((self.dist / "agentcall-1.0.zip").read_bytes()).hexdigest())

    def test_version_mismatch_detected(self):
        self.package()
        inspection = self.inspect(version_reader=lambda path: package.Version("9.9", 9))
        self.assertFalse(inspection.ok)
        self.assertTrue(any("version" in problem for problem in inspection.problems))

    def test_tampered_zip_detected(self):
        self.package()
        (self.dist / "agentcall-1.0.zip").write_bytes(
            (self.dist / "agentcall-1.0.zip").read_bytes() + b"tampered")
        inspection = self.inspect()
        self.assertFalse(inspection.ok)
        self.assertTrue(any("SHA256SUMS" in problem or "sha256" in problem
                            for problem in inspection.problems))

    def test_missing_sums_detected(self):
        self.package()
        (self.dist / "SHA256SUMS").unlink()
        inspection = self.inspect()
        self.assertFalse(inspection.ok)
        self.assertTrue(any("SHA256SUMS" in problem for problem in inspection.problems))

    def test_missing_webroot_in_zip_detected(self):
        self.package()
        rebuilt = self.tmp / "rebuilt.zip"
        with zipfile.ZipFile(self.dist / "agentcall-1.0.zip") as source, \
                zipfile.ZipFile(rebuilt, "w") as target:
            for info in source.infolist():
                if info.filename == "webroot/app.js":
                    continue
                target.writestr(info, source.read(info.filename))
        inspection = check_package.inspect_package(
            rebuilt, self.dist / "agentcall-1.0.apk", sha_sums_path=None,
            version_reader=lambda path: self.version,
            signing_probe=lambda path: "debug (CN=Android Debug)")
        self.assertFalse(inspection.ok)
        self.assertTrue(any("webroot/app.js" in problem for problem in inspection.problems))

    def test_leaked_secret_in_zip_detected(self):
        self.package()
        rebuilt = self.tmp / "rebuilt.zip"
        with zipfile.ZipFile(self.dist / "agentcall-1.0.zip") as source, \
                zipfile.ZipFile(rebuilt, "w") as target:
            for info in source.infolist():
                target.writestr(info, source.read(info.filename))
            target.writestr("webroot/leak.txt", "sk-" + "a" * 32)
        inspection = check_package.inspect_package(
            rebuilt, self.dist / "agentcall-1.0.apk", sha_sums_path=None,
            version_reader=lambda path: self.version,
            signing_probe=lambda path: "debug (CN=Android Debug)")
        self.assertFalse(inspection.ok)
        self.assertTrue(any("sk-" in problem for problem in inspection.problems))


if __name__ == "__main__":
    unittest.main()

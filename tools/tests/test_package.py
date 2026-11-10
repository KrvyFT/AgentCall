"""Packaging and package-checker tests (Task 9 Part A).

These tests are hermetic: they build synthetic module trees, APK/JAR fixtures and
structurally valid synthetic DEX files, and never invoke Gradle, the Android SDK
or the network.  The synthetic DEX containers are parseable by the production DEX
reader but are not loadable by ART; real-container verification is exercised by
make build/package-check against the AGP release artifacts.

DEX coverage is anchored on the container manifest emitted by
:bridge:packageRuntime, never on the app APK.  The fixtures therefore build a
*bridge* APK whose dex set is the manifest source, and keep the app APK's dex
layout independent of it.
"""

import contextlib
import hashlib
import json
import io
import os
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

APPLICATION_ID = "org.krvyft.agentcall"


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


def write_bridge_apk(path, dex_layout):
    """A *bridge* release APK fixture (what :bridge:packageRuntime consumes)."""
    write_zip(path, {
        "AndroidManifest.xml": b"\x03\x00~x08\x00bridge-manifest-fixture",
        "resources.arsc": b"\x02\x00\x0c\x00arsc-fixture",
        **{name: minimal_dex(classes) for name, classes in dex_layout.items()},
    })


def write_container(path, dex_layout):
    write_zip(path, {name: minimal_dex(classes) for name, classes in dex_layout.items()})


def write_manifest(container, manifest, source_apk=None, version=None):
    return package.write_dex_manifest(
        container, manifest,
        version=version if version is not None else package.Version("1.0", 1),
        source_apk_path=source_apk)


def write_container_with_manifest(container, manifest, dex_layout, source_apk=None,
                                  version=None):
    write_container(container, dex_layout)
    return write_manifest(container, manifest, source_apk=source_apk, version=version)


def rebuild_zip(source, target, drop=(), replace=None, add=None):
    """Copy a ZIP, dropping/replacing/adding entries (keeps entry metadata)."""
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
        for info in src.infolist():
            name = info.filename
            if name in drop:
                continue
            payload = (replace or {}).get(name, src.read(name))
            out.writestr(info, payload)
        for name, data in (add or {}).items():
            out.writestr(name, data)
    return target


def rebuild_jar_bytes(data, drop=(), replace=None):
    """Copy JAR bytes, dropping/replacing entries."""
    source = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    with source, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            if info.filename in drop:
                continue
            payload = (replace or {}).get(info.filename, source.read(info.filename))
            target.writestr(info, payload)
    return out.getvalue()


def read_zip_entry(path, name):
    with zipfile.ZipFile(path) as archive:
        return archive.read(name)


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
        # The app APK is deliberately independent of the DEX container: nothing
        # in packaging may derive coverage from it.
        self.apk = self.tmp / "app-release-unsigned.apk"
        write_apk(self.apk)
        self.bridge_apk = self.tmp / "bridge-release-unsigned.apk"
        write_bridge_apk(self.bridge_apk, {"classes.dex": all_classes()})
        self.jar = self.tmp / "agentcall-bridge.jar"
        write_container(self.jar, {"classes.dex": all_classes()})
        self.version = package.Version("1.0", 1)
        self.manifest = self.tmp / "agentcall-bridge.dex-manifest.json"
        write_manifest(self.jar, self.manifest, source_apk=self.bridge_apk, version=self.version)
        self.dist = self.tmp / "dist"

        def signer(source, target):
            shutil.copyfile(source, target)
            return "test-signer"

        self.signer = signer

    def package(self, **overrides):
        kwargs = dict(
            module_dir=self.module,
            apk_path=self.apk,
            bridge_jar=self.jar,
            bridge_manifest=self.manifest,
            bridge_apk=self.bridge_apk,
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
            package_reader=lambda path: APPLICATION_ID,
        )
        kwargs.update(overrides)
        return check_package.inspect_package(
            self.dist / "agentcall-1.0.zip", self.dist / "agentcall-1.0.apk", **kwargs)

    def inspect_rebuilt(self, zip_path, **overrides):
        kwargs = dict(
            version_reader=lambda path: self.version,
            signing_probe=lambda path: "debug (CN=Android Debug)",
            package_reader=lambda path: APPLICATION_ID,
        )
        kwargs.update(overrides)
        return check_package.inspect_package(
            zip_path, self.dist / "agentcall-1.0.apk", **kwargs)


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
            self.assertIn(package.DEX_MANIFEST_ENTRY, names)
            self.assertIn("bin/agentcallctl", names)
            self.assertIn("webroot/index.html", names)
            props = archive.read("module.prop").decode("utf-8")
            self.assertIn("version=1.0", props)
            self.assertIn("versionCode=1", props)
            self.assertEqual(archive.read("bridge/agentcall-bridge.jar"), self.jar.read_bytes())
            self.assertEqual(
                archive.read(package.DEX_MANIFEST_ENTRY), self.manifest.read_bytes())

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

    def test_artifacts_are_world_readable_even_under_a_restrictive_umask(self):
        # umask 077 (a hardened shell) must not leak into release artifact modes.
        previous = os.umask(0o077)
        try:
            self.package()
        finally:
            os.umask(previous)
        self.assertEqual((self.dist / "agentcall-1.0.zip").stat().st_mode & 0o777, 0o644)
        self.assertEqual((self.dist / "agentcall-1.0.apk").stat().st_mode & 0o777, 0o644)
        self.assertEqual((self.dist / "SHA256SUMS").stat().st_mode & 0o777, 0o644)

    def test_module_scripts_are_executable_and_data_files_are_not(self):
        result = self.package()
        modes = {entry.name: entry.mode for entry in result.entries}
        for script in ("customize.sh", "service.sh", "uninstall.sh", "bin/agentcallctl"):
            self.assertEqual(modes[script], 0o755, script)
        for data in ("module.prop", "webroot/index.html", "bridge/agentcall-bridge.jar",
                     package.DEX_MANIFEST_ENTRY):
            self.assertEqual(modes[data], 0o644, data)

    def test_packaging_is_byte_deterministic(self):
        first = self.package()
        first_bytes = first.zip_path.read_bytes()
        first_apk = first.apk_path.read_bytes()
        second_dir = self.tmp / "dist2"
        self.package(dist_dir=second_dir)
        self.assertEqual(first_bytes, (second_dir / "agentcall-1.0.zip").read_bytes())
        self.assertEqual(first_apk, (second_dir / "agentcall-1.0.apk").read_bytes())

    def test_webroot_tests_are_not_packaged(self):
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
        write_manifest(self.jar, self.manifest)
        self.assert_packaging_fails("AgentCallCli")

    def test_missing_webroot_fails(self):
        (self.module / "webroot" / "index.html").unlink()
        self.assert_packaging_fails("webroot/index.html")

    def test_version_mismatch_fails(self):
        write_module(self.module, version="2.0", version_code=2)
        self.assert_packaging_fails("does not match")

    # --- the bridge-reported version is the same single source (Task 9B) ---

    def test_bridge_version_mismatch_fails(self):
        write_manifest(self.jar, self.manifest, source_apk=self.bridge_apk,
                       version=package.Version("0.9", 9))
        self.assert_packaging_fails("bridge-reported version")

    def test_manifest_without_bridge_version_fails(self):
        manifest = package.read_dex_manifest(self.manifest)
        manifest.pop("bridgeVersion")
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")
        self.assert_packaging_fails("bridgeVersion")

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

    # --- DEX coverage is anchored on the container manifest (Critical 1) ---

    def test_packaging_requires_the_container_manifest(self):
        self.manifest.unlink()
        self.assert_packaging_fails("dex manifest is missing")

    def test_container_missing_manifest_dex_fails(self):
        # The manifest advertises classes2.dex but the container lost it.
        other = self.tmp / "full.jar"
        write_container(other, {
            "classes.dex": all_classes(),
            "classes2.dex": ["fixture/Extra"],
        })
        write_manifest(other, self.manifest)
        write_container(self.jar, {"classes.dex": all_classes()})
        self.assert_packaging_fails("classes2.dex")

    def test_container_extra_dex_not_declared_by_manifest_fails(self):
        # A container dex the manifest never attested must fail closed.
        single = self.tmp / "single.jar"
        write_container(single, {"classes.dex": all_classes()})
        write_manifest(single, self.manifest)
        write_container(self.jar, {
            "classes.dex": all_classes(),
            "classes2.dex": ["fixture/Extra"],
        })
        self.assert_packaging_fails("not declared")

    def test_manifest_hash_mismatch_fails(self):
        write_container(self.jar, {"classes.dex": all_classes()})
        write_manifest(self.jar, self.manifest)
        # Same entry names, different bytes: name-only coverage is not enough.
        write_container(self.jar, {"classes.dex": all_classes() + ["fixture/Marker"]})
        self.assert_packaging_fails("does not match")

    def test_coverage_uses_container_manifest_not_app_apk(self):
        # The app APK is multidex while the bridge container/manifest are single
        # dex.  Passing the app APK to the coverage check (the old behaviour)
        # fails this test; the container is the only PACKAGED dex source.
        write_apk(self.apk, dex_names=("classes.dex", "classes2.dex"))
        result = self.package()
        self.assertTrue(result.zip_path.is_file())
        inspection = self.inspect()
        self.assertTrue(inspection.ok, inspection.problems)

    def test_bridge_apk_with_extra_dex_not_in_manifest_fails(self):
        write_bridge_apk(self.bridge_apk, {
            "classes.dex": all_classes(),
            "classes2.dex": ["fixture/Extra"],
        })
        self.assert_packaging_fails("classes2.dex")

    def test_bridge_apk_dex_hash_mismatch_fails(self):
        write_bridge_apk(self.bridge_apk, {
            "classes.dex": all_classes() + ["fixture/Marker"],
        })
        self.assert_packaging_fails("sha256")

    def test_bridge_apk_source_hash_mismatch_fails(self):
        # The manifest records which APK produced it; swapping in another APK
        # with identical dex bytes must still fail.
        write_manifest(self.jar, self.manifest, source_apk=self.apk)
        self.assert_packaging_fails("source APK sha256")

    def test_manifest_with_bad_schema_fails(self):
        self.manifest.write_text('{"schema": 99, "dex": []}\n', encoding="utf-8")
        self.assert_packaging_fails("unsupported schema")

    def test_manifest_with_bad_json_fails(self):
        self.manifest.write_text("not json\n", encoding="utf-8")
        self.assert_packaging_fails("not JSON")

    def test_required_class_only_in_secondary_dex_fails(self):
        classes = all_classes()
        write_container_with_manifest(self.jar, self.manifest, {
            "classes.dex": [name for name in classes if name != "kotlin/Unit"],
            "classes2.dex": ["kotlin/Unit"],
        })
        self.assert_packaging_fails("classes.dex")

    def test_missing_kotlin_runtime_fails(self):
        classes = [name for name in all_classes() if name != "kotlin/Unit"]
        write_container_with_manifest(self.jar, self.manifest, {"classes.dex": classes})
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
        self.assertEqual(inspection.facts["application_id"], APPLICATION_ID)
        self.assertIn("debug", inspection.facts["signing"])
        self.assertEqual(inspection.facts["sha256"]["agentcall-1.0.zip"],
                         hashlib.sha256((self.dist / "agentcall-1.0.zip").read_bytes()).hexdigest())

    def test_version_mismatch_detected(self):
        self.package()
        inspection = self.inspect(version_reader=lambda path: package.Version("9.9", 9))
        self.assertFalse(inspection.ok)
        self.assertTrue(any("version" in problem for problem in inspection.problems))

    def test_checker_detects_bridge_version_mismatch_in_the_embedded_manifest(self):
        self.package()
        embedded = json.loads(read_zip_entry(
            self.dist / "agentcall-1.0.zip", package.DEX_MANIFEST_ENTRY).decode("utf-8"))
        embedded["bridgeVersion"] = {"name": "0.9", "code": 9}
        rebuilt = rebuild_zip(
            self.dist / "agentcall-1.0.zip", self.tmp / "rebuilt.zip",
            replace={package.DEX_MANIFEST_ENTRY:
                     json.dumps(embedded).encode("utf-8")})
        inspection = self.inspect_rebuilt(rebuilt, sha_sums_path=None)
        self.assertFalse(inspection.ok)
        self.assertTrue(any("bridge-reported version" in problem
                            for problem in inspection.problems))

    def test_version_read_failure_is_reported_without_traceback(self):
        self.package()

        def broken_reader(path):
            raise package.PackagingError("aapt2 could not read the APK")

        inspection = self.inspect(version_reader=broken_reader)
        self.assertFalse(inspection.ok)
        self.assertTrue(any("cannot read the packaged APK version" in problem
                            for problem in inspection.problems))
        self.assertNotIn("version", inspection.facts)

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
        rebuilt = rebuild_zip(self.dist / "agentcall-1.0.zip", self.tmp / "rebuilt.zip",
                              drop=("webroot/app.js",))
        inspection = self.inspect_rebuilt(rebuilt, sha_sums_path=None)
        self.assertFalse(inspection.ok)
        self.assertTrue(any("webroot/app.js" in problem for problem in inspection.problems))

    def test_leaked_secret_in_zip_detected(self):
        self.package()
        rebuilt = rebuild_zip(self.dist / "agentcall-1.0.zip", self.tmp / "rebuilt.zip",
                              add={"webroot/leak.txt": "sk-" + "a" * 32})
        inspection = self.inspect_rebuilt(rebuilt, sha_sums_path=None)
        self.assertFalse(inspection.ok)
        self.assertTrue(any("sk-" in problem for problem in inspection.problems))

    def test_unverified_signing_is_a_problem(self):
        self.package()
        inspection = self.inspect(
            signing_probe=lambda path: check_package.SIGNING_UNVERIFIED)
        self.assertFalse(inspection.ok)
        self.assertTrue(any("could not be verified" in problem
                            for problem in inspection.problems))

    def test_unverified_signing_can_be_accepted_explicitly(self):
        self.package()
        inspection = self.inspect(
            signing_probe=lambda path: check_package.SIGNING_UNVERIFIED,
            allow_unverified_signing=True)
        self.assertTrue(inspection.ok, inspection.problems)
        self.assertIn("signing_warning", inspection.facts)

    def test_unsigned_signing_is_a_problem(self):
        self.package()
        inspection = self.inspect(
            signing_probe=lambda path: "unsigned (apksigner verify failed)")
        self.assertFalse(inspection.ok)
        self.assertTrue(any("unsigned" in problem for problem in inspection.problems))

    def test_expected_signer_is_enforced(self):
        self.package()
        inspection = self.inspect(expected_signer="CN=Release Key")
        self.assertFalse(inspection.ok)
        self.assertTrue(any("expected signer" in problem for problem in inspection.problems))

    def test_wrong_application_id_detected(self):
        self.package()
        inspection = self.inspect(package_reader=lambda path: "com.example.other")
        self.assertFalse(inspection.ok)
        self.assertTrue(any("applicationId" in problem for problem in inspection.problems))

    def test_missing_manifest_entry_detected(self):
        self.package()
        rebuilt = rebuild_zip(self.dist / "agentcall-1.0.zip", self.tmp / "rebuilt.zip",
                              drop=(package.DEX_MANIFEST_ENTRY,))
        inspection = self.inspect_rebuilt(rebuilt, sha_sums_path=None)
        self.assertFalse(inspection.ok)
        self.assertTrue(any(package.DEX_MANIFEST_ENTRY in problem
                            for problem in inspection.problems))

    def test_checker_rejects_container_missing_manifest_dex(self):
        layout = {"classes.dex": all_classes(), "classes2.dex": ["fixture/Extra"]}
        write_bridge_apk(self.bridge_apk, layout)
        write_container_with_manifest(self.jar, self.manifest, layout)
        self.package()
        stripped = rebuild_jar_bytes(
            read_zip_entry(self.dist / "agentcall-1.0.zip", "bridge/agentcall-bridge.jar"),
            drop=("classes2.dex",))
        rebuilt = rebuild_zip(self.dist / "agentcall-1.0.zip", self.tmp / "rebuilt.zip",
                              replace={"bridge/agentcall-bridge.jar": stripped})
        inspection = self.inspect_rebuilt(rebuilt, sha_sums_path=None)
        self.assertFalse(inspection.ok)
        self.assertTrue(any("classes2.dex" in problem for problem in inspection.problems))

    def test_checker_does_not_use_app_apk_for_dex_coverage(self):
        write_apk(self.apk, dex_names=("classes.dex", "classes2.dex"))
        self.package()
        inspection = self.inspect()
        self.assertTrue(inspection.ok, inspection.problems)


class CheckPackageCliTests(PackagingFixture):
    def call_main(self, *argv, **readers):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = check_package.main(list(argv), **readers)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_main_returns_zero_for_valid_package(self):
        self.package()
        code, output, errors = self.call_main(
            "--dist", str(self.dist),
            version_reader=lambda path: self.version,
            signing_probe=lambda path: "debug (CN=Android Debug)",
            package_reader=lambda path: APPLICATION_ID)
        self.assertEqual(code, 0, output + errors)
        self.assertIn("Status: PASS", output)

    def test_allow_unverified_signing_cannot_bypass_missing_sdk_reads(self):
        self.package()

        def no_sdk(path):
            raise package.PackagingError(
                "Android SDK not found; set ANDROID_HOME or sdk.dir in local.properties")

        inspection = self.inspect(
            signing_probe=lambda path: check_package.SIGNING_UNVERIFIED,
            version_reader=no_sdk,
            package_reader=no_sdk,
            allow_unverified_signing=True)
        self.assertFalse(inspection.ok)
        self.assertTrue(any("cannot read the packaged APK version" in problem
                            for problem in inspection.problems))
        self.assertTrue(any("cannot read the packaged APK applicationId" in problem
                            for problem in inspection.problems))

    def test_help_states_the_flag_only_suppresses_signature_verification(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout), self.assertRaises(SystemExit) as raised:
            check_package.main(["--help"])
        self.assertEqual(0, raised.exception.code)
        self.assertIn("only suppresses signature verification", stdout.getvalue())

    def test_main_returns_one_for_tampered_package(self):
        self.package()
        (self.dist / "agentcall-1.0.zip").write_bytes(
            (self.dist / "agentcall-1.0.zip").read_bytes() + b"tampered")
        code, output, errors = self.call_main(
            "--dist", str(self.dist),
            version_reader=lambda path: self.version,
            signing_probe=lambda path: "debug (CN=Android Debug)",
            package_reader=lambda path: APPLICATION_ID)
        self.assertEqual(code, 1, output + errors)
        self.assertIn("Status: FAIL", output)
        self.assertIn("FAIL:", output)

    def test_main_returns_one_without_a_matching_apk(self):
        self.package()
        (self.dist / "agentcall-1.0.apk").unlink()
        code, output, errors = self.call_main("--dist", str(self.dist))
        self.assertEqual(code, 1, output)
        self.assertIn("no matching agentcall-*.apk", errors)

    def test_main_exits_for_missing_dist(self):
        with self.assertRaises(SystemExit) as raised:
            self.call_main("--dist", str(self.tmp / "absent"))
        self.assertIn("does not exist", str(raised.exception.code))

    def test_discover_selects_current_version_among_stale_artifacts(self):
        self.package()
        shutil.copyfile(self.dist / "agentcall-1.0.zip", self.dist / "agentcall-0.9.zip")
        shutil.copyfile(self.dist / "agentcall-1.0.apk", self.dist / "agentcall-0.9.apk")
        with contextlib.redirect_stdout(io.StringIO()):
            zip_path, apk_path = check_package._discover(self.dist)
        self.assertEqual(zip_path.name, "agentcall-1.0.zip")
        self.assertEqual(apk_path.name, "agentcall-1.0.apk")

    def test_main_passes_with_stale_older_artifacts(self):
        self.package()
        shutil.copyfile(self.dist / "agentcall-1.0.zip", self.dist / "agentcall-0.9.zip")
        shutil.copyfile(self.dist / "agentcall-1.0.apk", self.dist / "agentcall-0.9.apk")
        code, output, errors = self.call_main(
            "--dist", str(self.dist),
            version_reader=lambda path: self.version,
            signing_probe=lambda path: "debug (CN=Android Debug)",
            package_reader=lambda path: APPLICATION_ID)
        self.assertEqual(code, 0, output + errors)


if __name__ == "__main__":
    unittest.main()

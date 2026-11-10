#!/usr/bin/env python3
"""Verify an AgentCall release package (Task 9 Part A).

The checker fails closed: a missing dex entry, a missing CLI/webroot file, a
module.prop/app version mismatch, leaked credential material or signing key,
a wrong executable mode or a stale SHA256SUMS all exit non-zero.  It also
reports the structure, sizes, SHA-256 digests and the APK signing type so the
release notes can label the artifact honestly.
"""

import argparse
import hashlib
import re
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

import package


@dataclass
class Inspection:
    ok: bool
    problems: list
    facts: dict


SIGNING_UNVERIFIED = "unverified (Android SDK build-tools not found)"


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def probe_signing(apk_path, build_tools=None):
    """Return an honest description of the APK signer."""
    try:
        tools = Path(build_tools) if build_tools else package.find_build_tools()
    except package.PackagingError:
        return SIGNING_UNVERIFIED
    apksigner = tools / "apksigner"
    try:
        completed = subprocess.run(
            [str(apksigner), "verify", "--print-certs", str(apk_path)],
            capture_output=True, text=True, check=False)
    except OSError:
        return SIGNING_UNVERIFIED
    if completed.returncode != 0:
        return "unsigned (apksigner verify failed)"
    match = re.search(r"Signer #1 certificate DN: (.+)", completed.stdout)
    subject = match.group(1).strip() if match else "unknown"
    if "CN=Android Debug" in subject:
        return "debug keystore (CN=Android Debug) - local debug key, not a release key"
    return "other signer (" + subject + ") - not a release key"


def _read_ledger(sums_path):
    ledger = {}
    for line in Path(sums_path).read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) == 2 and re.match(r"^[0-9a-f]{64}$", fields[0]):
            ledger[fields[1]] = fields[0]
    return ledger


def inspect_package(zip_path, apk_path, sha_sums_path="auto",
                    version_reader=None, signing_probe=None):
    """Inspect a packaged ZIP plus its matching APK.

    sha_sums_path: "auto" looks for SHA256SUMS next to the ZIP, None skips the
    digest ledger (used by tests with rebuilt archives).
    """
    zip_path = Path(zip_path)
    apk_path = Path(apk_path)
    reader = version_reader if version_reader else package.read_apk_version
    signer = signing_probe if signing_probe else probe_signing
    problems = []
    facts = {}

    if not zip_path.is_file():
        return Inspection(False, ["package ZIP is missing: " + str(zip_path)], facts)
    if not apk_path.is_file():
        problems.append("packaged APK is missing: " + str(apk_path))

    try:
        archive = zipfile.ZipFile(zip_path)
    except zipfile.BadZipFile as exc:
        return Inspection(False, ["package ZIP is not readable: " + str(exc)], facts)

    with archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            problems.append("package ZIP contains duplicate entry names")
        facts["entries"] = len(names)
        facts["zip_bytes"] = zip_path.stat().st_size
        facts["zip_sha256"] = sha256_file(zip_path)
        facts["sha256"] = {zip_path.name: facts["zip_sha256"]}
        if apk_path.is_file():
            facts["apk_bytes"] = apk_path.stat().st_size
            facts["apk_sha256"] = sha256_file(apk_path)
            facts["sha256"][apk_path.name] = facts["apk_sha256"]

        for required in package.REQUIRED_MODULE_FILES:
            if required not in names:
                problems.append("package ZIP is missing required entry " + required)

        module_version = None
        if "module.prop" in names:
            prop = package.parse_module_prop(archive.read("module.prop").decode("utf-8", "replace"))
            try:
                module_version = package.module_version(prop)
                facts["module_version"] = module_version.name
                facts["module_version_code"] = module_version.code
            except package.PackagingError as exc:
                problems.append("module.prop is invalid: " + str(exc))
        else:
            problems.append("package ZIP is missing module.prop")

        version = None
        if apk_path.is_file():
            try:
                version = reader(apk_path)
            except package.PackagingError as exc:
                problems.append("cannot read the packaged APK version: " + str(exc))
            if version is not None:
                facts["version"] = version.name
            facts["version_code"] = version.code
            if module_version is not None and module_version != version:
                problems.append(
                    "module.prop version " + module_version.name + " (" + str(module_version.code)
                    + ") does not match app versionName " + version.name + " ("
                    + str(version.code) + ")")
            expected_zip = "agentcall-" + version.name + ".zip"
            expected_apk = "agentcall-" + version.name + ".apk"
            if zip_path.name != expected_zip:
                problems.append("package ZIP name " + zip_path.name + " does not match " + expected_zip)
            if apk_path.name != expected_apk:
                problems.append("packaged APK name " + apk_path.name + " does not match " + expected_apk)

        for info in archive.infolist():
            if info.filename in package.EXECUTABLE_MODULE_FILES:
                mode = (info.external_attr >> 16) & 0o777
                if not mode & 0o111:
                    problems.append(
                        info.filename + " is not executable in the ZIP (mode " + format(mode, "o") + ")")

        for name in names:
            suffix = Path(name).suffix.lower()
            if suffix in package.FORBIDDEN_KEY_SUFFIXES:
                problems.append("signing private key material must never be packaged: " + name)

        entries = [(name, archive.read(name)) for name in names]
        problems.extend(package.scan_for_secrets(entries))

        facts["dex_entries"] = []
        if "bridge/agentcall-bridge.jar" in names:
            with tempfile.TemporaryDirectory(prefix="agentcall-check-") as workspace:
                jar_path = Path(workspace) / "agentcall-bridge.jar"
                jar_path.write_bytes(archive.read("bridge/agentcall-bridge.jar"))
                problems.extend(package.verify_container(jar_path))
                if apk_path.is_file():
                    problems.extend(package.verify_dex_coverage(apk_path, jar_path))
                try:
                    facts["dex_entries"] = package.list_dex_entries(jar_path)
                except package.PackagingError as exc:
                    problems.append(str(exc))
        else:
            problems.append("package ZIP is missing bridge/agentcall-bridge.jar")

        facts["structure"] = _structure_counts(names)

    if apk_path.is_file():
        signing = signer(apk_path)
        facts["signing"] = signing
        lowered = signing.lower()
        if "unsigned" in lowered or "invalid" in lowered:
            problems.append("packaged APK is unsigned or its signature does not verify: " + signing)

    if sha_sums_path == "auto":
        sha_sums_path = zip_path.parent / "SHA256SUMS"
    if sha_sums_path is None:
        facts["sha256sums"] = "not checked"
    else:
        sha_sums_path = Path(sha_sums_path)
        if not sha_sums_path.is_file():
            problems.append("SHA256SUMS is missing next to the package ZIP: " + str(sha_sums_path))
        else:
            ledger = _read_ledger(sha_sums_path)
            for name, digest in facts["sha256"].items():
                if name not in ledger:
                    problems.append("SHA256SUMS has no entry for " + name)
                elif ledger[name] != digest:
                    problems.append(
                        "SHA256SUMS does not match " + name + " (expected " + digest
                        + ", ledger " + ledger[name] + ")")
            facts["sha256sums"] = str(sha_sums_path)

    return Inspection(not problems, problems, facts)


def _structure_counts(names):
    counts = {"module": 0, "scripts": 0, "bridge": 0, "bin": 0, "webroot": 0}
    for name in names:
        if name.startswith("bridge/"):
            counts["bridge"] += 1
        elif name.startswith("bin/"):
            counts["bin"] += 1
        elif name.startswith("webroot/"):
            counts["webroot"] += 1
        elif name in ("customize.sh", "service.sh", "uninstall.sh"):
            counts["scripts"] += 1
        else:
            counts["module"] += 1
    return counts


def _discover(dist_dir):
    dist = Path(dist_dir)
    if not dist.is_dir():
        raise SystemExit("dist directory does not exist: " + str(dist))
    zips = sorted(dist.glob("agentcall-*.zip"))
    apks = sorted(dist.glob("agentcall-*.apk"))
    if len(zips) != 1:
        raise SystemExit("expected exactly one dist/agentcall-*.zip, found " + str(len(zips)))
    return zips[0], (apks[0] if len(apks) == 1 else None)


def _print_report(inspection, zip_path, apk_path):
    facts = inspection.facts
    print("AgentCall package check")
    print("  zip        : " + str(zip_path) + " (" + str(facts.get("zip_bytes", "?")) + " bytes, "
          + str(facts.get("entries", "?")) + " entries)")
    if apk_path:
        print("  apk        : " + str(apk_path) + " (" + str(facts.get("apk_bytes", "?")) + " bytes)")
    if "version" in facts:
        print("  version    : " + str(facts["version"]) + " (" + str(facts["version_code"]) + ")"
              + ("  module.prop: " + str(facts["module_version"]) + " ("
                 + str(facts["module_version_code"]) + ")" if "module_version" in facts else ""))
    if "signing" in facts:
        print("  signing    : " + str(facts["signing"]))
    print("  dex        : " + (", ".join(facts.get("dex_entries") or []) or "MISSING"))
    print("  structure  : " + ", ".join(
        key + "=" + str(value) for key, value in facts.get("structure", {}).items()))
    for name, digest in facts.get("sha256", {}).items():
        print("  sha256     : " + digest + "  " + name)
    print()
    if inspection.ok:
        print("Status: PASS")
    else:
        for problem in inspection.problems:
            print("FAIL: " + problem)
        print("Status: FAIL (" + str(len(inspection.problems)) + " problem(s))")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Verify an AgentCall dist package: structure, dex, versions, signing, secrets, SHA-256.")
    parser.add_argument("--dist", type=Path, default=package.DEFAULT_DIST_DIR)
    parser.add_argument("--zip", type=Path, default=None)
    parser.add_argument("--apk", type=Path, default=None)
    parser.add_argument("--sums", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.zip:
        zip_path = args.zip
        apk_path = args.apk
        if apk_path is None:
            candidates = sorted(zip_path.parent.glob("agentcall-*.apk"))
            apk_path = candidates[0] if len(candidates) == 1 else None
    else:
        zip_path, apk_path = _discover(args.dist)
        if args.apk:
            apk_path = args.apk
    if apk_path is None:
        print("package check failed: no matching agentcall-*.apk", file=sys.stderr)
        return 1

    try:
        inspection = inspect_package(
            zip_path, apk_path, sha_sums_path=args.sums if args.sums is not None else "auto")
    except package.PackagingError as exc:
        print("package check failed: " + str(exc), file=sys.stderr)
        return 1
    _print_report(inspection, zip_path, apk_path)
    return 0 if inspection.ok else 1


if __name__ == "__main__":
    sys.exit(main())
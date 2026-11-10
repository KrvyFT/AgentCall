#!/usr/bin/env python3
"""Verify an AgentCall release package (Task 9 Part A).

The checker fails closed: a missing dex entry or dex manifest, a missing
CLI/webroot file, a module.prop/app version or applicationId mismatch, leaked
credential material or signing key, a wrong executable mode, an unsigned or
unverifiable APK or a stale SHA256SUMS all exit non-zero.  DEX coverage is
validated against the container manifest embedded in the ZIP (the attestation
emitted by :bridge:packageRuntime), never against the app APK.  It reports the
structure, sizes, SHA-256 digests and the APK signer so release notes can label
the artifact honestly; --expected-signer turns the signer label into a check,
and --allow-unverified-signing is the only way to accept an APK whose signature
could not be verified (for example when the SDK build-tools are absent).
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
        return "debug keystore (CN=Android Debug) - local debug key"
    # A non-debug certificate is not automatically a release key; only the
    # operator knows which key is theirs.  --expected-signer turns this into a
    # hard check instead of a guess.
    return "custom signer (" + subject + ") - verify it is your release key"


def signing_problem(signing, expected_signer=None):
    """Return the failure for a signing description, or None when acceptable."""
    lowered = signing.lower()
    if "unverified" in lowered:
        return ("APK signing could not be verified: " + signing
                + " (pass --allow-unverified-signing to accept this explicitly)")
    if "unsigned" in lowered or "invalid" in lowered:
        return "packaged APK is unsigned or its signature does not verify: " + signing
    if expected_signer and expected_signer not in signing:
        return ("packaged APK signer " + signing + " does not match expected signer "
                + repr(expected_signer))
    return None


def _read_ledger(sums_path):
    ledger = {}
    for line in Path(sums_path).read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) == 2 and re.match(r"^[0-9a-f]{64}$", fields[0]):
            ledger[fields[1]] = fields[0]
    return ledger


def inspect_package(zip_path, apk_path, sha_sums_path="auto",
                    version_reader=None, signing_probe=None, package_reader=None,
                    allow_unverified_signing=False, expected_signer=None):
    """Inspect a packaged ZIP plus its matching APK.

    sha_sums_path: "auto" looks for SHA256SUMS next to the ZIP, None skips the
    digest ledger (used by tests with rebuilt archives).

    DEX coverage is checked against the manifest embedded in the ZIP (emitted by
    :bridge:packageRuntime from the bridge release APK), never against the app
    APK: the app APK is an independent artifact whose dex layout says nothing
    about the runtime container.
    """
    zip_path = Path(zip_path)
    apk_path = Path(apk_path)
    reader = version_reader if version_reader else package.read_apk_version
    package_id_reader = package_reader if package_reader else package.read_apk_package_name
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

        if apk_path.is_file():
            version = None
            application_id = None
            try:
                version = reader(apk_path)
            except package.PackagingError as exc:
                # Missing SDK or unreadable APK: report, then skip the summary
                # checks that need the version instead of raising AttributeError.
                problems.append("cannot read the packaged APK version: " + str(exc))
            try:
                application_id = package_id_reader(apk_path)
            except package.PackagingError as exc:
                problems.append("cannot read the packaged APK applicationId: " + str(exc))
            if application_id is not None:
                facts["application_id"] = application_id
                if application_id != package.EXPECTED_APPLICATION_ID:
                    problems.append(
                        "packaged APK applicationId " + application_id + " is not "
                        + package.EXPECTED_APPLICATION_ID)

            if version is not None:
                facts["version"] = version.name
                facts["version_code"] = version.code
                if module_version is not None and module_version != version:
                    problems.append(
                        "module.prop version " + module_version.name + " ("
                        + str(module_version.code)
                        + ") does not match app versionName " + version.name + " ("
                        + str(version.code) + ")")
                expected_zip = "agentcall-" + version.name + ".zip"
                expected_apk = "agentcall-" + version.name + ".apk"
                if zip_path.name != expected_zip:
                    problems.append(
                        "package ZIP name " + zip_path.name + " does not match " + expected_zip)
                if apk_path.name != expected_apk:
                    problems.append(
                        "packaged APK name " + apk_path.name + " does not match " + expected_apk)

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
                if package.DEX_MANIFEST_ENTRY not in names:
                    problems.append(
                        "package ZIP is missing " + package.DEX_MANIFEST_ENTRY
                        + " (dex coverage cannot be verified without it)")
                else:
                    manifest_path = Path(workspace) / "dex-manifest.json"
                    manifest_path.write_bytes(archive.read(package.DEX_MANIFEST_ENTRY))
                    problems.extend(package.verify_dex_coverage(jar_path, manifest_path))
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
        if expected_signer:
            facts["signing_expected"] = expected_signer
        problem = signing_problem(signing, expected_signer)
        if problem is not None:
            if allow_unverified_signing and "unverified" in signing.lower():
                # Explicit operator opt-out, still reported as a warning.
                facts["signing_warning"] = problem
            else:
                problems.append(problem)

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
                        + ", ledger " + ledger[name] + "); regenerate SHA256SUMS after "
                        "re-signing or rebuilding (see docs/install.md section 1)")
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


def _artifact_version(name):
    """Sort key for agentcall-<version>.zip/apk file names."""
    stem = Path(name).stem
    version = stem.split("-", 1)[1] if "-" in stem else stem
    return package.version_sort_key(version)


def _discover(dist_dir):
    """Select the current package deterministically, ignoring stale artifacts.

    A dist/ directory may accumulate older-version ZIPs/APKs, so the current
    release is the highest version name present; a stale artifact can neither
    break the check nor shadow it silently.  Only an unambiguous single-ZIP
    dist/ falls back to the single APK without a matching stem.
    """
    dist = Path(dist_dir)
    if not dist.is_dir():
        raise SystemExit("dist directory does not exist: " + str(dist))
    zips = sorted(dist.glob("agentcall-*.zip"))
    if not zips:
        raise SystemExit("no dist/agentcall-*.zip found in " + str(dist))
    apks = sorted(dist.glob("agentcall-*.apk"))
    chosen = max(zips, key=lambda path: _artifact_version(path.name))
    ignored = sorted(path.name for path in zips if path != chosen)
    if ignored:
        print("note: ignoring other dist artifacts: " + ", ".join(ignored))
    apk = next((path for path in apks if path.stem == chosen.stem), None)
    if apk is None and len(apks) == 1 and len(zips) == 1:
        apk = apks[0]
    return chosen, apk


def _print_report(inspection, zip_path, apk_path):
    facts = inspection.facts
    print("AgentCall package check")
    print("  zip        : " + str(zip_path) + " (" + str(facts.get("zip_bytes", "?")) + " bytes, "
          + str(facts.get("entries", "?")) + " entries)")
    if apk_path:
        print("  apk        : " + str(apk_path) + " (" + str(facts.get("apk_bytes", "?")) + " bytes)")
    if "application_id" in facts:
        print("  appId      : " + str(facts["application_id"]))
    if "version" in facts:
        print("  version    : " + str(facts["version"]) + " (" + str(facts["version_code"]) + ")"
              + ("  module.prop: " + str(facts["module_version"]) + " ("
                 + str(facts["module_version_code"]) + ")" if "module_version" in facts else ""))
    if "signing" in facts:
        print("  signing    : " + str(facts["signing"]))
    if "signing_warning" in facts:
        print("  warning    : " + str(facts["signing_warning"]))
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


def main(argv=None, version_reader=None, signing_probe=None, package_reader=None):
    parser = argparse.ArgumentParser(
        description="Verify an AgentCall dist package: structure, dex, versions, signing, secrets, SHA-256.")
    parser.add_argument("--dist", type=Path, default=package.DEFAULT_DIST_DIR)
    parser.add_argument("--zip", type=Path, default=None)
    parser.add_argument("--apk", type=Path, default=None)
    parser.add_argument("--sums", type=Path, default=None)
    parser.add_argument("--expected-signer", default=None,
                        help="fail unless this substring appears in the signer description "
                             "(e.g. 'CN=Your Name')")
    parser.add_argument("--allow-unverified-signing", action="store_true",
                        help="accept an APK whose signing could not be checked (no SDK build-tools); "
                             "the report still prints the warning")
    args = parser.parse_args(argv)

    if args.zip:
        zip_path = args.zip
        apk_path = args.apk
        if apk_path is None:
            candidate = zip_path.with_name(zip_path.stem + ".apk")
            if candidate.is_file():
                apk_path = candidate
            else:
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
            zip_path, apk_path,
            sha_sums_path=args.sums if args.sums is not None else "auto",
            version_reader=version_reader, signing_probe=signing_probe,
            package_reader=package_reader,
            allow_unverified_signing=args.allow_unverified_signing,
            expected_signer=args.expected_signer)
    except package.PackagingError as exc:
        print("package check failed: " + str(exc), file=sys.stderr)
        return 1
    _print_report(inspection, zip_path, apk_path)
    return 0 if inspection.ok else 1


if __name__ == "__main__":
    sys.exit(main())

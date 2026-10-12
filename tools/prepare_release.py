#!/usr/bin/env python3
"""Prepare GitHub release assets from the actual, signed ARM64 APK.

Requires a cleanly reviewed Git file list, the Android SDK and a JDK. Generates
update.json from aapt/apksigner output, never from a manually supplied version.
No GitHub mutation or signing secret is performed by this script.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/update-publication")
    args = parser.parse_args()
    config = json.loads((ROOT / "tools/release-config.json").read_text())
    build_tools = sorted((args.sdk / "build-tools").iterdir(), key=lambda p: [int(n) for n in re.findall(r"\d+", p.name)])[-1]
    aapt = build_tools / ("aapt.exe" if (build_tools / "aapt.exe").exists() else "aapt")
    apk_argument = str(args.apk.resolve())
    if aapt.suffix == ".exe" and os.name != "nt":
        apk_argument = subprocess.check_output(["wslpath", "-w", apk_argument], text=True).strip()
    badging = subprocess.check_output([str(aapt), "dump", "badging", apk_argument], text=True, encoding="utf-8")
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if package is None:
        raise ValueError("Cannot inspect APK identity")
    package_name, version_code, version_name = package.groups()
    minimum = int(re.search(r"sdkVersion:'(\d+)'", badging)[1])
    target = int(re.search(r"targetSdkVersion:'(\d+)'", badging)[1])
    assert package_name == config["packageName"] and minimum == 29 and target == 37
    assert re.search(r"native-code: 'arm64-v8a'\s*$", badging, re.MULTILINE), "Release must be ARM64-only"
    assert "application-debuggable" not in badging
    assert re.fullmatch(r"\d+\.\d+\.\d+", version_name), "Only stable release names are published to the updater"
    signature = subprocess.check_output(["java", "-jar", str(build_tools / "lib/apksigner.jar"), "verify", "--verbose", "--print-certs", str(args.apk.resolve())], text=True)
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([a-f0-9]+)", signature)[1]
    assert certificate == config["signingCertificateSha256"], "Signing identity changed; existing installations cannot update"
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    tracked = [name for name in tracked if name]
    assert "app/src/main/AndroidManifest.xml" in tracked and "tools/release-config.json" in tracked
    forbidden = re.compile(r"(^|/)(\.downloads|\.toolchains|build|toolchains|artifacts|__pycache__)/|\.(jks|keystore|pem|key|pyc)$|(^|/)local\.properties$")
    assert not any(forbidden.search(name) for name in tracked), "Private/generated materials are tracked"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    apk = output / f"MobileCode-{version_name}-{config['abi']}.apk"
    if args.apk.resolve() != apk:
        shutil.copy2(args.apk, apk)
    sources = output / f"MobileCode-{version_name}-source.zip"
    with zipfile.ZipFile(sources, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in sorted(tracked):
            archive.write(ROOT / name, name)
    corresponding = output / f"MobileCode-{version_name}-corresponding-sources.zip"
    assert corresponding.is_file(), "Prepare corresponding source materials first"
    with zipfile.ZipFile(corresponding) as archive:
        source_map = json.loads(archive.read("sources.json"))
        assert source_map["copyleftRecipes"] and source_map["materials"]
    update = {"schemaVersion": 1, "packageName": package_name, "versionCode": int(version_code), "versionName": version_name,
              "minSdk": minimum, "abi": config["abi"], "size": apk.stat().st_size, "sha256": checksum(apk),
              "apkUrl": f"https://github.com/{config['repository']}/releases/download/v{version_name}/{apk.name}",
              "releaseNotes": args.notes.read_text(encoding="utf-8")}
    manifest = output / "update.json"
    manifest.write_text(json.dumps(update, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assets = [apk, sources, corresponding, manifest]
    (output / "SHA256SUMS.txt").write_text("".join(f"{checksum(path)}  {path.name}\n" for path in assets), encoding="utf-8")
    report = {"repository": config["repository"], "tag": f"v{version_name}", "versionCode": int(version_code),
              "signingCertificateSha256": certificate, "sourceFiles": len(tracked), "assets": [{"name": p.name, "bytes": p.stat().st_size, "sha256": checksum(p)} for p in assets]}
    (output / "release.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

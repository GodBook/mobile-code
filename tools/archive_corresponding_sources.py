#!/usr/bin/env python3
"""Archive the locked GPL/LGPL inputs, Android recipes/patches and Sora sources.

Run on Linux/WSL. On WSL, Windows curl is used when available because it follows
the host's network configuration. No downloaded build function is executed.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REVISION = "aed8f2405a392e49b487eb7ac0ed2eded4f45015"
CACHE = ROOT / ".downloads" / "corresponding-sources"


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def download(url, destination, expected=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and (expected is None or sha256(destination) == expected):
        return destination
    temporary = destination.with_name(destination.name + ".part")
    windows_curl = Path("/mnt/c/Windows/System32/curl.exe")
    if windows_curl.exists():
        command = [str(windows_curl), "--ssl-revoke-best-effort"]
        target = subprocess.check_output(["wslpath", "-w", str(temporary)], text=True).strip()
    else:
        command, target = ["curl"], str(temporary)
    if url.startswith("https://github.com/") and "/archive/refs/tags/" in url:
        repository, tag = url.removeprefix("https://github.com/").split("/archive/refs/tags/", 1)
        url = f"https://codeload.github.com/{repository}/tar.gz/refs/tags/{tag.removesuffix('.tar.gz')}"
    subprocess.run(command + ["-fsSL", "--retry", "3", "--retry-all-errors", "--connect-timeout", "20", "--max-time", "300", "-o", target, url], check=True)
    if expected and sha256(temporary) != expected:
        raise ValueError(f"Upstream source checksum mismatch: {url}")
    temporary.replace(destination)
    return destination


def recipe_info(path):
    # Source declarations and function definitions only; never call build steps.
    script = r'''
export TERMUX_ARCH=aarch64 TERMUX_PKG_API_LEVEL=29
export TERMUX_PKG_BUILDER_DIR="$(dirname "$1")"
export TERMUX_SCRIPTDIR="$(dirname "$(dirname "$TERMUX_PKG_BUILDER_DIR")")"
source "$1" >/dev/null
printf '%s\0' "$TERMUX_PKG_VERSION" "${TERMUX_PKG_REVISION:-0}" "$TERMUX_PKG_LICENSE"
printf '%s\0' "${#TERMUX_PKG_SRCURL[@]}" "${TERMUX_PKG_SRCURL[@]}" "${TERMUX_PKG_SHA256[@]}"
'''
    values = subprocess.check_output(["bash", "-c", script, "source-info", str(path)]).decode().rstrip("\0").split("\0")
    count = int(values[3])
    return {"version": values[0], "revision": values[1], "license": values[2],
            "urls": values[4:4 + count], "checksums": values[4 + count:]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="0.2.0")
    parser.add_argument("--recipes", type=Path, default=Path.home() / f".cache/mobilecode/termux-packages-{REVISION}")
    args = parser.parse_args()
    extra_checksums = json.loads((ROOT / "tools/locks/release-source-extras.json").read_text())
    records = json.loads((ROOT / "tools/locks/arm64-v8a.packages.json").read_text())
    if isinstance(records, dict):
        records = records.get("packages", records)
    if isinstance(records, dict):
        records = list(records.values())
    selected = {}
    package_licenses = []
    for record in records:
        name = record["Package"]
        recipe = args.recipes / "packages" / name / "build.sh"
        if not recipe.exists():
            matches = list((args.recipes / "packages").glob(f"*/{name}.subpackage.sh"))
            if not matches:
                raise ValueError(f"Missing pinned source recipe for {name}")
            recipe = matches[0].parent / "build.sh"
        info = recipe_info(recipe)
        package_licenses.append({"package": name, "binaryVersion": record["Version"], "recipe": recipe.relative_to(args.recipes).as_posix(), "license": info["license"]})
        if "GPL" not in info["license"]:
            continue
        binary = record["Version"].split(":")[-1]
        expected_version = info["version"] + ("-" + info["revision"] if info["revision"] != "0" else "")
        if binary != expected_version:
            raise ValueError(f"Pinned source/binary version mismatch: {name} {binary} != {expected_version}")
        entry = selected.setdefault(recipe.parent.name, {**info, "recipe": recipe.relative_to(args.recipes).as_posix(), "packages": []})
        entry["packages"].append({"name": name, "version": record["Version"], "binarySha256": record["SHA256"]})

    work = []
    for name, info in selected.items():
        for index, url in enumerate(info["urls"]):
            expected = info["checksums"][index] if index < len(info["checksums"]) else None
            if url.startswith("git+"):
                repository = url.removeprefix("git+").removesuffix(".git")
                if repository != "https://github.com/pulseaudio/pulseaudio":
                    raise ValueError(f"Unresolved source Git reference: {url}")
                url = f"{repository}/archive/refs/tags/v{info['version']}.tar.gz"
                expected = extra_checksums[f"{name}-{index}-v{info['version']}.tar.gz"]
            if expected in ("SKIP_CHECKSUM", ""):
                raise ValueError(f"Source has no fixed checksum: {name}")
            filename = f"{name}-{index}-" + url.split("/")[-1]
            work.append({"recipe": name, "url": url, "expectedSha256": expected, "file": filename})
        if name == "readline":
            source = (args.recipes / info["recipe"]).read_text()
            version = info["version"].rsplit(".", 1)[0]
            for number, digest in re.findall(r"PATCH_CHECKSUMS\[(\d+)\]=([a-f0-9]{64})", source):
                url = f"https://mirrors.kernel.org/gnu/readline/readline-{version}-patches/readline{version.replace('.', '')}-{number}"
                work.append({"recipe": name, "url": url, "expectedSha256": digest, "file": f"readline-patch-{number}.patch"})

    recipe_archive = ROOT / ".downloads" / f"termux-recipes-{REVISION}.tar.gz"
    if not recipe_archive.exists():
        download(f"https://codeload.github.com/termux/termux-packages/tar.gz/{REVISION}", recipe_archive, extra_checksums["termuxRecipesSha256"])
    assert sha256(recipe_archive) == extra_checksums["termuxRecipesSha256"]
    sora = {"recipe": "sora-editor", "url": "https://codeload.github.com/Rosemoe/sora-editor/tar.gz/refs/tags/0.24.6", "expectedSha256": extra_checksums["sora-editor-0.24.6.tar.gz"], "file": "sora-editor-0.24.6.tar.gz"}
    work.append(sora)

    def retrieve(item):
        file = download(item["url"], CACHE / item["file"], item["expectedSha256"])
        return {**item, "sha256": sha256(file), "bytes": file.stat().st_size}

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        materials = list(pool.map(retrieve, work))
    output = ROOT / "artifacts" / "update-publication"
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"MobileCode-{args.version}-corresponding-sources.zip"
    report = {"termuxRevision": REVISION, "recipesSha256": sha256(recipe_archive), "copyleftRecipes": selected,
              "allLockedPackageLicenses": package_licenses, "materials": materials,
              "applicationMaterials": "Application source, Android adaptations, repackaging and relinking instructions are in the matching GitHub tag/source ZIP."}
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_STORED, allowZip64=True) as target:
        target.write(recipe_archive, "recipes/" + recipe_archive.name)
        for material in materials:
            target.write(CACHE / material["file"], "upstream/" + material["file"])
        target.writestr("sources.json", json.dumps(report, ensure_ascii=False, indent=2))
        target.writestr("README.txt", "Corresponding source inputs for the matching Mobile Code release.\n"
            "sources.json maps every bundled GPL/LGPL recipe to the locked binary version and SHA256.\n"
            "recipes/ contains the complete pinned Android build framework, recipes, patches and configuration.\n"
            "upstream/ contains original source archives, including the readline patch level and Sora editor.\n"
            "Unpack recipes and sources; use the recorded Termux build recipes for aarch64.\n"
            "Use the matching application source and docs/BUILD.md to apply Android adaptations and rebuild/relink the APK.\n"
            "Each component retains its original license; source archives contain their full license texts.\n")
    with zipfile.ZipFile(archive) as target:
        assert target.testzip() is None
    (output / "corresponding-sources.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"archive": archive.name, "bytes": archive.stat().st_size, "sha256": sha256(archive), "copyleftRecipes": len(selected), "materials": len(materials)}), flush=True)


if __name__ == "__main__":
    main()

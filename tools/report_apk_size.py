#!/usr/bin/env python3
"""Measure APK and nested offline resource sizes without modifying release assets."""
import argparse
from collections import defaultdict
import hashlib
import json
import shutil
import tempfile
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    digest = hashlib.sha256()
    with args.apk.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    groups = defaultdict(lambda: {"entries": 0, "storedBytes": 0, "expandedBytes": 0})
    resources = defaultdict(lambda: {"entries": 0, "storedBytes": 0, "expandedBytes": 0})
    largest = []

    def add(target, name, entry):
        target[name]["entries"] += 1
        target[name]["storedBytes"] += entry.compress_size
        target[name]["expandedBytes"] += entry.file_size

    with zipfile.ZipFile(args.apk) as apk:
        for entry in apk.infolist():
            name = entry.filename
            category = ("native" if name.startswith("lib/") else
                        "offlineResources" if name == "assets/toolchains/root.zip" else
                        "dex" if name.endswith(".dex") else
                        "assets" if name.startswith("assets/") else "androidResourcesAndMetadata")
            add(groups, category, entry)
            largest.append({"path": name, "storedBytes": entry.compress_size, "expandedBytes": entry.file_size})
        with tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024) as spool:
            with apk.open("assets/toolchains/root.zip") as source:
                shutil.copyfileobj(source, spool)
            spool.seek(0)
            with zipfile.ZipFile(spool) as offline:
                for entry in offline.infolist():
                    parts = entry.filename.split("/")
                    category = "/".join(parts[:3]) if len(parts) > 2 else "/".join(parts[:2])
                    add(resources, category, entry)
    result = {"apk": args.apk.name, "bytes": args.apk.stat().st_size, "sha256": digest.hexdigest(),
              "groups": dict(groups), "largestEntries": sorted(largest, key=lambda item: item["storedBytes"], reverse=True)[:20],
              "offlineResourceGroups": dict(sorted(resources.items())),
              "decision": "Retain complete offline ARM64 runtimes, debug capabilities and license payload. No unmeasured shrink claim or dependency removal."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"bytes": result["bytes"], "groups": result["groups"]}))


if __name__ == "__main__":
    main()

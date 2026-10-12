#!/usr/bin/env python3
"""Audit the actual APK's offline payload, ABI, checksums and ELF LOAD alignment."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]

def sha(stream):
    digest=hashlib.sha256()
    for chunk in iter(lambda:stream.read(1024*1024),b""):
        digest.update(chunk)
    return digest.hexdigest()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("apk",type=Path)
    parser.add_argument("--abi",default="arm64-v8a",choices=["arm64-v8a","x86_64"])
    parser.add_argument("--output",type=Path,default=ROOT/"verification/local/apk-audit.json")
    args=parser.parse_args()
    checks=[]
    libraries=[]
    def check(name,condition):
        checks.append({"check":name,"passed":bool(condition)})
    with zipfile.ZipFile(args.apk) as archive:
        names=archive.namelist()
        manifest=json.loads(archive.read("assets/toolchains/manifest.json"))
        check("bundled toolchain ABI",manifest["abi"]==args.abi)
        abis={name.split('/')[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        check("single selected ABI",abis=={args.abi})
        with archive.open("assets/toolchains/root.zip") as resource:
            check("offline resource SHA256",sha(resource)==manifest["resourceSha256"])
        expected={item["name"]:item["sha256"] for item in manifest["libraries"]}
        check("unique manifested native filenames",len(expected)==len(manifest["libraries"]))
        found=set()
        for name in names:
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            with archive.open(name) as library:
                data=library.read()
            if data[:4]!=b"\x7fELF" or len(data)<64:
                check(f"ELF header {name}",False)
                continue
            machine=struct.unpack_from("<H",data,18)[0]
            count=struct.unpack_from("<H",data,56)[0]
            size=struct.unpack_from("<H",data,54)[0]
            offset=struct.unpack_from("<Q",data,32)[0]
            segments=[]
            for index in range(count):
                p=offset+index*size
                if struct.unpack_from("<I",data,p)[0]!=1:
                    continue
                file_offset,address=struct.unpack_from("<QQ",data,p+8)
                alignment=struct.unpack_from("<Q",data,p+48)[0]
                segments.append(alignment>=16384 and (address-file_offset)%16384==0)
            digest=hashlib.sha256(data).hexdigest()
            leaf=name.split('/')[-1]
            found.add(leaf)
            libraries.append({"name":leaf,"bytes":len(data),"machine":machine,"page16k":bool(segments) and all(segments),"sha256":digest})
            check(f"ELF ABI {leaf}",machine==(183 if args.abi=="arm64-v8a" else 62))
            check(f"ELF 16KiB {leaf}",bool(segments) and all(segments))
            if leaf in expected:
                check(f"bundled native SHA256 {leaf}",digest==expected[leaf])
        check("all manifested native libraries present",set(expected)<=found)
        check("packaged process/JDWP/syntax bridges",{"libmc_runner.so","libmc_process.so","libmc_syntax.so","libdt_mobile.so"}<=found)
        check("no host bytecode cache",not any("__pycache__" in name or name.endswith(".pyc") for name in names))
        for name in ("runtime/mobilecode_python.py","runtime/mobilecode-runtime.jar","THIRD_PARTY_NOTICES.md","licenses/sora-LGPL-2.1.txt","licenses/CPython-3.13.txt"):
            check(f"offline asset {name}","assets/"+name in names)
        with archive.open("assets/runtime/mobilecode_python.py") as packaged, (ROOT/"app/src/main/assets/runtime/mobilecode_python.py").open("rb") as source:
            check("packaged Python adapter matches current source",sha(packaged)==sha(source))
    check("APK <= 800MiB",args.apk.stat().st_size<=800*1024*1024)
    with args.apk.open("rb") as f:
        apk_hash=sha(f)
    report={"time":datetime.now(timezone.utc).isoformat(),"apk":str(args.apk),"bytes":args.apk.stat().st_size,"sha256":apk_hash,
        "abi":args.abi,"passed":all(item["passed"] for item in checks),"checks":checks,"libraries":libraries,
        "runtimePackages":manifest["packages"],"sourceOverrides":manifest.get("sourceOverrides",{}),
        "limits":["ELF alignment does not establish successful execution on a 16KiB device.","Manifest permissions, debuggable flag and signing are checked separately with Android SDK tools."]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps({key:report[key] for key in ("apk","bytes","sha256","passed")}))
    print(f"Native libraries: {len(libraries)}; failed checks: {sum(not item['passed'] for item in checks)}")
    for item in checks:
        if not item["passed"]: print("FAIL:",item["check"])
    raise SystemExit(0 if report["passed"] else 1)

if __name__=="__main__":
    main()

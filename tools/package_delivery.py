#!/usr/bin/env python3
"""Package the verified APK and application sources without caches or keys."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]

def main():
    evidence=json.loads((ROOT/"verification/local/phone-runtime.json").read_text(encoding="utf-8"))
    audit=json.loads((ROOT/"verification/local/apk-audit.json").read_text())
    source=ROOT/"app/build/outputs/apk/release/app-release.apk"
    def checksum(path):
        value=hashlib.sha256()
        with path.open("rb") as f:
            for block in iter(lambda:f.read(1024*1024),b""): value.update(block)
        return value.hexdigest()
    actual=checksum(source)
    assert evidence["passed"] and audit["passed"], "Device acceptance and APK audit must pass"
    assert actual==evidence["apkSha256"]==audit["sha256"], "Evidence does not describe this APK"
    out=ROOT/"artifacts"
    out.mkdir(exist_ok=True)
    apk=out/"MobileCode-0.1.0-arm64-v8a.apk"
    shutil.copy2(source,apk)
    assert checksum(apk)==actual
    files=set()
    for name in ("AGENTS.md","README.md","build.gradle.kts","settings.gradle.kts","gradle.properties","gradlew","gradlew.bat",
                 "app/build.gradle.kts","app/gradle.lockfile","runtime-java/build.gradle.kts","runtime-java/gradle.lockfile"):
        path=ROOT/name
        if path.is_file(): files.add(path)
    for name in ("gradle","app/src","runtime-java/src","tools","docs","verification"):
        for path in (ROOT/name).rglob("*"):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix==".pyc": continue
            if path.name.lower().endswith((".keystore",".jks")) or path.name=="local.properties": continue
            files.add(path)
    sources=out/"MobileCode-0.1.0-source.zip"
    with zipfile.ZipFile(sources,"w",zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sorted(files): archive.write(path,path.relative_to(ROOT).as_posix())
    report={"apk":apk.name,"bytes":apk.stat().st_size,"sha256":actual,
        "source":sources.name,"sourceSha256":checksum(sources),"androidTests":len(evidence["tests"]),"hostTests":5,
        "device":evidence["device"],"nativeFilesAudited":len(audit["libraries"]),"signing":"Local Android development certificate; application debuggable=false",
        "limits":evidence["notVerified"],"publicDistributionSourceMaterials":"See docs/SOURCE_MATERIALS.md"}
    (out/"delivery.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    (out/"SHA256SUMS.txt").write_text(f"{actual}  {apk.name}\n{report['sourceSha256']}  {sources.name}\n",encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False))

if __name__=="__main__": main()

#!/usr/bin/env python3
"""Save Android instrumentation outcomes tied to the exact tested APK."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("log",type=Path)
    parser.add_argument("apk",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    text=args.log.read_text(encoding="utf-8-sig")
    fields={}
    results=[]
    for line in text.splitlines():
        match=re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)",line)
        if match: fields[match[1]]=match[2]
        code=re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)",line)
        if code and int(code[1])!=1 and fields.get("test"):
            results.append({"class":fields.get("class"),"test":fields["test"],"passed":int(code[1])==0,
                "status":int(code[1]),"failure":fields.get("stack") if int(code[1])!=0 else None})
            fields={}
    expected=re.search(r"OK \((\d+) tests?\)",text)
    passed=bool(expected) and int(expected[1])==len(results) and all(r["passed"] for r in results)
    digest=hashlib.sha256()
    with args.apk.open("rb") as f:
        for block in iter(lambda:f.read(1024*1024),b""): digest.update(block)
    report={"time":datetime.now(timezone.utc).isoformat(),"device":{"model":"Xiaomi Mi 9 SE","os":"LineageOS Android 15","api":35,"abi":"arm64-v8a","pageBytes":4096},
        "apk":str(args.apk),"apkSha256":digest.hexdigest(),"passed":passed,"tests":results,
        "durationSeconds":float(re.search(r"Time: ([0-9.]+)",text)[1]) if "Time:" in text else None,
        "log":str(args.log),"notVerified":["Actual 16KiB execution","Full Android10–17/vendor matrix","P95 performance and long-running background/low-memory matrix"]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"passed":passed,"tests":len(results),"apkSha256":report["apkSha256"]}))
    raise SystemExit(0 if passed else 1)

if __name__=="__main__": main()

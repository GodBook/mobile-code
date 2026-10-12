#!/usr/bin/env python3
"""Repackage Android/Bionic tools into APK-native libraries and offline resources.

Run under Linux/WSL (requires dpkg-deb, patchelf, readelf). Downloads are cached,
SHA256 checked against the repository index, then pinned in a generated lock.
No downloaded executable is run by this script.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".downloads"
TOOLCHAIN_CACHE = Path(os.environ.get("MC_TOOLCHAIN_CACHE", str(Path.home()/".cache/mobilecode/toolchains")))
MIRRORS = ["https://packages-cf.termux.dev/apt/termux-main", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"]
PACKAGES = ["clang", "lld", "lldb", "openjdk-21", "python", "ndk-sysroot", "libc++"]
TOOLS = {"clang", "clang++", "clang-21", "clangd", "lld", "ld.lld", "lldb-dap", "lldb-server", "llvm-ar", "llvm-nm"}
GRAMMARS = {"tree-sitter": "v0.25.10", "tree-sitter-c": "v0.24.1", "tree-sitter-cpp": "v0.23.4", "tree-sitter-python": "v0.23.6", "tree-sitter-java": "v0.23.5"}
SPAWN_SOURCE_REV = "aed8f2405a392e49b487eb7ac0ed2eded4f45015"

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def fetch(url: str, path: Path, sha: str | None = None) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and (sha is None or digest(path) == sha):
        return path
    tmp = path.with_suffix(path.suffix + ".part")
    subprocess.run(["curl", "-fsSL", "--retry", "4", "--retry-all-errors", "--connect-timeout", "20", "--max-time", "600", "-o", str(tmp), url], check=True)
    if sha and digest(tmp) != sha:
        tmp.unlink()
        raise ValueError(f"Checksum mismatch: {url}")
    tmp.replace(path)
    return path

def index(arch: str) -> tuple[dict[str, dict], str]:
    target = CACHE / f"Packages-{arch}"
    failures = []
    for mirror in MIRRORS:
        try:
            fetch(f"{mirror}/dists/stable/main/binary-{arch}/Packages", target)
            text = target.read_text()
            if not text.startswith("Package:"):
                target.unlink()
                raise ValueError("Not a Debian package index")
            records = {}
            for paragraph in text.split("\n\n"):
                fields = dict(line.split(": ", 1) for line in paragraph.splitlines() if ": " in line and not line.startswith(" "))
                if "Package" in fields:
                    records[fields["Package"]] = fields
            return records, mirror
        except (OSError, ValueError, subprocess.CalledProcessError) as error:
            failures.append(str(error))
    raise RuntimeError("Package mirrors failed: " + "; ".join(failures))

def closure(records: dict, roots: list[str]) -> list[dict]:
    selected = {}
    def add(name: str):
        if name in selected:
            return
        if name not in records:
            providers = [r for r in records.values() if name in re.split(r"[, ]+", r.get("Provides", ""))]
            if not providers:
                raise ValueError(f"Missing dependency {name}")
            name = providers[0]["Package"]
        record = records[name]
        selected[name] = record
        for dependency in record.get("Depends", "").split(","):
            alternatives = [re.split(r"[ (:]", value.strip())[0] for value in dependency.split("|")]
            alternatives = [value for value in alternatives if value]
            if alternatives:
                add(next((value for value in alternatives if value in records), alternatives[0]))
    for root in roots:
        add(root)
    return sorted(selected.values(), key=lambda r: r["Package"])

def unpack_deb(archive: Path, root: Path, record: dict, ownership: dict):
    stamp = root / f".unpacked-{record['Package']}"
    if stamp.exists() and stamp.read_text() == record["SHA256"]:
        return
    proc = subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(archive)], stdout=subprocess.PIPE)
    assert proc.stdout
    with tarfile.open(fileobj=proc.stdout, mode="r|") as tar:
        for member in tar:
            name = member.name.removeprefix("./")
            prefix = "data/data/com.termux/files/"
            if not name.startswith(prefix):
                continue
            relative = Path(name[len(prefix):])
            if ".." in relative.parts:
                raise ValueError("Unsafe package path")
            dest = root / relative
            ownership[str(relative)] = record["Package"]
            if member.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                dest.parent.mkdir(parents=True, exist_ok=True)
                if dest.is_symlink():
                    dest.unlink()
                stream = tar.extractfile(member)
                assert stream
                with dest.open("wb") as out:
                    shutil.copyfileobj(stream, out)
                dest.chmod(member.mode & 0o777)
            elif member.issym():
                dest.parent.mkdir(parents=True, exist_ok=True)
                link = member.linkname
                if link.startswith("/data/data/com.termux/files/"):
                    link = os.path.relpath(root / link[len("/data/data/com.termux/files/"):], dest.parent)
                resolved = (dest.parent / link).resolve()
                if not resolved.is_relative_to(root.resolve()):
                    continue
                if dest.exists() or dest.is_symlink():
                    dest.unlink()
                dest.symlink_to(link)
            elif member.islnk():
                link = member.linkname.removeprefix("./").removeprefix(prefix)
                source = root / link
                if source.is_file():
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, dest)
    if proc.wait() != 0:
        raise RuntimeError(f"Unpack failed: {archive}")
    stamp.write_text(record["SHA256"])

def elf_info(path: Path):
    with path.open("rb") as f:
        header = f.read(64)
        if len(header) < 64 or header[:4] != b"\x7fELF":
            return None
        kind, machine = struct.unpack_from("<HH", header, 16)
        if header[4] != 2 or kind not in (2, 3):
            return None
        phoff = struct.unpack_from("<Q", header, 32)[0]
        phsize, phnum = struct.unpack_from("<HH", header, 54)
        alignments = []
        for i in range(phnum):
            f.seek(phoff + i * phsize)
            segment = f.read(phsize)
            if struct.unpack_from("<I", segment)[0] == 1:
                offset, vaddr = struct.unpack_from("<QQ", segment, 8)
                alignment = struct.unpack_from("<Q", segment, 48)[0]
                alignments.append((alignment, (vaddr - offset) % 16384 == 0))
        return machine, alignments

def wheel(name: str, version: str, destination: Path):
    metadata = json.loads(fetch(f"https://pypi.org/pypi/{name}/{version}/json", CACHE / f"{name}-{version}.json").read_text())
    item = next(item for item in metadata["urls"] if item["filename"].endswith("py2.py3-none-any.whl") or item["filename"].endswith("py3-none-any.whl"))
    archive = fetch(item["url"], CACHE / item["filename"], item["digests"]["sha256"])
    with zipfile.ZipFile(archive) as z:
        z.extractall(destination)

def jdtls(root: Path):
    dest = root / "usr/share/jdtls"
    if (dest / "plugins").is_dir():
        return
    filename = "jdt-language-server-1.61.0-202609031315.tar.gz"
    archive = fetch("https://download.eclipse.org/jdtls/milestones/1.61.0/" + filename,
        CACHE / filename,"338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64")
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tar:
        tar.extractall(dest, filter="data")

def grammars():
    base = ROOT / ".toolchains/grammars"
    for name, version in GRAMMARS.items():
        dest = base / name
        if dest.exists():
            continue
        archive = fetch(f"https://codeload.github.com/tree-sitter/{name}/tar.gz/refs/tags/{version}", CACHE / f"{name}-{version}.tar.gz")
        with tarfile.open(archive) as tar:
            for member in tar:
                parts = Path(member.name).parts[1:]
                if not parts or ".." in parts:
                    continue
                member.name = str(Path(name, *parts))
                tar.extract(member, base, filter="data")

def rebuild_spawn(root: Path, abi: str):
    """The upstream spawn compatibility library still has 4KiB LOAD segments."""
    source = CACHE / f"android-spawn-{SPAWN_SOURCE_REV}"
    for name in ("posix_spawn.cpp", "posix_spawn.h", "LICENSE"):
        fetch(f"https://raw.githubusercontent.com/termux/termux-packages/{SPAWN_SOURCE_REV}/packages/libandroid-spawn/{name}", source / name)
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", "/opt/android-ndk-r28c"))
    triple = "aarch64-linux-android29" if abi == "arm64-v8a" else "x86_64-linux-android29"
    compiler = ndk / f"toolchains/llvm/prebuilt/linux-x86_64/bin/{triple}-clang++"
    output = source / f"{abi}-libandroid-spawn.so"
    if not output.exists():
        subprocess.run([str(compiler), "-std=c++17", "-D_GNU_SOURCE", "-fPIC", "-shared", "-static-libstdc++",
            "-Wl,-soname,libandroid-spawn.so", "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
            "-I", str(source), str(source / "posix_spawn.cpp"), "-o", str(output)], check=True)
    shutil.copyfile(output, root / "usr/lib/libandroid-spawn.so")

def package(root: Path, abi: str, records: list[dict], python_prefix: Path | None, native_only: bool = False):
    print(f"Preparing runtime resources for {abi}", flush=True)
    if python_prefix and not native_only:
        for link in python_prefix.rglob("*"):
            destination = root / "cpython313" / link.relative_to(python_prefix)
            if link.is_symlink() and destination.is_symlink():
                destination.unlink()
        shutil.copytree(python_prefix, root / "cpython313", dirs_exist_ok=True, symlinks=True)
    python_home = root / "cpython313"
    if not (python_home / "lib/python3.13").is_dir():
        raise RuntimeError("CPython 3.13 is required. Supply --python-prefix from tools/build_python.sh (LLDB's Python remains separate).")
    if not native_only:
        wheel("jedi", "0.19.2", python_home / "lib/python3.13/site-packages")
        wheel("parso", "0.8.5", python_home / "lib/python3.13/site-packages")
        jdtls(root)
    rebuild_spawn(root, abi)
    print("Mapping Android ELF libraries", flush=True)
    out = ROOT / f"app/toolchains/{abi}"
    libs = out / f"jniLibs/{abi}"
    assets = out / "assets/toolchains"
    libs.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    expected_machine = 183 if abi == "arm64-v8a" else 62
    ownership = json.loads((root.parent / "ownership.json").read_text())
    native = {}
    for p in sorted(root.rglob("*")):
        if not p.is_file() or p.is_symlink():
            continue
        rel = p.relative_to(root).as_posix()
        if ownership.get(rel) in {"ndk-sysroot", "libandroid-stub"}:
            continue
        if "/include/" in rel or "linux-android/" in rel or p.suffix == ".a":
            continue
        info = elf_info(p)
        if not info:
            continue
        if info[0] != expected_machine:
            continue
        dynamic = subprocess.check_output(["readelf", "-d", str(p)], text=True)
        soname = re.search(r'\(SONAME\).*?\[(.*?)\]', dynamic)
        if not soname and not p.name.endswith(".so") and p.name not in TOOLS and not ("/jvm/" in rel and ("/bin/" in rel or p.name=="jspawnhelper")):
            continue
        name = p.name if re.fullmatch(r"lib[^/]+\.so", p.name) else "libmc_" + re.sub(r"[^a-zA-Z0-9_]", "_", rel) + ".so"
        native[p.resolve()] = {"name": name, "soname": soname.group(1) if soname else None, "path": rel}
    groups = {}
    for source, item in native.items():
        groups.setdefault(item["name"], []).append(item)
    for items in groups.values():
        if len(items)>1:
            for item in items:
                item["name"] = "libmc_" + re.sub(r"[^a-zA-Z0-9_]", "_", item["path"]) + ".so"
    assert len({item["name"] for item in native.values()}) == len(native), "Native filename collision"
    by_soname = {}
    for source, item in native.items():
        if item["soname"]:
            by_soname.setdefault(item["soname"], []).append((source,item))
    for source, item in native.items():
        dest = libs / item["name"]
        shutil.copyfile(source, dest)
        cmd = ["patchelf", "--page-size", "16384", "--set-rpath", "$ORIGIN"]
        if item["soname"]:
            cmd += ["--set-soname", item["name"]]
        needed = subprocess.check_output(["patchelf", "--print-needed", str(dest)], text=True).splitlines()
        for old in needed:
            candidates = by_soname.get(old, [])
            local = (source.parent / old).resolve()
            if local in native:
                replacement = native[local]["name"]
            elif len(candidates)==1:
                replacement = candidates[0][1]["name"]
            elif candidates:
                namespace = item["path"].split('/')[0]
                same = [value for _,value in candidates if value["path"].split('/')[0]==namespace]
                if len(same)!=1:
                    raise RuntimeError(f"Ambiguous dependency {old} from {item['path']}")
                replacement = same[0]["name"]
            else:
                replacement = old
            if replacement != old:
                cmd += ["--replace-needed", old, replacement]
        subprocess.run(cmd + [str(dest)], check=True)
        dest.chmod(0o755)
        item["sha256"] = digest(dest)
        info = elf_info(dest)
        item["page16k"] = all(a >= 16384 and compatible for a, compatible in info[1])
    names = {item["name"] for item in native.values()}
    assert libs.resolve().is_relative_to((ROOT / "app/toolchains").resolve())
    for obsolete in libs.glob("*.so"):
        if obsolete.name not in names:
            obsolete.unlink() # Only stale files generated by this packager.
    print("Writing offline resource archive" if not native_only else "Reusing verified resource archive", flush=True)
    aliases = []
    archive = assets / "root.zip"
    bytes_total = 0
    if native_only:
        previous = json.loads((assets / "manifest.json").read_text())
        assert digest(archive)==previous["resourceSha256"]
        bytes_total = previous["resourceBytes"]
        for alias in previous["aliases"]:
            source = (root / alias["path"]).resolve()
            aliases.append({"path":alias["path"],"library":native[source]["name"]})
    else:
      with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in sorted(root.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(root).as_posix()
            if rel.startswith(".unpacked-"):
                continue
            resolved = p.resolve()
            if resolved in native:
                aliases.append({"path": rel, "library": native[resolved]["name"]})
                continue
            if rel.startswith(("usr/bin/", "cpython313/bin/")) or "/man/" in rel or "/cmake/" in rel or rel.endswith("src.zip"):
                continue
            z.write(p, rel)
            bytes_total += p.stat().st_size
    jdk = next(root.glob("usr/lib/jvm/java-21*"))
    launcher = next((root / "usr/share/jdtls/plugins").glob("org.eclipse.equinox.launcher_*.jar"))
    manifest = {
        "schemaVersion": 1, "abi": abi, "resourceSha256": digest(archive), "resourceBytes": bytes_total,
        "pythonHome": "cpython313", "pythonLibrary": next(v["name"] for p, v in native.items() if p.name.startswith("libpython3.13.so")),
        "javaHome": jdk.relative_to(root).as_posix(), "jvmLibrary": "libjvm.so",
        "jdtLauncher": launcher.relative_to(root).as_posix(),
        "aliases": aliases, "libraries": list(native.values()),
        "packages": [{k: r.get(k) for k in ("Package", "Version", "SHA256", "Filename", "Homepage")} for r in records],
        "sourceOverrides": {"libandroid-spawn": {"revision": SPAWN_SOURCE_REV, "reason": "Rebuilt with 16KiB LOAD alignment", "ndk": "28.2.13676358"}},
    }
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Packaged {abi}: {len(native)} ELF files; resources {archive.stat().st_size / 2**20:.1f}MiB; 16KiB ELF: {sum(v['page16k'] for v in native.values())}/{len(native)}", flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], default="arm64-v8a")
    parser.add_argument("--download-only", action="store_true")
    parser.add_argument("--grammars-only", action="store_true")
    parser.add_argument("--python-prefix", type=Path)
    parser.add_argument("--native-only", action="store_true", help="Repackage ELF files/aliases while retaining an already verified resource ZIP")
    args = parser.parse_args()
    grammars()
    if args.grammars_only:
        return
    arch = "aarch64" if args.abi == "arm64-v8a" else "x86_64"
    lock = ROOT / f"tools/locks/{args.abi}.packages.json"
    if lock.exists():
        selected, mirror = json.loads(lock.read_text()), MIRRORS[0]
    else:
        records, mirror = index(arch)
        selected = closure(records, PACKAGES)
    print(f"{len(selected)} Android packages, {sum(int(r['Size']) for r in selected) / 2**20:.1f}MiB", flush=True)
    def download(r):
        p = fetch(mirror + "/" + r["Filename"], CACHE / arch / Path(r["Filename"]).name, r["SHA256"])
        print(f"Fetched {r['Package']} {r['Version']}", flush=True)
        return p
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        archives = list(pool.map(download, selected))
    root = TOOLCHAIN_CACHE / args.abi / "root"
    root.mkdir(parents=True, exist_ok=True)
    ownership_path = root.parent / "ownership.json"
    ownership = json.loads(ownership_path.read_text()) if ownership_path.exists() else {}
    for r, archive in zip(selected, archives):
        print(f"Unpacking {r['Package']}", flush=True)
        unpack_deb(archive, root, r, ownership)
    ownership_path.write_text(json.dumps(ownership))
    (root.parent / "packages.lock.json").write_text(json.dumps(selected, indent=2))
    if not args.download_only:
        package(root, args.abi, selected, args.python_prefix, args.native_only)

if __name__ == "__main__":
    main()

#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
abi="${1:-arm64-v8a}"
ndk_root="${2:?Pass the Linux Android NDK directory}"
python_version="3.13.12"
if [[ "$abi" == arm64-v8a ]]; then triple=aarch64-linux-android; else triple=x86_64-linux-android; fi
cache="$project_root/.downloads"
build_root="${MC_BUILD_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/mobilecode/build}"
work="$build_root/python-build-$abi"
host="$build_root/python-host"
archive="$cache/Python-$python_version.tar.xz"
mkdir -p "$cache" "$work" "$host"
if [[ ! -f "$archive" ]]; then
    curl -fsSL --retry 4 --retry-all-errors --connect-timeout 20 --max-time 600 \
        "https://www.python.org/ftp/python/$python_version/Python-$python_version.tar.xz" -o "$archive.part"
    mv "$archive.part" "$archive"
fi
python_checksum="$(sha256sum "$archive")"
if [[ "${python_checksum%% *}" != 2a84cd31dd8d8ea8aaff75de66fc1b4b0127dd5799aa50a64ae9a313885b4593 ]]; then
    echo 'CPython source SHA256 mismatch' >&2
    exit 1
fi
source_dir="$work/Python-$python_version"
if [[ ! -d "$source_dir" ]]; then tar -xf "$archive" -C "$work"; fi
if [[ ! -x "$host/bin/python3.13" ]]; then
    mkdir -p "$work/host"
    cd "$work/host"
    "$source_dir/configure" --prefix="$host" --without-ensurepip > configure.log 2>&1
    make -j"$(nproc)" > build.log 2>&1
    make install > install.log 2>&1
fi
mkdir -p "$work/target"
cd "$work/target"
toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$toolchain/bin:$PATH"
export CC="$toolchain/bin/${triple}29-clang"
export CXX="$toolchain/bin/${triple}29-clang++"
export AR="$toolchain/bin/llvm-ar"
export RANLIB="$toolchain/bin/llvm-ranlib"
export READELF="$toolchain/bin/llvm-readelf"
export PKG_CONFIG_LIBDIR=/nonexistent
deps="${MC_TOOLCHAIN_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/mobilecode/toolchains}/$abi/root/usr"
export CFLAGS="-O2 -fPIC -I$deps/include"
export LDFLAGS="-L$deps/lib -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
export ac_cv_file__dev_ptmx=no ac_cv_file__dev_ptc=no
"$source_dir/configure" --host="$triple" --build=x86_64-pc-linux-gnu \
    --prefix=/mobilecode/cpython313 --with-build-python="$host/bin/python3.13" \
    --enable-shared --without-ensurepip --disable-test-modules \
    --with-openssl="$deps" > configure.log 2>&1
make -j"$(nproc)" > build.log 2>&1
make DESTDIR="$work/stage" install > install.log 2>&1
echo "Python prefix: $work/stage/mobilecode/cpython313"

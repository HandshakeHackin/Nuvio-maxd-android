#!/usr/bin/env python3
"""Build the checksum-pinned TLS/HTTP dependencies for the shared Max'd core."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
NDK_VERSION = "27.2.12479018"


def run(arguments, cwd=None, env=None):
    subprocess.run([str(value) for value in arguments], cwd=cwd, env=env, check=True)


def sources(cache):
    manifest = json.loads((ROOT / "native-dependencies.json").read_text())
    for name, pin in manifest.items():
        archive = cache / "downloads" / (name + ".tar")
        archive.parent.mkdir(parents=True, exist_ok=True)
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != pin["sha256"]:
            temporary = archive.with_suffix(".part")
            with urllib.request.urlopen(pin["url"], timeout=90) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != pin["sha256"]:
                temporary.unlink()
                raise RuntimeError("Native source checksum mismatch: " + name)
            temporary.replace(archive)
        target = cache / "sources" / name
        marker = target / ".source-sha256"
        if target.exists() and (not marker.exists() or marker.read_text().strip() != pin["sha256"]):
            shutil.rmtree(target)
        if not target.exists():
            unpack = cache / "sources" / (name + "-unpack")
            unpack.mkdir(parents=True, exist_ok=True)
            with tarfile.open(archive) as source:
                source.extractall(unpack, filter="data")
            children = list(unpack.iterdir())
            if len(children) != 1 or not children[0].is_dir():
                raise RuntimeError("Unexpected source archive layout: " + name)
            children[0].rename(target)
            unpack.rmdir()
            marker.write_text(pin["sha256"] + "\n")
        print("Verified native source: " + name, flush=True)


def build(sdk, cache, abis, jobs):
    sources(cache)
    ndk = sdk / "ndk" / NDK_VERSION
    cmake_dir = sdk / "cmake" / "3.22.1" / "bin"
    for abi in abis:
        prefix = cache / "prefix" / abi
        marker = prefix / "complete.json"
        pins = json.loads((ROOT / "native-dependencies.json").read_text())
        stamp = hashlib.sha256(json.dumps({name:pins[name] for name in ("openssl","curl")},sort_keys=True).encode() + NDK_VERSION.encode()).hexdigest()
        if marker.exists() and json.loads(marker.read_text()).get("sourceStamp") == stamp:
            print("Native dependencies ready: " + abi, flush=True)
            continue
        work = cache / "build" / abi
        openssl_work = work / "openssl"
        openssl_work.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env["ANDROID_NDK_ROOT"] = str(ndk)
        env["PATH"] = str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin") + os.pathsep + env["PATH"]
        target = {"arm64-v8a": "android-arm64", "armeabi-v7a": "android-arm", "x86_64": "android-x86_64"}[abi]
        run(["perl", cache / "sources/openssl/Configure", target, "-D__ANDROID_API__=26", "no-shared", "no-tests",
             "--prefix=" + str(prefix), "--libdir=lib"], cwd=openssl_work, env=env)
        run(["make", "-j" + str(jobs), "build_libs"], cwd=openssl_work, env=env)
        run(["make", "install_dev"], cwd=openssl_work, env=env)
        curl_work = work / "curl"
        run([cmake_dir / "cmake", "-S", cache / "sources/curl", "-B", curl_work, "-G", "Ninja",
             "-DCMAKE_MAKE_PROGRAM=" + str(cmake_dir / "ninja"),
             "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
             "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-26", "-DCMAKE_BUILD_TYPE=Release",
             "-DCMAKE_INSTALL_PREFIX=" + str(prefix), "-DBUILD_CURL_EXE=OFF", "-DBUILD_SHARED_LIBS=OFF",
             "-DBUILD_TESTING=OFF", "-DCURL_USE_OPENSSL=ON", "-DOPENSSL_USE_STATIC_LIBS=TRUE",
             "-DOPENSSL_ROOT_DIR=" + str(prefix), "-DCURL_USE_LIBPSL=OFF", "-DCURL_USE_LIBSSH2=OFF",
             "-DOPENSSL_INCLUDE_DIR=" + str(prefix / "include"),
             "-DOPENSSL_CRYPTO_LIBRARY=" + str(prefix / "lib/libcrypto.a"),
             "-DOPENSSL_SSL_LIBRARY=" + str(prefix / "lib/libssl.a"),
             "-DCURL_ZSTD=OFF", "-DCURL_BROTLI=OFF", "-DCURL_CA_BUNDLE=none", "-DCURL_CA_PATH=none"], env=env)
        run([cmake_dir / "cmake", "--build", curl_work, "--parallel", jobs], env=env)
        run([cmake_dir / "cmake", "--install", curl_work], env=env)
        marker.write_text(json.dumps({"sourceStamp": stamp, "ndk": NDK_VERSION, "abi": abi}) + "\n")
        print("Built native dependencies: " + abi, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=pathlib.Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--abis", default="arm64-v8a,armeabi-v7a,x86_64")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if args.prepare_only:
        sources(ROOT / ".native-deps")
        raise SystemExit(0)
    if args.sdk_root is None:
        parser.error("Set ANDROID_HOME or pass --sdk-root")
    build(args.sdk_root.resolve(), ROOT / ".native-deps", args.abis.split(","), args.jobs)

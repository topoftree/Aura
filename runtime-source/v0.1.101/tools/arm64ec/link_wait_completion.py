#!/usr/bin/env python3
"""Finish build_wait_completion.py with the pinned Android ntsync dependency.

Consumes its reviewed build directory; links the native half of the private
opcode together with the matching PE output. Does not install runtime files.
Rust 1.90.0 and its aarch64-linux-android target must already be installed.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

from build_ntdll import extract, sha, relocate, NTSYNC_SHA, NTSYNC_COMMIT
from build_wait_completion import DISABLED, HERE
from audit_runtime_candidate import elf_info
from verify_ntdll_layout import inspect as inspect_layout

VENDOR_SHA = "0ae1dad4a5f62eb79ff50d48b8793a1c310c4ffd69bc0359d6c5663297340441"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ("work", "ntsync-source", "ntsync-vendor", "ndk", "mingw", "cargo"):
        p.add_argument("--" + key, type=Path, required=True)
    a = p.parse_args()
    work = a.work.resolve()
    source, native, output = [work / name for name in ("source", "native", "output")]
    candidate = json.loads((output / "build.json").read_text())
    assert candidate["schema"] == "WINE_WAIT_COMPLETION_BUILD_V1"
    assert sha(a.ntsync_source) == NTSYNC_SHA and sha(a.ntsync_vendor) == VENDOR_SHA
    assert "Pkg.Revision = 28.2.13676358" in (a.ndk / "source.properties").read_text()
    fixed = output / "fixed/i386-windows/ntdll.dll"
    assert sha(fixed) == candidate["peFiles"]["fixed"]["i386"]["sha256"]
    ntsync, deps = work / "ntsync", work / "deps/lib"
    ntsync.mkdir(exist_ok=False)
    deps.mkdir(parents=True, exist_ok=True)
    extract(a.ntsync_source, ntsync)
    with tarfile.open(a.ntsync_vendor) as archive:
        archive.extractall(ntsync, filter="data")
    tc = a.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    env = os.environ.copy()
    env.update(PATH=str(a.mingw.resolve()) + os.pathsep + env["PATH"], SOURCE_DATE_EPOCH="0",
               XDG_CACHE_HOME=str(work / "cache"), PKG_CONFIG_PATH="",
               PKG_CONFIG_LIBDIR=str(work / "empty-pkg-config"))
    for key, value in dict(CC="aarch64-linux-android34-clang", CXX="aarch64-linux-android34-clang++",
                           AS="aarch64-linux-android34-clang", AR="llvm-ar",
                           RANLIB="llvm-ranlib", STRIP="llvm-strip").items():
        env[key] = str(tc / value)
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = env["CC"]
    env["AR_aarch64_linux_android"] = env["AR"]
    env["CFLAGS"] = (
        "-g -O2 -fPIC -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES -Wno-declaration-after-statement "
        "-Wno-implicit-function-declaration -Wno-int-conversion "
        f"-ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={native}=/aura/wine-build")
    env["LDFLAGS"] = f"-L{deps} -Wl,-rpath,/data/data/com.android.aura/f/r/lib -Wl,-z,max-page-size=16384"
    with (work / "link.log").open("w") as log:
        def run(args, cwd):
            subprocess.run([str(v) for v in args], cwd=cwd, env=env, check=True,
                           stdout=log, stderr=subprocess.STDOUT)
        run([a.cargo, "+1.90.0", "build", "--offline", "--release", "--locked",
             "--target", "aarch64-linux-android"], ntsync)
        shutil.copyfile(ntsync / "target/aarch64-linux-android/release/libntsync_android.a",
                        deps / "libntsync_android.a")
        prefix = "/data/data/com.android.aura/f/opt/wine"
        run([source / "configure", "--no-create", "--host=aarch64-linux-android34",
             "--enable-win64", "--enable-archs=arm64ec,aarch64", "--with-mingw=clang",
             "--with-wine-tools=" + str(work / "pe"), f"--prefix={prefix}", f"--exec-prefix={prefix}",
             f"--bindir={prefix}/bin", f"--libdir={prefix}/lib", f"--datadir={prefix}/share",
             "--enable-wineandroid_drv=no", "--disable-nls", "--without-unwind", "--without-hwloc",
             "--without-inotify", "--without-udev", "--without-usb", "--without-ffmpeg", *DISABLED], native)
        status = native / "config.status"
        text = status.read_text()
        assert text.count('" -C -i') == 1
        status.write_text(text.replace('" -C -i', '" -C "SUBDIRS=include dlls/ntdll" -i'))
        run(["sh", "./config.status"], native)
        run(["make", "-j4", "dlls/ntdll/ntdll.so"], native)
        symbols = output / "ntdll-symbols.so"
        symbols.write_bytes(relocate((native / "dlls/ntdll/ntdll.so").read_bytes()))
        target = output / "ntdll.so"
        shutil.copyfile(symbols, target)
        run([tc / "llvm-strip", "--strip-debug", target], native)
    info = elf_info(target.read_bytes())
    assert info["machine"] == "0xb7" and info["abi"] == "bionic"
    assert set(info["needed"]) == {"libc.so", "libdl.so"}
    assert info["minimumLoadAlignment"] >= 16384
    assert info["searchPaths"] == ["/data/data/com.android.aura/f/r/lib"]
    manifest = json.loads((HERE.parents[1] / "engine/src/main/assets/arm64ec/manifest.json").read_text())
    layout = inspect_layout(target.read_bytes(), manifest["files"])
    assert layout["passed"]
    protocol = (source / "include/wine/server_protocol.h").read_text()
    signal = (source / "dlls/ntdll/unix/signal_arm64.c").read_text()
    assert "SELECT_COOPERATIVE_SUSPEND" in protocol
    assert "SELECT_INTERRUPTIBLE | SELECT_COOPERATIVE_SUSPEND" in signal
    assert "*chpe->SuspendDoorbell = -1;" in signal
    assert "0x30" in (HERE / "ntdll/initial-ec-stack.patch").read_text()
    metadata = dict(
        schema="WINE_WAIT_COMPLETION_LINK_V1", revision="11.0-2-aura-wait-completion1",
        candidate=candidate, candidateRecordSha256=sha(output / "build.json"),
        nativeLinked=True, privateUnixOpcode=14, unixTableEntries=15,
        ntsyncSourceCommit=NTSYNC_COMMIT, ntsyncSourceSha256=NTSYNC_SHA,
        ntsyncVendorSha256=VENDOR_SHA, ntsyncArchiveSha256=sha(deps / "libntsync_android.a"),
        rust="1.90.0", ndk="28.2.13676358", abi="android-aarch64-api34",
        linkerScriptSha256=sha(Path(__file__)), nativeSha256=sha(target),
        nativeBytes=target.stat().st_size, symbolsSha256=sha(symbols), elf=info,
        compiledLayout=layout, initialEcStackPatchSha256=sha(HERE / "ntdll/initial-ec-stack.patch"),
        sourceFiles={name: sha(source / "dlls/ntdll" / name) for name in
                     ("sync.c", "unixlib.h", "unix/sync.c", "unix/loader.c",
                      "unix/unix_private.h", "unix/signal_arm64.c")},
        cooperativeSuspendPreserved=True, initialArm64EcStackReserve=48,
        runtimePackaged=False, androidDeviceTested=False)
    (output / "link.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({k: metadata[k] for k in ("nativeSha256", "nativeBytes", "nativeLinked", "androidDeviceTested")}))


if __name__ == "__main__":
    main()

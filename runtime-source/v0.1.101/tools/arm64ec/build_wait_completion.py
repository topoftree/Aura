#!/usr/bin/env python3
"""Cross-compile the wait-completion candidate without installing it.

Builds baseline/fixed i386 and ARM64X PE DLLs and the two changed Android native
objects. Native linking/packaging requires the pinned ntsync Rust archive and is
deliberately a separate step: a new PE DLL must never run with an old Unix table.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from build_ntdll import extract, sha, SOURCE_SHA, SOURCE_COMMIT
from build_sechost import TOOLCHAIN_SHA, TOOLCHAIN_URL
from build_wait_queue import SUBDIRS, PATCH_SHA
from pe_dependencies import PeImage

HERE = Path(__file__).resolve().parent
DISABLED = [
    "--disable-tests", "--disable-win16", "--without-x", "--without-wayland",
    "--without-gstreamer", "--without-vulkan", "--without-opengl", "--without-alsa",
    "--without-pulse", "--without-freetype", "--without-fontconfig",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "toolchain-archive", "mingw", "ndk", "work"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    assert sha(args.source) == SOURCE_SHA
    assert sha(args.toolchain_archive) == TOOLCHAIN_SHA
    assert "Pkg.Revision = 28.2.13676358" in (args.ndk / "source.properties").read_text()
    lifetime = HERE / "wait-queue/wine-wait-queue-lifetime.patch"
    candidate = HERE / "wait-completion/wine-wait-completion.patch"
    assert sha(lifetime) == PATCH_SHA
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    source, pe_build, native_build, output = [work / name for name in ("source", "pe", "native", "output")]
    for directory in (source, pe_build, native_build, output):
        directory.mkdir()
    extract(args.source, source, wine=True)
    mingw = args.mingw.resolve()
    env = os.environ.copy()
    env.update(PATH=str(mingw) + os.pathsep + env["PATH"], CC="gcc", CXX="g++",
               SOURCE_DATE_EPOCH="0", XDG_CACHE_HOME=str(work / "cache"),
               PKG_CONFIG_PATH="", PKG_CONFIG_LIBDIR=str(work / "empty-pkg-config"),
               CFLAGS=f"-O2 -ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={pe_build}=/aura/wine-build",
               CROSSCFLAGS=f"-g0 -O2 -ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={pe_build}=/aura/wine-build")
    patch_list = (source / "build-scripts/build-step-arm64ec.sh").read_text().split("PATCHES=(", 1)[1].split("\n    )", 1)[0]
    vendor = ["server_protocol.def.patch"] + re.findall(r'^\s*"([^"\n]+\.patch)"', patch_list, re.M)
    assert len(vendor) > 40 and vendor[-1] == "ntsync_userspace.patch"
    records = {}
    with (work / "build.log").open("w") as log:
        def run(command, cwd=source, environment=env):
            subprocess.run([str(x) for x in command], cwd=cwd, env=environment,
                           stdout=log, stderr=subprocess.STDOUT, check=True)

        def configure_status(build, subdirs):
            status = build / "config.status"
            text = status.read_text()
            needle = '" -C -i'
            assert text.count(needle) == 1
            status.write_text(text.replace(needle, f'" -C "SUBDIRS={subdirs}" -i'))

        for name in vendor:
            run(["git", "apply", source / "android/patches" / name])
        run(["git", "apply", lifetime])
        for command in (["tools/make_requests"], ["tools/make_specfiles"],
                        ["python3", "dlls/winevulkan/make_vulkan", "-x", "vk.xml", "-X", "video.xml"],
                        ["autoconf"], ["autoheader"]):
            run(command)
        run([source / "configure", "--no-create", "--enable-win64",
             "--enable-archs=i386,arm64ec,aarch64", "--with-mingw=clang", *DISABLED], pe_build)
        configure_status(pe_build, SUBDIRS)
        run(["sh", "./config.status"], pe_build)
        arches = ("i386", "aarch64")
        targets = [f"dlls/ntdll/{arch}-windows/ntdll.dll" for arch in arches]
        for stage in ("baseline", "fixed"):
            if stage == "fixed":
                run(["git", "apply", candidate])
            run(["make", "-j4", *targets], pe_build)
            records[stage] = {}
            for arch, target in zip(arches, targets):
                destination = output / stage / f"{arch}-windows/ntdll.dll"
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(pe_build / target, destination)
                run([mingw / "llvm-strip", "--strip-debug", destination])
                image = PeImage(destination.read_bytes())
                records[stage][arch] = dict(bytes=destination.stat().st_size,
                                           sha256=sha(destination), machine=image.info["machine"])
        for arch in arches:
            before = PeImage((output / f"baseline/{arch}-windows/ntdll.dll").read_bytes())
            after = PeImage((output / f"fixed/{arch}-windows/ntdll.dll").read_bytes())
            assert before.info == after.info
            for a, b in ((before, after), (before.ec_view(), after.ec_view())):
                assert a.exports() == b.exports()
                assert a.imports() == b.imports()
        assert records["fixed"]["aarch64"] == records["baseline"]["aarch64"]
        assert records["fixed"]["i386"]["sha256"] != records["baseline"]["i386"]["sha256"]

        ndk = args.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        native_env = env.copy()
        for key, value in dict(CC="aarch64-linux-android34-clang", CXX="aarch64-linux-android34-clang++",
                               AS="aarch64-linux-android34-clang", AR="llvm-ar",
                               RANLIB="llvm-ranlib", STRIP="llvm-strip").items():
            native_env[key] = str(ndk / value)
        native_env["CFLAGS"] = (
            "-g -O2 -fPIC -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES -Wno-declaration-after-statement "
            "-Wno-implicit-function-declaration -Wno-int-conversion "
            f"-ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={native_build}=/aura/wine-build"
        )
        native_env["LDFLAGS"] = "-Wl,-z,max-page-size=16384"
        prefix = "/data/data/com.android.aura/f/opt/wine"
        run([source / "configure", "--no-create", "--host=aarch64-linux-android34",
             "--enable-win64", "--enable-archs=arm64ec,aarch64", "--with-mingw=clang",
             "--with-wine-tools=" + str(pe_build), f"--prefix={prefix}", f"--exec-prefix={prefix}",
             f"--bindir={prefix}/bin", f"--libdir={prefix}/lib", f"--datadir={prefix}/share",
             "--enable-wineandroid_drv=no", "--without-unwind", "--without-hwloc", "--without-inotify",
             "--without-udev", "--without-usb", "--without-ffmpeg", *DISABLED],
            native_build, native_env)
        configure_status(native_build, "include dlls/ntdll")
        run(["sh", "./config.status"], native_build, native_env)
        run(["git", "apply", HERE / "ntdll/initial-ec-stack.patch"])
        native_targets = ["dlls/ntdll/unix/sync.o", "dlls/ntdll/unix/loader.o"]
        run(["make", "-j4", *native_targets], native_build, native_env)
        native_objects = {}
        for target in native_targets:
            path = native_build / target
            assert path.read_bytes()[:4] == b"\x7fELF"
            native_objects[target] = dict(sha256=sha(path), bytes=path.stat().st_size)
        assert native_objects
    metadata = dict(
        schema="WINE_WAIT_COMPLETION_BUILD_V1", sourceCommit=SOURCE_COMMIT, sourceSha256=SOURCE_SHA,
        toolchainUrl=TOOLCHAIN_URL, toolchainSha256=TOOLCHAIN_SHA,
        vendorPatches=[dict(file=name, sha256=sha(source / "android/patches" / name)) for name in vendor],
        lifetimePatchSha256=PATCH_SHA, candidatePatchSha256=sha(candidate),
        buildScriptSha256=sha(Path(__file__)), peFiles=records, nativeObjects=native_objects,
        appendedPrivateUnixOpcode=14, publicExportsAndImportsUnchanged=True,
        arm64xPeUnchanged=True, nativeLinked=False, runtimePackaged=False,
        androidGameExecuted=False, requiredBeforePackaging=[
            "Link and verify native ntdll with pinned ntsync and existing stack patch.",
            "Package/update the new native and i386 components as a compatible pair.",
            "Update fixed-layout diagnostic fingerprints; never read stale offsets.",
            "Verify the freeze on Android with the same saved game and settings.",
        ])
    (output / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({key: metadata[key] for key in (
        "schema", "peFiles", "nativeObjects", "nativeLinked", "runtimePackaged", "androidGameExecuted"
    )}, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Reproduce the shipped PE baseline, then build the opt-in Ground Zeroes adapter.

Only ARM64X PE ntdll changes. i386 PE must reproduce byte for byte; Android
native ntdll and FEX are neither rebuilt nor modified. No game is executed.
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
    for name in ("source", "toolchain-archive", "mingw", "work"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    assert sha(args.source) == SOURCE_SHA
    assert sha(args.toolchain_archive) == TOOLCHAIN_SHA
    lifetime = HERE / "wait-queue/wine-wait-queue-lifetime.patch"
    completion = HERE / "wait-completion/wine-wait-completion.patch"
    candidate = HERE / "mgs-tick-thunk/wine-mgs-tick-thunk.patch"
    header = HERE / "mgs-tick-thunk/aura_mgs_tick_thunk.h"
    assert sha(lifetime) == PATCH_SHA
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    source, pe_build, output = [work / name for name in ("source", "pe", "output")]
    for directory in (source, pe_build, output):
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
        run(["git", "apply", completion])
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
                shutil.copyfile(header, source / "dlls/ntdll" / header.name)
                run(["git", "apply", candidate])
            # Keep the reproduced i386 payload. Recompiling loader.c there only
            # moves debug __LINE__ constants; the adapter is ARM64EC-only.
            run(["make", "-j4", *(targets if stage == "baseline" else [targets[1]])], pe_build)
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
        assert records["baseline"]["aarch64"]["sha256"] == "a7829bd582ee844519952ec17241ae403fd036960e4a2c69d772e13b57bb33c3"
        assert records["baseline"]["i386"]["sha256"] == "f69245fe5c63ec8a028f9f69c51b983f1e25569ec14429504491fd000ef46db0"
        assert records["fixed"]["i386"] == records["baseline"]["i386"]
        assert records["fixed"]["aarch64"]["sha256"] != records["baseline"]["aarch64"]["sha256"]

    metadata = dict(
        schema="WINE_MGS_TICK_THUNK_BUILD_V1", revision="11.0-2-aura-mgs-tick1",
        sourceCommit=SOURCE_COMMIT, sourceSha256=SOURCE_SHA,
        toolchainUrl=TOOLCHAIN_URL, toolchainSha256=TOOLCHAIN_SHA,
        vendorPatches=[dict(file=name, sha256=sha(source / "android/patches" / name)) for name in vendor],
        lifetimePatchSha256=PATCH_SHA, completionPatchSha256=sha(completion),
        candidatePatchSha256=sha(candidate), helperSha256=sha(header),
        buildScriptSha256=sha(Path(__file__)), peFiles=records,
        publicExportsAndImportsUnchanged=True, i386PeUnchanged=True,
        nativeAndFexUnchanged=True, defaultEnabled=False,
        gate="AURA_MGS_TICK_THUNK=1 AND main executable MgsGroundZeroes.exe",
        clockImplementation="original kernel32!GetTickCount", runtimePackaged=False,
        androidGameExecuted=False)
    (output / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({key: metadata[key] for key in (
        "schema", "revision", "peFiles", "i386PeUnchanged", "nativeAndFexUnchanged", "androidGameExecuted"
    )}, indent=2))


if __name__ == "__main__":
    main()

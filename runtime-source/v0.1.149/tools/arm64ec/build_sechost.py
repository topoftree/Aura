#!/usr/bin/env python3
"""Rebuild only Wine's ARM64X service DLL with direct rpcrt4 imports.

Host prerequisites: gcc, make, autoconf/autoheader, bison, flex, m4, Python.
Use the pinned LLVM-MinGW toolchain (not Android NDK clang). Sources remain
LGPL-2.1-or-later; the original Wine licence is copied alongside the result.
No Windows or Android executable is run by this build.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

from pe_dependencies import PeImage

SOURCE_SHA = "9776c760f250926d777224ed662a30b32a6a3fc241c680c58e280ffe1bcefae5"
SOURCE_URL = "https://codeload.github.com/The412Banner/proton-wine/tar.gz/256288853b206373cbf65b775143d33bb11035dd"
TOOLCHAIN_URL = "https://github.com/bylaws/llvm-mingw/releases/download/20250920/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64.tar.xz"
TOOLCHAIN_SHA = "8dd8c34fc051a50c2fae86015f35057f8aae93fe1e19b34537ef1269a8b4c772"
SUBDIRS = " ".join([
    "include", "dlls/winecrt0", "tools/widl", "tools/winebuild", "tools/winegcc", "tools/wrc",
    "libs/compiler-rt", "libs/musl", "dlls/ntdll", "dlls/kernel32", "dlls/kernelbase",
    "dlls/rpcrt4", "dlls/sechost", "dlls/msvcrt", "dlls/ucrtbase",
])


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Pinned Wine source tar.gz")
    parser.add_argument("--toolchain-archive", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path, help="New directory")
    args = parser.parse_args()
    assert digest(args.source) == SOURCE_SHA
    assert digest(args.toolchain_archive) == TOOLCHAIN_SHA
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    source, build, output = [work / name for name in ("source", "build", "output")]
    for directory in (source, build, output):
        directory.mkdir()
    with tarfile.open(args.source, "r:gz") as archive:
        for entry in archive:
            parts = Path(entry.name).parts[1:]
            if not parts or ".." in parts or parts[0] in ("android", ".github", ".git", "po") or "tests" in parts:
                continue
            if entry.isfile():
                target = source.joinpath(*parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(entry).read())
                target.chmod(entry.mode & 0o755)
    with tarfile.open(args.toolchain_archive, "r:xz") as archive:
        archive.extractall(work, filter="data")
    toolchain = work / "llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64/bin"
    environment = os.environ.copy()
    environment.update(PATH=str(toolchain) + os.pathsep + environment["PATH"],
                       SOURCE_DATE_EPOCH="0", XDG_CACHE_HOME=str(work / "cache"))
    # No source paths enter __FILE__ / debug output; strip debug information below.
    environment["CFLAGS"] = f"-O2 -ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={build}=/aura/wine-build"
    makefile = source / "dlls/sechost/Makefile.in"
    assert "IMPORTS   = kernelbase rpcrt4\n" in makefile.read_text()
    assert "DELAYIMPORTS" not in makefile.read_text()
    with (work / "build.log").open("w") as log:
        def run(command, cwd=source):
            subprocess.run(command, cwd=cwd, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
        run(["tools/make_requests"])
        run(["tools/make_specfiles"])
        run(["python3", "dlls/winevulkan/make_vulkan", "-x", "vk.xml", "-X", "video.xml"])
        run(["autoconf"])
        run(["autoheader"])
        run([str(source / "configure"), "--no-create", "--enable-win64", "--enable-archs=arm64ec,aarch64",
             "--with-mingw=clang", "--disable-tests", "--disable-win16", "--disable-nls",
             "--without-x", "--without-wayland", "--without-gstreamer", "--without-vulkan",
             "--without-opengl", "--without-alsa", "--without-pulse", "--without-freetype",
             "--without-fontconfig"], build)
        # Generate only this component's dependency graph. The upstream source
        # also contains unrelated Android drivers requiring additional SDKs.
        status = build / "config.status"
        original = status.read_text()
        needle = '"tools/makedep" -C -i'
        assert original.count(needle) == 1
        status.write_text(original.replace(needle, f'"tools/makedep" -C "SUBDIRS={SUBDIRS}" -i'))
        run(["sh", "./config.status"], build)
        run(["make", "-j4", "dlls/sechost/aarch64-windows/sechost.dll"], build)
        dll = output / "sechost.dll"
        shutil.copyfile(build / "dlls/sechost/aarch64-windows/sechost.dll", dll)
        run([str(toolchain / "llvm-strip"), "--strip-debug", str(dll)], build)
    data = dll.read_bytes()
    pe = PeImage(data)
    assert pe.info["chpeMetadata"] and pe.info["machine"] == "0xaa64"
    assert b"Wine builtin DLL" in data[:128]
    for view in (pe, pe.ec_view()):
        rpc = [i for i in view.imports() if i["module"] == "rpcrt4.dll"]
        assert len(rpc) == 1 and not rpc[0]["delayed"] and "NdrClientCall2" in rpc[0]["symbols"]
    shutil.copyfile(source / "COPYING.LIB", output / "COPYING.LIB")
    metadata = {
        "source": {"url": SOURCE_URL, "sha256": SOURCE_SHA},
        "toolchain": {"url": TOOLCHAIN_URL, "sha256": TOOLCHAIN_SHA},
        "fixReference": "https://github.com/GameNative/proton-wine/commit/5c8e34567130bea90a695be51fa581f26e1267c9",
        "llvmBugReference": "https://github.com/llvm/llvm-project/commit/16bd5ce1184bb5683ea96290478e5912cf2975b9",
        "file": "sechost.dll", "bytes": len(data), "sha256": digest(dll),
        "architectures": ["ARM64", "ARM64EC"], "rpcImport": "DIRECT",
        "androidOrSteamTested": False,
    }
    (output / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()

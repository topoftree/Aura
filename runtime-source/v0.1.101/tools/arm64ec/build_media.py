#!/usr/bin/env python3
"""Cross-build the missing GStreamer codecs against Aura's pinned Bionic ABI.

Requires Python with meson==1.9.1, ninja==1.13.0 and zstandard; NDK 28.2.13676358.
Only host build tools execute. Android code is never executed by this script.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tarfile

from mesonbuild.scripts.depfixer import fix_rpath

REPO = Path(__file__).resolve().parents[2]
META = Path(__file__).with_name("media")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_path(root, name):
    parts = PurePosixPath(name)
    if parts.is_absolute() or ".." in parts.parts:
        raise ValueError(name)
    return root / name


def extract_sysroot(archive, root):
    prefix = "data/data/com.termux/files/usr/"
    links = []
    with tarfile.open(archive) as source:
        for item in source:
            if not item.name.startswith(prefix):
                continue
            name = item.name[len(prefix):]
            wanted = name.startswith(("include/gstreamer-1.0/", "include/glib-2.0/",
                "include/libav", "include/libsw", "lib/pkgconfig/", "lib/glib-2.0/")) or (
                name.startswith("lib/") and "/" not in name[4:] and ".so" in name)
            if not wanted:
                continue
            target = checked_path(root, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            if item.isfile():
                with source.extractfile(item) as data, target.open("wb") as output:
                    shutil.copyfileobj(data, output)
            elif item.issym():
                links.append((target, Path(item.linkname).name))
    # Some unversioned names precede the intermediate SONAME symlink in the tar.
    for _ in range(4):
        for target, name in links:
            if not target.exists() and (target.parent / name).is_file():
                target.symlink_to(name)
    for pc in (root / "lib/pkgconfig").glob("*.pc"):
        pc.write_text(pc.read_text().replace("/data/data/com.termux/files/usr", str(root)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    inputs = json.loads((META / "sources.json").read_text())
    cached = {}
    for key, pin in inputs.items():
        path = args.cache / pin["file"]
        if not path.exists() and key == "gstreamer":
            path = META / pin["file"]
        assert path.stat().st_size == pin["bytes"] and sha(path) == pin["sha256"], key
        cached[key] = path
    source = work / "source"
    with tarfile.open(cached["gstreamer"]) as archive:
        for item in archive:
            name = item.name.removeprefix("gstreamer-1.28.0/")
            if item.isfile() and name.startswith(("subprojects/gst-libav/", "subprojects/gst-plugins-bad/", "scripts/")):
                path = checked_path(source, name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.extractfile(item).read())
                path.chmod(item.mode & 0o777)
    subprocess.run(["patch", "-p1", "--batch", "-i", str(META / "omit-video-filters.patch")], cwd=source, check=True)
    root = work / "sysroot"
    extract_sysroot(cached["termux"], root)
    host = work / "host-bin"
    host.mkdir()
    enums = host / "glib-mkenums"
    enums.write_text(cached["glib-mkenums"].read_text().replace("@PYTHON@", sys.executable).replace("@VERSION@", "2.86.3"))
    enums.chmod(0o755)
    env = os.environ.copy()
    env["PATH"] = str(host) + os.pathsep + env["PATH"]
    tc = args.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    assert "28.2.13676358" in (args.ndk / "source.properties").read_text()
    cross = work / "android.cross"
    cross.write_text(f"""[binaries]
c = '{tc}/aarch64-linux-android34-clang'
cpp = '{tc}/aarch64-linux-android34-clang++'
ar = '{tc}/llvm-ar'
strip = '{tc}/llvm-strip'
pkg-config = '{shutil.which('pkg-config')}'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['{root}/lib/pkgconfig']
[built-in options]
c_args = ['-ffile-prefix-map={work}=/aura-media-build']
cpp_args = ['-ffile-prefix-map={work}=/aura-media-build']
c_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1']
cpp_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1']
""")
    meson = [sys.executable, "-m", "mesonbuild.mesonmain"]
    common = ["--cross-file", str(cross), "--buildtype=release", "--strip", "--wrap-mode=nofallback",
        "-Dtests=disabled", "-Ddoc=disabled", "-Dpackage-name=Aura", "-Dpackage-origin=https://github.com/topoftree/winlator"]
    for name, project, options, target in [
        ("libav", "gst-libav", [], None),
        ("parsers", "gst-plugins-bad", ["-Dauto_features=disabled", "-Dvideoparsers=enabled",
            "-Dtools=disabled", "-Dexamples=disabled", "-Dintrospection=disabled", "-Dorc=disabled", "-Dgpl=disabled"], "gstvideoparsersbad"),
    ]:
        build = work / ("build-" + name)
        with (work / (name + "-configure.log")).open("w") as log:
            subprocess.run(meson + ["setup", str(build), str(source / "subprojects" / project)] + common + options,
                env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        with (work / (name + "-build.log")).open("w") as log:
            subprocess.run(meson + ["compile", "-C", str(build), "-j", "4"] + ([target] if target else []),
                env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    files = {}
    paths = {
        "usr/lib/gstreamer-1.0/libgstlibav.so": "build-libav/ext/libav/libgstlibav.so",
        "usr/lib/gstreamer-1.0/libgstvideoparsersbad.so": "build-parsers/gst/videoparsers/libgstvideoparsersbad.so",
        "usr/lib/libgstcodecparsers-1.0.so": "build-parsers/gst-libs/gst/codecparsers/libgstcodecparsers-1.0.so",
    }
    for logical, relative in paths.items():
        binary = work / "payload" / logical
        binary.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(work / relative, binary)
        # Apply Meson's normal installation cleanup without installing unrelated bad plugins.
        remove = {str(root / "lib").encode(), str(work / "build-parsers/gst-libs/gst/codecparsers").encode(),
            b"$ORIGIN/../../gst-libs/gst/codecparsers"}
        fix_rpath(str(binary), remove, b"", "/" + logical, {}, verbose=False)
        subprocess.run([str(tc / "llvm-strip"), "--strip-unneeded", str(binary)], check=True)
        files[logical] = {"bytes": binary.stat().st_size, "sha256": sha(binary)}
    report = {"schema": 1, "gstreamer": "1.28.0", "abi": "android-aarch64-api34", "ndk": "28.2.13676358",
        "meson": "1.9.1", "ninja": "1.13.0", "sources": inputs, "files": files,
        "patch_sha256": sha(META / "omit-video-filters.patch"), "build_script_sha256": sha(Path(__file__)),
        "limitations": "Cross-compiled and statically checked; no Android playback validation."}
    (work / "build.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report["files"], indent=2))


if __name__ == "__main__":
    main()

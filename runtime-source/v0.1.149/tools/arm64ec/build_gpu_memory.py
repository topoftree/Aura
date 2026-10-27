#!/usr/bin/env python3
"""Rebuild win32u from the reviewed Wine 11.0-2 completion source/configuration.

The base is produced by build_wait_completion.py + link_wait_completion.py.
Reuse its native configure result/host tools, not its ntdll-only feature set:
enable the four original dynamically loaded font/graphics providers explicitly.
Build both variants, change only sysparams.c for the second link, and retain the
current packaged ntdll as a link dependency. No game or Android code is executed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

from build_ntdll import sha, relocate, SOURCE_COMMIT
from audit_runtime_candidate import elf_info

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).with_name("gpu-memory")
SYS_SHA = "0545c2b1233d7a85d3fca6dd27c3748b57cf08ee07b1359a4491ee2918ced61c"
SOURCE_INPUT_SHA = "ebfa5ddd165669078deb3bc15097557a29255f9cc1684b571f8408332ed3ef41"
OLD_SHA = "ff422e00fb0be1505f33e51a4fe38edef0ae0ae684c9bf99313dc7082c482672"
HEADERS = {
    "freetype": ("bc5c898e4756d373e0d991bab053036c5eb2aa7c0d5c67e8662ddc6da40c4103",
                 "https://codeload.github.com/freetype/freetype/tar.gz/refs/tags/VER-2-13-3"),
    "fontconfig": ("cdebb4b805d33e9bdefcc0ef9743db638d2acb21139bbe1a6a85878d4c3e8c9e",
                   "https://codeload.github.com/fontconfig/fontconfig/tar.gz/refs/tags/2.15.0"),
}
PROVIDERS = dict(SONAME_LIBFREETYPE='"libfreetype.so"', SONAME_LIBFONTCONFIG='"libfontconfig.so"',
                 SONAME_LIBEGL='"libEGL.so.1"', SONAME_LIBVULKAN='"libvulkan.so.1"', HAVE_FT2BUILD_H="1")


def symbols(path):
    rows = [line.split() for line in subprocess.check_output(["readelf", "--dyn-syms", "-W", path], text=True).splitlines()]
    rows = [r for r in rows if len(r) >= 8 and r[4] in ("GLOBAL", "WEAK")]
    return {kind: sorted({r[7].split("@")[0] for r in rows if (r[6] == "UND") == (kind == "imports")})
            for kind in ("imports", "exports")}


def input_hash(source):
    digest = hashlib.sha256()
    for directory in ("dlls/win32u", "include"):
        for path in sorted((source / directory).rglob("*")):
            if path.is_file():
                digest.update(path.relative_to(source).as_posix().encode() + b"\0" +
                              hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def import_versions(path):
    rows = [line.split() for line in subprocess.check_output(["readelf", "--dyn-syms", "-W", path], text=True).splitlines()]
    return {r[7].split("@")[0]: r[7] for r in rows if len(r) >= 8 and r[4] in ("GLOBAL", "WEAK") and r[6] == "UND"}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "work", "ndk", "freetype", "fontconfig"):
        p.add_argument("--" + name, required=True, type=Path)
    p.add_argument("--baseline-native", required=True, type=Path,
                   help="Original SHA-pinned win32u.so, retained from the previous runtime/APK for ABI comparison")
    a = p.parse_args()
    base, work = a.base.resolve(), a.work.resolve()
    assert sha(base / "source/dlls/win32u/sysparams.c") == SYS_SHA
    assert input_hash(base / "source") == SOURCE_INPUT_SHA, "Win32u source/header input changed"
    assert json.loads((base / "output/link.json").read_text())["revision"] == "11.0-2-aura-wait-completion1"
    assert "Pkg.Revision = 28.2.13676358" in (a.ndk / "source.properties").read_text()
    work.mkdir(parents=True, exist_ok=False)
    source, native, output = (work / name for name in ("source", "native", "output"))
    shutil.copytree(base / "source", source)
    native.mkdir(); output.mkdir()
    for name, (expected, _) in HEADERS.items():
        archive = getattr(a, name)
        assert sha(archive) == expected
        with tarfile.open(archive) as tar:
            for entry in tar:
                parts = Path(entry.name).parts[1:]
                if not parts or ".." in parts or parts[0] not in ("include", "fontconfig") or not entry.isfile():
                    continue
                target = work / name / Path(*parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(entry).read())
    manifest = json.loads((ROOT / "engine/src/main/assets/arm64ec/manifest.json").read_text())
    digest = manifest["files"]["opt/wine/lib/wine/aarch64-unix/ntdll.so"]["sha256"]
    dependency = ROOT / "engine/src/main/jniLibs/arm64-v8a" / ("libaura_" + digest[:24] + ".so")
    assert sha(dependency) == digest
    old = a.baseline_native.resolve()
    assert sha(old) == OLD_SHA
    config = (base / "native/config.status").read_text()
    config = config.replace(str(base / "source"), str(source)).replace(str(base / "native"), str(native))
    assert config.count("SUBDIRS=include dlls/ntdll") == 1
    config = config.replace("SUBDIRS=include dlls/ntdll", "SUBDIRS=include dlls/ntdll dlls/win32u")
    # Configure contains the compiler's absolute path; constrain it to the pinned NDK.
    tc = a.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    assert str(tc / "aarch64-linux-android34-clang") in config
    (native / "config.status").write_text(config)
    env = dict(os.environ, SOURCE_DATE_EPOCH="0")
    outputs = {}
    with (work / "build.log").open("w") as log:
        def run(command, cwd=native):
            subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True,
                           stdout=log, stderr=subprocess.STDOUT)
        run(["sh", "./config.status"])
        cfg = native / "include/config.h"
        text = cfg.read_text()
        for key, value in PROVIDERS.items():
            assert "/* #undef " + key + " */" in text
            text = text.replace("/* #undef " + key + " */", "#define " + key + " " + value)
        cfg.write_text(text)
        (native / "dlls/ntdll").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(dependency, native / "dlls/ntdll/ntdll.so")
        flags = ("-g -O2 -fPIC -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES -Wno-declaration-after-statement "
                 "-Wno-implicit-function-declaration -Wno-int-conversion "
                 f"-ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={native}=/aura/wine-build "
                 f"-I{work}/freetype/include -I{work}/fontconfig")
        for variant in ("baseline", "fixed"):
            if variant == "fixed":
                run(["patch", "--batch", "--fuzz=0", "-p1", "-i", META / "wine-gpu-memory.patch"], source)
            run(["make", "-j4", "-o", "dlls/ntdll/ntdll.so", "CFLAGS=" + flags, "dlls/win32u/win32u.so"])
            symbols_path = output / (variant + "-symbols.so")
            symbols_path.write_bytes(relocate((native / "dlls/win32u/win32u.so").read_bytes()))
            target = output / (variant + ".so")
            shutil.copyfile(symbols_path, target)
            run([tc / "llvm-strip", "--strip-debug", target])
            outputs[variant] = dict(bytes=target.stat().st_size, sha256=sha(target), symbolsSha256=sha(symbols_path))
            assert symbols(target) == symbols(old), "Native public ABI changed"
            previous, current = import_versions(old), import_versions(target)
            version_changes = {key: [previous.get(key), current.get(key)] for key in sorted(previous | current)
                               if previous.get(key) != current.get(key)}
            # NDK 28 records the API-30 version for this already-used libc symbol.
            # The application's minimum is API 34. No other import ABI may drift.
            assert version_changes == {"memfd_create": ["memfd_create", "memfd_create@LIBC_R"]}
            info = elf_info(target.read_bytes())
            assert info["machine"] == "0xb7" and info["abi"] == "bionic"
            assert set(info["needed"]) == {"ntdll.so", "libm.so", "libdl.so", "libc.so"}
            assert info["minimumLoadAlignment"] >= 16384
            for value in PROVIDERS.values():
                if value.startswith('"'): assert value.strip('"').encode() + b"\0" in target.read_bytes()
    record = dict(schema="WINE_GPU_MEMORY_BUILD_V1", revision="11.0-2-aura-gpu-memory1",
        sourceCommit=SOURCE_COMMIT, baselineSourceSha256=SYS_SHA, sourceAndHeadersSha256=SOURCE_INPUT_SHA,
        packagedBeforeSha256=OLD_SHA,
        baseConfigurationSha256=sha(base / "native/config.status"), sourcePatchSha256=sha(META / "wine-gpu-memory.patch"),
        builderSha256=sha(Path(__file__)), ndk="28.2.13676358", androidApi=34,
        headers={name: dict(sha256=s, url=u) for name, (s, u) in HEADERS.items()},
        outputs=outputs, providers=PROVIDERS, nativeCompanionSha256=digest, elf=info,
        samePublicImportsAndExports=True, importVersionChanges=version_changes, peAndNtdllUnchanged=True,
        originalToolchainReproduced=False, androidDeviceTested=False)
    (output / "build.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()

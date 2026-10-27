#!/usr/bin/env python3
"""Backport Wine's native-system-info correction to the pinned ARM64X kernelbase.

Reuse the source/configuration prepared by build_wait_completion.py and
link_wait_completion.py, in a private copy of its PE build. Verify every
kernelbase source against the pinned source archive. Build an unmodified DLL,
then compile only the two memory.o objects with the upstream change. No game,
Windows executable, or Android session runs on this host.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tarfile

from build_ntdll import sha, SOURCE_SHA, SOURCE_COMMIT
from build_sechost import TOOLCHAIN_SHA, TOOLCHAIN_URL
from pe_dependencies import PeImage

HERE = Path(__file__).resolve().parent
META = HERE / "system-info"
REVISION = "11.0-2-aura-system-info1"
UPSTREAM = "c5a96a0b6c635995718602ca0d0792833de1f46f"
SUBDIRS = ("include nls tools/widl tools/winebuild tools/winegcc tools/wrc tools/wmc "
           "dlls/winecrt0 libs/compiler-rt libs/musl libs/tomcrypt libs/uuid "
           "dlls/ntdll dlls/kernelbase dlls/kernel32 dlls/msvcrt dlls/ucrtbase")


def abi(before, after):
    assert before.info == after.info
    for a, b in ((before, after), (before.ec_view(), after.ec_view())):
        assert a.exports() == b.exports(), "Export ABI changed"
        imports = lambda p: {(i["module"], i["delayed"], s) for i in p.imports() for s in i["symbols"]}
        assert imports(a) == imports(b), "Import ABI changed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "source-archive", "toolchain-archive", "mingw", "original-dll", "work"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    assert sha(args.source_archive) == SOURCE_SHA
    assert sha(args.toolchain_archive) == TOOLCHAIN_SHA
    original_sha = "ec28231735d59c9fc8d29adc796aefb07bfad5e1be58198bdb645bee90aa7d92"
    assert sha(args.original_dll) == original_sha
    base, work, toolchain = args.base.resolve(), args.work.resolve(), args.mingw.resolve()
    source, base_pe = base / "source", base / "pe"
    base_record = HERE / "wait-completion/build.json"
    assert json.loads(base_record.read_text()) == json.loads((base / "output/link.json").read_text())
    inputs = {}
    with tarfile.open(args.source_archive) as archive:
        for item in archive:
            name = "/".join(Path(item.name).parts[1:])
            if item.isfile() and name.startswith("dlls/kernelbase/") and "/tests/" not in name:
                data = archive.extractfile(item).read()
                assert (source / name).read_bytes() == data, "Unexpected kernelbase edit: " + name
                inputs[name] = hashlib.sha256(data).hexdigest()
    assert len(inputs) == 22
    work.mkdir(parents=True, exist_ok=False)
    build, output = work / "pe", work / "output"
    output.mkdir()
    shutil.copytree(base_pe, build, symlinks=True)
    status = build / "config.status"
    text = status.read_text().replace(str(base_pe), str(build))
    text, count = re.subn(r'SUBDIRS=[^"\n]+', "SUBDIRS=" + SUBDIRS, text)
    assert count == 1
    status.write_text(text)
    environment = dict(os.environ, PATH=str(toolchain) + os.pathsep + os.environ["PATH"], SOURCE_DATE_EPOCH="0")
    with (work / "build.log").open("w") as log:
        def run(command, cwd=build):
            subprocess.run([str(x) for x in command], cwd=cwd, env=environment,
                           check=True, stdout=log, stderr=subprocess.STDOUT)

        run(["sh", "./config.status"])
        target = "dlls/kernelbase/aarch64-windows/kernelbase.dll"
        run(["make", "-j4", target])
        baseline = output / "baseline-kernelbase.dll"
        run([toolchain / "llvm-strip", "--strip-all", build / target, "-o", baseline])
        original_source = source / "dlls/kernelbase/memory.c"
        patched = work / "source/dlls/kernelbase/memory.c"
        patched.parent.mkdir(parents=True)
        shutil.copyfile(original_source, patched)
        run(["patch", "--batch", "--fuzz=0", "-p1", "-i", META / "wine-native-system-info.patch"], work / "source")
        planned = subprocess.check_output(["make", "-n", "-W", str(original_source), target],
                                          cwd=build, env=environment, text=True).replace("\\\n", "")
        commands = [shlex.split(line) for line in planned.splitlines() if line.strip()]
        compiles = [line for line in commands if str(original_source) in line]
        link, = [line for line in commands if "-shared" in line and target in line]
        assert len(compiles) == 2
        objects = {}
        for command in compiles:
            old_object = command[command.index("-o") + 1]
            arch = Path(old_object).parent.name
            assert arch in ("aarch64-windows", "arm64ec-windows")
            replacement = work / (arch + "-memory.o")
            command = [str(patched) if x == str(original_source) else
                       str(replacement) if x == old_object else x for x in command]
            command += ["-ffile-prefix-map=" + str(work / "source") + "=/aura/wine-source"]
            run(command)
            objects[old_object] = str(replacement)
        fixed = output / "kernelbase.dll"
        link = [str(fixed) if x == target else objects.get(x, x) for x in link]
        run(link)
        run([toolchain / "llvm-strip", "--strip-all", fixed])
    old, before, after = (PeImage(p.read_bytes()) for p in (args.original_dll, baseline, fixed))
    abi(old, before)
    abi(before, after)
    assert after.info["machine"] == "0xaa64" and after.info["chpeMetadata"]
    assert b"Wine builtin DLL" in after.data[:128]
    assert sha(baseline) != sha(fixed)
    record = dict(schema="WINE_SYSTEM_INFO_BUILD_V1", revision=REVISION,
                  sourceCommit=SOURCE_COMMIT, sourceSha256=SOURCE_SHA,
                  upstreamCommit=UPSTREAM, upstreamUrl="https://github.com/wine-mirror/wine/commit/" + UPSTREAM,
                  toolchainUrl=TOOLCHAIN_URL, toolchainSha256=TOOLCHAIN_SHA,
                  compilerSha256=sha(toolchain / "clang"), baseBuildRecordSha256=sha(base_record),
                  builderSha256=sha(Path(__file__)), patchSha256=sha(META / "wine-native-system-info.patch"),
                  originalSha256=original_sha, baselineSha256=sha(baseline), kernelbaseSources=inputs,
                  memoryAfterSha256=sha(patched), sha256=sha(fixed), bytes=fixed.stat().st_size,
                  pe=after.info, publicExportsAndImportsUnchanged=True,
                  architectures=["ARM64", "ARM64EC"], i386Changed=False, nativeNtdllChanged=False,
                  androidGameTested=False)
    (output / "build.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({k: record[k] for k in ("revision", "sha256", "bytes", "publicExportsAndImportsUnchanged")}))


if __name__ == "__main__":
    main()

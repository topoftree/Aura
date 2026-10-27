#!/usr/bin/env python3
"""Add the Android locale choice to a verified wait-completion build.

First reproduce the pinned native binary with the base build's existing objects.
Compile only env.c into a new directory and link there; never edit the base build.
Run build_wait_completion.py and link_wait_completion.py first for a clean build.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess

from build_ntdll import sha, relocate
from audit_runtime_candidate import elf_info
from verify_ntdll_layout import inspect as inspect_layout

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
META = HERE / "android-locale"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base", type=Path, required=True)
    p.add_argument("--work", type=Path, required=True)
    a = p.parse_args()
    base, work = a.base.resolve(), a.work.resolve()
    source, native = base / "source", base / "native"
    previous = json.loads((HERE / "wait-completion/build.json").read_text())
    assert previous == json.loads((base / "output/link.json").read_text())
    assert sha(base / "output/ntdll.so") == previous["nativeSha256"]
    work.mkdir(parents=True, exist_ok=False)
    env_source = source / "dlls/ntdll/unix/env.c"
    planned = subprocess.check_output(["make", "-n", "-W", str(env_source), "dlls/ntdll/ntdll.so"],
                                      cwd=native, text=True).replace("\\\n", "")
    commands = [shlex.split(line) for line in planned.splitlines()
                if line.startswith("/") and "aarch64-linux-android34-clang " in line]
    compile_command, = [line for line in commands if str(env_source) in line]
    link, = [line for line in commands if "-shared" in line and "dlls/ntdll/ntdll.so" in line]
    compiler = Path(link[0])
    ndk = compiler.parents[5]
    assert "Pkg.Revision = 28.2.13676358" in (ndk / "source.properties").read_text()
    env = dict(os.environ, SOURCE_DATE_EPOCH="0")
    with (work / "build.log").open("w") as log:
        def run(args, cwd=native):
            subprocess.run([str(v) for v in args], cwd=cwd, env=env, check=True,
                           stdout=log, stderr=subprocess.STDOUT)

        def linked(args, name):
            target = work / name
            args = list(args)
            args[args.index("-o") + 1] = str(target)
            run(args)
            target.write_bytes(relocate(target.read_bytes()))
            symbols = target.with_name(target.stem + "-symbols.so")
            shutil.copyfile(target, symbols)
            run([compiler.parent / "llvm-strip", "--strip-debug", target])
            return target, symbols

        baseline, _ = linked(link, "baseline.so")
        assert sha(baseline) == previous["nativeSha256"], "Base objects do not reproduce reviewed ntdll"
        patched = work / "source/dlls/ntdll/unix/env.c"
        patched.parent.mkdir(parents=True)
        shutil.copyfile(env_source, patched)
        run(["patch", "--batch", "--fuzz=0", "-p1", "-i", META / "wine-android-locale.patch"], work / "source")
        compiled = work / "env.o"
        command = [str(patched) if item == str(env_source) else
                   str(compiled) if item == "dlls/ntdll/unix/env.o" else item for item in compile_command]
        command += ["-I" + str(env_source.parent),
                    "-ffile-prefix-map=" + str(work / "source") + "=/aura/wine-source"]
        run(command)
        target, symbols = linked([str(compiled) if item == "dlls/ntdll/unix/env.o" else item for item in link],
                                 "ntdll.so")
    info = elf_info(target.read_bytes())
    assert info == previous["elf"], "ELF ABI/dependencies/load alignment changed"
    manifest = json.loads((ROOT / "engine/src/main/assets/arm64ec/manifest.json").read_text())
    layout = inspect_layout(target.read_bytes(), manifest["files"])
    assert layout["passed"]
    record = dict(schema="WINE_ANDROID_LOCALE_BUILD_V1", revision="11.0-2-aura-locale1",
                  baseNativeSha256=previous["nativeSha256"],
                  baseBuildRecordSha256=sha(HERE / "wait-completion/build.json"),
                  baseReproduced=True, patchSha256=sha(META / "wine-android-locale.patch"),
                  builderSha256=sha(Path(__file__)), beforeEnvSha256=sha(env_source), afterEnvSha256=sha(patched),
                  nativeSha256=sha(target), nativeBytes=target.stat().st_size, symbolsSha256=sha(symbols),
                  elf=info, compiledLayout=layout, unixCodepage="UTF-8", windowsLocales=["en-US", "zh-CN"],
                  privateUnixOpcode=14, unixTableEntries=15, peBinariesChanged=False,
                  nativeObjects={item: sha(native / item) for item in link if item.endswith(".o")},
                  replacementEnvObjectSha256=sha(compiled), androidDeviceTested=False)
    (work / "build.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({key: record[key] for key in ("revision", "baseReproduced", "nativeSha256", "nativeBytes")}))


if __name__ == "__main__":
    main()

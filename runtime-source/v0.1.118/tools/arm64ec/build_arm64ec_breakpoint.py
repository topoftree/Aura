#!/usr/bin/env python3
"""Build the EC loader breakpoint correction on a reproduced 0.1.107 PE build.

First run build_mgs_tick_thunk.py as documented, or reuse its completed work
directory. Only the generated source/build directory is modified. The stripped
baseline must match the shipped PE; the candidate may change only the two ARM64
load/store instructions and LLD's eight-byte content build ID.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess

from pe_dependencies import PeImage

HERE = Path(__file__).resolve().parent
META = HERE / "arm64ec-breakpoint"
BASE_SHA = "9d7aa3f166f78f5b77a91c0f4f6b105ac9489ded6641579f2c578aca6c06dc3a"
SIGNAL_SHA = "09c946931ed9e9e23fe1af8be9304517a08bbcf609af5752cf8ac2b4c8d511a1"
HANDLER_RVA = 0xc3a58
BASE_HANDLER = bytes.fromhex("448440f984100091448400f900008052c0035fd6")
FIXED_HANDLER = bytes.fromhex("447c40f984100091447c00f900008052c0035fd6")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def content_build_id(pe):
    rva, size = pe.directory(6)
    assert size == 28, "Expected one IMAGE_DEBUG_DIRECTORY"
    fields = struct.unpack_from("<IIHHIIII", pe.data, pe.offset(rva, size))
    _, _, _, _, kind, count, data_rva, offset = fields
    assert kind == 2 and count == 25 and pe.offset(data_rva, count) == offset
    record = pe.data[offset:offset + count]
    # --build-id uses an eight-byte content hash followed by the LLD PDB tag.
    # The age and empty PDB filename must also be identical; no local paths leak.
    assert record[:4] == b"RSDS" and record[12:] == b"LLD PDB.\x01\0\0\0\0"
    return offset + 4


def verify_candidate(before, after):
    assert hashlib.sha256(before).hexdigest() == BASE_SHA
    a, b = PeImage(before), PeImage(after)
    assert len(before) == len(after) and a.info == b.info
    for aa, bb in ((a, b), (a.ec_view(), b.ec_view())):
        assert aa.exports() == bb.exports() and aa.imports() == bb.imports()
    offset = a.offset(HANDLER_RVA, len(BASE_HANDLER))
    assert before[offset:offset + len(BASE_HANDLER)] == BASE_HANDLER
    assert after[offset:offset + len(FIXED_HANDLER)] == FIXED_HANDLER
    build_id = content_build_id(a)
    assert content_build_id(b) == build_id
    changed = {i for i, (x, y) in enumerate(zip(before, after)) if x != y}
    instructions = {offset + 1, offset + 9}
    assert instructions <= changed
    assert changed <= instructions | set(range(build_id, build_id + 8)), sorted(changed)
    return sorted(changed), build_id


def write_metadata(output, baseline_meta):
    before, after = (output / "baseline.dll").read_bytes(), (output / "ntdll.dll").read_bytes()
    changed, build_id = verify_candidate(before, after)
    meta = dict(schema="WINE_ARM64EC_BREAKPOINT_BUILD_V1", revision="11.0-2-aura-ec-breakpoint1",
        sourceCommit=json.loads(baseline_meta.read_text())["sourceCommit"],
        baselineBuildSha256=sha(baseline_meta), sourceSignalSha256=SIGNAL_SHA,
        patchSha256=sha(META / "wine-arm64ec-breakpoint.patch"),
        buildScriptSha256=sha(Path(__file__)),
        baseline=dict(bytes=len(before), sha256=BASE_SHA),
        candidate=dict(bytes=len(after), sha256=sha(output / "ntdll.dll")),
        handlerRva=hex(HANDLER_RVA), changedFileOffsets=[hex(i) for i in changed],
        contentBuildId=dict(fileOffset=hex(build_id), previous=before[build_id:build_id+8].hex(),
                            current=after[build_id:build_id+8].hex()),
        publicExportsAndImportsUnchanged=True, i386NativeAndFexUnchanged=True,
        contextPcOffset="0xf8", instructionStepBytes=4,
        scope="ARM64EC loader process_breakpoint SEH handler only",
        debuggerAndExceptionDispatchPreserved=True, androidGameExecuted=False)
    (output / "build.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(json.dumps(meta, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline-work", "mingw", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    work, mingw, output = args.baseline_work.resolve(), args.mingw.resolve(), args.output.resolve()
    source, build = work / "source", work / "pe"
    old = work / "output/fixed/aarch64-windows/ntdll.dll"
    baseline_meta = work / "output/build.json"
    assert baseline_meta.read_bytes() == (HERE / "mgs-tick-thunk/build.json").read_bytes()
    assert sha(old) == BASE_SHA
    signal = source / "dlls/ntdll/signal_arm64ec.c"
    assert sha(signal) == SIGNAL_SHA, "Use a completed, unmodified baseline build"
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(old, output / "baseline.dll")
    env = os.environ.copy()
    env.update(PATH=str(mingw) + os.pathsep + env["PATH"], SOURCE_DATE_EPOCH="0",
               XDG_CACHE_HOME=str(work / "cache"))
    target = "dlls/ntdll/aarch64-windows/ntdll.dll"
    with (output / "build.log").open("w") as log:
        def run(command, cwd=source):
            subprocess.run([str(x) for x in command], cwd=cwd, env=env,
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        # Check the existing linked build before any mutation as well.
        shutil.copyfile(build / target, output / "linked-baseline.dll")
        run([mingw / "llvm-strip", "--strip-debug", output / "linked-baseline.dll"])
        assert sha(output / "linked-baseline.dll") == BASE_SHA
        run(["git", "apply", "--check", META / "wine-arm64ec-breakpoint.patch"])
        run(["git", "apply", META / "wine-arm64ec-breakpoint.patch"])
        run(["make", "-j2", target], build)
        candidate = output / "ntdll.dll"
        shutil.copyfile(build / target, candidate)
        run([mingw / "llvm-strip", "--strip-debug", candidate])
    write_metadata(output, baseline_meta)


if __name__ == "__main__":
    main()

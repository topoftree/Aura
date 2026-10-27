#!/usr/bin/env python3
"""Build and package Aura's FEX unix bridge; verify and preserve every other runtime entry."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import zstandard

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name("fex-unix")
sys.path.insert(0, str(ROOT / "tools"))
from audit_runtime_candidate import elf_info


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    compiler = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang++"
    library = args.output / "fex-unix.so"
    flags = ["-shared", "-fPIC", "-O2", "-std=c++20", "-static-libstdc++", "-Wl,-z,max-page-size=16384"]
    subprocess.run([str(compiler), *flags, str(SOURCE / "FEXUnixLib.cpp"), "-o", str(library)], check=True)
    data = library.read_bytes()
    elf = elf_info(data)
    assert elf["machine"] == "0xb7" and elf["abi"] != "glibc" and elf["minimumLoadAlignment"] >= 16384
    record = dict(schema="AURA_FEX_UNIX_V2", flags=flags, librarySha256=sha(library),
        ndk=(args.ndk / "source.properties").read_text(),
        sources={name: sha(SOURCE / name) for name in ("FEXUnixLib.cpp", "FEXUnixLib.h", "HardwareTso.h", "LICENSE")},
        hardwareTso="PER_GAME_OPT_IN_WITH_KERNEL_READBACK", softwareTsoFallback=True, androidDeviceTested=False)
    record["cpuAudit"] = "READ_ONLY_AUXV_AND_SVE_LENGTH_ENTRY_8"
    replacements = {f"opt/wine/lib/wine/aarch64-unix/{name}": data for name in ("libarm64ecfex.so", "libwow64fex.so")}
    for name in record["sources"]:
        replacements["usr/share/licenses/aura-fex-unix/" + name] = (SOURCE / name).read_bytes()
    assets = ROOT / "engine/src/main/assets/arm64ec"
    archive, index = assets / "runtime.tzst", assets / "manifest.json"
    manifest = json.loads(index.read_text())
    assert sha(archive) == manifest["archives"][archive.name]["sha256"]
    temporary = archive.with_suffix(".new")
    seen, preserved = set(), 0
    digest = lambda value: hashlib.sha256(value).hexdigest()
    try:
        with archive.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open("wb") as dst, zstandard.ZstdCompressor(level=10).stream_writer(dst) as writer:
                with tarfile.open(fileobj=reader, mode="r|") as original, tarfile.open(fileobj=writer, mode="w|") as out:
                    for entry in original:
                        name = entry.name.removeprefix("./")
                        content = original.extractfile(entry).read() if entry.isfile() else None
                        if content is not None:
                            assert digest(content) == manifest["files"][name]["sha256"], name
                        if name in replacements:
                            assert content is not None and name not in seen
                            content = replacements[name]; entry.size = len(content); seen.add(name)
                        elif content is not None:
                            preserved += 1
                        out.addfile(entry, io.BytesIO(content) if content is not None else None)
                    for name, content in sorted(replacements.items()):
                        if name not in seen:
                            assert name.startswith("usr/share/licenses/"), name
                            entry = tarfile.TarInfo(name); entry.size = len(content); entry.mode = 0o644
                            out.addfile(entry, io.BytesIO(content))
                while reader.read(1024 * 1024):
                    pass
        for name, content in replacements.items():
            manifest["files"][name] = dict(bytes=len(content), sha256=digest(content))
        manifest["fexUnixBridge"] = record
        manifest["archives"][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        index.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        record["otherFilesByteIdentical"] = preserved
        (args.output / "package.json").write_text(json.dumps(record, indent=2) + "\n")
    finally:
        temporary.unlink(missing_ok=True)
    subprocess.run([sys.executable, str(ROOT / "tools/prepare_engine.py")], check=True)
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()

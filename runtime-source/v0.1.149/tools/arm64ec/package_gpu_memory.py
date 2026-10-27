#!/usr/bin/env python3
"""Install the source-built win32u correction; preserve other runtime entries."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zstandard

from build_ntdll import sha

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).with_name("gpu-memory")
NATIVE = "opt/wine/lib/wine/aarch64-unix/win32u.so"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--built", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    a = parser.parse_args()
    record = json.loads((META / "build.json").read_text())
    assert record == json.loads((a.built / "build.json").read_text())
    assert record["schema"] == "WINE_GPU_MEMORY_BUILD_V1" and record["samePublicImportsAndExports"]
    assert sha(META / "wine-gpu-memory.patch") == record["sourcePatchSha256"]
    assert sha(META.parent / "build_gpu_memory.py") == record["builderSha256"]
    fixed = record["outputs"]["fixed"]
    assert sha(a.built / "fixed.so") == fixed["sha256"]
    replacements = {NATIVE: (a.built / "fixed.so").read_bytes()}
    for name in ("NOTICE", "COPYING.LIB", "wine-gpu-memory.patch", "build.json"):
        replacements["opt/wine/share/licenses/aura-gpu-memory/" + name] = (META / name).read_bytes()
    assets = ROOT / "engine/src/main/assets/arm64ec"
    archive, index = assets / "runtime.tzst", assets / "manifest.json"
    manifest = json.loads(index.read_text())
    assert sha(archive) == manifest["archives"][archive.name]["sha256"]
    assert manifest["files"][NATIVE]["sha256"] in (record["packagedBeforeSha256"], fixed["sha256"])
    temporary = archive.with_suffix(".new")
    seen, preserved = set(), 0
    digest = lambda data: hashlib.sha256(data).hexdigest()
    try:
        with archive.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open("wb") as dst, zstandard.ZstdCompressor(level=10).stream_writer(dst) as writer:
                with tarfile.open(fileobj=reader, mode="r|") as original, tarfile.open(fileobj=writer, mode="w|") as out:
                    for entry in original:
                        name = entry.name.removeprefix("./")
                        data = original.extractfile(entry).read() if entry.isfile() else None
                        if data is not None:
                            assert digest(data) == manifest["files"][name]["sha256"], name
                        if name in replacements:
                            assert data is not None and name not in seen
                            data = replacements[name]; entry.size = len(data); seen.add(name)
                        elif data is not None: preserved += 1
                        out.addfile(entry, io.BytesIO(data) if data is not None else None)
                    assert NATIVE in seen
                    for name, data in sorted(replacements.items()):
                        if name not in seen:
                            entry = tarfile.TarInfo(name); entry.size = len(data); entry.mode = 0o644
                            out.addfile(entry, io.BytesIO(data))
                while reader.read(1024 * 1024): pass
        for name, data in replacements.items():
            manifest["files"][name] = dict(bytes=len(data), sha256=digest(data))
        manifest["wineGpuMemory"] = record
        manifest["archives"][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        index.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        report = dict(win32uSha256=fixed["sha256"], archive=manifest["archives"][archive.name],
            otherFilesByteIdentical=preserved, peAndNtdllUnchanged=True, androidDeviceTested=False)
        a.report.parent.mkdir(parents=True, exist_ok=True)
        a.report.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__": main()

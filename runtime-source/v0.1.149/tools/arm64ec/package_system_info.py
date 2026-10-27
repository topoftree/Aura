#!/usr/bin/env python3
"""Package only the rebuilt ARM64X kernelbase and its upstream licence/notice."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

import zstandard
from build_ntdll import sha
from build_system_info import abi, REVISION
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).resolve().parent / "system-info"
LOGICAL = "opt/wine/lib/wine/aarch64-windows/kernelbase.dll"


def catalogue(record):
    return dict(schema=1, revision=record["revision"], file=dict(
        path=LOGICAL, bytes=record["bytes"], sha256=record["sha256"], previousSha256=[record["originalSha256"]]))


def stage_from_cache(root, cache):
    record = json.loads((META / "build.json").read_text())
    payload = (cache / "aura-kernelbase-system-info.dll").read_bytes()
    assert len(payload) == record["bytes"] and hashlib.sha256(payload).hexdigest() == record["sha256"]
    target = root / LOGICAL
    assert sha(target) in (record["originalSha256"], record["sha256"])
    abi(PeImage(target.read_bytes()), PeImage(payload))
    target.write_bytes(payload)
    for name in ("NOTICE", "COPYING.LIB", "wine-native-system-info.patch"):
        destination = root / "opt/wine/share/licenses/aura-system-info" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((META / name).read_bytes())
    return record


def write_catalogue(assets, record):
    (assets / "wine-system-info.json").write_text(json.dumps(catalogue(record), indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--built", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    record = json.loads((META / "build.json").read_text())
    assert record == json.loads((args.built / "build.json").read_text())
    assert record["revision"] == REVISION and record["publicExportsAndImportsUnchanged"]
    assert sha(META / "wine-native-system-info.patch") == record["patchSha256"]
    assert sha(META.parent / "build_system_info.py") == record["builderSha256"]
    dll = (args.built / "kernelbase.dll").read_bytes()
    assert hashlib.sha256(dll).hexdigest() == record["sha256"] and len(dll) == record["bytes"]
    replacements = {LOGICAL: dll}
    for name in ("NOTICE", "COPYING.LIB", "wine-native-system-info.patch"):
        replacements["opt/wine/share/licenses/aura-system-info/" + name] = (META / name).read_bytes()
    assets = ROOT / "engine/src/main/assets/arm64ec"
    archive, manifest_path = assets / "runtime.tzst", assets / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    assert sha(archive) == manifest["archives"][archive.name]["sha256"]
    assert manifest["files"][LOGICAL]["sha256"] in (record["originalSha256"], record["sha256"])
    temporary = archive.with_suffix(".new")
    seen, preserved = set(), 0
    digest = lambda data: hashlib.sha256(data).hexdigest()
    try:
        with archive.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open("wb") as dst, zstandard.ZstdCompressor(level=10).stream_writer(dst) as writer:
                with tarfile.open(fileobj=reader, mode="r|") as source, tarfile.open(fileobj=writer, mode="w|") as output:
                    for entry in source:
                        name = entry.name.removeprefix("./")
                        data = source.extractfile(entry).read() if entry.isfile() else None
                        if data is not None:
                            assert manifest["files"][name] == dict(bytes=len(data), sha256=digest(data)), name
                        if name in replacements:
                            assert data is not None and name not in seen
                            if name == LOGICAL:
                                abi(PeImage(data), PeImage(dll))
                            data = replacements[name]
                            entry.size = len(data)
                            seen.add(name)
                        elif data is not None:
                            preserved += 1
                        output.addfile(entry, io.BytesIO(data) if data is not None else None)
                    assert LOGICAL in seen
                    for name, data in sorted(replacements.items()):
                        if name not in seen:
                            entry = tarfile.TarInfo(name)
                            entry.size, entry.mode = len(data), 0o644
                            output.addfile(entry, io.BytesIO(data))
                while reader.read(1024 * 1024):
                    pass
        for name, data in replacements.items():
            manifest["files"][name] = dict(bytes=len(data), sha256=digest(data))
        manifest["wineSystemInfo"] = record
        manifest["archives"][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        write_catalogue(assets, record)
        report = dict(revision=record["revision"], archive=manifest["archives"][archive.name],
                      otherFilesByteIdentical=preserved, i386Unchanged=True, nativeLibrariesUnchanged=True,
                      androidGameTested=False)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()

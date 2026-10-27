#!/usr/bin/env python3
"""Add the audited media plugins without replacing Wine or existing runtime files."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile

import zstandard

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_runtime_candidate import elf_info

REPO = Path(__file__).resolve().parents[2]
META = Path(__file__).with_name("media")
LICENSE = "usr/share/licenses/aura-gstreamer-media/COPYING"


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def payload(folder):
    build = json.loads((META / "build.json").read_text())
    files = {}
    for name, expected in build["files"].items():
        data = (folder / name).read_bytes()
        assert len(data) == expected["bytes"] and hashlib.sha256(data).hexdigest() == expected["sha256"], name
        info = elf_info(data)
        assert info["machine"] == "0xb7" and info["abi"] == "bionic", name
        assert info["minimumLoadAlignment"] >= 16384 and not info["searchPaths"], name
        files[name] = data
    files[LICENSE] = (META / "COPYING").read_bytes()
    return build, files


def stage_from_cache(root, cache):
    build, files = payload(cache / "aura-gstreamer-media")
    for name, data in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return build


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload", type=Path, required=True)
    args = parser.parse_args()
    build, additions = payload(args.payload)
    assets = REPO / "engine/src/main/assets/arm64ec"
    path = assets / "runtime.tzst"
    manifest_path = assets / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    assert sha(path) == manifest["archives"][path.name]["sha256"]
    temporary = path.with_suffix(".new")
    seen = set()
    try:
        with path.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open("wb") as target, zstandard.ZstdCompressor(level=10).stream_writer(target) as writer:
                with tarfile.open(fileobj=reader, mode="r|") as original, tarfile.open(fileobj=writer, mode="w|") as output:
                    for item in original:
                        name = item.name.removeprefix("./")
                        if name in additions:
                            assert item.isfile(), name
                            previous = original.extractfile(item).read()
                            assert hashlib.sha256(previous).hexdigest() == manifest["files"][name]["sha256"], name
                            seen.add(name)
                            item.size = len(additions[name])
                            output.addfile(item, io.BytesIO(additions[name]))
                        else:
                            output.addfile(item, original.extractfile(item) if item.isfile() else None)
                    for name, data in sorted(additions.items()):
                        if name in seen:
                            continue
                        item = tarfile.TarInfo(name)
                        item.size = len(data)
                        item.mode = 0o644
                        output.addfile(item, io.BytesIO(data))
                while reader.read(1024 * 1024):
                    pass
        for name, data in additions.items():
            manifest["files"][name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        manifest["mediaCodecs"] = build
        manifest["archives"][path.name] = {"sha256": sha(temporary), "bytes": temporary.stat().st_size}
        temporary.replace(path)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        print(json.dumps(manifest["archives"][path.name]))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()

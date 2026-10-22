#!/usr/bin/env python3
"""Replace only the audited Wine service DLL in the pinned runtime archive."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

import zstandard
from pe_dependencies import PeImage

REPO = Path(__file__).resolve().parents[2]
BUILD = Path(__file__).with_name("sechost") / "build.json"
LOGICAL = "opt/wine/lib/wine/aarch64-windows/sechost.dll"
ORIGINAL_SHA = "39075e5f62303da14501db52df0e9b013cefa7fea0aafdec1d5dc10e811857fa"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate(old, new):
    metadata = json.loads(BUILD.read_text())
    assert hashlib.sha256(new).hexdigest() == metadata["sha256"] and len(new) == metadata["bytes"]
    assert hashlib.sha256(old).hexdigest() in (ORIGINAL_SHA, metadata["sha256"])
    for transform in (lambda p: p, lambda p: p.ec_view()):
        original, rebuilt = transform(PeImage(old)), transform(PeImage(new))
        assert original.exports() == rebuilt.exports(), "Builtin export ABI changed"
        imports = rebuilt.imports()
        assert not any(i["delayed"] for i in imports)
        assert any(i["module"] == "rpcrt4.dll" and "NdrClientCall2" in i["symbols"] for i in imports)
        allowed = {(i["module"], s) for i in original.imports() for s in i["symbols"]}
        assert {(i["module"], s) for i in imports for s in i["symbols"]} <= allowed
    return metadata


def stage_from_cache(root, cache):
    target = root / LOGICAL
    new = (cache / "aura-sechost-rpc-direct.dll").read_bytes()
    metadata = validate(target.read_bytes(), new)
    target.write_bytes(new)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dll", type=Path, required=True)
    args = parser.parse_args()
    assets = REPO / "engine/src/main/assets/arm64ec"
    path = assets / "runtime.tzst"
    manifest_path = assets / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    assert sha(path) == manifest["archives"][path.name]["sha256"]
    new = args.dll.read_bytes()
    temporary = path.with_suffix(".new")
    found = False
    try:
        with path.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open("wb") as target, zstandard.ZstdCompressor(level=10).stream_writer(target) as writer:
                with tarfile.open(fileobj=reader, mode="r|") as source, tarfile.open(fileobj=writer, mode="w|") as output:
                    for item in source:
                        if item.name.removeprefix("./") == LOGICAL:
                            assert not found and item.isfile()
                            metadata = validate(source.extractfile(item).read(), new)
                            item.size = len(new)
                            output.addfile(item, io.BytesIO(new))
                            found = True
                        else:
                            output.addfile(item, source.extractfile(item) if item.isfile() else None)
                while reader.read(1024 * 1024):
                    pass
        assert found
        manifest["files"][LOGICAL] = {"sha256": metadata["sha256"], "bytes": len(new)}
        manifest["archives"][path.name] = {"sha256": sha(temporary), "bytes": temporary.stat().st_size}
        manifest["wineServiceRepair"] = metadata
        temporary.replace(path)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        print(json.dumps(manifest["archives"][path.name]))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Verify media payload ABI/imports and unchanged previous runtime entries; no guest execution."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile

import zstandard

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_runtime_candidate import elf_info

REPO = Path(__file__).resolve().parents[2]


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def entries(path):
    result = {}
    with path.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as stream:
        with tarfile.open(fileobj=stream, mode="r|") as archive:
            for item in archive:
                name = item.name.removeprefix("./")
                assert name not in result
                digest = hashlib.file_digest(archive.extractfile(item), "sha256").hexdigest() if item.isfile() else None
                result[name] = (item.type.decode(), item.linkname, item.mode, item.size, digest)
        while stream.read(1024 * 1024):
            pass
    return result


def symbols(readelf, path):
    text = subprocess.check_output([str(readelf), "--dyn-syms", "--wide", str(path)], text=True)
    defined, strong, weak = set(), set(), set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        name = fields[7].split("@")[0]
        if fields[6] == "UND":
            (weak if fields[4] == "WEAK" else strong).add(name)
        elif fields[4] in ("GLOBAL", "WEAK"):
            defined.add(name)
    return defined, strong, weak


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--baseline-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assets = REPO / "engine/src/main/assets"
    native = REPO / "engine/src/main/jniLibs/arm64-v8a"
    document = json.loads((assets / "arm64ec/manifest.json").read_text())
    index = json.loads((assets / "aura-native-index.json").read_text())["files"]
    build = json.loads((REPO / "tools/arm64ec/media/build.json").read_text())
    assert document["mediaCodecs"] == build
    assert sha(REPO / "tools/arm64ec/build_media.py") == build["build_script_sha256"]
    assert sha(REPO / "tools/arm64ec/media/omit-video-filters.patch") == build["patch_sha256"]
    info, paths, names = {}, {}, {}
    for logical, entry in document["files"].items():
        digest = entry["sha256"]
        if digest not in index:
            continue
        path = native / index[digest]
        assert path.is_file()
        details = elf_info(path.read_bytes())
        info[logical], paths[logical] = details, path
        names[Path(logical).name] = logical
        for name in details["soname"]:
            names[name] = logical
    tc = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    readelf = tc / "bin/llvm-readelf"
    system = tc / "sysroot/usr/lib/aarch64-linux-android/34"
    cache = {}

    def exported(path):
        if path not in cache:
            cache[path] = symbols(readelf, path)
        return cache[path]

    checks = []
    for logical, expected in build["files"].items():
        path = paths[logical]
        assert sha(path) == expected["sha256"] and path.stat().st_size == expected["bytes"]
        details = info[logical]
        assert details["machine"] == "0xb7" and details["abi"] == "bionic"
        assert details["minimumLoadAlignment"] >= 16384 and not details["searchPaths"]
        # Meson's install cleanup removes DT_RUNPATH but can retain its unreferenced
        # string-table bytes. Loader search paths above, not a raw byte grep, are authoritative.
        assert b"com.termux" not in path.read_bytes()
        seen, available = set(), set()
        pending = list(details["needed"])
        while pending:
            name = pending.pop()
            if name in seen:
                continue
            seen.add(name)
            if name in names:
                dependency = names[name]
                available |= exported(paths[dependency])[0]
                pending += info[dependency]["needed"]
            else:
                stub = system / name
                assert stub.is_file(), (logical, "unbundled dependency", name)
                available |= exported(stub)[0]
        defined, strong, weak = exported(path)
        missing = sorted(strong - available)
        assert not missing, (logical, missing)
        if logical.endswith("libgstlibav.so"):
            assert "gst_plugin_libav_get_desc" in defined
        if logical.endswith("libgstvideoparsersbad.so"):
            assert "gst_plugin_videoparsersbad_get_desc" in defined
        checks.append({"path": logical, **expected, **details, "strong_imports": len(strong),
            "weak_imports": len(weak), "dependency_closure": len(seen), "unresolved_strong": missing})
    archive = assets / "arm64ec/runtime.tzst"
    assert sha(archive) == document["archives"]["runtime.tzst"]["sha256"]
    old, new = entries(args.baseline_archive), entries(archive)
    removed = sorted(set(old) - set(new))
    changed = sorted(name for name in old.keys() & new.keys() if old[name] != new[name])
    assert not removed and not changed
    added = sorted(set(new) - set(old))
    assert set(added) == set(build["files"]) | {"usr/share/licenses/aura-gstreamer-media/COPYING"}
    for name in added:
        assert new[name][4] == document["files"][name]["sha256"]
    result = {"schema": 1, "native_checks": checks, "baseline_sha256": sha(args.baseline_archive),
        "runtime_sha256": sha(archive), "preserved_entries": len(old), "added": added,
        "changed": changed, "removed": removed,
        "validation": "ELF, dynamic-symbol, archive and source checks only; not Android playback."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"libraries": len(checks), "preserved_entries": len(old), "added": added}))


if __name__ == "__main__":
    main()

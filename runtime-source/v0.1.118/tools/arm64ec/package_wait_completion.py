#!/usr/bin/env python3
"""Package the reviewed native/i386 wait-completion pair in one runtime archive."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zstandard

from build_ntdll import sha
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).resolve().parent / "wait-completion"
NATIVE = "opt/wine/lib/wine/aarch64-unix/ntdll.so"
PE32 = "opt/wine/lib/wine/i386-windows/ntdll.dll"
PREVIOUS_NATIVE = "ee8b621d7ecb2bc6d090063083282356d3d02cc02688d7376ca6dd902b33a20d"
PREVIOUS_PE32 = "8e91fbf9bcd92af27fd6416ea2337ee76b4aa08050164229f35f3a3c8e832bfd"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def payload(folder):
    meta = json.loads((META / "build.json").read_text())
    assert meta == json.loads((folder / "link.json").read_text())
    assert meta["schema"] == "WINE_WAIT_COMPLETION_LINK_V1" and meta["nativeLinked"]
    assert meta["candidate"]["candidatePatchSha256"] == sha(META / "wine-wait-completion.patch")
    assert meta["linkerScriptSha256"] == sha(META.parent / "link_wait_completion.py")
    assert meta["candidate"]["buildScriptSha256"] == sha(META.parent / "build_wait_completion.py")
    native = (folder / "ntdll.so").read_bytes()
    pe = (folder / "fixed/i386-windows/ntdll.dll").read_bytes()
    assert digest(native) == meta["nativeSha256"] and len(native) == meta["nativeBytes"]
    assert digest(pe) == meta["candidate"]["peFiles"]["fixed"]["i386"]["sha256"]
    replacements = {NATIVE: native, PE32: pe}
    for name in ("NOTICE", "wine-wait-completion.patch", "COPYING.LIB"):
        replacements["opt/wine/share/licenses/aura-wait-completion/" + name] = (META / name).read_bytes()
    return meta, replacements


def catalogue(assets, meta):
    path = assets / "wine-wait-queue.json"
    doc = json.loads(path.read_text())
    record = doc["files"][PE32]
    old = record["sha256"]
    new = meta["candidate"]["peFiles"]["fixed"]["i386"]
    record.update(bytes=new["bytes"], sha256=new["sha256"])
    record["previousSha256"] = sorted(set(record["previousSha256"]) | {PREVIOUS_PE32})
    assert old in record["previousSha256"] or old == record["sha256"]
    doc.update(schema=2, revision=meta["revision"],
               nativeCompanion=dict(path=NATIVE, bytes=meta["nativeBytes"], sha256=meta["nativeSha256"]))
    path.write_text(json.dumps(doc, indent=2) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--built", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    a = p.parse_args()
    meta, replacements = payload(a.built)
    assets = ROOT / "engine/src/main/assets/arm64ec"
    archive, index = assets / "runtime.tzst", assets / "manifest.json"
    manifest = json.loads(index.read_text())
    assert sha(archive) == manifest["archives"][archive.name]["sha256"]
    temporary = archive.with_suffix(".new")
    seen, changed, added = set(), {}, []
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
                            assert name not in seen and data is not None
                            if name in (NATIVE, PE32):
                                old = PREVIOUS_NATIVE if name == NATIVE else PREVIOUS_PE32
                                assert digest(data) in (old, digest(replacements[name]))
                                changed[name] = dict(previousSha256=digest(data), sha256=digest(replacements[name]))
                            if name == PE32:
                                before, after = PeImage(data), PeImage(replacements[name])
                                assert before.info == after.info
                                assert before.exports() == after.exports() and before.imports() == after.imports()
                            data = replacements[name]
                            entry.size = len(data)
                            seen.add(name)
                        out.addfile(entry, io.BytesIO(data) if data is not None else None)
                    assert {NATIVE, PE32} <= seen
                    for name, data in sorted(replacements.items()):
                        if name in seen:
                            continue
                        entry = tarfile.TarInfo(name)
                        entry.size, entry.mode = len(data), 0o644
                        out.addfile(entry, io.BytesIO(data))
                        added.append(name)
                while reader.read(1024 * 1024):
                    pass
        for name, data in replacements.items():
            manifest["files"][name] = dict(bytes=len(data), sha256=digest(data))
        manifest["wineWaitCompletion"] = meta
        manifest["archives"][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        index.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        catalogue(assets, meta)
        report = dict(changed=changed, added=added, archive=manifest["archives"][archive.name],
                      matchingNativeAndI386=True, arm64xPeUnchanged=True, androidDeviceTested=False)
        a.report.parent.mkdir(parents=True, exist_ok=True)
        a.report.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Package the reviewed Android locale adapter, retaining every other runtime file."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zstandard

from build_ntdll import sha

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).resolve().parent / "android-locale"
NATIVE = "opt/wine/lib/wine/aarch64-unix/ntdll.so"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--built", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    a = p.parse_args()
    record = json.loads((META / "build.json").read_text())
    assert record == json.loads((a.built / "build.json").read_text())
    assert record["schema"] == "WINE_ANDROID_LOCALE_BUILD_V1" and record["baseReproduced"]
    assert sha(META / "wine-android-locale.patch") == record["patchSha256"]
    assert sha(META.parent / "build_android_locale.py") == record["builderSha256"]
    assert sha(a.built / "ntdll.so") == record["nativeSha256"]
    replacements = {NATIVE: (a.built / "ntdll.so").read_bytes()}
    for name in ("NOTICE", "COPYING.LIB", "wine-android-locale.patch"):
        replacements["opt/wine/share/licenses/aura-android-locale/" + name] = (META / name).read_bytes()
    assets = ROOT / "engine/src/main/assets/arm64ec"
    archive, index = assets / "runtime.tzst", assets / "manifest.json"
    manifest = json.loads(index.read_text())
    assert sha(archive) == manifest["archives"][archive.name]["sha256"]
    assert manifest["files"][NATIVE]["sha256"] in (record["baseNativeSha256"], record["nativeSha256"])
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
                            data = replacements[name]
                            entry.size = len(data)
                            seen.add(name)
                        elif data is not None:
                            preserved += 1
                        out.addfile(entry, io.BytesIO(data) if data is not None else None)
                    assert NATIVE in seen
                    for name, data in sorted(replacements.items()):
                        if name not in seen:
                            entry = tarfile.TarInfo(name)
                            entry.size, entry.mode = len(data), 0o644
                            out.addfile(entry, io.BytesIO(data))
                while reader.read(1024 * 1024):
                    pass
        for name, data in replacements.items():
            manifest["files"][name] = dict(bytes=len(data), sha256=digest(data))
        manifest["wineAndroidLocale"] = record
        manifest["archives"][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        # The completion opcode and both PE companions are unchanged. Pin their
        # catalogue to the newly linked Android native companion as one update.
        catalogue = assets / "wine-wait-queue.json"
        wait = json.loads(catalogue.read_text())
        assert wait["nativeCompanion"]["sha256"] in (record["baseNativeSha256"], record["nativeSha256"])
        wait["nativeCompanion"].update(bytes=record["nativeBytes"], sha256=record["nativeSha256"])
        temporary.replace(archive)
        index.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        catalogue.write_text(json.dumps(wait, indent=2) + "\n")
        report = dict(nativeSha256=record["nativeSha256"], archive=manifest["archives"][archive.name],
                      otherFilesByteIdentical=preserved, windowsDllsUnchanged=True,
                      allNlsAndFontsPreserved=True, androidDeviceTested=False)
        a.report.parent.mkdir(parents=True, exist_ok=True)
        a.report.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()

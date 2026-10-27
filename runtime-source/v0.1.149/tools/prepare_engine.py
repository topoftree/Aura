#!/usr/bin/env python3
"""Reproducibly stage bundled AArch64 ELF files in the APK's executable library directory.

No downloaded code is executed here. Source archives are Winlator's bundled assets.
Android 10+ forbids execve/mmap(PROT_EXEC) of code in writable app data.
The runtime links these files to the PackageManager-installed, read-only .so copies.
"""
import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import zstandard
import fnmatch
from runtime_paths import relocate_runtime_paths

ROOT = Path(__file__).resolve().parents[1] / "engine/src/main"
index = {}
loader = None
unaligned = []
windows_components = {}
wine_startup_files = {}
box64_files = {}
wine_startup_paths = {
    "bin/wine", "bin/wineserver", "lib/wine/x86_64-unix/wine", "lib/wine/x86_64-unix/ntdll.so",
    *{f"lib/wine/{arch}-windows/{name}.dll" for arch in ("x86_64", "i386")
      for name in ("ntdll", "kernel32", "kernelbase", "apisetschema")},
}
out = ROOT / "jniLibs/arm64-v8a"
out.mkdir(parents=True, exist_ok=True)
index_path = ROOT / "assets/aura-native-index.json"
previous_index = json.loads(index_path.read_text()).get("files", {}) if index_path.exists() else {}
excluded = [line.strip() for line in (ROOT.parents[1] / "legacy-runtime-assets.txt").read_text().splitlines()
            if line.strip() and not line.startswith("#")]

for archive in sorted((ROOT / "assets").rglob("*.tzst")):
    if any(fnmatch.fnmatch(archive.relative_to(ROOT / "assets").as_posix(), pattern) for pattern in excluded):
        continue
    changed = False
    temporary = archive.with_suffix(".staging")
    with archive.open("rb") as source, temporary.open("wb") as target:
        with zstandard.ZstdDecompressor().stream_reader(source) as reader, zstandard.ZstdCompressor(level=10).stream_writer(target) as writer:
            with tarfile.open(fileobj=reader, mode="r|") as tar_in, tarfile.open(fileobj=writer, mode="w|") as tar_out:
                for entry in tar_in:
                    if archive.parent.name == "wincomponents" and entry.isfile():
                        windows_components.setdefault(archive.stem, []).append(entry.name.removeprefix("./"))
                    if not entry.isfile():
                        patched_link = relocate_runtime_paths(entry.linkname.encode()).decode()
                        changed |= patched_link != entry.linkname
                        entry.linkname = patched_link
                        tar_out.addfile(entry)
                        continue
                    data = tar_in.extractfile(entry).read()
                    patched = relocate_runtime_paths(data)
                    changed |= patched != data
                    tar_out.addfile(entry, io.BytesIO(patched))
                    wine_path = entry.name.removeprefix("./").removeprefix("opt/wine/")
                    if archive.name == "rootfs.tzst" and entry.name.removeprefix("./").startswith("opt/wine/") and wine_path in wine_startup_paths:
                        wine_startup_files[wine_path] = {"bytes": len(patched), "sha256": hashlib.sha256(patched).hexdigest()}
                    if patched[:4] != b"\x7fELF" or int.from_bytes(patched[18:20], "little") != 183:
                        continue
                    digest = hashlib.sha256(patched).hexdigest()
                    if archive.parent.name == "box64" and entry.name.removeprefix("./") == "usr/local/bin/box64":
                        box64_files[archive.stem.removeprefix("box64-")] = {"bytes": len(patched), "sha256": digest}
                    name = f"libaura_{digest[:24]}.so"
                    (out / name).write_bytes(patched)
                    index[digest] = name
                    if entry.name.endswith("ld-linux-aarch64.so.1"):
                        loader = name
                    if patched[4] == 2:
                        phoff = struct.unpack_from("<Q", patched, 32)[0]
                        phsize, phnum = struct.unpack_from("<HH", patched, 54)
                        for number in range(phnum):
                            offset = phoff + number * phsize
                            if struct.unpack_from("<I", patched, offset)[0] == 1:
                                alignment = struct.unpack_from("<Q", patched, offset + 48)[0]
                                if alignment < 16384:
                                    unaligned.append(f"{archive.name}:{entry.name}")
                                    break
    if changed:
        temporary.replace(archive)
    else:
        temporary.unlink()

assert not loader and not box64_files and not wine_startup_files, "Legacy native runtime leaked into ARM64EC package"
(ROOT / "assets/aura-native-index.json").write_text(json.dumps({"abi": "bionic-arm64ec", "files": index}, sort_keys=True))
(ROOT / "assets/wincomponents/file-index.json").write_text(json.dumps(windows_components, sort_keys=True, indent=2) + "\n")
(ROOT.parents[1] / "native-audit.json").write_text(json.dumps({"staged": len(index), "requires16kReview": sorted(set(unaligned))}, indent=2))
print(f"Staged {len(index)} native ELF files; {len(set(unaligned))} require a 16 KB review.")
# Remove only unchanged files generated by a previous catalogue and no longer
# referenced by any current archive. Never remove handwritten or modified JNI libs.
for digest, name in previous_index.items():
    if digest in index or name != f"libaura_{digest[:24]}.so":
        continue
    obsolete = out / name
    if obsolete.is_file() and hashlib.sha256(obsolete.read_bytes()).hexdigest() == digest:
        obsolete.unlink()

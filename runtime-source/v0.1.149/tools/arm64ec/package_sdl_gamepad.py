#!/usr/bin/env python3
"""Add/update the compiled SDL Wine input backend; preserve every other runtime file."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import zstandard

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from audit_runtime_candidate import elf_info


def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def package(library):
    data = library.read_bytes()
    elf = elf_info(data)
    assert elf['machine'] == '0xb7' and elf['abi'] != 'glibc'
    assert elf['minimumLoadAlignment'] >= 16384
    assets = ROOT / 'engine/src/main/assets/arm64ec'
    runtime = assets / 'runtime.tzst'
    manifest = json.loads((assets / 'manifest.json').read_text())
    assert digest(runtime) == manifest['archives']['runtime.tzst']['sha256']
    replacements = {'usr/lib/libSDL2.so': data,
                    'usr/share/licenses/aura-sdl-gamepad/LICENSE':
                        (ROOT / 'tools/arm64ec/sdl-gamepad/LICENSE').read_bytes()}
    with tempfile.TemporaryDirectory(prefix='.sdl-', dir=assets) as temporary:
        target = Path(temporary) / 'runtime.tzst'
        with runtime.open('rb') as raw, target.open('wb') as out:
            with zstandard.ZstdDecompressor().stream_reader(raw) as reader, \
                    zstandard.ZstdCompressor(level=10).stream_writer(out) as writer:
                with tarfile.open(fileobj=reader, mode='r|') as old, \
                        tarfile.open(fileobj=writer, mode='w|') as new:
                    for entry in old:
                        if entry.name.removeprefix('./') not in replacements:
                            new.addfile(entry, old.extractfile(entry) if entry.isfile() else None)
                    for name, content in replacements.items():
                        entry = tarfile.TarInfo(name); entry.mode = 0o644; entry.size = len(content)
                        new.addfile(entry, io.BytesIO(content))
        for name, content in replacements.items():
            manifest['files'][name] = {'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
        manifest['sources']['sdl2'] = json.loads((ROOT / 'tools/arm64ec/sources.lock.json').read_text())['sources']['sdl2']
        manifest['archives']['runtime.tzst'] = {'bytes': target.stat().st_size, 'sha256': digest(target)}
        target.replace(runtime)
        (assets / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
    subprocess.run([sys.executable, str(ROOT / 'tools/prepare_engine.py')], check=True)
    print(json.dumps({'sdl2': manifest['files']['usr/lib/libSDL2.so'],
                      'runtime': manifest['archives']['runtime.tzst']}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, required=True)
    package(parser.parse_args().library)

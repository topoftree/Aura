#!/usr/bin/env python3
"""Rebuild only Aura's shared-memory shim without re-downloading the runtime.

The full assembler uses the same compiler recipe. This command streams the
existing archive, preserves every other entry and updates its manifest/index.
It does not build an APK or change container/runtime identity.
"""
import argparse
import copy
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
LIBRARY = 'usr/lib/libandroid-sysvshm.so'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def build(ndk, output):
    compiler = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    subprocess.run([
        str(compiler / 'aarch64-linux-android34-clang'),
        '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
        '-Wl,-z,max-page-size=16384', '-Wl,-soname,libandroid-sysvshm.so',
        str(ROOT / 'tools/arm64ec/sysvshm/android_sysvshm.c'), '-o', str(output),
    ], check=True)
    subprocess.run([str(compiler / 'llvm-strip'), '--strip-debug', str(output)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    args = parser.parse_args()
    assets = ROOT / 'engine/src/main/assets/arm64ec'
    manifest_path = assets / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    runtime = assets / 'runtime.tzst'
    expected = manifest['archives'][runtime.name]
    assert runtime.stat().st_size == expected['bytes'] and digest(runtime) == expected['sha256']
    with tempfile.TemporaryDirectory(prefix='.sysvshm-', dir=assets) as directory:
        stage = Path(directory)
        library = stage / 'libandroid-sysvshm.so'
        build(args.ndk, library)
        payload = library.read_bytes()
        replacement = stage / runtime.name
        replaced = 0
        with runtime.open('rb') as source, replacement.open('wb') as target:
            with zstandard.ZstdDecompressor().stream_reader(source) as reader, \
                    zstandard.ZstdCompressor(level=10).stream_writer(target) as writer:
                with tarfile.open(fileobj=reader, mode='r|') as original, \
                        tarfile.open(fileobj=writer, mode='w|') as updated:
                    for entry in original:
                        if entry.name.removeprefix('./') == LIBRARY:
                            assert entry.isfile() and not replaced
                            old = original.extractfile(entry).read()
                            assert hashlib.sha256(old).hexdigest() == manifest['files'][LIBRARY]['sha256']
                            entry = copy.copy(entry)
                            entry.size = len(payload)
                            updated.addfile(entry, io.BytesIO(payload))
                            replaced += 1
                        else:
                            updated.addfile(entry, original.extractfile(entry) if entry.isfile() else None)
        assert replaced == 1, 'Shared-memory library missing from runtime'
        manifest['files'][LIBRARY] = {'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}
        manifest['archives'][runtime.name] = {'bytes': replacement.stat().st_size, 'sha256': digest(replacement)}
        updated_manifest = stage / 'manifest.json'
        updated_manifest.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
        replacement.replace(runtime)
        updated_manifest.replace(manifest_path)
    subprocess.run([sys.executable, str(ROOT / 'tools/prepare_engine.py')], check=True)
    print(json.dumps({LIBRARY: manifest['files'][LIBRARY], runtime.name: manifest['archives'][runtime.name]}, indent=2))


if __name__ == '__main__':
    main()

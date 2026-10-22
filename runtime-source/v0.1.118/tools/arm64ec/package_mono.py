#!/usr/bin/env python3
"""Package the unchanged, pinned Wine Mono MSI for appwiz's offline installer."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import zstandard

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'engine/src/main/assets/arm64ec'
NAME = 'wine-mono-10.4.1-x86.msi'


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def audit():
    manifest = json.loads((ASSETS / 'wine-mono.json').read_text())
    archive = ASSETS / 'wine-mono.tzst'
    assert sha(archive) == manifest['archive']['sha256']
    assert archive.stat().st_size == manifest['archive']['bytes']
    seen = set()
    with archive.open('rb') as f, zstandard.ZstdDecompressor().stream_reader(f) as stream:
        with tarfile.open(fileobj=stream, mode='r|') as tar:
            for entry in tar:
                assert entry.isfile() and entry.name in manifest['files'] and entry.name not in seen
                record = manifest['files'][entry.name]
                assert entry.size == record['bytes']
                assert hashlib.file_digest(tar.extractfile(entry), 'sha256').hexdigest() == record['sha256']
                seen.add(entry.name)
    assert seen == set(manifest['files'])
    lock = json.loads((ROOT / 'tools/arm64ec/sources.lock.json').read_text())['sources']['wine-mono']
    assert manifest['files'][NAME] == {k: lock[k] for k in ('sha256', 'bytes')}
    return {'version': manifest['version'], 'installerSha256': lock['sha256'],
            'archiveSha256': manifest['archive']['sha256'], 'files': len(seen),
            'offlinePackage': 'verified', 'androidInstallation': 'not tested'}


def package(cache):
    source = cache / NAME
    lock = json.loads((ROOT / 'tools/arm64ec/sources.lock.json').read_text())['sources']['wine-mono']
    assert sha(source) == lock['sha256'] and source.stat().st_size == lock['bytes']
    files = {NAME: source, 'COPYING.wine-mono': Path(__file__).with_name('mono') / 'COPYING'}
    target = ASSETS / 'wine-mono.tzst'
    with target.open('wb') as f, zstandard.ZstdCompressor(level=10).stream_writer(f) as stream:
        with tarfile.open(fileobj=stream, mode='w|') as tar:
            for name, path in sorted(files.items()):
                entry = tarfile.TarInfo(name); entry.size = path.stat().st_size; entry.mode = 0o644
                with path.open('rb') as data: tar.addfile(entry, data)
    manifest = {'schema': 1, 'version': '10.4.1', 'source': lock,
                'correspondingSource': 'https://github.com/wine-mono/wine-mono/releases/download/wine-mono-10.4.1/wine-mono-10.4.1-src.tar.xz',
                'correspondingSourceSha256': '128e335396689e466dff5e1aa0770a714dbe3d16397fc4ddbb8a80999c67323b',
                'files': {name: {'sha256': sha(path), 'bytes': path.stat().st_size} for name, path in files.items()},
                'archive': {'sha256': sha(target), 'bytes': target.stat().st_size}}
    (ASSETS / 'wine-mono.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return audit()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path)
    args = parser.parse_args()
    print(json.dumps(package(args.cache) if args.cache else audit(), indent=2))

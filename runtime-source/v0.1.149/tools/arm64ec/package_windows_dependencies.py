#!/usr/bin/env python3
"""Package unmodified Unicode ICU 68.2 Windows binaries for Wine's ICU facade."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zipfile
import zstandard
from assemble import archive
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'engine/src/main/assets/arm64ec'
DLLS = ('icudt68.dll', 'icuin68.dll', 'icuuc68.dll')
EXPECTED = {directory + '/' + name for directory in ('system32', 'syswow64') for name in DLLS} | {'LICENSE.ICU'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def audit():
    manifest = json.loads((ASSETS / 'windows-dependencies.json').read_text())
    payload = ASSETS / 'windows-dependencies.tzst'
    assert payload.stat().st_size == manifest['archive']['bytes']
    assert sha(payload.read_bytes()) == manifest['archive']['sha256']
    assert manifest['schema'] == 1 and manifest['version'] == 'icu-68.2'
    assert set(manifest['files']) == EXPECTED
    lock = json.loads((ROOT / 'tools/arm64ec/sources.lock.json').read_text())['sources']
    assert manifest['sources'] == {key: lock[key] for key in ('icu-win64', 'icu-win32')}
    seen = set()
    for name, data, link in archive(payload):
        assert name in EXPECTED and name not in seen and data is not None and link is None
        record = manifest['files'][name]
        assert len(data) == record['bytes'] and sha(data) == record['sha256'], name
        if name.endswith('.dll'):
            expected_machine = '0x8664' if name.startswith('system32/') else '0x14c'
            assert PeImage(data).info['machine'] == expected_machine, name
        seen.add(name)
    assert seen == EXPECTED
    return {'version': manifest['version'], 'archiveSha256': manifest['archive']['sha256'],
            'verifiedDlls': 6, 'license': 'LICENSE.ICU', 'architectures': ['x64', 'x86']}


def package(cache):
    lock = json.loads((ROOT / 'tools/arm64ec/sources.lock.json').read_text())['sources']
    files = {}
    for key, source_dir, target in [('icu-win64', 'bin64', 'system32'), ('icu-win32', 'bin', 'syswow64')]:
        source = lock[key]
        path = cache / source['url'].rsplit('/', 1)[-1]
        assert path.stat().st_size == source['bytes'] and sha(path.read_bytes()) == source['sha256'], path
        with zipfile.ZipFile(path) as z:
            for name in DLLS:
                files[target + '/' + name] = z.read(source_dir + '/' + name)
            license_data = z.read('LICENSE')
            assert files.get('LICENSE.ICU', license_data) == license_data
            files['LICENSE.ICU'] = license_data
    payload = ASSETS / 'windows-dependencies.tzst'
    with payload.open('wb') as f, zstandard.ZstdCompressor(level=10).stream_writer(f) as stream:
        with tarfile.open(fileobj=stream, mode='w|') as tar:
            for name, data in sorted(files.items()):
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                entry.mode = 0o644
                tar.addfile(entry, io.BytesIO(data))
    manifest = {'schema': 1, 'version': 'icu-68.2',
                'sources': {key: lock[key] for key in ('icu-win64', 'icu-win32')},
                'correspondingSource': 'https://github.com/unicode-org/icu/releases/download/release-68-2/icu4c-68_2-src.tgz',
                'files': {name: {'sha256': sha(data), 'bytes': len(data)} for name, data in sorted(files.items())},
                'archive': {'sha256': sha(payload.read_bytes()), 'bytes': payload.stat().st_size}}
    (ASSETS / 'windows-dependencies.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return audit()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path)
    args = parser.parse_args()
    print(json.dumps(package(args.cache) if args.cache else audit(), indent=2))

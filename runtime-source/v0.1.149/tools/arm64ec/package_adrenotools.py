#!/usr/bin/env python3
"""Replace only the two source-built R8 loader libraries and their license record."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile

import zstandard
from build_adrenotools import ROOT, SOURCE, sha


def update_runtime_delivery():
    directory = ROOT / 'delivery/runtime-fex-smc1'
    path = directory / 'manifest.json'
    delivery = json.loads(path.read_text())
    item = next(row for row in delivery['files'] if row['path'] == 'engine/src/main/assets/arm64ec/runtime.tzst')
    parts, digest, size = [], hashlib.sha256(), 0
    with (ROOT / item['path']).open('rb') as source:
        for number in range(1, 32):
            data = source.read(45 * 1024 * 1024)
            if not data: break
            name = f'wine-runtime.{number:03d}.part'
            temporary = directory / (name + '.tmp')
            temporary.write_bytes(data); temporary.replace(directory / name)
            parts.append(dict(name=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
            digest.update(data); size += len(data)
        assert not source.read(1), 'Runtime exceeds bounded delivery part count'
    for old in item['parts']:
        if old['name'] not in {part['name'] for part in parts}:
            assert Path(old['name']).name == old['name'] and old['name'].startswith('wine-runtime.')
            obsolete = directory / old['name']
            if obsolete.exists():
                assert sha(obsolete) == old['sha256']; obsolete.unlink()
    item.update(bytes=size, sha256=digest.hexdigest(), parts=parts)
    path.write_text(json.dumps(delivery, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--built', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    record = json.loads((args.built / 'build.json').read_text())
    assert record['schema'] == 'AURA_ADRENOTOOLS_BUILD_V1'
    assert sha(Path(__file__).with_name('build_adrenotools.py')) == record['builderSha256']
    for name, digest in record['sources'].items(): assert sha(SOURCE / name) == digest
    replacements = {}
    assets = ROOT / 'engine/src/main/assets/arm64ec'
    archive, index = assets / 'runtime.tzst', assets / 'manifest.json'
    manifest = json.loads(index.read_text())
    assert sha(archive) == manifest['archives'][archive.name]['sha256']
    for name, output in record['outputs'].items():
        path = 'usr/lib/' + name
        assert sha(args.built / name) == output['sha256']
        assert manifest['files'][path]['sha256'] in (output['previousSha256'], output['sha256'])
        replacements[path] = (args.built / name).read_bytes()
    license_root = 'usr/share/licenses/aura-adrenotools-loader/'
    replacements[license_root + 'LICENSE'] = (SOURCE / 'LICENSE').read_bytes()
    replacements[license_root + 'linkernsbypass-LICENSE'] = (SOURCE / 'lib/linkernsbypass/LICENSE').read_bytes()
    replacements[license_root + 'build.json'] = (args.built / 'build.json').read_bytes()
    for name in ('system_linker.cpp', 'system_linker.h', 'android_linker_ns.cpp'):
        replacements[license_root + name] = (SOURCE / 'lib/linkernsbypass' / name).read_bytes()
    temporary = archive.with_suffix('.new')
    seen, preserved = set(), 0
    digest = lambda data: hashlib.sha256(data).hexdigest()
    try:
        with archive.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open('wb') as dst, zstandard.ZstdCompressor(level=10).stream_writer(dst) as writer:
                with tarfile.open(fileobj=reader, mode='r|') as original, tarfile.open(fileobj=writer, mode='w|') as out:
                    for entry in original:
                        name = entry.name.removeprefix('./')
                        data = original.extractfile(entry).read() if entry.isfile() else None
                        if data is not None: assert digest(data) == manifest['files'][name]['sha256'], name
                        if name in replacements:
                            assert data is not None and name not in seen
                            data = replacements[name]; entry.size = len(data); seen.add(name)
                        elif data is not None: preserved += 1
                        out.addfile(entry, io.BytesIO(data) if data is not None else None)
                    assert {'usr/lib/libadrenotools.so', 'usr/lib/libhook_impl.so'} <= seen
                    for name, data in sorted(replacements.items()):
                        if name not in seen:
                            assert name.startswith(license_root)
                            entry = tarfile.TarInfo(name); entry.size = len(data); entry.mode = 0o644
                            out.addfile(entry, io.BytesIO(data))
                while reader.read(1024 * 1024): pass
        for name, data in replacements.items(): manifest['files'][name] = dict(bytes=len(data), sha256=digest(data))
        manifest['adrenotoolsLoader'] = record
        manifest['archives'][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        index.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
    finally:
        temporary.unlink(missing_ok=True)
    metadata = Path(__file__).with_name('adrenotools-loader')
    metadata.mkdir(exist_ok=True)
    (metadata / 'build.json').write_text(json.dumps(record, indent=2) + '\n')
    lock_path = Path(__file__).with_name('sources.lock.json')
    lock = json.loads(lock_path.read_text())
    lock['builtOverrides']['adrenotools-loader'] = dict(revision=record['revision'],
        source=record['sourceRoot'], build='adrenotools-loader/build.json',
        files={name: manifest['files'][name] for name in replacements if name.startswith('usr/lib/')})
    lock_path.write_text(json.dumps(lock, indent=2) + '\n')
    subprocess.run([sys.executable, str(ROOT / 'tools/prepare_engine.py')], check=True)
    update_runtime_delivery()
    report = dict(revision=record['revision'], outputs=record['outputs'], otherFilesByteIdentical=preserved,
                  archive=manifest['archives'][archive.name], androidDeviceTested=False)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(dict(otherFilesByteIdentical=preserved, replacedNativeLibraries=len(record['outputs']))))


if __name__ == '__main__': main()

#!/usr/bin/env python3
"""Package the pinned Wine PE wait-queue backport without changing native Wine."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

import zstandard
from build_wait_queue import PATCH_SHA, SOURCE_COMMIT, SOURCE_SHA
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).resolve().parent / 'wait-queue'
OLD = {
    'opt/wine/lib/wine/aarch64-windows/ntdll.dll': 'f207a3462e98f8a5a31ca3846751cd57a61097447cfe4e3a434daa2aa278033e',
    'opt/wine/lib/wine/i386-windows/ntdll.dll': '7a7f08703c3d9763c5b0de65b138c785e7f37300a36813ca4bb84ce43df5a5ec',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def payload(folder):
    meta = json.loads((META / 'build.json').read_text())
    assert json.loads((folder / 'build.json').read_text()) == meta
    assert meta['source_commit'] == SOURCE_COMMIT and meta['source_sha256'] == SOURCE_SHA
    assert meta['patch_sha256'] == PATCH_SHA
    assert meta['build_script_sha256'] == digest((META.parent / 'build_wait_queue.py').read_bytes())
    result = {}
    for path in OLD:
        arch = path.split('/')[-2].removesuffix('-windows')
        data = (folder / f'fixed/{arch}-windows/ntdll.dll').read_bytes()
        record = meta['files']['fixed'][arch]
        assert digest(data) == record['sha256'] and len(data) == record['bytes']
        result[path] = data
    for name in ('COPYING.LIB', 'NOTICE', 'wine-wait-queue-lifetime.patch'):
        result['opt/wine/share/licenses/aura-wait-queue/' + name] = (META / name).read_bytes()
    assert digest((META / 'wine-wait-queue-lifetime.patch').read_bytes()) == PATCH_SHA
    return meta, result


def validate(path, old, new):
    assert digest(old) in {OLD[path], digest(new)}, ('Unrecognized runtime', path)
    before, after = PeImage(old), PeImage(new)
    assert before.info == after.info, ('PE architecture changed', path)
    checks = []
    for name, a, b in [('native', before, after), ('ec', before.ec_view(), after.ec_view())]:
        assert a.exports() == b.exports(), ('Export ABI changed', path, name)
        assert a.imports() == b.imports(), ('Import ABI changed', path, name)
        checks.append(dict(view=name, exports=len(b.exports()), imports=len(b.imports())))
    assert b'Wine builtin DLL' in new[:128]
    return dict(old_sha256=digest(old), sha256=digest(new), bytes=len(new), views=checks)


def write_catalogue(assets, meta):
    data = dict(schema=1, revision=meta['revision'], files={})
    for path, old in OLD.items():
        arch = path.split('/')[-2].removesuffix('-windows')
        record = meta['files']['fixed'][arch]
        data['files'][path] = dict(bytes=record['bytes'], sha256=record['sha256'], previousSha256=[old])
    (assets / 'wine-wait-queue.json').write_text(json.dumps(data, indent=2) + '\n')


def stage_from_cache(root, cache):
    meta, files = payload(cache / 'aura-wine-wait-queue')
    for path, data in files.items():
        target = root / path
        if path in OLD:
            validate(path, target.read_bytes(), data)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return meta


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--built', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    meta, replacements = payload(args.built)
    assets = ROOT / 'engine/src/main/assets/arm64ec'
    path, manifest_path = assets / 'runtime.tzst', assets / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    assert digest(path.read_bytes()) == manifest['archives'][path.name]['sha256']
    temporary = path.with_suffix('.new')
    report = dict(changed={}, added=[], unchanged_files=0, native_runtime_changed=False, android_execution=False)
    seen = set()
    try:
        with path.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open('wb') as target, zstandard.ZstdCompressor(level=10).stream_writer(target) as writer:
                with tarfile.open(fileobj=reader, mode='r|') as source, tarfile.open(fileobj=writer, mode='w|') as output:
                    for entry in source:
                        name = entry.name.removeprefix('./')
                        data = source.extractfile(entry).read() if entry.isfile() else None
                        if data is not None:
                            assert digest(data) == manifest['files'][name]['sha256'], name
                        if name in replacements:
                            assert name not in seen and data is not None
                            if name in OLD:
                                report['changed'][name] = validate(name, data, replacements[name])
                            data = replacements[name]
                            entry.size = len(data)
                            seen.add(name)
                        elif data is not None:
                            report['unchanged_files'] += 1
                        output.addfile(entry, io.BytesIO(data) if data is not None else None)
                    assert set(report['changed']) == set(OLD)
                    for name, data in sorted(replacements.items()):
                        if name in seen:
                            continue
                        entry = tarfile.TarInfo(name)
                        entry.size = len(data)
                        entry.mode = 0o644
                        output.addfile(entry, io.BytesIO(data))
                        report['added'].append(name)
                while reader.read(1024 * 1024):
                    pass
        for name, data in replacements.items():
            manifest['files'][name] = dict(bytes=len(data), sha256=digest(data))
        manifest['archives'][path.name] = dict(bytes=temporary.stat().st_size, sha256=digest(temporary.read_bytes()))
        manifest['wineWaitQueue'] = meta
        temporary.replace(path)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
        write_catalogue(assets, meta)
        report['archive'] = manifest['archives'][path.name]
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()

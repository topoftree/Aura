#!/usr/bin/env python3
"""Publish only two verified DXGI DLLs into the existing DirectX archive."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile

import zstandard
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
META = ROOT / 'tools/arm64ec/dxvk/initialization-build.json'
OLD = {
    'system32/dxgi.dll': '866773429baee99b2255daaecf09b3f730eb2be7ff58dcfb479941ac490c68c6',
    'syswow64/dxgi.dll': 'd8e487aeb4029e83f21f2eaaadc053a5c829ff432ddbe35164adf7af6199a0db',
}
INIT1 = {
    'system32/dxgi.dll': '7dcfdfc56ff9af70dec45e86b41949e3dcf51e618b4139d066b9b197db5cae62',
    'syswow64/dxgi.dll': '9d7bca3c26929d1db6ffc73025b674e23f00a01b1968da4f0ae402dd2f63099f',
}
INIT2 = {
    'system32/dxgi.dll': '313a921c0bc01eff84123e428d97e9a79899869081bd17b0e6d1001aae41cbea',
    'syswow64/dxgi.dll': '64062ce6275643192b7bcc86af6aaf22da5d810e4c6de8221345f8678939bf7c',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def validate(name, old, new):
    meta = json.loads(META.read_text())
    record = meta['files'][name]
    assert len(new) == record['bytes'] and digest(new) == record['sha256']
    assert digest(old) in {OLD[name], INIT1[name], INIT2[name], record['sha256']}, ('Unknown base DLL', name)
    before, after = PeImage(old), PeImage(new)
    assert before.info == after.info, ('PE architecture changed', name)
    assert after.info['chpeMetadata'] == name.startswith('system32/')
    before, after = before.ec_view(), after.ec_view()
    assert before.exports() == after.exports(), ('DXGI exports changed', name)
    imports = lambda pe: {(i['module'], s, i['delayed']) for i in pe.imports() for s in i['symbols']}
    added = imports(after) - imports(before)
    # Trace attribution and preserving the caller's Win32 error state. These
    # entry points are checked against both packaged Wine export tables by
    # verify_dxgi_init_contract.py; no other ABI additions are permitted.
    allowed = {('kernel32.dll', name, False) for name in ('GetCurrentProcessId', 'GetCurrentThreadId', 'SetLastError')}
    assert added <= allowed, ('Unexpected imports', name, added - allowed)
    assert meta['version'].encode() + b'\0' in new and b'AURA_DXVK_INIT_V1' in new
    return {'exports': len(after.exports()), 'new_imports': sorted(x[1] for x in added), 'architecture_preserved': True}


def catalogue(meta):
    return {'schema': 1, 'revision': meta['version'].removeprefix('v'),
            'fix': 'SINGLETON_EXCEPTION_RETRY', 'files': {
                name: {'bytes': entry['bytes'], 'sha256': entry['sha256'], 'previousSha256': [OLD[name], INIT1[name], INIT2[name]]}
                for name, entry in meta['files'].items()}}


def stage_from_cache(dx, cache):
    meta = json.loads(META.read_text())
    for name in OLD:
        new = (cache / ('aura-dxvk-dxgi-' + name.split('/')[0] + '.dll')).read_bytes()
        target = dx / name
        validate(name, target.read_bytes(), new)
        target.write_bytes(new)
    return meta


def write_catalogue(assets, meta):
    (assets / 'dxvk-dxgi.json').write_text(json.dumps(catalogue(meta), indent=2) + '\n')
    shutil.copyfile(META.parent / 'LICENSE.dxvk', assets / 'dxvk-LICENSE.txt')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--built', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    assets = ROOT / 'engine/src/main/assets/arm64ec'
    path, manifest_path = assets / 'directx.tzst', assets / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    assert digest(path.read_bytes()) == manifest['archives'][path.name]['sha256']
    meta = json.loads(META.read_text())
    replacements = {name: (args.built / name).read_bytes() for name in OLD}
    temporary = path.with_suffix('.new')
    report = {'changed': {}, 'unchanged_entries': 0, 'android_execution': 'not run'}
    try:
        with path.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open('wb') as target, zstandard.ZstdCompressor(level=10).stream_writer(target) as writer:
                with tarfile.open(fileobj=reader, mode='r|') as source, tarfile.open(fileobj=writer, mode='w|') as output:
                    for entry in source:
                        name = entry.name.removeprefix('./')
                        if name in replacements:
                            assert name not in report['changed'] and entry.isfile()
                            old = source.extractfile(entry).read()
                            new = replacements[name]
                            report['changed'][name] = validate(name, old, new)
                            entry.size = len(new)
                            output.addfile(entry, io.BytesIO(new))
                        else:
                            output.addfile(entry, source.extractfile(entry) if entry.isfile() else None)
                            report['unchanged_entries'] += 1
                while reader.read(1024 * 1024):
                    pass
        assert set(report['changed']) == set(OLD)
        manifest['archives'][path.name] = {'bytes': temporary.stat().st_size, 'sha256': digest(temporary.read_bytes())}
        manifest['dxvkDXGI'] = meta
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

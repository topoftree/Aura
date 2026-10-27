#!/usr/bin/env python3
"""Publish only two verified D3D9 DLLs into the existing DirectX archive."""
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
META = ROOT / 'tools/arm64ec/dxvk/build.json'
OLD = {
    'system32/d3d9.dll': 'dfbe9df61c5a915f65078cbd076bdaee86115ed8db3290e12acd4fd044ccbfc8',
    'syswow64/d3d9.dll': '627b14c48404aedbc987e5e012285db387460f94ac46251fd93f49e190a90dcf',
}

PREVIOUS = {
    'system32/d3d9.dll': 'c9b43f9da2f95f8043bdf9bc0f0368389b75a9560b75f74fec78cec8aa1157a9',
    'syswow64/d3d9.dll': 'd1069ea69417e098f8ef2259067cb5ea18372ed5dc8a7a8edb31ef0fd9dd30ac',
}
FLOW_IMPORTS = {'GetEnvironmentVariableW', 'GetModuleFileNameW', 'lstrcmpiW', 'CreateFileW',
    'GetFileSizeEx', 'CreateFileMappingW', 'MapViewOfFile', 'UnmapViewOfFile', 'CloseHandle',
    'GetCurrentProcessId', 'GetCurrentThreadId', 'QueryPerformanceFrequency', 'QueryPerformanceCounter',
    'GetSystemTimeAsFileTime', 'SetLastError', 'GetLastError'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def validate(name, old, new):
    meta = json.loads(META.read_text())
    record = meta['files'][name]
    assert len(new) == record['bytes'] and digest(new) == record['sha256']
    assert digest(old) in {OLD[name], PREVIOUS[name], record['sha256']}, ('Unknown base DLL', name)
    before, after = PeImage(old), PeImage(new)
    assert before.info == after.info, ('PE architecture changed', name)
    assert after.info['chpeMetadata'] == name.startswith('system32/')
    before, after = before.ec_view(), after.ec_view()
    assert before.exports() == after.exports(), ('D3D9 exports changed', name)
    imports = lambda pe: {(i['module'], s, i['delayed']) for i in pe.imports() for s in i['symbols']}
    added = imports(after) - imports(before)
    allowed = {('kernel32.dll', symbol, False) for symbol in FLOW_IMPORTS}
    assert added <= allowed, ('Unexpected imports', name, added - allowed)
    assert meta['version'].encode() + b'\0' in new
    return {'exports': len(after.exports()), 'new_imports': sorted(x[1] for x in added), 'architecture_preserved': True}


def catalogue(meta):
    return {'schema': 1, 'revision': meta['version'].removeprefix('v'),
            'fix': 'EMPTY_STRETCH_RECT_AND_MANUAL_FLOW_TRACE', 'files': {
                name: {'bytes': entry['bytes'], 'sha256': entry['sha256'], 'previousSha256': [OLD[name], PREVIOUS[name]]}
                for name, entry in meta['files'].items()}}


def stage_from_cache(dx, cache):
    meta = json.loads(META.read_text())
    for name in OLD:
        new = (cache / ('aura-dxvk-d3d9-' + name.split('/')[0] + '.dll')).read_bytes()
        target = dx / name
        validate(name, target.read_bytes(), new)
        target.write_bytes(new)
    return meta


def write_catalogue(assets, meta):
    (assets / 'dxvk-d3d9.json').write_text(json.dumps(catalogue(meta), indent=2) + '\n')
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
        manifest['dxvkD3D9'] = meta
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

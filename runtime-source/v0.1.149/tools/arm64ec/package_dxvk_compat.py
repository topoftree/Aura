#!/usr/bin/env python3
"""Package the verified compatibility cohort separately from the standard DXVK."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

import zstandard
from build_dxvk_compat import VERSION, DLLS, COMMIT
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
META = ROOT / 'tools/arm64ec/dxvk/compatibility-build.json'
STANDARD = '2.7.1-arm64ec-aura-flow1-batch1-init3'
PATHS = {f'{arch}/{dll}.dll' for arch in ('system32', 'syswow64') for dll in DLLS}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_archive(path):
    result = {}
    with path.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
        with tarfile.open(fileobj=reader, mode='r|') as archive:
            for entry in archive:
                name = entry.name.removeprefix('./')
                if name in PATHS:
                    assert name not in result and entry.isfile() and entry.size < 16 * 2**20
                    result[name] = archive.extractfile(entry).read()
    assert result.keys() == PATHS
    return result


def catalogue(assets, meta, standard):
    qcom2 = json.loads((ROOT / 'tools/arm64ec/dxvk/compatibility-qcom2-build.json').read_text())
    assert qcom2['version']=='v2.6.2-arm64ec-aura-qcom2' and set(qcom2['files'])==PATHS
    previous_compat = json.loads((ROOT / 'tools/arm64ec/dxvk/compatibility-qcom1-build.json').read_text())
    assert previous_compat['version'] == 'v2.6.2-arm64ec-aura-qcom1'
    assert previous_compat['source_commit'] == COMMIT and set(previous_compat['files']) == PATHS
    profiles = {name: dict(archive='arm64ec/' + archive, files={}) for name, archive in
                [('standard', 'directx.tzst'), ('qualcomm', 'dxvk-qualcomm.tzst')]}
    for name in sorted(PATHS):
        current = dict(bytes=len(standard[name]), sha256=digest(standard[name]))
        compat = {k: meta['files'][name][k] for k in ('bytes', 'sha256')}
        owned = {current['sha256'], compat['sha256'], previous_compat['files'][name]['sha256'], qcom2['files'][name]['sha256']}
        dll = Path(name).stem
        if dll in ('d3d9', 'd3d11', 'dxgi'):
            previous = json.loads((assets / f'dxvk-{dll}.json').read_text())['files'][name]
            assert all(previous[k] == current[k] for k in current)
            owned.update(previous['previousSha256'])
        for key, entry in [('standard', current), ('qualcomm', compat)]:
            profiles[key]['files'][name] = dict(entry, previousSha256=sorted(owned - {entry['sha256']}))
    return dict(schema=1, standardRevision=STANDARD,
                compatRevision=VERSION.removeprefix('v'), profiles=profiles)


def install(assets, built):
    meta = json.loads(META.read_text())
    assert meta['version'] == VERSION and meta['source_commit'] == COMMIT
    assert set(meta['files']) == PATHS
    standard = read_archive(assets / 'directx.tzst')
    records = {}
    payload = {}
    for name in sorted(PATHS):
        data = (built / name).read_bytes()
        assert meta['files'][name]['bytes'] == len(data) and meta['files'][name]['sha256'] == digest(data)
        pe, old = PeImage(data), PeImage(standard[name])
        assert pe.info == old.info == meta['files'][name]['pe']
        # D3D8/D3D10 forward into the same cohort; exported entry points stay compatible.
        assert pe.ec_view().exports() == old.ec_view().exports(), name
        if Path(name).stem in ('dxgi', 'd3d9', 'd3d11'):
            assert VERSION.encode() + b'\0' in data
        payload[name] = data
        records[name] = dict(exports=len(pe.ec_view().exports()), architecture=pe.info)
    target = assets / 'dxvk-qualcomm.tzst'
    temp = target.with_suffix('.new')
    try:
        with temp.open('wb') as raw, zstandard.ZstdCompressor(level=10).stream_writer(raw) as writer:
            with tarfile.open(fileobj=writer, mode='w|', format=tarfile.USTAR_FORMAT) as archive:
                for name, data in payload.items():
                    entry = tarfile.TarInfo(name)
                    entry.size = len(data)
                    entry.mode = 0o644
                    entry.mtime = int(meta['source_date_epoch'])
                    archive.addfile(entry, io.BytesIO(data))
        assert read_archive(temp) == payload
        temp.replace(target)
    finally:
        temp.unlink(missing_ok=True)
    (assets / 'dxvk-profiles.json').write_text(json.dumps(catalogue(assets, meta, standard), indent=2) + '\n')
    return meta, dict(files=records, archive=dict(bytes=target.stat().st_size, sha256=digest(target.read_bytes())),
                     android_execution='not run')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--built', type=Path, required=True)
    p.add_argument('--report', type=Path, required=True)
    a = p.parse_args()
    assets = ROOT / 'engine/src/main/assets/arm64ec'
    manifest = json.loads((assets / 'manifest.json').read_text())
    standard = assets / 'directx.tzst'
    assert manifest['archives']['directx.tzst'] == dict(bytes=standard.stat().st_size, sha256=digest(standard.read_bytes()))
    meta, report = install(assets, a.built)
    manifest['dxvkQualcomm'] = meta
    manifest['archives']['dxvk-qualcomm.tzst'] = report['archive']
    (assets / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
    lock_path = ROOT / 'tools/arm64ec/sources.lock.json'
    lock = json.loads(lock_path.read_text())
    lock['builtOverrides']['dxvk-qualcomm'] = dict(manifest=str(META.relative_to(ROOT)),
        sourceBase='v2.6.2', sourceCommit=COMMIT, revision=VERSION.removeprefix('v'),
        scope='PROPRIETARY_ADRENO_MATCHED_COHORT', files=sorted(PATHS))
    lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + '\n')
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

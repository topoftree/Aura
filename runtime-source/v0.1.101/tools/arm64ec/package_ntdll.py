#!/usr/bin/env python3
"""Replace only the audited native ntdll and add its source/licence metadata."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zstandard

from build_ntdll import META, sha
from verify_ntdll_layout import inspect as inspect_layout

REPO = Path(__file__).resolve().parents[2]
BASELINE = '7d6b493dbe50fb061f08d513f3827162c383a76840f3de3f0c607b5bd8af93ea'
CURRENT_META = META / 'proton-11.0-2-build.json'
WITHDRAWN = {
    '5ef66c18e9e32ce9c6ea515a13567af5e9bbbfd759d2ecafd49e5e85eb461ea7',  # 0.1.66: NLS layout
    '9b19ddd84e16f9cb1e9d1a6193ef05002c74b166c3643f03ce659d729278db97',  # 0.1.68: 11.0 source
}


def payload(folder):
    # Historical metadata cannot authorize rebuilding either withdrawn payload.
    supplied = json.loads((folder/'build.json').read_text())
    assert supplied['sha256'] not in WITHDRAWN, 'Withdrawn native runtime; use the matching Proton 11.0-2 candidate'
    build = json.loads(CURRENT_META.read_text())
    assert build['schema'] == 3 and build['sha256'] not in WITHDRAWN
    assert build['wineSourceBranch'] == 'proton_11.0-2' and build['hostToolsSameSource']
    assert build['threadProtocol']['cooperativeSelect'] == 4
    # Packaging itself changes the runtime manifest. A later reproduction may
    # check against that new manifest without changing any native build input;
    # check its ELF against the current files below instead of pinning this digest.
    comparison = dict(supplied, runtimeManifestSha256=build['runtimeManifestSha256'])
    assert comparison == build, 'Unreviewed native build metadata'
    binary = folder/'ntdll.so'
    assert sha(binary) == build['sha256'] and binary.stat().st_size == build['bytes']
    assert sha(META/'initial-ec-stack.patch') == build['patchSha256']
    assert sha(META.parent/'build_ntdll.py') == build['buildScriptSha256']
    files = json.loads((REPO/'engine/src/main/assets/arm64ec/manifest.json').read_text())['files']
    layout = inspect_layout(binary.read_bytes(), files)
    assert layout['passed'] and layout == build['compiledLayout']['candidate'], layout
    additions = {build['file']: binary.read_bytes()}
    for name in ('COPYING.WINE', 'COPYING.NTSYNC', 'NOTICE'):
        additions['opt/wine/share/licenses/aura-ntdll-stack/'+name] = (META/name).read_bytes()
    return build, additions


def historical_payload(folder):
    build = json.loads((META/'build.json').read_text())
    binary = folder/'ntdll.so'
    assert sha(binary) == build['sha256'] and binary.stat().st_size == build['bytes']
    assert sha(META/'initial-ec-stack.patch') == build['patchSha256']
    files = {build['file']: binary.read_bytes()}
    for name in ('COPYING.WINE', 'COPYING.NTSYNC', 'NOTICE'):
        files['opt/wine/share/licenses/aura-ntdll-stack/'+name] = (META/name).read_bytes()
    return build, files


def stage_from_cache(root, cache):
    build, files = payload(cache/'aura-ntdll-stack')
    for name, data in files.items():
        target = root/name; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
    return build


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--version', required=True, help='Explicit target Aura version for integration metadata')
    args = parser.parse_args()
    build, additions = payload(args.payload)
    assets = REPO/'engine/src/main/assets/arm64ec'
    archive = assets/'runtime.tzst'; metadata = assets/'manifest.json'
    manifest = json.loads(metadata.read_text())
    assert sha(archive) == manifest['archives']['runtime.tzst']['sha256']
    assert manifest['files'][build['file']]['sha256'] in {BASELINE, build['sha256'], *WITHDRAWN}
    temporary = archive.with_suffix('.new'); seen = set()
    try:
        with archive.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open('wb') as dst, zstandard.ZstdCompressor(level=10).stream_writer(dst) as writer:
                with tarfile.open(fileobj=reader, mode='r|') as original, tarfile.open(fileobj=writer, mode='w|') as output:
                    for entry in original:
                        name = entry.name.removeprefix('./')
                        if name in additions:
                            assert entry.isfile()
                            old = original.extractfile(entry).read()
                            assert hashlib.sha256(old).hexdigest() == manifest['files'][name]['sha256']
                            entry.size = len(additions[name]); output.addfile(entry, io.BytesIO(additions[name])); seen.add(name)
                        else:
                            output.addfile(entry, original.extractfile(entry) if entry.isfile() else None)
                    assert build['file'] in seen, 'Original ntdll was not found'
                    for name, data in sorted(additions.items()):
                        if name in seen:
                            continue
                        entry = tarfile.TarInfo(name); entry.size = len(data); entry.mode = 0o644
                        output.addfile(entry, io.BytesIO(data))
                while reader.read(1024*1024):
                    pass
        for name, data in additions.items():
            manifest['files'][name] = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        manifest['wineInitialStack'] = dict(build, integration=dict(
            version=args.version, nlsLayout='FLAT_PREFIX', androidDeviceTested=False))
        manifest['archives']['runtime.tzst'] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        metadata.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2)+'\n')
        print(json.dumps(manifest['archives']['runtime.tzst']))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()

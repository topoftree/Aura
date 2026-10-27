#!/usr/bin/env python3
"""Replace only ARM64X PE ntdll, retaining the native/i386 wait-completion pair."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zstandard

from build_ntdll import sha
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).resolve().parent / 'mgs-tick-thunk'
DIRECTORY = 'opt/wine/lib/wine/aarch64-windows/'
PREVIOUS = {DIRECTORY + 'ntdll.dll': 'a7829bd582ee844519952ec17241ae403fd036960e4a2c69d772e13b57bb33c3'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--built', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    meta = json.loads((args.built / 'build.json').read_text())
    assert meta['schema'] == 'WINE_MGS_TICK_THUNK_BUILD_V1'
    assert meta['revision'] == '11.0-2-aura-mgs-tick1' and not meta['defaultEnabled']
    assert meta['candidatePatchSha256'] == sha(META / 'wine-mgs-tick-thunk.patch')
    assert meta['helperSha256'] == sha(META / 'aura_mgs_tick_thunk.h')
    assert meta['buildScriptSha256'] == sha(META.parent / 'build_mgs_tick_thunk.py')
    assert meta['i386PeUnchanged'] and meta['nativeAndFexUnchanged']
    replacements = {}
    for path in PREVIOUS:
        data = (args.built / 'fixed/aarch64-windows/ntdll.dll').read_bytes()
        record = meta['peFiles']['fixed']['aarch64']
        assert digest(data) == record['sha256'] and len(data) == record['bytes']
        assert meta['peFiles']['baseline']['aarch64']['sha256'] == PREVIOUS[path]
        replacements[path] = data
    for name in ('NOTICE', 'COPYING.LIB', 'aura_mgs_tick_thunk.h', 'wine-mgs-tick-thunk.patch'):
        replacements['opt/wine/share/licenses/aura-mgs-tick-thunk/' + name] = (META / name).read_bytes()
    assets = ROOT / 'engine/src/main/assets/arm64ec'
    archive, index = assets / 'runtime.tzst', assets / 'manifest.json'
    manifest = json.loads(index.read_text())
    assert sha(archive) == manifest['archives'][archive.name]['sha256']
    temporary = archive.with_suffix('.new')
    seen, changed, added, unchanged, links = set(), {}, [], 0, {}
    try:
        with archive.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with temporary.open('wb') as dst, zstandard.ZstdCompressor(level=10).stream_writer(dst) as writer:
                with tarfile.open(fileobj=reader, mode='r|') as original, tarfile.open(fileobj=writer, mode='w|') as out:
                    for entry in original:
                        name = entry.name.removeprefix('./')
                        data = original.extractfile(entry).read() if entry.isfile() else None
                        if data is not None:
                            assert digest(data) == manifest['files'][name]['sha256'], name
                        elif entry.issym() or entry.islnk():
                            links[name] = (entry.type.decode(), entry.linkname)
                        if name in replacements:
                            assert name not in seen and data is not None
                            if name in PREVIOUS:
                                assert digest(data) in (PREVIOUS[name], digest(replacements[name]))
                                before, after = PeImage(data), PeImage(replacements[name])
                                assert before.info == after.info, name
                                assert before.exports() == after.exports() and before.imports() == after.imports(), name
                                changed[name] = dict(previousSha256=digest(data), sha256=digest(replacements[name]),
                                                     unchangedExports=True, unchangedImports=True)
                            data = replacements[name]
                            entry.size = len(data)
                            seen.add(name)
                        elif data is not None:
                            unchanged += 1
                        out.addfile(entry, io.BytesIO(data) if data is not None else None)
                    assert set(PREVIOUS) <= seen
                    for name, data in sorted(replacements.items()):
                        if name in seen:
                            continue
                        entry = tarfile.TarInfo(name)
                        entry.size, entry.mode = len(data), 0o644
                        out.addfile(entry, io.BytesIO(data))
                        added.append(name)
                while reader.read(1024 * 1024):
                    pass
        for name, data in replacements.items():
            manifest['files'][name] = dict(bytes=len(data), sha256=digest(data))
        # Re-read the completed archive before publication, including all unchanged files and links.
        verified, checked_links = 0, {}
        with temporary.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as reader:
            with tarfile.open(fileobj=reader, mode='r|') as result:
                for entry in result:
                    name = entry.name.removeprefix('./')
                    if entry.isfile():
                        data = result.extractfile(entry).read()
                        assert digest(data) == manifest['files'][name]['sha256'] and len(data) == manifest['files'][name]['bytes'], name
                        verified += 1
                    elif entry.issym() or entry.islnk():
                        checked_links[name] = (entry.type.decode(), entry.linkname)
            while reader.read(1024 * 1024):
                pass
        assert checked_links == links and verified == unchanged + len(replacements)
        manifest['mgsTickThunk'] = meta
        previous_archive = manifest['archives'][archive.name]
        manifest['archives'][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        index.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
        (META / 'build.json').write_text(json.dumps(meta, indent=2) + '\n')
        lockpath = META.parent / 'sources.lock.json'
        lock = json.loads(lockpath.read_text())
        lock['builtOverrides']['mgs-tick-thunk'] = dict(manifest='tools/arm64ec/mgs-tick-thunk/build.json',
            revision=meta['revision'], sourceBase=meta['sourceCommit'], files=list(PREVIOUS),
            defaultEnabled=False, scope='MgsGroundZeroes.exe')
        lockpath.write_text(json.dumps(lock, indent=2) + '\n')
        catalog_path = assets / 'wine-wait-queue.json'
        catalog = json.loads(catalog_path.read_text())
        entry = catalog['files'][DIRECTORY + 'ntdll.dll']
        assert entry['sha256'] in (PREVIOUS[DIRECTORY + 'ntdll.dll'], meta['peFiles']['fixed']['aarch64']['sha256'])
        entry['previousSha256'] = sorted(set(entry['previousSha256']) | set(PREVIOUS.values()))
        entry.update({key: meta['peFiles']['fixed']['aarch64'][key] for key in ('bytes', 'sha256')})
        catalog['mgsTickThunk'] = dict(revision=meta['revision'])
        catalog_path.write_text(json.dumps(catalog, indent=2) + '\n')
        report = dict(schema='WINE_MGS_TICK_THUNK_PACKAGE_V1', changed=changed, added=added,
            unchangedFiles=unchanged, verifiedFiles=verified, unchangedLinks=len(links),
            previousArchive=previous_archive, archive=manifest['archives'][archive.name],
            nativeAndFexUnchanged=True, i386PeUnchanged=True, gameFilesChanged=False, androidDeviceTested=False)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()

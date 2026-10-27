#!/usr/bin/env python3
"""Replace only the two FEX PE translators and add source/licence metadata."""
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
META = Path(__file__).resolve().parent / 'fex-image-pages'
DIRECTORY = 'opt/wine/lib/wine/aarch64-windows/'
PREVIOUS = {
    DIRECTORY + 'libarm64ecfex.dll': '833c58a451f6efbe5c893f148a860f28ed446bfeb0161c207834764039ba8195',
    DIRECTORY + 'libwow64fex.dll': '40544c5535bff5666a6fffbb10a0ddfd214d1d37c092da5caf498b4c6d02c827',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--built', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    meta = json.loads((args.built / 'build.json').read_text())
    assert meta['schema'] == 'FEX_IMAGE_PAGES_BUILD_V1'
    assert meta['revision'] == 'FEX-2610-aura-ip1-smc2-ir1-ca1' and not meta['defaultEnabled']
    assert meta['sources'] == json.loads((META / 'sources.json').read_text())
    for name, value in meta['inputs'].items():
        assert sha((META.parent if name.endswith('.py') or name.startswith(('fex-crysis-smc/', 'fex-ir-block-tail/', 'fex-jit-capture/', 'fex-shared-exits/')) else META) / name) == value, name
    replacements = {}
    for path in PREVIOUS:
        name = Path(path).name
        data = (args.built / name).read_bytes()
        assert digest(data) == meta['files'][name]['sha256'] and len(data) == meta['files'][name]['bytes']
        replacements[path] = data
    for name in ('NOTICE', 'LICENSE', 'ImageExecutableRanges.h', 'image-pages.patch', 'sources.json'):
        replacements['opt/wine/share/licenses/aura-fex-image-pages/' + name] = (META / name).read_bytes()
    for name in ('NOTICE', 'LICENSE', 'CrysisSmcPolicy.h', 'inline-smc.patch', 'test_policy.cpp', 'repro_smc.c',
                 'fast-checks.patch', 'FastCodeValidation.h', 'bounded-jit.patch',
                 'shared-exits.patch', 'test_shared_exits.py'):
        replacements['opt/wine/share/licenses/aura-fex-crysis-smc/' + name] = (META.parent / 'fex-crysis-smc' / name).read_bytes()
    for name in ('NOTICE', 'LICENSE', 'block-tail.patch', 'test_ir_block_tail.cpp', 'test_ir_block_tail.py'):
        replacements['opt/wine/share/licenses/aura-fex-ir-block-tail/' + name] = (META.parent / 'fex-ir-block-tail' / name).read_bytes()
    for name in ('NOTICE', 'LICENSE', 'generalize.patch', 'SharedSmcPolicy.h', 'test_policy.cpp', 'test_emitter.py'):
        replacements['opt/wine/share/licenses/aura-fex-shared-exits/' + name] = (META.parent / 'fex-shared-exits' / name).read_bytes()
    for name in ('NOTICE', 'LICENSE', 'capture.patch', 'JitCapture.h', 'JitCaptureFex.h', 'test_capture.cpp', 'test_adapter.py',
                 'cache-audit.patch', 'CacheAudit.h', 'CacheBufferPolicy.h', 'test_cache_audit.py'):
        replacements['opt/wine/share/licenses/aura-fex-jit-capture/' + name] = (META.parent / 'fex-jit-capture' / name).read_bytes()
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
                                assert digest(data) in (PREVIOUS[name], digest(replacements[name])), (name, 'unrecognized translator baseline')
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
        manifest['fexImagePages'] = meta
        previous_archive = manifest['archives'][archive.name]
        manifest['archives'][archive.name] = dict(bytes=temporary.stat().st_size, sha256=sha(temporary))
        temporary.replace(archive)
        index.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
        (META / 'build.json').write_text(json.dumps(meta, indent=2) + '\n')
        lockpath = META.parent / 'sources.lock.json'
        lock = json.loads(lockpath.read_text())
        lock['builtOverrides']['fex-image-pages'] = dict(manifest='tools/arm64ec/fex-image-pages/build.json',
            revision=meta['revision'], sourceBase=meta['sourceCommit'], files=list(PREVIOUS),
            defaultEnabled=False, scope='Gated image/SMC compatibility; general IR block-tail maintenance; bounded manual JIT diagnostics',
            crysisInlineSmc=meta['crysisInlineSmc'], crysisFastSmc=meta['crysisFastSmc'],
            irBlockTail=meta['irBlockTail'], jitCapture=meta['jitCapture'], crysisJitBudget=meta['crysisJitBudget'],
            crysisSharedSmcExits=meta['crysisSharedSmcExits'], sharedSmcExits=meta['sharedSmcExits'], cacheAudit=meta['cacheAudit'])
        lockpath.write_text(json.dumps(lock, indent=2) + '\n')
        report = dict(schema='FEX_IMAGE_PAGES_PACKAGE_V1', changed=changed, added=added,
            unchangedFiles=unchanged, verifiedFiles=verified, unchangedLinks=len(links),
            previousArchive=previous_archive, archive=manifest['archives'][archive.name],
            unixBridgesUnchanged=True, gameFilesChanged=False, androidDeviceTested=False)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()

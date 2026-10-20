#!/usr/bin/env python3
"""Prove deduplicated APK archives reconstruct the exact source runtime files."""
import argparse
from contextlib import contextmanager
import fnmatch
import hashlib
import json
from pathlib import Path
import tarfile
import zipfile

import zstandard
from stage_runtime_assets import PREFIX, REPORT, is_native, safe_name, sha256
from arm64ec.verify_ntdll_layout import LOGICAL as NTDLL_PATH, inspect as inspect_ntdll_layout


def fingerprint(stream, resolve=None):
    entries, references, native_count = {}, {}, 0
    with zstandard.ZstdDecompressor().stream_reader(stream) as reader, tarfile.open(fileobj=reader, mode='r|') as tar:
        for entry in tar:
            name = safe_name(entry.name)
            assert name not in entries, ('duplicate archive entry', name)
            if entry.issym() and entry.linkname.startswith(PREFIX):
                assert resolve is not None, ('unexpected source reference', name)
                digest = entry.linkname.removeprefix(PREFIX)
                size = resolve(digest)
                assert entry.size == 0
                references[name] = {'sha256': digest, 'bytes': size}
                value = ('file', digest, size, entry.mode)
            elif entry.isfile():
                file = tar.extractfile(entry)
                header = file.read(20)
                native_count += int(is_native(header))
                checksum = hashlib.sha256(header)
                while chunk := file.read(1024 * 1024):
                    checksum.update(chunk)
                value = ('file', checksum.hexdigest(), entry.size, entry.mode)
            else:
                value = (entry.type.decode(), entry.linkname, entry.mode)
            entries[name] = value
        while reader.read(1024 * 1024):
            pass
    return entries, references, native_count


def audit(root, generated=None, apk_path=None):
    source = root / 'engine/src/main/assets'
    generated = generated or root / 'engine/build/generated/arm64ecAssets'
    native = root / 'engine/src/main/jniLibs/arm64-v8a'
    patterns = [s for s in (root / 'engine/legacy-runtime-assets.txt').read_text().splitlines() if s and not s.startswith('#')]
    apk = zipfile.ZipFile(apk_path) if apk_path else None

    @contextmanager
    def asset(name):
        with (apk.open('assets/' + name) if apk else (generated / name).open('rb')) as file:
            yield file

    def document(name):
        with asset(name) as file:
            return json.load(file)

    try:
        index = document('aura-native-index.json')
        assert index == json.loads((source / 'aura-native-index.json').read_text()), 'Native index changed'
        metadata = document(REPORT)
        assert metadata['schema'] == 1 and metadata['referencePrefix'] == PREFIX
        resolved = {}

        def resolve(digest):
            name = index['files'].get(digest)
            assert name == f'libaura_{digest[:24]}.so' and len(digest) == 64, 'Unindexed native reference'
            if digest not in resolved:
                with (apk.open('lib/arm64-v8a/' + name) if apk else (native / name).open('rb')) as file:
                    header = file.read(20)
                    assert is_native(header), name
                    check = hashlib.sha256(header)
                    size = len(header)
                    while chunk := file.read(1024 * 1024):
                        size += len(chunk)
                        check.update(chunk)
                assert check.hexdigest() == digest, name
                resolved[digest] = size
            return resolved[digest]

        counts, files, saved, references, originals = {}, 0, 0, 0, 0
        expected_names = set()
        for path in sorted(source.rglob('*')):
            if not path.is_file():
                continue
            name = path.relative_to(source).as_posix()
            if any(fnmatch.fnmatch(name, p) for p in patterns):
                assert apk is None or 'assets/' + name not in apk.namelist(), name
                continue
            expected_names.add(name)
            if path.suffix != '.tzst':
                if name != 'arm64ec/manifest.json':
                    with asset(name) as file:
                        assert hashlib.file_digest(file, 'sha256').hexdigest() == sha256(path), name
                continue
            with path.open('rb') as file:
                original, _, original_native = fingerprint(file)
            with asset(name) as file:
                packaged, refs, embedded = fingerprint(file, resolve)
            assert original == packaged, ('Extracted runtime differs', name)
            assert embedded == 0, ('Duplicated native ELF payload', name)
            assert len(refs) == original_native, ('Missing native references', name)
            record = metadata['archives'][name]
            assert record['nativeFiles'] == refs, ('Reference index mismatch', name)
            assert record['sourceBytes'] == path.stat().st_size and record['sourceSha256'] == sha256(path), name
            size = apk.getinfo('assets/' + name).file_size if apk else (generated / name).stat().st_size
            with asset(name) as file:
                assert record['sha256'] == hashlib.file_digest(file, 'sha256').hexdigest(), name
            assert record['bytes'] == size, name
            saved += path.stat().st_size - size
            files += len(original)
            references += len(refs)
            originals += sum(r['bytes'] for r in refs.values())
            counts[name] = {'nativeReferences': len(refs), 'embeddedNativePayloads': embedded}
        assert set(counts) == set(metadata['archives'])
        assert set(resolved) == set(index['files']), 'Unreferenced or missing APK native library'
        assert metadata['uniqueNativeLibraries'] == len(resolved) and metadata['nativeReferences'] == references
        assert metadata['savedArchiveBytes'] == saved and metadata['removedNativeBytes'] == originals
        manifest = document('arm64ec/manifest.json')
        original_manifest = json.loads((source / 'arm64ec/manifest.json').read_text())
        for name, record in manifest['archives'].items():
            assert record == {k: metadata['archives']['arm64ec/' + name][k] for k in ('bytes', 'sha256')}, name
        manifest['archives'] = original_manifest['archives']
        assert manifest == original_manifest, 'Logical runtime manifest changed'
        ntdll_name = index['files'][manifest['files'][NTDLL_PATH]['sha256']]
        ntdll = apk.read('lib/arm64-v8a/' + ntdll_name) if apk else (native / ntdll_name).read_bytes()
        nls_layout = inspect_ntdll_layout(ntdll, manifest['files'])
        assert nls_layout['passed'], ('Native ntdll resolves NLS outside the packaged data directory', nls_layout)
        if apk is None:
            assert {p.relative_to(generated).as_posix() for p in generated.rglob('*') if p.is_file()} == expected_names | {REPORT}, 'Unexpected generated assets'
        return {'checkedArchives': len(counts), 'comparedEntries': files, 'nativeReferences': references,
                'uniqueNativeLibraries': len(resolved), 'embeddedNativePayloadsRemaining': 0,
                'removedNativeBytes': originals, 'savedArchiveBytes': saved, 'reconstructedSourceFilesIdentical': True,
                'nativeNlsLayout': nls_layout,
                'inspectedApk': str(apk_path) if apk_path else None, 'androidDeviceExecution': 'not run'}
    finally:
        if apk:
            apk.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--generated', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = audit(Path(__file__).resolve().parents[1], args.generated, args.apk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

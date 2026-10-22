#!/usr/bin/env python3
"""Generate APK assets with indexed AArch64 ELF payloads replaced by references.

Source archives stay complete for reproducible assembly. Executable bytes are
packaged once in lib/arm64-v8a; the Android installer resolves trusted tar
references against aura-native-index.json and the current nativeLibraryDir.
Selected data archives use stronger build-time compression without increasing
their 4 MiB decoder window or changing the installed files.
"""
import argparse
import copy
import fnmatch
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile

import zstandard

PREFIX = 'aura-native-sha256:'
REPORT = 'aura-native-packaging.json'
COMPACT_ARCHIVES = frozenset({
    'arm64ec/runtime.tzst',
    'arm64ec/prefix.tzst',
    'arm64ec/windows-dependencies.tzst',
})
ARCHIVE_CATALOGS = {'arm64ec/windows-dependencies.json': 'arm64ec/windows-dependencies.tzst'}
COMPACT_LEVEL = 19
COMPACT_WINDOW_LOG = 22


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def is_native(data):
    return data[:6] == b'\x7fELF\x02\x01' and data[18:20] == b'\xb7\0'


def safe_name(name):
    name = name.removeprefix('./')
    if name.startswith('/') or '..' in PurePosixPath(name).parts or '\\' in name:
        raise ValueError('Unsafe runtime entry: ' + name)
    return name


def native_entries(source, index, native):
    entries = {}
    with source.open('rb') as raw, zstandard.ZstdDecompressor().stream_reader(raw) as stream:
        with tarfile.open(fileobj=stream, mode='r|') as tar:
            for entry in tar:
                name = safe_name(entry.name)
                if not entry.isfile():
                    continue
                file = tar.extractfile(entry)
                header = file.read(20)
                if not is_native(header):
                    continue
                digest = hashlib.sha256(header)
                while chunk := file.read(1024 * 1024):
                    digest.update(chunk)
                value = digest.hexdigest()
                library = index.get(value)
                if library != f'libaura_{value[:24]}.so' or sha256(native / library) != value:
                    raise ValueError('Missing or mismatched native payload: ' + name)
                if name in entries:
                    raise ValueError('Duplicate native entry: ' + name)
                entries[name] = {'sha256': value, 'bytes': entry.size}
        while stream.read(1024 * 1024):
            pass
    return entries


def rewrite_archive(source, target, entries, compact=False):
    if not entries and not compact:
        shutil.copyfile(source, target)
        return
    compressor = (zstandard.ZstdCompressor(compression_params=
                  zstandard.ZstdCompressionParameters.from_level(COMPACT_LEVEL, window_log=COMPACT_WINDOW_LOG))
                  if compact else zstandard.ZstdCompressor(level=10))
    with source.open('rb') as raw, target.open('wb') as output:
        with zstandard.ZstdDecompressor().stream_reader(raw) as reader, \
                compressor.stream_writer(output) as writer:
            if not entries:
                # Preserve the entire tar stream, including metadata and padding.
                shutil.copyfileobj(reader, writer, 1024 * 1024)
                return
            with tarfile.open(fileobj=reader, mode='r|') as original, tarfile.open(fileobj=writer, mode='w|') as updated:
                for entry in original:
                    name = safe_name(entry.name)
                    if name in entries:
                        entry = copy.copy(entry)
                        entry.type = tarfile.SYMTYPE
                        entry.linkname = PREFIX + entries[name]['sha256']
                        entry.size = 0
                        entry.pax_headers = {k: v for k, v in entry.pax_headers.items() if k not in ('size', 'linkpath')}
                        updated.addfile(entry)
                    else:
                        updated.addfile(entry, original.extractfile(entry) if entry.isfile() else None)


def stage(assets, native, exclusions, output):
    assets, native, output = assets.resolve(), native.resolve(), output.resolve()
    if output == assets or output in assets.parents or assets in output.parents or output == native or output in native.parents or native in output.parents:
        raise ValueError('Output must be separate from runtime sources')
    index = json.loads((assets / 'aura-native-index.json').read_text())
    if index['abi'] != 'bionic-arm64ec':
        raise ValueError('Expected ARM64EC native index')
    patterns = [s.strip() for s in exclusions.read_text().splitlines() if s.strip() and not s.startswith('#')]
    output.parent.mkdir(parents=True, exist_ok=True)
    # Publish a complete generated directory only after every archive/index agrees.
    with tempfile.TemporaryDirectory(prefix='.aura-assets-', dir=output.parent) as temporary:
        generated = Path(temporary)
        records = {}
        referenced = set()
        for source in sorted(assets.rglob('*')):
            if not source.is_file():
                continue
            name = source.relative_to(assets).as_posix()
            if any(fnmatch.fnmatch(name, p) for p in patterns):
                continue
            target = generated / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.suffix != '.tzst':
                shutil.copyfile(source, target)
                continue
            entries = native_entries(source, index['files'], native)
            compact = name in COMPACT_ARCHIVES
            rewrite_archive(source, target, entries, compact)
            referenced.update(e['sha256'] for e in entries.values())
            records[name] = {'sourceBytes': source.stat().st_size, 'sourceSha256': sha256(source),
                             'bytes': target.stat().st_size, 'sha256': sha256(target), 'nativeFiles': entries}
            if compact:
                records[name]['compression'] = {'codec': 'zstd', 'level': COMPACT_LEVEL,
                                                'windowLog': COMPACT_WINDOW_LOG}
        if referenced != set(index['files']):
            raise ValueError('Archive native references differ from packaged native index')
        manifest_path = generated / 'arm64ec/manifest.json'
        manifest = json.loads(manifest_path.read_text())
        for name in manifest['archives']:
            record = records['arm64ec/' + name]
            manifest['archives'][name] = {key: record[key] for key in ('bytes', 'sha256')}
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
        for catalog_name, archive_name in ARCHIVE_CATALOGS.items():
            catalog_path = generated / catalog_name
            if not catalog_path.exists():
                continue
            catalog = json.loads(catalog_path.read_text())
            catalog['archive'] = {key: records[archive_name][key] for key in ('bytes', 'sha256')}
            catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
        report = {'schema': 1, 'referencePrefix': PREFIX, 'archives': records,
                  'nativeReferences': sum(len(r['nativeFiles']) for r in records.values()),
                  'uniqueNativeLibraries': len(referenced),
                  'removedNativeBytes': sum(e['bytes'] for r in records.values() for e in r['nativeFiles'].values()),
                  'savedArchiveBytes': sum(r['sourceBytes'] - r['bytes'] for r in records.values())}
        (generated / REPORT).write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')
        if output.exists():
            shutil.rmtree(output)
        generated.rename(output)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--exclusions', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = stage(args.assets, args.native, args.exclusions, args.output)
    print(json.dumps({k: v for k, v in report.items() if k != 'archives'}, indent=2))

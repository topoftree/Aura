#!/usr/bin/env python3
"""Check native ntdll's compiled directory relationship against the packaged NLS layout.

This reads the real ELF constants; it does not execute Android/Wine. A successful
wine --version or a checksum for an existing NLS file cannot validate this path.
"""
import argparse
import hashlib
import json
from pathlib import Path
import posixpath
import re
import zipfile

LOGICAL = 'opt/wine/lib/wine/aarch64-unix/ntdll.so'


def inspect(binary, files):
    assert binary[:4] == b'\x7fELF', 'Expected native ntdll ELF'
    strings = {s.decode('ascii') for s in re.findall(rb'[\x20-\x7e]{8,}', binary)}
    def constant(suffix):
        candidates = [s for s in strings if '/opt/wine/' in s and s.endswith(suffix)]
        assert len(candidates) == 1, (suffix, candidates)
        return candidates[0]
    compiled_lib = constant('/lib/wine')
    compiled_data = constant('/share/wine')
    relative = posixpath.relpath(compiled_data, compiled_lib)
    # init_paths removes /aarch64-unix before build_relative_path. Wine's
    # relative path is defined by LIBDIR/wine -> DATADIR/wine, not the host root.
    logical_lib = posixpath.dirname(posixpath.dirname(LOGICAL))
    resolved = posixpath.normpath(posixpath.join(logical_lib, relative))
    required = [resolved + '/nls/' + name for name in ('locale.nls', 'l_intl.nls')]
    return dict(ntdllSha256=hashlib.sha256(binary).hexdigest(),
                compiledLibDirectory=compiled_lib, compiledDataDirectory=compiled_data,
                relativeDataDirectory=relative, resolvedDataDirectory=resolved,
                nlsPaths=required, missingPaths=[p for p in required if p not in files],
                passed=resolved == 'opt/wine/share/wine' and all(p in files for p in required),
                androidExecuted=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        manifest = json.loads(apk.read('assets/arm64ec/manifest.json'))
        index = json.loads(apk.read('assets/aura-native-index.json'))['files']
        expected = manifest['files'][LOGICAL]['sha256']
        result = inspect(apk.read('lib/arm64-v8a/' + index[expected]), manifest['files'])
        assert result['ntdllSha256'] == expected, 'APK native hash differs from manifest'
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print(text, end='')
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()

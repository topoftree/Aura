#!/usr/bin/env python3
"""Check rebuilt ntdll's Android ABI and exported interface against the old payload."""
import argparse
import json
from pathlib import Path
from build_ntdll import sha
from verify_media import symbols, elf_info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, required=True)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--build-metadata', type=Path,
                        default=Path(__file__).with_name('ntdll')/'build.json',
                        help='Explicit candidate record; defaults to historical 0.1.66 metadata')
    args = parser.parse_args()
    assert sha(args.original) == '7d6b493dbe50fb061f08d513f3827162c383a76840f3de3f0c607b5bd8af93ea'
    build = json.loads(args.build_metadata.read_text())
    new = args.payload/'ntdll.so'
    assert sha(new) == build['sha256']
    tc = args.ndk/'toolchains/llvm/prebuilt/linux-x86_64'
    readelf = tc/'bin/llvm-readelf'
    old_exports, _, _ = symbols(readelf, args.original)
    exports, imports, weak = symbols(readelf, new)
    missing_exports = sorted(x for x in old_exports - exports if not x.startswith(('_R', '_ZN')))
    assert not missing_exports, missing_exports
    old_info, info = [elf_info(p.read_bytes()) for p in (args.original, new)]
    for key in ('abi', 'machine', 'soname', 'searchPaths'):
        assert info[key] == old_info[key], key
    assert set(info['needed']) == set(old_info['needed']) == {'libc.so', 'libdl.so'}
    assert info['minimumLoadAlignment'] >= 16384
    available = set()
    for dependency in info['needed']:
        available |= symbols(readelf, tc/'sysroot/usr/lib/aarch64-linux-android/34'/dependency)[0]
    unresolved = sorted(imports - available)
    assert not unresolved, unresolved
    data = new.read_bytes()
    assert b'com.termux' not in data and b'com.winlator/files' not in data
    assert not any(x in data for x in [b'libc.so.6', b'ld-linux-aarch64.so.1'])
    print(json.dumps(dict(passed=True, originalSha256=sha(args.original), newSha256=sha(new),
        originalExports=len(old_exports), currentExports=len(exports), lostNonRustExports=missing_exports,
        strongImports=len(imports), weakImports=len(weak), unresolvedAndroidApi34Imports=unresolved,
        staticNtsync=True, **info, androidDeviceTested=False), indent=2))


if __name__ == '__main__':
    main()

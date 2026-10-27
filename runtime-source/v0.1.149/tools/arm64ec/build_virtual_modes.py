#!/usr/bin/env python3
"""Rebuild the shipped Wine win32u with aspect-preserving virtual modes.

Input is the verified gpu-memory build tree. Unchanged sources are read-only
symlinks into that tree; the modified source and all build outputs are private.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

from build_ntdll import sha, relocate
from build_gpu_memory import input_hash, symbols, import_versions, PROVIDERS
from audit_runtime_candidate import elf_info

META = Path(__file__).with_name('virtual-modes')
SOURCE_HASH = 'a94aeb8ccc67e4c9d78785f977d3fc812aaa9407949b94d3aee76fbd22dd12d2'
BASE_HASH = '1adc95435684af0cee099d9a0d6faecf13f99902acf76400f9f0e38fbd9d8afc'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('base', 'work', 'ndk'):
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    base, work = a.base.resolve(), a.work.resolve()
    assert input_hash(base / 'source') == SOURCE_HASH
    assert sha(base / 'output/fixed.so') == BASE_HASH
    assert 'Pkg.Revision = 28.2.13676358' in (a.ndk / 'source.properties').read_text()
    work.mkdir(parents=True, exist_ok=False)
    source, native, output = [work / n for n in ('source', 'native', 'output')]
    for folder, dirs, files in os.walk(base / 'source'):
        target = source / Path(folder).relative_to(base / 'source')
        target.mkdir(parents=True, exist_ok=True)
        for name in files:
            (target / name).symlink_to(Path(folder) / name)
    edited = source / 'dlls/win32u/sysparams.c'
    edited.unlink()
    shutil.copyfile(base / 'source/dlls/win32u/sysparams.c', edited)
    native.mkdir(); output.mkdir()
    status = (base / 'native/config.status').read_text().replace(str(base / 'source'), str(source)).replace(str(base / 'native'), str(native))
    tc = a.ndk.resolve() / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    assert str(tc / 'aarch64-linux-android34-clang') in status
    (native / 'config.status').write_text(status)
    flags = ('-g -O2 -fPIC -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES -Wno-declaration-after-statement '
             '-Wno-implicit-function-declaration -Wno-int-conversion '
             f'-ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={native}=/aura/wine-build '
             f'-I{base}/freetype/include -I{base}/fontconfig')
    results = {}
    with (work / 'build.log').open('w') as log:
        def run(cmd, cwd=native):
            subprocess.run([str(v) for v in cmd], cwd=cwd, env=dict(os.environ, SOURCE_DATE_EPOCH='0'),
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        run(['sh', './config.status'])
        cfg = native / 'include/config.h'
        text = cfg.read_text()
        for key, value in PROVIDERS.items():
            text = text.replace('/* #undef ' + key + ' */', '#define ' + key + ' ' + value)
            assert '#define ' + key + ' ' + value in text
        cfg.write_text(text)
        (native / 'dlls/ntdll').mkdir(parents=True, exist_ok=True)
        shutil.copyfile(base / 'native/dlls/ntdll/ntdll.so', native / 'dlls/ntdll/ntdll.so')
        for variant in ('baseline', 'fixed'):
            if variant == 'fixed':
                run(['patch', '--batch', '--fuzz=0', '-p1', '-i', META / 'wine-aspect-modes.patch'], source)
            run(['make', '-j3', '-o', 'dlls/ntdll/ntdll.so', 'CFLAGS=' + flags, 'dlls/win32u/win32u.so'])
            sym = output / (variant + '-symbols.so')
            sym.write_bytes(relocate((native / 'dlls/win32u/win32u.so').read_bytes()))
            dll = output / (variant + '.so'); shutil.copyfile(sym, dll)
            run([tc / 'llvm-strip', '--strip-debug', dll])
            assert symbols(dll) == symbols(base / 'output/fixed.so')
            assert import_versions(dll) == import_versions(base / 'output/fixed.so')
            info = elf_info(dll.read_bytes())
            assert info['machine'] == '0xb7' and info['abi'] == 'bionic' and info['minimumLoadAlignment'] >= 16384
            assert set(info['needed']) == {'ntdll.so', 'libm.so', 'libdl.so', 'libc.so'}
            results[variant] = dict(bytes=dll.stat().st_size, sha256=sha(dll), symbolsSha256=sha(sym))
    # Building in a new path must reproduce the prior stripped runtime exactly.
    assert results['baseline']['sha256'] == BASE_HASH, 'Baseline did not reproduce; review before packaging'
    assert input_hash(base / 'source') == SOURCE_HASH, 'Read-only base changed'
    record = dict(schema='WINE_VIRTUAL_MODES_BUILD_V1', revision='11.0-2-aura-aspect-modes1',
                  sourceCommit='dcc50c55e2a4dbd06610e5b45d8872bc6f048633', sourceAndHeadersSha256=SOURCE_HASH,
                  packagedBeforeSha256=BASE_HASH, builderSha256=sha(Path(__file__)),
                  sourcePatchSha256=sha(META / 'wine-aspect-modes.patch'),
                  baseConfigurationSha256=sha(base / 'native/config.status'),
                  ndk='28.2.13676358', androidApi=34, baselineReproduced=True,
                  samePublicImportsAndExports=True, sameImportVersions=True, outputs=results,
                  elf=info, preservesGpuMemoryFix=True, androidDeviceTested=False)
    (output / 'build.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))


if __name__ == '__main__': main()

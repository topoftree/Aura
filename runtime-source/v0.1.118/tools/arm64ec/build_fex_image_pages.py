#!/usr/bin/env python3
"""Rebuild pinned FEX-2610 with the per-game image-page compatibility switch.

Requires CMake/Ninja, g++, zstandard and the pinned llvm-mingw toolchain. Sources
are supplied in --downloads from sources.json; this script never downloads code.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

from build_ntdll import sha
from build_sechost import TOOLCHAIN_SHA, TOOLCHAIN_URL
from pe_dependencies import PeImage

META = Path(__file__).resolve().parent / 'fex-image-pages'
REVISION = 'FEX-2610-aura-image-pages1'
FLAGS = ['-DCMAKE_BUILD_TYPE=Release', '-DBUILD_TESTING=OFF', '-DBUILD_THUNKS=OFF',
         '-DBUILD_FEXCONFIG=OFF', '-DENABLE_LTO=OFF', '-DENABLE_ASSERTIONS=OFF',
         '-DENABLE_GDB_SYMBOLS=OFF', '-DENABLE_CCACHE=OFF', '-DTUNE_ARCH=generic',
         '-DTUNE_CPU=none', '-DRANGES_NATIVE=OFF']


def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as src:
        for entry in src:
            entry.name = entry.name.partition('/')[2]
            if entry.name:
                src.extract(entry, destination, filter='data')


def tree(path):
    return {p.relative_to(path).as_posix(): ('link:' + os.readlink(p) if p.is_symlink() else sha(p))
            for p in path.rglob('*') if p.is_symlink() or p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('downloads', 'toolchain-archive', 'mingw', 'work', 'cmake-bin'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    work, mingw = args.work.resolve(), args.mingw.resolve()
    work.mkdir(parents=True, exist_ok=True)
    assert sha(args.toolchain_archive) == TOOLCHAIN_SHA, 'toolchain archive identity'
    # Verify every installed toolchain input against the pinned archive, not just its version label.
    verified_tools = 0
    with tarfile.open(args.toolchain_archive) as archive:
        for entry in archive:
            name = entry.name.partition('/')[2]
            if not name or not entry.isfile():
                continue
            target = mingw / name
            assert target.is_file() and target.stat().st_size == entry.size, name
            assert sha(target) == hashlib.file_digest(archive.extractfile(entry), 'sha256').hexdigest(), name
            verified_tools += 1
    sources = json.loads((META / 'sources.json').read_text())
    stage = work / 'verified-source'
    assert not stage.exists(), 'remove only the previous verified-source staging directory to retry'
    for entry in sources:
        archive = args.downloads / entry['archive']
        assert archive.stat().st_size == entry['bytes'] and sha(archive) == entry['sha256'], entry['path']
        extract(archive, stage / entry['path'])
    subprocess.run(['git', 'apply', '--check', META / 'image-pages.patch'], cwd=stage, check=True)
    subprocess.run(['git', 'apply', META / 'image-pages.patch'], cwd=stage, check=True)
    shutil.copyfile(META / 'ImageExecutableRanges.h', stage / 'Source/Windows/Common/ImageExecutableRanges.h')
    source = work / 'source'
    source_files = tree(stage)
    if source.exists():
        assert tree(source) == source_files, 'existing source differs from verified inputs'
        shutil.rmtree(stage)
    else:
        stage.rename(source)
    env = dict(os.environ, PATH=str(mingw / 'bin') + os.pathsep + str(args.cmake_bin.resolve()) +
               os.pathsep + os.environ['PATH'], SOURCE_DATE_EPOCH='0')
    output = work / 'output'
    output.mkdir(exist_ok=True)
    records = {}
    with (work / 'repro-build.log').open('w') as log:
        def run(command):
            subprocess.run([str(x) for x in command], env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        test = work / 'test-image-pages'
        run(['g++', '-std=c++20', '-O2', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
             '-Wno-changes-meaning', '-I' + str(META), '-I' + str(source / 'FEXCore/include'),
             '-I' + str(source / 'External/fmt/include'), META / 'test_image_pages.cpp', '-o', test])
        run([test])
        for arch, triple, target in [('arm64ec', 'arm64ec-w64-mingw32', 'arm64ecfex'),
                                     ('wow64', 'aarch64-w64-mingw32', 'wow64fex')]:
            build = work / ('build-' + arch)
            run(['cmake', '-S', source, '-B', build, '-G', 'Ninja',
                 '-DCMAKE_TOOLCHAIN_FILE=' + str(source / 'Data/CMake/toolchain_mingw.cmake'),
                 '-DMINGW_TRIPLE=' + triple, *FLAGS, '-DOVERRIDE_VERSION=' + REVISION,
                 '-DOVERRIDE_HASH=' + sources[0]['revision']])
            run(['cmake', '--build', build, '--target', target, '-j' + str(args.jobs)])
            dll = output / ('lib' + target + '.dll')
            shutil.copyfile(build / 'Bin' / dll.name, dll)
            run([mingw / 'bin/llvm-strip', '--strip-debug', dll])
            pe = PeImage(dll.read_bytes())
            assert pe.info['machine'] == ('0x8664' if arch == 'arm64ec' else '0xaa64')
            assert b'FEX_WINDOWSIMAGEPAGES' in dll.read_bytes()
            records[dll.name] = dict(bytes=dll.stat().st_size, sha256=sha(dll), pe=pe.info,
                                     exportCount=len(pe.exports()), importCount=len(pe.imports()))
    metadata = dict(schema='FEX_IMAGE_PAGES_BUILD_V1', revision=REVISION, sourceCommit=sources[0]['revision'],
                    sourceFilesSha256=hashlib.sha256(json.dumps(source_files, sort_keys=True).encode()).hexdigest(),
                    sources=sources, toolchain=dict(url=TOOLCHAIN_URL, sha256=TOOLCHAIN_SHA, verifiedFiles=verified_tools),
                    flags=FLAGS, sourceDateEpoch=0, inputs={p.name: sha(p) for p in
                        [Path(__file__), META / 'ImageExecutableRanges.h', META / 'image-pages.patch', META / 'test_image_pages.cpp']},
                    files=records, defaultEnabled=False, process='MgsGroundZeroes.exe', androidDeviceTested=False)
    (output / 'build.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()

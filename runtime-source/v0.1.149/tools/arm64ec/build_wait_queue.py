#!/usr/bin/env python3
"""Rebuild Wine 11.0-2 PE ntdll with the upstream wait-queue lifetime correction.

Keeps the Android vendor patches and server protocol from the pinned source.
Does not rebuild native ntdll.so, wineserver, FEX, or graphics drivers. Outputs
baseline and fixed DLLs so ABI and code changes can be inspected independently.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from build_ntdll import extract, sha, SOURCE_SHA, SOURCE_COMMIT
from build_sechost import TOOLCHAIN_SHA, TOOLCHAIN_URL
from pe_dependencies import PeImage

HERE = Path(__file__).resolve().parent
META = HERE / 'wait-queue'
PATCH_SHA = '204455c164263c2764f67aa4550acf2be5dafacea9a2b0332439f562caf3a2e5'
SUBDIRS = 'include nls tools/widl tools/winebuild tools/winegcc tools/wrc dlls/winecrt0 libs/compiler-rt libs/musl libs/tomcrypt dlls/ntdll'
ARCHES = ('i386', 'aarch64')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'toolchain-archive', 'mingw', 'work'):
        p.add_argument('--'+name, type=Path, required=True)
    args = p.parse_args()
    assert sha(args.source) == SOURCE_SHA
    assert sha(args.toolchain_archive) == TOOLCHAIN_SHA
    patch = META/'wine-wait-queue-lifetime.patch'
    assert sha(patch) == PATCH_SHA
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    source, build, output = [work/n for n in ('source', 'build', 'output')]
    for directory in (source, build, output): directory.mkdir()
    extract(args.source, source, wine=True)
    toolchain = args.mingw.resolve()
    env = os.environ.copy()
    env.update(PATH=str(toolchain)+os.pathsep+env['PATH'], CC='gcc', CXX='g++',
               SOURCE_DATE_EPOCH='0', XDG_CACHE_HOME=str(work/'cache'),
               PKG_CONFIG_PATH='', PKG_CONFIG_LIBDIR=str(work/'empty-pkg-config'),
               CFLAGS=f'-O2 -ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={build}=/aura/wine-build',
               CROSSCFLAGS=f'-g0 -O2 -ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={build}=/aura/wine-build')
    array = (source/'build-scripts/build-step-arm64ec.sh').read_text().split('PATCHES=(',1)[1].split('\n    )',1)[0]
    patches = ['server_protocol.def.patch']+re.findall(r'^\s*"([^"\n]+\.patch)"', array, re.M)
    assert len(patches) > 40 and patches[-1] == 'ntsync_userspace.patch'
    targets = [f'dlls/ntdll/{arch}-windows/ntdll.dll' for arch in ARCHES]
    records = {}
    with (work/'build.log').open('w') as log:
        def run(command, cwd=source):
            subprocess.run([str(x) for x in command], cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        for name in patches: run(['git', 'apply', source/'android/patches'/name])
        for command in [['tools/make_requests'], ['tools/make_specfiles'],
                        ['python3', 'dlls/winevulkan/make_vulkan', '-x', 'vk.xml', '-X', 'video.xml'],
                        ['autoconf'], ['autoheader']]: run(command)
        run([source/'configure', '--no-create', '--enable-win64', '--enable-archs=i386,arm64ec,aarch64',
             '--with-mingw=clang', '--disable-tests', '--disable-win16',
             '--without-x', '--without-wayland', '--without-gstreamer', '--without-vulkan',
             '--without-opengl', '--without-alsa', '--without-pulse', '--without-freetype',
             '--without-fontconfig'], build)
        status = build/'config.status'
        text = status.read_text()
        needle = '"tools/makedep" -C -i'
        assert text.count(needle) == 1
        status.write_text(text.replace(needle, f'"tools/makedep" -C "SUBDIRS={SUBDIRS}" -i'))
        run(['sh', './config.status'], build)
        for stage in ('baseline', 'fixed'):
            if stage == 'fixed': run(['git', 'apply', patch])
            run(['make', '-j4', *targets], build)
            records[stage] = {}
            for arch, target in zip(ARCHES, targets):
                dst = output/stage/f'{arch}-windows/ntdll.dll'
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(build/target, dst)
                run([toolchain/'llvm-strip', '--strip-debug', dst], build)
                pe = PeImage(dst.read_bytes())
                assert pe.info['machine'] == ('0x14c' if arch == 'i386' else '0xaa64'), pe.info
                assert pe.info['chpeMetadata'] == (arch == 'aarch64')
                records[stage][arch] = dict(bytes=dst.stat().st_size, sha256=sha(dst),
                                            machine=pe.info['machine'], exports=len(pe.exports()))
        for arch in ARCHES:
            before = PeImage((output/f'baseline/{arch}-windows/ntdll.dll').read_bytes())
            after = PeImage((output/f'fixed/{arch}-windows/ntdll.dll').read_bytes())
            for a,b in ((before, after),(before.ec_view(), after.ec_view())):
                assert a.exports() == b.exports()
                assert a.imports() == b.imports()
    shutil.copyfile(source/'COPYING.LIB', output/'COPYING.LIB')
    metadata = dict(schema=1, revision='11.0-2-aura-wait-queue1', source_commit=SOURCE_COMMIT,
                    source_sha256=SOURCE_SHA, toolchain_url=TOOLCHAIN_URL, toolchain_sha256=TOOLCHAIN_SHA,
                    upstream_commit='81e52628b25ac173d04796e8a9b9cda637d3e233', patch_sha256=PATCH_SHA,
                    vendor_patches=[dict(file=n,sha256=sha(source/'android/patches'/n)) for n in patches],
                    build_script_sha256=sha(Path(__file__)), files=records,
                    native_runtime_changed=False, android_game_execution=False)
    (output/'build.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__': main()

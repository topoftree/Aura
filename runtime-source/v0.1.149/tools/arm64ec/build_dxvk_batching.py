#!/usr/bin/env python3
"""Build the opt-in D3D11 submission experiment from pinned DXVK 2.7.1.

Only d3d11.dll is published; DXGI is built for its import library. The existing
DXGI/D3D9 runtime files are retained. No downloads or Android packaging here.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

from build_dxvk import COMMIT, SUBMODULES, output, run, sha
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / 'tools/arm64ec/dxvk/submission-batching.patch'
VERSION = 'v2.7.1-arm64ec-aura-batch1'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'toolchain', 'glslang-bin', 'ninja-bin', 'work'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    source, work = args.source.resolve(), args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    assert output(['git', 'rev-parse', 'HEAD'], source) == COMMIT
    for name, revision in SUBMODULES.items():
        assert output(['git', 'rev-parse', 'HEAD'], source / name) == revision
        assert not output(['git', 'status', '--porcelain', '--untracked-files=no'], source / name)
    changed = set(output(['git', 'diff', '--name-only'], source).splitlines())
    # A disk-constrained build may link to the independently verified submodules.
    changed.difference_update(SUBMODULES)
    expected = {'src/util/util_flush.h', 'src/util/util_flush.cpp',
                'src/d3d11/d3d11_context_imm.cpp', 'src/d3d11/d3d11_context_imm.h', 'src/d3d11/d3d11_options.h',
                'src/d3d11/d3d11_options.cpp'}
    assert changed <= expected | {'version.h.in'}, changed
    if not changed & expected:
        run(['git', 'apply', '--check', PATCH], source)
        run(['git', 'apply', PATCH], source)
    # Verify the exact functional diff, not just a successful patch reverse check.
    assert output(['git', 'diff', '--', *sorted(expected)], source) == PATCH.read_text().strip()
    template = source / 'version.h.in'
    original = output(['git', 'show', 'HEAD:version.h.in'], source) + '\n'
    versioned = original.replace('@VCS_TAG@', VERSION)
    assert template.read_text() in (original, versioned)
    template.write_text(versioned)
    env = os.environ.copy()
    env['PATH'] = os.pathsep.join([str(args.toolchain.resolve() / 'bin'),
        str(args.glslang_bin.resolve()), str(args.ninja_bin.resolve()), env['PATH']])
    env['SOURCE_DATE_EPOCH'] = output(['git', 'show', '-s', '--format=%ct', COMMIT], source)
    records = {}
    for arch, compiler, family in [('syswow64', 'i686', 'x86'), ('system32', 'arm64ec', 'aarch64')]:
        cross = work / f'{arch}.ini'
        cross.write_text(f'''[binaries]
c = '{compiler}-w64-mingw32-clang'
cpp = '{compiler}-w64-mingw32-clang++'
ar = 'llvm-ar'
strip = 'llvm-strip'
windres = '{compiler}-w64-mingw32-windres'
widl = '{compiler}-w64-mingw32-widl'
pkg-config = 'pkg-config'
[host_machine]
system = 'windows'
cpu_family = '{family}'
cpu = '{family}'
endian = 'little'
[built-in options]
c_link_args = ['-Wl,--no-insert-timestamp']
cpp_link_args = ['-Wl,--no-insert-timestamp']
''')
        build = work / ('build-' + arch)
        command = [sys.executable, '-m', 'mesonbuild.mesonmain', 'setup', build, source,
            '--cross-file', cross, '-Dbuildtype=release', '-Dstrip=true', '-Dbuild_id=true',
            '-Denable_d3d9=false', '-Denable_d3d8=false', '-Denable_dxgi=true',
            '-Denable_d3d10=false', '-Denable_d3d11=true', '--wrap-mode=nodownload']
        if (build / 'build.ninja').exists():
            command.append('--reconfigure')
        run(command, env=env)
        run(['ninja', '-C', build, '-j', str(args.jobs), 'src/d3d11/d3d11.dll'], env=env)
        target = work / 'output' / arch / 'd3d11.dll'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build / 'src/d3d11/d3d11.dll', target)
        run(['llvm-strip', '--strip-all', target], env=env)
        data = target.read_bytes()
        info = PeImage(data).info
        assert info['machine'] == ('0x8664' if arch == 'system32' else '0x14c'), info
        assert info['chpeMetadata'] == (arch == 'system32'), info
        assert VERSION.encode() + b'\0' in data
        assert b'd3d11.auraBatchSubmissions\0' in data and b'AURA_D3D11_BATCH_V1' in data
        records[arch + '/d3d11.dll'] = dict(bytes=len(data), sha256=sha(target), pe=info)
    manifest = dict(schema='AURA_DXVK_BATCH_BUILD_V1', version=VERSION,
        source_url='https://github.com/doitsujin/dxvk', source_commit=COMMIT,
        submodules=SUBMODULES, patch_sha256=sha(PATCH), script_sha256=sha(Path(__file__)),
        source_date_epoch=env['SOURCE_DATE_EPOCH'], files=records, default_enabled=False,
        policy='HINTS_6_PENDING_2', android_game_execution='not run',
        source_files={name: sha(source / name) for name in sorted(expected)})
    manifest['tools'] = {name: sha(path) for name, path in {
        'clang': args.toolchain / 'bin/clang', 'lld': args.toolchain / 'bin/lld',
        'strip': args.toolchain / 'bin/llvm-strip',
        'glslang': args.glslang_bin / 'glslangValidator', 'ninja': args.ninja_bin / 'ninja',
    }.items()}
    manifest['compiler_version'] = output([args.toolchain / 'bin/clang', '--version']).splitlines()[0]
    manifest['meson'] = __import__('mesonbuild.coredata', fromlist=['version']).version
    (work / 'output/build.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()

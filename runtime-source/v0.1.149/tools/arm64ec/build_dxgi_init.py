#!/usr/bin/env python3
"""Build only DXGI's exception-safe initialization from pinned DXVK 2.7.1.

Inputs are local and hash-recorded. No downloads, APK build or remote writes.
"""
import argparse
import json
import os
import re
from pathlib import Path
import shutil
import sys

from build_dxvk import COMMIT, SUBMODULES, output, run, sha
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / 'tools/arm64ec/dxvk/initialization.patch'
VERSION = 'v2.7.1-arm64ec-aura-init3'
CHANGED = {'src/util/util_singleton.h', 'src/util/util_aura_init.h',
           'src/dxgi/dxgi_main.cpp', 'src/dxvk/dxvk_instance.cpp',
           'src/dxvk/dxvk_device_info.cpp', 'src/dxvk/dxvk_device_filter.cpp',
           'src/dxgi/dxgi_aura_display.h', 'src/dxgi/dxgi_factory.cpp',
           'src/dxgi/dxgi_output.cpp', 'src/dxgi/dxgi_swapchain.cpp', 'src/dxgi/dxgi_swapchain.h'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'toolchain', 'glslang-bin', 'ninja-bin', 'work'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=2)
    args = parser.parse_args()
    source, work = args.source.resolve(), args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    assert output(['git', 'rev-parse', 'HEAD'], source) == COMMIT
    for name, revision in SUBMODULES.items():
        assert output(['git', 'rev-parse', 'HEAD'], source / name) == revision
        assert not output(['git', 'status', '--porcelain', '--untracked-files=no'], source / name)
    changed = set(output(['git', 'diff', '--name-only'], source).splitlines()) - SUBMODULES.keys()
    assert changed <= CHANGED | {'version.h.in'}, changed
    if not changed & CHANGED:
        run(['git', 'apply', '--check', PATCH], source)
        run(['git', 'apply', PATCH], source)
    run(['git', 'add', '-N', 'src/util/util_aura_init.h', 'src/dxgi/dxgi_aura_display.h'], source)
    assert output(['git', 'diff', '--', *sorted(CHANGED)], source) == PATCH.read_text().strip()
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
            '-Denable_d3d10=false', '-Denable_d3d11=false', '--wrap-mode=nodownload']
        if (build / 'build.ninja').exists():
            command.append('--reconfigure')
        run(command, env=env)
        run(['ninja', '-C', build, '-j', str(args.jobs), 'src/dxgi/dxgi.dll'], env=env)
        target = work / 'output' / arch / 'dxgi.dll'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build / 'src/dxgi/dxgi.dll', target)
        run(['llvm-strip', '--strip-all', target], env=env)
        data = target.read_bytes()
        info = PeImage(data).info
        assert info['machine'] == ('0x8664' if arch == 'system32' else '0x14c'), info
        assert info['chpeMetadata'] == (arch == 'system32'), info
        assert VERSION.encode() + b'\0' in data and b'AURA_DXVK_INIT_V1' in data
        assert b'AURA_DXGI_MODE_V1' in data and b'AURA_BF3_DISPLAY_TRACE\0' in data
        records[arch + '/dxgi.dll'] = dict(bytes=len(data), sha256=sha(target), pe=info,
            unstripped_sha256=sha(build / 'src/dxgi/dxgi.dll'))
    feature_list = (source / 'src/dxvk/dxvk_device_info.cpp').read_text().split('return {{', 1)[1].split('}};', 1)[0]
    entries = re.findall(r'^\s+ENABLE_(?:EXT_FEATURE|FEATURE|EXT)\(([^)]+)\)', feature_list, re.M)
    required = {str(i): parts[-2].strip() for i, entry in enumerate(entries, 1)
                if (parts := entry.split(','))[-1].strip() == 'true'}
    assert len(entries) == 122 and required['39'] == 'textureCompressionBC'
    meta = dict(schema='AURA_DXGI_INIT_BUILD_V1', version=VERSION,
        source_url='https://github.com/doitsujin/dxvk', source_commit=COMMIT,
        submodules=SUBMODULES, patch_sha256=sha(PATCH), script_sha256=sha(Path(__file__)),
        source_date_epoch=env['SOURCE_DATE_EPOCH'], files=records,
        fix='SINGLETON_EXCEPTION_RETRY', trace_default=False, trace_limit_lines=64,
        trace_limit_ms=120000, android_game_execution='not run',
        adapter_rejection=dict(schema='AURA_DXVK_INIT_V1', feature_ids=required,
            feature_id_scope='ONE_BASED_PINNED_FEATURE_LIST', extra_gpu_queries=False,
            feature_result_bits={'0': 'FEATURE_SUPPORTED', '1': 'EXTENSION_GATED',
                                 '2': 'EXTENSION_SUPPORTED', '3': 'EXTENSION_ENABLED'}),
        display_trace=dict(schema='AURA_DXGI_MODE_V1', default_enabled=False, executable='bf3.exe',
                           limit_rows=384, limit_ms=120000, line_bytes=640, per_frame=False),
        source_files={name: sha(source / name) for name in sorted(CHANGED)})
    meta['tools'] = {name: sha(path) for name, path in {
        'clang': args.toolchain / 'bin/clang', 'lld': args.toolchain / 'bin/lld',
        'strip': args.toolchain / 'bin/llvm-strip',
        'glslang': args.glslang_bin / 'glslangValidator', 'ninja': args.ninja_bin / 'ninja',
    }.items()}
    meta['compiler_version'] = output([args.toolchain / 'bin/clang', '--version']).splitlines()[0]
    meta['meson'] = __import__('mesonbuild.coredata', fromlist=['version']).version
    (work / 'output/build.json').write_text(json.dumps(meta, indent=2) + '\n')
    print(json.dumps(meta, indent=2))


if __name__ == '__main__':
    main()

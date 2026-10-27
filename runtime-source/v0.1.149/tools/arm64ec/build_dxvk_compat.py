#!/usr/bin/env python3
"""Build a matched x86/ARM64EC DXVK 2.6.2 cohort for proprietary Adreno.

Pinned upstream has real non-maintenance5/non-GPL implementations. Keep its
requirements intact; port Aura's zero-extent, singleton, batching and traces.
Downloads, publication and Android execution are separate.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

from build_dxvk import SUBMODULES, output, run, sha
from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'tools/arm64ec/dxvk'
PATCH = DATA / 'compatibility.patch'
COMMIT = '9d6f54a1ade20d1d27dd421024717a636f3d8c68'
VERSION = 'v2.6.2-arm64ec-aura-qcom3'
DLLS = {'d3d8': 'd3d8', 'd3d9': 'd3d9', 'd3d10core': 'd3d10',
        'd3d11': 'd3d11', 'dxgi': 'dxgi'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'toolchain', 'glslang-bin', 'ninja-bin', 'work'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--jobs', type=int, default=3)
    a = p.parse_args()
    source, work = a.source.resolve(), a.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    assert output(['git', 'rev-parse', 'HEAD'], source) == COMMIT
    for name, commit in SUBMODULES.items():
        assert output(['git', 'rev-parse', 'HEAD'], source / name) == commit
        assert not output(['git', 'status', '--porcelain', '--untracked-files=no'], source / name)
    names = {line[6:] for line in PATCH.read_text().splitlines() if line.startswith('+++ b/')}
    changed = set(output(['git', 'diff', '--name-only'], source).splitlines())
    assert changed <= names | {'version.h.in'}
    if not changed & names:
        run(['git', 'apply', PATCH], source)
    run(['git', 'add', '-N', 'src/util/util_aura_init.h', 'src/dxgi/dxgi_aura_display.h'], source)
    assert output(['git', 'diff', '--', *sorted(names)], source) == PATCH.read_text().strip()
    for name in ('flow_core.h', 'flow_trace.h', 'pipeline_core.h', 'pipeline_trace.h', 'color_core.h', 'color_trace.h'):
        dst = 'src/util/aura_' + name
        content = (DATA / name).read_text()
        if name == 'flow_trace.h':
            # This build shares libdxvk between DLLs. Only D3D9 may claim the
            # manual observer, as in the standard runtime's separate builds.
            content = content.replace('namespace aura_flow {',
                'extern "C" IMAGE_DOS_HEADER __ImageBase;\n\nnamespace aura_flow {', 1)
            guard = '    DWORD saved = GetLastError(); Data* result = nullptr;'
            assert content.count(guard) == 1
            content = content.replace(guard, guard + '''
    if (GetModuleHandleW(L"d3d9.dll") != reinterpret_cast<HMODULE>(&__ImageBase)) {
      SetLastError(saved);
      return nullptr;
    }''')
        (source / dst).write_text(content)
        names.add(dst)
    original = output(['git', 'show', 'HEAD:version.h.in'], source) + '\n'
    template = source / 'version.h.in'
    versioned = original.replace('@VCS_TAG@', VERSION)
    assert template.read_text() in (original, versioned, original.replace('@VCS_TAG@', 'v2.6.2-arm64ec-aura-qcom1'), original.replace('@VCS_TAG@', 'v2.6.2-arm64ec-aura-qcom2'))
    template.write_text(versioned)
    env = os.environ.copy()
    env['PATH'] = os.pathsep.join([str(a.toolchain.resolve() / 'bin'),
        str(a.glslang_bin.resolve()), str(a.ninja_bin.resolve()), env['PATH']])
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
            '--wrap-mode=nodownload']
        if (build / 'build.ninja').exists():
            command.append('--reconfigure')
        run(command, env=env)
        run(['ninja', '-C', build, '-j', str(a.jobs)] +
            [f'src/{folder}/{dll}.dll' for dll, folder in DLLS.items()], env=env)
        for dll, folder in DLLS.items():
            name = f'{arch}/{dll}.dll'
            target = work / 'output' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            unstripped = build / f'src/{folder}/{dll}.dll'
            shutil.copyfile(unstripped, target)
            run(['llvm-strip', '--strip-all', target], env=env)
            pe = PeImage(target.read_bytes())
            assert pe.info['machine'] == ('0x8664' if arch == 'system32' else '0x14c')
            assert pe.info['chpeMetadata'] == (arch == 'system32')
            if dll in ('d3d9', 'd3d11', 'dxgi'):
                assert VERSION.encode() + b'\0' in target.read_bytes()
            records[name] = dict(bytes=target.stat().st_size, sha256=sha(target), pe=pe.info,
                unstripped_sha256=sha(unstripped))
    meta = dict(schema='AURA_DXVK_COMPAT_BUILD_V1', version=VERSION,
        source_url='https://github.com/doitsujin/dxvk', source_commit=COMMIT,
        submodules=SUBMODULES, source_date_epoch=env['SOURCE_DATE_EPOCH'],
        patch_sha256=sha(PATCH), script_sha256=sha(Path(__file__)), files=records,
        source_files={name: sha(source / name) for name in sorted(names)},
        optional_extensions=['VK_KHR_maintenance5', 'VK_KHR_pipeline_library'],
        preserved_fixes=['ZERO_EXTENT_STRETCH_RECT', 'SINGLETON_EXCEPTION_RETRY',
            'OPT_IN_BATCH_HINTS', 'MANUAL_D3D9_FLOW', 'STARTUP_DXGI_DISPLAY', 'STARTUP_PIPELINE_RING', 'MANUAL_PRE_PRESENT_COLOR'],
        compatibility_fix='EXPLICIT_SHADER_MODULE_WITHOUT_INLINE_PNEXT',
        android_game_execution='not run')
    meta['tools'] = {name: sha(path) for name, path in {
        'clang': a.toolchain / 'bin/clang', 'lld': a.toolchain / 'bin/lld',
        'strip': a.toolchain / 'bin/llvm-strip',
        'glslang': a.glslang_bin / 'glslangValidator', 'ninja': a.ninja_bin / 'ninja',
    }.items()}
    meta['compiler_version'] = output([a.toolchain / 'bin/clang', '--version']).splitlines()[0]
    meta['meson'] = __import__('mesonbuild.coredata', fromlist=['version']).version
    (work / 'output/build.json').write_text(json.dumps(meta, indent=2) + '\n')
    print(json.dumps(meta, indent=2))


if __name__ == '__main__':
    main()

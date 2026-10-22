#!/usr/bin/env python3
"""Build only patched D3D9 for the existing DXVK 2.7.1 ARM64EC runtime.

Inputs are a pinned recursive DXVK checkout, LLVM-MinGW 20251104, glslang,
Meson 1.9.1 and Ninja. Downloads and Android asset publication are separate.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

from pe_dependencies import PeImage

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'tools/arm64ec/dxvk'
COMMIT = 'c3dd74be6baec53786d4e064a572185b70347a17'
VERSION = 'v2.7.1-arm64ec-aura1'
SUBMODULES = {
    'include/native/directx': '9df86f2341616ef1888ae59919feaa6d4fad693d',
    'include/spirv': '8b246ff75c6615ba4532fe4fde20f1be090c3764',
    'include/vulkan': '234c4b7370a8ea3239a214c9e871e4b17c89f4ab',
    'subprojects/libdisplay-info': '275e6459c7ab1ddd4b125f28d0440716e4888078',
}


def run(args, cwd=None, env=None):
    subprocess.run([str(x) for x in args], cwd=cwd, env=env, check=True)


def output(args, cwd=None):
    return subprocess.check_output([str(x) for x in args], cwd=cwd, text=True).strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--toolchain', type=Path, required=True)
    parser.add_argument('--glslang-bin', type=Path, required=True)
    parser.add_argument('--ninja-bin', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    source = args.source.resolve()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    assert output(['git', 'rev-parse', 'HEAD'], source) == COMMIT
    for name, revision in SUBMODULES.items():
        assert output(['git', 'rev-parse', 'HEAD'], source / name) == revision
        assert not output(['git', 'status', '--porcelain', '--untracked-files=no'], source / name)
    changed = set(output(['git', 'diff', '--name-only'], source).splitlines())
    assert changed <= {'src/d3d9/d3d9_device.cpp', 'version.h.in'}, changed
    provenance = json.loads((DATA / 'backport.json').read_text())
    device = source / 'src/d3d9/d3d9_device.cpp'
    if sha(device) == provenance['source_files']['d3d9_device.cpp']['sha256']:
        run(['git', 'apply', '--check', DATA / 'zero-extent-stretch-rect.patch'], source)
        run(['git', 'apply', DATA / 'zero-extent-stretch-rect.patch'], source)
    assert sha(device) == provenance['patched_device_source_sha256']
    # Clearly mark modified binaries, without touching functional upstream code.
    template = source / 'version.h.in'
    original_template = output(['git', 'show', 'HEAD:version.h.in'], source) + '\n'
    expected_template = original_template.replace('@VCS_TAG@', VERSION)
    assert template.read_text() in (original_template, expected_template)
    template.write_text(expected_template)
    env = os.environ.copy()
    env['PATH'] = os.pathsep.join([str(args.toolchain.resolve() / 'bin'),
                                 str(args.glslang_bin.resolve()), str(args.ninja_bin.resolve()), env['PATH']])
    env['SOURCE_DATE_EPOCH'] = output(['git', 'show', '-s', '--format=%ct', COMMIT], source)
    records = {}
    for arch, compiler, family, cpu, machine in [
            ('syswow64', 'i686', 'x86', 'i686', 0x14c),
            ('system32', 'arm64ec', 'aarch64', 'aarch64', 0xa641)]:
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
cpu = '{cpu}'
endian = 'little'
[built-in options]
c_link_args = ['-Wl,--no-insert-timestamp']
cpp_link_args = ['-Wl,--no-insert-timestamp']
''')
        build = work / ('build-' + arch)
        options = ['-Dbuildtype=release', '-Dstrip=true', '-Dbuild_id=true',
                   '-Denable_d3d9=true', '-Denable_d3d8=false', '-Denable_dxgi=false',
                   '-Denable_d3d10=false', '-Denable_d3d11=false', '--wrap-mode=nodownload']
        command = [sys.executable, '-m', 'mesonbuild.mesonmain', 'setup', build, source,
                   '--cross-file', cross] + options
        if (build / 'build.ninja').exists():
            command.append('--reconfigure')
        run(command, env=env)
        run(['ninja', '-C', build, '-j', str(args.jobs), 'src/d3d9/d3d9.dll'], env=env)
        target = work / 'output' / arch / 'd3d9.dll'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build / 'src/d3d9/d3d9.dll', target)
        run(['llvm-strip', '--strip-all', target], env=env)
        data = target.read_bytes()
        pe = struct.unpack_from('<I', data, 0x3c)[0]
        actual_machine = struct.unpack_from('<H', data, pe + 4)[0]
        info = PeImage(data).info
        if machine == 0xa641:
            # LLVM emits an AMD64 PE header with ARM64EC CHPE metadata. A plain
            # desktop x64 DLL must not pass as an ARM64EC build.
            assert actual_machine == 0x8664 and info['chpeMetadata']
        else:
            assert actual_machine == machine and not info['chpeMetadata']
        assert VERSION.encode() + b'\0' in data
        records[arch + '/d3d9.dll'] = {'bytes': len(data), 'sha256': sha(target),
                                      'pe_machine': hex(actual_machine),
                                      'chpe_metadata': info['chpeMetadata'],
                                      'unstripped_sha256': sha(build / 'src/d3d9/d3d9.dll')}
    manifest = {'schema': 'AURA_DXVK_D3D9_BUILD_V1', 'version': VERSION,
                'source_url': 'https://github.com/doitsujin/dxvk', 'source_commit': COMMIT,
                'submodules': SUBMODULES, 'patch_sha256': sha(DATA / 'zero-extent-stretch-rect.patch'),
                'device_source_sha256': sha(device), 'version_template_sha256': sha(template),
                'source_date_epoch': env['SOURCE_DATE_EPOCH'], 'architectures': ['x86', 'ARM64EC'],
                'other_dxvk_dlls_rebuilt': False, 'files': records,
                'meson': '1.9.1', 'llvm_mingw_release': '20251104',
                'android_game_execution': 'not run'}
    (work / 'output/build.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()

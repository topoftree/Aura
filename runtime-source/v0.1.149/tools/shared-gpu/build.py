#!/usr/bin/env python3
"""Build the optional Android Zink bridge from pinned source and Android libraries.

Linux .deb libraries are NEVER linked: only their headers/pkg-config metadata
are copied. Host generators are isolated separately. --install updates the
optional module and its integrity record, not the normal Mesa runtime.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from patch_mesa import patch, ROOT

HERE = Path(__file__).resolve().parent
LOCK = json.loads((HERE / 'sources.lock.json').read_text())


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(record, target, offline):
    if not target.exists():
        if offline:
            raise RuntimeError(f'Missing verified input: {target.name}')
        temporary = target.with_suffix(target.suffix + '.part')
        with urllib.request.urlopen(record['url'], timeout=60) as source, temporary.open('wb') as out:
            shutil.copyfileobj(source, out)
        if digest(temporary) != record['sha256']:
            raise RuntimeError(f'Input checksum failed: {target.name}')
        temporary.replace(target)
    if digest(target) != record['sha256']:
        raise RuntimeError(f'Input checksum failed: {target.name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', required=True, type=Path)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--jobs', type=int, default=3)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--install', action='store_true')
    args = parser.parse_args()
    work, ndk = args.work.resolve(), args.ndk.resolve()
    if LOCK['ndk'] not in (ndk / 'source.properties').read_text():
        raise RuntimeError('Use the pinned NDK version')
    work.mkdir(parents=True, exist_ok=True)
    archive = work / 'mesa.tar.gz'
    fetch(LOCK['mesa'], archive, args.offline)
    source = work / 'source' / ('mesa-' + LOCK['mesa']['commit'])
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(work / 'source', filter='data')
    patch(source)
    deps, host = work / 'deps/usr', work / 'host-tools'
    include, pc, lib = deps / 'include', deps / 'lib/pkgconfig', deps / 'lib'
    for directory in (include, pc, lib, host):
        directory.mkdir(parents=True, exist_ok=True)
    for record in LOCK['packages']:
        package = work / (record['package'] + '.deb')
        fetch(record, package, args.offline)
        if record['role'] == 'host-tool':
            subprocess.run(['dpkg-deb', '-x', package, host], check=True)
        else:
            with tempfile.TemporaryDirectory(dir=work) as temp:
                temp = Path(temp)
                subprocess.run(['dpkg-deb', '-x', package, temp], check=True)
                if (temp / 'usr/include').exists():
                    shutil.copytree(temp / 'usr/include', include, dirs_exist_ok=True)
                for item in temp.rglob('*.pc'):
                    content = item.read_text().replace('prefix=/usr', f'prefix={deps}')
                    content = content.replace('${prefix}/lib/x86_64-linux-gnu', '${prefix}/lib')
                    (pc / item.name).write_text(content)
    for name, version, link in [('zlib', '1.3.1', '-lz'), ('expat', '2.5.0', '-l:libexpat.so.1'),
                                 ('libzstd', '1.5.4', '-l:libzstd.so.1')]:
        (pc / (name + '.pc')).write_text(f'prefix={deps}\nName: {name}\nDescription: Android dependency\n'
            f'Version: {version}\nLibs: -L{lib} {link}\nCflags: -I{include}\n')
    native = ROOT / 'engine/src/main/jniLibs/arm64-v8a'
    for name, record in LOCK['androidLibraries'].items():
        binary = native / ('libaura_' + record['sha256'][:24] + '.so')
        if digest(binary) != record['sha256']:
            raise RuntimeError(f'Android library checksum failed: {name}')
        target = lib / name
        target.unlink(missing_ok=True)
        target.symlink_to(binary)
    python = work / 'python'
    if not (python / 'mesonbuild').exists():
        subprocess.run([sys.executable, '-m', 'pip', 'install', '--target', python,
            *(['--no-index'] if args.offline else []), *LOCK['pythonPackages']], check=True)
    clang = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    ninja = Path(shutil.which(args.ninja) or args.ninja).resolve()
    env = dict(os.environ, PYTHONPATH=str(python),
        PATH=f'{host}/usr/bin:{ninja.parent}:' + os.environ['PATH'],
        BISON_PKGDATADIR=str(host / 'usr/share/bison'), M4=str(host / 'usr/bin/m4'))
    cross = work / 'cross.ini'
    cross.write_text(f'''[binaries]
c = '{clang}/aarch64-linux-android34-clang'
cpp = '{clang}/aarch64-linux-android34-clang++'
ar = '{clang}/llvm-ar'
strip = '{clang}/llvm-strip'
pkg-config = '/usr/bin/pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['{pc}']
[built-in options]
c_args = ['-DVK_USE_PLATFORM_ANDROID_KHR', '-DANDROID_API_LEVEL=34']
cpp_args = ['-DVK_USE_PLATFORM_ANDROID_KHR', '-DANDROID_API_LEVEL=34']
c_link_args = ['-L{lib}', '-Wl,-z,max-page-size=16384']
cpp_link_args = ['-L{lib}', '-static-libstdc++', '-Wl,-z,max-page-size=16384']
''')
    def run(*command):
        subprocess.run(list(map(str, command)), env=env, check=True, cwd=work)
    meson = [sys.executable, '-m', 'mesonbuild.mesonmain']
    build = work / 'build'
    options = ['-Dbuildtype=release', '-Dplatforms=x11', '-Dglx=xlib', '-Dgallium-drivers=zink',
        '-Dvulkan-drivers=', '-Degl=disabled', '-Dgbm=disabled', '-Dllvm=disabled', '-Dgles1=disabled',
        '-Dgles2=disabled', '-Dshared-glapi=disabled', '-Dglvnd=disabled', '-Dlibunwind=disabled', '-Dbuild-tests=false']
    run(*meson, 'setup', *(['--reconfigure'] if (build / 'build.ninja').exists() else []),
        build, source, '--cross-file', cross, *options)
    run(ninja, '-C', build, '-j', args.jobs, 'src/gallium/targets/libgl-xlib/libGL.so')
    run(*meson, 'install', '-C', build, '--no-rebuild', '--destdir', work / 'install', '--tags', 'runtime')
    binary = work / 'install/usr/local/lib/libGL.so'
    run(clang / 'llvm-strip', '--strip-unneeded', binary)
    dynamic = subprocess.check_output([clang / 'llvm-readelf', '-d', binary], text=True)
    headers = subprocess.check_output([clang / 'llvm-readelf', '-l', binary], text=True)
    symbols = subprocess.check_output([clang / 'llvm-readelf', '--dyn-syms', binary], text=True)
    assert 'RPATH' not in dynamic and 'RUNPATH' not in dynamic
    assert 'GLIBC_' not in symbols and 'glXSwapBuffers' in symbols
    assert 'AHardwareBuffer_allocate' in symbols
    assert all(int(line.split()[-1], 16) >= 16384 for line in headers.splitlines() if line.strip().startswith('LOAD'))
    assert dynamic.index('[libandroid-sysvshm.so]') < dynamic.index('[libc.so]')
    inputs = ['build.py', 'patch_mesa.py', 'zink_aura_present.c', 'zink_aura_present.h', 'sources.lock.json']
    record = dict(schema=1, filename='libaura_zink_shared.so', bytes=binary.stat().st_size, sha256=digest(binary),
        mesaCommit=LOCK['mesa']['commit'], mesaArchiveSha256=LOCK['mesa']['sha256'], api=34,
        ndk=LOCK['ndk'], inputSha256={name: digest(HERE / name) for name in inputs},
        protocolSha256=digest(ROOT / 'engine/src/main/cpp/winlator/include/shared_gpu_protocol.h'),
        scope='Explicit per-game experiment; one GPU blit; device validation pending')
    (work / 'shared-gpu.json').write_text(json.dumps(record, indent=2) + '\n')
    if args.install:
        shutil.copyfile(binary, native / record['filename'])
        shutil.copyfile(work / 'shared-gpu.json', ROOT / 'engine/src/main/assets/arm64ec/shared-gpu.json')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()

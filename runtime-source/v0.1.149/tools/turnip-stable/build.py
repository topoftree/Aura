#!/usr/bin/env python3
"""Build the pinned Mesa Android KGSL HAL for Aura's private driver loader.

No downloads or source patches. Supply the official archive and its extracted
tree, Android NDK r28c, Meson 1.5.2, Ninja, Mako, PLY, PyYAML and glslang.
The result is not installed into Android's system/vendor driver directories.
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

HERE = Path(__file__).resolve().parent
REVISION = '26.2.5-aura-ext1'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_source(archive, source, lock):
    assert sha(archive) == lock['mesa']['sha256'], 'Wrong source archive'
    checked = 0
    with tarfile.open(archive, 'r:xz') as tar:
        for entry in tar:
            parts = Path(entry.name).parts
            assert parts[0] == 'mesa-' + lock['version'] and '..' not in parts
            if entry.isdir():
                continue
            target = source.joinpath(*parts[1:])
            if entry.issym():
                assert target.is_symlink() and os.readlink(target) == entry.linkname
                assert target.resolve().is_relative_to(source)
                continue
            assert entry.isfile(), 'Unexpected non-regular source entry'
            assert target.is_file() and not target.is_symlink()
            assert hashlib.sha256(tar.extractfile(entry).read()).hexdigest() == sha(target), target
            checked += 1
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-archive', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    lock = json.loads((HERE / 'sources.json').read_text())
    source, work, ndk = (p.resolve() for p in (args.source, args.work, args.ndk))
    assert 'Pkg.Revision = 28.2.13676358' in (ndk / 'source.properties').read_text()
    resume = (work / 'build/build.ninja').is_file()
    assert not work.exists() or resume, 'Use a new or already configured work directory'
    checked = verify_source(args.source_archive, source, lock)
    work.mkdir(parents=True, exist_ok=True)
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    compiler = toolchain / 'bin/aarch64-linux-android34-clang'
    pkgconfig = work / 'pkgconfig'
    pkgconfig.mkdir(exist_ok=True)
    zlib_version = re.search(r'#define ZLIB_VERSION "([^"]+)"',
        (toolchain / 'sysroot/usr/include/zlib.h').read_text())[1]
    (pkgconfig / 'zlib.pc').write_text('Name: zlib\nDescription: Android NDK system zlib\n'
        f'Version: {zlib_version}\nLibs: -lz\n')
    cross = work / 'android-aarch64.ini'
    cross_text = f'''[binaries]
c = '{compiler}'
cpp = ['{compiler}++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
ar = '{toolchain}/bin/llvm-ar'
strip = '{toolchain}/bin/llvm-strip'
pkg-config = '/usr/bin/pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['{pkgconfig}']
'''
    if resume:
        assert cross.read_text() == cross_text, 'Toolchain changed; use a new work directory'
    cross.write_text(cross_text)
    # ANDROID_STRICT is an Android CTS advertisement policy, not a feature
    # implementation. Keep API 34 imports while exposing implemented extensions
    # through the app-private AdrenoTools loader (including maintenance5).
    options = ['-Dbuildtype=release', '-Db_ndebug=true', '-Dstrip=true',
               '-Dplatforms=android', '-Dplatform-sdk-version=34',
               '-Dandroid-stub=true', '-Dandroid-strict=false',
               '-Dandroid-libbacktrace=disabled', '-Degl=disabled',
               '-Dgallium-drivers=', '-Dvulkan-drivers=freedreno',
               '-Dvulkan-beta=true', '-Dfreedreno-kmds=kgsl', '-Dvideo-codecs=',
               '-Dzstd=disabled']
    env = dict(os.environ, MESA_GIT_SHA1_OVERRIDE=lock['mesa']['commit'],
               SOURCE_DATE_EPOCH='1791976320')
    meson = [sys.executable, '-m', 'mesonbuild.mesonmain']
    subprocess.run(meson + ['setup'] + (['--reconfigure'] if resume else []) + [str(work / 'build'), str(source),
                   '--cross-file', str(cross), '--prefix', str(work / 'install'),
                   '--wrap-mode=nodownload'] + options, env=env, check=True)
    subprocess.run(meson + ['compile', '-C', str(work / 'build'), '-j', str(args.jobs)],
                   env=env, check=True)
    subprocess.run(meson + ['install', '-C', str(work / 'build')], env=env, check=True)
    binary = work / 'install/lib/libvulkan_freedreno.so'
    report = dict(schema=1, revision=REVISION, sourceArchiveSha256=lock['mesa']['sha256'],
                  sourceFilesVerified=checked, sourceCommit=lock['mesa']['commit'],
                  sourcePatches=[], androidApi=34, androidStrict=False, backend='kgsl',
                  ndk='28.2.13676358', compiler=subprocess.check_output(
                      [str(compiler), '--version'], text=True).splitlines()[0],
                  meson=subprocess.check_output(meson + ['--version'], env=env, text=True).strip(),
                  options=options, libraryBytes=binary.stat().st_size,
                  librarySha256=sha(binary), buildScriptSha256=sha(Path(__file__)),
                  hostTools={name: dict(sha256=sha(Path(shutil.which(name))),
                      version=subprocess.check_output([shutil.which(name), '--version'],
                          env=env, text=True).splitlines()[0])
                      for name in ('glslangValidator', 'bison', 'flex', 'ninja')},
                  androidExecution='not run')
    (work / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Built', REVISION, report['librarySha256'], '; no phone execution')


if __name__ == '__main__':
    main()

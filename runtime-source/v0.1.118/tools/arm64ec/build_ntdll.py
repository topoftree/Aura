#!/usr/bin/env python3
"""Build Android/Bionic ntdll with vendor adaptations and initial EC stack space.

Prerequisites: NDK 28.2, LLVM-MinGW 20250920, gcc, autoconf/autoheader, bison,
flex, make, Python and Rust 1.90.0 with
aarch64-linux-android std installed. Cargo uses its locked dependency cache.
No client/game executable is run. The Wine and ntsync source archives are inputs.
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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_runtime_candidate import elf_info
from runtime_paths import relocate_runtime_paths
from verify_ntdll_layout import inspect as inspect_layout

META = Path(__file__).with_name('ntdll')
RUNTIME_MANIFEST = META.parents[2] / 'engine/src/main/assets/arm64ec/manifest.json'
WINE_PREFIX = '/data/data/com.android.aura/f/opt/wine'
SOURCE_SHA = 'a6bec61fbe4ff4bc5fa73d6693a0455a3144fe5272a9342ddb751e7715d70a3a'
NTSYNC_SHA = 'f90f38be286eb6867b43269630c3683f6a62cf809f6e433e3e30a513e2649cbf'
SOURCE_COMMIT = 'dcc50c55e2a4dbd06610e5b45d8872bc6f048633'
SOURCE_BRANCH = 'proton_11.0-2'
NTSYNC_COMMIT = '7ce6435e5979b1cb5341aa4b299f31e8937fe121'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def extract(archive, destination, wine=False):
    with tarfile.open(archive) as tar:
        for item in tar:
            parts = Path(item.name).parts[1:]
            if not parts or '..' in parts or Path(*parts).is_absolute():
                continue
            if wine and (parts[0] in ('.git', '.github', 'po') or 'tests' in parts):
                continue
            if wine and parts[0] == 'android' and (len(parts) < 2 or parts[1] not in ('patches', 'ntsync_android', 'shm_utils')):
                continue
            if item.isfile():
                target = destination.joinpath(*parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(item).read())
                target.chmod(item.mode & 0o755)


def relocate(data):
    data = relocate_runtime_paths(data)
    for old, new in [(b'/data/data/com.termux/files/usr', b'/data/data/com.android.aura/f/r'),
                     (b'/data/data/com.termux/files/home', b'/data/data/com.android.aura/f/h')]:
        assert len(new) <= len(old)
        data = data.replace(old, new + b'/' * (len(old) - len(new)))
    return data


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['source', 'ntsync-source', 'ndk', 'mingw', 'work']:
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--cargo', default='cargo')
    p.add_argument('--runtime-manifest', type=Path, default=RUNTIME_MANIFEST)
    args = p.parse_args()
    assert sha(args.source) == SOURCE_SHA and sha(args.ntsync_source) == NTSYNC_SHA
    assert 'Pkg.Revision = 28.2.13676358' in (args.ndk / 'source.properties').read_text()
    runtime = json.loads(args.runtime_manifest.read_text())
    assert runtime['wine'] == '11.0-2', 'Native ntdll must match the packaged Wine generation'
    runtime_files = runtime['files']
    work = args.work.resolve(); work.mkdir(parents=True, exist_ok=False)
    source, build, output, ntsync, deps, host = [work / n for n in
        ('source', 'build', 'output', 'ntsync', 'deps/lib', 'host-tools')]
    for path in (source, build, output, ntsync, deps, host):
        path.mkdir(parents=True)
    extract(args.source, source, wine=True); extract(args.ntsync_source, ntsync)
    toolchain = args.ndk.resolve() / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    env = os.environ.copy()
    env.update(PATH=str(args.mingw.resolve()) + os.pathsep + env['PATH'], SOURCE_DATE_EPOCH='0',
               XDG_CACHE_HOME=str(work/'cache'), PKG_CONFIG_LIBDIR=str(work/'empty-pkg-config'), PKG_CONFIG_PATH='')
    for name, value in dict(CC='aarch64-linux-android34-clang', CXX='aarch64-linux-android34-clang++',
                            AS='aarch64-linux-android34-clang', AR='llvm-ar', RANLIB='llvm-ranlib', STRIP='llvm-strip').items():
        env[name] = str(toolchain / value)
    env['CFLAGS'] = ('-g -O2 -fPIC -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES -Wno-declaration-after-statement '
                     '-Wno-implicit-function-declaration -Wno-int-conversion '
                     f'-ffile-prefix-map={source}=/aura/wine-source -ffile-prefix-map={build}=/aura/wine-build')
    env['LDFLAGS'] = f'-L{deps} -Wl,-rpath,/data/data/com.android.aura/f/r/lib -Wl,-z,max-page-size=16384'
    env['CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER'] = env['CC']
    env['AR_aarch64_linux_android'] = env['AR']
    patch_array = (source/'build-scripts/build-step-arm64ec.sh').read_text().split('PATCHES=(',1)[1].split('\n    )',1)[0]
    patches = ['server_protocol.def.patch'] + re.findall(r'^\s*"([^"\n]+\.patch)"', patch_array, re.M)
    assert len(patches) > 40 and patches[-1] == 'ntsync_userspace.patch'
    disabled = ['--disable-tests', '--disable-win16', '--disable-nls', '--without-x', '--without-wayland',
        '--without-gstreamer', '--without-vulkan', '--without-opengl', '--without-alsa', '--without-pulse',
        '--without-freetype', '--without-fontconfig', '--without-unwind', '--without-hwloc', '--without-inotify',
        '--without-udev', '--without-usb', '--without-ffmpeg']
    with (work/'build.log').open('w') as log:
        def run(command, cwd=source, environment=env):
            subprocess.run([str(x) for x in command], cwd=cwd, env=environment, stdout=log,
                           stderr=subprocess.STDOUT, check=True)
        for patch in patches:
            run(['git', 'apply', source/'android/patches'/patch])
        for command in [['tools/make_requests'], ['tools/make_specfiles'],
                        ['python3', 'dlls/winevulkan/make_vulkan', '-x', 'vk.xml', '-X', 'video.xml'],
                        ['autoconf'], ['autoheader']]:
            run(command)
        # A native-only rebuild still needs the same server/ARM64EC contract as
        # the existing runtime. Proton 11.0 lacks 11.0-2's cooperative select
        # handshake even though the exported function names are compatible.
        protocol = (source/'include/wine/server_protocol.h').read_text()
        assert re.search(r'^#define SELECT_COOPERATIVE_SUSPEND\s+4$', protocol, re.M)
        signal_source = (source/'dlls/ntdll/unix/signal_arm64.c').read_text()
        assert 'SELECT_INTERRUPTIBLE | SELECT_COOPERATIVE_SUSPEND' in signal_source
        assert '*chpe->SuspendDoorbell = -1;' in signal_source
        # Build the small host-tool subset from this exact archive as well;
        # do not reuse the 11.0 tools previously used for the sechost repair.
        host_env = env.copy()
        host_env.update(CC='gcc', CXX='g++', AS='as', AR='ar', RANLIB='ranlib', STRIP='strip', LDFLAGS='',
                        CFLAGS=f'-O2 -ffile-prefix-map={source}=/aura/wine-source '
                               f'-ffile-prefix-map={host}=/aura/wine-host-tools')
        run([source/'configure', '--no-create', '--enable-win64', '--enable-archs=arm64ec,aarch64',
             '--with-mingw=clang', *disabled], host, host_env)
        host_status = host/'config.status'
        host_text = host_status.read_text()
        host_needle = '"tools/makedep" -C -i'
        assert host_text.count(host_needle) == 1
        host_status.write_text(host_text.replace(host_needle,
            '"tools/makedep" -C "SUBDIRS=include tools/widl tools/winebuild tools/winegcc tools/wrc" -i'))
        run(['sh', './config.status'], host, host_env)
        run(['make', '-j4', 'tools/widl/widl', 'tools/winebuild/winebuild', 'tools/winegcc/winegcc',
             'tools/wrc/wrc'], host, host_env)
        run([args.cargo, '+1.90.0', 'build', '--offline', '--release', '--locked', '--target', 'aarch64-linux-android'], ntsync)
        # Only the static archive is visible to -lntsync_android, as in the vendor build.
        shutil.copy2(ntsync/'target/aarch64-linux-android/release/libntsync_android.a', deps/'libntsync_android.a')
        # Android configure otherwise inserts /arm64-v8a in exec_prefix. Aura
        # installs native DLLs and NLS under the same flat opt/wine prefix;
        # init_paths uses the compiled LIBDIR -> DATADIR relationship at runtime.
        directories = [f'--prefix={WINE_PREFIX}', f'--exec-prefix={WINE_PREFIX}',
                       f'--bindir={WINE_PREFIX}/bin', f'--libdir={WINE_PREFIX}/lib',
                       f'--datadir={WINE_PREFIX}/share']
        configure = [source/'configure', '--no-create', '--host=aarch64-linux-android34', '--enable-win64',
            '--enable-archs=arm64ec,aarch64', '--with-mingw=clang', '--with-wine-tools='+str(host),
            *directories, '--enable-wineandroid_drv=no', *disabled]
        run(configure, build)
        status = build/'config.status'; text = status.read_text(); needle = '" -C -i'
        assert text.count(needle) == 1
        status.write_text(text.replace(needle, '" -C "SUBDIRS=include dlls/ntdll" -i'))
        run(['sh', './config.status'], build)
        run(['make', '-j4', 'dlls/ntdll/ntdll.so'], build)
        shutil.copy2(build/'dlls/ntdll/ntdll.so', output/'baseline-ntdll.so')
        baseline_layout = inspect_layout((output/'baseline-ntdll.so').read_bytes(), runtime_files)
        assert baseline_layout['passed'], baseline_layout
        run(['git', 'apply', META/'initial-ec-stack.patch'])
        run(['make', '-j4', 'dlls/ntdll/ntdll.so'], build)
        symbols = output/'ntdll-symbols.so'
        symbols.write_bytes(relocate((build/'dlls/ntdll/ntdll.so').read_bytes()))
        target = output/'ntdll.so'; shutil.copy2(symbols, target)
        run([toolchain/'llvm-strip', '--strip-debug', target], build)
    info = elf_info(target.read_bytes())
    assert info['machine'] == '0xb7' and info['abi'] == 'bionic'
    assert set(info['needed']) == {'libc.so', 'libdl.so'} and info['minimumLoadAlignment'] >= 16384
    assert info['searchPaths'] == ['/data/data/com.android.aura/f/r/lib'], info['searchPaths']
    candidate_layout = inspect_layout(target.read_bytes(), runtime_files)
    assert candidate_layout['passed'], candidate_layout
    shutil.copy2(source/'COPYING.LIB', output/'COPYING.WINE')
    shutil.copy2(ntsync/'LICENSE', output/'COPYING.NTSYNC')
    metadata = dict(schema=3, wineSourceCommit=SOURCE_COMMIT, wineSourceSha256=SOURCE_SHA,
        wineSourceBranch=SOURCE_BRANCH, hostToolsSameSource=True,
        threadProtocol=dict(cooperativeSelect=4, suspendDoorbellValue='0xffffffff',
            signalSourceSha256=sha(source/'dlls/ntdll/unix/signal_arm64.c'),
            serverProtocolSha256=sha(source/'include/wine/server_protocol.h')),
        ntsyncSourceCommit=NTSYNC_COMMIT, ntsyncSourceSha256=NTSYNC_SHA,
        ndk='28.2.13676358', rust='1.90.0', abi='android-aarch64-api34',
        patchSha256=sha(META/'initial-ec-stack.patch'), buildScriptSha256=sha(Path(__file__)),
        vendorPatches=[dict(file=n, sha256=sha(source/'android/patches'/n)) for n in patches],
        file='opt/wine/lib/wine/aarch64-unix/ntdll.so', bytes=target.stat().st_size, sha256=sha(target),
        symbolsSha256=sha(symbols), baselineSha256=sha(output/'baseline-ntdll.so'),
        configureDirectories=directories, runtimeManifestSha256=sha(args.runtime_manifest),
        compiledLayout=dict(baseline=baseline_layout, candidate=candidate_layout),
        publicationStatus='LOCAL_CANDIDATE_NOT_PACKAGED',
        initialArm64EcStackReserve=48, nativeArm64AndWow64='UNCHANGED',
        androidDeviceTested=False, southParkMainMenuVerified=False)
    (output/'build.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()

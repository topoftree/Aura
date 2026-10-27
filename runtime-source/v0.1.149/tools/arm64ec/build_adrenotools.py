#!/usr/bin/env python3
"""Build the common AdrenoTools resolver and both Wine-side consumers from source."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'engine/src/main/cpp/libadrenotools'
sys.path.insert(0, str(ROOT / 'tools'))
from audit_runtime_candidate import elf_info
from build_gpu_memory import symbols

OLD = {
    'libadrenotools.so': '4939ec0ad71634cda4ac734fcfeffa91422ee4001612fc14a5ede3d040429d24',
    'libhook_impl.so': '4194de0221b4d6c66966a2b4c92207b65c85666bc588dc5b3981e0f188c49ae0',
}


def sha(path):
    with path.open('rb') as source: return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('ndk', 'cmake', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert 'Pkg.Revision = 28.2.13676358' in (args.ndk / 'source.properties').read_text()
    build = args.output / 'build'
    options = ['-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + str(args.cmake.with_name('ninja')),
               '-DCMAKE_TOOLCHAIN_FILE=' + str(args.ndk / 'build/cmake/android.toolchain.cmake'),
               '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-34', '-DANDROID_STL=c++_shared',
               '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_CXX_STANDARD=17', '-DBUILD_SHARED_LIBS=ON',
               '-DCMAKE_BUILD_WITH_INSTALL_RPATH=ON', '-DCMAKE_INSTALL_RPATH=/data/data/com.android.aura/f/r/lib',
               '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-rpath,/data/data/com.android.aura/f/r/lib',
               '-DCMAKE_C_FLAGS=-ffile-prefix-map=' + str(ROOT) + '=.',
               '-DCMAKE_CXX_FLAGS=-ffile-prefix-map=' + str(ROOT) + '=.']
    subprocess.run([str(args.cmake), '-S', str(SOURCE), '-B', str(build), *options], check=True)
    subprocess.run([str(args.cmake), '--build', str(build), '--parallel', '2', '--target', 'adrenotools', 'hook_impl'], check=True)
    strip = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
    outputs = {}
    for name, digest in OLD.items():
        original = ROOT / 'engine/src/main/jniLibs/arm64-v8a' / ('libaura_' + digest[:24] + '.so')
        if original.is_file():
            assert sha(original) == digest
            public = [symbol for symbol in symbols(original)['exports'] if not symbol.startswith('_Z')]
        else:
            # The obsolete hashed payload is removed after packaging. Keep the
            # reviewed ABI baseline in ordinary Git for subsequent rebuilds.
            baseline = json.loads((Path(__file__).with_name('adrenotools-loader') / 'build.json').read_text())
            assert baseline['outputs'][name]['previousSha256'] == digest
            public = baseline['outputs'][name]['retainedPublicExports']
        target = args.output / name
        shutil.copyfile(build / ('src/hook/' if name == 'libhook_impl.so' else '') / name, target)
        subprocess.run([str(strip), '--strip-unneeded', str(target)], check=True)
        info = elf_info(target.read_bytes())
        assert info['machine'] == '0xb7' and info['abi'] != 'glibc' and info['minimumLoadAlignment'] >= 16384
        assert info['soname'] == [name] and info['searchPaths'] == ['/data/data/com.android.aura/f/r/lib']
        current = symbols(target)
        assert set(public) <= set(current['exports']), (name, set(public) - set(current['exports']))
        assert b'AURA_ADRENOTOOLS_LOADER_V1' in target.read_bytes()
        outputs[name] = dict(bytes=target.stat().st_size, sha256=sha(target), previousSha256=digest,
                             retainedPublicExports=public, elf=info)
    sources = {p.relative_to(SOURCE).as_posix(): sha(p) for p in sorted(SOURCE.rglob('*'))
               if p.is_file() and (p.suffix in ('.cpp', '.c', '.h') or p.name in ('CMakeLists.txt', 'LICENSE'))}
    record = dict(schema='AURA_ADRENOTOOLS_BUILD_V1', revision='libdl-handle-1', ndk='28.2.13676358',
                  androidApi=34, builderSha256=sha(Path(__file__)), sourceRoot=SOURCE.relative_to(ROOT).as_posix(),
                  sources=sources, options=[o.replace(str(ROOT), '${ROOT}').replace(str(args.ndk), '${NDK}')
                                            .replace(str(args.cmake.parent), '${CMAKE_BIN}') for o in options],
                  outputs=outputs, androidDeviceTested=False)
    (args.output / 'build.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({name: output['sha256'] for name, output in outputs.items()}))


if __name__ == '__main__': main()

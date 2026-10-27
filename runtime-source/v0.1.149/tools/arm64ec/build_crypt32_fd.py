#!/usr/bin/env python3
"""Build only Wine crypt32's Unix library, retaining GnuTLS and the native ABI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from build_ntdll import sha, relocate, SOURCE_COMMIT
from build_gpu_memory import symbols, import_versions
from audit_runtime_candidate import elf_info

ROOT = Path(__file__).resolve().parents[2]
META = Path(__file__).with_name('crypt32-fd')
SOURCE_SHA = 'e22614c94c1482659747afcfc60be6993435d9c33a98bb0cae9d302e79926c34'
OLD_SHA = 'e24450a3b0da2cd28ac4f989d57d80be3e5e3904e1d66de1d9441b72db7c39e3'


def tree_hash(directory):
    h = hashlib.sha256()
    for p in sorted(directory.rglob('*')):
        if p.is_file():
            h.update(p.relative_to(directory).as_posix().encode() + b'\0' + bytes.fromhex(sha(p)))
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['base', 'work', 'ndk', 'gnutls-headers', 'baseline-native']:
        parser.add_argument('--'+name, type=Path, required=True)
    a = parser.parse_args()
    base, work = a.base.resolve(), a.work.resolve()
    source = base/'source'; config = base/'native/include/config.h'
    assert sha(source/'dlls/crypt32/unixlib.c') == SOURCE_SHA
    assert 'Pkg.Revision = 28.2.13676358' in (a.ndk/'source.properties').read_text()
    work.mkdir(parents=True, exist_ok=False)
    output = work/'output'; output.mkdir()
    local = work/'source/dlls/crypt32'; local.mkdir(parents=True)
    shutil.copyfile(source/'dlls/crypt32/unixlib.c', local/'unixlib.c')
    include = work/'include'; include.mkdir()
    shutil.copytree(a.gnutls_headers, include/'gnutls')
    cfg = config.read_text().replace('/* #undef SONAME_LIBGNUTLS */', '#define SONAME_LIBGNUTLS "libgnutls.so"')
    assert '#define SONAME_LIBGNUTLS "libgnutls.so"' in cfg
    (include/'config.h').write_text(cfg)
    manifest = json.loads((ROOT/'engine/src/main/assets/arm64ec/manifest.json').read_text())
    ntdll_sha = manifest['files']['opt/wine/lib/wine/aarch64-unix/ntdll.so']['sha256']
    libs = ROOT/'engine/src/main/jniLibs/arm64-v8a'
    ntdll = libs/f'libaura_{ntdll_sha[:24]}.so'; old = a.baseline_native.resolve()
    assert sha(ntdll) == ntdll_sha and sha(old) == OLD_SHA
    tc = a.ndk.resolve()/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    flags = ['-shared','-fPIC','-O2','-g','-fno-strict-aliasing','-fvisibility=hidden',
             '-fno-stack-protector','-D__WINESRC__','-DWINE_UNIX_LIB',
             '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES','-Wall','-Wextra',
             '-Wno-unused-parameter','-Wno-implicit-function-declaration','-Wno-int-conversion',
             f'-ffile-prefix-map={work}=/aura/crypt32-build',
             f'-ffile-prefix-map={source}=/aura/wine-source',
             '-I'+str(include),'-I'+str(base/'native/include'),'-I'+str(source/'include'),
             '-I'+str(source/'dlls/crypt32'),'-Wl,-Bsymbolic','-Wl,-z,defs','-Wl,-z,now',
             '-Wl,-z,max-page-size=16384','-Wl,-soname,crypt32.so',
             '-Wl,-rpath,/data/data/com.android.aura/f/r/lib']
    outputs = {}
    with (work/'build.log').open('w') as log:
        for variant in ['baseline','fixed']:
            if variant == 'fixed':
                subprocess.run(['patch','--batch','--fuzz=0','-p1','-i',str(META/'wine-crypt32-fd.patch')],
                    cwd=work/'source',check=True,stdout=log,stderr=subprocess.STDOUT)
            target = output/(variant+'.so')
            subprocess.run([str(tc/'aarch64-linux-android34-clang'),*flags,str(local/'unixlib.c'),
                str(ntdll),'-ldl','-o',str(target)],check=True,stdout=log,stderr=subprocess.STDOUT,
                env=dict(os.environ,SOURCE_DATE_EPOCH='0'))
            target.write_bytes(relocate(target.read_bytes()))
            shutil.copyfile(target, output/(variant+'-symbols.so'))
            subprocess.run([str(tc/'llvm-strip'),'--strip-debug',str(target)],check=True)
            assert symbols(target) == symbols(old), 'Public native imports/exports changed'
            assert import_versions(target) == import_versions(old), 'Import symbol versions changed'
            info = elf_info(target.read_bytes())
            assert info['machine']=='0xb7' and info['abi']=='bionic' and info['minimumLoadAlignment']>=16384
            assert set(info['needed'])=={'ntdll.so','libdl.so','libc.so'}
            assert b'libgnutls.so\0' in target.read_bytes()
            outputs[variant] = {'bytes':target.stat().st_size,'sha256':sha(target),
                'symbolsSha256':sha(output/(variant+'-symbols.so'))}
    record = dict(schema='WINE_CRYPT32_FD_BUILD_V1', sourceCommit=SOURCE_COMMIT,
        baselineSourceSha256=SOURCE_SHA, packagedBeforeSha256=OLD_SHA, sourceHeadersSha256=tree_hash(source/'include'),
        gnutlsHeadersSha256=tree_hash(include/'gnutls'),configSha256=sha(include/'config.h'),
        builderSha256=sha(Path(__file__)),sourcePatchSha256=sha(META/'wine-crypt32-fd.patch'),
        nativeCompanionSha256=ntdll_sha,ndk='28.2.13676358',androidApi=34,
        samePublicImportsAndExports=True,gnutlsProvider='libgnutls.so',outputs=outputs,elf=info,
        originalToolchainReproduced=False,androidDeviceTested=False)
    (output/'build.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps(record,indent=2))


if __name__ == '__main__': main()

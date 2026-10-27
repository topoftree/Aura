#!/usr/bin/env python3
"""Build the v863.1 helper replacement without modifying the upstream ZIP."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
sys.path.insert(0, str(ROOT / 'tools/arm64ec'))
from audit_bundled_driver import audit
from audit_runtime_candidate import elf_info
from build_gpu_memory import symbols


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    assert 'Pkg.Revision = 28.2.13676358' in (args.ndk / 'source.properties').read_text()
    original = audit(bundle='qualcomm')
    args.work.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'engine/src/main/cpp/aura_qualcomm_uuid/uuid_shim.c'
    output = args.work / 'adrenoutils_extra.so'
    version = args.work / 'exports.map'
    version.write_text('{ global: get_driver_uuid; get_override_device_uuid; local: *; };\n')
    toolchain = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    options = ['-std=c11', '-O2', '-shared', '-fPIC', '-fvisibility=hidden', '-fno-omit-frame-pointer',
               '-Wall', '-Wextra', '-Werror', '-Wl,--no-undefined', '-Wl,-z,max-page-size=16384',
               '-Wl,-soname,adrenoutils_extra.so', '-Wl,--exclude-libs,ALL',
               '-ffile-prefix-map=' + str(ROOT) + '=.']
    subprocess.run([str(toolchain / 'aarch64-linux-android34-clang'), *options,
                    '-Wl,--version-script=' + str(version), str(source), '-ldl', '-llog', '-o', str(output)], check=True)
    subprocess.run([str(toolchain / 'llvm-strip'), '--strip-unneeded', str(output)], check=True)
    info = elf_info(output.read_bytes())
    assert info['machine'] == '0xb7' and info['abi'] != 'glibc' and info['minimumLoadAlignment'] >= 16384
    assert info['soname'] == ['adrenoutils_extra.so']
    assert set(symbols(output)['exports']) == {'get_driver_uuid', 'get_override_device_uuid'}
    assert b'AURA_QUALCOMM_UUID_V1 revision=uuid1' in output.read_bytes()
    target = ROOT / 'app/src/main/assets/drivers/qualcomm-uuid1'
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(output, target / output.name)
    shutil.copyfile(source, target / 'adrenoutils_extra.c')
    index = dict(sourceArchiveSha256=original['archive_sha256'], revision='uuid1',
                 files={name: dict(size=(target / name).stat().st_size, sha256=sha(target / name))
                        for name in ('adrenoutils_extra.so', 'adrenoutils_extra.c')})
    (target / 'index.json').write_text(json.dumps(index, indent=2) + '\n')
    record = dict(schema='QUALCOMM_UUID_BUILD_V1', revision='uuid1', ndk='28.2.13676358', androidApi=34,
                  source=source.relative_to(ROOT).as_posix(), sourceSha256=sha(source),
                  builderSha256=sha(Path(__file__)), originalArchiveSha256=original['archive_sha256'],
                  options=[x.replace(str(ROOT), '${ROOT}') for x in options],
                  outputSha256=sha(output), outputBytes=output.stat().st_size, elf=info,
                  exportedFunctions=sorted(symbols(output)['exports']), androidDeviceTested=False)
    Path(__file__).with_name('build.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(dict(sha256=sha(output), bytes=output.stat().st_size)))


if __name__ == '__main__':
    main()

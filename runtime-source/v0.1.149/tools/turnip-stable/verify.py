#!/usr/bin/env python3
"""Check the exact stable Android package, entry point, provenance and offline notices.

Optional NDK checks resolve public imports against API 34 stubs. They do not
execute the driver or verify the device's private libhardware namespace.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/arm64ec')]
from audit_bundled_driver import audit
from audit_graphics_abi import dynamic_symbols
from audit_runtime_candidate import elf_info


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(apk=None, ndk=None):
    lock = json.loads((HERE / 'sources.json').read_text())
    report = audit(apk, 'turnip-stable')
    assert report['archive_sha256'] == lock['distribution']['sha256']
    assert report['archive_bytes'] == lock['distribution']['bytes']
    assert report['source'] == lock['distribution']['release']
    if apk:
        import io
        with zipfile.ZipFile(apk) as package:
            payload = io.BytesIO(package.read('assets/drivers/turnip-26-2-5.zip'))
    else:
        payload = ROOT / lock['distribution']['archive']
    with zipfile.ZipFile(payload) as package:
        binary = package.read(lock['library'])
    abi = elf_info(binary)
    imports, exports = dynamic_symbols(binary)
    assert abi['abi'] == 'bionic' and abi['machine'] == '0xb7'
    assert abi['soname'] == [lock['library']] and not abi['searchPaths']
    assert abi['minimumLoadAlignment'] >= 16384
    assert set(abi['needed']) == {'libhardware.so', 'liblog.so', 'libnativewindow.so',
        'libsync.so', 'libm.so', 'libz.so', 'libdl.so', 'libc.so'}
    assert exports == {'HMI'}, 'Expected the Android Vulkan HAL entry, not a Linux ICD'
    assert lock['mesa']['binaryVersion'].encode() + b'\0' in binary
    assert report['metadata']['name'] == 'Mesa Turnip ' + lock['version'] + ' (Aura ext1)'
    assert report['metadata']['minApi'] == lock['ui']['minApi'] == 34
    assert report['metadata']['packageVersion'] == '2'
    assert report['metadata']['description'] == 'Aura app-private KGSL build; implemented Vulkan extensions exposed. Mesa ddaf85e353.'
    choice = (ROOT / 'app/src/main/java/dev/aura/console/runtime/VulkanDriverChoice.kt').read_text()
    assert all(s in choice for s in (lock['choice'], lock['component'], lock['distribution']['sha256']))
    assert '"' + lock['choice'] + '"' in (ROOT / 'engine/src/main/java/com/winlator/core/PerGameLaunch.kt').read_text()
    for name, record in lock['upstreamBuilder']['sources'].items():
        assert sha(ROOT / name) == record['sha256'], name
    build = json.loads((HERE / 'build.json').read_text())
    assert lock['locallyRebuilt'] and not lock['distribution']['unmodified']
    assert build['revision'] == lock['builder']['revision'] == '26.2.5-aura-ext1'
    assert build['androidApi'] == 34 and build['androidStrict'] is False
    assert build['sourceArchiveSha256'] == lock['mesa']['sha256'] and not build['sourcePatches']
    assert build['sourceFilesVerified'] == 12808
    assert build['buildScriptSha256'] == sha(HERE / 'build.py')
    assert build['libraryBytes'] == len(binary) and build['librarySha256'] == hashlib.sha256(binary).hexdigest()
    replay = json.loads((ROOT / 'docs/validation/turnip-26-2-5/extension-replay.json').read_text())
    assert replay['newLibrarySha256'] == build['librarySha256'] and replay['cases'] == 28
    assert replay['fixedEnumerationPreservesOnlySuppliedSupport'] and replay['fullOutputAndVkIncompleteVerified']
    notices = json.loads((HERE / 'notices.json').read_text())
    assert notices['source'] == lock['mesa']
    for name, record in notices['files'].items():
        assert (ROOT / name).stat().st_size == record['bytes'] and sha(ROOT / name) == record['sha256'], name
        if apk:
            with zipfile.ZipFile(apk) as package:
                assert hashlib.sha256(package.read('assets/' + name.split('/assets/', 1)[1])).hexdigest() == record['sha256']
    report.update(elf=abi, exportedHal=sorted(exports), importedSymbols=len(imports),
        mesaCommit=lock['mesa']['commit'], binaryVersion=lock['mesa']['binaryVersion'],
        sourceBuild=build['revision'], androidStrict=False, rebuiltFromPinnedSource=True,
        retainedLicenceFiles=len(notices['files']))
    if ndk:
        sysroot = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/34'
        available = set()
        for name in abi['needed']:
            if name != 'libhardware.so':
                available.update(dynamic_symbols((sysroot / name).read_bytes())[1])
        private = {'AHardwareBuffer_getNativeHandle': 'libnativewindow.so',
            'hw_get_module': 'libhardware.so', 'sync_wait': 'libsync.so'}
        assert imports - available == set(private), sorted(imports - available)
        # All three are established Android platform interfaces, outside the
        # public NDK stubs, also required by the existing R8 Android HAL.
        with zipfile.ZipFile(ROOT / 'app/src/main/assets/drivers/turnip-26-r8.zip') as old:
            old_imports, old_exports = dynamic_symbols(old.read('vulkan.ad07xx.so'))
        assert set(private) <= old_imports and old_exports == exports
        report['android34PublicImportsResolved'] = len(imports & available)
        report['privateAndroidImportsAlsoUsedByR8'] = private
        report['privateAndroidImportStatus'] = 'Expected platform interfaces; device namespace not verified'
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = verify(args.apk, args.ndk)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Verified Turnip 26.2.5 archive, ARM64 Android HAL, version, source provenance and notices; no device execution.')

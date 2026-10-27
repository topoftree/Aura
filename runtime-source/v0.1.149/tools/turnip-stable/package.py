#!/usr/bin/env python3
"""Package a verified ext1 build; print hashes for the checked-in bundle locks."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

HERE = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    receipt = json.loads((args.work / 'build.json').read_text())
    library = (args.work / 'install/lib/libvulkan_freedreno.so').read_bytes()
    assert receipt['revision'] == '26.2.5-aura-ext1'
    assert receipt['androidStrict'] is False and receipt['androidApi'] == 34
    assert receipt['librarySha256'] == digest(library) and receipt['libraryBytes'] == len(library)
    assert receipt['buildScriptSha256'] == digest((HERE / 'build.py').read_bytes())
    meta = dict(schemaVersion=1, name='Mesa Turnip 26.2.5 (Aura ext1)',
        description='Aura app-private KGSL build; implemented Vulkan extensions exposed. Mesa ddaf85e353.',
        author='Mesa Project; Aura build configuration', packageVersion='2', vendor='Mesa',
        driverVersion='Vulkan 1.4.354', minApi=34, libraryName='libvulkan_freedreno.so')
    files = {'libvulkan_freedreno.so': library, 'meta.json': (json.dumps(meta, indent=2) + '\n').encode()}
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / 'turnip-26-2-5.zip'
    with ZipFile(archive, 'w', compression=ZIP_DEFLATED, compresslevel=9) as zip:
        for name, data in files.items():
            info = ZipInfo(name, (2026, 10, 25, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = ZIP_DEFLATED
            zip.writestr(info, data, compresslevel=9)
    index = dict(files={name: dict(size=len(data), sha256=digest(data)) for name, data in files.items()})
    (args.output / 'turnip-26-2-5.json').write_text(json.dumps(index, indent=2) + '\n')
    print(json.dumps(dict(bytes=archive.stat().st_size, sha256=digest(archive.read_bytes()),
                         librarySha256=digest(library)), indent=2))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Reject stale/mismatched UUID helper assets before any app build."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from audit_bundled_driver import audit
from audit_runtime_candidate import elf_info


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(apk=None):
    original = audit(bundle='qualcomm')
    directory = ROOT / 'app/src/main/assets/drivers/qualcomm-uuid1'
    index = json.loads((directory / 'index.json').read_text())
    record = json.loads(Path(__file__).with_name('build.json').read_text())
    assert index['revision'] == record['revision'] == 'uuid1'
    assert index['sourceArchiveSha256'] == record['originalArchiveSha256'] == original['archive_sha256']
    assert record['builderSha256'] == digest(Path(__file__).with_name('build.py').read_bytes())
    source = ROOT / 'engine/src/main/cpp/aura_qualcomm_uuid/uuid_shim.c'
    assert record['source'] == source.relative_to(ROOT).as_posix()
    assert record['sourceSha256'] == digest(source.read_bytes())
    assert (directory / 'adrenoutils_extra.c').read_bytes() == source.read_bytes()
    assert set(index['files']) == {'adrenoutils_extra.so', 'adrenoutils_extra.c'}
    for name, expected in index['files'].items():
        data = (directory / name).read_bytes()
        assert len(data) == expected['size'] and digest(data) == expected['sha256'], name
    binary = (directory / 'adrenoutils_extra.so').read_bytes()
    assert digest(binary) == record['outputSha256'] and len(binary) == record['outputBytes']
    info = elf_info(binary)
    assert info == record['elf'] and info['abi'] == 'bionic' and info['machine'] == '0xb7'
    assert info['minimumLoadAlignment'] >= 16384 and info['soname'] == ['adrenoutils_extra.so']
    assert set(info['needed']) == {'libdl.so', 'liblog.so', 'libc.so'}
    if apk:
        with ZipFile(apk) as package:
            for name in ('index.json', *index['files']):
                assert package.read('assets/drivers/qualcomm-uuid1/' + name) == (directory / name).read_bytes()
        audit(apk=apk, bundle='qualcomm')
    return dict(revision='uuid1', sourceAndPayloadMatched=True, originalArchiveUnchanged=True,
                payloadSha256=digest(binary), apkPayloadsVerified=apk is not None, androidDeviceTested=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = json.dumps(verify(args.apk), indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result)
    print(result, end='')

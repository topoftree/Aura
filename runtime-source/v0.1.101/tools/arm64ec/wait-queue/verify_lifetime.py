#!/usr/bin/env python3
"""Reproduce a return-before-unlink race in pinned Wine source, then test its upstream fix.

No phone/game execution. Alert syscalls are controlled host boundaries. A negative
control exits before dereferencing expired stack data; fixed cases must finish
with the original status, correct wake TIDs and empty/relevant queues.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

SOURCE_SHA = 'a6bec61fbe4ff4bc5fa73d6693a0455a3144fe5272a9342ddb751e7715d70a3a'
SYNC_SHA = '1a95d17f1c429a044ae3c58ede47573ac3e96fbc79c97d19b2a4524ab082460e'
PATCH_SHA = '204455c164263c2764f67aa4550acf2be5dafacea9a2b0332439f562caf3a2e5'
HERE = Path(__file__).resolve().parent


def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    assert digest(args.source) == SOURCE_SHA
    patch = HERE / 'wine-wait-queue-lifetime.patch'
    assert digest(patch) == PATCH_SHA
    results = []
    with tempfile.TemporaryDirectory(prefix='wine-sync-') as temporary:
        root = Path(temporary)
        with tarfile.open(args.source) as archive:
            for entry in archive:
                name = '/'.join(Path(entry.name).parts[1:])
                if name in ('dlls/ntdll/sync.c', 'include/wine/list.h'):
                    p = root / name
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_bytes(archive.extractfile(entry).read())
        source = root / 'dlls/ntdll/sync.c'
        assert digest(source) == SYNC_SHA
        (root/'wine-list.h').write_bytes((root/'include/wine/list.h').read_bytes())
        for version in ('baseline', 'fixed'):
            if version == 'fixed':
                subprocess.run(['git', 'apply', '--unsafe-paths', str(patch)], cwd=root, check=True)
            text = source.read_text()
            start = text.index('struct futex_entry\n')
            end = text.index('/*************************************************************************\n *           RtlInitializeSListHead', start)
            (root/'wine-futex.inc').write_text(text[start:end])
            binary = root / version
            subprocess.run(['gcc', '-std=gnu11', '-O2', '-g', '-pthread', '-Wall', '-Wextra', '-Werror',
                            '-fsanitize=undefined', '-fno-sanitize-recover=all', '-I', str(root),
                            str(HERE/'test_lifetime.c'), '-o', str(binary)], check=True)
            for case in ('single-timeout', 'single-alert', 'all-timeout', 'all-alert', 'semantics'):
                expected = 42 if version == 'baseline' and case != 'semantics' else 0
                p = subprocess.run([str(binary), case], text=True, capture_output=True, timeout=10)
                assert p.returncode == expected, (version, case, p.returncode, p.stdout, p.stderr)
                assert not p.stderr, p.stderr
                if expected == 42:
                    assert 'NEGATIVE_CONTROL:' in p.stdout
                results.append(dict(version=version, scenario=case, expected_exit=expected,
                                    actual_exit=p.returncode, result=p.stdout.strip()))
    report = dict(schema='WINE_WAIT_QUEUE_LIFETIME_HOST_V1', source_sha256=SOURCE_SHA,
                  patch_sha256=PATCH_SHA, cases=results, checks=len(results),
                  android_game_execution=False, root_cause_on_phone_proven=False)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

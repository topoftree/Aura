#!/usr/bin/env python3
"""Exercise production UUID forwarding with simulated Bionic provider identities."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CASES = ['success', 'vendor_open_failure', 'device_open_failure', 'libdl_open_failure',
         'loader_symbol_missing', 'provider_symbol_missing', 'recursive_symbol',
         'untrusted_libdl', 'untrusted_linker', 'wrong_provider', 'reentrant_initialization',
         'invalid_arguments', 'concurrent_cached_calls', 'reentrant_log_callback']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cc', default='cc')
    args = parser.parse_args()
    env = dict(os.environ)
    env.pop('LD_PRELOAD', None)
    env['ASAN_OPTIONS'] = 'detect_leaks=0:abort_on_error=1'
    with tempfile.TemporaryDirectory(prefix='aura-uuid-tests-') as work:
        p = Path(work)
        (p / 'android').mkdir()
        (p / 'android/log.h').write_text('#define ANDROID_LOG_INFO 4\nint __android_log_write(int, const char *, const char *);\n')
        options = ['-std=c11', '-O1', '-g', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                   '-I' + work, '-Ddlopen=aura_test_dlopen', '-Ddlsym=aura_test_dlsym',
                   '-Ddladdr=aura_test_dladdr', '-Ddlclose=aura_test_dlclose']
        subprocess.run([args.cc, *options, str(ROOT / 'engine/src/main/cpp/aura_qualcomm_uuid/uuid_shim.c'),
                        str(Path(__file__).with_name('test_shim.c')), '-ldl', '-pthread', '-o', str(p / 'test')], check=True)
        for mode, name in enumerate(CASES):
            result = subprocess.run([str(p / 'test'), str(mode)], env=env, capture_output=True, text=True, timeout=10)
            assert result.returncode == 0 and result.stdout.strip() == 'PASS', (name, result.returncode, result.stderr)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(schema='QUALCOMM_UUID_CHECKS_V1', cases=CASES, passed=len(CASES),
        sanitizers=['address', 'undefined'], androidNamespaceEmulated=True, androidDeviceTested=False), indent=2) + '\n')
    print(f'Passed {len(CASES)} production-source checks under ASan/UBSan; Android namespaces are simulated.')


if __name__ == '__main__':
    main()

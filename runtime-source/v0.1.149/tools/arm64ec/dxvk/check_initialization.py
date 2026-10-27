#!/usr/bin/env python3
"""Compile and exercise actual upstream/patched headers; no Android execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
COMMIT = 'c3dd74be6baec53786d4e064a572185b70347a17'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--cxx', default='c++')
    a = p.parse_args()
    meta_path = HERE / 'initialization-build.json'
    if meta_path.exists():
        meta = json.loads(meta_path.read_text())
        for name, digest in meta['source_files'].items():
            assert hashlib.sha256((a.source / name).read_bytes()).hexdigest() == digest
    results = {}
    # The managed executor traces children; LeakSanitizer cannot run under
    # ptrace. ASan/UBSan stay enabled, and Rc destruction is checked explicitly.
    environment = dict(os.environ, ASAN_OPTIONS='detect_leaks=0')
    with tempfile.TemporaryDirectory(prefix='aura-dxgi-test-') as temp:
        base = Path(temp)
        for version in ('original', 'patched'):
            source = base / version
            (source / 'rc').mkdir(parents=True)
            for name in ('util_singleton.h', 'rc/util_rc_ptr.h', 'util_aura_init.h'):
                if version == 'original' and name == 'util_singleton.h':
                    data = subprocess.check_output(['git', 'show', COMMIT + ':src/util/' + name], cwd=a.source)
                else:
                    data = (a.source / 'src/util' / name).read_bytes()
                (source / name).write_bytes(data)
            (source / 'thread.h').write_text('#include <mutex>\nnamespace dxvk { using mutex = std::mutex; }\n')
            (source / 'util_env.h').write_text('''#pragma once
#include <string>
using DWORD = unsigned;
using ULONGLONG = unsigned long long;
using HMODULE = void*;
DWORD GetLastError();
void SetLastError(DWORD);
DWORD GetCurrentProcessId();
ULONGLONG GetTickCount64();
HMODULE GetModuleHandleA(const char*);
void* GetProcAddress(HMODULE, const char*);
namespace dxvk::env { std::string getEnvVar(const char*); }
''')
            exe = source / 'test'
            subprocess.run([a.cxx, '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-fno-pie', '-no-pie',
                '-pthread', '-I' + str(source), str(HERE / 'test_initialization.cpp'), '-o', str(exe)],
                check=True, capture_output=True, timeout=60)
            for mode in (('original',) if version == 'original' else
                         ('retry', 'disabled', 'invalid', 'enabled', 'timeout', 'budget', 'fallback')):
                result = subprocess.run([exe, mode], capture_output=True, text=True, timeout=30, env=environment)
                assert result.returncode == 0, (mode, result.returncode, result.stderr)
                if mode == 'fallback':
                    assert result.stderr == 'AURA_DXVK_INIT_V1 pid=100; stage=CREATE_INSTANCE; result=-9; value=4206592\n'
                else:
                    assert result.stderr == ''
                results[mode] = result.stdout.strip()
    report = dict(schema='AURA_DXGI_INITIALIZATION_CHECKS_V1', tests=len(results), results=results,
                  sanitizers=['ASan', 'UBSan'], source_commit=COMMIT,
                  leak_sanitizer='Unavailable under executor ptrace; explicit Rc lifetime checks used',
                  scope='Actual Singleton/Rc and trace header; host mutex and Wine API doubles',
                  android_execution=False)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

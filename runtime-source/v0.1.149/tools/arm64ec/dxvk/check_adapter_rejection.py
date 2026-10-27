#!/usr/bin/env python3
"""Replay the real upstream/patched adapter decisions with explicit host GPU/API doubles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
COMMIT = 'c3dd74be6baec53786d4e064a572185b70347a17'


def function(text, name):
    start = text.index(name)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    meta = json.loads((HERE / 'initialization-build.json').read_text())
    for name, sha in meta['source_files'].items():
        assert hashlib.sha256((args.source / name).read_bytes()).hexdigest() == sha
    source = (args.source / 'src/dxvk/dxvk_device_info.cpp').read_text()
    features = re.findall(r'^\s+ENABLE_(?:EXT_FEATURE|FEATURE|EXT)\(([^)]+)\)',
                         source.split('return {{', 1)[1].split('}};', 1)[0], re.M)
    ids = {str(i): parts[-2].strip() for i, entry in enumerate(features, 1)
           if (parts := entry.split(','))[-1].strip() == 'true'}
    assert ids == meta['adapter_rejection']['feature_ids']
    results = {}
    with tempfile.TemporaryDirectory(prefix='aura-adapter-test-') as directory:
        base = Path(directory)
        (base / 'util_aura_init.h').write_bytes((args.source / 'src/util/util_aura_init.h').read_bytes())
        (base / 'util_env.h').write_text('''#pragma once
#include <string>
using DWORD = unsigned; using ULONGLONG = unsigned long long; using HMODULE = void*;
DWORD GetLastError(); void SetLastError(DWORD); DWORD GetCurrentProcessId();
ULONGLONG GetTickCount64(); HMODULE GetModuleHandleA(const char*); void* GetProcAddress(HMODULE, const char*);
namespace dxvk::env { std::string getEnvVar(const char*); }
''')
        signatures = {
            'src/dxvk/dxvk_device_info.cpp': 'std::optional<std::string> DxvkDeviceCapabilities::checkDeviceCompatibility()',
            'src/dxvk/dxvk_device_filter.cpp': 'bool DxvkDeviceFilter::testAdapter(DxvkAdapter& adapter) const',
        }
        for variant in ('upstream', 'patched'):
            extracted = []
            for path, signature in signatures.items():
                text = subprocess.check_output(['git', 'show', COMMIT + ':' + path], cwd=args.source, text=True) \
                    if variant == 'upstream' else (args.source / path).read_text()
                extracted.append(function(text, signature))
            (base / 'decisions.h').write_text('namespace dxvk {\n' + '\n'.join(extracted) + '\n}\n')
            exe = base / variant
            subprocess.run(['c++', '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-fno-pie', '-no-pie',
                '-pthread', '-I' + str(base), str(HERE / 'test_adapter_rejection.cpp'), '-o', str(exe)],
                check=True, timeout=60)
            for enabled in (False, True):
                run = subprocess.run([exe, 'on' if enabled else 'off', variant], capture_output=True,
                    text=True, timeout=30, env=dict(os.environ, ASAN_OPTIONS='detect_leaks=0'))
                assert run.returncode == 0 and not run.stderr, (variant, enabled, run.stdout, run.stderr)
                results[variant + ('/on' if enabled else '/off')] = run.stdout.strip()
    assert len(set(results.values())) == 1, results
    report = dict(schema='AURA_DXGI_ADAPTER_REJECTION_CHECKS_V1', results=results,
                  comparisons=4, scenarios_per_comparison=15,
                  source_commit=COMMIT, required_feature_ids=ids,
                  sanitizers=['ASan', 'UBSan'], scope='Real compatibility and filter functions; host capability/API doubles',
                  android_execution=False, gpu_queries_executed=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: 15 adapter scenarios, upstream/patched x trace off/on; feature ID map matches pinned source')


if __name__ == '__main__':
    main()

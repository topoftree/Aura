#!/usr/bin/env python3
"""Exercise the real DXGI trace and closest-mode functions with host Wine/WSI doubles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
COMMIT = 'c3dd74be6baec53786d4e064a572185b70347a17'


def function(text, name):
    start = text.index(name)
    end = text.index('\n  }', start) + 4
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    meta = json.loads((HERE / 'initialization-build.json').read_text())
    for name, digest in meta['source_files'].items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest, name
    env = dict(os.environ, ASAN_OPTIONS='detect_leaks=0')
    results = {}
    with tempfile.TemporaryDirectory(prefix='aura-display-check-') as temp:
        base = Path(temp)
        (base / 'dxgi').mkdir(); (base / 'util').mkdir()
        shutil.copyfile(source / 'src/dxgi/dxgi_aura_display.h', base / 'dxgi/dxgi_aura_display.h')
        # Exact external API boundary doubles; production DXGI/Wine ABI is also
        # verified by both cross-builds and the packaged PE import/export contract.
        (base / 'dxgi/dxgi_include.h').write_text('''#pragma once
#include <cstdint>
using UINT = uint32_t; using DWORD = uint32_t; using ULONGLONG = unsigned long long;
using HRESULT = int32_t; using HMODULE = void*; using IUnknown = void;
using DXGI_FORMAT = uint32_t;
struct DXGI_RATIONAL { UINT Numerator = 0, Denominator = 0; };
struct DXGI_MODE_DESC { UINT Width=0, Height=0; DXGI_RATIONAL RefreshRate;
  DXGI_FORMAT Format=0; UINT ScanlineOrdering=0, Scaling=0; };
struct DXGI_MODE_DESC1 : DXGI_MODE_DESC { uint32_t Stereo=0; };
struct DXGI_SWAP_CHAIN_DESC1 { UINT Width=0, Height=0; DXGI_FORMAT Format=0;
  uint32_t Stereo=0; UINT Flags=0, BufferCount=0; };
struct DXGI_SWAP_CHAIN_FULLSCREEN_DESC { DXGI_RATIONAL RefreshRate;
  UINT ScanlineOrdering=0, Scaling=0; int Windowed=1; };
constexpr HRESULT S_OK=0, DXGI_ERROR_INVALID_CALL=int32_t(0x887a0001), DXGI_ERROR_NOT_FOUND=int32_t(0x887a0002);
constexpr UINT DXGI_FORMAT_UNKNOWN=0, DXGI_MODE_SCANLINE_ORDER_UNSPECIFIED=0,
  DXGI_MODE_SCALING_UNSPECIFIED=0, DXGI_ENUM_MODES_SCALING=2;
#define STDMETHODCALLTYPE
#define FALSE 0
#define FAILED(hr) ((hr)<0)
#define __cdecl
DWORD GetLastError(); void SetLastError(DWORD); DWORD GetCurrentProcessId(); DWORD GetCurrentThreadId();
ULONGLONG GetTickCount64(); HMODULE GetModuleHandleA(const char*); void* GetProcAddress(HMODULE, const char*);
''')
        (base / 'util/util_env.h').write_text('''#pragma once
#include <string>
namespace dxvk::env { std::string getEnvVar(const char*); std::string getExeName(); }
''')
        old = subprocess.check_output(['git', 'show', COMMIT + ':src/dxgi/dxgi_output.cpp'], cwd=source, text=True)
        new = (source / 'src/dxgi/dxgi_output.cpp').read_text()
        names = ['HRESULT STDMETHODCALLTYPE DxgiOutput::FindClosestMatchingMode(',
                 'HRESULT STDMETHODCALLTYPE DxgiOutput::FindClosestMatchingMode1(',
                 'void DxgiOutput::FilterModesByDesc(']
        assert function(old, names[2]) == function(new, names[2]), 'Matching algorithm changed'
        for variant, text in [('upstream', old), ('patched', new)]:
            (base / 'display_functions.h').write_text('namespace dxvk {\n' +
                '\n'.join(function(text, name) for name in names) + '\n}\n')
            exe = base / variant
            subprocess.run(['c++', '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                            '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-fno-pie', '-no-pie',
                            '-pthread', '-I'+str(base), str(HERE / 'test_display_trace.cpp'), '-o', str(exe)],
                           check=True, capture_output=True, timeout=60)
            modes = ['api-off', 'api-on'] if variant == 'upstream' else [
                'api-off', 'api-on', 'disabled', 'invalid-flag', 'other-exe', 'nested',
                'unwound', 'budget-concurrent', 'timeout', 'fallback', 'maximum-fields', 'gate-error']
            for mode in modes:
                run = subprocess.run([exe, mode], capture_output=True, text=True, timeout=30, env=env)
                assert run.returncode == 0, (variant, mode, run.stdout, run.stderr)
                assert not run.stderr or mode == 'fallback', run.stderr
                results[variant + '/' + mode] = run.stdout.strip()
        # Real upstream/patched functions agree in both trace states: exact/missing
        # sizes, zero-size default, refresh override, invalid arguments and no modes.
        assert len({results[v+'/'+m] for v in ('upstream', 'patched') for m in ('api-off', 'api-on')}) == 1
    report = dict(schema='AURA_BF3_DISPLAY_CHECKS_V1', results=results, tests=len(results),
                  sanitizers=['ASan', 'UBSan'], source_commit=COMMIT,
                  scope='Real trace header and upstream/patched FindClosestMatchingMode/1 plus FilterModesByDesc; host API/WSI doubles',
                  android_execution=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

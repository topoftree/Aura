#!/usr/bin/env python3
"""Exercise actual 2.6.2 extension/feature fallback and preserved Aura fixes.

Vulkan/Win32 calls are explicit host doubles, not a Vulkan device or phone test.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
COMMIT = '9d6f54a1ade20d1d27dd421024717a636f3d8c68'


def function(text, start):
    begin = text.index(start)
    brace = text.index('{', begin)
    depth = 1
    end = brace + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[begin:end] + '\n'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    source = a.source.resolve()
    meta = json.loads((HERE / 'compatibility-build.json').read_text())
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip() == COMMIT
    for name, expected in meta['source_files'].items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == expected, name
    def original(name):
        return subprocess.check_output(['git', 'show', COMMIT + ':' + name], cwd=source, text=True)
    # These fallback mechanisms and capability requirements are unchanged upstream code.
    for name in ('src/dxvk/dxvk_extensions.h', 'src/dxvk/dxvk_extensions.cpp', 'src/dxvk/dxvk_memory.cpp'):
        assert (source / name).read_text() == original(name)
    environment = dict(os.environ, ASAN_OPTIONS='detect_leaks=0')
    result = {}
    with tempfile.TemporaryDirectory(prefix='aura-dxvk-compat-tests-') as temp:
        root = Path(temp)
        def compile_run(name, files, modes=(None,)):
            exe = root / name
            command = ['c++', '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                       '-Wno-missing-field-initializers', '-fsanitize=address,undefined',
                       '-fno-omit-frame-pointer', '-fno-pie', '-no-pie', '-pthread',
                       '-I' + str(root), '-I' + str(source / 'include/vulkan/include'),
                       *map(str, files), '-o', str(exe)]
            subprocess.run(command, check=True, text=True, timeout=60)
            for mode in modes:
                r = subprocess.run([str(exe)] + ([mode] if mode else []),
                                   capture_output=True, text=True, timeout=30, env=environment)
                assert r.returncode == 0, (name, mode, r.stdout, r.stderr)
                result[name + (':' + mode if mode else '')] = r.stdout.strip()
        # Keep the real extension classes, full extension catalogue and enabled-name algorithm.
        header = (source / 'src/dxvk/dxvk_extensions.h').read_text().replace('#include "dxvk_include.h"', '''
#include <vulkan/vulkan_core.h>
#include <string>
#include <sstream>
namespace dxvk {
template<class> class Rc;
namespace vk { struct LibraryFn; struct InstanceFn; }
namespace str { template<typename... T> std::string format(T&&... t) {
  std::ostringstream out; (out << ... << t); return out.str(); } }
struct Logger { static void info(const std::string&) {} };
}
''')
        win32_names = '\n'.join(line for line in (source / 'include/vulkan/include/vulkan/vulkan_win32.h').read_text().splitlines()
                                if line.startswith('#define ') and '_EXTENSION_NAME ' in line)
        header = header.replace('#include <vulkan/vulkan_core.h>', '#include <vulkan/vulkan_core.h>\n' + win32_names)
        (root / 'dxvk_extensions.h').write_text(header)
        cpp = (source / 'src/dxvk/dxvk_extensions.cpp').read_text()
        (root / 'extensions.cpp').write_text(cpp[:cpp.index('  DxvkNameSet DxvkNameSet::enumInstanceLayers')] + '}\n')
        ext_list = function((source / 'src/dxvk/dxvk_adapter.cpp').read_text(),
                            'std::vector<DxvkExt*> DxvkAdapter::getExtensionList(')
        (root / 'extension_test.cpp').write_text('''#include "dxvk_extensions.h"
#include <cassert>
#include <iostream>
namespace dxvk {
struct DxvkAdapter { static std::vector<DxvkExt*> getExtensionList(DxvkDeviceExtensions&); };
''' + ext_list + '''}
int main() {
  unsigned cases = 0;
  for (unsigned mask = 0; mask < 4; ++mask) {
    dxvk::DxvkDeviceExtensions ext;
    auto list = dxvk::DxvkAdapter::getExtensionList(ext);
    dxvk::DxvkNameSet available, enabled;
    for (auto* item : list) if (item->mode() == dxvk::DxvkExtMode::Required) available.add(item->name());
    if (mask & 1) available.add(VK_KHR_MAINTENANCE_5_EXTENSION_NAME);
    if (mask & 2) available.add(VK_KHR_PIPELINE_LIBRARY_EXTENSION_NAME);
    assert(available.enableExtensions(list.size(), list.data(), &enabled));
    assert(bool(ext.khrMaintenance5) == bool(mask & 1));
    assert(bool(ext.khrPipelineLibrary) == bool(mask & 2));
    assert(bool(enabled.supports(VK_KHR_MAINTENANCE_5_EXTENSION_NAME)) == bool(mask & 1));
    assert(bool(enabled.supports(VK_KHR_PIPELINE_LIBRARY_EXTENSION_NAME)) == bool(mask & 2));
    ++cases;
  }
  // Missing required features still fail: compatibility is not a success override.
  for (const char* missing : { VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME }) {
    dxvk::DxvkDeviceExtensions ext;
    auto list = dxvk::DxvkAdapter::getExtensionList(ext);
    dxvk::DxvkNameSet available, enabled;
    for (auto* item : list) if (item->mode() == dxvk::DxvkExtMode::Required && std::string(item->name()) != missing)
      available.add(item->name());
    assert(!available.enableExtensions(list.size(), list.data(), &enabled)); ++cases;
  }
  std::cout << "extension_cases=" << cases << '\\n';
}
''')
        compile_run('extensions', [root / 'extensions.cpp', root / 'extension_test.cpp'])
        shader = function((source / 'src/dxvk/dxvk_shader.cpp').read_text(), 'void DxvkShaderStageInfo::addStage(')
        # The pipeline observer is compiled unchanged in check_pipeline_trace.py.
        # Keep this older fallback double focused on the exact original Vulkan call.
        shader = shader.replace('aura_pipeline::createShader(vk->vkCreateShaderModule, ', 'vk->vkCreateShaderModule(')
        memory = function((source / 'src/dxvk/dxvk_memory.cpp').read_text(),
                          'VkBufferView DxvkResourceBufferViewMap::createBufferView(')
        (root / 'actual_fallback.inc').write_text(shader + memory)
        compile_run('fallback', [HERE / 'test_compat_fallback.cpp'])
        trace = (source / 'src/util/aura_flow_trace.h').read_text()
        gate = trace[trace.index('    DWORD saved = GetLastError();'):trace.index('    wchar_t path[')]
        (root / 'flow_gate.cpp').write_text('''#include <cassert>
#include <iostream>
#include <cwchar>
using DWORD = unsigned; using HMODULE = void*;
struct Data {}; struct IMAGE_DOS_HEADER {};
IMAGE_DOS_HEADER __ImageBase; Data mapped;
static HMODULE selected; static DWORD lastError = 17; static unsigned entered = 0;
DWORD GetLastError() { return lastError; } void SetLastError(DWORD v) { lastError = v; }
HMODULE GetModuleHandleW(const wchar_t* name) {
  assert(std::wcscmp(name, L"d3d9.dll") == 0); lastError = 99; return selected;
}
Data* guard() {
''' + gate + '''
  (void)result; ++entered; return &mapped; // boundary before environment/file access
}
int main() {
  for (void* module : {static_cast<void*>(nullptr), static_cast<void*>(&mapped), static_cast<void*>(&__ImageBase)}) {
    selected = module; lastError = 17;
    bool expected = module == &__ImageBase;
    assert(bool(guard()) == expected);
    assert(lastError == (expected ? 99u : 17u));
  }
  assert(entered == 1); std::cout << "flow_module_gate_cases=3\\n";
}
''')
        compile_run('flow_module_gate', [root / 'flow_gate.cpp'])
        # Exercise original vs patched flush trackers, with default-off equivalence.
        for label, data in [('baseline', original('src/util/util_flush.h')),
                            ('patched', (source / 'src/util/util_flush.h').read_text())]:
            (root / (label + '.h')).write_text(data.replace('namespace dxvk', 'namespace ' + label))
            data = original('src/util/util_flush.cpp') if label == 'baseline' else (source / 'src/util/util_flush.cpp').read_text()
            (root / (label + '.cpp')).write_text(data.replace('"util_flush.h"', '"' + label + '.h"').replace('namespace dxvk', 'namespace ' + label))
        (root / 'flush_test.cpp').write_text('''#include <algorithm>
#include <cassert>
#include <iostream>
#include "baseline.cpp"
#include "patched.cpp"
int main() {
  unsigned cases = 0;
  for (unsigned allowed = 0; allowed < 5; ++allowed) {
    baseline::GpuFlushTracker old(static_cast<baseline::GpuFlushType>(allowed));
    patched::GpuFlushTracker now(static_cast<patched::GpuFlushType>(allowed), false);
    uint64_t submission = 0;
    for (unsigned chunk = 1; chunk < 10001; ++chunk) {
      unsigned type = (chunk * 7) % 5;
      unsigned complete = submission > 3 ? submission - 3 : 0;
      bool a = old.considerFlush(static_cast<baseline::GpuFlushType>(type), chunk, complete);
      bool b = now.considerFlush(static_cast<patched::GpuFlushType>(type), chunk, complete);
      assert(a == b); ++cases;
      if (a) { old.notifyFlush(chunk, ++submission); now.notifyFlush(chunk, submission); }
    }
  }
  using T = patched::GpuFlushType;
  for (unsigned chunk : {1u, 3u, 5u, 6u, 20u}) {
    for (auto type : {T::ExplicitFlush, T::ImplicitSynchronization}) {
      patched::GpuFlushTracker off(T::ImplicitWeakHint), on(T::ImplicitWeakHint, true);
      off.notifyFlush(0, 4); on.notifyFlush(0, 4);
      assert(off.considerFlush(type, chunk, 1) == on.considerFlush(type, chunk, 1)); ++cases;
    }
  }
  for (unsigned chunk : {3u, 4u, 5u, 6u}) {
    patched::GpuFlushTracker on(T::ImplicitWeakHint, true); on.notifyFlush(0, 4);
    assert(on.considerFlush(T::ImplicitStrongHint, chunk, 1) == (chunk >= 6)); ++cases;
  }
  std::cout << "flush_cases=" << cases << '\\n';
}
''')
        compile_run('batching', [root / 'flush_test.cpp'])
        (root / 'rc').mkdir()
        for name in ('util_singleton.h', 'rc/util_rc_ptr.h', 'util_aura_init.h'):
            (root / name).write_text((source / 'src/util' / name).read_text())
        (root / 'thread.h').write_text('#include <mutex>\nnamespace dxvk { using mutex = std::mutex; }\n')
        (root / 'util_env.h').write_text('''#pragma once
#include <string>
using DWORD = unsigned; using ULONGLONG = unsigned long long; using HMODULE = void*;
DWORD GetLastError(); void SetLastError(DWORD); DWORD GetCurrentProcessId();
ULONGLONG GetTickCount64(); HMODULE GetModuleHandleA(const char*); void* GetProcAddress(HMODULE, const char*);
namespace dxvk::env { std::string getEnvVar(const char*); }
''')
        compile_run('initialization', [HERE / 'test_initialization.cpp'],
                    ('retry', 'disabled', 'invalid', 'enabled', 'timeout', 'budget', 'fallback'))
    report = dict(schema='AURA_DXVK_COMPAT_HOST_V1', source_commit=COMMIT, results=result,
                  sanitizers=['ASan', 'UBSan'], android_execution='not run',
                  scope='Actual extension selection, shader and buffer view fallback, flush tracker, singleton and bounded trace; modeled API boundaries')
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

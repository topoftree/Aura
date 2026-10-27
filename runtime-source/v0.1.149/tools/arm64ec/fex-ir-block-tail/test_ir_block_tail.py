#!/usr/bin/env python3
"""Differential test of the real before/after FEX IREmitter on an x86-64 host.

Generated headers must come from configuring the pinned FEX source. No guest
program is run; timings cover synthetic IR construction, not game FPS/JIT total.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline-source', 'patched-source', 'generated', 'work', 'report'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--cxx', default='g++')
    p.add_argument('--sanitize', action='store_true')
    args = p.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    helper = args.work / 'ir-definitions.cpp'
    helper.write_text('''#include "Interface/IR/IR.h"
namespace FEXCore::IR {
#define IROP_GETNAME_IMPL
#define IROP_GETRAARGS_IMPL
#define IROP_REG_CLASSES_IMPL
#define IROP_HASSIDEEFFECTS_IMPL
#define IROP_SIZES_IMPL
#define IROP_GETHASDEST_IMPL
#include <FEXCore/IR/IRDefines.inc>
}
''')
    rows = {}
    for name, source in [('baseline', args.baseline_source), ('patched', args.patched_source)]:
        source = source.resolve()
        binary, output = args.work / name, args.work / (name + '.ir')
        includes = [source / x for x in ('FEXCore/Source', 'FEXCore/include', 'FEXHeaderUtils',
                    'CodeEmitter', 'External/fmt/include', 'External/unordered_dense/include')]
        includes += [args.generated / 'include']
        command = [args.cxx, '-std=c++20', '-O2', '-fno-strict-aliasing', '-Wno-changes-meaning',
                   '-DARCHITECTURE_x86_64=1', '-DASSERTIONS_ENABLED=1']
        if args.sanitize:
            command += ['-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        command += ['-I' + str(x) for x in includes]
        command += [str(HERE / 'test_ir_block_tail.cpp'), str(helper)]
        command += [str(source / x) for x in ('FEXCore/Source/Interface/IR/IREmitter.cpp',
                    'FEXCore/Source/Utils/LogManager.cpp', 'FEXCore/Source/Utils/ForcedAssert.cpp',
                    'FEXCore/Source/Utils/AllocatorHooks.cpp',
                    'External/fmt/src/format.cc')]
        command += ['-o', str(binary)]
        with (args.work / (name + '-build.log')).open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        env = dict(os.environ)
        if args.sanitize:
            # LeakSanitizer cannot inspect threads under the sandbox's ptrace.
            # Address/undefined checks remain active, including scope lifetime.
            env['ASAN_OPTIONS'] = 'detect_leaks=0'
        result = subprocess.run([binary, output], capture_output=True, text=True, env=env)
        (args.work / (name + '-run.log')).write_text(result.stdout + result.stderr)
        result.check_returncode()
        readings = [json.loads(line) for line in result.stdout.splitlines()]
        rows[name] = {'checks': readings[-1]['checks'], 'timings': readings[:-1],
                      'ir_bytes': output.stat().st_size,
                      'ir_sha256': hashlib.sha256(output.read_bytes()).hexdigest()}
    assert rows['baseline']['ir_sha256'] == rows['patched']['ir_sha256'], 'IR changed'
    assert rows['baseline']['checks'] == rows['patched']['checks'] == 4132
    report = dict(schema='FEX_IR_BLOCK_TAIL_TEST_V1', actual_iremitter_compiled=True,
                  synthetic_ir_byte_identical=True, results=rows,
                  address_and_undefined_sanitizers=args.sanitize,
                  leak_sanitizer_enabled=False,
                  host_microbenchmark_only=True, android_device_tested=False,
                  game_fps_improvement_measured=False)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()

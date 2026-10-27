#!/usr/bin/env python3
"""Test the production helper on x64 Linux; optionally replay supplied hook setup.

The optional replay requires Unicorn and exact hash-matching input DLLs. It never
loads them through Wine/Windows or executes the full initializer or game.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
CASES = ('active case off missing long-flag other-exe installer suffix custom other-module '
         'other-export layout bounds bad-jump alloc-fail protect-fail flush-fail').split()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--module', type=Path)
    p.add_argument('--kernel32', type=Path)
    p.add_argument('--report', type=Path, required=True)
    args = p.parse_args()
    if bool(args.module) != bool(args.kernel32):
        p.error('Provide both supplied module and kernel32 for bounded replay')
    helper = HERE / 'mgs-tick-thunk/aura_mgs_tick_thunk.h'
    report = dict(schema='MGS_TICK_THUNK_TEST_V1', cases=CASES,
                  helperSha256=hashlib.sha256(helper.read_bytes()).hexdigest(),
                  realHostX64Calls=True, realMmapAndMprotect=True,
                  syntheticWindowsBoundaries=True, androidGameExecuted=False, wineFexExecuted=False)
    with tempfile.TemporaryDirectory(prefix='aura-tick-test-') as path:
        work = Path(path)
        executable, template = work / 'probe', work / 'entry.bin'
        subprocess.run(['gcc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-fshort-wchar', '-O2',
                        str(HERE / 'mgs-tick-thunk/test_tick_thunk.c'), '-o', str(executable)], check=True)
        for case in CASES:
            subprocess.run([str(executable), case, str(template)], check=True)
        report['passed'] = len(CASES)
        if args.module:
            from replay_ground_zeroes_system_hook import replay
            bases = (0x6fffd30000, 0x190000000, 0x6f00000000)
            report['baselineReplay'] = [replay(args.module, args.kernel32, base, True) for base in bases]
            report['adapterReplay'] = [replay(args.module, args.kernel32, base, True, template) for base in bases]
            report['fullDllInitializationExecuted'] = False
            report['generatedGameHooksExecuted'] = False
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('Passed host cases:', report['passed'])


if __name__ == '__main__':
    main()

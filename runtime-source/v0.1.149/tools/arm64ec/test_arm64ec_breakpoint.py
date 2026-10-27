#!/usr/bin/env python3
"""Execute the shipped and rebuilt ARM64 SEH handlers in Unicorn 2.1.4.

This checks the real PE instructions and an AMD64-compatible EC context. It does
not execute the game, Wine exception dispatcher, FEX or Android.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

import unicorn
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import UC_ARM64_REG_PC, UC_ARM64_REG_W0, UC_ARM64_REG_X2, UC_ARM64_REG_X30

from build_arm64ec_breakpoint import HANDLER_RVA, BASE_HANDLER, verify_candidate
from pe_dependencies import PeImage

CODE, STOP, PAGE, CONTEXT = 0x100000, 0x100100, 0x200000, 0x200200
PC_OFFSET, FP_OFFSET = 0xf8, 0x108
CONTEXT_IN_PAGE = CONTEXT - PAGE
MASK = (1 << 64) - 1


def execute(code, initial):
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    machine.mem_map(CODE, 4096)
    machine.mem_write(CODE, code)
    machine.mem_map(PAGE, 4096)
    machine.mem_write(PAGE, initial)
    machine.reg_write(UC_ARM64_REG_X2, CONTEXT)
    machine.reg_write(UC_ARM64_REG_X30, STOP)
    machine.reg_write(UC_ARM64_REG_W0, 0xffffffff)
    machine.emu_start(CODE, STOP, count=8)
    assert machine.reg_read(UC_ARM64_REG_PC) == STOP, "Handler must return"
    assert machine.reg_read(UC_ARM64_REG_W0) == 0, "ExceptionContinueExecution"
    assert machine.reg_read(UC_ARM64_REG_X2) == CONTEXT
    return bytes(machine.mem_read(PAGE, 4096))


def put(buffer, offset, value):
    struct.pack_into('<Q', buffer, CONTEXT_IN_PAGE + offset, value)


def get(buffer, offset):
    return struct.unpack_from('<Q', buffer, CONTEXT_IN_PAGE + offset)[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--built', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    before = (args.built / 'baseline.dll').read_bytes()
    after = (args.built / 'ntdll.dll').read_bytes()
    meta = json.loads((args.built / 'build.json').read_text())
    assert hashlib.sha256(after).hexdigest() == meta['candidate']['sha256']
    verify_candidate(before, after)
    pe = PeImage(before)
    offset = pe.offset(HANDLER_RVA, len(BASE_HANDLER))
    baseline, fixed = before[offset:offset+20], after[offset:offset+20]
    # The original BRK #0xf000 and its RET remain present in the candidate.
    assert before[offset-8:offset] == after[offset-8:offset] == bytes.fromhex('00003ed4c0035fd6')
    cases = []
    for pc in (0x6ffff03a50, 0x140001000, 0x7fff0000fff8):
        for fp in (0, 0x1234567800000000, 0xfffffffffffffffc):
            initial = bytearray((i * 17 + 31) & 255 for i in range(4096))
            put(initial, PC_OFFSET, pc)
            put(initial, FP_OFFSET, fp)
            original_result = execute(baseline, bytes(initial))
            expected_original = initial.copy()
            put(expected_original, FP_OFFSET, (fp + 4) & MASK)
            assert original_result == expected_original
            candidate_result = execute(fixed, bytes(initial))
            expected_fixed = initial.copy()
            put(expected_fixed, PC_OFFSET, pc + 4)
            # Whole-page equality checks all other context fields and both guards.
            assert candidate_result == expected_fixed
            cases.append(dict(pc=hex(pc), floatingPointSentinel=hex(fp),
                baselinePc=hex(get(original_result, PC_OFFSET)),
                correctedPc=hex(get(candidate_result, PC_OFFSET)),
                allOtherContextAndGuardBytesPreserved=True))
    # Bounded model of resuming at the same BRK: the old handler stays trapped.
    initial = bytearray(4096)
    put(initial, PC_OFFSET, 0x6ffff03a50)
    repeated = bytes(initial)
    for _ in range(4):
        repeated = execute(baseline, repeated)
        assert get(repeated, PC_OFFSET) == 0x6ffff03a50
    assert get(repeated, FP_OFFSET) == 16
    assert get(execute(fixed, bytes(initial)), PC_OFFSET) == 0x6ffff03a54
    # Verify the packaging guard rejects code changes outside the two operands.
    for position in (offset - 8, offset + 12):
        invalid = bytearray(after)
        invalid[position] ^= 1
        try:
            verify_candidate(before, bytes(invalid))
        except AssertionError:
            pass
        else:
            raise AssertionError('Unexpected instruction mutation accepted')
    report = dict(schema='WINE_ARM64EC_BREAKPOINT_TEST_V1', unicornVersion=unicorn.__version__,
        baselineSha256=hashlib.sha256(before).hexdigest(),
        candidateSha256=hashlib.sha256(after).hexdigest(),
        pairedInstructionCases=len(cases), cases=cases, unchangedBrkAndReturn=True,
        baselineRepeatedBreakpointReproduced=True, candidateResumesAtReturn=True,
        unexpectedCodeMutationRejected=True, contextOffsetCompileTimeAssertions=True,
        gameAndWineDispatcherExecuted=False, androidDeviceTested=False, result='PASS')
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'cases'}, indent=2))


if __name__ == '__main__':
    main()

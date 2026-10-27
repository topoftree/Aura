#!/usr/bin/env python3
"""Run production-emitted ARM64 checks in Unicorn; not a Wine/game/device test.

Build emit_fast_test.cpp against the pinned FEX CodeEmitter, emit its 24 blobs,
then pass their directory. No game code or device data is used.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random

from unicorn import Uc, UcError, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_MEM_READ, UC_PROT_ALL, UC_PROT_READ, UC_PROT_WRITE
import unicorn.arm64_const as arm


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('emitted', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    code, data, stop = 0x100000, 0x400000, 0x100800
    rng = random.Random(128)
    rows, total, faults = [], 0, 0
    for length in range(1, 9):
        for alias in range(3):
            blob = (args.emitted / f'check-{length}-{alias}.bin').read_bytes()
            uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            uc.mem_map(code, 4096, UC_PROT_ALL)
            uc.mem_write(code, blob)
            uc.mem_map(data, 8192, UC_PROT_READ | UC_PROT_WRITE)
            reads = []
            uc.hook_add(UC_HOOK_MEM_READ, lambda cpu, access, address, size, value, user: reads.append((address, size)))
            dst = (22, 20, 21)[alias]
            registers = [getattr(arm, f'UC_ARM64_REG_X{i}') for i in range(31)]
            fixed = [rng.getrandbits(64) for _ in registers]
            for i, register in enumerate(registers):
                uc.reg_write(register, fixed[i])
            patterns = [bytes(length), bytes([255]) * length, bytes([0x80]) * length,
                        bytes([0x55]) * length, bytes(rng.getrandbits(8) for _ in range(length))]
            cases = 0
            for offset in (0, 1, 3, 7, 15, 4096 - length, 4095, 8192 - length):
                for original in patterns:
                    changes = [None] + [(byte, bit) for byte in range(length) for bit in range(8)]
                    for change in changes:
                        current = bytearray(original)
                        if change:
                            current[change[0]] ^= 1 << change[1]
                        uc.mem_write(data + offset, bytes(current))
                        uc.reg_write(registers[20], data + offset)
                        uc.reg_write(registers[21], int.from_bytes(original, 'little'))
                        uc.reg_write(registers[22], fixed[22])
                        uc.reg_write(registers[30], stop)
                        flags = (cases % 16) << 28
                        uc.reg_write(arm.UC_ARM64_REG_NZCV, flags)
                        reads.clear()
                        uc.emu_start(code, stop, count=32)
                        assert uc.reg_read(arm.UC_ARM64_REG_PC) == stop
                        assert (uc.reg_read(registers[dst]) != 0) == (change is not None)
                        assert uc.reg_read(arm.UC_ARM64_REG_NZCV) == flags
                        touched = []
                        for address, size in reads:
                            assert data + offset <= address < address + size <= data + offset + length
                            touched.extend(range(address, address + size))
                        assert sorted(touched) == list(range(data + offset, data + offset + length))
                        for i in range(3, 30):
                            if i == dst:
                                continue
                            expected = data + offset if i == 20 else int.from_bytes(original, 'little') if i == 21 else fixed[i]
                            assert uc.reg_read(registers[i]) == expected, (length, alias, i)
                        cases += 1
            # A required byte outside readable memory must still fault; do not swallow it.
            uc.reg_write(registers[20], data + 8192 - length + 1)
            uc.reg_write(registers[21], 0)
            uc.reg_write(registers[30], stop)
            try:
                uc.emu_start(code, stop, count=32)
            except UcError:
                faults += 1
            else:
                raise AssertionError('Missing required-byte access violation')
            rows.append(dict(length=length, destinationAlias=alias, comparisons=cases,
                             instructionsExcludingReturn=len(blob) // 4 - 1,
                             emittedSha256=hashlib.sha256(blob).hexdigest()))
            total += cases
    report = dict(schema='CRYSIS_FAST_SMC_EMITTER_TEST_V1', comparisons=total, expectedFaults=faults,
                  exactReadBounds=True, allByteBitMutationsDetected=True, preservesNZCV=True,
                  registerAliasingVerified=True, rows=rows, gameCodeExecuted=False,
                  fexFullRuntimeExecuted=False, androidDeviceTested=False, fpsGainMeasured=False)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'rows'}))


if __name__ == '__main__':
    main()

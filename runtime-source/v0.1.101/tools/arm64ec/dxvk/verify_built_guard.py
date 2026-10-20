#!/usr/bin/env python3
"""Replay the empty-extent guard in the hash-pinned, rebuilt x86/ARM64EC DLLs.

Starts after rectangle validation, with valid modeled rectangle endpoints.
Stops before resource cleanup or nonempty copy handling. This verifies the
actual compiled guard and D3D_OK selection, not Wine/FEX/GPU execution.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys

import unicorn
from unicorn import Uc, UC_ARCH_X86, UC_ARCH_ARM64, UC_MODE_32, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import x86_const as x86, arm64_const as arm

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pe_dependencies import PeImage

HASHES = {
    'syswow64/d3d9.dll': 'd1069ea69417e098f8ef2259067cb5ea18372ed5dc8a7a8edb31ef0fd9dd30ac',
    'system32/d3d9.dll': 'c9b43f9da2f95f8043bdf9bc0f0368389b75a9560b75f74fec78cec8aa1157a9',
}


def replay(data, architecture, src, dst):
    pe = PeImage(data)
    machine = Uc(UC_ARCH_X86, UC_MODE_32) if architecture == 'x86' else Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    end = max(rva + size for _, rva, size, _ in pe.sections)
    machine.mem_map(pe.image_base, (end + 4095) & ~4095)
    for _, rva, size, offset in pe.sections:
        machine.mem_write(pe.image_base + rva, data[offset:offset + size])
    stack = 0x50010000
    machine.mem_map(stack - 0x10000, 0x20000)

    def slot(offset, value):
        machine.mem_write(stack + offset, struct.pack('<I', value))

    if architecture == 'x86':
        # Registers/stack at the first extent subtraction, RVA 0x113ab.
        for reg, value in [(x86.UC_X86_REG_ESP, stack), (x86.UC_X86_REG_EAX, src[0]),
                           (x86.UC_X86_REG_ECX, src[1]), (x86.UC_X86_REG_EBX, src[2]),
                           (x86.UC_X86_REG_EDX, dst[0]), (x86.UC_X86_REG_EBP, dst[3])]:
            machine.reg_write(reg, value)
        slot(0x18, src[3]); slot(0x40, dst[2]); slot(0x24, dst[1])
        start, early, nonempty = 0x100113ab, 0x10011116, 0x100113f7
        result_reg = x86.UC_X86_REG_EBP
    else:
        machine.reg_write(arm.UC_ARM64_REG_SP, stack)
        machine.reg_write(arm.UC_ARM64_REG_W6, src[1])
        machine.reg_write(arm.UC_ARM64_REG_W19, dst[0])
        machine.reg_write(arm.UC_ARM64_REG_W26, dst[1])
        slot(0x3c, src[0]); slot(0x4c, src[2]); slot(0x64, src[3])
        slot(0x60, dst[2]); slot(0x5c, dst[3])
        start, early, nonempty = 0x1800149a0, 0x180014c94, 0x1800149d4
        result_reg = arm.UC_ARM64_REG_W21
    reached = []

    def stop(uc, address, size, unused):
        if address in (early, nonempty):
            reached.append(address)
            uc.emu_stop()

    machine.hook_add(UC_HOOK_CODE, stop)
    machine.emu_start(start, 0, count=100)
    assert len(reached) == 1, reached
    empty = src[0] == src[2] or src[1] == src[3] or dst[0] == dst[2] or dst[1] == dst[3]
    assert reached[0] == (early if empty else nonempty)
    if empty:
        assert machine.reg_read(result_reg) == 0, 'D3D_OK was not selected'
    return {'source': src, 'destination': dst, 'outcome': 'D3D_OK_NO_COPY' if empty else 'CONTINUE_NONEMPTY'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--built', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    full = [0, 0, 1920, 1080]
    cases = [([0, 0, 0, 0], [0, 0, 0, 0]), ([0, 0, 0, 1080], full),
             ([0, 0, 1920, 0], full), (full, [0, 0, 0, 1080]),
             (full, [0, 0, 1920, 0]), ([30, 40, 30, 400], full),
             (full, [30, 40, 900, 40]), ([0, 0, 1, 1], [0, 0, 1, 1]),
             ([0, 0, 800, 600], full), (full, full),
             ([10, 20, 810, 620], [30, 40, 1950, 1120]),
             ([0, 0, 3120, 1440], full)]
    result = {'scope': __doc__, 'unicorn': unicorn.__version__, 'architectures': {}, 'device_tested': False}
    for name, architecture in [('syswow64/d3d9.dll', 'x86'), ('system32/d3d9.dll', 'ARM64EC')]:
        data = (args.built / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == HASHES[name], 'Offsets only apply to pinned DLLs'
        result['architectures'][architecture] = {'sha256': HASHES[name],
            'cases': [replay(data, architecture, src, dst) for src, dst in cases]}
    result['passed_cases'] = len(cases) * 2
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f"PASS: {result['passed_cases']} compiled DLL guard cases")


if __name__ == '__main__':
    main()

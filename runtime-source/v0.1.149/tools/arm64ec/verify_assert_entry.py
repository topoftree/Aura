#!/usr/bin/env python3
"""Assemble the production ARM64 entry and execute its register-save instructions.

Requires Unicorn 2.1.4. The C collector, Android loader and GPU are not modeled.
No game executable or driver bytecode is required by this check.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import struct
import subprocess
import tempfile

import unicorn
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as regs

ROOT = Path(__file__).resolve().parents[2]


def verify(ndk):
    tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    source = ROOT / 'engine/src/main/cpp/aura_native_trace/assert_entry_arm64.S'
    with tempfile.TemporaryDirectory(prefix='aura-assert-arm64-') as temp:
        directory = Path(temp)
        stub = directory / 'stub.S'
        stub.write_text('.text\n.global aura_trace_assert_entry\n'
                        '.hidden aura_trace_assert_entry\naura_trace_assert_entry:\n brk #1\n')
        elf, raw = directory / 'entry.elf', directory / 'entry.bin'
        subprocess.run([str(tools / 'clang'), '--target=aarch64-linux-android34',
            '-nostdlib', '-Wl,-Ttext=0x100000,-e,__assert2,--build-id=none',
            str(source), str(stub), '-o', str(elf)], check=True)
        subprocess.run([str(tools / 'llvm-objcopy'), '--dump-section', '.text=' + str(raw), str(elf)], check=True)
        symbols = subprocess.check_output([str(tools / 'llvm-nm'), str(elf)], text=True)
        addresses = {line.split()[-1]: int(line.split()[0], 16) for line in symbols.splitlines()
                     if len(line.split()) == 3 and line.split()[-1] in ('__assert2', 'aura_trace_assert_entry')}
        code = raw.read_bytes()
    randomizer = random.Random(190)
    for case in range(16):
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        machine.mem_map(0x100000, 0x1000)
        machine.mem_write(0x100000, code)
        machine.mem_map(0x50000000, 0x20000)
        original_sp = 0x50010000 - case * 16
        values = [randomizer.getrandbits(64) for _ in range(31)]
        # Include unsigned underflow and legal coordinate edge values.
        values[8] = (0, 1919, 16383, 16384, 0xffffffff)[case % 5]
        values[10] = (0xffffffff, 1079, 0, 16384)[case % 4]
        for i, value in enumerate(values):
            machine.reg_write(getattr(regs, 'UC_ARM64_REG_X' + str(i)), value)
        machine.reg_write(regs.UC_ARM64_REG_SP, original_sp)
        visited = []

        def stop_at_c(uc, address, size, opaque):
            if address == addresses['aura_trace_assert_entry']:
                visited.append(address)
                uc.emu_stop()

        machine.hook_add(UC_HOOK_CODE, stop_at_c)
        machine.emu_start(addresses['__assert2'], 0, count=100)
        assert visited == [addresses['aura_trace_assert_entry']]
        frame = machine.reg_read(regs.UC_ARM64_REG_X0)
        assert frame == original_sp - 256 and frame % 16 == 0
        saved = struct.unpack('<32Q', machine.mem_read(frame, 256))
        assert list(saved) == values + [original_sp], (case, saved)
        assert machine.reg_read(regs.UC_ARM64_REG_X18) == values[18], 'Android platform register changed'
    return {'result': 'PASS', 'cases': 16, 'gpr_and_sp_values_checked': 512,
            'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'assembled_text_sha256': hashlib.sha256(code).hexdigest(),
            'unicorn': unicorn.__version__, 'stack_aligned': True,
            'android_loader_or_gpu_executed': False,
            'limits': ['Stops at the C collector entry; native delegation has separate host tests.',
                       'x16/x17 can already be clobbered by the platform PLT before this entry.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.ndk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))

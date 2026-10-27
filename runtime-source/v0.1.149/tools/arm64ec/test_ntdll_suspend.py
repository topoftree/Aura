#!/usr/bin/env python3
"""Compare actual Bionic SIGUSR1 instructions with the shipped 11.0-2 contract.

Unicorn executes the real handler. Server select, TEB access and context calls
are mocked; this does not execute Android signals, wineserver, FEX or Steam.
The stripped original's local addresses are tied to its complete SHA-256 and
were resolved from the SIGUSR1 sigaction registration and its call targets.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
    UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X5, UC_ARM64_REG_SP,
    UC_ARM64_REG_LR, UC_ARM64_REG_PC)

ORIGINAL_SHA = '7d6b493dbe50fb061f08d513f3827162c383a76840f3de3f0c607b5bd8af93ea'
REGRESSED_SHA = '9b19ddd84e16f9cb1e9d1a6193ef05002c74b166c3643f03ce659d729278db97'
ORIGINAL_LOCALS = {'usr1_handler': 0xe3e70, 'server_select': 0xdf618, 'wait_suspend': 0xfc56c}
BASE, TEB, CPU, DOORBELL, SIGCONTEXT = 0x10000000, 0x20000000, 0x20010000, 0x20011000, 0x20012000
STACK, RETURN = 0x30000000, 0x40000000
TIMEOUT, SUSPENDED = 0x102, 0x40000001
REGS = [UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
        UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X5]


def run(elf, original, simulation, callback, server_status, area=True, doorbell=True):
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    loads = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    end = max(s['p_vaddr'] + s['p_memsz'] for s in loads)
    uc.mem_map(BASE, (end + 4095) & ~4095)
    for seg in loads:
        uc.mem_write(BASE + seg['p_vaddr'], seg.data())
    for addr, size in [(TEB, 0x20000), (STACK, 0x10000), (RETURN, 0x10000)]:
        uc.mem_map(addr, size)
    symbols = {}
    for name in ('.dynsym', '.symtab'):
        section = elf.get_section_by_name(name)
        if section:
            symbols.update({s.name: s['st_value'] for s in section.iter_symbols()
                            if s['st_shndx'] != 'SHN_UNDEF'})
    if original:
        symbols.update(ORIGINAL_LOCALS)
    write = lambda addr, value, fmt='<Q': uc.mem_write(addr, struct.pack(fmt, value))
    read = lambda addr, fmt='<I': struct.unpack(fmt, uc.mem_read(addr, struct.calcsize(fmt)))[0]
    external = {}
    for section in elf.iter_sections():
        if section['sh_type'] != 'SHT_RELA':
            continue
        dynsym = elf.get_section(section['sh_link'])
        for rel in section.iter_relocations():
            kind, dest = rel['r_info_type'], BASE + rel['r_offset']
            if kind == 1027:
                write(dest, BASE + rel['r_addend'])
            elif kind in (1025, 1026):
                symbol = dynsym.get_symbol(rel['r_info_sym'])
                if symbol['st_shndx'] != 'SHN_UNDEF':
                    write(dest, BASE + symbol['st_value'] + rel['r_addend'])
                else:
                    target = RETURN + 16 * (len(external) + 1)
                    assert target < RETURN + 0x10000
                    write(dest, target)
                    external[target] = symbol.name
            else:
                raise ValueError('Unhandled ELF relocation: ' + str(kind))
    # Wine 11.0/11.0-2 ARM64 TEB and Bionic ucontext layouts are identical.
    write(TEB + 0x1788, CPU if area else 0)
    write(TEB + 0x378, STACK + 0xe000)  # syscall_frame
    write(TEB + 0x3a8, STACK)           # kernel_stack
    write(CPU, int(simulation), '<B')
    write(CPU + 1, int(callback), '<B')
    write(CPU + 0x20, DOORBELL if doorbell else 0)
    write(SIGCONTEXT + 0x1b0, STACK + 0x8000)  # interrupted native syscall SP
    if 'main_image_info' in symbols:
        write(BASE + symbols['main_image_info'] + 0x30, 0x8664, '<H')
    mocked = ['NtCurrentTeb', 'server_select', 'wait_suspend', 'NtGetContextThread', 'NtSetContextThread']
    hooks = {BASE + symbols[name]: name for name in mocked}
    calls = []
    observed = dict(in_simulation=simulation, in_callback=callback, cpu_area=area,
                    doorbell_pointer=doorbell, server_status=hex(server_status))

    def on_code(cpu, pc, size, _):
        if pc == RETURN:
            observed['returned'] = True
            cpu.emu_stop()
            return
        if pc in external:
            raise AssertionError('Unexpected native dependency: ' + external[pc])
        name = hooks.get(pc)
        if not name:
            return
        assert cpu.reg_read(UC_ARM64_REG_SP) % 16 == 0
        args = [cpu.reg_read(reg) for reg in REGS]
        result = 0
        if name == 'NtCurrentTeb':
            result = TEB
        elif name == 'server_select':
            assert args == [0, 0, 6, 0, 0, 0], args
            # The server exchange must happen before setting a suspend request.
            assert read(DOORBELL) == read(TEB + 0x2f0) == 0
            calls.append(name)
            result = server_status
        else:
            if name in ('NtGetContextThread', 'NtSetContextThread'):
                assert args[0] == 0xfffffffffffffffe
            calls.append(name)
        cpu.reg_write(UC_ARM64_REG_X0, result)
        cpu.reg_write(UC_ARM64_REG_PC, cpu.reg_read(UC_ARM64_REG_LR))

    uc.hook_add(UC_HOOK_CODE, on_code)
    for reg, value in [(UC_ARM64_REG_X0, 10), (UC_ARM64_REG_X1, 0), (UC_ARM64_REG_X2, SIGCONTEXT),
                       (UC_ARM64_REG_SP, STACK + 0xf000), (UC_ARM64_REG_LR, RETURN)]:
        uc.reg_write(reg, value)
    uc.emu_start(BASE + symbols['usr1_handler'], 0, count=10000)
    assert observed.get('returned'), 'SIGUSR1 handler did not return'
    observed.update(calls=calls, doorbell=hex(read(DOORBELL)), pending=read(TEB + 0x2f0))
    if area and doorbell and (simulation or callback):
        expected_doorbell = 0xffffffff if server_status == SUSPENDED else 0
        compatible = calls == ['server_select'] and read(DOORBELL) == expected_doorbell
        compatible &= read(TEB + 0x2f0) == int(server_status == SUSPENDED)
    else:
        compatible = calls == ['NtGetContextThread', 'wait_suspend', 'NtSetContextThread']
        compatible &= read(DOORBELL) == read(TEB + 0x2f0) == 0
    observed['matches_shipped_contract'] = bool(compatible)
    return observed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('original', 'regressed', 'candidate'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    results = {}
    for label, path in vars(args).items():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if label == 'original':
            assert digest == ORIGINAL_SHA, 'Original local addresses require the exact shipped library'
        elif label == 'regressed':
            assert digest == REGRESSED_SHA, 'Negative control must be the released 0.1.68 library'
        with path.open('rb') as stream:
            elf = ELFFile(stream)
            assert elf['e_machine'] == 'EM_AARCH64'
            cooperative = [run(elf, label == 'original', sim, cb, status)
                           for sim, cb in [(True, False), (False, True), (True, True)]
                           for status in (TIMEOUT, SUSPENDED)]
            normal = [run(elf, label == 'original', False, False, TIMEOUT),
                      run(elf, label == 'original', False, True, TIMEOUT, area=False),
                      run(elf, label == 'original', True, False, TIMEOUT, doorbell=False)]
        assert all(c['matches_shipped_contract'] for c in normal), normal
        assert all(c['matches_shipped_contract'] == (label != 'regressed') for c in cooperative), cooperative
        results[label] = dict(sha256=digest, cases=cooperative + normal)
    print(json.dumps(dict(passed=True, instruction_cases=27, negative_control_mismatches=6,
        binaries=results, mock_boundaries=['server_select', 'TEB access', 'context access', 'wait_suspend'],
        real_server_apcs_executed=False, android_device_tested=False, steam_startup_verified=False), indent=2))


if __name__ == '__main__':
    main()

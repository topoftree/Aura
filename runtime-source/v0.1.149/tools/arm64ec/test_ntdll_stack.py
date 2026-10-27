#!/usr/bin/env python3
"""Execute compiled Bionic init_syscall_frame with synthetic memory in Unicorn.

The real ARM64 function sets the initial context and copies it onto the guest
stack. libc, Wine server suspension and context application are mocked: this is
not an Android thread, FEX transition, DLL initialization or game launch test.
Requires pyelftools and Unicorn. Inputs must retain DWARF and local symbols.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1,
    UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_LR,
    UC_ARM64_REG_SP, UC_ARM64_REG_PC)

BASE, TEB, FRAME, CPU, WOWTEB = 0x10000000, 0x20000000, 0x20010000, 0x20020000, 0x20006000
STACK, TOP, HOST_STACK, RETURN = 0x100000, 0x120000, 0x30000000, 0x40000000
ENTRY, ARG, START, THUNK = 0x111000, 0x1234, 0x112000, 0x113000


def layout(elf):
    wanted = {'_TEB', '_NT_TIB', '_ARM64_NT_CONTEXT', '_SECTION_IMAGE_INFORMATION',
              'ntdll_thread_data', 'syscall_frame'}
    result = {}
    for cu in elf.get_dwarf_info().iter_CUs():
        for die in cu.iter_DIEs():
            attr = die.attributes.get('DW_AT_name')
            name = attr.value.decode() if attr else ''
            if die.tag != 'DW_TAG_structure_type' or name not in wanted or name in result:
                continue
            if 'DW_AT_byte_size' not in die.attributes:
                continue
            fields = {'size': die.attributes['DW_AT_byte_size'].value}
            for child in die.iter_children():
                attrs = child.attributes
                if 'DW_AT_name' in attrs and 'DW_AT_data_member_location' in attrs:
                    fields[attrs['DW_AT_name'].value.decode()] = attrs['DW_AT_data_member_location'].value
            result[name] = fields
        if result.keys() == wanted:
            return result
    raise ValueError('Required native DWARF structure layouts are missing')


def run(elf, fields, machine, suspended, redirect=False):
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    segments = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    end = max(s['p_vaddr'] + s['p_memsz'] for s in segments)
    uc.mem_map(BASE, (end + 4095) & ~4095)
    for seg in segments:
        uc.mem_write(BASE + seg['p_vaddr'], seg.data())
    for addr, size in [(TEB, 0x30000), (STACK, TOP-STACK), (HOST_STACK, 0x10000), (RETURN, 16384)]:
        uc.mem_map(addr, size)

    symbols = {s.name: s for s in elf.get_section_by_name('.symtab').iter_symbols()}
    addr = lambda name: BASE + symbols[name]['st_value']
    write = lambda address, value, fmt='<Q': uc.mem_write(address, struct.pack(fmt, value))
    read = lambda address, fmt='<Q': struct.unpack(fmt, uc.mem_read(address, struct.calcsize(fmt)))[0]
    hooks = {}
    for section in elf.iter_sections():
        if section['sh_type'] != 'SHT_RELA':
            continue
        dynsym = elf.get_section(section['sh_link'])
        for rel in section.iter_relocations():
            kind, dest = rel['r_info_type'], BASE + rel['r_offset']
            if kind == 1027:  # R_AARCH64_RELATIVE
                write(dest, BASE + rel['r_addend'])
            elif kind in (1025, 1026):  # GLOB_DAT / JUMP_SLOT
                symbol = dynsym.get_symbol(rel['r_info_sym'])
                if symbol['st_shndx'] != 'SHN_UNDEF':
                    write(dest, BASE + symbol['st_value'] + rel['r_addend'])
                else:
                    target = RETURN + 16 * (len(hooks) + 1)
                    assert target < RETURN + 16384
                    write(dest, target)
                    hooks[target] = symbol.name
            else:
                raise ValueError('Unhandled ELF relocation: ' + str(kind))

    context = fields['_ARM64_NT_CONTEXT']
    frame = fields['syscall_frame']
    write(TEB + fields['_TEB']['Tib'] + fields['_NT_TIB']['StackBase'], TOP)
    write(TEB + fields['_TEB']['GdiTebBatch'] + fields['ntdll_thread_data']['syscall_frame'], FRAME)
    write(TEB + fields['_TEB']['WowTebOffset'], WOWTEB - TEB, '<I')
    write(WOWTEB + 4, TOP, '<I')  # NT_TIB32.StackBase
    write(addr('main_image_info') + fields['_SECTION_IMAGE_INFORMATION']['Machine'], machine, '<H')
    write(addr('pRtlUserThreadStart'), START)
    write(addr('pLdrInitializeThunk'), THUNK)
    write(addr('pLdrSystemDllInitBlock'), CPU + 0x1000)
    write(CPU + 0x1000 + 0x38, START)  # SYSTEM_DLL_INIT_BLOCK.pRtlUserThreadStart
    hooks.update({addr(n): n for n in ['wait_suspend', 'get_cpu_area', 'fpux_to_fpu', 'signal_set_full_context']})
    observed = {'machine': hex(machine), 'suspended': suspended, 'redirected_context': redirect}

    def on_code(cpu, pc, size, _):
        if pc == RETURN:
            observed['returned'] = True
            cpu.emu_stop()
            return
        if pc not in hooks:
            return
        # Every AArch64 call boundary, including mocked calls, must stay aligned.
        assert cpu.reg_read(UC_ARM64_REG_SP) % 16 == 0
        name = hooks[pc]
        x0, x1, x2 = [cpu.reg_read(reg) for reg in [UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2]]
        result = 0
        if name == 'memset':
            cpu.mem_write(x0, bytes([x1 & 255]) * x2); result = x0
        elif name == 'memcpy':
            cpu.mem_write(x0, bytes(cpu.mem_read(x1, x2))); result = x0
        elif name == 'get_cpu_area':
            result = CPU if machine in (0x14c, 0x1c4) and x0 == machine else 0
        elif name == 'wait_suspend':
            observed['suspend_sp'] = hex(read(x0 + context['Sp']))
            if redirect:
                write(x0 + context['Sp'], TOP - 0x100)
                write(x0 + context['Pc'], START + 0x80)
        elif name == 'signal_set_full_context':
            observed.update(context_address=hex(x0), initial_sp=hex(read(x0 + context['Sp'])),
                            initial_pc=hex(read(x0 + context['Pc'])))
            observed['copied_context_flags'] = hex(read(x0, '<I'))
        elif name not in ('pthread_sigmask', 'fpux_to_fpu'):
            raise AssertionError('Unexpected native dependency: ' + name)
        cpu.reg_write(UC_ARM64_REG_X0, result)
        cpu.reg_write(UC_ARM64_REG_PC, cpu.reg_read(UC_ARM64_REG_LR))

    uc.hook_add(UC_HOOK_CODE, on_code)
    for reg, value in [(UC_ARM64_REG_X0, ENTRY), (UC_ARM64_REG_X1, ARG),
                       (UC_ARM64_REG_X2, int(suspended)), (UC_ARM64_REG_X3, TEB),
                       (UC_ARM64_REG_SP, HOST_STACK + 0xf000), (UC_ARM64_REG_LR, RETURN)]:
        uc.reg_write(reg, value)
    uc.emu_start(addr('init_syscall_frame'), 0, count=10000)
    assert observed.get('returned'), 'Native function did not return'
    ctx = int(observed['context_address'], 16)
    assert read(FRAME + frame['sp']) == ctx
    assert read(FRAME + frame['pc']) == THUNK
    assert read(FRAME + frame['x']) == ctx
    assert read(FRAME + frame['x'] + 18 * 8) == TEB
    assert observed['copied_context_flags'] == '0x400007'
    assert ctx == (int(observed['initial_sp'], 16) & ~15) - context['size']
    assert STACK <= ctx < TOP and int(observed['initial_sp'], 16) % 16 == 0
    if machine == 0x14c:
        assert read(CPU + 0xc4, '<I') == TOP - 16
    elif machine == 0x1c4:
        assert read(CPU + 0x38, '<I') == TOP
    return observed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--patched', type=Path, required=True)
    args = parser.parse_args()
    results = {}
    for label, path in vars(args).items():
        with path.open('rb') as stream:
            elf = ELFFile(stream)
            assert elf['e_machine'] == 'EM_AARCH64'
            fields = layout(elf)
            cases = [run(elf, fields, m, s) for m in (0x8664, 0xaa64, 0x14c, 0x1c4) for s in (False, True)]
            cases.append(run(elf, fields, 0x8664, True, True))
        for case in cases:
            reserve = 0x30 if label == 'patched' and case['machine'] == '0x8664' else 0
            expected = TOP - (0x100 if case['redirected_context'] else reserve)
            assert int(case['initial_sp'], 16) == expected, case
            assert case['initial_pc'] == hex(START + (0x80 if case['redirected_context'] else 0))
            if case['suspended']:
                assert int(case['suspend_sp'], 16) == TOP - reserve
        results[label] = dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), cases=cases)
    print(json.dumps(dict(passed=True, native_instruction_cases=18, binaries=results,
        mock_boundaries=['libc', 'get_cpu_area', 'wait_suspend', 'signal_set_full_context', 'fpux_to_fpu'],
        context_copy_and_frame_setup_executed=True, android_device_tested=False,
        full_context_roundtrip_tested=False, game_startup_verified=False), indent=2))


if __name__ == '__main__':
    main()

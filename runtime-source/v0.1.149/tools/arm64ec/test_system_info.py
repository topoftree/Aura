#!/usr/bin/env python3
"""Replay actual ARM64/EC kernelbase instructions with synthetic NT query replies.

Unicorn executes the packaged API bodies, including GetSystemInfo and its
structure conversion. Only imported NT calls are mocked. This is not a Wine
loader, .NET, SMAPI, Android, or game execution test.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64_const import ARM64_OP_REG, ARM64_OP_MEM
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                                UC_ARM64_REG_X18, UC_ARM64_REG_SP, UC_ARM64_REG_LR, UC_ARM64_REG_PC)
from pe_dependencies import PeImage
from replay_ubisoft_injection_stack import export_rva, map_image


def body(pe, name):
    rva = export_rva(pe, name)
    offset = pe.offset(rva, 16)
    if pe.info["machine"] == "0x8664":
        # Resolve this pinned Wine compiler's x64-to-EC entry thunk, not its
        # bytes as ARM64 instructions. No private fixed function RVA is used.
        assert pe.data[offset:offset + 10] == bytes.fromhex("488bc448895820555de9")
        rva += 14 + struct.unpack_from("<i", pe.data, offset + 10)[0]
    return pe.image_base + rva


def wow64_address(pe):
    start = body(pe, "GetNativeSystemInfo")
    offset = pe.offset(start - pe.image_base, 256)
    decoder = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    decoder.detail = True
    pages, candidates = {}, set()
    for instruction in decoder.disasm(pe.data[offset:offset + 256], start):
        operands = instruction.operands
        if instruction.mnemonic == "adrp":
            pages[operands[0].reg] = operands[1].imm
        elif instruction.mnemonic == "ldr" and operands[0].type == ARM64_OP_REG and operands[1].type == ARM64_OP_MEM:
            memory = operands[1].mem
            if instruction.reg_name(operands[0].reg).startswith("w") and memory.base in pages:
                candidates.add(pages[memory.base] + memory.disp)
        if instruction.mnemonic == "ret":
            break
    candidates = {address for address in candidates if any(
        name == ".data" and pe.image_base + rva <= address < pe.image_base + rva + size
        for name, rva, size, _ in pe.sections)}
    assert len(candidates) == 1, "Cannot uniquely resolve is_wow64 from the real API body"
    return candidates.pop()


def replay(pe, guest, host, native_machine, wow64=False, failure=None, function="GetNativeSystemInfo"):
    cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    map_image(cpu, pe, pe.image_base)
    stack, data, imports = 0x100000, 0x200000, 0x300000
    for address in (stack, data, imports):
        cpu.mem_map(address, 65536)
    stop, info, teb = imports, data + 256, data + 4096
    cpu.mem_write(info - 16, b"\xcc" * 80)
    cpu.mem_write(wow64_address(pe), struct.pack("<I", int(wow64)))
    hooks = {}
    auxiliary_iat = 0
    if pe.info["machine"] == "0x8664":
        config, _ = pe.directory(10)
        metadata, = struct.unpack_from("<Q", pe.data, pe.offset(config + 200, 8))
        auxiliary_iat, = struct.unpack_from("<I", pe.data, pe.offset(metadata - pe.image_base + 44, 4))
        assert auxiliary_iat
    iat_base, _ = pe.directory(12)
    rva, size = pe.directory(1)
    for index in range(size // 20):
        fields = struct.unpack_from("<5I", pe.data, pe.offset(rva + index * 20, 20))
        if not any(fields):
            break
        table, _, _, name, iat = fields
        assert pe.string(name).lower() == "ntdll.dll"
        for ordinal, symbol in enumerate(pe.thunks(table or iat)):
            pointer = imports + 16 * (len(hooks) + 1)
            assert pointer < imports + 65536
            cpu.mem_write(pe.image_base + iat + ordinal * 8, struct.pack("<Q", pointer))
            if auxiliary_iat:
                # Model the loader binding the corresponding ARM64EC import.
                cpu.mem_write(pe.image_base + auxiliary_iat + iat + ordinal * 8 - iat_base,
                              struct.pack("<Q", pointer))
            hooks[pointer] = symbol
    calls, returned = [], False

    def step(uc, pc, size, _):
        nonlocal returned
        if pc == stop:
            returned = True
            uc.emu_stop()
            return
        if pc not in hooks:
            return
        name = hooks[pc]
        a, b, c = (uc.reg_read(register) for register in (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2))
        result = 0
        if name == "RtlWow64GetProcessMachines":
            assert a == 0
            uc.mem_write(b, struct.pack("<H", 0x14c if wow64 else (0x8664 if guest == 9 else 0)))
            uc.mem_write(c, struct.pack("<H", native_machine))
        elif name in ("NtQuerySystemInformation", "RtlGetNativeSystemInformation"):
            calls.append((name, a))
            assert a in (0, 1) and c == (64 if a == 0 else 12)
            if failure == a:
                result = 0xc0000005
            elif a == 0:
                uc.mem_write(b, struct.pack("<6I4QB7x", 0, 156250, 4096, 1000, 1, 1001,
                                           65536, 65536, 0x7fffffff0000, 255, 8))
            else:
                arch = host if name == "RtlGetNativeSystemInformation" else guest
                uc.mem_write(b, struct.pack("<4HI", arch, 6, 0x1234, 8, 0))
        elif name == "RtlNtStatusToDosError":
            assert a == 0xc0000005
            result = 998
        else:
            raise AssertionError("Unexpected imported call " + name)
        uc.reg_write(UC_ARM64_REG_X0, result)
        uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))

    cpu.reg_write(UC_ARM64_REG_SP, stack + 65536 - 16)
    cpu.reg_write(UC_ARM64_REG_LR, stop)
    cpu.reg_write(UC_ARM64_REG_X18, teb)
    cpu.reg_write(UC_ARM64_REG_X0, info)
    cpu.hook_add(UC_HOOK_CODE, step)
    cpu.emu_start(body(pe, function), 0, count=2000)
    assert returned, "Instruction budget exhausted"
    assert bytes(cpu.mem_read(info - 16, 16)) == b"\xcc" * 16
    assert bytes(cpu.mem_read(info + 48, 16)) == b"\xcc" * 16
    result = bytes(cpu.mem_read(info, 48))
    if failure is not None:
        assert result == b"\xcc" * 48, "Failed query unexpectedly published SYSTEM_INFO"
        assert struct.unpack("<I", cpu.mem_read(teb + 0x68, 4))[0] == 998
    else:
        arch, reserved, page, low, high, mask, count, kind, granularity, level, revision = struct.unpack("<HHIQQQIIIHH", result)
        assert (reserved, page, low, high, mask, count, granularity, level, revision) == (0, 4096, 65536, 0x7fffffff0000, 255, 8, 65536, 6, 0x1234)
        # The pre-existing WOW64/ARM path changes only the reported architecture;
        # retain that behavior rather than inventing different processor fields.
        expected_type = {0: 586, 9: 8664, 12: 0}[guest if wow64 and native_machine != 0x8664 else arch]
        assert kind == expected_type
    return dict(architecture=struct.unpack("<H", result[:2])[0] if failure is None else None,
                queries=calls, failedQuery=failure)


def verify(original, baseline, fixed):
    records = []
    cases = [
        ("x64_on_arm64", True, 9, 12, 0xaa64, False, 12, 9),
        ("native_arm64", False, 12, 12, 0xaa64, False, 12, 12),
        ("x64_on_amd64_api_state", True, 9, 9, 0x8664, False, 9, 9),
        ("wow64_arm_host_api_state", False, 0, 12, 0xaa64, True, 9, 9),
        ("wow64_amd64_host_api_state", False, 0, 9, 0x8664, True, 9, 9),
    ]
    for label, path in (("shipped", original), ("rebuilt_baseline", baseline), ("fixed", fixed)):
        pe = PeImage(path.read_bytes())
        for name, ec, guest, host, machine, wow, old, new in cases:
            view = pe.ec_view() if ec else pe
            result = replay(view, guest, host, machine, wow)
            assert result["architecture"] == (new if label == "fixed" else old), (label, name, result)
            records.append(dict(binary=label, case=name, **result))
        # Both early and second-query failures keep the caller's output intact.
        for ec, guest in ((False, 12), (True, 9)):
            view = pe.ec_view() if ec else pe
            for failed in (0, 1):
                result = replay(view, guest, 12, 0xaa64, failure=failed)
                records.append(dict(binary=label, case=("ec_" if ec else "arm64_") + "failure", **result))
            result = replay(view, guest, 12, 0xaa64, function="GetSystemInfo")
            assert result["architecture"] == guest
            records.append(dict(binary=label, case=("ec_" if ec else "arm64_") + "GetSystemInfo", **result))
    return dict(schema="WINE_SYSTEM_INFO_REPLAY_V1", cases=len(records), records=records,
                binaries={k: hashlib.sha256(p.read_bytes()).hexdigest() for k, p in
                          (("shipped", original), ("rebuiltBaseline", baseline), ("fixed", fixed))},
                importedNtCalls="SYNTHETIC_REPLIES", actualApiInstructionsExecuted=True,
                androidGameTested=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original", "baseline", "fixed", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.original, args.baseline, args.fixed)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "records"}))

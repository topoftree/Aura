#!/usr/bin/env python3
"""Execute the linked ARM64 locale initializer with real bundled NLS tables.

Unicorn host checks, not an Android session: libc, file I/O and logging are mocked.
Also compare the existing wait-completion, initial-stack and suspension contracts.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                                UC_ARM64_REG_PC, UC_ARM64_REG_SP, UC_ARM64_REG_LR)
from build_ntdll import sha
import test_ntdll_stack as stack
import test_ntdll_suspend as suspend

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def locale_case(elf, nls, choice, executable="game.exe"):
    cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    base, heap, stop, sp = 0x10000000, 0x20000000, 0x30000000, 0x40010000
    segments = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
    end = max(s["p_vaddr"] + s["p_memsz"] for s in segments)
    cpu.mem_map(base, (end + 4095) & ~4095)
    for segment in segments:
        cpu.mem_write(base + segment["p_vaddr"], segment.data())
    cpu.mem_map(heap, 16 * 1024 * 1024)
    cpu.mem_map(stop, 16384)
    cpu.mem_map(sp - 65536, 65536)
    symbols = {s.name: s["st_value"] for s in elf.get_section_by_name(".symtab").iter_symbols()}
    address = lambda name: base + symbols[name]
    def write(where, value, fmt="<Q"):
        cpu.mem_write(where, struct.pack(fmt, value))
    cursor = heap
    def alloc(data):
        nonlocal cursor
        result = cursor
        cursor += (len(data) + 15) & ~15
        assert cursor < heap + 16 * 1024 * 1024
        cpu.mem_write(result, data)
        return result
    def string(pointer):
        assert pointer
        result = bytearray()
        while True:
            char = cpu.mem_read(pointer + len(result), 1)
            if char == b"\0": return bytes(result)
            result.extend(char)
            assert len(result) < 4096
    hooks = {}
    for section in elf.iter_sections():
        if section["sh_type"] != "SHT_RELA": continue
        dynamic = elf.get_section(section["sh_link"])
        for rel in section.iter_relocations():
            kind, destination = rel["r_info_type"], base + rel["r_offset"]
            if kind == 1027:
                write(destination, base + rel["r_addend"])
            elif kind in (1025, 1026):
                symbol = dynamic.get_symbol(rel["r_info_sym"])
                if symbol["st_shndx"] != "SHN_UNDEF":
                    write(destination, base + symbol["st_value"] + rel["r_addend"])
                else:
                    target = stop + 16 * (len(hooks) + 1)
                    assert target < stop + 16384
                    write(destination, target)
                    hooks[target] = symbol.name
            else: raise AssertionError(kind)
    environment = {b"LC_ALL": b"C.UTF-8"}
    if choice is not None: environment[b"AURA_WINE_LOCALE"] = choice.encode()
    write(address("main_argc"), 2, "<I")
    argv = alloc(struct.pack("<QQ", alloc(b"wine\0"), alloc(executable.encode() + b"\0")))
    write(address("main_argv"), argv)
    hooks[address("read_nls_file")] = "read_nls_file"
    for symbol in elf.get_section_by_name(".symtab").iter_symbols():
        if symbol.name in ("wine_dbg_log", "debugstr_a"):
            hooks[base + symbol["st_value"]] = symbol.name
    calls, returned = [], False
    def step(uc, pc, size, _):
        nonlocal returned
        if pc == stop:
            returned = True
            uc.emu_stop()
            return
        if pc not in hooks: return
        name = hooks[pc]
        a, b, c = [uc.reg_read(reg) for reg in (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2)]
        result = 0
        if name == "setlocale":
            # Android Bionic accepts the empty request but returns C.UTF-8.
            calls.append((a, string(b).decode()))
            result = alloc(b"C\0" if string(b) == b"C" else b"C.UTF-8\0")
        elif name == "getenv":
            value = environment.get(string(a))
            result = alloc(value + b"\0") if value is not None else 0
        elif name == "setenv":
            if c or string(a) not in environment: environment[string(a)] = string(b)
        elif name == "read_nls_file":
            result = alloc(nls[string(a).decode()])
        elif name in ("free", "wine_dbg_log"):
            pass
        elif name == "debugstr_a": result = a
        elif name == "strlen": result = len(string(a))
        elif name == "strdup": result = alloc(string(a) + b"\0")
        elif name in ("strcpy", "strcat"):
            dest = a + (len(string(a)) if name == "strcat" else 0)
            uc.mem_write(dest, string(b) + b"\0"); result = a
        elif name in ("strcmp", "strncmp"):
            x, y = string(a), string(b)
            if name == "strncmp": x, y = x[:c], y[:c]
            result = (x > y) - (x < y)
        elif name in ("strchr", "strrchr", "strstr"):
            haystack = string(a)
            needle = string(b) if name == "strstr" else bytes([b & 255])
            index = haystack.rfind(needle) if name == "strrchr" else haystack.find(needle)
            result = a + index if index >= 0 else 0
        elif name == "strpbrk":
            needles = string(b)
            result = next((a + i for i, char in enumerate(string(a)) if char in needles), 0)
        elif name in ("memcpy", "memmove"):
            uc.mem_write(a, bytes(uc.mem_read(b, c))); result = a
        else: raise AssertionError("Unexpected dependency: " + name)
        uc.reg_write(UC_ARM64_REG_X0, result & ((1 << 64) - 1))
        uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))
    cpu.hook_add(UC_HOOK_CODE, step)
    cpu.reg_write(UC_ARM64_REG_SP, sp)
    cpu.reg_write(UC_ARM64_REG_LR, stop)
    cpu.emu_start(address("init_environment"), 0, count=100000)
    assert returned
    read = lambda n, fmt: struct.unpack(fmt, cpu.mem_read(address(n), struct.calcsize(fmt)))[0]
    expected = 0x804 if choice == "zh-CN" else 0x409
    user_expected = 0x409 if executable == "start_protected_game.exe" else expected
    assert read("system_lcid", "<I") == expected
    assert read("user_lcid", "<I") == read("user_ui_language", "<H") == user_expected
    assert environment[b"LC_ALL"] == b"C.UTF-8" and calls[-1] == (1, "C")
    assert calls[:-1] == [(6, ""), (0, ""), (5, "")]
    return dict(choice=choice, executable=executable, systemLcid=hex(expected), userLcid=hex(user_expected),
                unixLocale="C.UTF-8", numericLocale="C")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ("base", "built", "report"):
        p.add_argument("--" + key, type=Path, required=True)
    a = p.parse_args()
    metadata = json.loads((a.built / "build.json").read_text())
    assert sha(a.built / "ntdll-symbols.so") == metadata["symbolsSha256"]
    manifest = json.loads((ROOT / "engine/src/main/assets/arm64ec/manifest.json").read_text())["files"]
    nls = {}
    for name in ("locale.nls", "l_intl.nls"):
        path = a.base / "source/nls" / name
        assert sha(path) == manifest["opt/wine/share/wine/nls/" + name]["sha256"]
        nls[name] = path.read_bytes()
    spec = importlib.util.spec_from_file_location("completion", ROOT / "tools/diagnostics/wine-wait-completion/verify_binary.py")
    completion = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(completion)
    with (a.built / "ntdll-symbols.so").open("rb") as new, (a.base / "output/ntdll-symbols.so").open("rb") as old:
        after, before = ELFFile(new), ELFFile(old)
        cases = [locale_case(after, nls, choice) for choice in (None, "", "en-US", "zh-CN", "pt-BR", "ru-RU", "ZH-CN", "garbage")]
        cases.append(locale_case(after, nls, "zh-CN", "start_protected_game.exe"))
        assert locale_case(before, nls, "en-US") == locale_case(after, nls, "en-US")
        helpers = [completion.helper(after, w, c, t, s) for w in (False, True) for c in (False, True)
                   for t in (False, True) for s in (0x101, 0x102, 0xc000000b)]
        fields = stack.layout(after)
        stack_cases, suspend_cases = [], []
        for machine, suspended, redirect in [*((m, s, False) for m in (0x8664, 0xaa64, 0x14c, 0x1c4) for s in (False, True)),
                                             (0x8664, True, True)]:
            one = stack.run(after, fields, machine, suspended, redirect)
            assert one == stack.run(before, fields, machine, suspended, redirect)
            stack_cases.append(one)
        for sim, callback, status, area, doorbell in [
            *((sim, cb, status, True, True) for sim, cb in ((True, False), (False, True), (True, True))
              for status in (suspend.TIMEOUT, suspend.SUSPENDED)),
            (False, False, suspend.TIMEOUT, True, True), (False, True, suspend.TIMEOUT, False, True),
            (True, False, suspend.TIMEOUT, True, False)]:
            one = suspend.run(after, False, sim, callback, status, area, doorbell)
            assert one == suspend.run(before, False, sim, callback, status, area, doorbell)
            assert one["matches_shipped_contract"]
            suspend_cases.append(one)
    report = dict(nativeSha256=metadata["nativeSha256"], localeCases=cases, englishMatchesPrevious=True,
                  completionCases=helpers, stackCases=stack_cases, suspensionCases=suspend_cases,
                  actualNlsTables=True, androidDeviceTested=False,
                  mockBoundaries=["libc/Bionic setlocale", "NLS file reads", "logging", "Wine server/context calls"])
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(dict(localeCases=len(cases), completionCases=len(helpers), stackCases=len(stack_cases),
                          suspensionCases=len(suspend_cases), passed=True)))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Offline checks for the Mirror's Edge empty-copy failure and upstream backport.

Uses hash-pinned binary instructions and actual extracted upstream C++ (not a
Python reimplementation). Unicorn/pyelftools are investigation dependencies;
this tool and its inputs are not packaged into the APK. No GPU is executed.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile

import unicorn
from unicorn import Uc, UC_ARCH_ARM64, UC_ARCH_X86, UC_MODE_ARM, UC_MODE_32, UC_HOOK_CODE
from unicorn import arm64_const as arm, x86_const as x86
from elftools.elf.elffile import ELFFile

HERE = Path(__file__).resolve().parent
HASHES = {
    'driver': '0c2ba587e4477b49081a70226f022f6a9a01ebb8528d3beeddc37c1adcbec8f7',
    'd3d9': '627b14c48404aedbc987e5e012285db387460f94ac46251fd93f49e190a90dcf',
    'd3d9_device.cpp': '6cab742e207d10249334555516ed2c075a9205d1da0fb13976e41dc186464350',
    'd3d9_util.h': 'ece606c091f98bf096b768dd095b9678c1c34401850c1be22e0386c952671486',
}


def pinned(path, key):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != HASHES[key]:
        raise ValueError(f'Unexpected {key}; binary offsets/source extraction do not apply')
    return data


def stop_at(machine, addresses):
    visited = []

    def stop(uc, address, size, opaque):
        if address in addresses:
            visited.append(address)
            uc.emu_stop()

    machine.hook_add(UC_HOOK_CODE, stop)
    return visited


def replay_driver(data):
    elf = ELFFile(io.BytesIO(data))
    segments = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    end = max(s['p_vaddr'] + s['p_memsz'] for s in segments)
    rows = []
    # Zero width and zero height reach separate original fatal checks. Positive
    # controls stop before writing any GPU commands, including the 14-bit edge.
    cases = [(0, 0, 'assert-x'), (0, 1080, 'assert-x'), (1920, 0, 'assert-y'),
             (1, 1, 'emit'), (800, 600, 'emit'), (1920, 1080, 'emit'),
             (3120, 1440, 'emit'), (16384, 16384, 'emit'),
             (16385, 1080, 'assert-x')]
    for width, height, expected in cases:
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        machine.mem_map(0, (end + 4095) & ~4095)
        for segment in segments:
            machine.mem_write(segment['p_vaddr'], segment.data())
        machine.mem_map(0x50000000, 0x20000)
        machine.reg_write(arm.UC_ARM64_REG_SP, 0x50010000)
        machine.reg_write(arm.UC_ARM64_REG_X2, 0)  # packed destination x/y
        machine.reg_write(arm.UC_ARM64_REG_X3, 0)  # packed source x/y
        machine.reg_write(arm.UC_ARM64_REG_X4, width | (height << 32))
        visits = stop_at(machine, {0xa2f460, 0x70a6fc})  # assert PLT / first emit
        machine.emu_start(0x70aeec, 0, count=200)
        assert len(visits) == 1, (width, height, visits)
        lr = machine.reg_read(arm.UC_ARM64_REG_LR)
        kind = 'emit' if visits[0] == 0x70a6fc else {
            0x70b060: 'assert-x', 0x70b080: 'assert-y',
        }.get(lr, 'unexpected-assert')
        assert kind == expected, (width, height, kind)
        rows.append({'extent': [width, height], 'route': kind,
                     'lr': hex(lr), 'w8': hex(machine.reg_read(arm.UC_ARM64_REG_X8) & 0xffffffff)})
    assert rows[0]['w8'] == '0xffffffff'
    return rows


def decode_capture(path):
    lines = path.read_text().splitlines()
    # This is intentionally specific to the matched call site; other driver
    # versions or assert callers must not be decoded with these register roles.
    callers = [line for line in lines if 'kind=CALLER_RETURN;' in line
               and 'module=libaura_0c2ba587e4477b49081a7022.so;' in line
               and 'offset=0x70b060' in line]
    assert len(callers) == 1, 'Expected one matched Turnip copy-coordinate event'
    key = re.search(r'event=(\d+); pid=(\d+);', callers[0]).groups()
    tag = f'event={key[0]}; pid={key[1]};'
    registers = {}
    for line in lines:
        if line.startswith('AURA_NATIVE_ASSERT_REGS_V1 ') and tag in line:
            match = re.search(r'first=(\d+); values=(.*)$', line)
            first = int(match[1])
            for index, value in enumerate(match[2].split(','), first):
                assert index not in registers, 'Duplicate register'
                registers[index] = int(value, 16)
    assert set(registers) == set(range(32)), 'Incomplete register snapshot'
    packed_extent = registers[21]
    assert registers[22] == (packed_extent + 0xffffffff) & ((1 << 64) - 1)
    packed_dst = (registers[8] - registers[22]) & ((1 << 64) - 1)
    x = registers[8] & 0xffffffff
    assert x & 0xffffc000, 'Recorded x does not fail the original assertion'
    return {'caller_return': '0x70b060', 'x_unsigned': x,
            'x_signed': x if x < 0x80000000 else x - (1 << 32),
            'extent': [packed_extent & 0xffffffff, packed_extent >> 32],
            'destination': [packed_dst & 0xffffffff, packed_dst >> 32],
            'guest_log_sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def replay_d3d9(data):
    # Itanium RTTI identifies the D3D9DeviceEx vtable. COM slot 34 is StretchRect.
    assert data.startswith(b'N4dxvk12D3D9DeviceExE\0', 0x226330)
    assert struct.unpack_from('<I', data, 0x223a64)[0] == 0x10226324
    assert struct.unpack_from('<I', data, 0x223a68 + 34 * 4)[0] == 0x10010ea0
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    count, opt_size = struct.unpack_from('<H', data, pe + 6)[0], struct.unpack_from('<H', data, pe + 20)[0]
    section_table = pe + 24 + opt_size
    sections = [struct.unpack_from('<8sIIII', data, section_table + i * 40) for i in range(count)]
    cases = [((0, 0, 0, 0), 'copy'), ((0, 0, 1, 1), 'copy'),
             ((0, 0, 800, 600), 'copy'), ((0, 0, 1920, 1080), 'copy'),
             ((0, 0, 3120, 1440), 'copy'), ((0, 0, 0, 600), 'copy'),
             ((0, 0, 800, 0), 'copy'), ((10, 20, 10, 20), 'copy'),
             ((-1, 0, 100, 100), 'invalid'), ((10, 0, 9, 100), 'invalid'),
             ((0, 0, 4097, 100), 'invalid')]
    rows = []
    for rect, expected in cases:
        machine = Uc(UC_ARCH_X86, UC_MODE_32)
        machine.mem_map(0x10000000, 0x400000)
        for _, _, va, raw_size, raw_offset in sections:
            if raw_size:
                machine.mem_write(0x10000000 + va, data[raw_offset:raw_offset + raw_size])
        machine.mem_map(0x50000000, 0x20000)
        sp, dst_texture, src_texture = 0x50010000, 0x50000000, 0x50001000

        def word(address, value):
            machine.mem_write(address, struct.pack('<I', value & 0xffffffff))

        left, top, right, bottom = rect
        # Start at the real post-rectangle-setup block. Model valid uncompressed
        # render-target metadata, equal sample counts, and an already-held lock.
        values = {0x00: dst_texture, 0x0c: src_texture, 0x24: top, 0x20: left,
                  0x50: right, 0x4c: bottom, 0x60: bottom, 0x34: 4096, 0x64: 4096,
                  0x1c: 4096, 0x14: 4096, 0x10: 21, 0x30: 21, 0x38: 1,
                  0x110: 1, 0x11c: 1, 0x144: 0x50002000, 0x14c: 0x50002000}
        for offset, value in values.items():
            word(sp + offset, value)
        for address in (src_texture, dst_texture):
            word(address + 0x18, 1)  # D3DUSAGE_RENDERTARGET
            word(address + 0x34, 1)  # D3DRTYPE_SURFACE
        machine.reg_write(x86.UC_X86_REG_ESP, sp)
        machine.reg_write(x86.UC_X86_REG_EAX, left & 0xffffffff)
        machine.reg_write(x86.UC_X86_REG_EBP, top & 0xffffffff)
        machine.reg_write(x86.UC_X86_REG_EBX, right & 0xffffffff)
        visits = stop_at(machine, {0x1001156f, 0x10011a89, 0x1001175a, 0x10011628})
        machine.emu_start(0x1001128c, 0, count=500)
        assert len(visits) == 1, (rect, visits)
        route = {0x1001156f: 'invalid', 0x10011a89: 'copy',
                 0x1001175a: 'blit', 0x10011628: 'resolve'}[visits[0]]
        assert route == expected, (rect, route, expected)
        extent = [struct.unpack('<I', machine.mem_read(sp + off, 4))[0] for off in (0x48, 0x44)]
        rows.append({'rectangle': list(rect), 'route': route, 'extent': extent})
    return rows


def source_regression(directory):
    original = pinned(directory / 'd3d9_device.cpp', 'd3d9_device.cpp').decode()
    util = pinned(directory / 'd3d9_util.h', 'd3d9_util.h').decode()
    helper = util[util.index('  inline bool IsBlitRegionInvalid('):]
    helper = helper[:helper.index('\n  }') + 4]
    with tempfile.TemporaryDirectory(prefix='aura-dxvk-empty-copy-') as temp:
        root = Path(temp)
        device = root / 'src/d3d9/d3d9_device.cpp'
        device.parent.mkdir(parents=True)
        device.write_text(original)
        subprocess.run(['git', 'apply', '--check', str(HERE / 'zero-extent-stretch-rect.patch')], cwd=root, check=True)
        subprocess.run(['git', 'apply', str(HERE / 'zero-extent-stretch-rect.patch')], cwd=root, check=True)
        patched = device.read_text()
        assert len(patched.splitlines()) - len(original.splitlines()) == 4
        preamble = '''#include <cstdint>
#include <cassert>
#include <cstdio>
#define unlikely(x) (x)
struct RECT { int32_t left, top, right, bottom; };
struct VkOffset3D { int32_t x, y, z; };
struct VkExtent3D { uint32_t width, height, depth; };
struct VkImageBlit { VkOffset3D srcOffsets[2], dstOffsets[2]; };
constexpr int D3D_OK = 0, D3DERR_INVALIDCALL = -1;
'''
        code = preamble + helper + '\n'
        for name, text in [('before', original), ('after', patched)]:
            method = text[text.index('  HRESULT STDMETHODCALLTYPE D3D9DeviceEx::StretchRect('):]
            body = method[method.index('    blitInfo.dstOffsets[0] ='):]
            body = body[:body.index('    bool srcIsDS =')]
            code += f'int {name}(const RECT* pSourceRect, const RECT* pDestRect) {{\n'
            code += 'VkExtent3D srcExtent{4096,4096,1}, dstExtent{4096,4096,1}; VkImageBlit blitInfo;\n'
            code += body + '\nreturn 1;\n}\n'
        code += '''int main() {
  RECT zero{0,0,0,0}, widthZero{0,0,0,600}, heightZero{0,0,800,0};
  RECT offsetZero{10,20,10,20}, edgeZero{4096,4096,4096,4096};
  RECT full{0,0,1920,1080}, one{0,0,1,1}, small{0,0,800,600}, phone{0,0,3120,1440};
  RECT negative{-1,0,100,100}, reversed{10,0,9,100}, overflow{0,0,4097,100};
  int cases = 0;
  auto check = [&](const RECT* src, const RECT* dst, int oldValue, int newValue) {
    assert(before(src,dst) == oldValue); assert(after(src,dst) == newValue); ++cases;
  };
  for (auto empty : {zero,widthZero,heightZero,offsetZero,edgeZero}) {
    check(&empty,&empty,1,0); check(&empty,&full,1,0); check(&full,&empty,1,0);
  }
  for (auto good : {one,small,full,phone}) check(&good,&good,1,1);
  check(&small,&full,1,1); check(nullptr,nullptr,1,1);
  check(nullptr,&full,1,1); check(&full,nullptr,1,1);
  for (auto bad : {negative,reversed,overflow}) {
    check(&bad,&full,-1,-1); check(&full,&bad,-1,-1);
    check(&zero,&bad,-1,-1); check(&bad,&zero,-1,-1);
  }
  std::printf("%d\\n", cases);
}
'''
        code = '#include <initializer_list>\n' + code
        source = root / 'regression.cpp'
        source.write_text(code)
        binary = root / 'regression'
        subprocess.run(['g++', '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                        '-Wno-unused-variable', str(source), '-o', str(binary)], check=True)
        cases = int(subprocess.check_output([str(binary)], text=True))
        return {'cases': cases, 'patch_applies': True,
                'patched_source_sha256': hashlib.sha256(patched.encode()).hexdigest(),
                'extracted_harness_sha256': hashlib.sha256(code.encode()).hexdigest(),
                'scope': 'Actual source rectangle setup, validation, extent calculation and upstream guard; not a complete D3D device'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--driver', type=Path, required=True)
    parser.add_argument('--d3d9', type=Path, required=True)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--guest-log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    driver, d3d9 = pinned(args.driver, 'driver'), pinned(args.d3d9, 'd3d9')
    result = {'schema': 'AURA_DXVK_ZERO_EXTENT_REPLAY_V1', 'result': 'PASS',
              'inputs': HASHES, 'unicorn': unicorn.__version__,
              'capture': decode_capture(args.guest_log),
              'driver_cases': replay_driver(driver), 'd3d9_binary_cases': replay_d3d9(d3d9),
              'source_regression': source_regression(args.upstream),
              'patch_sha256': hashlib.sha256((HERE / 'zero-extent-stretch-rect.patch').read_bytes()).hexdigest(),
              'android_or_gpu_executed': False, 'runtime_dll_changed': False,
              'limits': ['DLL replay starts after rectangle setup with modeled valid render-target metadata.',
                         'DLL replay stops before copy command construction; no Wine, FEX or GPU execution.',
                         'Driver replay stops at the original assertion or before the first GPU command write.',
                         'Source backport is not yet built into an ARM64EC runtime or installed on the phone.']}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'result': result['result'], 'capture': result['capture'],
                      'driver_cases': len(result['driver_cases']),
                      'd3d9_binary_cases': len(result['d3d9_binary_cases']),
                      'source_cases': result['source_regression']['cases']}, indent=2))


if __name__ == '__main__':
    main()

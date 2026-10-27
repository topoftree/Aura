#!/usr/bin/env python3
"""Differentially execute production-emitted SMC exits, not a full FEX/game run.

Extract the actual ThreadRemoveCodeEntry and SpillStaticRegs bodies, actual x32
register lists, and CpuStateFrame layout from the pinned sources. A small host
harness replaces only IR operand lookup/configuration; ARM64 bytes come from
the real CodeEmitter. Unicorn compares state, registers and ordered writes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_MEM_WRITE
import unicorn.arm64_const as arm


def body(text, signature):
    start = text.index('{', text.index(signature))
    depth = 1
    end = start + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


HARNESS = r'''
#include "Interface/Core/ArchHelpers/Arm64Emitter.h"
#include "Interface/IR/IR.h"
#include <FEXCore/Config/Config.h>
#include <FEXCore/Core/CoreState.h>
#include <FEXCore/fextl/map.h>
#include <fstream>
#include <cassert>
#include <cstdio>
#include <string>
namespace FEXCore::CPU {
@REGISTERS@
struct ExposeSpillOptions : Arm64Emitter { using Arm64Emitter::SpillStaticRegOptions; };
struct Option { int value; int operator()() const { return value; } };
struct TestContext {
  struct { Option CrysisSharedSMCExits {1}, Is64BitMode {0}, SMCChecks {2}; } Config;
  struct { bool SupportsAFP {}, SupportsAVX {}, SupportsSVE256 {}; } HostFeatures;
};
struct TestIR {
  ARMEmitter::Register EntryToInvalidate, NewRIP;
  template<class T> const TestIR* C() const { return this; }
};
class TestEmitter : public ARMEmitter::Emitter {
public:
  TestContext Context;
  TestContext* CTX = &Context;
  TestContext* EmitterCTX = &Context;
  decltype(x32::SRA) StaticRegisters = x32::SRA;
  decltype(x32::SRAFPR) StaticFPRegisters = x32::SRAFPR;
  using SpillStaticRegOptions = ExposeSpillOptions::SpillStaticRegOptions;
  fextl::map<uint16_t, ARMEmitter::BackwardLabel> SharedSMCExits;
  TestEmitter(uint8_t* p, size_t n) : ARMEmitter::Emitter(p, n) {}
  ARMEmitter::Register GetReg(ARMEmitter::Register reg) { return reg; }
  template<class T> void b_OrRestart(T* label) { assert(b(label) == ARMEmitter::BranchEncodeSucceeded::Success); }
  template<class T> void BindOrRestart(T* label) { assert(Bind(label)); }
  void SpillStaticRegs(ARMEmitter::Register reg) { SpillStaticRegs(reg, {}); }
  void SpillStaticRegs(ARMEmitter::Register, SpillStaticRegOptions);
  void Original(const TestIR* IROp) @ORIGINAL@
  void Patched(const TestIR* IROp) @PATCHED@
};
void TestEmitter::SpillStaticRegs(ARMEmitter::Register TmpReg, SpillStaticRegOptions Options) @SPILL@
}
int main(int argc, char** argv) {
  using namespace FEXCore::CPU;
  assert(argc == 2);
  const std::string dir = argv[1];
  // Every permitted x32 dynamic operand pair, then reuse with fresh values.
  // Include static operands and same-register pairs to catch unsafe grouping.
  std::vector<ARMEmitter::Register> regs(x32::RA.begin(), x32::RA.end());
  for (auto reg : x32::SRA) regs.push_back(reg);
  for (int afp = 0; afp < 2; ++afp) {
    for (int mode = 0; mode < 6; ++mode) {
      std::array<uint8_t, 524288> code {};
      TestEmitter e(code.data(), code.size());
      e.Context.HostFeatures.SupportsAFP = afp;
      if (mode == 2) e.Context.Config.CrysisSharedSMCExits.value = 0;
      if (mode == 3) e.Context.Config.Is64BitMode.value = 1;
      if (mode == 4) e.Context.Config.SMCChecks.value = 1;
      std::ofstream index(dir + "/" + std::to_string(afp) + "-" + std::to_string(mode) + ".idx");
      for (unsigned repeat = 0; repeat < 2; ++repeat) {
        if (mode == 5) e.SharedSMCExits.clear(); // same operation as CompileCode restart
        for (auto entry : regs) for (auto rip : regs) {
          TestIR op {entry, rip};
          auto offset = e.GetCursorOffset();
          if (mode == 0) e.Original(&op); else e.Patched(&op);
          index << offset << ' ' << entry.Idx() << ' ' << rip.Idx() << '\n';
        }
      }
      std::ofstream out(dir + "/" + std::to_string(afp) + "-" + std::to_string(mode) + ".bin", std::ios::binary);
      out.write(reinterpret_cast<char*>(code.data()), e.GetCursorOffset());
    }
    // Size-only synthetic FULL unit with two register-pair variants, 512 exits.
    for (int mode = 0; mode < 2; ++mode) {
      std::array<uint8_t, 65536> code {};
      TestEmitter e(code.data(), code.size());
      e.Context.HostFeatures.SupportsAFP = afp;
      for (unsigned i = 0; i < 512; ++i) {
        TestIR op {x32::RA[i % 2], x32::RA[2 + i % 2]};
        if (mode == 0) e.Original(&op); else e.Patched(&op);
      }
      std::printf("%d %d %zu\n", afp, mode, e.GetCursorOffset());
    }
  }
  std::ofstream offsets(dir + "/layout.json");
  offsets << "{\"frameBytes\":" << sizeof(FEXCore::Core::CpuStateFrame)
          << ",\"rip\":" << offsetof(FEXCore::Core::CpuStateFrame, State.rip)
          << ",\"dispatch\":" << offsetof(FEXCore::Core::CpuStateFrame, Pointers.ThreadDispatchRemoveCodeEntry) << "}";
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline-source', 'patched-source', 'generated', 'work', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    rel = 'FEXCore/Source/Interface/Core/'
    original_text = (args.baseline_source / (rel + 'JIT/BranchOps.cpp')).read_text()
    patched_text = (args.patched_source / (rel + 'JIT/BranchOps.cpp')).read_text()
    spill_text = (args.patched_source / (rel + 'ArchHelpers/Arm64Emitter.cpp')).read_text()
    assert spill_text == (args.baseline_source / (rel + 'ArchHelpers/Arm64Emitter.cpp')).read_text()
    compile_text = (args.patched_source / (rel + 'JIT/JIT.cpp')).read_text()
    # Check reset is AFTER the setjmp restart switch and BEFORE buffer acquisition.
    start = compile_text.index('CPUBackend::CompiledCode Arm64JITCore::CompileCode(')
    reset = compile_text.index('SharedSMCExits.clear();', start)
    assert compile_text.index('SetJump(ThreadState->RestartJump)', start) < reset
    assert reset < compile_text.index('TempCodeBufferAllocator.ReownOrClaimBufferWithSize', start)
    old_body = body(original_text, 'DEF_OP(ThreadRemoveCodeEntry)')
    new_body = body(patched_text, 'DEF_OP(ThreadRemoveCodeEntry)')
    # The existing exit is unchanged byte for byte at source level.
    assert old_body[old_body.index('  SpillStaticRegs(TMP1);'):] == new_body[new_body.index('  SpillStaticRegs(TMP1);'):]
    cpp = HARNESS.replace('@ORIGINAL@', old_body).replace('@PATCHED@', new_body)
    cpp = cpp.replace('@SPILL@', body(spill_text, 'void Arm64Emitter::SpillStaticRegs('))
    cpp = cpp.replace('@REGISTERS@', 'namespace x32 ' + body(spill_text, 'namespace x32'))
    harness = args.work / 'emitter.cpp'
    harness.write_text(cpp)
    source = args.patched_source.resolve()
    includes = [source / x for x in ('FEXCore/Source', 'FEXCore/include', 'FEXHeaderUtils',
                                     'CodeEmitter', 'External/fmt/include', 'External/unordered_dense/include')]
    includes.append(args.generated.resolve() / 'include')
    command = ['g++', '-std=c++20', '-O2', '-Wno-changes-meaning', '-DARCHITECTURE_x86_64=1',
               '-DASSERTIONS_ENABLED=1', '-fsanitize=undefined', '-fno-sanitize-recover=all']
    command += ['-I' + str(x) for x in includes]
    command += [str(harness)] + [str(source / x) for x in (
        'FEXCore/Source/Utils/LogManager.cpp', 'FEXCore/Source/Utils/ForcedAssert.cpp',
        'FEXCore/Source/Utils/AllocatorHooks.cpp', 'External/fmt/src/format.cc')]
    binary = args.work / 'emit'
    command += ['-o', str(binary)]
    with (args.work / 'build.log').open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    result = subprocess.run([binary, args.work], capture_output=True, text=True, check=True)
    sizes = [list(map(int, line.split())) for line in result.stdout.splitlines()]
    layout = json.loads((args.work / 'layout.json').read_text())
    code, state, stop = 0x100000, 0x400000, 0x1f0000
    rng = random.Random(20261025)
    total = 0
    byte_rows = []
    register_ids = [getattr(arm, f'UC_ARM64_REG_X{i}') for i in range(31)]
    vector_ids = [getattr(arm, f'UC_ARM64_REG_Q{i}') for i in range(32)]
    for afp in range(2):
        machines, indexes, writes = [], [], []
        blobs = [(args.work / f'{afp}-{mode}.bin').read_bytes() for mode in range(6)]
        assert all(blobs[mode] == blobs[0] for mode in (2, 3, 4, 5)), 'fallback/reset changed code'
        for mode, blob in enumerate(blobs[:2]):
            uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            uc.mem_map(code, 1024 * 1024)
            uc.mem_write(code, blob)
            uc.mem_map(state, 16384)
            events = []
            uc.hook_add(UC_HOOK_MEM_WRITE, lambda cpu, access, address, size, value, user, out=events:
                        out.append((address - state, size, value)))
            machines.append(uc)
            writes.append(events)
            indexes.append([tuple(map(int, line.split())) for line in
                            (args.work / f'{afp}-{mode}.idx').read_text().splitlines()])
        for i, ((old_offset, entry, rip), (new_offset, e2, r2)) in enumerate(zip(*indexes, strict=True)):
            assert (entry, rip) == (e2, r2)
            # Fresh operands each time, including shared sites; cover every NZCV.
            for flags in range(16):
                regs = [rng.getrandbits(64) for _ in register_ids]
                regs[28] = state
                vectors = [rng.getrandbits(128) for _ in vector_ids]
                initial = bytearray(rng.randbytes(layout['frameBytes']))
                initial[layout['dispatch']:layout['dispatch'] + 8] = stop.to_bytes(8, 'little')
                outcomes = []
                for mode, uc in enumerate(machines):
                    uc.mem_write(state, bytes(initial))
                    for reg, value in zip(register_ids, regs): uc.reg_write(reg, value)
                    for reg, value in zip(vector_ids, vectors): uc.reg_write(reg, value)
                    uc.reg_write(arm.UC_ARM64_REG_SP, 0x600000)
                    uc.reg_write(arm.UC_ARM64_REG_NZCV, flags << 28)
                    uc.reg_write(arm.UC_ARM64_REG_FPCR, 7 if afp else 0)
                    writes[mode].clear()
                    uc.emu_start(code + (old_offset if mode == 0 else new_offset), stop, count=128)
                    assert uc.reg_read(arm.UC_ARM64_REG_PC) == stop
                    assert uc.reg_read(arm.UC_ARM64_REG_X0) == state
                    assert uc.reg_read(arm.UC_ARM64_REG_X1) == regs[entry]
                    assert int.from_bytes(uc.mem_read(state + layout['rip'], 8), 'little') == regs[rip]
                    outcomes.append((bytes(uc.mem_read(state, len(initial))),
                        tuple(uc.reg_read(reg) for reg in register_ids + vector_ids),
                        uc.reg_read(arm.UC_ARM64_REG_SP), uc.reg_read(arm.UC_ARM64_REG_NZCV),
                        uc.reg_read(arm.UC_ARM64_REG_FPCR), tuple(writes[mode])))
                assert outcomes[0] == outcomes[1], (afp, entry, rip, flags, i)
                total += 1
        byte_rows.append(dict(afp=bool(afp), originalBytes=len(blobs[0]), sharedBytes=len(blobs[1]),
                              operandPairs=len(indexes[0]) // 2))
    report = dict(schema='CRYSIS_SHARED_SMC_EXIT_TEST_V1', comparisons=total,
        actualProductionExitAndSpillBodies=True, actualCoreStateLayout=True,
        orderedStateWritesEqual=True, gprVectorFlagsAndStackEqual=True,
        nonFull64bitAndDisabledByteIdentical=True, resetBetweenAttemptsByteIdentical=True,
        resetAfterRestartBeforeNewBufferVerified=True, exactOriginalExitBodyPreserved=True,
        rows=byte_rows, synthetic512ExitBytes=[dict(afp=bool(a), shared=bool(m), bytes=n) for a,m,n in sizes],
        sources={name: hashlib.sha256(value.encode()).hexdigest() for name,value in
                 [('originalExit', old_body), ('patchedExit', new_body), ('spill', spill_text)]},
        fullRegisterAllocatorExecuted=False, fullRuntimeExecuted=False, sveExecuted=False,
        asynchronousSignalsTested=False, androidDeviceTested=False, fpsGainMeasured=False)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

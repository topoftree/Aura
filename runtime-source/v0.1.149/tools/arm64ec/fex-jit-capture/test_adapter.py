#!/usr/bin/env python3
"""Execute the real FEX adapter with synthetic SHM and a controlled clock."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
SHM = '''#pragma once
#include "JitCapture.h"
namespace FEXCore::SHMStats {
struct ThreadStats { uint8_t Prefix[112]; AuraJit::Record Aura; };
struct ThreadStatsHeader { uint8_t Prefix[64]; AuraJit::Control Aura; };
inline uint64_t Tick;
inline uint64_t GetCycleCounter() { return Tick; }
static_assert(sizeof(ThreadStats) == 1280 && sizeof(ThreadStatsHeader) == 128);
}
'''
ALLOCATOR = '''#pragma once
#include <cstdlib>
namespace FEXCore::Allocator {
inline void* VirtualAlloc(size_t Size) { return std::calloc(1, Size); }
inline void VirtualFree(void* Ptr, size_t) { std::free(Ptr); }
}
'''
TEST = r'''
#include "JitCaptureFex.h"
#include <cassert>
#include <cstdio>
struct Shared { FEXCore::SHMStats::ThreadStatsHeader Header; FEXCore::SHMStats::ThreadStats Thread; };
int main() {
  using namespace AuraJit;
  for (uint64_t G = 1; G <= 128; ++G) {
    Shared S {};
    S.Header.Aura = {Magic, 1, 100, 10100, 1000, 1, 0, 0};
    auto& V = S.Thread.Aura.Values;
    V[Slot] = 128; V[Serial] = 1;
    FEXCore::SHMStats::Tick = 101;
    RecordEvent(&S.Thread, BufferAdopted, 134213632, 10000, G);
    {
      PrivateState P;
      for (int I = 0; I < 3; ++I) {
        FEXCore::SHMStats::Tick = 102 + I * 3;
        Compilation C(&S.Thread, P, 4096, 0);
        FEXCore::SHMStats::Tick += 2;
        C.Finish(G + (I == 2), 134213632, 4096, false);
      }
      assert(V[Compiles] == 3 && V[CompileTicks] == 6 && V[MaxCompileTicks] == 2);
      assert(V[Repeats] == 2 && V[CrossGeneration] == 1 && V[Unknown] == 0);
      assert(V[FirstTick] == 101 && V[LastTick] == 110 && V[PeakUsed] == 10000);
      assert(V[Generation] == G + 1 && V[Races] == 0);
      assert(V[AllocationFailures] == 0 && V[PreviousUsed] == 0 && V[MaxRequest] == 0);
      assert(S.Thread.Aura.Bins[0].Compiles == 3 && S.Thread.Aura.Bins[0].Ticks == 6);
      auto Before = S.Thread.Aura;
      S.Header.Aura.Deadline = 0;
      Compilation Off(&S.Thread, P, 8192, 0);
      Off.Finish(UINT64_MAX, 1, 1, true);
      RecordEvent(&S.Thread, AllocationFailed, 1, 2, 3);
      assert(std::memcmp(&Before, &S.Thread.Aura, sizeof(Before)) == 0);
    }
    assert(OutstandingTables == 0);
  }
  {
    Shared S {}; S.Header.Aura = {Magic, 42, 100, 10100, 1000, 3, 0, 0};
    S.Thread.Aura.Values[Slot] = 128; S.Thread.Aura.Values[Serial] = 1;
    S.Thread.Aura.Audit[WineKnown] = 127;
    FEXCore::SHMStats::Tick = 150;
    RecordCache(&S.Thread, RamBlobHit, 4096, 42);
    RecordCache(&S.Thread, CodeLoaded, 0, 42);
    assert(S.Thread.Aura.Cache[RamBlobHit] == 1 && S.Thread.Aura.Cache[CodeLoaded] == 1);
    assert(S.Thread.Aura.Cache[RamBytes] == 4096 && S.Thread.Aura.Cache[RamPeakBytes] == 4096);
    // Rearm resets interval counts, preserves immutable capability evidence and rejects the old lease.
    S.Header.Aura.Epoch = 43;
    RecordCache(&S.Thread, KeyMissing, 0, 42);
    assert(S.Thread.Aura.Cache[KeyMissing] == 0);
    RecordCache(&S.Thread, KeyMissing, 1024, 43);
    assert(S.Thread.Aura.Cache[RamBlobHit] == 0 && S.Thread.Aura.Cache[KeyMissing] == 1);
    assert(S.Thread.Aura.Audit[WineKnown] == 127);
    FEXCore::SHMStats::Tick = 10101;
    RecordCache(&S.Thread, KeyMissing, 9999, 43);
    assert(S.Thread.Aura.Cache[KeyMissing] == 1);
  }
  std::puts("128 generations: fields, bins, repeat classification, disabled capture and release passed");
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix='aura-jd2-adapter-') as directory:
        tmp = Path(directory)
        includes = tmp / 'FEXCore/Utils'
        includes.mkdir(parents=True)
        (includes / 'SHMStats.h').write_text(SHM)
        (includes / 'AllocatorHooks.h').write_text(ALLOCATOR)
        (tmp / 'test.cpp').write_text(TEST)
        subprocess.run(['g++', '-std=c++20', '-O1', '-g', '-fno-pie', '-no-pie',
            '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-I', str(tmp), '-I', str(ROOT),
            str(tmp / 'test.cpp'), '-o', str(tmp / 'test')], check=True)
        result = subprocess.run([str(tmp / 'test')], check=True, capture_output=True, text=True,
            env={**os.environ, 'ASAN_OPTIONS': 'detect_leaks=0', 'UBSAN_OPTIONS': 'halt_on_error=1'})
        assert not result.stderr
    report = dict(schema='FEX_JD2_ADAPTER_TEST_V1', generations=128, passed=True,
        adapterSha256=hashlib.sha256((ROOT / 'JitCaptureFex.h').read_bytes()).hexdigest(),
        sanitizer='ASan + UBSan; LeakSanitizer unavailable under ptrace',
        nativeDiagnosticAdapterExecuted=True, syntheticClockAndAllocator=True, androidDeviceTested=False)
    Path(sys.argv[1]).write_text(json.dumps(report, indent=2) + '\n')
    print(result.stdout.strip())


if __name__ == '__main__':
    main()

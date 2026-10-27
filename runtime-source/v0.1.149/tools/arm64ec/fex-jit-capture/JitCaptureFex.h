// SPDX-License-Identifier: MIT
#pragma once
#include <FEXCore/Utils/SHMStats.h>
#include <FEXCore/Utils/AllocatorHooks.h>

namespace AuraJit {
// At most 2 MiB of private repeat keys per process; overflow remains explicitly unknown.
inline std::atomic<uint32_t> OutstandingTables {};
struct PrivateState {
  RepeatTable* Table {};
  bool Busy {};
  ~PrivateState() {
    if (Table) { FEXCore::Allocator::VirtualFree(Table, sizeof(RepeatTable)); --OutstandingTables; }
  }
};
inline Control* ControlFor(FEXCore::SHMStats::ThreadStats* Stats) {
  if (!Stats) return nullptr;
  auto Offset = Stats->Aura.Values[Slot];
  if (Offset < 128 || Offset > 4 * 1024 * 1024 - sizeof(*Stats) || (Offset - 128) % sizeof(*Stats)) return nullptr;
  return &reinterpret_cast<FEXCore::SHMStats::ThreadStatsHeader*>(reinterpret_cast<uint8_t*>(Stats) - Offset)->Aura;
}
inline uint64_t ActiveEpoch(FEXCore::SHMStats::ThreadStats* Stats) {
  auto C = ControlFor(Stats);
  // No counter instruction, allocation, I/O or lock when no manual capture is armed.
  return C && Load(C->Deadline) ? Load(C->Epoch) : 0;
}
inline void RecordCache(FEXCore::SHMStats::ThreadStats* Stats, CacheField Result, uint64_t Ram = 0, uint64_t Epoch = 0, bool Resident = false) {
  if (!Epoch) return;
  Writer W(ControlFor(Stats), &Stats->Aura, FEXCore::SHMStats::GetCycleCounter(), Epoch);
  if (!W) return;
  auto& C = W.Get().Cache;
  ++C[Result];
  if (Result == RamBlobHit) ++C[Resident ? ResidentBlobHit : PendingBlobHit];
  if (Result < CodeLoaded) { C[RamBytes] = Ram; C[RamPeakBytes] = std::max(C[RamPeakBytes], Ram); }
}
inline void RecordEvent(FEXCore::SHMStats::ThreadStats* Stats, Event Type, uint64_t A = 0, uint64_t B = 0, uint64_t C = 0) {
  if (!ActiveEpoch(Stats)) return;
  auto Tick = FEXCore::SHMStats::GetCycleCounter();
  Writer W(ControlFor(Stats), &Stats->Aura, Tick);
  if (!W) return;
  auto& V = W.Get().Values;
  switch (Type) {
    case AllocationFailed: ++V[AllocationFailures]; V[PreviousCapacity] = A; V[PreviousUsed] = B;
      V[MaxRequest] = std::max(V[MaxRequest], C); break;
    case BufferCreated: ++V[Creations]; break;
    case BufferAdopted: ++V[Adoptions]; break;
    case ClearRequested: ++V[Clears]; break;
    case InlineExit: ++V[InlineExits]; break;
    case RangeInvalidated: ++V[Invalidations]; break;
    default: break;
  }
  if (Type == BufferCreated || Type == BufferAdopted) {
    V[Capacity] = A; V[Used] = B; V[Generation] = C; V[PeakUsed] = std::max(V[PeakUsed], B);
  }
  W.AddEvent(Tick, Type, A, B, C);
}
class Compilation {
  FEXCore::SHMStats::ThreadStats* Stats {};
  PrivateState* Private {};
  uint64_t Epoch {}, Begin {}, RIP {}, MaxInst {};
public:
  Compilation(FEXCore::SHMStats::ThreadStats* S, PrivateState& P, uint64_t Address, uint64_t Limit) {
    Epoch = ActiveEpoch(S);
    if (!Epoch || P.Busy) return;
    Begin = FEXCore::SHMStats::GetCycleCounter();
    Writer W(ControlFor(S), &S->Aura, Begin, Epoch);
    if (!W) return;
    Stats = S; Private = &P; RIP = Address; MaxInst = Limit; P.Busy = true;
    if (!P.Table) {
      if (OutstandingTables.fetch_add(1) < 64) {
        P.Table = static_cast<RepeatTable*>(FEXCore::Allocator::VirtualAlloc(sizeof(RepeatTable)));
        if (P.Table) new (P.Table) RepeatTable {};
      }
      if (!P.Table) --OutstandingTables;
    }
  }
  ~Compilation() { if (Private) Private->Busy = false; }
  void Finish(uint64_t BufferGeneration, uint64_t CapacityBytes, uint64_t UsedBytes, bool Raced) {
    if (!Stats) return;
    auto Tick = FEXCore::SHMStats::GetCycleCounter();
    Writer W(ControlFor(Stats), &Stats->Aura, Tick, Epoch);
    if (!W) return;
    auto& V = W.Get().Values;
    auto Duration = Tick - Begin;
    ++V[Compiles]; V[Races] += Raced; V[CompileTicks] += Duration;
    V[MaxCompileTicks] = std::max(V[MaxCompileTicks], Duration);
    auto Repeat = Private->Table ? Private->Table->Observe(RIP, MaxInst, BufferGeneration, Epoch) : -1;
    V[Repeats] += Repeat > 0; V[CrossGeneration] += Repeat == 2; V[Unknown] += Repeat < 0;
    if (Repeat < 0) V[Flags] |= TrackingPartial;
    V[Capacity] = CapacityBytes; V[Used] = UsedBytes; V[AuraJit::Generation] = BufferGeneration;
    V[PeakUsed] = std::max(V[PeakUsed], UsedBytes);
    auto Hz = Load(ControlFor(Stats)->Frequency);
    auto Index = std::min<uint64_t>(Buckets - 1, (Tick - V[Start]) * 2 / Hz);
    auto& Bin = W.Get().Bins[Index];
    ++Bin.Compiles; Bin.Repeats += Repeat > 0; Bin.Ticks += Duration;
    if (Duration >= Hz / 1000) W.AddEvent(Tick, SlowCompile, Duration, Repeat > 0, Raced);
  }
};
}

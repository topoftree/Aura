// SPDX-License-Identifier: MIT
// Aura downstream diagnostics. No guest addresses or instruction bytes enter this ABI.
#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include "CacheAudit.h"

namespace AuraJit {
constexpr uint64_t Magic = 0x3154494a41525541ULL; // AURAJIT1
constexpr uint64_t Budget = 50000;
constexpr size_t Buckets = 20, Events = 8, TableSize = 1024;
struct Control {
  uint64_t MagicValue, Epoch, Start, Deadline, Frequency, Capabilities, NextSerial, Reserved;
};
enum Field : size_t {
  Slot, Serial, Sequence, Epoch, Operations, Flags, Compiles, Repeats, CrossGeneration, Unknown,
  CompileTicks, MaxCompileTicks, InlineExits, Invalidations, AllocationFailures, Creations, Adoptions,
  Clears, Capacity, Used, MaxRequest, PeakUsed, FirstTick, LastTick, Start, Deadline, EventCount,
  ContentionLifetime, PreviousCapacity, PreviousUsed, Generation, Races,
};
enum Flag : uint64_t { Expired = 1, BudgetReached = 2, TrackingPartial = 4 };
enum Event : uint64_t { AllocationFailed = 1, BufferCreated, BufferAdopted, ClearRequested, InlineExit, RangeInvalidated, SlowCompile };
struct Bucket { uint32_t Compiles, Repeats; uint64_t Ticks; };
struct EventRow { uint64_t Tick, Type, A, B, C; };
struct Record {
  std::array<uint64_t, 32> Values;
  std::array<Bucket, Buckets> Bins;
  std::array<EventRow, Events> Ring;
  uint64_t Reserved[2];
  std::array<uint64_t, 8> Audit;
  std::array<uint64_t, 24> Cache;
};
static_assert(sizeof(Control) == 64 && sizeof(Record) == 1168);
inline uint64_t Load(const uint64_t& Value) { return std::atomic_ref(const_cast<uint64_t&>(Value)).load(std::memory_order_acquire); }
inline void Store(uint64_t& Value, uint64_t New) { std::atomic_ref(Value).store(New, std::memory_order_release); }

// A try-only writer: diagnostic contention must never wait on a game thread.
class Writer {
  Record* R {};
  uint64_t Seq {};
public:
  Writer(Control* C, Record* Candidate, uint64_t Now, uint64_t ExpectedEpoch = 0) {
    if (!C || !Candidate || Load(C->MagicValue) != Magic) return;
    auto End = Load(C->Deadline), E = Load(C->Epoch), Begin = Load(C->Start), Hz = Load(C->Frequency);
    if (!End || !E || (ExpectedEpoch && E != ExpectedEpoch) || !Hz || Hz > 10000000000ULL ||
        End <= Begin || End - Begin > Hz * 10 || Now < Begin) return;
    auto& V = Candidate->Values;
    Seq = Load(V[Sequence]);
    if ((Seq & 1) || !std::atomic_ref(V[Sequence]).compare_exchange_strong(Seq, Seq + 1, std::memory_order_acquire)) {
      std::atomic_ref(V[ContentionLifetime]).fetch_add(1, std::memory_order_relaxed);
      return;
    }
    R = Candidate;
    // Disarm/rearm can happen concurrently with a native callback.
    if (Load(C->Deadline) != End || Load(C->Epoch) != E) { Release(); return; }
    if (V[Epoch] != E) {
      for (size_t I = Epoch; I < V.size(); ++I) if (I != ContentionLifetime) V[I] = 0;
      R->Bins = {}; R->Ring = {};
      R->Cache = {};
      V[Epoch] = E; V[Start] = Begin; V[Deadline] = End;
    }
    if (Now >= End) { V[Flags] |= Expired; Release(); return; }
    if (V[Operations] >= Budget) { V[Flags] |= BudgetReached; Release(); return; }
    ++V[Operations];
    if (!V[FirstTick]) V[FirstTick] = Now;
    V[LastTick] = Now;
  }
  ~Writer() { Release(); }
  Writer(const Writer&) = delete;
  explicit operator bool() const { return R; }
  Record& Get() { return *R; }
  void Release() { if (R) { Store(R->Values[Sequence], Seq + 2); R = nullptr; } }
  void AddEvent(uint64_t Tick, Event Type, uint64_t A = 0, uint64_t B = 0, uint64_t C = 0) {
    auto Index = R->Values[EventCount]++;
    R->Ring[Index % Events] = {Tick, Type, A, B, C};
  }
};

// Full-key equality, bounded probing; collisions/table saturation are UNKNOWN, never false repeats.
// Private memory only. A first sighting means first in this capture, not first ever.
struct Entry { uint64_t RIP, MaxInst, Generation, Epoch; };
struct RepeatTable {
  std::array<Entry, TableSize> Entries {};
  int Observe(uint64_t RIP, uint64_t MaxInst, uint64_t Generation, uint64_t Epoch) {
    auto Hash = ((RIP >> 4) ^ (RIP * 0x9e3779b97f4a7c15ULL) ^ MaxInst) % TableSize;
    for (size_t I = 0; I < 8; ++I) {
      auto& E = Entries[(Hash + I) % TableSize];
      if (E.Epoch != Epoch) { E = {RIP, MaxInst, Generation, Epoch}; return 0; }
      if (E.RIP == RIP && E.MaxInst == MaxInst) {
        int Result = E.Generation == Generation ? 1 : 2;
        E.Generation = Generation;
        return Result;
      }
    }
    return -1;
  }
};
}

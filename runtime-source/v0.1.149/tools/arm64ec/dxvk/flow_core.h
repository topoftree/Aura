#pragma once
#include <cstdint>
#include <cstring>
#include <limits>
#ifndef AURA_FLOW_TEST
#include <windows.h>
#endif

// Shared POD layout. No object handles, pointers, resource contents or shader data.
namespace aura_flow {
constexpr uint32_t FileBytes = 36864, Rows = 128, Bins = 20, Limit = 50000;
enum Reason : uint32_t {
  Present = 1, Flush, FlushWeak, FlushStrong, FlushSync, FlushExplicit,
  WaitMapRead, WaitMapWrite, MapBusyNoWait, CommandThread,
  QueryGetData, QueryFlush, StagingWait, QueueSubmit, GpuCompletion,
  ShaderModule, PipelineCompile, ReasonCount
};
struct alignas(8) Header {
  char magic[8]; uint32_t version, bytes, armed, epoch, nativeEpoch, initializing;
  uint32_t ownerPid, rowDrops;
  uint64_t frequency, anchorBefore, anchorAfter, utc100ns, deadline;
  uint32_t operations, budgetDrops, clockDrops, reserved;
  uint8_t padding[160];
};
struct Bin { uint32_t calls, elapsedUs; };
struct alignas(8) Row {
  uint64_t key; uint32_t sequence, epoch;
  uint64_t calls, ticks, maxTicks, firstTick, lastTick, saturatedBins;
  Bin bins[Bins]; uint8_t padding[32];
};
struct alignas(8) Data { Header header; Row rows[Rows]; uint8_t padding[3840]; };
static_assert(sizeof(Header) == 256 && sizeof(Row) == 256 && sizeof(Data) == FileBytes);

inline uint32_t load(uint32_t& p) {
#ifdef AURA_FLOW_TEST
  return __atomic_load_n(&p, __ATOMIC_SEQ_CST);
#else
  return uint32_t(InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(&p), 0, 0));
#endif
}
inline uint64_t load(uint64_t& p) {
#ifdef AURA_FLOW_TEST
  return __atomic_load_n(&p, __ATOMIC_SEQ_CST);
#else
  return uint64_t(InterlockedCompareExchange64(reinterpret_cast<volatile LONG64*>(&p), 0, 0));
#endif
}
inline bool claim(uint32_t& p, uint32_t expected, uint32_t value) {
#ifdef AURA_FLOW_TEST
  return __atomic_compare_exchange_n(&p, &expected, value, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
#else
  return uint32_t(InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(&p), LONG(value), LONG(expected))) == expected;
#endif
}
inline bool claim(uint64_t& p, uint64_t expected, uint64_t value) {
#ifdef AURA_FLOW_TEST
  return __atomic_compare_exchange_n(&p, &expected, value, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
#else
  return uint64_t(InterlockedCompareExchange64(reinterpret_cast<volatile LONG64*>(&p), LONG64(value), LONG64(expected))) == expected;
#endif
}
inline void store(uint32_t& p, uint32_t value) {
#ifdef AURA_FLOW_TEST
  __atomic_store_n(&p, value, __ATOMIC_SEQ_CST);
#else
  InterlockedExchange(reinterpret_cast<volatile LONG*>(&p), LONG(value));
#endif
}
inline void add(uint32_t& p) {
#ifdef AURA_FLOW_TEST
  __atomic_fetch_add(&p, 1, __ATOMIC_SEQ_CST);
#else
  InterlockedIncrement(reinterpret_cast<volatile LONG*>(&p));
#endif
}
inline void store(uint64_t& p, uint64_t value) {
#ifdef AURA_FLOW_TEST
  __atomic_store_n(&p, value, __ATOMIC_SEQ_CST);
#else
  InterlockedExchange64(reinterpret_cast<volatile LONG64*>(&p), LONG64(value));
#endif
}
inline bool valid(const Data* d) {
  return d && !std::memcmp(d->header.magic, "AUDFLOW1", 8) && d->header.version == 1 && d->header.bytes == FileBytes;
}
// The caller owns header.initializing. Native data for a new epoch is published last.
inline bool initialize(Data* d, uint32_t epoch, uint32_t pid, uint64_t frequency,
                       uint64_t before, uint64_t after, uint64_t utc) {
  if (!valid(d)) return false;
  auto& h = d->header;
  if (!epoch || load(h.armed) != 1 || load(h.epoch) != epoch ||
      load(h.ownerPid) != pid || frequency == 0 || frequency > 1000000000 ||
      before == 0 || after < before || after - before > frequency ||
      before > std::numeric_limits<uint64_t>::max() - 10 * frequency) return false;
  store(h.frequency, frequency); store(h.anchorBefore, before); store(h.anchorAfter, after);
  store(h.utc100ns, utc); store(h.deadline, before + 10 * frequency);
  store(h.operations, 0); store(h.budgetDrops, 0); store(h.rowDrops, 0); store(h.clockDrops, 0);
  store(h.nativeEpoch, epoch);
  return true;
}
struct Ticket {
  Data* data = nullptr; uint32_t epoch = 0, tid = 0, reason = 0;
  uint64_t tick = 0, frequency = 0, anchor = 0, deadline = 0;
};
inline Ticket begin(Data* d, uint32_t reason, uint32_t tid, uint64_t tick) {
  if (!valid(d) || reason == 0 || reason >= ReasonCount || tid == 0) return {};
  auto& h = d->header; auto epoch = load(h.epoch);
  if (load(h.armed) != 1 || !epoch || load(h.nativeEpoch) != epoch) return {};
  uint64_t frequency = load(h.frequency), anchor = load(h.anchorBefore), deadline = load(h.deadline);
  if (frequency == 0 || tick < anchor || tick >= deadline || load(h.operations) >= Limit ||
      load(h.epoch) != epoch || load(h.nativeEpoch) != epoch || load(h.armed) != 1) return {};
  return {d, epoch, tid, reason, tick, frequency, anchor, deadline};
}
inline void finish(Ticket t, uint64_t now) {
  if (!t.data) return;
  auto& h = t.data->header;
  if (load(h.armed) != 1 || load(h.epoch) != t.epoch || load(h.nativeEpoch) != t.epoch) return;
  if (now < t.tick || now >= t.deadline) { add(h.clockDrops); return; }
  // A bounded CAS loop is unnecessary: a contended observation may be omitted.
  uint32_t operations = load(h.operations);
  if (operations >= Limit || !claim(h.operations, operations, operations + 1)) { add(h.budgetDrops); return; }
  uint64_t key = (uint64_t(t.tid) << 32) | t.reason;
  for (uint32_t i = 0; i < Rows; i++) {
    auto& r = t.data->rows[(t.tid * 17u + t.reason + i) % Rows];
    auto observed = load(r.key);
    if (observed != key && !(observed == 0 && claim(r.key, uint64_t(0), key))) continue;
    uint32_t seq = load(r.sequence);
    if (seq & 1 || !claim(r.sequence, seq, seq + 1)) { add(h.rowDrops); return; }
    if (load(h.epoch) != t.epoch || load(h.armed) != 1) { store(r.sequence, seq + 2); return; }
    if (r.epoch != t.epoch) {
      std::memset(reinterpret_cast<uint8_t*>(&r) + 16, 0, sizeof(r) - 16);
      r.epoch = t.epoch;
    }
    uint64_t elapsed = now - t.tick;
    r.calls++; r.ticks += elapsed;
    if (elapsed > r.maxTicks) r.maxTicks = elapsed;
    if (!r.firstTick || t.tick < r.firstTick) r.firstTick = t.tick;
    if (now > r.lastTick) r.lastTick = now;
    const uint32_t bin = uint32_t((t.tick - t.anchor) * 2 / t.frequency);
    if (bin < Bins) {
      auto& b = r.bins[bin]; b.calls++;
      uint64_t micros = elapsed * 1000000 / t.frequency;
      if (micros > UINT32_MAX - b.elapsedUs) { b.elapsedUs = UINT32_MAX; r.saturatedBins++; }
      else b.elapsedUs += uint32_t(micros);
    }
    store(r.sequence, seq + 2);
    return;
  }
  add(h.rowDrops);
}
}

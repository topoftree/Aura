#define AURA_FLOW_TEST
#include "flow_core.h"
#include <cassert>
#include <thread>
#include <vector>
#include <cstdio>

using namespace aura_flow;
Data data{};
void reset(uint32_t epoch = 1) {
  std::memset(&data, 0, sizeof(data));
  std::memcpy(data.header.magic, "AUDFLOW1", 8);
  data.header.version = 1; data.header.bytes = FileBytes;
  data.header.ownerPid = 7; data.header.armed = 1; data.header.epoch = epoch;
  assert(initialize(&data, epoch, 7, 1000000, 1000000, 1000010, 134000000000000000ull));
}
uint64_t count() { uint64_t n = 0; for (auto& r : data.rows) n += r.calls; return n; }
int main() {
  assert(!initialize(nullptr, 1, 7, 1, 1, 1, 1));
  reset(); data.header.armed = 0;
  assert(!begin(&data, Present, 42, 1000100).data);
  data.header.armed = 1;
  auto first = begin(&data, WaitMapRead, 42, 1000100);
  finish(first, 1002100); assert(count() == 1);
  auto& r = data.rows[(42 * 17 + WaitMapRead) % Rows];
  assert(r.ticks == 2000 && r.bins[0].elapsedUs == 2000 && !(r.sequence & 1));
  finish(begin(&data, Present, 42, 11000000), 11000001); assert(count() == 1);
  finish(begin(&data, Present, 42, 10999999), 11000000);
  finish(begin(&data, Present, 42, 1000100), 999999);
  assert(data.header.clockDrops == 2 && count() == 1);
  auto old = begin(&data, Present, 42, 1000100);
  data.header.armed = 0; data.header.epoch = 2; data.header.armed = 1;
  assert(initialize(&data, 2, 7, 1000000, 2000000, 2000010, 134000000000000000ull));
  finish(old, 2001000); assert(count() == 1);
  finish(begin(&data, WaitMapRead, 42, 2000100), 2003100);
  assert(r.epoch == 2 && r.calls == 1 && r.ticks == 3000);
  reset();
  for (unsigned i = 0; i < Limit + 10; ++i) finish(begin(&data, Present, 1, 1000010), 1000020);
  assert(count() == Limit && data.header.operations == Limit);
  reset();
  for (unsigned tid = 1; tid <= Rows + 3; ++tid) finish(begin(&data, Present, tid, 1000010), 1000020);
  assert(count() == Rows && data.header.rowDrops == 3);
  reset();
  std::vector<std::thread> threads;
  for (unsigned tid = 1; tid <= 8; ++tid) threads.emplace_back([tid] {
    for (unsigned i = 0; i < 1000; ++i) finish(begin(&data, Present, tid, 1000010), 1000020);
  });
  for (auto& thread : threads) thread.join();
  assert(count() + data.header.budgetDrops + data.header.rowDrops == 8000);
  for (auto& row : data.rows) assert(!(row.sequence & 1));
  reset();
  for (unsigned i = 0; i < 1000; ++i) finish(begin(&data, Present, 1, 1000010), 10999999);
  auto& saturated = data.rows[(17 + Present) % Rows];
  assert(saturated.saturatedBins && saturated.bins[0].elapsedUs == UINT32_MAX);
  std::puts("flow protocol: off, epoch, timeout, budget, capacity, concurrency, saturation passed");
}

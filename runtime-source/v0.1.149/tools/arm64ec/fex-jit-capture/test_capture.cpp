// SPDX-License-Identifier: MIT
#include "JitCapture.h"
#include <cassert>
#include <thread>
#include <vector>
int main() {
  using namespace AuraJit;
  Control C {Magic, 7, 100, 10100, 1000, 1, 0, 0};
  Record R {}; R.Values[Slot] = 128; R.Values[Serial] = 3;
  { Writer W(&C, &R, 99); assert(!W); }
  { Writer W(&C, &R, 100, 6); assert(!W); }
  { Writer W(&C, &R, 100); assert(W); W.Get().Values[Compiles] = 4;
    Writer Busy(&C, &R, 101); assert(!Busy); }
  assert(R.Values[ContentionLifetime] == 1 && R.Values[Sequence] == 2);
  { Writer W(&C, &R, 10100); assert(!W); }
  assert(R.Values[Flags] & Expired);
  C.Epoch = 8;
  { Writer W(&C, &R, 101); assert(W && W.Get().Values[Compiles] == 0);
    assert(W.Get().Values[Slot] == 128 && W.Get().Values[Serial] == 3);
    W.Get().Values[Operations] = Budget; }
  { Writer W(&C, &R, 102); assert(!W); }
  assert(R.Values[Flags] & BudgetReached);
  C.Epoch = 9; C.Deadline = 10101;
  { Writer W(&C, &R, 103); assert(!W); } // forged window > 10 seconds
  C.Deadline = 0;
  { Writer W(&C, &R, 103); assert(!W); }
  C.Deadline = 10100;
  std::vector<std::thread> Threads;
  for (int I = 0; I < 4; ++I) Threads.emplace_back([&] {
    for (int J = 0; J < 10000; ++J) { Writer W(&C, &R, 104); if (W) ++W.Get().Values[Compiles]; }
  });
  for (auto& T : Threads) T.join();
  assert(!(R.Values[Sequence] & 1));
  assert(R.Values[Compiles] + R.Values[ContentionLifetime] - 1 == 40000);
  RepeatTable Table;
  assert(Table.Observe(0x123456789abcULL, 0, 1, 7) == 0);
  assert(Table.Observe(0x123456789abcULL, 0, 1, 7) == 1);
  assert(Table.Observe(0x123456789abcULL, 0, 2, 7) == 2);
  assert(Table.Observe(0x123456789abcULL, 1, 2, 7) == 0);
  assert(Table.Observe(0x123456789abcULL, 0, 2, 8) == 0);
  int Unknown = 0;
  for (uint64_t I = 0; I < 4096; ++I) {
    auto Result = Table.Observe(I * 16384, 0, 1, 9);
    assert(Result == 0 || Result == -1); Unknown += Result == -1;
  }
  assert(Unknown > 0);
}

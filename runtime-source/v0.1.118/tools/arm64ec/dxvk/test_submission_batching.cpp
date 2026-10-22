// SPDX-License-Identifier: MIT
// Compile against the actual upstream and patched tracker sources; no GPU model.
#include <algorithm>
#include <cassert>
#include <iostream>
#include "baseline/util_flush.h"
#include "patched/util_flush.h"

int main() {
  using T = dxvk::GpuFlushType;
  size_t baselineChecks = 0, syncChecks = 0, hintChecks = 0;
  const T types[] = {T::ExplicitFlush, T::ImplicitSynchronization,
                    T::ImplicitStrongHint, T::ImplicitWeakHint, T::None};
  // All upstream max-type modes, queue depths, sizes and missed-hint histories.
  for (unsigned max = 0; max <= 3; max++)
    for (unsigned pending = 0; pending <= 8; pending++)
      for (unsigned count = 0; count <= 64; count++)
        for (T prior : types)
          for (T next : types) {
            baseline::GpuFlushTracker original(static_cast<baseline::GpuFlushType>(max));
            dxvk::GpuFlushTracker disabled(static_cast<T>(max), false);
            original.notifyFlush(100, 12);
            disabled.notifyFlush(100, 12);
            const auto compare = [&](T type) {
              bool before = original.considerFlush(static_cast<baseline::GpuFlushType>(type),
                                                   100 + count, 12 - pending);
              bool after = disabled.considerFlush(type, 100 + count, 12 - pending);
              assert(before == after);
              assert(static_cast<unsigned>(original.getPendingType()) ==
                     static_cast<unsigned>(disabled.getPendingType()));
              assert(!disabled.shouldBatchHint(type, 100 + count, 12 - pending));
              baselineChecks++;
            };
            compare(prior);
            compare(next);
          }
  for (unsigned pending = 0; pending <= 8; pending++)
    for (unsigned count = 0; count <= 64; count++) {
      // Required flush/query/resource synchronization retains the upstream decision,
      // even after a missed speculative hint. The explicit execution path is untouched.
      for (T sync : {T::ExplicitFlush, T::ImplicitSynchronization}) {
        dxvk::GpuFlushTracker on(T::ImplicitStrongHint, true), off(T::ImplicitStrongHint);
        on.notifyFlush(100, 12); off.notifyFlush(100, 12);
        on.considerFlush(T::ImplicitStrongHint, 101, 12 - pending);
        off.considerFlush(T::ImplicitStrongHint, 101, 12 - pending);
        assert(on.considerFlush(sync, 100 + count, 12 - pending) ==
               off.considerFlush(sync, 100 + count, 12 - pending));
        assert(!on.shouldBatchHint(sync, 100 + count, 12 - pending));
        syncChecks++;
      }
      dxvk::GpuFlushTracker on(T::ImplicitStrongHint, true);
      on.notifyFlush(100, 12);
      bool defer = pending >= 2 && count > 0 && count < 6;
      assert(on.shouldBatchHint(T::ImplicitStrongHint, 100 + count, 12 - pending) == defer);
      assert(on.considerFlush(T::ImplicitStrongHint, 100 + count, 12 - pending) ==
             (count >= 3 && !defer));
      hintChecks++;
    }
  dxvk::GpuFlushTracker on(T::ImplicitStrongHint, true);
  on.notifyFlush(100, 12);
  assert(!on.hasBatchedHint());
  // Boundary retries must stop deferring as soon as queued work drains.
  on.considerFlush(T::ImplicitStrongHint, 101, 10);
  assert(on.getPendingType() == T::ImplicitStrongHint);
  assert(on.shouldBatchHint(on.getPendingType(), 101, 10));
  assert(on.hasBatchedHint());
  assert(!on.shouldBatchHint(on.getPendingType(), 101, 11));
  assert(!on.shouldBatchHint(on.getPendingType(), 101, 12));
  assert(!on.shouldBatchHint(on.getPendingType(), 101, 13));
  assert(!on.shouldBatchHint(T::None, 101, 10));
  on.notifyFlush(106, 13);
  assert(on.getPendingType() == T::None);
  assert(!on.shouldBatchHint(T::ImplicitStrongHint, 105, 10));
  std::cout << "{\"baseline_decisions\":" << baselineChecks
            << ",\"required_sync_cases\":" << syncChecks
            << ",\"hint_cases\":" << hintChecks
            << ",\"boundary_cases\":9,\"android_execution\":\"not run\"}\n";
}

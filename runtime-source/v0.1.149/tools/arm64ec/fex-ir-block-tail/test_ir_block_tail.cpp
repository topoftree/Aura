// SPDX-License-Identifier: MIT
// Exercise the real FEX IREmitter, using synthetic IR only. This is not a game benchmark.
#include "Interface/IR/IREmitter.h"
#include <FEXCore/Utils/ThreadPoolAllocator.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <random>
#include <vector>

using namespace FEXCore::IR;

static void require(bool ok) { if (!ok) std::abort(); }

class Emitter final : public IREmitter {
public:
  using IREmitter::IREmitter;
  void begin() {
    ReownOrClaimBuffer();
    // Make unused padding deterministic for the baseline/patched byte comparison.
    std::memset(reinterpret_cast<void*>(DualListData.DataBegin()), 0, DualListData.DataBackingSize());
    std::memset(reinterpret_cast<void*>(DualListData.ListBegin()), 0, DualListData.ListBackingSize());
    auto header = _IRHeader(Invalid(), 0x10000, 0, 0, 0, 0);
    auto block = CreateCodeNode(true, 0);
    header.first->Blocks = WrapNode(block);
    SetCurrentCodeBlock(block);
  }
  std::vector<Ref> blocks() {
    std::vector<Ref> out;
    auto view = ViewIR();
    for (auto [node, op] : view.GetBlocks()) out.push_back(node);
    return out;
  }
  void check(const std::vector<Ref>& expected) {
    auto actual = blocks();
    require(actual == expected);
    auto base = ViewIR().GetListData();
    for (size_t i = 0; i < actual.size(); ++i) {
      auto next = actual[i]->Header.Next.GetNode(base);
      require(next == (i + 1 == actual.size() ? Invalid() : actual[i + 1]));
      if (i) require(actual[i]->Header.Previous.GetNode(base) == actual[i - 1]);
    }
  }
  void save(std::ofstream& out) {
    auto view = ViewIR();
    uint64_t sizes[] = {view.GetDataSize(), view.GetListSize()};
    out.write(reinterpret_cast<char*>(sizes), sizeof(sizes));
    out.write(reinterpret_cast<char*>(view.GetData()), sizes[0]);
    out.write(reinterpret_cast<char*>(view.GetListData()), sizes[1]);
  }
  void selectCopiedFirst() { SetCurrentCodeBlock(blocks().front()); }
};

int main(int argc, char** argv) {
  require(argc == 2);
  std::ofstream out(argv[1], std::ios::binary);
  FEXCore::Utils::PooledAllocatorMalloc pool;
  Emitter ir(pool, false), copy(pool, false);
  uint64_t checks = 0;
  std::mt19937 random(0x43525932);
  // Append from a middle/current block, insert after tail, insert before tail,
  // remove tail through either removal API, and copy/reset/reuse the allocator.
  for (int pass = 0; pass < 16; ++pass) {
    ir.begin();
    auto expected = ir.blocks();
    for (int step = 0; step < 256; ++step) {
      auto index = random() % expected.size();
      ir.SetCurrentCodeBlock(expected[index]);
      auto cursor = ir.GetWriteCursor();
      switch (step % 6) {
      case 0: case 1: {
        Ref added = ir.CreateNewCodeBlockAtEnd();
        expected.push_back(added);
        break;
      }
      case 2: case 3: {
        if (step % 6 == 3) index = expected.size() - 1;
        Ref added = ir.CreateNewCodeBlockAfter(expected[index]);
        expected.insert(expected.begin() + index + 1, added);
        break;
      }
      case 4: case 5:
        if (expected.size() > 1) {
          ir.SetCurrentCodeBlock(expected.front());
          cursor = ir.GetWriteCursor();
          if (step % 6 == 4) ir.Remove(expected.back());
          else ir.RemovePostRA(expected.back());
          expected.pop_back();
        }
        break;
      }
      require(ir.GetWriteCursor() == cursor);
      ir.check(expected);
      ++checks;
    }
    ir.save(out);
    copy.begin();
    copy.CopyData(ir);
    copy.selectCopiedFirst();
    auto copied = copy.blocks();
    copied.push_back(copy.CreateNewCodeBlockAtEnd());
    copy.check(copied);
    copy.save(out);
    ++checks;
  }
  // Reproduce FULL instruction validation's exact linked-block construction:
  // create failure block at the end, insert success block after current, repeat.
  // Keep checks/branch destinations and IR ordering in the byte comparison.
  for (int size : {64, 1024, 4096, 8192}) {
    std::vector<long long> timings;
    for (int run = 0; run < 5; ++run) {
      ir.begin();
      const auto start = std::chrono::steady_clock::now();
      for (int i = 0; i < size; ++i) {
        Ref current = ir.GetCurrentBlock();
        auto address = ir._EntrypointOffset(OpSize::i32Bit, i * 4);
        auto changed = ir._ValidateCode(ir.Constant(0x12345678), address, 4, true);
        auto branch = ir._CondJump(changed);
        auto failure = ir.CreateNewCodeBlockAtEnd();
        ir.SetTrueJumpTarget(branch, failure);
        ir.SetCurrentCodeBlock(failure);
        ir._ThreadRemoveCodeEntry(ir._EntrypointOffset(OpSize::i32Bit, 0),
                                 ir._EntrypointOffset(OpSize::i32Bit, i * 4));
        auto success = ir.CreateNewCodeBlockAfter(current);
        ir.SetFalseJumpTarget(branch, success);
        ir.SetCurrentCodeBlock(success);
      }
      const auto end = std::chrono::steady_clock::now();
      timings.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
      require(ir.blocks().size() == 2 * size + 1);
      ++checks;
      if (!run) ir.save(out);
    }
    std::sort(timings.begin(), timings.end());
    std::printf("{\"instructions\":%d,\"median_ns\":%lld}\n", size, timings[2]);
  }
  require(out.good());
  std::printf("{\"checks\":%llu}\n", static_cast<unsigned long long>(checks));
}

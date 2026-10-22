// SPDX-License-Identifier: MIT
// Runs the production helper and upstream IntervalList; only OS queries/allocator are supplied by the host.
#include "ImageExecutableRanges.h"
#include <FEXCore/Utils/IntervalList.h>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace FEXCore::Allocator {
void* aligned_alloc(size_t Alignment, size_t Size) {
  void* Pointer {};
  if (::posix_memalign(&Pointer, std::max(Alignment, sizeof(void*)), Size)) throw std::bad_alloc();
  return Pointer;
}
void aligned_free(void* Pointer) { std::free(Pointer); }
}

using FEX::Windows::ImagePageRegion;
using FEX::Windows::MgsImagePagesEnabled;
using FEX::Windows::UpdateImageExecutableRanges;
using Ranges = FEXCore::IntervalList<uint64_t>;
static unsigned Checks;
static void Check(bool Value, const char* Name) {
  ++Checks;
  if (!Value) throw std::runtime_error(Name);
}

int main() {
  constexpr uint64_t Base = 0x6ffc830000, Entry = Base + 0x389c5, ImageSize = 0x78000;
  Ranges Execute, WriteExecute;
  // The shipped image-registration rule, applied to the measured section layout.
  Execute.Insert({Base + 0x1000, Base + 0x388e6});
  Check(Execute.Query(Base + 0x1100).Enclosed, "ordinary entry remains executable");
  Check(!Execute.Query(Entry).Enclosed, "old FEX range rejects captured entry");
  std::vector<ImagePageRegion> Pages {
    {Base, 0x1000, Base, true, false, false},
    {Base + 0x1000, 0x38000, Base, true, true, false},
    {Base + 0x39000, 0x25000, Base, true, false, false},
    {Base + 0x5e000, 0x11000, Base, true, false, true},
    {Base + 0x6f000, 0x9000, Base, true, false, false},
  };
  unsigned Queries = 0;
  auto Query = [&](uint64_t Address, ImagePageRegion& Out) {
    ++Queries;
    for (const auto& Region : Pages) if (Address >= Region.Base && Address - Region.Base < Region.Size) {
      Out = Region;
      return true;
    }
    return false;
  };
  Check(UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute, Query), "complete image walk");
  Check(Queries == Pages.size(), "one query per actual protection region");
  Check(Execute.Query(Entry).Enclosed, "captured entry is inside a committed executable page");
  Check(Execute.Query(Base + 0x38fff).Enclosed, "last executable byte included");
  Check(!Execute.Query(Base + 0x39000).Enclosed, "adjacent readonly page excluded");
  Check(!Execute.Query(Base).Enclosed, "image headers excluded");
  Check(!Execute.Query(Base + 0x60000).Enclosed, "writable data is not executable");
  Check(WriteExecute.Empty(), "readonly code does not acquire write status");
  Check(!Execute.Query(Base + ImageSize).Enclosed, "end-exclusive image bound");

  Pages[1].Writable = true;
  Check(UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute, Query) &&
        WriteExecute.Query(Entry).Enclosed, "actual RWX page retained for SMC tracking");
  Pages[1].Writable = false;
  Check(UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute, Query) &&
        WriteExecute.Empty(), "stale writable interval removed");
  Pages[1].Executable = false; // Same result as PAGE_GUARD/PAGE_NOACCESS in the Windows adapter.
  Check(UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute, Query) &&
        !Execute.Query(Entry).Enclosed, "non-executable or guarded page cannot be promoted");
  Pages[1].Executable = true;
  Pages[1].Committed = false;
  Check(UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute, Query) &&
        !Execute.Query(Entry).Enclosed, "uncommitted page excluded");
  Pages[1].Committed = true;

  Execute.Insert({Base + ImageSize, Base + ImageSize + 0x1000});
  Check(UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute, Query) &&
        Execute.Query(Base + ImageSize).Enclosed, "another allocation is unchanged");
  Check(!UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute,
        [](uint64_t, ImagePageRegion&) { return false; }) && !Execute.Query(Entry).Enclosed,
        "query failure clears stale image ranges");
  Pages[1].AllocationBase = Base + ImageSize;
  Check(!UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute, Query) &&
        !Execute.Query(Entry).Enclosed, "foreign allocation rejected");
  Pages[1].AllocationBase = Base;
  Check(!UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute,
        [&](uint64_t, ImagePageRegion& Out) { Out = {Base, 0, Base, true, true, true}; return true; }),
        "zero-size query cannot loop");
  Check(!UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute,
        [&](uint64_t Cursor, ImagePageRegion& Out) { Out = {Cursor + 0x1000, 0x1000, Base, true, true, true}; return true; }),
        "query cannot jump over unknown pages");
  Check(!UpdateImageExecutableRanges(Base, ImageSize, Execute, WriteExecute,
        [&](uint64_t, ImagePageRegion& Out) { Out = {Base, 1, Base, true, true, true}; return true; }),
        "non-page query rejected");
  Check(!UpdateImageExecutableRanges(Base, 0, Execute, WriteExecute, Query), "zero image rejected");
  Check(!UpdateImageExecutableRanges(Base + 1, ImageSize, Execute, WriteExecute, Query), "unaligned base rejected");
  Check(!UpdateImageExecutableRanges(Base, UINT64_MAX, Execute, WriteExecute, Query), "rounding overflow rejected");
  Check(!UpdateImageExecutableRanges(UINT64_MAX - 4095, 4096, Execute, WriteExecute, Query), "end overflow rejected");
  Check(UpdateImageExecutableRanges(Base, 0x388e6, Execute, WriteExecute, Query) &&
        Execute.Query(Entry).Enclosed && !Execute.Query(Base + 0x39000).Enclosed, "partial last image page rounded and bounded");
  Execute.Remove({Base, Base + ImageSize});
  WriteExecute.Remove({Base, Base + ImageSize});
  Check(!Execute.Query(Entry).Enclosed, "existing interval removal clears repaired page");
  Check(MgsImagePagesEnabled(true, "D:\\Games\\MgsGroundZeroes.exe"), "selected process enabled");
  Check(MgsImagePagesEnabled(true, "MGSGROUNDZEROES.EXE"), "case-insensitive executable");
  Check(!MgsImagePagesEnabled(false, "MgsGroundZeroes.exe"), "default and disabled route stay off");
  for (auto Name : {"steam.exe", "steamwebhelper.exe", "MgsTpp.exe", "setup.exe", "MgsGroundZeroes.exe.bak", ""})
    Check(!MgsImagePagesEnabled(true, Name), "other processes cannot inherit workaround");
  std::cout << Checks << " image range and process-scope checks passed\n";
}

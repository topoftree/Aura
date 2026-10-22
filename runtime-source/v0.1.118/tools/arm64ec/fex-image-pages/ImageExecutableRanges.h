// SPDX-License-Identifier: MIT
// Aura opt-in image-range compatibility for FEX-2610.
#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>
#include <string_view>

namespace FEX::Windows {
inline bool MgsImagePagesEnabled(bool Requested, std::string_view Path) {
  if (!Requested) return false;
  Path = Path.substr(Path.find_last_of("/\\") + 1);
  constexpr std::string_view Name = "mgsgroundzeroes.exe";
  if (Path.size() != Name.size()) return false;
  for (size_t i = 0; i < Name.size(); ++i) {
    const auto C = Path[i];
    if ((C >= 'A' && C <= 'Z' ? C + ('a' - 'A') : C) != Name[i]) return false;
  }
  return true;
}

struct ImagePageRegion {
  uint64_t Base {}, Size {}, AllocationBase {};
  bool Committed {}, Executable {}, Writable {};
};

// Query the loader's committed image pages, not the unrounded PE VirtualSize.
// The caller holds the interval lock and invalidates compiled code afterwards.
// Failed queries leave unknown pages excluded; no OS permissions are changed.
template<typename Ranges, typename Query>
bool UpdateImageExecutableRanges(uint64_t Base, uint64_t Size, Ranges& Execute, Ranges& WriteExecute, Query&& ReadRegion) {
  constexpr uint64_t Page = 4096;
  constexpr auto Max = std::numeric_limits<uint64_t>::max();
  if (!Size || (Base & (Page - 1)) || Size > Max - (Page - 1)) return false;
  Size = (Size + Page - 1) & ~(Page - 1);
  if (Base > Max - Size) return false;
  const auto End = Base + Size;
  Execute.Remove({Base, End});
  WriteExecute.Remove({Base, End});
  for (auto Cursor = Base; Cursor < End;) {
    ImagePageRegion Region;
    if (!ReadRegion(Cursor, Region) || Region.AllocationBase != Base || Region.Base > Cursor ||
        !Region.Size || Region.Base > Max - Region.Size || Region.Base + Region.Size <= Cursor ||
        (Region.Base & (Page - 1)) || (Region.Size & (Page - 1))) return false;
    const auto Next = std::min(End, Region.Base + Region.Size);
    if (Region.Committed && Region.Executable) {
      Execute.Insert({Cursor, Next});
      if (Region.Writable) WriteExecute.Insert({Cursor, Next});
    }
    Cursor = Next;
  }
  return true;
}
} // namespace FEX::Windows

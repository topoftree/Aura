// SPDX-License-Identifier: MIT
// Downstream Aura experiment; not an upstream FEX contribution.
#pragma once
#include <algorithm>
#include <cstdint>
#include <string_view>

namespace FEX::Windows {
// Aura verifies the complete executable before setting this launch-only request.
// Recheck the actual WOW64 process here so inherited environment cannot affect Steam,
// an installer, or a child with a different executable name.
inline bool CrysisInlineSmcRequested(const char* Requested, std::string_view Path) {
  if (!Requested || std::string_view(Requested) != "1") return false;
  const auto Name = Path.substr(Path.find_last_of("/\\") + 1);
  constexpr std::string_view Expected = "crysis2.exe";
  if (Name.size() != Expected.size()) return false;
  for (size_t i = 0; i < Name.size(); ++i) {
    const auto C = Name[i];
    if ((C >= 'A' && C <= 'Z' ? C + ('a' - 'A') : C) != Expected[i]) return false;
  }
  return true;
}

// Never remove page traps unless instruction validation is actually selected.
inline bool CrysisInlineSmcActive(bool Requested, bool FullChecks, bool MonoHacks) {
  return Requested && FullChecks && !MonoHacks;
}

inline bool CrysisFastSmcRequested(const char* Requested, bool InlineSmc) {
  return InlineSmc && Requested && std::string_view(Requested) == "1";
}

// Only the existing verified FULL mode opts in. Keep a launch-only rollback.
inline bool CrysisSharedSmcExitsEnabled(bool InlineSmc, const char* Requested) {
  return InlineSmc && (!Requested || std::string_view(Requested) == "1");
}

// FULL per-instruction validation makes speculative multiblocks much larger.
// Bound one translation unit rather than changing its checks or cache lifetime.
// Keep explicit smaller FEX_MAXINST choices. Unset/1 enables only the verified
// Crysis WOW64 mode; 0 (or another explicit value) restores the existing limit.
inline bool CrysisBoundedJitEnabled(bool InlineSmc, const char* Requested) {
  return InlineSmc && (!Requested || std::string_view(Requested) == "1");
}
inline int32_t CrysisJitInstructionBudget(bool Enabled, int32_t Existing) {
  if (!Enabled) return Existing;
  return Existing > 0 ? std::min(Existing, int32_t {512}) : 512;
}
} // namespace FEX::Windows

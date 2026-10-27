// SPDX-License-Identifier: MIT
#include "CrysisSmcPolicy.h"
#include <array>
#include <cassert>
#include <cstdio>

int main() {
  using namespace FEX::Windows;
  assert(CrysisInlineSmcRequested("1", "D:\\Game\\Bin32\\Crysis2.exe"));
  assert(CrysisInlineSmcRequested("1", "D:/Game/Bin32/CRYSIS2.EXE"));
  assert(CrysisInlineSmcRequested("1", "Crysis2.exe"));
  for (const char* value : std::array<const char*, 6>{nullptr, "", "0", "true", "01", "1 "})
    assert(!CrysisInlineSmcRequested(value, "Crysis2.exe"));
  for (const char* name : {"", "steam.exe", "Crysis.exe", "Crysis2Launcher.exe", "Crysis2.exe.bak", "Crysis2.exe/child.exe"})
    assert(!CrysisInlineSmcRequested("1", name));
  for (bool requested : {false, true})
    for (bool full : {false, true})
      for (bool mono : {false, true})
        assert(CrysisInlineSmcActive(requested, full, mono) == (requested && full && !mono));
  for (const char* value : std::array<const char*, 7>{nullptr, "", "0", "true", "01", "1 ", "1"}) {
    assert(!CrysisFastSmcRequested(value, false));
    assert(CrysisFastSmcRequested(value, true) == (value && std::string_view(value) == "1"));
  }
  for (const char* value : std::array<const char*, 7>{nullptr, "", "0", "true", "01", "1 ", "1"}) {
    assert(!CrysisBoundedJitEnabled(false, value));
    assert(CrysisBoundedJitEnabled(true, value) == (!value || std::string_view(value) == "1"));
    assert(!CrysisSharedSmcExitsEnabled(false, value));
    assert(CrysisSharedSmcExitsEnabled(true, value) == (!value || std::string_view(value) == "1"));
  }
  for (int32_t existing : {-1, 0, 1, 2, 128, 511, 512, 513, 5000, INT32_MAX}) {
    assert(CrysisJitInstructionBudget(false, existing) == existing);
    const auto limited = CrysisJitInstructionBudget(true, existing);
    assert(limited > 0 && limited <= 512);
    assert(existing <= 0 || limited <= existing);
    if (existing > 0 && existing <= 512) assert(limited == existing);
  }
  std::puts("Crysis policy: process gates, 14 budget gates, 14 shared-exit gates and 20 budget boundary cases passed");
}

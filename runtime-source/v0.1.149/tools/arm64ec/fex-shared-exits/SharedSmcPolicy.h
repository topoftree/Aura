// SPDX-License-Identifier: MIT
#pragma once
#include <string_view>

namespace FEX::Windows {
// The launch snapshot supplies only the selected game's basename. Recheck the
// actual process so inherited environments cannot enable Steam or child helpers.
inline bool SharedSmcExitsRequested(const char* Requested, const char* Expected, std::string_view Path) {
  if (!Requested || std::string_view(Requested) != "1" || !Expected) return false;
  const std::string_view Target {Expected};
  if (Target.size() < 5 || Target.size() > 1024 || Target.find_first_of("/\\\r\n") != Target.npos) return false;
  const auto Name = Path.substr(Path.find_last_of("/\\") + 1);
  if (Name.size() != Target.size()) return false;
  auto Lower = [](unsigned char C) { return C >= 'A' && C <= 'Z' ? C + ('a' - 'A') : C; };
  for (size_t i = 0; i < Name.size(); ++i) {
    if (Lower(Name[i]) != Lower(Target[i])) return false;
  }
  return true;
}
} // namespace FEX::Windows

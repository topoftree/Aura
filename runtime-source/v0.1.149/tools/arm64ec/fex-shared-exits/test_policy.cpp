#include "SharedSmcPolicy.h"
#include <cassert>
int main() {
  using FEX::Windows::SharedSmcExitsRequested;
  assert(SharedSmcExitsRequested("1", "bf3.exe", "D:\\games\\BF3.EXE"));
  assert(SharedSmcExitsRequested("1", "StardewModdingAPI.exe", "D:/game/StardewModdingAPI.exe"));
  assert(SharedSmcExitsRequested("1", "Crysis2.exe", "Crysis2.exe"));
  assert(!SharedSmcExitsRequested("0", "bf3.exe", "bf3.exe"));
  assert(!SharedSmcExitsRequested(nullptr, "bf3.exe", "bf3.exe"));
  assert(!SharedSmcExitsRequested("true", "bf3.exe", "bf3.exe"));
  assert(!SharedSmcExitsRequested("1", nullptr, "bf3.exe"));
  assert(!SharedSmcExitsRequested("1", "", ""));
  assert(!SharedSmcExitsRequested("1", "bf3.exe", "steam.exe"));
  assert(!SharedSmcExitsRequested("1", "bf3.exe", "bf3.exe.helper"));
  assert(!SharedSmcExitsRequested("1", "D:/bf3.exe", "D:/bf3.exe"));
  assert(!SharedSmcExitsRequested("1", "bf3.exe\n", "bf3.exe\n"));
}

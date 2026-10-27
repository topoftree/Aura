// SPDX-License-Identifier: MIT
// Emit the SAME production helper with the upstream ARM64 emitter for emulation.
#include "FastCodeValidation.h"
#include <array>
#include <cstdio>
#include <string>

int main(int argc, char** argv) {
  if (argc != 2) return 2;
  for (unsigned length = 1; length <= 8; ++length) {
    for (unsigned alias = 0; alias < 3; ++alias) {
      std::array<uint8_t, 128> code {};
      ARMEmitter::Emitter emitter {code.data(), code.size()};
      const auto dst = alias == 0 ? ARMEmitter::XReg::x22 : alias == 1 ? ARMEmitter::XReg::x20 : ARMEmitter::XReg::x21;
      FEXCore::CPU::EmitFastCodeValidation(emitter, dst, ARMEmitter::XReg::x20, ARMEmitter::XReg::x21,
                                         ARMEmitter::XReg::x0, ARMEmitter::XReg::x2, length);
      emitter.ret();
      const auto path = std::string(argv[1]) + "/check-" + std::to_string(length) + "-" + std::to_string(alias) + ".bin";
      auto* file = std::fopen(path.c_str(), "wb");
      if (!file) return 3;
      const auto size = emitter.GetCursorAddress<uint8_t*>() - code.data();
      const auto written = std::fwrite(code.data(), 1, size, file);
      std::fclose(file);
      if (written != static_cast<size_t>(size)) return 4;
    }
  }
}

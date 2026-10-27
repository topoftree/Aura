// SPDX-License-Identifier: MIT
// Downstream Aura experiment. Uses the upstream emitter; never widens a read.
#pragma once
#include <CodeEmitter/Emitter.h>

namespace FEXCore::CPU {
// Dst may alias Base or Expected. Acc and Scratch must be reserved JIT temporaries.
// The result is zero for equal bytes, nonzero for changed bytes. Preserve NZCV:
// the surrounding translated guest may still have live flags.
inline void EmitFastCodeValidation(ARMEmitter::Emitter& Emit, ARMEmitter::XRegister Dst,
                                   ARMEmitter::XRegister Base, ARMEmitter::XRegister Expected,
                                   ARMEmitter::XRegister Acc, ARMEmitter::XRegister Scratch,
                                   unsigned Length) {
  // Caller restricts this path to 1..8 bytes; longer instructions use upstream CRC.
  unsigned Offset = 0;
  for (unsigned Width : {8U, 4U, 2U, 1U}) {
    if (Length < Width) continue;
    const auto Target = Offset == 0 ? Acc : Scratch;
    switch (Width) {
    case 8: Emit.ldr(Target, Base, Offset); break;
    case 4: Emit.ldr(Target.W(), Base, Offset); break;
    case 2: Emit.ldrh(Target.W(), Base, Offset); break;
    case 1: Emit.ldrb(Target.W(), Base, Offset); break;
    }
    if (Offset != 0) Emit.orr(ARMEmitter::Size::i64Bit, Acc, Acc, Scratch, ARMEmitter::ShiftType::LSL, Offset * 8);
    Offset += Width;
    Length -= Width;
  }
  Emit.eor(ARMEmitter::Size::i64Bit, Dst, Acc, Expected);
}
} // namespace FEXCore::CPU

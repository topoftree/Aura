// SPDX-License-Identifier: MIT
#pragma once
#include <array>
#include <cstdint>

namespace AuraJit {
// Read-only startup snapshot. Bits: LSE, RCpc, RCpc2, SVE2, SVE, I8MM, DotProd.
enum AuditField { NativeKnown, NativeBits, WineKnown, WineBits, EffectiveBits, SveVectorBits, DiskFlags, MemoryBudget };
inline std::array<uint64_t, 8> ProcessAudit {};
// Exactly one lookup outcome; loading a valid blob into executable memory is counted separately.
enum CacheField { Disabled, AnonymousDisabled, DecodeRejected, RangeRejected, KeyMissing, ContentMismatch,
  ReadFailed, BlobRejected, RelocationRejected, RamBlobHit, StorageBlobHit, CodeLoaded,
  LoadAllocationFailed, ValidationOnly, RamBytes, RamPeakBytes, PendingBlobHit, ResidentBlobHit };
}

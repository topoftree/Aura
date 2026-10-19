/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_FEX_GUEST_PC_H
#define AURA_FEX_GUEST_PC_H
#include <stddef.h>
#include <stdint.h>

/* Read-only FEX 2610 ARM64EC layout. Inputs remain inside the disposable tracer;
 * neither x28 nor frame memory is part of the exported diagnostic packet. */
struct aura_fex_view {
    uint64_t pc, frame, jit_begin, jit_end, frame_begin, frame_end;
};
enum aura_fex_status {
    FEX_PC_UNSUPPORTED, FEX_PC_NOT_JIT, FEX_PC_UNREADABLE, FEX_PC_LAYOUT,
    FEX_PC_LIMIT, FEX_PC_CHANGED, FEX_PC_UNMAPPED, FEX_PC_MAPPED
};
typedef int (*aura_fex_read)(void *opaque, uint64_t address, void *buffer, size_t length);
/* Bounded metadata reads only. No stale State.rip fallback, target calls or writes.
 * The result is FEX's nearest guest mapping, not an exact x86 register snapshot. */
unsigned aura_fex_guest_pc(const struct aura_fex_view *view, aura_fex_read reader,
                           void *opaque, uint64_t *guest_pc);
#endif

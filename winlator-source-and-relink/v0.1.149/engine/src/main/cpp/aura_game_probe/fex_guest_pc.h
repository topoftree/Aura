/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_FEX_GUEST_PC_H
#define AURA_FEX_GUEST_PC_H
#include <stddef.h>
#include <stdint.h>

/* Read-only FEX 2610 ARM64EC/WOW64 layouts. Inputs remain inside the disposable tracer;
 * neither x28 nor frame memory is part of the exported diagnostic packet. */
struct aura_fex_view {
    uint64_t pc, frame, jit_begin, jit_end, frame_begin, frame_end;
    /* Set by the maps reader, not exported as raw mappings. */
    uint32_t frame_flags;
};
enum aura_fex_status {
    FEX_PC_UNSUPPORTED, FEX_PC_NOT_JIT, FEX_PC_UNREADABLE, FEX_PC_LAYOUT,
    FEX_PC_LIMIT, FEX_PC_CHANGED, FEX_PC_UNMAPPED, FEX_PC_MAPPED
};
/* Fixed reasons only: never export frame contents, unknown instructions or raw pointers. */
enum aura_fex_detail {
    FEX_DETAIL_NONE, FEX_DETAIL_PC_RANGE, FEX_DETAIL_FRAME_LAYOUT, FEX_DETAIL_FRAME_READ,
    FEX_DETAIL_BLOCK_RANGE, FEX_DETAIL_HEADER_READ, FEX_DETAIL_ENTRY_SIGNATURE,
    FEX_DETAIL_TAIL_LIMIT, FEX_DETAIL_TAIL_RANGE, FEX_DETAIL_PC_OUTSIDE_BLOCK,
    FEX_DETAIL_TAIL_READ, FEX_DETAIL_BLOCK_LIMIT, FEX_DETAIL_ENTRY_LIMIT,
    FEX_DETAIL_TAIL_FIELDS, FEX_DETAIL_TABLE_LIMIT, FEX_DETAIL_TABLE_READ,
    FEX_DETAIL_PAIR_ENCODING, FEX_DETAIL_HOST_DELTA, FEX_DETAIL_PAIR_RANGE,
    FEX_DETAIL_NO_MAPPING, FEX_DETAIL_PADDING, FEX_DETAIL_RECHECK_READ,
    FEX_DETAIL_TABLE_CHANGED, FEX_DETAIL_TAIL_CHANGED, FEX_DETAIL_HEADER_CHANGED,
    FEX_DETAIL_FRAME_CHANGED, FEX_DETAIL_GUEST_UNMAPPED, FEX_DETAIL_MAPPED,
    FEX_DETAIL_FRAME_ALIGNMENT, FEX_DETAIL_FRAME_UNMAPPED, FEX_DETAIL_FRAME_UNREADABLE,
    FEX_DETAIL_FRAME_RANGE, FEX_DETAIL_MAPPED_ENTRY, FEX_DETAIL_FRAME_MAPS_INCOMPLETE
};
enum aura_fex_frame_flags { FEX_FRAME_CHECKED = 1, FEX_FRAME_MAPPED = 2,
    FEX_FRAME_READABLE = 4, FEX_FRAME_EXECUTABLE = 8, FEX_FRAME_MAPS_INCOMPLETE = 16 };
const char *aura_fex_detail_name(unsigned detail);
typedef int (*aura_fex_read)(void *opaque, uint64_t address, void *buffer, size_t length);
/* Bounded metadata reads only. No stale State.rip fallback, target calls or writes.
 * The result is FEX's nearest guest mapping, not an exact x86 register snapshot. */
unsigned aura_fex_guest_pc(const struct aura_fex_view *view, aura_fex_read reader,
                           void *opaque, uint64_t *guest_pc);
unsigned aura_fex_guest_pc_detail(const struct aura_fex_view *view, aura_fex_read reader,
                                  void *opaque, uint64_t *guest_pc, unsigned *detail);
#endif

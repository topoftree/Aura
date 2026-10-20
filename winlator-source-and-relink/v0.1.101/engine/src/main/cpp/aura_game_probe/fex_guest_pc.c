/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Independent bounded reader for the public FEX 2610 JIT metadata format.
 * Layout references and pinned runtime hash: docs/validation/game-native-pc/README.md.
 * This does not include, patch or execute FEX code. All work uses fixed buffers.
 */
#include "fex_guest_pc.h"
#include <string.h>

#define FEX_BLOCK_LIMIT (1024U * 1024U)
#define FEX_TABLE_LIMIT 16384U
#define FEX_ENTRY_LIMIT 4096U
#define FEX_TAIL_SIZE 40U

static uint32_t le32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static uint64_t le64(const unsigned char *p) { return le32(p) | (uint64_t)le32(p + 4) << 32; }
static int contains(uint64_t begin, uint64_t end, uint64_t address, uint64_t bytes) {
    return begin < end && address >= begin && address < end && bytes <= end - address;
}
static uint64_t extend_bits(uint64_t value, unsigned bits) {
    uint64_t sign = UINT64_C(1) << (bits - 1);
    return (value ^ sign) - sign; /* Unsigned two's-complement delta; no signed-shift UB. */
}
static int add_delta(uint64_t base, uint64_t delta, uint64_t *result) {
    if (delta >> 63) {
        uint64_t magnitude = ~delta + 1;
        if (magnitude > base) return 0;
        *result = base - magnitude;
    } else {
        if (delta > UINT64_MAX - base) return 0;
        *result = base + delta;
    }
    return 1;
}

/* vl64pair is NOT LEB128. Large encodings have exact 0xc0/0xe0 tags.
 * Decode every pair within its buffer before looking at any payload bytes. */
static size_t decode_pair(const unsigned char *p, size_t available, uint64_t *host, uint64_t *guest) {
    if (!available) return 0;
    if (!(p[0] & 0x80)) {
        *host = ((p[0] & 15U) + 1) * 4; *guest = ((p[0] >> 4) & 7U) + 1; return 1;
    }
    if ((p[0] & 0xc0) == 0x80) {
        if (available < 2) return 0;
        *host = extend_bits(p[1], 8) * 4; *guest = extend_bits(p[0] & 63U, 6); return 2;
    }
    if (p[0] == 0xc0 && available >= 9) {
        *host = extend_bits(le32(p + 1), 32); *guest = extend_bits(le32(p + 5), 32); return 9;
    }
    if (p[0] == 0xe0 && available >= 17) {
        *host = le64(p + 1); *guest = le64(p + 9); return 17;
    }
    return 0;
}

unsigned aura_fex_guest_pc(const struct aura_fex_view *v, aura_fex_read read_memory,
                           void *opaque, uint64_t *guest_pc) {
    *guest_pc = 0;
    if (!contains(v->jit_begin, v->jit_end, v->pc, 4) || (v->pc & 3)) return FEX_PC_NOT_JIT;
    if ((v->frame & 63) || !contains(v->frame_begin, v->frame_end, v->frame, 8)) return FEX_PC_LAYOUT;
    unsigned char frame[8], header[12], tail[FEX_TAIL_SIZE];
    if (!read_memory(opaque, v->frame, frame, sizeof(frame))) return FEX_PC_UNREADABLE;
    uint64_t block = le64(frame);
    if ((block & 15) || !contains(v->jit_begin, v->jit_end, block, sizeof(header))) return FEX_PC_LAYOUT;
    if (!read_memory(opaque, block, header, sizeof(header))) return FEX_PC_UNREADABLE;
    /* First entry: adr x10, header (-4); str x10, [x28]. Reject other ABIs/layouts. */
    if (le32(header + 4) != UINT32_C(0x10ffffea) || le32(header + 8) != UINT32_C(0xf900038a)) return FEX_PC_LAYOUT;
    uint64_t tail_offset = le32(header);
    if (tail_offset > FEX_BLOCK_LIMIT) return FEX_PC_LIMIT;
    if (tail_offset < 16 || (tail_offset & 7) ||
        !contains(v->jit_begin, v->jit_end, block, tail_offset + sizeof(tail))) return FEX_PC_LAYOUT;
    /* Before the entry's str has executed, x28 may still identify the previous block. */
    if (v->pc < block + 12 || v->pc >= block + tail_offset) return FEX_PC_LAYOUT;
    if (!read_memory(opaque, block + tail_offset, tail, sizeof(tail))) return FEX_PC_UNREADABLE;
    uint64_t size = le64(tail), rip = le64(tail + 8);
    uint32_t count = le32(tail + 24), table_offset = le32(tail + 28);
    if (size > FEX_BLOCK_LIMIT || count > FEX_ENTRY_LIMIT) return FEX_PC_LIMIT;
    if ((size & 15) || size < tail_offset + FEX_TAIL_SIZE || table_offset != FEX_TAIL_SIZE ||
        !count || tail[36] > 1 || !rip || rip >= UINT64_C(0x800000000000) ||
        !contains(v->jit_begin, v->jit_end, block, size)) return FEX_PC_LAYOUT;
    size_t bytes = (size_t)(size - tail_offset - table_offset);
    if (bytes > FEX_TABLE_LIMIT) return FEX_PC_LIMIT;
    unsigned char table[FEX_TABLE_LIMIT], check[FEX_TABLE_LIMIT];
    uint64_t table_address = block + tail_offset + table_offset;
    if (!bytes || !read_memory(opaque, table_address, table, bytes)) return FEX_PC_UNREADABLE;
    uint64_t host = block, candidate = 0;
    size_t used = 0;
    int passed_pc = 0;
    for (uint32_t i = 0; i < count; i++) {
        uint64_t host_delta, guest_delta, next_host, next_rip;
        size_t n = decode_pair(table + used, bytes - used, &host_delta, &guest_delta);
        if (!n || host_delta >> 63 || (host_delta & 3) ||
            !add_delta(host, host_delta, &next_host) || !add_delta(rip, guest_delta, &next_rip) ||
            next_host < block + 4 || next_host >= block + tail_offset ||
            !next_rip || next_rip >= UINT64_C(0x800000000000)) return FEX_PC_LAYOUT;
        /* Source entries are monotonic in host position; guest deltas can be negative.
         * Equal host positions are allowed for eliminated guest instructions. */
        if (!passed_pc && next_host <= v->pc) candidate = next_rip;
        else passed_pc = 1;
        host = next_host; rip = next_rip; used += n;
    }
    if (!candidate || bytes - used >= 16) return FEX_PC_LAYOUT; /* At most 15 alignment bytes. */
    /* Only the selected leader is stopped. Reject observable mutation by other threads;
     * this double-read is a consistency check, not a claim of a whole-process snapshot. */
    if (!read_memory(opaque, table_address, check, bytes)) return FEX_PC_UNREADABLE;
    if (memcmp(table, check, bytes)) return FEX_PC_CHANGED;
    if (!read_memory(opaque, block + tail_offset, check, sizeof(tail))) return FEX_PC_UNREADABLE;
    if (memcmp(tail, check, sizeof(tail))) return FEX_PC_CHANGED;
    if (!read_memory(opaque, block, check, sizeof(header))) return FEX_PC_UNREADABLE;
    if (memcmp(header, check, sizeof(header))) return FEX_PC_CHANGED;
    if (!read_memory(opaque, v->frame, check, sizeof(frame))) return FEX_PC_UNREADABLE;
    if (memcmp(frame, check, sizeof(frame))) return FEX_PC_CHANGED;
    *guest_pc = candidate;
    return FEX_PC_MAPPED;
}

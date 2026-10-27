/* Aura integration changes for 0.1.149, 2026-10-27.
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
    unsigned detail;
    return aura_fex_guest_pc_detail(v, read_memory, opaque, guest_pc, &detail);
}

const char *aura_fex_detail_name(unsigned detail) {
    static const char *const names[] = { "NOT_REQUESTED", "PC_RANGE", "FRAME_LAYOUT", "FRAME_READ",
        "BLOCK_RANGE", "HEADER_READ", "ENTRY_SIGNATURE", "TAIL_LIMIT", "TAIL_RANGE", "PC_OUTSIDE_BLOCK",
        "TAIL_READ", "BLOCK_LIMIT", "ENTRY_LIMIT", "TAIL_FIELDS", "TABLE_LIMIT", "TABLE_READ",
        "PAIR_ENCODING", "HOST_DELTA", "PAIR_RANGE", "NO_MAPPING", "PADDING", "RECHECK_READ",
        "TABLE_CHANGED", "TAIL_CHANGED", "HEADER_CHANGED", "FRAME_CHANGED", "GUEST_UNMAPPED", "MAPPED",
        "FRAME_ALIGNMENT", "FRAME_UNMAPPED", "FRAME_UNREADABLE", "FRAME_RANGE", "MAPPED_ENTRY", "FRAME_MAPS_INCOMPLETE" };
    return detail < sizeof(names) / sizeof(*names) ? names[detail] : "UNKNOWN";
}

#define RESULT(status, reason) do { *detail = FEX_DETAIL_##reason; return FEX_PC_##status; } while (0)
static int entry_signature(const unsigned char header[12]) {
    uint32_t adr = le32(header + 4), str = le32(header + 8);
    return (adr == UINT32_C(0x10ffffea) && str == UINT32_C(0xf900038a)) ||
           (adr == UINT32_C(0x10ffffe0) && str == UINT32_C(0xf9000380));
}

static unsigned decode_block(const struct aura_fex_view *v, aura_fex_read read_memory,
                              void *opaque, uint64_t *guest_pc, unsigned *detail, uint64_t entry_block,
                              const unsigned char *entry_header) {
    *detail = FEX_DETAIL_NONE;
    *guest_pc = 0;
    if (!contains(v->jit_begin, v->jit_end, v->pc, 4) || (v->pc & 3)) RESULT(NOT_JIT, PC_RANGE);
    unsigned char frame[8], header[12], tail[FEX_TAIL_SIZE];
    uint64_t block = entry_block;
    if (!block) {
        if (v->frame & 63) RESULT(LAYOUT, FRAME_ALIGNMENT);
        if (v->frame_flags & FEX_FRAME_CHECKED) {
            if (v->frame_flags & FEX_FRAME_MAPS_INCOMPLETE) RESULT(UNREADABLE, FRAME_MAPS_INCOMPLETE);
            if (!(v->frame_flags & FEX_FRAME_MAPPED)) RESULT(LAYOUT, FRAME_UNMAPPED);
            if (!(v->frame_flags & FEX_FRAME_READABLE)) RESULT(UNREADABLE, FRAME_UNREADABLE);
        }
        if (!contains(v->frame_begin, v->frame_end, v->frame, 8)) RESULT(LAYOUT, FRAME_RANGE);
        if (!read_memory(opaque, v->frame, frame, sizeof(frame))) RESULT(UNREADABLE, FRAME_READ);
        block = le64(frame);
    }
    if ((block & 15) || !contains(v->jit_begin, v->jit_end, block, sizeof(header))) RESULT(LAYOUT, BLOCK_RANGE);
    if (!read_memory(opaque, block, header, sizeof(header))) RESULT(UNREADABLE, HEADER_READ);
    if (entry_header && memcmp(entry_header, header, sizeof(header))) RESULT(CHANGED, HEADER_CHANGED);
    /* FEX 2610 EmitEntryPoint stores the same header through TMP1 in both
     * Windows backends: x10 for ARM64EC, x0 for WOW64. Require a complete
     * matching pair (adr TMP1, header (-4); str TMP1, [x28]); accepting only
     * one ABI made every otherwise valid WOW64 block fail as LAYOUT.
     * Do not mask registers or accept a mixed/unknown entry sequence. */
    if (!entry_signature(header)) RESULT(LAYOUT, ENTRY_SIGNATURE);
    uint64_t tail_offset = le32(header);
    if (tail_offset > FEX_BLOCK_LIMIT) RESULT(LIMIT, TAIL_LIMIT);
    if (tail_offset < 16 || (tail_offset & 7) ||
        !contains(v->jit_begin, v->jit_end, block, tail_offset + sizeof(tail))) RESULT(LAYOUT, TAIL_RANGE);
    /* Before the entry's str has executed, x28 may still identify the previous block. */
    if (v->pc < block + (entry_block ? 4 : 12) || v->pc >= block + tail_offset) RESULT(LAYOUT, PC_OUTSIDE_BLOCK);
    if (!read_memory(opaque, block + tail_offset, tail, sizeof(tail))) RESULT(UNREADABLE, TAIL_READ);
    uint64_t size = le64(tail), rip = le64(tail + 8);
    uint32_t count = le32(tail + 24), table_offset = le32(tail + 28);
    if (size > FEX_BLOCK_LIMIT) RESULT(LIMIT, BLOCK_LIMIT);
    if (count > FEX_ENTRY_LIMIT) RESULT(LIMIT, ENTRY_LIMIT);
    if ((size & 15) || size < tail_offset + FEX_TAIL_SIZE || table_offset != FEX_TAIL_SIZE ||
        !count || tail[36] > 1 || !rip || rip >= UINT64_C(0x800000000000) ||
        !contains(v->jit_begin, v->jit_end, block, size)) RESULT(LAYOUT, TAIL_FIELDS);
    size_t bytes = (size_t)(size - tail_offset - table_offset);
    if (bytes > FEX_TABLE_LIMIT) RESULT(LIMIT, TABLE_LIMIT);
    unsigned char table[FEX_TABLE_LIMIT], check[FEX_TABLE_LIMIT];
    uint64_t table_address = block + tail_offset + table_offset;
    if (!bytes || !read_memory(opaque, table_address, table, bytes)) RESULT(UNREADABLE, TABLE_READ);
    uint64_t host = block, candidate = 0;
    size_t used = 0;
    int passed_pc = 0;
    for (uint32_t i = 0; i < count; i++) {
        uint64_t host_delta, guest_delta, next_host, next_rip;
        size_t n = decode_pair(table + used, bytes - used, &host_delta, &guest_delta);
        if (!n) RESULT(LAYOUT, PAIR_ENCODING);
        if (host_delta >> 63 || (host_delta & 3)) RESULT(LAYOUT, HOST_DELTA);
        if (!add_delta(host, host_delta, &next_host) || !add_delta(rip, guest_delta, &next_rip) ||
            next_host < block + 4 || next_host >= block + tail_offset ||
            !next_rip || next_rip >= UINT64_C(0x800000000000)) RESULT(LAYOUT, PAIR_RANGE);
        /* Source entries are monotonic in host position; guest deltas can be negative.
         * Equal host positions are allowed for eliminated guest instructions. */
        if (!passed_pc && next_host <= v->pc) candidate = next_rip;
        else passed_pc = 1;
        host = next_host; rip = next_rip; used += n;
    }
    if (!candidate) RESULT(LAYOUT, NO_MAPPING);
    if (bytes - used >= 16) RESULT(LAYOUT, PADDING); /* At most 15 alignment bytes. */
    /* Only the selected leader is stopped. Reject observable mutation by other threads;
     * this double-read is a consistency check, not a claim of a whole-process snapshot. */
    if (!read_memory(opaque, table_address, check, bytes)) RESULT(UNREADABLE, RECHECK_READ);
    if (memcmp(table, check, bytes)) RESULT(CHANGED, TABLE_CHANGED);
    if (!read_memory(opaque, block + tail_offset, check, sizeof(tail))) RESULT(UNREADABLE, RECHECK_READ);
    if (memcmp(tail, check, sizeof(tail))) RESULT(CHANGED, TAIL_CHANGED);
    if (!read_memory(opaque, block, check, sizeof(header))) RESULT(UNREADABLE, RECHECK_READ);
    if (memcmp(header, check, sizeof(header))) RESULT(CHANGED, HEADER_CHANGED);
    if (!entry_block) {
        if (!read_memory(opaque, v->frame, check, sizeof(frame))) RESULT(UNREADABLE, RECHECK_READ);
        if (memcmp(frame, check, sizeof(frame))) RESULT(CHANGED, FRAME_CHANGED);
    }
    *guest_pc = candidate;
    if (entry_block) RESULT(MAPPED, MAPPED_ENTRY);
    RESULT(MAPPED, MAPPED);
}

unsigned aura_fex_guest_pc_detail(const struct aura_fex_view *v, aura_fex_read read_memory,
                                  void *opaque, uint64_t *guest_pc, unsigned *detail) {
    unsigned result = decode_block(v, read_memory, opaque, guest_pc, detail, 0, NULL);
    /* At adr/str the frame header can still belong to the previous block. Only
     * inspect the single aligned header immediately under the actual PC; never
     * scan backward or use stale State.rip. Full metadata and rechecks still apply. */
    switch (*detail) {
        case FEX_DETAIL_FRAME_ALIGNMENT: case FEX_DETAIL_FRAME_UNMAPPED:
        case FEX_DETAIL_FRAME_UNREADABLE: case FEX_DETAIL_FRAME_RANGE:
        case FEX_DETAIL_FRAME_MAPS_INCOMPLETE:
        case FEX_DETAIL_FRAME_READ: case FEX_DETAIL_BLOCK_RANGE:
        case FEX_DETAIL_HEADER_READ: case FEX_DETAIL_PC_OUTSIDE_BLOCK: break;
        default: return result;
    }
    uint64_t offset = v->pc & 15;
    if (offset != 4 && offset != 8) return result;
    uint64_t block = v->pc - offset;
    unsigned char header[12];
    if (!block || !contains(v->jit_begin, v->jit_end, block, sizeof(header)) ||
        !read_memory(opaque, block, header, sizeof(header)) || !entry_signature(header)) return result;
    return decode_block(v, read_memory, opaque, guest_pc, detail, block, header);
}

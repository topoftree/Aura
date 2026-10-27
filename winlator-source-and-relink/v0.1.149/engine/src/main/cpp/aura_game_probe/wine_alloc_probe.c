/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#include "wine_alloc_probe.h"
#include <string.h>

static int alloc_contains(uint64_t start, uint64_t end, uint64_t at, size_t size) {
    return start < end && at >= start && at < end && size <= end - at;
}
static int alloc_words(const unsigned char *bytes, const uint32_t *words, size_t count) {
    for (size_t i = 0; i < count; i++, bytes += 4) {
        uint32_t word = (uint32_t)bytes[0] | (uint32_t)bytes[1] << 8 |
                        (uint32_t)bytes[2] << 16 | (uint32_t)bytes[3] << 24;
        if (word != words[i]) return 0;
    }
    return 1;
}

/* Exact instruction signatures of the bundled ntdll.dll ARM64EC view.
 * SHA-256 f207a3462e98f8a5a31ca3846751cd57a61097447cfe4e3a434daa2aa278033e.
 * The independent oracle checks these against the PE, not duplicated fixtures. */
static const uint32_t allocation_checker[] = {
    0xf9403250, 0xd352fd71, 0xf941b610, 0xd34cfd69, 0xf8717a10, 0x9ac92610,
    0x370005b0, 0xf2400d7f, 0x540003e1, 0xf9400170, 0x58000551, 0xeb11021f,
    0x54000141, 0xf9400570, 0x58000511, 0xca110211, 0xf2403e3f, 0x54000401,
    0x9100396b, 0xd350fe09, 0x8b29c16b, 0xd65f03c0, 0x18000451, 0x6b11021f,
    0x540001e1, 0xd360ae09, 0xf9400570, 0x580003f1, 0xeb11021f, 0x54000281,
    0xf9400970, 0x580003b1, 0xeb11021f, 0x54000201, 0xf104093f, 0x540001c2,
    0x102dcb4b, 0xf869796b, 0xd65f03c0, 0x39400170, 0x7103fe1f, 0x54000101,
    0x39400570, 0x7100961f, 0x540000a1, 0xb8402169, 0x91001970, 0xf869ca0b,
    0x17ffffd0, 0xaa0b03e9, 0xaa0a03eb, 0xd65f03c0,
};
static const uint32_t allocation_notify[] = {
    0xa9bc53f3, 0xa9015bf5, 0xa9026bf9, 0xa9037bfb, 0xd12243ff, 0x2a0503f3,
    0x2a0403f6, 0xaa0303f4, 0xaa0203fa, 0xaa0103f5, 0xaa0003f9, 0x97ffb586,
    0xf94bc648, 0x39400509, 0x340001a9, 0xaa1903e0, 0xaa1503e1, 0xaa1a03e2,
    0xaa1403e3, 0x2a1603e4, 0x2a1303e5, 0x912243ff, 0xa9437bfb, 0xa9426bf9,
    0xa9415bf5, 0xa8c453f3, 0x14000067, 0x52800029, 0x39000509, 0x121402c8,
    0xf94002a2, 0xf100005f, 0x7a400904, 0x321302c8, 0x1a8802d6, 0x72001c1f,
    0x540004e0, 0x90000488, 0xf000045b, 0xf946bd0b, 0xb400018b, 0xf9438f68,
    0xf9400281, 0xf00000ea, 0x913cd14a, 0xd63f0100, 0xaa0203e0, 0x2a1603e2,
    0x2a1303e3, 0x2a1f03e4, 0x2a1f03e5, 0xd63f0160, 0xaa1903e0, 0xaa1503e1,
    0xaa1a03e2, 0xaa1403e3, 0x2a1603e4, 0x2a1303e5, 0x94000047, 0x90000488,
    0x2a0003fa, 0xf946bd0b, 0xb400056b, 0xf9438f68, 0xf9400281, 0xf00000ea,
    0x913cd14a, 0xf94002a0, 0xd63f0100, 0x2a1603e2, 0x2a1303e3, 0x52800024,
    0x2a1a03e5, 0xd63f0160, 0x1400001f,
};

void aura_wine_alloc_probe(const struct aura_alloc_view *v, aura_ec_read read_memory,
                           void *opaque, struct aura_alloc_result *out) {
    memset(out, 0, sizeof(*out));
    /* The PE is mapped on a 64-KiB boundary. LR identifies exactly one of the
     * pre/post checker calls. The checker itself never modifies x1/x19/x22/x26.
     * At POST w26 holds the syscall result; at PRE it still holds ZeroBits. */
    unsigned stage;
    uint64_t base;
    if ((v->lr & 0xffff) == 0x67d4 && v->lr >= 0xc67d4) {
        base = v->lr - 0xc67d4; stage = ALLOC_PRE;
    } else if ((v->lr & 0xffff) == 0x6830 && v->lr >= 0xc6830) {
        base = v->lr - 0xc6830; stage = ALLOC_POST;
    } else return;
    if (base < 0x10000 || base >= UINT64_C(0x800000000000) - 0xc6848 ||
        (v->pc & 3) || v->pc < base + 0xc4110 || v->pc >= base + 0xc41e0 ||
        !alloc_contains(v->code_begin, v->code_end, base + 0xc4110, sizeof(allocation_checker)) ||
        !alloc_contains(v->code_begin, v->code_end, base + 0xc671c, sizeof(allocation_notify))) return;
    unsigned char checker[sizeof(allocation_checker)], notify[sizeof(allocation_notify)];
    unsigned char again[sizeof(allocation_notify)];
    if (!read_memory(opaque, base + 0xc4110, checker, sizeof(checker)) ||
        !read_memory(opaque, base + 0xc671c, notify, sizeof(notify))) {
        out->state = ALLOC_UNREADABLE; return;
    }
    if (!alloc_words(checker, allocation_checker, sizeof(allocation_checker) / 4) ||
        !alloc_words(notify, allocation_notify, sizeof(allocation_notify) / 4)) return;
    if (!read_memory(opaque, base + 0xc4110, again, sizeof(checker))) {
        out->state = ALLOC_UNREADABLE; return;
    }
    if (memcmp(checker, again, sizeof(checker))) { out->state = ALLOC_CHANGED; return; }
    if (!read_memory(opaque, base + 0xc671c, again, sizeof(notify))) {
        out->state = ALLOC_UNREADABLE; return;
    }
    if (memcmp(notify, again, sizeof(notify))) { out->state = ALLOC_CHANGED; return; }
    out->size = v->size; out->type = (uint32_t)v->type; out->protect = (uint32_t)v->protect;
    out->status = stage == ALLOC_POST ? (uint32_t)v->status : 0;
    out->state = stage;
}

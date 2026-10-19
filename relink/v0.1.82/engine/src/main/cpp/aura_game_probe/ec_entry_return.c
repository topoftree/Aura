/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
/* SPDX-License-Identifier: MIT */
#include "ec_entry_return.h"
#include <string.h>

static int ec_contains(uint64_t begin, uint64_t end, uint64_t address, size_t bytes) {
    return begin < end && address >= begin && address < end && bytes <= end - address;
}
static uint32_t ec_le32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static int ec_words(const unsigned char *p, const uint32_t *words, size_t count) {
    for (size_t i = 0; i < count; i++) if (ec_le32(p + i * 4) != words[i]) return 0;
    return 1;
}

unsigned aura_ec_entry_return(const struct aura_ec_view *v, aura_ec_read read_memory,
                              void *opaque, uint64_t *result) {
    *result = 0;
    /* Exact shipped native VirtualAlloc prologue through its NtAllocateVirtualMemory
     * call (kernelbase RVA b7388..b73b8). PC must be AT that call, not elsewhere in
     * this function, an import thunk, a patched entry, or a previous stack frame. */
    static const uint32_t allocation[] = {
        0xd10083ff, 0xf9000bf3, 0xf9000ffe, 0xa90007e0, 0xb00005e8,
        0x2a0303e5, 0xf9407d08, 0x2a0203e4, 0x910003e1, 0x910023e3,
        0x92800000, 0xaa1f03e2, 0xd63f0100
    };
    /* Entry thunk at kernelbase RVA f15bc..f1600. It saves the incoming LR at
     * FP+8, sets FP, and calls the native function with blr x9. Verify the return
     * sequence too; this is not a generic "read whatever FP points at" unwinder. */
    static const uint32_t bridge[] = {
        0xadba9fe6, 0xad0127e8, 0xad022fea, 0xad0337ec, 0xad043fee,
        0xa90a7bfd, 0x910283fd, 0xd63f0120,
        0xf00004a8, 0xf943dd01, 0xaa0003e8, 0xa94a7bfd, 0xad443fee,
        0xad4337ec, 0xad422fea, 0xad4127e8, 0xacc59fe6, 0xd61f0020
    };
    if ((v->pc | v->lr) & 3 || v->pc < 48 || v->lr < 32 ||
        !ec_contains(v->pc_begin, v->pc_end, v->pc - 48, sizeof(allocation)) ||
        !ec_contains(v->lr_begin, v->lr_end, v->lr - 32, sizeof(bridge))) return EC_RETURN_NOT_APPLICABLE;
    unsigned char api[sizeof(allocation)], entry[sizeof(bridge)], slot[8], check[sizeof(bridge)];
    if (!read_memory(opaque, v->pc - 48, api, sizeof(api))) return EC_RETURN_UNREADABLE;
    if (!ec_words(api, allocation, sizeof(allocation) / sizeof(*allocation))) return EC_RETURN_NOT_APPLICABLE;
    if (!read_memory(opaque, v->lr - 32, entry, sizeof(entry))) return EC_RETURN_UNREADABLE;
    if (!ec_words(entry, bridge, sizeof(bridge) / sizeof(*bridge))) return EC_RETURN_LAYOUT;
    /* Actual instruction execution verifies this relation for both FEX stack
     * alignment paths. Require one readable, non-executable stack mapping. */
    if ((v->sp | v->fp) & 15 || v->sp > UINT64_MAX - 0xd0 || v->fp != v->sp + 0xc0 ||
        !ec_contains(v->stack_begin, v->stack_end, v->sp, 0xd0)) return EC_RETURN_LAYOUT;
    if (!read_memory(opaque, v->fp + 8, slot, sizeof(slot))) return EC_RETURN_UNREADABLE;
    uint64_t address = ec_le32(slot) | (uint64_t)ec_le32(slot + 4) << 32;
    if (address < 0x10000 || address >= UINT64_C(0x800000000000)) return EC_RETURN_LAYOUT;
    /* Only this thread is stopped. Reject observable modification before exporting
     * the one interpreted return address; no argument, old FP or other stack data. */
    if (!read_memory(opaque, v->fp + 8, check, sizeof(slot))) return EC_RETURN_UNREADABLE;
    if (memcmp(slot, check, sizeof(slot))) return EC_RETURN_CHANGED;
    if (!read_memory(opaque, v->lr - 32, check, sizeof(entry))) return EC_RETURN_UNREADABLE;
    if (memcmp(entry, check, sizeof(entry))) return EC_RETURN_CHANGED;
    if (!read_memory(opaque, v->pc - 48, check, sizeof(api))) return EC_RETURN_UNREADABLE;
    if (memcmp(api, check, sizeof(api))) return EC_RETURN_CHANGED;
    *result = address;
    return EC_RETURN_CAPTURED;
}

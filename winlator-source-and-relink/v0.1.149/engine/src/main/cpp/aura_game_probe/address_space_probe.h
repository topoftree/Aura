/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_ADDRESS_SPACE_PROBE_H
#define AURA_ADDRESS_SPACE_PROBE_H
#include "ec_entry_return.h"

enum aura_va_state {
    VA_NOT_REQUESTED, VA_CAPTURED, VA_NOT_FOUND, VA_UNREADABLE, VA_LAYOUT,
    VA_CHANGED, VA_INVALID, VA_NOT_APPLICABLE, VA_LIMIT
};
enum aura_va_kernel_state {
    VA_KERNEL_UNKNOWN, VA_KERNEL_EXACT, VA_KERNEL_OCCUPIED,
    VA_KERNEL_REDIRECTED, VA_KERNEL_REJECTED, VA_KERNEL_CLEANUP_FAILED
};
struct aura_va_snapshot {
    uint64_t page_size;
    uint32_t kernel_state[3], kernel_errno[3];
    uint32_t wine_state, game_state;
    /* address, user, working-set, host limits; single-allocation cap (0 = unlimited). */
    uint64_t wine[5];
    /* original arena, pool start, next page, reserve cursor, pool end, shared error. */
    uint64_t game[6];
};

/* Fixed layout of the shipped ee8b621d native ntdll. Offsets never apply to an
 * unknown binary. Name + ELF/code fingerprints select this observational ABI;
 * fingerprints detect layout drift, they do not authenticate hostile code. */
#define AURA_VA_NTDLL_NAME "libaura_ee8b621d7ecb2bc6d0900630.so"
void aura_va_wine(uint64_t base, aura_ec_read read_memory, void *, struct aura_va_snapshot *);
/* Only the caller's selected SouthPark_TFBW.exe, with four matching code slices.
 * No disk/game edits, argument/register writes or DLL/DRM hooks. */
void aura_va_southpark(aura_ec_read read_memory, void *, struct aura_va_snapshot *);
/* Disposable observer process only. Three PROT_NONE pages, no MAP_FIXED and no
 * target mapping. EXACT/OCCUPIED prove only these addresses, not large-hole availability. */
void aura_va_kernel(uint64_t page_size, struct aura_va_snapshot *);
const char *aura_va_state_name(unsigned);
const char *aura_va_kernel_name(unsigned);
#endif

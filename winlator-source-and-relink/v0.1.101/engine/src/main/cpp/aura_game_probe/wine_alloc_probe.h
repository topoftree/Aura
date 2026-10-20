/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_WINE_ALLOC_PROBE_H
#define AURA_WINE_ALLOC_PROBE_H
#include "ec_entry_return.h"

/* Private GETREGSET inputs. Export interpreted allocation fields only after
 * exact code/range validation; no argument pointers or raw registers. */
struct aura_alloc_view {
    uint64_t pc, lr, size, type, protect, status, code_begin, code_end;
};
enum aura_alloc_state { ALLOC_NOT_APPLICABLE, ALLOC_PRE, ALLOC_POST, ALLOC_UNREADABLE, ALLOC_CHANGED };
struct aura_alloc_result {
    uint64_t size;
    uint32_t state, type, protect, status;
};
/* Wine 11.0-2 NtAllocateVirtualMemory notification checks only. PRE has no
 * result; POST reports NTSTATUS, not proof of a leak, loop, or root cause. */
void aura_wine_alloc_probe(const struct aura_alloc_view *, aura_ec_read, void *, struct aura_alloc_result *);
#endif

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_EC_ENTRY_RETURN_H
#define AURA_EC_ENTRY_RETURN_H
#include <stddef.h>
#include <stdint.h>

/* Private tracer inputs; SP/FP and stack bytes never enter the result packet. */
struct aura_ec_view {
    uint64_t pc, lr, sp, fp;
    uint64_t pc_begin, pc_end, lr_begin, lr_end, stack_begin, stack_end;
};
enum aura_ec_status {
    EC_RETURN_NOT_APPLICABLE, EC_RETURN_UNREADABLE, EC_RETURN_LAYOUT,
    EC_RETURN_CHANGED, EC_RETURN_UNMAPPED, EC_RETURN_CAPTURED
};
typedef int (*aura_ec_read)(void *, uint64_t, void *, size_t);
/* Narrow, signature-checked Wine 11.0-2 VirtualAlloc -> entry-thunk observation.
 * Returns a saved entry return address, NOT current guest RIP or a backtrace.
 * FEX's stack-alignment shim can be that return address instead of game code.
 * No scanning/unwinding or unverified-frame fallback. */
unsigned aura_ec_entry_return(const struct aura_ec_view *, aura_ec_read, void *, uint64_t *);
#endif

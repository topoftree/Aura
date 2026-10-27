/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_WINE_REQUEST_PROBE_H
#define AURA_WINE_REQUEST_PROBE_H
#include "fex_guest_pc.h"
#define AURA_REQUEST_NTDLL "libaura_091586ec99aa9aa25e1cb786.so"
enum aura_request_state { REQUEST_NOT_APPLICABLE, REQUEST_CAPTURED, REQUEST_UNREADABLE,
    REQUEST_CODE_MISMATCH, REQUEST_LAYOUT, REQUEST_UNKNOWN_NAME, REQUEST_CHANGED, REQUEST_UNKNOWN_CALLER };
enum aura_request_phase { REQUEST_PHASE_NONE, REQUEST_SEND, REQUEST_SEND_PARTIAL,
    REQUEST_SEND_VECTOR, REQUEST_REPLY_HEADER, REQUEST_REPLY_DATA,
    REQUEST_YIELD_USAGE_BEFORE, REQUEST_YIELD_SCHEDULER, REQUEST_YIELD_USAGE_AFTER };
enum aura_request_source { REQUEST_SOURCE_NONE, REQUEST_SOURCE_SEND_HEADER,
    REQUEST_SOURCE_WOW64_HEADER, REQUEST_SOURCE_NATIVE_NAME };
enum aura_request_detail { REQUEST_DETAIL_NONE, REQUEST_DETAIL_HEADER_ID, REQUEST_DETAIL_SEND_SIZE,
    REQUEST_DETAIL_CALLER_FRAME, REQUEST_DETAIL_CALLER_UNLISTED, REQUEST_DETAIL_WOW64_CODE,
    REQUEST_DETAIL_WOW64_POINTER, REQUEST_DETAIL_WOW64_COUNT, REQUEST_DETAIL_NAME_NULL,
    REQUEST_DETAIL_NAME_OUTSIDE, REQUEST_DETAIL_NAME_UNLISTED, REQUEST_DETAIL_NAME_TEXT };
/* No pointer, handle, FD, payload, stack bytes or target string crosses the pipe. */
struct aura_request_snapshot { uint32_t state, phase, request_id, source, detail; };
struct aura_request_view {
    uint64_t base, pc, pc_begin, pc_end, lr, syscall_number, sp, request, fp;
};
void aura_wine_request_probe(const struct aura_request_view *, aura_fex_read, void *,
                              struct aura_request_snapshot *);
const char *aura_request_state_name(unsigned);
const char *aura_request_phase_name(unsigned);
const char *aura_request_name(unsigned);
const char *aura_request_source_name(unsigned);
const char *aura_request_detail_name(unsigned);
#endif

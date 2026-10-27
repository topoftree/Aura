/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_CODE_TRACE_H
#define AURA_CODE_TRACE_H
#include <stdint.h>
#include <stdatomic.h>
#include <stddef.h>
#define CODE_TRACE_BYTES 16384
#define CODE_TRACE_ROWS 64
#define CODE_TRACE_EVENTS 128
#define CODE_TRACE_BUDGET 100000
struct code_control {
    char magic[8]; uint32_t version, bytes;
    _Atomic uint64_t epoch, deadline_ns;
    uint64_t reserved[4];
};
struct code_header {
    char magic[8]; uint32_t version, bytes, pid;
    _Atomic uint32_t state;
    uint64_t start_ticks, reserved_clock;
    _Atomic uint64_t sequence;
    uint64_t epoch, returns;
    _Atomic uint64_t dropped;
    uint64_t foreign, unreadable, nonexec, limited, row_overflow, events, attempts;
};
struct code_row {
    uint64_t base, size, calls, failures, wall_ns, cpu_ns, writable;
    uint32_t status, requested, previous, tid;
    uint64_t cpu_calls;
};
struct code_event {
    uint64_t at_ns, base, size, wall_ns, cpu_ns;
    uint32_t tid, requested, previous, status, flags, reserved;
};
struct code_trace {
    struct code_header header;
    struct code_row rows[CODE_TRACE_ROWS];
    struct code_event events[CODE_TRACE_EVENTS];
    unsigned char padding[CODE_TRACE_BYTES - 128 - 80 * CODE_TRACE_ROWS - 64 * CODE_TRACE_EVENTS];
};
_Static_assert(sizeof(struct code_control) == 64, "control format");
_Static_assert(sizeof(struct code_header) == 128, "header format");
_Static_assert(sizeof(struct code_row) == 80, "row format");
_Static_assert(sizeof(struct code_event) == 64, "event format");
_Static_assert(sizeof(struct code_trace) == CODE_TRACE_BYTES, "trace format");
_Static_assert(offsetof(struct code_header, sequence) == 40, "sequence offset");
#endif

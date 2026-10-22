/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_WAKE_RING_H
#define AURA_WAKE_RING_H
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

#define AURA_WAKE_CAPACITY 8192
#define AURA_WAKE_HEADER 4096
enum { WAIT_ENTER = 1, WAIT_RETURN, ALERT_ENTER, ALERT_RETURN, MULTI_ENTER, MULTI_RETURN,
       COMPLETION_ENTER, COMPLETION_RETURN };
struct wake_event {
    _Atomic uint64_t sequence;
    uint64_t time_ns, call_id, caller_lr;
    int64_t timeout;
    uint32_t wine_tid, linux_tid, target_tid, phase;
    int32_t status, flag;
    uint64_t argument, duration_ns;
    _Atomic uint64_t committed;
    uint64_t reserved;
};
struct wake_module { uint64_t base, size; char name[48]; };
struct wake_header {
    char magic[8];
    uint32_t version, event_size, capacity, pid;
    _Atomic uint64_t next, dropped;
    uint32_t leader_wine_tid, installed;
    _Atomic uint32_t stop, module_count;
    uint64_t native_base;
    _Atomic uint64_t unobserved_multi;
    uint8_t reserved[56];
    struct wake_module modules[32];
};
struct wake_ring {
    struct wake_header header;
    uint8_t padding[AURA_WAKE_HEADER - sizeof(struct wake_header)];
    struct wake_event events[AURA_WAKE_CAPACITY];
};
_Static_assert(sizeof(struct wake_event) == 96, "Stable binary format");
_Static_assert(offsetof(struct wake_ring, events) == AURA_WAKE_HEADER, "Stable header");
_Static_assert(offsetof(struct wake_header, stop) == 48, "Stop flag");

void aura_wake_init(struct wake_ring *, uint32_t pid, uint32_t tid, uint64_t native_base);
void aura_wake_record(struct wake_ring *, const struct wake_event *);
uint64_t aura_wake_clock(void);
#endif

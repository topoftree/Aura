/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT -- metadata only, no handles or command contents. */
#ifndef AURA_VULKAN_COMMANDS_H
#define AURA_VULKAN_COMMANDS_H
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#define AURA_CMD_EVENTS 8
#define AURA_CMD_LIMIT 2000000u
enum aura_cmd_kind { AC_DRAW=1, AC_DISPATCH, AC_COPY, AC_BARRIER, AC_CLEAR, AC_RENDER, AC_SECONDARY, AC_BIND };
enum aura_cmd_flags { AC_UNKNOWN=1, AC_TRUNCATED=2, AC_SECONDARY_UNKNOWN=4, AC_NEGATIVE=8, AC_LOST=16, AC_SECONDARY_FOLDED=32 };
/* v3: OR of unknown-buffer reasons in flags[15:8], never a unique failure count. */
enum aura_cmd_unknown { ACU_MISSING=1, ACU_GENERATION=2, ACU_INCOMPLETE=4, ACU_CAPACITY=8,
    ACU_ANCESTRY=16, ACU_LIFECYCLE=32, ACU_CONTENTION=64, ACU_INACTIVE=128 };
#define AC_UNKNOWN_REASON(bits) (AC_UNKNOWN | ((uint32_t)(bits) << 8))
enum aura_cmd_reason { ACR_CONTENTION, ACR_MISSING, ACR_GENERATION, ACR_INCOMPLETE, ACR_CAPACITY, ACR_LIFECYCLE, ACR_COUNT };
struct aura_cmd_data {
    uint64_t call, begin_ns, end_ns, activity;
    uint32_t device, queue, family, index;
    uint32_t buffers, known, flags, operations;
    uint32_t draws, dispatches, copies, barriers, clears, rendering, secondary;
    uint32_t images, buffer_ops, ownership, src_layout, dst_layout, last_op, bind_point;
};
struct aura_cmd_event { _Atomic uint64_t seq; struct aura_cmd_data data; };
struct aura_cmd_capture {
    uint32_t version, capacity;
    _Atomic uint64_t observed, dropped, submitted, publications;
    _Atomic uint32_t reasons[ACR_COUNT]; /* v2/v3; v1 reserved these 24 bytes */
    struct aura_cmd_event first_lost, events[AURA_CMD_EVENTS];
};
_Static_assert(offsetof(struct aura_cmd_capture,reasons)==40,"Command reason ABI");
_Static_assert(offsetof(struct aura_cmd_capture,first_lost)==64,"Command event ABI");
_Static_assert(sizeof(struct aura_cmd_data)==120,"Command data ABI");
_Static_assert(sizeof(struct aura_cmd_capture)==1216,"Command capture ABI");
static inline void aura_cmd_publish(struct aura_cmd_capture *c,struct aura_cmd_data data,int first_lost) {
    struct aura_cmd_event *e=&c->events[(data.call-1)%AURA_CMD_EVENTS];
    uint64_t seq=atomic_load(&e->seq);
    if (!(seq&1) && atomic_compare_exchange_strong(&e->seq,&seq,seq+1)) {
        if (e->data.call<data.call) e->data=data;
        atomic_store_explicit(&e->seq,seq+2,memory_order_release);
        atomic_fetch_add(&c->publications,1);
    }
    if (first_lost) {
        uint64_t empty=0;
        if (atomic_compare_exchange_strong(&c->first_lost.seq,&empty,1)) {
            c->first_lost.data=data;
            atomic_store_explicit(&c->first_lost.seq,2,memory_order_release);
        }
    }
}
#endif

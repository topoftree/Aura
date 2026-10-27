/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Manual-only bounded native call timeline, separate from the legacy ABI.
 * Host monotonic / calling-thread CPU, never GPU or physical display time.
 * No pointers, arguments, strings, images or driver handles are persisted. */
#ifndef AURA_GRAPHICS_CADENCE_H
#define AURA_GRAPHICS_CADENCE_H
#include <stdatomic.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#define AURA_CADENCE_ROWS 8192u
#define AURA_CADENCE_NS UINT64_C(10000000000)
struct aura_cadence_row {
    _Atomic uint64_t seq;
    uint64_t generation, call, begin, end, cpu;
    uint32_t tid, api;
    int32_t result;
    uint32_t state; /* 1=entered, return unobserved; 2=returned */
};
struct aura_cadence {
    char magic[8]; uint32_t version, capacity, pid;
    _Atomic uint32_t enabled;
    uint64_t start_ticks;
    _Atomic uint64_t generation, epoch, begin, deadline, base;
    _Atomic uint64_t next, dropped, late;
    uint64_t created_ns, reserved[3];
    struct aura_cadence_row rows[AURA_CADENCE_ROWS];
};
struct aura_cadence_ticket { uint64_t generation, call, epoch; };
_Static_assert(offsetof(struct aura_cadence,rows)==128, "cadence header ABI");
_Static_assert(sizeof(struct aura_cadence_row)==64, "cadence row ABI");
_Static_assert(sizeof(struct aura_cadence)==524416, "cadence file ABI");
static inline void aura_cadence_init(struct aura_cadence *c,uint32_t pid,uint64_t ticks,uint64_t now) {
    c->version=1;c->capacity=AURA_CADENCE_ROWS;c->pid=pid;c->start_ticks=ticks;c->created_ns=now;
    memcpy(c->magic,"AURAGC1",8);
}
static inline int aura_cadence_live(struct aura_cadence *c,uint64_t generation,uint64_t epoch,uint64_t now) {
    if(!c || !generation || (generation&1) || !now || !atomic_load(&c->enabled) ||
       atomic_load(&c->generation)!=generation || atomic_load(&c->epoch)!=epoch || (epoch&1)) return 0;
    uint64_t begin=atomic_load(&c->begin),end=atomic_load(&c->deadline);
    return begin && now>=begin && now<end && end>=begin && end-begin<=AURA_CADENCE_NS;
}
static inline struct aura_cadence_ticket aura_cadence_enter(struct aura_cadence *c,uint64_t now,
        uint64_t epoch,uint32_t tid,uint32_t api) {
    struct aura_cadence_ticket empty={0};
    if(!c || !tid || api<1 || api>13) return empty;
    uint64_t generation=atomic_load(&c->generation);
    if(!aura_cadence_live(c,generation,epoch,now)) return empty;
    uint64_t next=atomic_load(&c->next),base=atomic_load(&c->base);
    for(unsigned attempt=0;attempt<4;++attempt) {
        if(next<base || next-base>=AURA_CADENCE_ROWS || next==UINT64_MAX) {
            atomic_fetch_add(&c->dropped,1);return empty;
        }
        if(atomic_compare_exchange_weak(&c->next,&next,next+1)) {
            struct aura_cadence_row *r=&c->rows[next%AURA_CADENCE_ROWS];
            uint64_t seq=atomic_load(&r->seq);
            if((seq&1) || !atomic_compare_exchange_strong(&r->seq,&seq,seq+1)) {
                atomic_fetch_add(&c->dropped,1);return empty;
            }
            if(!aura_cadence_live(c,generation,epoch,now)) {
                atomic_store_explicit(&r->seq,seq+2,memory_order_release);return empty;
            }
            r->generation=generation;r->call=next+1;r->begin=now;r->end=0;r->cpu=0;
            r->tid=tid;r->api=api;r->result=0;r->state=1;
            atomic_store_explicit(&r->seq,seq+2,memory_order_release);
            return (struct aura_cadence_ticket){generation,next+1,epoch};
        }
    }
    atomic_fetch_add(&c->dropped,1);return empty;
}
static inline void aura_cadence_return(struct aura_cadence *c,struct aura_cadence_ticket t,
        uint64_t now,uint64_t cpu_begin,uint64_t cpu_end,int32_t result,uint64_t epoch,int stopped) {
    if(!c || !t.call) return;
    if(stopped || epoch!=t.epoch || !aura_cadence_live(c,t.generation,epoch,now)) return;
    struct aura_cadence_row *r=&c->rows[(t.call-1)%AURA_CADENCE_ROWS];
    uint64_t seq=atomic_load(&r->seq);
    if((seq&1) || !atomic_compare_exchange_strong(&r->seq,&seq,seq+1)) {
        atomic_fetch_add(&c->late,1);return;
    }
    if(r->generation==t.generation && r->call==t.call && now>=r->begin &&
       aura_cadence_live(c,t.generation,epoch,now)) {
        r->end=now;r->cpu=cpu_begin && cpu_end>=cpu_begin ? cpu_end-cpu_begin : UINT64_MAX;
        r->result=result;r->state=2;
    }
    atomic_store_explicit(&r->seq,seq+2,memory_order_release);
}
#endif

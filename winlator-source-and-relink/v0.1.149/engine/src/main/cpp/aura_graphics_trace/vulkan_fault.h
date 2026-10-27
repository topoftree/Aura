/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Bounded metadata from actual native Vulkan returns. No pointers, shader words,
 * device addresses, arbitrary driver strings or vendor binary data in the ABI. */
#ifndef AURA_VULKAN_FAULT_H
#define AURA_VULKAN_FAULT_H
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#define AURA_VK_EVENTS 24
#define AURA_VK_MAX_CALLS 131072u
#define AURA_VK_WINDOW_NS UINT64_C(120000000000)
enum aura_vk_api {
    AVK_SUBMIT=1, AVK_SUBMIT2, AVK_SUBMIT2_KHR, AVK_PRESENT, AVK_FENCES,
    AVK_SEMAPHORES, AVK_SEMAPHORES_KHR, AVK_QUEUE_IDLE, AVK_DEVICE_IDLE,
    AVK_MAP, AVK_CREATE, AVK_ACQUIRE, AVK_ACQUIRE2
};
enum aura_vk_fault_status {
    AVF_UNOBSERVED, AVF_NO_DEVICE, AVF_NOT_ENABLED, AVF_ENABLE_UNKNOWN,
    AVF_NO_RESOLVER, AVF_NO_ENTRY, AVF_COUNTS_FAILED, AVF_COMPLETE,
    AVF_PARTIAL, AVF_PENDING
};
struct aura_vk_data {
    uint64_t call, returned, begin_ns, end_ns, activity;
    uint32_t tid, api, device, queue;
    int32_t result;
    uint32_t flags, objects, commands, waits, signals, image, timeout;
};
struct aura_vk_event { _Atomic uint64_t seq; struct aura_vk_data data; };
struct aura_vk_once { _Atomic uint64_t state; struct aura_vk_data data; };
struct aura_vk_driver_fault {
    _Atomic uint64_t seq;
    uint32_t state;
    int32_t counts_result, detail_result;
    uint32_t flags, address_total, vendor_total;
    uint64_t vendor_binary_bytes;
    uint32_t address_retained, vendor_retained, category;
    uint32_t address_types[8], reserved[11];
};
struct aura_vk_fault {
    uint32_t version, capacity;
    _Atomic uint64_t deadline_ns, entries, returns, dropped;
    _Atomic uint32_t frozen;
    uint32_t reserved;
    uint64_t started_ns, reserved2;
    struct aura_vk_once first_error, first_lost;
    struct aura_vk_driver_fault driver;
    struct aura_vk_event events[AURA_VK_EVENTS];
};
_Static_assert(sizeof(struct aura_vk_data)==88, "Vulkan event payload ABI");
_Static_assert(sizeof(struct aura_vk_event)==96, "Vulkan event ABI");
_Static_assert(sizeof(struct aura_vk_driver_fault)==128, "Vulkan driver fault ABI");
_Static_assert(sizeof(struct aura_vk_fault)==2688, "Vulkan fault ABI");
static inline void aura_vk_init(struct aura_vk_fault *f, uint64_t now) {
    f->version=1; f->capacity=AURA_VK_EVENTS; f->started_ns=now;
    atomic_store(&f->deadline_ns,now+AURA_VK_WINDOW_NS);
}
static inline int aura_vk_allowed(struct aura_vk_fault *f, uint64_t now) {
    uint64_t deadline=atomic_load(&f->deadline_ns);
    return f->version==1 && now && now>=f->started_ns && now<=deadline &&
        deadline-now<=AURA_VK_WINDOW_NS && !atomic_load(&f->frozen);
}
static inline struct aura_vk_data aura_vk_enter(struct aura_vk_fault *f,
        uint64_t now, uint64_t activity, uint32_t tid, unsigned api) {
    struct aura_vk_data d={.image=UINT32_MAX};
    if (!aura_vk_allowed(f,now) || atomic_load(&f->entries)>=AURA_VK_MAX_CALLS) return d;
    uint64_t call=atomic_fetch_add(&f->entries,1)+1;
    if (call>AURA_VK_MAX_CALLS) return d;
    d.call=call; d.begin_ns=now; d.activity=activity; d.tid=tid; d.api=api;
    return d;
}
static inline void aura_vk_once_publish(struct aura_vk_once *once, struct aura_vk_data d) {
    uint64_t empty=0;
    if (atomic_compare_exchange_strong(&once->state,&empty,1)) {
        once->data=d;
        atomic_store_explicit(&once->state,2,memory_order_release);
    }
}
/* The winning claim is first OBSERVED among these wrappers in this process.
 * Concurrent calls and unobserved entry points preclude a global first-fault claim. */
static inline int aura_vk_return(struct aura_vk_fault *f, struct aura_vk_data *d,
        uint64_t now, int32_t result, uint64_t activity, int stopped) {
    if (!d->call || stopped || activity!=d->activity || !aura_vk_allowed(f,now) || now<d->begin_ns) return 0;
    d->end_ns=now; d->result=result; d->returned=atomic_fetch_add(&f->returns,1)+1;
    if (result<0) aura_vk_once_publish(&f->first_error,*d);
    int first_lost=0;
    if (result==-4) {
        uint64_t empty=0;
        if (atomic_compare_exchange_strong(&f->first_lost.state,&empty,1)) {
            atomic_store(&f->frozen,1);
            f->first_lost.data=*d;
            atomic_store_explicit(&f->first_lost.state,2,memory_order_release);
            first_lost=1;
        }
    }
    /* In-flight writers may finish around the freeze. Their times and return
     * ordinals remain explicit; never label the ring a GPU execution order. */
    struct aura_vk_event *event=&f->events[(d->returned-1)%AURA_VK_EVENTS];
    uint64_t seq=atomic_load(&event->seq);
    if ((seq&1) || !atomic_compare_exchange_strong(&event->seq,&seq,seq+1)) {
        atomic_fetch_add(&f->dropped,1);
    } else {
        if (event->data.returned<d->returned) event->data=*d;
        else atomic_fetch_add(&f->dropped,1);
        atomic_store_explicit(&event->seq,seq+2,memory_order_release);
    }
    return first_lost;
}
#endif

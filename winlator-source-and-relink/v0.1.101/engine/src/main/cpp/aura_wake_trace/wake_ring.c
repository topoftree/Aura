/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Fixed storage, no allocation, locks, waits or log I/O on the event path.
 * A busy/overwritten slot is dropped rather than waiting for its writer.
 */
#include "wake_ring.h"
#include <string.h>
#include <time.h>

void aura_wake_init(struct wake_ring *ring, uint32_t pid, uint32_t tid, uint64_t base) {
    memset(ring, 0, sizeof(*ring));
    memcpy(ring->header.magic, "AURAWK1", 8);
    ring->header.version = 1;
    ring->header.event_size = sizeof(struct wake_event);
    ring->header.capacity = AURA_WAKE_CAPACITY;
    ring->header.pid = pid;
    ring->header.leader_wine_tid = tid;
    ring->header.native_base = base;
    ring->header.installed = 1;
}

uint64_t aura_wake_clock(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_BOOTTIME, &now)) return 0;
    return (uint64_t)now.tv_sec * 1000000000 + now.tv_nsec;
}

void aura_wake_record(struct wake_ring *ring, const struct wake_event *event) {
    if (!ring || atomic_load_explicit(&ring->header.stop, memory_order_relaxed)) return;
    uint64_t ticket = atomic_fetch_add_explicit(&ring->header.next, 1, memory_order_relaxed) + 1;
    struct wake_event *slot = &ring->events[(ticket - 1) % AURA_WAKE_CAPACITY];
    uint64_t previous = atomic_load_explicit(&slot->sequence, memory_order_relaxed);
    if ((previous & 1) || previous >= ticket * 2 ||
        !atomic_compare_exchange_strong_explicit(&slot->sequence, &previous, ticket * 2 + 1,
                                                 memory_order_acquire, memory_order_relaxed)) {
        atomic_fetch_add_explicit(&ring->header.dropped, 1, memory_order_relaxed);
        return;
    }
    memcpy((char *)slot + 8, (const char *)event + 8, 72);
    slot->reserved = event->reserved; /* completion boundary observation for phases 7/8 */
    atomic_store_explicit(&slot->committed, ticket * 2, memory_order_release);
    atomic_store_explicit(&slot->sequence, ticket * 2, memory_order_release);
}

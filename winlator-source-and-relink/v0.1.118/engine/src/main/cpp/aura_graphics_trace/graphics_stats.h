/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Bounded, per-process/per-thread aggregate observation. No frame log writes.
 * Durations are inclusive host times, never GPU execution time. */
#ifndef AURA_GRAPHICS_STATS_H
#define AURA_GRAPHICS_STATS_H
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <time.h>
#define GRAPHICS_ROWS 128
enum graphics_metric {
    GL_SWAP, GL_FINISH, GL_READ_PIXELS, X_GEOMETRY, X_SHM_SUBMIT, X_SYNC,
    MESA_RECT_COPY, MESA_FUTEX_WAIT, VK_SUBMIT, VK_PRESENT, VK_FENCE_WAIT,
    VK_SEMAPHORE_WAIT, VK_QUEUE_IDLE, VK_DEVICE_IDLE, GL_MAP_BUFFER, VK_MAP_MEMORY,
    MESA_COPY_TO_SHM, MESA_COPY_TO_SWAP_END,
    GRAPHICS_METRICS
};
struct graphics_row {
    _Atomic uint64_t seq, key;
    uint64_t count, wall, cpu, max_wall, max_cpu, bytes, cpu_count;
    uint64_t first_start, last_start, gap_total, gap_max, errors;
    uint32_t histogram[8]; /* wall <= .1/.5/1/2/5/10/20 ms, then larger */
    uint64_t copy_calls; /* Mesa copy requests; helper chunks not counted; old rows: 0 */
    unsigned char reserved[40];
};
struct graphics_header {
    char magic[8];
    uint32_t version, rows, pid;
    _Atomic uint32_t stop;
    uint64_t start_ticks, started_ns;
    _Atomic uint64_t coverage, conflicts, dropped;
    _Atomic uint64_t activity; /* host epoch; odd while paused */
    uint32_t copy_version; /* 1: bounded synchronous large-copy helpers configured */
    _Atomic uint32_t copy_workers;
    _Atomic uint64_t parallel_copy_calls, parallel_copy_bytes, serial_large_copies;
    unsigned char reserved[3992];
};
struct graphics_stats { struct graphics_header header; struct graphics_row rows[GRAPHICS_ROWS]; };
_Static_assert(sizeof(struct graphics_header) == 4096, "header ABI");
_Static_assert(sizeof(struct graphics_row) == 192, "row ABI");
_Static_assert(sizeof(struct graphics_stats) == 28672, "file ABI");
static inline uint64_t graphics_clock(clockid_t clock) {
#ifdef AURA_GRAPHICS_TEST
    extern uint64_t graphics_test_clock_calls;
    ++graphics_test_clock_calls;
#endif
    struct timespec ts;
    return clock_gettime(clock, &ts) ? 0 : (uint64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}
/* Each key has one producer (Linux thread + metric); live readers filter by seq.
 * Rows never recycle; exceeding the fixed budget increments dropped. */
static inline void graphics_record_copies(struct graphics_stats *s, uint32_t tid, unsigned metric,
        uint64_t begin, uint64_t end, uint64_t cpu_begin, uint64_t cpu_end, uint64_t bytes, int error,
        uint64_t copy_calls) {
    if (!s || !tid || metric >= GRAPHICS_METRICS || !begin || end < begin || atomic_load(&s->header.stop)) return;
    uint64_t key = ((uint64_t)tid << 32) | (metric + 1);
    unsigned start = (tid * 17u + metric) % GRAPHICS_ROWS;
    struct graphics_row *row = NULL;
    for (unsigned i = 0; i < GRAPHICS_ROWS; ++i) {
        struct graphics_row *r = &s->rows[(start + i) % GRAPHICS_ROWS];
        uint64_t expected = 0;
        if (atomic_load_explicit(&r->key, memory_order_relaxed) == key ||
            atomic_compare_exchange_strong(&r->key, &expected, key)) { row = r; break; }
    }
    if (!row) { atomic_fetch_add(&s->header.dropped, 1); return; }
    /* A recursive call to the same metric must not corrupt the row. */
    uint64_t seq = atomic_load(&row->seq);
    if ((seq & 1) || !atomic_compare_exchange_strong(&row->seq, &seq, seq + 1)) {
        atomic_fetch_add(&s->header.dropped, 1); return;
    }
    uint64_t wall = end - begin;
    ++row->count; row->wall += wall; row->bytes += bytes; row->errors += !!error;
    row->copy_calls += copy_calls;
    if (wall > row->max_wall) row->max_wall = wall;
    if (cpu_begin && cpu_end >= cpu_begin) {
        uint64_t cpu = cpu_end - cpu_begin;
        row->cpu += cpu; ++row->cpu_count;
        if (cpu > row->max_cpu) row->max_cpu = cpu;
    }
    if (!row->first_start) row->first_start = begin;
    if (row->last_start && begin >= row->last_start) {
        uint64_t gap = begin - row->last_start;
        row->gap_total += gap; if (gap > row->gap_max) row->gap_max = gap;
    }
    row->last_start = begin;
    const uint64_t bounds[] = {100000,500000,1000000,2000000,5000000,10000000,20000000};
    unsigned bucket = 0;
    while (bucket < 7 && wall > bounds[bucket]) ++bucket;
    ++row->histogram[bucket];
    atomic_store_explicit(&row->seq, seq + 2, memory_order_release);
}
static inline void graphics_record(struct graphics_stats *s, uint32_t tid, unsigned metric,
        uint64_t begin, uint64_t end, uint64_t cpu_begin, uint64_t cpu_end, uint64_t bytes, int error) {
    graphics_record_copies(s, tid, metric, begin, end, cpu_begin, cpu_end, bytes, error, 0);
}
#endif

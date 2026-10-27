/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Bounded parallel copy for large, non-overlapping Mesa display rectangles.
 * The caller retains ownership and waits for every helper before returning.
 * No affinity, priority changes, spin loops, asynchronous frames or GPU waits.
 */
#define _GNU_SOURCE
#include "frame_copy.h"
#include "frame_copy_regions.h"
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

struct copy_job {
    void *dst; const void *src; size_t bytes, first; aura_copy_fn copy;
    int regions;
    /* Shared cursor has a separate cache line from immutable job parameters. */
    _Alignas(64) _Atomic size_t next;
};
static struct {
    pthread_mutex_t gate, lock;
    pthread_cond_t ready, done;
    pthread_t threads[AURA_FRAME_COPY_WORKERS];
    struct copy_job job;
    unsigned helpers, remaining;
    size_t compared_bytes, written_bytes;
    uint64_t generation;
    int initialized, attempted, stop, fork_child;
    pid_t owner;
} pool = {
    .gate = PTHREAD_MUTEX_INITIALIZER, .lock = PTHREAD_MUTEX_INITIALIZER,
    .ready = PTHREAD_COND_INITIALIZER, .done = PTHREAD_COND_INITIALIZER
};
static pthread_once_t init_once = PTHREAD_ONCE_INIT;
static _Thread_local int fork_locked;

/* Each chunk is claimed exactly once. A fast caller/helper can keep working
 * instead of waiting with a fixed third of the image already finished. Only
 * the first boundary needs alignment; every subsequent boundary is 256 KiB
 * further on the destination. The last chunk is strictly bounded by bytes. */
static size_t copy_available(struct copy_job *job, size_t *compared, size_t *written) {
    size_t copied = 0;
    for (;;) {
        size_t index = atomic_fetch_add_explicit(&job->next, 1, memory_order_relaxed);
        size_t offset = index ? job->first + (index - 1) * AURA_FRAME_COPY_CHUNK_BYTES : 0;
        if (offset >= job->bytes) break;
        size_t bytes = index ? AURA_FRAME_COPY_CHUNK_BYTES : job->first;
        if (bytes > job->bytes - offset) bytes = job->bytes - offset;
        if (job->regions)
            copy_regions((char *)job->dst + offset, (const char *)job->src + offset,
                         bytes, job->copy, compared, written);
        else job->copy((char *)job->dst + offset, (const char *)job->src + offset, bytes);
        copied += bytes;
    }
    return copied;
}

/* Wait for an in-flight job before fork. Child processes have no helper threads
 * and use libc until exec; they must never wait on inherited condition state. */
static void before_fork(void) {
    if (getpid() != pool.owner) return;
    pthread_mutex_lock(&pool.gate); pthread_mutex_lock(&pool.lock); fork_locked = 1;
}
static void after_fork(void) {
    if (!fork_locked) return;
    fork_locked = 0; pthread_mutex_unlock(&pool.lock); pthread_mutex_unlock(&pool.gate);
}
static void child_fork(void) { pool.fork_child = 1; pool.helpers = 0; after_fork(); }
static void initialize(void) {
    if (!pthread_atfork(before_fork, after_fork, child_fork)) { pool.owner = getpid(); pool.initialized = 1; }
}
int aura_frame_copy_init(void) {
    int saved = errno;
    pthread_once(&init_once, initialize);
    int result = pool.initialized;
    errno = saved;
    return result;
}

static void *worker(void *argument) {
#ifdef __ANDROID__
    pthread_setname_np(pthread_self(), "AuraFrameCopy");
#endif
    (void)argument;
    uint64_t previous = 0;
    pthread_mutex_lock(&pool.lock);
    for (;;) {
        while (!pool.stop && pool.generation == previous) pthread_cond_wait(&pool.ready, &pool.lock);
        if (pool.stop) break;
        previous = pool.generation;
        pthread_mutex_unlock(&pool.lock);
        size_t compared = 0, written = 0;
        copy_available(&pool.job, &compared, &written);
        pthread_mutex_lock(&pool.lock);
        pool.compared_bytes += compared;
        pool.written_bytes += written;
        if (--pool.remaining == 0) pthread_cond_signal(&pool.done);
    }
    pthread_mutex_unlock(&pool.lock);
    return NULL;
}

/* Called with gate and lock held. Allocation/thread failures are remembered,
 * not retried every frame. A partially created pool is still bounded and safe. */
static void start_workers(void) {
    if (pool.attempted) return;
    pool.attempted = 1;
    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpus < 4) return;
    pthread_attr_t attr;
    if (pthread_attr_init(&attr)) return;
    if (pthread_attr_setstacksize(&attr, 256u * 1024u)) { pthread_attr_destroy(&attr); return; }
    for (unsigned i = 0; i < AURA_FRAME_COPY_WORKERS; ++i) {
        if (pthread_create(&pool.threads[i], &attr, worker, (void *)(uintptr_t)i)) break;
        ++pool.helpers;
    }
    pthread_attr_destroy(&attr);
}

static unsigned copy_frame(void *dst, const void *src, size_t bytes, aura_copy_fn copy,
                           struct aura_frame_copy_work *work, int regions) {
    int saved = errno;
    if (work) *work = (struct aura_frame_copy_work){0};
    uintptr_t d = (uintptr_t)dst, s = (uintptr_t)src;
    if (!pool.initialized || pool.fork_child || getpid() != pool.owner || bytes < AURA_FRAME_COPY_MIN_BYTES ||
        bytes > AURA_FRAME_COPY_MAX_BYTES || d > UINTPTR_MAX - bytes || s > UINTPTR_MAX - bytes ||
        (d < s + bytes && s < d + bytes) || pthread_mutex_trylock(&pool.gate)) {
        errno = saved; copy(dst, src, bytes); return 0;
    }
    // A canceled caller must not release its buffers while helpers still use
    // them, or leave the pool mutex locked by pthread_cond_wait cancellation.
#ifndef __ANDROID__ /* Bionic does not implement pthread cancellation. */
    int cancellation;
    pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, &cancellation);
#endif
    pthread_mutex_lock(&pool.lock);
    if (!pool.stop) start_workers();
    unsigned helpers = pool.stop ? 0 : pool.helpers;
    if (!helpers) {
        pthread_mutex_unlock(&pool.lock); pthread_mutex_unlock(&pool.gate);
#ifndef __ANDROID__
        pthread_setcancelstate(cancellation, NULL);
#endif
        errno = saved; copy(dst, src, bytes); return 0;
    }
    pool.job.dst = dst; pool.job.src = src; pool.job.bytes = bytes; pool.job.copy = copy;
    pool.job.regions = regions;
    pool.compared_bytes = pool.written_bytes = 0;
    pool.job.first = AURA_FRAME_COPY_CHUNK_BYTES - (d & 63u);
    atomic_store_explicit(&pool.job.next, 0, memory_order_relaxed);
    pool.remaining = helpers;
    ++pool.generation;
    pthread_cond_broadcast(&pool.ready);
    pthread_mutex_unlock(&pool.lock);
    size_t compared = 0, written = 0;
    size_t caller_bytes = copy_available(&pool.job, &compared, &written);
    pthread_mutex_lock(&pool.lock);
    while (pool.remaining) pthread_cond_wait(&pool.done, &pool.lock);
    if (work) {
        work->caller_bytes = caller_bytes;
        work->helper_bytes = bytes - caller_bytes;
        work->chunks = 1 + (bytes - pool.job.first + AURA_FRAME_COPY_CHUNK_BYTES - 1) / AURA_FRAME_COPY_CHUNK_BYTES;
        work->compared_bytes = compared + pool.compared_bytes;
        work->written_bytes = written + pool.written_bytes;
    }
    pthread_mutex_unlock(&pool.lock);
    pthread_mutex_unlock(&pool.gate);
#ifndef __ANDROID__
    pthread_setcancelstate(cancellation, NULL);
#endif
    errno = saved;
    return helpers;
}

unsigned aura_frame_copy_measured(void *dst, const void *src, size_t bytes, aura_copy_fn copy,
                                 struct aura_frame_copy_work *work) {
    return copy_frame(dst, src, bytes, copy, work, 0);
}
unsigned aura_frame_copy_regions(void *dst, const void *src, size_t bytes, aura_copy_fn copy,
                                 struct aura_frame_copy_work *work) {
    return copy_frame(dst, src, bytes, copy, work, 1);
}

unsigned aura_frame_copy(void *dst, const void *src, size_t bytes, aura_copy_fn copy) {
    return aura_frame_copy_measured(dst, src, bytes, copy, NULL);
}

void aura_frame_copy_shutdown(void) {
    if (!pool.initialized || pool.fork_child || getpid() != pool.owner) return;
    int saved = errno;
    pthread_mutex_lock(&pool.gate);
    pthread_mutex_lock(&pool.lock);
    pool.stop = 1;
    pthread_cond_broadcast(&pool.ready);
    pthread_mutex_unlock(&pool.lock);
    for (unsigned i = 0; i < pool.helpers; ++i) pthread_join(pool.threads[i], NULL);
    pool.helpers = 0;
    pthread_mutex_unlock(&pool.gate);
    errno = saved;
}

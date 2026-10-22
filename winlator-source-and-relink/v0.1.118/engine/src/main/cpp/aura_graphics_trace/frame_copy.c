/* Aura integration changes for 0.1.118, 2026-10-22.
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
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <unistd.h>

struct copy_job { void *dst; const void *src; size_t bytes; aura_copy_fn copy; };
static struct {
    pthread_mutex_t gate, lock;
    pthread_cond_t ready, done;
    pthread_t threads[AURA_FRAME_COPY_WORKERS];
    struct copy_job jobs[AURA_FRAME_COPY_WORKERS];
    unsigned helpers, remaining;
    uint64_t generation;
    int initialized, attempted, stop, fork_child;
    pid_t owner;
} pool = {
    .gate = PTHREAD_MUTEX_INITIALIZER, .lock = PTHREAD_MUTEX_INITIALIZER,
    .ready = PTHREAD_COND_INITIALIZER, .done = PTHREAD_COND_INITIALIZER
};
static pthread_once_t init_once = PTHREAD_ONCE_INIT;
static _Thread_local int fork_locked;

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
    unsigned index = (unsigned)(uintptr_t)argument;
    uint64_t previous = 0;
    pthread_mutex_lock(&pool.lock);
    for (;;) {
        while (!pool.stop && pool.generation == previous) pthread_cond_wait(&pool.ready, &pool.lock);
        if (pool.stop) break;
        previous = pool.generation;
        struct copy_job job = pool.jobs[index];
        pthread_mutex_unlock(&pool.lock);
        job.copy(job.dst, job.src, job.bytes);
        pthread_mutex_lock(&pool.lock);
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

unsigned aura_frame_copy(void *dst, const void *src, size_t bytes, aura_copy_fn copy) {
    int saved = errno;
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
    size_t offset = 0;
    for (unsigned i = 0; i < helpers; ++i) {
        /* Boundaries on destination cache lines avoid false sharing, including
         * unaligned buffers. All reads/writes remain within the original span. */
        size_t end = ((d + bytes * (i + 1) / (helpers + 1)) & ~(uintptr_t)63) - d;
        pool.jobs[i] = (struct copy_job){(char *)dst + offset, (const char *)src + offset, end - offset, copy};
        offset = end;
    }
    pool.remaining = helpers;
    ++pool.generation;
    pthread_cond_broadcast(&pool.ready);
    pthread_mutex_unlock(&pool.lock);
    copy((char *)dst + offset, (const char *)src + offset, bytes - offset);
    pthread_mutex_lock(&pool.lock);
    while (pool.remaining) pthread_cond_wait(&pool.done, &pool.lock);
    pthread_mutex_unlock(&pool.lock);
    pthread_mutex_unlock(&pool.gate);
#ifndef __ANDROID__
    pthread_setcancelstate(cancellation, NULL);
#endif
    errno = saved;
    return helpers;
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

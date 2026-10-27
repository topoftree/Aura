/* SPDX-License-Identifier: MIT
 * Host-only scheduler for the actual Wine futex-queue functions extracted by
 * verify_lifetime.py. Win32 alert syscalls are controlled boundaries, not Wine
 * or Android execution. Each scenario runs in its own watchdog-limited process.
 */
#include <assert.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef uint8_t UCHAR;
typedef uint16_t USHORT;
typedef uint32_t ULONG, DWORD;
typedef uint64_t ULONG64;
typedef int32_t LONG, NTSTATUS;
typedef uintptr_t ULONG_PTR, DWORD_PTR, SIZE_T;
typedef void *HANDLE;
typedef int BOOL;
typedef struct { int64_t QuadPart; } LARGE_INTEGER;
#define WINAPI
#define FALSE 0
#define TRUE 1
#define STATUS_SUCCESS 0
#define STATUS_ALERTED 0x101
#define STATUS_TIMEOUT 0x102
#define STATUS_INVALID_PARAMETER ((NTSTATUS)0xc000000d)
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define TRACE(...) ((void)0)
#include "wine-list.h"

static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t event = PTHREAD_COND_INITIALIZER;
static _Thread_local int waiter, after_wait;
static int race, all, queued, release_wait, retry_lock, returned, alert_result;
static DWORD alerts[600];
static unsigned alert_count;
static void remove_hook(struct list *entry);
static LONG InterlockedCompareExchange(LONG *p, LONG value, LONG compare) {
    LONG old = compare;
    __atomic_compare_exchange_n(p, &old, value, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    if (race && waiter && after_wait && old) {
        pthread_mutex_lock(&gate);
        retry_lock = 1;
        pthread_cond_broadcast(&event);
        pthread_mutex_unlock(&gate);
    }
    return old;
}
static LONG InterlockedExchange(LONG *p, LONG value) {
    return __atomic_exchange_n(p, value, __ATOMIC_SEQ_CST);
}
#define YieldProcessor() sched_yield()
#define ReadPointerAcquire(p) __atomic_load_n((p), __ATOMIC_ACQUIRE)
#define WritePointerRelease(p, v) __atomic_store_n((p), (v), __ATOMIC_RELEASE)
static DWORD GetCurrentThreadId(void) { return 0x120; }
static NTSTATUS NtWaitForAlertByThreadId(const void *p, const LARGE_INTEGER *timeout) {
    (void)p; (void)timeout;
    if (race) {
        pthread_mutex_lock(&gate);
        queued = 1;
        pthread_cond_broadcast(&event);
        while (!release_wait) pthread_cond_wait(&event, &gate);
        pthread_mutex_unlock(&gate);
        after_wait = 1;
    }
    return alert_result;
}
static NTSTATUS NtAlertThreadByThreadId(HANDLE tid) {
    assert(alert_count < ARRAY_SIZE(alerts));
    alerts[alert_count++] = (DWORD)(uintptr_t)tid;
    return 0;
}
static NTSTATUS NtAlertMultipleThreadByThreadId(HANDLE *tids, ULONG count, void *a, void *b) {
    (void)a; (void)b;
    for (unsigned i = 0; i < count; i++) NtAlertThreadByThreadId(tids[i]);
    return 0;
}

/* Only list removal is intercepted, before any links are changed. The queue
 * algorithms, stack allocation and unlocked return precheck are unmodified. */
#define list_remove remove_hook
#include "wine-futex.inc"
#undef list_remove

static void remove_hook(struct list *node) {
    if (race && !waiter) {
        struct futex_entry *entry = LIST_ENTRY(node, struct futex_entry, entry);
        int early = entry->addr == NULL;
        pthread_mutex_lock(&gate);
        release_wait = 1;
        pthread_cond_broadcast(&event);
        while (!returned && !retry_lock) pthread_cond_wait(&event, &gate);
        if (early && returned) {
            puts("NEGATIVE_CONTROL: waiter returned while its stack node was still linked");
            fflush(stdout);
            /* Do not dereference the expired stack object or invoke UB. */
            _Exit(42);
        }
        assert(!returned && retry_lock && !early);
        pthread_mutex_unlock(&gate);
    }
    list_remove(node);
}

static LONG address;
static void *wait_thread(void *unused) {
    (void)unused;
    const LONG zero = 0;
    const LARGE_INTEGER timeout = { .QuadPart = -50000000 };
    waiter = 1;
    NTSTATUS result = RtlWaitOnAddress(&address, &zero, sizeof(zero), &timeout);
    assert(result == (alert_result == STATUS_ALERTED ? STATUS_SUCCESS : STATUS_TIMEOUT));
    pthread_mutex_lock(&gate);
    returned = 1;
    pthread_cond_broadcast(&event);
    pthread_mutex_unlock(&gate);
    return NULL;
}

static void race_case(void) {
    pthread_t thread;
    assert(!pthread_create(&thread, NULL, wait_thread, NULL));
    pthread_mutex_lock(&gate);
    while (!queued) pthread_cond_wait(&event, &gate);
    pthread_mutex_unlock(&gate);
    if (all) RtlWakeAddressAll(&address); else RtlWakeAddressSingle(&address);
    assert(!pthread_join(thread, NULL));
    struct futex_queue *q = get_futex_queue(&address);
    assert(q->queue.next == &q->queue && q->queue.prev == &q->queue && !q->lock);
    assert(returned && retry_lock && alert_count == 1 && alerts[0] == 0x120);
}

static void semantics_case(void) {
    const uint64_t zero = 0, different = 1;
    LARGE_INTEGER timeout = {0};
    alert_result = STATUS_TIMEOUT;
    for (unsigned size = 1; size <= 8; size *= 2) {
        assert(RtlWaitOnAddress(&different, &zero, size, &timeout) == STATUS_SUCCESS);
        assert(RtlWaitOnAddress(&zero, &zero, size, &timeout) == STATUS_TIMEOUT);
        struct futex_queue *q = get_futex_queue(&zero);
        assert(q->queue.next == &q->queue && !q->lock);
    }
    assert(RtlWaitOnAddress(&zero, &zero, 3, &timeout) == STATUS_INVALID_PARAMETER);
    RtlWakeAddressSingle(NULL); RtlWakeAddressAll(NULL);
    assert(!alert_count);
    struct futex_entry entries[259];
    struct futex_queue *q = get_futex_queue(&address);
    list_init(&q->queue);
    for (unsigned i = 0; i < ARRAY_SIZE(entries); i++) {
        entries[i].addr = &address;
        entries[i].tid = 4 * (i + 1);
        list_add_tail(&q->queue, &entries[i].entry);
    }
    /* A different address in the same bucket must remain queued. */
    entries[1].addr = (void *)((uintptr_t)&address + 4096);
    RtlWakeAddressSingle(&address);
    assert(alert_count == 1 && alerts[0] == 4 && entries[0].addr == NULL);
    RtlWakeAddressAll(&address);
    assert(alert_count == 258 && entries[1].addr != NULL);
    for (unsigned i = 1; i < alert_count; i++) assert(alerts[i] == 4 * (i + 2));
    assert(q->queue.next == &entries[1].entry && q->queue.prev == &entries[1].entry);
    RtlWakeAddressAll(entries[1].addr);
    assert(alert_count == 259 && alerts[258] == 8 && list_empty(&q->queue));
}

int main(int argc, char **argv) {
    assert(argc == 2);
    alarm(8);
    if (!strcmp(argv[1], "semantics")) semantics_case();
    else {
        race = 1;
        all = strstr(argv[1], "all") != NULL;
        alert_result = strstr(argv[1], "timeout") ? STATUS_TIMEOUT : STATUS_ALERTED;
        race_case();
    }
    puts("PASS");
    return 0;
}

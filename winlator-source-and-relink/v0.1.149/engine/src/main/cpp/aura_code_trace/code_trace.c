/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Debug-only, bounded NtProtectVirtualMemory observation for Aura's verified Wine.
 * No FEX modification, signal handler, executable patch, protection policy or retry.
 * A single non-executable syscall-table pointer is replaced before guest execution.
 * Every wrapper calls the original exactly once, preserving arguments/status/errno.
 */
#define _GNU_SOURCE
#include "code_trace.h"
#include "../aura_loader/adreno_compiler_loader.h"
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#define NATIVE_NAME "libaura_091586ec99aa9aa25e1cb786.so"
#define FUNCTION_RVA 0x109dac
#define FUNCTION_HASH UINT64_C(0x641b2fbea9bb4940)
#define TABLE_SLOT_RVA 0x1bbc38 /* syscall_table + 0x50 * 8 */
typedef int32_t (*protect_fn)(void *, void **, size_t *, uint32_t, uint32_t *);
static protect_fn original;
static struct code_control *control;
static struct code_trace *trace;
static void *(*next_dlopen)(const char *, int);
static aura_system_dlopen_fn compiler_loader;
static char directory[PATH_MAX];
static int requested;
static _Atomic int installed;
static atomic_flag installing = ATOMIC_FLAG_INIT;
static atomic_flag recording = ATOMIC_FLAG_INIT;

static uint64_t now(clockid_t clock) {
    struct timespec time;
    return clock_gettime(clock, &time) ? 0 : (uint64_t)time.tv_sec * 1000000000 + (uint64_t)time.tv_nsec;
}
static uint64_t process_start(void) {
    char buffer[4096];
    int fd = open("/proc/self/stat", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t length = read(fd, buffer, sizeof(buffer) - 1); close(fd);
    if (length <= 0) return 0;
    buffer[length] = 0;
    char *p = strrchr(buffer, ')');
    if (!p) return 0;
    p += 2;
    for (unsigned field = 3; field < 22; field++) {
        p = strchr(p, ' '); if (!p) return 0;
        while (*p == ' ') p++;
    }
    return strtoull(p, NULL, 10);
}
static int read_self(const void *address, void *buffer, size_t bytes) {
    if ((uintptr_t)address < 0x10000 || (uintptr_t)address > UINTPTR_MAX - bytes) return 0;
#if defined(AURA_CODE_TRACE_TEST)
    int fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t result = pread(fd, buffer, bytes, (off_t)(uintptr_t)address); close(fd);
#else
    struct iovec local = {buffer, bytes}, remote = {(void *)address, bytes};
    ssize_t result = syscall(SYS_process_vm_readv, getpid(), &local, 1, &remote, 1, 0);
#endif
    return result == (ssize_t)bytes;
}
static void state(uint32_t value) { if (trace) atomic_store(&trace->header.state, value); }
static void open_storage(void) {
    uint64_t start = process_start();
    if (!start) return;
    int dir = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir < 0) return;
    int fd = openat(dir, "control.bin", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat stat;
    if (fd < 0 || fstat(fd, &stat) || !S_ISREG(stat.st_mode) || stat.st_size != 64) {
        if (fd >= 0) close(fd);
        close(dir); return;
    }
    void *memory = mmap(NULL, 64, PROT_READ, MAP_SHARED, fd, 0); close(fd);
    if (memory == MAP_FAILED) { close(dir); return; }
    struct code_control *c = memory;
    if (memcmp(c->magic, "AURACT1", 8) || c->version != 1 || c->bytes != 64) {
        munmap(memory, 64); close(dir); return;
    }
    control = c;
    fd = -1;
    for (unsigned slot = 1; slot <= 16; slot++) {
        char name[32]; snprintf(name, sizeof(name), "code-%02u.bin", slot);
        fd = openat(dir, name, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd >= 0 || errno != EEXIST) break;
    }
    close(dir);
    if (fd < 0) return;
    if (!ftruncate(fd, CODE_TRACE_BYTES)) {
        memory = mmap(NULL, CODE_TRACE_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (memory != MAP_FAILED) {
            trace = memory;
            trace->header.version = 1; trace->header.bytes = CODE_TRACE_BYTES;
            trace->header.pid = getpid(); trace->header.start_ticks = start;
            memcpy(trace->header.magic, "AURACP1", 8);
        }
    }
    close(fd);
}

/* Only executable-request or known previous-executable ranges retain addresses.
 * A candidate is not proof that FEX caused the request or that SMC succeeded. */
static int32_t observed_protect(void *process, void **address, size_t *size, uint32_t protection, uint32_t *old) {
    int saved_errno = errno;
    uint64_t epoch = 0, start = 0, cpu = 0, base = 0, length = 0;
    int readable = 0, local = (uintptr_t)process == UINTPTR_MAX;
    if (control && trace && trace->header.pid == (uint32_t)getpid()) {
        epoch = atomic_load_explicit(&control->epoch, memory_order_acquire);
        uint64_t deadline = atomic_load_explicit(&control->deadline_ns, memory_order_acquire);
        if (epoch && deadline) {
            start = now(CLOCK_MONOTONIC);
            if (!start || start >= deadline || deadline - start > UINT64_C(10000000000)) start = 0;
        }
        if (start && !atomic_flag_test_and_set_explicit(&recording, memory_order_acquire)) {
            if (epoch != atomic_load(&control->epoch) || start >= atomic_load(&control->deadline_ns)) {
                atomic_flag_clear_explicit(&recording, memory_order_release);
                start = 0;
            } else {
            struct code_header *h = &trace->header;
            atomic_fetch_add_explicit(&h->sequence, 1, memory_order_acq_rel);
            if (h->epoch != epoch) {
                memset(trace->rows, 0, sizeof(trace->rows)); memset(trace->events, 0, sizeof(trace->events));
                h->epoch = epoch; h->returns = h->foreign = h->unreadable = h->nonexec = h->row_overflow = h->events = 0;
                h->attempts = h->limited = 0; atomic_store(&h->dropped, 0);
            }
            if (h->attempts >= CODE_TRACE_BUDGET) { h->limited = 1; start = 0; }
            else h->attempts++;
            atomic_fetch_add_explicit(&h->sequence, 1, memory_order_release);
            atomic_flag_clear_explicit(&recording, memory_order_release);
            }
        } else if (start) {
            atomic_fetch_add(&trace->header.dropped, 1); start = 0;
        }
        if (start && local) {
            readable = read_self(address, &base, sizeof(base)) && read_self(size, &length, sizeof(length));
            cpu = now(CLOCK_THREAD_CPUTIME_ID);
        }
    }
    errno = saved_errno;
    int32_t result = original(process, address, size, protection, old);
    saved_errno = errno;
    if (start) {
        uint64_t end = now(CLOCK_MONOTONIC), cpu_end = local ? now(CLOCK_THREAD_CPUTIME_ID) : 0;
        uint32_t previous = 0;
        int previous_read = result >= 0 && local && read_self(old, &previous, sizeof(previous));
        if (epoch == atomic_load(&control->epoch) && end >= start && end < atomic_load(&control->deadline_ns)) {
            if (!atomic_flag_test_and_set_explicit(&recording, memory_order_acquire)) {
                if (trace->header.epoch != epoch || epoch != atomic_load(&control->epoch) ||
                    end >= atomic_load(&control->deadline_ns)) {
                    atomic_flag_clear_explicit(&recording, memory_order_release);
                    goto finished;
                }
                atomic_fetch_add_explicit(&trace->header.sequence, 1, memory_order_acq_rel);
                struct code_header *h = &trace->header;
                h->returns++;
                if (!local) h->foreign++;
                else if (!readable || !base || !length || base > UINT64_MAX - length) h->unreadable++;
                else if (!(protection & 0xf0) && !(previous_read && (previous & 0xf0))) h->nonexec++;
                else {
                    struct code_event event = { .at_ns = start, .base = base, .size = length,
                        .wall_ns = end - start, .cpu_ns = cpu && cpu_end >= cpu ? cpu_end - cpu : 0,
                        .tid = (uint32_t)syscall(SYS_gettid), .requested = protection, .previous = previous_read ? previous : 0,
                        .status = (uint32_t)result, .flags = (previous_read ? 1U : 0U) | (cpu && cpu_end >= cpu ? 2U : 0U) };
                    unsigned row;
                    for (row = 0; row < CODE_TRACE_ROWS; row++) {
                        struct code_row *r = &trace->rows[row];
                        if (!r->calls || (r->base == base && r->size == length)) {
                            r->base = base; r->size = length; r->calls++; r->failures += result < 0;
                            r->wall_ns += event.wall_ns; r->cpu_ns += event.cpu_ns;
                            r->cpu_calls += !!(event.flags & 2);
                            r->writable += !!(protection & (0x04 | 0x08 | 0x40 | 0x80));
                            r->status = event.status; r->requested = protection; r->previous = event.previous; r->tid = event.tid;
                            break;
                        }
                    }
                    if (row == CODE_TRACE_ROWS) h->row_overflow++;
                    trace->events[h->events++ % CODE_TRACE_EVENTS] = event;
                }
                atomic_fetch_add_explicit(&h->sequence, 1, memory_order_release);
                atomic_flag_clear_explicit(&recording, memory_order_release);
            } else atomic_fetch_add(&trace->header.dropped, 1);
        }
    }
finished:
    errno = saved_errno;
    return result;
}

#if defined(__aarch64__) || defined(AURA_CODE_TRACE_TEST)
static uint64_t fingerprint(const void *pointer) {
    const unsigned char *p = pointer;
    uint64_t hash = UINT64_C(14695981039346656037);
    for (unsigned i = 0; i < 64; i++) hash = (hash ^ p[i]) * UINT64_C(1099511628211);
    return hash;
}
static int table_pages(uintptr_t base, uintptr_t first, uintptr_t last, uintptr_t page) {
    const Elf64_Ehdr *elf = (const void *)base;
    if (memcmp(elf->e_ident, ELFMAG, SELFMAG) || elf->e_ident[EI_CLASS] != ELFCLASS64 ||
        elf->e_phentsize != sizeof(Elf64_Phdr) || !elf->e_phnum || elf->e_phnum > 32 || elf->e_phoff > 4096) return 0;
    const Elf64_Phdr *headers = (const void *)(base + elf->e_phoff);
    int relro = 0, data = 0, function = 0;
    uintptr_t begin = first & ~(page - 1), end = (last + page - 1) & ~(page - 1);
    for (unsigned i = 0; i < elf->e_phnum; i++) {
        const Elf64_Phdr *p = headers + i;
        if (p->p_vaddr > UINTPTR_MAX - base || base + p->p_vaddr > UINTPTR_MAX - page ||
            p->p_memsz > UINTPTR_MAX - (base + p->p_vaddr) - page) return 0;
        uintptr_t lo = base + p->p_vaddr, hi = lo + p->p_memsz;
        if (p->p_type == PT_GNU_RELRO && first >= lo && last <= hi) relro = 1;
        if (p->p_type != PT_LOAD) continue;
        if (p->p_flags == (PF_R | PF_X) && base + FUNCTION_RVA >= lo && base + FUNCTION_RVA + 64 <= hi) function = 1;
        lo &= ~(page - 1); hi = (hi + page - 1) & ~(page - 1);
        if (begin < hi && end > lo && (p->p_flags & PF_X)) return 0;
        if (begin >= lo && end <= hi && p->p_flags == (PF_R | PF_W)) data = 1;
    }
    return relro && data && function;
}
#endif

static void install(void *handle) {
#if defined(__aarch64__)
    void *function = dlsym(handle, "NtProtectVirtualMemory");
    Dl_info info = {0};
    if (!function || !dladdr(function, &info) || !info.dli_fbase || !info.dli_fname) { state(3); return; }
    const char *name = strrchr(info.dli_fname, '/'); name = name ? name + 1 : info.dli_fname;
    if (strcmp(name, NATIVE_NAME)) {
        char resolved[PATH_MAX];
        char *(*resolve)(const char *, char *) = dlsym(RTLD_NEXT, "realpath");
        if (!resolve || !resolve(info.dli_fname, resolved) || !(name = strrchr(resolved, '/')) || strcmp(name + 1, NATIVE_NAME)) {
            state(3); return;
        }
    }
    uintptr_t base = (uintptr_t)info.dli_fbase;
    void **slot = (void **)(base + TABLE_SLOT_RVA);
    long page = sysconf(_SC_PAGESIZE);
    if ((page != 4096 && page != 16384) || function != (void *)(base + FUNCTION_RVA) ||
        !table_pages(base, (uintptr_t)slot, (uintptr_t)(slot + 1), page) ||
        fingerprint(function) != FUNCTION_HASH || *slot != function) { state(4); return; }
    uintptr_t begin = (uintptr_t)slot & ~((uintptr_t)page - 1);
    original = function;
    if (mprotect((void *)begin, page, PROT_READ | PROT_WRITE)) { state(5); return; }
    __atomic_store_n(slot, observed_protect, __ATOMIC_RELEASE);
    if (mprotect((void *)begin, page, PROT_READ)) {
        __atomic_store_n(slot, function, __ATOMIC_RELEASE);
        (void)mprotect((void *)begin, page, PROT_READ);
        state(6); return;
    }
    installed = 1; state(1);
#else
    (void)handle; state(2);
#endif
}
__attribute__((constructor)) static void configure(void) {
    next_dlopen = dlsym(RTLD_NEXT, "dlopen");
    compiler_loader = aura_adreno_loader_resolve();
    const char *enable = getenv("AURA_CODE_TRACE"), *path = getenv("AURA_CODE_DIRECTORY");
    if (enable && !strcmp(enable, "1") && path && path[0] == '/' && strlen(path) < sizeof(directory)) {
        strcpy(directory, path); requested = 1; open_storage();
    }
}
#ifndef AURA_CODE_TRACE_TEST
void *dlopen(const char *name, int flags) {
    const void *caller = __builtin_extract_return_addr(__builtin_return_address(0));
    void *handle = aura_adreno_dlopen(compiler_loader, next_dlopen, name, flags, caller);
    int saved_errno = errno;
    const char *basename = name ? strrchr(name, '/') : NULL; basename = basename ? basename + 1 : name;
    if (handle && requested && trace && !installed && basename &&
        (!strcmp(basename, "ntdll.so") || !strcmp(basename, NATIVE_NAME)) &&
        !atomic_flag_test_and_set_explicit(&installing, memory_order_acquire)) {
        install(handle);
        atomic_flag_clear_explicit(&installing, memory_order_release);
    }
    errno = saved_errno; return handle;
}
#endif

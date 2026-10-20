/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Opt-in call boundary observation for the exact shipped Bionic ntdll.
 * Only its four data-table slots are redirected, before Wine starts its
 * threads. Original functions, arguments, return codes, errno and deadlines
 * are retained. No executable code, alert flag, lock or game data is patched.
 */
#define _GNU_SOURCE
#include "wake_ring.h"
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
#include <unistd.h>

typedef int32_t (*wait_fn)(const void *, const int64_t *);
typedef int32_t (*alert_fn)(void *);
typedef int32_t (*multi_fn)(void **, uint32_t, void *, void *);
typedef int32_t (*completion_fn)(void *);
static wait_fn original_wait;
static alert_fn original_alert;
static multi_fn original_multi;
static completion_fn original_completion;
#define NATIVE_NAME "libaura_77e3942211cfadbed242b8ef.so"
#define ALERT_BLOCKS_RVA 0x217a50
#define SYSCALL_TABLE_RVA 0x1bb968
#define COMPLETION_TABLE_RVA 0x1bc178
#define COMPLETION_RVA 0xf640c
#define COMPLETION_FNV UINT64_C(17446221209677784933)
static void *(*current_teb)(void);
static void *(*next_dlopen)(const char *, int);
static uintptr_t native_base;
static struct wake_ring *ring;
static char output_directory[PATH_MAX];
static int requested;
static _Atomic int installed;
static atomic_flag installing = ATOMIC_FLAG_INIT;
static _Atomic int process_kind; /* 0 not initialized, -1 other image, 1 Mirror's Edge */
static _Atomic uint32_t leader_tid;
static _Atomic uint64_t calls;
static _Atomic int module_snapshot;
static _Thread_local uint32_t linux_tid;

static int read_self(uintptr_t address, void *buffer, size_t bytes) {
    if (address < 0x10000 || bytes > 4096 || address > UINTPTR_MAX - bytes) return 0;
#if defined(AURA_WAKE_TEST)
    /* The host sandbox lacks process_vm_readv. The test backend reads only
     * the harness process; this branch is never included in the APK. */
    int fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t result = pread(fd, buffer, bytes, (off_t)address);
    close(fd);
    return result == (ssize_t)bytes;
#else
    struct iovec local = {buffer, bytes}, remote = {(void *)address, bytes};
    return syscall(SYS_process_vm_readv, getpid(), &local, 1, &remote, 1, 0) == (ssize_t)bytes;
#endif
}

static uint64_t hash(const void *data, size_t size) {
    const unsigned char *bytes = data;
    uint64_t value = UINT64_C(14695981039346656037);
    for (size_t i = 0; i < size; ++i) value = (value ^ bytes[i]) * UINT64_C(1099511628211);
    return value;
}

static void state(const char *value) {
    /* Bounded setup notes, including failures before an event ring exists.
     * No pipe writes or signal handler changes. At most 16 notes per launch. */
    char message[192];
    int n = snprintf(message, sizeof(message), "WINE_WAKE_TRACE_V1 pid=%u; state=%s; source=NTDLL_77E39422; capacity=8192\n",
                     (unsigned)getpid(), value);
    if (n > 0 && n < (int)sizeof(message)) {
        int dir = open(output_directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (dir < 0) return;
        for (unsigned i = 1; i <= 16; ++i) {
            char name[32];
            snprintf(name, sizeof(name), "setup-%02u.txt", i);
            int fd = openat(dir, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
            if (fd < 0) { if (errno == EEXIST) continue; break; }
            (void)write(fd, message, n);
            close(fd);
            break;
        }
        close(dir);
    }
}

static int mirror_image(uintptr_t teb) {
    uintptr_t peb = 0, parameters = 0;
    struct { uint16_t length, maximum; uint32_t pad; uint64_t buffer; } image = {0};
    if (!read_self(teb + 0x60, &peb, 8) || !read_self(peb + 0x20, &parameters, 8) ||
        !read_self(parameters + 0x60, &image, sizeof(image)) || !image.buffer ||
        image.length < 30 || image.length > 4096 || (image.length & 1)) return 0;
    uint16_t tail[16] = {0};
    unsigned chars = image.length / 2;
    unsigned count = chars >= 16 ? 16 : 15;
    if (!read_self(image.buffer + image.length - count * 2, tail, count * 2)) return 0;
    const char *expected = "mirrorsedge.exe";
    unsigned offset = count - 15;
    if (offset && tail[0] != '\\' && tail[0] != '/') return -1;
    for (unsigned i = 0; i < 15; ++i) {
        uint16_t c = tail[offset + i];
        if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
        if (c != (unsigned char)expected[i]) return -1;
    }
    return 1;
}

static void open_ring(uint32_t tid) {
    int dir = open(output_directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir < 0) return;
    char name[48];
    int fd = -1;
    for (unsigned slot = 1; slot <= 4; ++slot) {
        snprintf(name, sizeof(name), "wake-%u.bin", slot);
        fd = openat(dir, name, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd >= 0 || errno != EEXIST) break;
    }
    close(dir);
    if (fd < 0) return;
    if (!ftruncate(fd, sizeof(struct wake_ring))) {
        void *memory = mmap(NULL, sizeof(struct wake_ring), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (memory != MAP_FAILED) {
            aura_wake_init(memory, getpid(), tid, native_base);
            ring = memory;
        }
    }
    close(fd);
}

/* Resolve a few native PE module bases once a five-second wait is reached.
 * Only basenames are kept. This permits later attribution of syscall-frame LR
 * to wow64.dll versus native ntdll/FEX; no guessed origin label is emitted.
 */
static void modules(uintptr_t teb) {
    int expected = 0;
    if (!ring || !atomic_compare_exchange_strong(&module_snapshot, &expected, 1)) return;
    uintptr_t peb = 0, ldr = 0, node = 0;
    if (!read_self(teb + 0x60, &peb, 8) || !read_self(peb + 0x18, &ldr, 8) ||
        !read_self(ldr + 0x10, &node, 8)) return;
    unsigned count = 0;
    for (unsigned scanned = 0; node != ldr + 0x10 && scanned < 128 && count < 32; ++scanned) {
        unsigned char entry[0x68];
        if (!read_self(node, entry, sizeof(entry))) break;
        uintptr_t next, base, name;
        uint32_t size;
        uint16_t length;
        memcpy(&next, entry, 8); memcpy(&base, entry + 0x30, 8);
        memcpy(&size, entry + 0x40, 4); memcpy(&length, entry + 0x58, 2);
        memcpy(&name, entry + 0x60, 8);
        if (base && size && length && length <= 94 && !(length & 1)) {
            uint16_t text[48] = {0};
            if (read_self(name, text, length)) {
                struct wake_module *module = &ring->header.modules[count];
                module->base = base; module->size = size;
                for (unsigned j = 0; j < length / 2; ++j) {
                    unsigned c = text[j];
                    module->name[j] = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') ||
                        (c >= 'a' && c <= 'z') || c == '.' || c == '_' || c == '-' ? c : '_';
                }
                ++count;
            }
        }
        if (next == node) break;
        node = next;
    }
    atomic_store_explicit(&ring->header.module_count, count, memory_order_release);
}

static int context(struct wake_event *event, uint32_t target) {
    if (atomic_load_explicit(&process_kind, memory_order_relaxed) < 0) return 0;
    if (atomic_load_explicit(&leader_tid, memory_order_acquire) && ring &&
        (ring->header.pid != (uint32_t)getpid() || atomic_load(&ring->header.stop))) return 0;
    uintptr_t teb = (uintptr_t)current_teb();
    if (!teb) return 0;
    if (!linux_tid) linux_tid = syscall(SYS_gettid);
    uint32_t tid = 0;
    if (!read_self(teb + 0x48, &tid, sizeof(tid)) || !tid) return 0;
    if (linux_tid == (uint32_t)getpid() && !atomic_load_explicit(&leader_tid, memory_order_acquire)) {
        int kind = mirror_image(teb);
        if (!kind) return 0;
        atomic_store(&process_kind, kind);
        if (kind < 0) return 0;
        open_ring(tid);
        /* Publication protects the initialized mapping against worker reads. */
        atomic_store_explicit(&leader_tid, tid, memory_order_release);
    }
    uint32_t leader = atomic_load_explicit(&leader_tid, memory_order_acquire);
    if (!leader || !ring || ring->header.pid != (uint32_t)getpid() || atomic_load(&ring->header.stop)) return 0;
    if (target ? target != leader : tid != leader) return 0;
    event->wine_tid = tid; event->linux_tid = linux_tid;
    event->target_tid = target ? target : tid;
    uintptr_t frame = 0;
    if (read_self(teb + 0x378, &frame, 8))
        (void)read_self(frame + 0xf0, &event->caller_lr, 8);
    event->call_id = atomic_fetch_add_explicit(&calls, 1, memory_order_relaxed) + 1;
    return 1;
}

static int32_t flag(uint32_t tid) {
    if (!tid || (tid & 3)) return -1;
    uint32_t index = (tid >> 2) - 1, block = index / 16384;
    if (block >= 4096) return -1;
    uintptr_t *table = (uintptr_t *)(native_base + ALERT_BLOCKS_RVA);
    uintptr_t address = __atomic_load_n(&table[block], __ATOMIC_ACQUIRE);
    return address ? __atomic_load_n((int32_t *)(address + (index % 16384) * 4), __ATOMIC_ACQUIRE) : -1;
}

static void record(struct wake_event *event, uint32_t phase, int32_t result) {
    event->phase = phase; event->status = result;
    event->time_ns = aura_wake_clock();
    event->flag = flag(event->target_tid);
    aura_wake_record(ring, event);
}

static int32_t traced_wait(const void *address, const int64_t *timeout) {
    int before_errno = errno;
    struct wake_event event = {0};
    int trace = context(&event, 0);
    if (trace) {
        event.argument = (uintptr_t)address;
        event.timeout = INT64_MAX; /* NULL = infinite; unreadable = INT64_MIN. */
        if (timeout && !read_self((uintptr_t)timeout, &event.timeout, 8)) event.timeout = INT64_MIN;
        if (event.timeout == -50000000) modules((uintptr_t)current_teb());
        record(&event, WAIT_ENTER, 0);
    }
    uint64_t began = event.time_ns;
    errno = before_errno;
    int32_t status = original_wait(address, timeout);
    int after_errno = errno;
    if (trace) {
        event.duration_ns = aura_wake_clock() - began;
        record(&event, WAIT_RETURN, status);
    }
    errno = after_errno;
    return status;
}

static uint64_t completion_observation(uintptr_t address) {
    uint32_t value;
    return read_self(address, &value, sizeof(value)) ? (value == 0) : UINT64_MAX;
}

/* The new i386 helper can return STATUS_ALERTED without consuming an alert.
 * Keep distinct phases: neither this status nor a boundary flag proves the
 * helper's branch at the exact instant it executed. The original is called
 * once with its identical parameter pointer, status and errno.
 */
static int32_t traced_completion(void *args) {
    int before_errno = errno;
    struct wake_event event = {0};
    int trace = context(&event, 0);
    if (trace) {
        uint32_t params[2] = {0};
        event.timeout = INT64_MIN;
        event.reserved = UINT64_MAX;
        if (read_self((uintptr_t)args, params, sizeof(params))) {
            event.argument = params[0];
            event.reserved = completion_observation(params[0]);
            event.timeout = INT64_MAX;
            if (params[1] && !read_self(params[1], &event.timeout, 8)) event.timeout = INT64_MIN;
        }
        if (event.timeout == -50000000) modules((uintptr_t)current_teb());
        record(&event, COMPLETION_ENTER, 0);
    }
    uint64_t began = event.time_ns;
    errno = before_errno;
    int32_t status = original_completion(args);
    int after_errno = errno;
    if (trace) {
        event.duration_ns = aura_wake_clock() - began;
        event.reserved = completion_observation(event.argument);
        record(&event, COMPLETION_RETURN, status);
    }
    errno = after_errno;
    return status;
}

static int32_t traced_alert(void *target) {
    int before_errno = errno;
    struct wake_event event = {0};
    int trace = (uintptr_t)target <= UINT32_MAX && target && context(&event, (uint32_t)(uintptr_t)target);
    if (trace) record(&event, ALERT_ENTER, 0);
    uint64_t began = event.time_ns;
    errno = before_errno;
    int32_t status = original_alert(target);
    int after_errno = errno;
    if (trace) {
        event.duration_ns = aura_wake_clock() - began;
        record(&event, ALERT_RETURN, status);
    }
    errno = after_errno;
    return status;
}

static int32_t traced_multi(void **targets, uint32_t count, void *a, void *b) {
    int before_errno = errno;
    struct wake_event event = {0};
    uint32_t leader = atomic_load_explicit(&leader_tid, memory_order_acquire);
    uintptr_t ids[256];
    int trace = 0;
    int eligible = leader && ring && ring->header.pid == (uint32_t)getpid() && !atomic_load(&ring->header.stop);
    if (eligible && count && count <= 256 && read_self((uintptr_t)targets, ids, count * 8)) {
        for (uint32_t i = 0; i < count; ++i) if (ids[i] == leader) { trace = context(&event, leader); break; }
    } else if (eligible && count) {
        atomic_fetch_add_explicit(&ring->header.unobserved_multi, 1, memory_order_relaxed);
    }
    if (trace) { event.argument = count; record(&event, MULTI_ENTER, 0); }
    uint64_t began = event.time_ns;
    errno = before_errno;
    int32_t status = original_multi(targets, count, a, b);
    int after_errno = errno;
    if (trace) {
        event.duration_ns = aura_wake_clock() - began;
        record(&event, MULTI_RETURN, status);
    }
    errno = after_errno;
    return status;
}

static int protected_table(uintptr_t base, uintptr_t first, uintptr_t last) {
    const Elf64_Ehdr *elf = (const void *)base;
    if (memcmp(elf->e_ident, ELFMAG, SELFMAG) || elf->e_ident[EI_CLASS] != ELFCLASS64 ||
        elf->e_phentsize != sizeof(Elf64_Phdr) || elf->e_phnum > 32 ||
        elf->e_phoff > 4096) return 0;
    const Elf64_Phdr *headers = (const void *)(base + elf->e_phoff);
    int relro = 0, writable = 0;
    for (unsigned i = 0; i < elf->e_phnum; ++i) {
        const Elf64_Phdr *p = headers + i;
        if (p->p_vaddr > UINTPTR_MAX - base || p->p_memsz > UINTPTR_MAX - (base + p->p_vaddr)) return 0;
        uintptr_t low = base + p->p_vaddr, high = low + p->p_memsz;
        if (first >= low && last <= high) {
            if (p->p_type == PT_GNU_RELRO) relro = 1;
            if (p->p_type == PT_LOAD && p->p_flags == (PF_R | PF_W)) writable = 1;
        }
    }
    return relro && writable;
}

static int nonexecutable_pages(uintptr_t base, uintptr_t first, uintptr_t last, uintptr_t page) {
    const Elf64_Ehdr *elf = (const void *)base;
    const Elf64_Phdr *headers = (const void *)(base + elf->e_phoff);
    int covered = 0;
    for (unsigned i = 0; i < elf->e_phnum; ++i) {
        const Elf64_Phdr *p = headers + i;
        if (p->p_type != PT_LOAD) continue;
        uintptr_t low = (base + p->p_vaddr) & ~(page - 1);
        uintptr_t high = (base + p->p_vaddr + p->p_memsz + page - 1) & ~(page - 1);
        if (first < high && last > low && (p->p_flags & PF_X)) return 0;
        if (first >= low && last <= high && p->p_flags == (PF_R | PF_W)) covered = 1;
    }
    return covered;
}

static void install(void *handle) {
#if defined(__aarch64__)
    if (installed || !requested) return;
    void *wait = dlsym(handle, "NtWaitForAlertByThreadId");
    Dl_info info = {0};
    if (!wait || !dladdr(wait, &info) || !info.dli_fbase || !info.dli_fname) return;
    const char *name = strrchr(info.dli_fname, '/');
    name = name ? name + 1 : info.dli_fname;
    if (strcmp(name, NATIVE_NAME)) {
        /* Some linkers retain the logical symlink name. Bypass the earlier
         * Wine logical-path shim when checking its actual installed target. */
        char resolved[PATH_MAX];
        char *(*resolve)(const char *, char *) = dlsym(RTLD_NEXT, "realpath");
        if (!resolve || !resolve(info.dli_fname, resolved)) { state("IDENTITY_UNAVAILABLE"); return; }
        name = strrchr(resolved, '/');
        if (!name || strcmp(name + 1, NATIVE_NAME)) {
            state("IDENTITY_MISMATCH"); return;
        }
    }
    uintptr_t base = (uintptr_t)info.dli_fbase;
    const struct { const char *name; uintptr_t rva; uint64_t hash; unsigned index; void *wrapper; } functions[] = {
        {"NtAlertThreadByThreadId", 0xf5fac, UINT64_C(15291833780092925767), 103, traced_alert},
        {"NtWaitForAlertByThreadId", 0xf6114, UINT64_C(9838130541946745358), 255, traced_wait},
        {"NtAlertMultipleThreadByThreadId", 0xf5eb0, UINT64_C(3229217557870019904), 100, traced_multi},
    };
    void *teb = dlsym(handle, "NtCurrentTeb");
    if (teb != (void *)(base + 0x101c98) || hash(teb, 64) != UINT64_C(15725814003289649945)) {
        state("TEB_LAYOUT_MISMATCH"); return;
    }
    void **table = (void **)(base + SYSCALL_TABLE_RVA);
    void **completion = (void **)(base + COMPLETION_TABLE_RVA);
    if (!protected_table(base, (uintptr_t)&table[100], (uintptr_t)&completion[15])) {
        state("TABLE_LAYOUT_MISMATCH"); return;
    }
    for (unsigned i = 0; i < 3; ++i) {
        void *fn = dlsym(handle, functions[i].name);
        if (fn != (void *)(base + functions[i].rva) || hash(fn, 64) != functions[i].hash ||
            table[functions[i].index] != fn) { state("LAYOUT_MISMATCH"); return; }
    }
    if (completion[14] != (void *)(base + COMPLETION_RVA) ||
        hash(completion[14], 64) != COMPLETION_FNV) { state("COMPLETION_LAYOUT_MISMATCH"); return; }
    long page = sysconf(_SC_PAGESIZE);
    if (page != 4096 && page != 16384) return;
    uintptr_t start = (uintptr_t)&table[100] & ~((uintptr_t)page - 1);
    uintptr_t end = ((uintptr_t)&completion[15] + page - 1) & ~((uintptr_t)page - 1);
    /* The page-rounded range must also stay inside the non-executable load.
     * PT_GNU_RELRO itself need not start/end on a page boundary. */
    if (!nonexecutable_pages(base, start, end, page)) { state("TABLE_PAGE_MISMATCH"); return; }
    original_alert = table[103]; original_wait = table[255]; original_multi = table[100];
    original_completion = completion[14];
    current_teb = teb; native_base = base;
    if (mprotect((void *)start, end - start, PROT_READ | PROT_WRITE)) { state("TABLE_READ_ONLY"); return; }
    for (unsigned i = 0; i < 3; ++i)
        __atomic_store_n(&table[functions[i].index], functions[i].wrapper, __ATOMIC_RELEASE);
    __atomic_store_n(&completion[14], traced_completion, __ATOMIC_RELEASE);
    if (mprotect((void *)start, end - start, PROT_READ)) {
        table[103] = original_alert; table[255] = original_wait; table[100] = original_multi;
        completion[14] = original_completion;
        (void)mprotect((void *)start, end - start, PROT_READ);
        state("TABLE_RESTORE_FAILED");
        return;
    }
    installed = 1;
    state("INSTALLED");
#else
    (void)handle;
#endif
}

__attribute__((constructor)) static void configure(void) {
    next_dlopen = dlsym(RTLD_NEXT, "dlopen");
    const char *enable = getenv("AURA_WINE_WAKE_TRACE");
    const char *directory = getenv("AURA_WINE_WAKE_DIRECTORY");
    if (enable && !strcmp(enable, "1") && directory && directory[0] == '/' &&
        strlen(directory) < sizeof(output_directory)) {
        strcpy(output_directory, directory);
        requested = 1;
    }
}

#if !defined(AURA_WAKE_TEST)
void *dlopen(const char *name, int flags) {
    if (!next_dlopen) { errno = ENOSYS; return NULL; }
    void *handle = next_dlopen(name, flags);
    int saved_errno = errno;
    /* Only inspect the ntdll load; leave unrelated dlerror state untouched. */
    const char *basename = name ? strrchr(name, '/') : NULL;
    basename = basename ? basename + 1 : name;
    if (handle && requested && !installed && basename &&
        (!strcmp(basename, "ntdll.so") || !strcmp(basename, NATIVE_NAME)) &&
        !atomic_flag_test_and_set_explicit(&installing, memory_order_acquire)) {
        install(handle);
        atomic_flag_clear_explicit(&installing, memory_order_release);
    }
    errno = saved_errno;
    return handle;
}
#endif

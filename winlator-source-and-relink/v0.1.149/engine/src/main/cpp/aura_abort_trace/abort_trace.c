/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Startup-only imported abort/assert observation. No signal handler, instruction
 * patch, stderr redirection or recovery. Internal/hidden libc calls may bypass
 * interposition; no event is NOT evidence that no abort occurred.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

struct control {
    char magic[8];
    uint64_t deadline_ms;
    _Atomic uint32_t enabled, events, ready;
    uint32_t reserved[9];
};
_Static_assert(sizeof(struct control) == 64, "startup control ABI");
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "fatal observation must not acquire atomic locks");
static struct control *control;
static int output_fd = -1;
static void (*next_abort)(void);
static void (*next_assert2)(const char *, int, const char *, const char *);
static void (*next_assert)(const char *, int, const char *);
static void (*next_abort_message)(const char *);
static _Atomic unsigned reported;
static _Thread_local int observing;
static _Thread_local const char *message_category;

/* Classify a bounded prefix; never retain the message, paths or shader text. */
static const char *classify_message(const char *message) {
    if (!message) return "UNCLASSIFIED";
    char prefix[513]; size_t n = strnlen(message, 512);
    for (size_t i = 0; i < n; ++i) {
        unsigned char c = message[i]; prefix[i] = c >= 'A' && c <= 'Z' ? (char)(c + 'a' - 'A') : (char)c;
    }
    prefix[n] = 0;
    if (strstr(prefix, "out of memory") || strstr(prefix, "out-of-memory")) return "OUT_OF_MEMORY";
    if (strstr(prefix, "spirv") || strstr(prefix, "spir-v")) return "SPIRV";
    if (strstr(prefix, "shader") || strstr(prefix, "llvm") || strstr(prefix, "compil")) return "COMPILER";
    if (strstr(prefix, "assert")) return "ASSERTION";
    if (strstr(prefix, "unreachable")) return "UNREACHABLE";
    return "UNCLASSIFIED";
}

static uint64_t clock_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_BOOTTIME, &ts)) return 0;
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}
static int active(void) {
    uint64_t now = clock_ms();
    return control && output_fd >= 0 && atomic_load_explicit(&control->enabled, memory_order_acquire) == 1 &&
        now && now < control->deadline_ms && control->deadline_ms - now <= 120000;
}
static int owned_regular(int fd, off_t size, int exact) {
    struct stat s;
    return fd >= 0 && !fstat(fd, &s) && S_ISREG(s.st_mode) && s.st_uid == geteuid() &&
        s.st_nlink == 1 && !(s.st_mode & 0077) && (exact ? s.st_size == size : s.st_size <= size);
}
__attribute__((constructor)) static void configure(void) {
    int saved = errno;
    next_abort = dlsym(RTLD_NEXT, "abort");
    next_assert2 = dlsym(RTLD_NEXT, "__assert2");
    next_assert = dlsym(RTLD_NEXT, "__assert");
    next_abort_message = dlsym(RTLD_NEXT, "android_set_abort_message");
    const char *enabled = getenv("AURA_STARTUP_ABORT_TRACE");
    const char *directory = getenv("AURA_STARTUP_ABORT_DIRECTORY");
    if (!next_abort || !enabled || strcmp(enabled, "1") || !directory || directory[0] != '/') goto done;
    int dir = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (dir < 0) goto done;
    if (fstat(dir, &st) || st.st_uid != geteuid()) { close(dir); goto done; }
    int fd = openat(dir, ".abort-control", O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (!owned_regular(fd, 64, 1)) { if (fd >= 0) close(fd); close(dir); goto done; }
    struct control *mapped = mmap(NULL, sizeof(*mapped), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) { close(dir); goto done; }
    if (memcmp(mapped->magic, "AURAAB1", 8)) { munmap(mapped, 64); close(dir); goto done; }
    output_fd = openat(dir, ".abort-events", O_WRONLY | O_APPEND | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    close(dir);
    if (!owned_regular(output_fd, 32768, 0)) {
        if (output_fd >= 0) close(output_fd);
        output_fd = -1; munmap(mapped, 64); goto done;
    }
    control = mapped;
    if (active()) atomic_fetch_add_explicit(&control->ready, 1, memory_order_relaxed);
done:
    errno = saved;
}

/* A private regular file avoids blocking a failed process on the Wine stderr
 * pipe or changing the shared pipe's flags/signal mask. Writes fit one record. */
static void emit(const char *text, int length) {
    if (length > 0 && length < 320 && active()) (void)write(output_fd, text, (size_t)length);
}
static int allowed_module(const char *name, size_t length) {
    if (length <= 6 || length >= 97 || strcmp(name + length - 3, ".so")) return 0;
    for (size_t i = 0; i < length; ++i) {
        unsigned char c = name[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) return 0;
    }
    if (!strncmp(name, "lib", 3)) return 1;
    // Fixed basenames from the bundled v863.1 archive and the Android Adreno
    // HAL. Do not export arbitrary executable names, full paths or raw PCs.
    static const char *const adreno[] = {
        "notadreno_utils.so", "notgsl.so", "notllvm-glnext.so", "notllvm-qcom.so",
        "notllvm-qgl.so", "vulkan.ad0863.so", "vulkan.adreno.so"
    };
    for (size_t i = 0; i < sizeof(adreno) / sizeof(adreno[0]); ++i)
        if (!strcmp(name, adreno[i])) return 1;
    return 0;
}
static void frame(unsigned event, unsigned index, uintptr_t pc, const char *kind) {
    Dl_info info = {0};
    char module[97] = "UNKNOWN";
    uintptr_t offset = 0;
    if (dladdr((void *)pc, &info) && info.dli_fname && info.dli_fbase && pc >= (uintptr_t)info.dli_fbase) {
        const char *name = strrchr(info.dli_fname, '/');
        name = name ? name + 1 : info.dli_fname;
        size_t n = strnlen(name, sizeof(module));
        if (allowed_module(name, n)) { memcpy(module, name, n + 1); offset = pc - (uintptr_t)info.dli_fbase; }
    }
    char text[320];
    emit(text, snprintf(text, sizeof(text), "NATIVE_ABORT_FRAME_V1 event=%u; index=%u; kind=%s; "
        "module=%s; image_offset=0x%" PRIxPTR "\n", event, index, kind, module, offset));
}
struct frames { uintptr_t pcs[15]; unsigned count; uint64_t until; };
static _Unwind_Reason_Code unwind(struct _Unwind_Context *ctx, void *opaque) {
    struct frames *f = opaque;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (!pc || f->count == 15 || !active() || clock_ms() >= f->until) return _URC_END_OF_STACK;
    f->pcs[f->count++] = pc;
    return _URC_NO_REASON;
}
static void observe(const char *origin, uintptr_t caller) {
    if (observing || !active() || atomic_exchange_explicit(&reported, 1, memory_order_relaxed)) return;
    unsigned event = atomic_load_explicit(&control->events, memory_order_relaxed);
    do {
        if (event >= 4) return;
    } while (!atomic_compare_exchange_weak_explicit(&control->events, &event, event + 1,
                                                   memory_order_relaxed, memory_order_relaxed));
    event++;
    observing = 1;
    char text[320];
    emit(text, snprintf(text, sizeof(text), "NATIVE_ABORT_EVENT_V1 event=%u; pid=%ld; tid=%ld; "
        "origin=%s; host_elapsed_ms=%" PRIu64 "; fatal=UNCHANGED\n",
        event, (long)getpid(), (long)syscall(SYS_gettid), origin, clock_ms()));
    // Persist the immediate caller before best-effort unwinding.
    // ELF return offsets are NOT a precise fault PC or a complete PE/FEX stack.
    frame(event, 0, caller, "CALLER_RETURN");
    emit(text, snprintf(text, sizeof(text), "NATIVE_ABORT_REASON_V1 event=%u; source=%s; category=%s; text=NOT_EXPORTED\n",
        event, message_category ? "ANDROID_MESSAGE" : "UNOBSERVED", message_category ? message_category : "UNOBSERVED"));
    struct frames f = {.until = clock_ms() + 5};
    (void)_Unwind_Backtrace(unwind, &f);
    for (unsigned i = 0; i < f.count && active(); i++) frame(event, i + 1, f.pcs[i], "UNWIND_RETURN");
    emit(text, snprintf(text, sizeof(text), "NATIVE_ABORT_END_V1 event=%u; frames=%u; unwind=BEST_EFFORT\n", event, f.count + 1));
    observing = 0;
}
// The existing narrow ARM64 assertion observer calls this after saving the
// original entry registers. It remains first in the preload order.
void aura_startup_observe_assert(uintptr_t caller) {
    int saved = errno;
    observe("ASSERT2", caller);
    errno = saved;
}
void android_set_abort_message(const char *message) {
    int saved = errno;
    if (active()) message_category = classify_message(message);
    errno = saved;
    if (next_abort_message) next_abort_message(message);
}
__attribute__((noreturn)) static void fatal_delegate(void) {
    if (!next_abort) { int saved = errno; next_abort = dlsym(RTLD_NEXT, "abort"); errno = saved; }
    if (next_abort) next_abort();
    // Validated Android libc always provides abort. An unavailable delegate must
    // still never return to a failed assertion; no recovery/success is invented.
    _exit(134);
}
__attribute__((noreturn)) void abort(void) {
    int saved = errno;
    observe("ABORT", (uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0)));
    errno = saved;
    fatal_delegate();
}
__attribute__((noreturn)) void __assert2(const char *file, int line, const char *function, const char *expression) {
    int saved = errno;
    observe("ASSERT2", (uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0)));
    errno = saved;
    if (!next_assert2) { next_assert2 = dlsym(RTLD_NEXT, "__assert2"); errno = saved; }
    if (next_assert2) next_assert2(file, line, function, expression);
    fatal_delegate();
}
__attribute__((noreturn)) void __assert(const char *file, int line, const char *expression) {
    int saved = errno;
    observe("ASSERT", (uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0)));
    errno = saved;
    if (!next_assert) { next_assert = dlsym(RTLD_NEXT, "__assert"); errno = saved; }
    if (next_assert) next_assert(file, line, expression);
    fatal_delegate();
}

/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Opt-in diagnostics for Wine's Android host, loaded only during saved debug
 * sessions. Observe an owned fd reaching plain close(), then leave the actual
 * fdsan check, ownership and close operation to Bionic. Never suppress a fault.
 * The inspected Android 16 close() also converts EINTR to success (without a
 * second close); preserve that contract. No Wine debugger attachment is used.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <unwind.h>

#ifdef __ANDROID__
#include <android/fdsan.h>
#else
/* Host tests provide a simulated Android ownership table, never a substitute
 * for the API in the shipped Android library. */
extern uint64_t android_fdsan_get_owner_tag(int fd);
extern int android_fdsan_close_with_tag(int fd, uint64_t tag);
#endif

#define EVENT_LIMIT 4
#define FRAME_LIMIT 16
static int enabled;
static _Atomic unsigned event_count;
static _Thread_local int reporting;
static int assert_enabled;
static _Atomic unsigned assert_events;
static void (*platform_assert)(const char *, int, const char *, const char *);
static void emit(const char *message, size_t length);

__attribute__((constructor)) static void configure_trace(void) {
    const char *value = getenv("AURA_NATIVE_FD_TRACE");
    enabled = value && !strcmp(value, "1");
    value = getenv("AURA_NATIVE_ASSERT_TRACE");
    assert_enabled = value && !strcmp(value, "1");
    // Resolve before a failure. The collector never replaces a failed check
    // with success or returns to the caller of __assert2.
    platform_assert = dlsym(RTLD_NEXT, "__assert2");
    if (assert_enabled) {
        const char *message = platform_assert
            ? "AURA_NATIVE_ASSERT_TRACE_V1 state=READY; target=TURNIP_A2D_DEST_X\n"
            : "AURA_NATIVE_ASSERT_TRACE_V1 state=DELEGATE_MISSING\n";
        emit(message, strlen(message));
    }
}

/* All messages fit one pipe-atomic write. Drop failed writes; do not turn a
 * diagnostic pipe closure into a new SIGPIPE or alter the caller's errno. */
static void emit(const char *message, size_t length) {
    sigset_t block, previous, pending;
    sigemptyset(&block);
    sigaddset(&block, SIGPIPE);
    if (sigprocmask(SIG_BLOCK, &block, &previous)) return;
    int had_pending = sigpending(&pending) == 0 && sigismember(&pending, SIGPIPE);
    ssize_t result = write(STDERR_FILENO, message, length);
    if (result < 0 && errno == EPIPE && !had_pending) {
        const struct timespec zero = {0, 0};
        (void)sigtimedwait(&block, NULL, &zero);
    }
    (void)sigprocmask(SIG_SETMASK, &previous, NULL);
}

static void print_frame(const char *prefix, unsigned event, unsigned index, uintptr_t pc, const char *kind) {
    Dl_info info = {0};
    char module[81] = "UNKNOWN";
    uintptr_t offset = 0;
    if (dladdr((void *)pc, &info) && info.dli_fname && info.dli_fbase) {
        const char *name = strrchr(info.dli_fname, '/');
        name = name ? name + 1 : info.dli_fname;
        size_t i;
        for (i = 0; name[i] && i < sizeof(module) - 1; ++i) {
            unsigned char c = (unsigned char)name[i];
            module[i] = ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                         (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') ? (char)c : '_';
        }
        module[i] = 0;
        offset = pc - (uintptr_t)info.dli_fbase;
    }
    char message[320];
    int length = snprintf(message, sizeof(message),
        "%s event=%u; pid=%ld; kind=%s; index=%u; pc=0x%" PRIxPTR
        "; module=%s; offset=0x%" PRIxPTR "\n",
        prefix, event, (long)getpid(), kind, index, pc, module, offset);
    if (length > 0 && (size_t)length < sizeof(message)) emit(message, (size_t)length);
}

struct trace_frames { uintptr_t pc[FRAME_LIMIT]; unsigned count; };
static _Unwind_Reason_Code collect_frame(struct _Unwind_Context *context, void *opaque) {
    struct trace_frames *frames = opaque;
    uintptr_t pc = _Unwind_GetIP(context);
    if (!pc || frames->count == FRAME_LIMIT) return _URC_END_OF_STACK;
    frames->pc[frames->count++] = pc;
    return _URC_NO_REASON;
}

static void report_owned_close(int fd, uint64_t tag, uintptr_t caller) {
    unsigned event = atomic_load(&event_count);
    do {
        if (event >= EVENT_LIMIT) return;
    } while (!atomic_compare_exchange_weak(&event_count, &event, event + 1));
    ++event;

    char message[256];
    int length = snprintf(message, sizeof(message),
        "AURA_NATIVE_FD_V1 event=%u; pid=%ld; tid=%ld; fd=%d; owner_type=%u; expected=UNOWNED\n",
        event, (long)getpid(), (long)syscall(SYS_gettid), fd, (unsigned)(tag >> 56));
    if (length > 0 && (size_t)length < sizeof(message)) emit(message, (size_t)length);
    /* Save the immediate caller before attempting a best-effort native unwind.
     * FEX JIT / PE frames may not have ELF unwind information. */
    print_frame("AURA_NATIVE_FD_FRAME_V1", event, 0, caller, "CALLER");
    struct trace_frames frames = {0};
    (void)_Unwind_Backtrace(collect_frame, &frames);
    for (unsigned i = 0; i < frames.count; ++i)
        print_frame("AURA_NATIVE_FD_FRAME_V1", event, i, frames.pc[i], "UNWIND");
}

int close(int fd) {
    int saved_errno = errno;
    if (enabled && !reporting && fd >= 0) {
        reporting = 1;
        uint64_t tag = android_fdsan_get_owner_tag(fd);
        if (tag) report_owned_close(fd, tag,
            (uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0)));
        reporting = 0;
    }
    errno = saved_errno;
    int result = android_fdsan_close_with_tag(fd, 0);
    return result == -1 && errno == EINTR ? 0 : result;
}

static int target_assert(const char *function, const char *expression) {
    return function && expression &&
        strstr(function, "pack_A6XX_GRAS_A2D_DEST_BR(") &&
        !strcmp(expression, "(fields.x & 0xffffc000) == 0");
}

__attribute__((noreturn)) static void trace_assert(const char *file, int line,
        const char *function, const char *expression, uintptr_t caller, const uint64_t *regs) {
    int saved_errno = errno;
    if (assert_enabled && !reporting && target_assert(function, expression) &&
        atomic_fetch_add(&assert_events, 1) == 0) {
        reporting = 1;
        char message[320];
        int length = snprintf(message, sizeof(message),
            "AURA_NATIVE_ASSERT_V1 event=1; pid=%ld; tid=%ld; target=TURNIP_A2D_DEST_X; "
            "registers=%s; coordinate=UNDECODED; fatal=UNCHANGED\n",
            (long)getpid(), (long)syscall(SYS_gettid), regs ? "ARM64_ENTRY" : "UNAVAILABLE");
        if (length > 0 && (size_t)length < sizeof(message)) emit(message, (size_t)length);
        print_frame("AURA_NATIVE_ASSERT_FRAME_V1", 1, 0, caller, "CALLER_RETURN");
        if (regs) {
            for (unsigned i = 0; i < 32; i += 4) {
                length = snprintf(message, sizeof(message),
                    "AURA_NATIVE_ASSERT_REGS_V1 event=1; pid=%ld; first=%u; "
                    "values=0x%" PRIx64 ",0x%" PRIx64 ",0x%" PRIx64 ",0x%" PRIx64 "\n",
                    (long)getpid(), i, regs[i], regs[i + 1], regs[i + 2], regs[i + 3]);
                if (length > 0 && (size_t)length < sizeof(message)) emit(message, (size_t)length);
            }
        }
        // Exact caller and entry registers are already written if ELF unwind
        // metadata is unavailable across a Wine/FEX boundary.
        struct trace_frames frames = {0};
        (void)_Unwind_Backtrace(collect_frame, &frames);
        for (unsigned i = 0; i < frames.count; ++i)
            print_frame("AURA_NATIVE_ASSERT_FRAME_V1", 1, i, frames.pc[i], "UNWIND");
        reporting = 0;
    }
    errno = saved_errno;
    if (platform_assert) platform_assert(file, line, function, expression);
    // Missing delegate is itself a fatal fallback, never a bypass of the check.
    abort();
}

#if defined(__aarch64__)
__attribute__((noreturn, visibility("hidden"))) void aura_trace_assert_entry(const uint64_t *regs) {
    trace_assert((const char *)(uintptr_t)regs[0], (int)regs[1],
        (const char *)(uintptr_t)regs[2], (const char *)(uintptr_t)regs[3], (uintptr_t)regs[30], regs);
}
#else
// Host behaviour tests and non-ARM64 builds do not claim ARM64 register capture.
__attribute__((noreturn)) void __assert2(const char *file, int line, const char *function, const char *expression) {
    trace_assert(file, line, function, expression,
        (uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0)), NULL);
}
#endif

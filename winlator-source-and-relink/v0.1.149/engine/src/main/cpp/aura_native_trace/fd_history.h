/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Saved-debug-only, bounded, best-effort ELF fd lifetime evidence. No file names,
 * FILE pointers or contents. Raw syscalls/libc-internal calls are not intercepted.
 * Try-locks drop contended samples instead of blocking a game or signal handler.
 * This observes the original libc operations; it never repairs ownership. */
#include <fcntl.h>
#include <stdarg.h>
#include <time.h>

#define HISTORY_SLOTS 256
#define HISTORY_DEPTH 8
enum history_op { H_OPEN, H_OPENAT, H_DUP, H_DUP2, H_FOPEN, H_FDOPEN,
                  H_FCLOSE_BEGIN, H_FCLOSE_END, H_CLOSE_BEGIN, H_CLOSE_END };
static const char *const history_names[] = {"OPEN", "OPENAT", "DUP", "DUP2", "FOPEN", "FDOPEN",
    "FCLOSE_BEGIN", "FCLOSE_END", "CLOSE_BEGIN", "CLOSE_END"};
struct fd_event { uint64_t ns; uintptr_t caller; long tid; int op, result, other; unsigned owner; };
struct fd_history { int fd; unsigned count; struct fd_event events[HISTORY_DEPTH]; };
static struct fd_history history[HISTORY_SLOTS];
static atomic_flag history_lock = ATOMIC_FLAG_INIT;
static _Atomic unsigned history_dropped, history_evicted;
static int history_enabled;

static void history_record(int fd, enum history_op op, int result, int other, uintptr_t caller, unsigned owner) {
    if (!history_enabled || reporting || fd < 0) return;
    int saved_errno = errno;
    struct timespec time = {0};
    (void)clock_gettime(CLOCK_MONOTONIC, &time);
    struct fd_event event = { (uint64_t)time.tv_sec * 1000000000 + time.tv_nsec,
        caller, (long)syscall(SYS_gettid), op, result, other, owner };
    if (atomic_flag_test_and_set_explicit(&history_lock, memory_order_acquire)) {
        atomic_fetch_add(&history_dropped, 1);
    } else {
        struct fd_history *slot = &history[(unsigned)fd % HISTORY_SLOTS];
        if (slot->count && slot->fd != fd) { atomic_fetch_add(&history_evicted, 1); slot->count = 0; }
        slot->fd = fd;
        if (slot->count == HISTORY_DEPTH) {
            memmove(slot->events, slot->events + 1, sizeof(slot->events[0]) * (HISTORY_DEPTH - 1));
            --slot->count;
            atomic_fetch_add(&history_evicted, 1);
        }
        slot->events[slot->count++] = event;
        atomic_flag_clear_explicit(&history_lock, memory_order_release);
    }
    errno = saved_errno;
}

static void history_report(unsigned event, int fd) {
    if (!history_enabled) return;
    struct fd_history copy = {0};
    if (atomic_flag_test_and_set_explicit(&history_lock, memory_order_acquire)) atomic_fetch_add(&history_dropped, 1);
    else {
        struct fd_history *slot = &history[(unsigned)fd % HISTORY_SLOTS];
        if (slot->fd == fd) copy = *slot;
        atomic_flag_clear_explicit(&history_lock, memory_order_release);
    }
    char message[384];
    int length = snprintf(message, sizeof(message),
        "AURA_NATIVE_FD_HISTORY_V1 event=%u; pid=%ld; fd=%d; samples=%u; dropped=%u; evicted=%u; coverage=PARTIAL\n",
        event, (long)getpid(), fd, copy.count, atomic_load(&history_dropped), atomic_load(&history_evicted));
    if (length > 0 && (size_t)length < sizeof(message)) emit(message, (size_t)length);
    for (unsigned i = 0; i < copy.count; ++i) {
        const struct fd_event *sample = &copy.events[i];
        char owner[16] = "?";
        if (sample->owner < 256) (void)snprintf(owner, sizeof(owner), "%u", sample->owner);
        length = snprintf(message, sizeof(message),
            "AURA_NATIVE_FD_LIFETIME_V1 event=%u; pid=%ld; index=%u; ns=%" PRIu64
            "; tid=%ld; op=%s; result=%d; other_fd=%d; owner_type=%s\n",
            event, (long)getpid(), i, sample->ns, sample->tid, history_names[sample->op],
            sample->result, sample->other, owner);
        if (length > 0 && (size_t)length < sizeof(message)) emit(message, (size_t)length);
        print_frame("AURA_NATIVE_FD_HISTORY_FRAME_V1", event, i, sample->caller, "CALLER");
    }
}

#define HISTORY_CALLER ((uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0)))
/* Resolve to the real libc, including when another preload's constructor runs
 * before ours. Atomics avoid races during the first calls on different threads. */
#define HISTORY_DELEGATE(name, type) \
    static _Atomic(type) cached; \
    type real = atomic_load(&cached); \
    if (!real) { real = (type)dlsym(RTLD_NEXT, #name); atomic_store(&cached, real); }

static unsigned history_owner(int fd) {
    if (!history_enabled || reporting || fd < 0) return 0;
    int saved_errno = errno;
    unsigned owner = android_fdsan_get_owner_tag(fd) >> 56;
    errno = saved_errno;
    return owner;
}

typedef int (*open_fn)(const char *, int, ...);
typedef int (*openat_fn)(int, const char *, int, ...);
typedef int (*dup_fn)(int);
typedef int (*dup2_fn)(int, int);
typedef FILE *(*fopen_fn)(const char *, const char *);
typedef FILE *(*fdopen_fn)(int, const char *);
typedef int (*fclose_fn)(FILE *);

static int mode_required(int flags) {
    return (flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE;
}
int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (mode_required(flags)) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    int saved_errno = errno;
    HISTORY_DELEGATE(open, open_fn)
    if (!real) { errno = ENOSYS; return -1; }
    errno = saved_errno;
    int result = real(path, flags, mode);
    history_record(result, H_OPEN, result, -1, HISTORY_CALLER, history_owner(result));
    return result;
}
int openat(int directory, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (mode_required(flags)) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    int saved_errno = errno;
    HISTORY_DELEGATE(openat, openat_fn)
    if (!real) { errno = ENOSYS; return -1; }
    errno = saved_errno;
    int result = real(directory, path, flags, mode);
    history_record(result, H_OPENAT, result, directory, HISTORY_CALLER, history_owner(result));
    return result;
}
int dup(int fd) {
    int saved_errno = errno;
    HISTORY_DELEGATE(dup, dup_fn)
    if (!real) { errno = ENOSYS; return -1; }
    errno = saved_errno;
    int result = real(fd);
    history_record(result, H_DUP, result, fd, HISTORY_CALLER, history_owner(result));
    return result;
}
int dup2(int fd, int target) {
    int saved_errno = errno;
    HISTORY_DELEGATE(dup2, dup2_fn)
    if (!real) { errno = ENOSYS; return -1; }
    errno = saved_errno;
    int result = real(fd, target);
    history_record(target, H_DUP2, result, fd, HISTORY_CALLER, history_owner(target));
    return result;
}
FILE *fopen(const char *path, const char *mode) {
    int saved_errno = errno;
    HISTORY_DELEGATE(fopen, fopen_fn)
    if (!real) { errno = ENOSYS; return NULL; }
    errno = saved_errno;
    FILE *result = real(path, mode);
    if (result && history_enabled && !reporting) {
        saved_errno = errno;
        int fd = fileno(result);
        history_record(fd, H_FOPEN, fd, -1, HISTORY_CALLER, history_owner(fd));
        errno = saved_errno;
    }
    return result;
}
FILE *fdopen(int fd, const char *mode) {
    int saved_errno = errno;
    HISTORY_DELEGATE(fdopen, fdopen_fn)
    if (!real) { errno = ENOSYS; return NULL; }
    errno = saved_errno;
    FILE *result = real(fd, mode);
    history_record(fd, H_FDOPEN, result ? fd : -1, -1, HISTORY_CALLER, history_owner(fd));
    return result;
}
int fclose(FILE *file) {
    int saved_errno = errno;
    HISTORY_DELEGATE(fclose, fclose_fn)
    if (!real) { errno = ENOSYS; return EOF; }
    // Never inspect the FILE after libc releases it.
    int fd = history_enabled && !reporting ? fileno(file) : -1;
    history_record(fd, H_FCLOSE_BEGIN, 0, -1, HISTORY_CALLER, history_owner(fd));
    errno = saved_errno;
    int result = real(file);
    history_record(fd, H_FCLOSE_END, result, -1, HISTORY_CALLER, 256);
    return result;
}

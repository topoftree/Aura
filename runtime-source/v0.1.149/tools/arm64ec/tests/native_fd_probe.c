/* Host-only Bionic ownership model. Real Android fdsan is never replaced in the APK. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

static int target = -1;
static const char *mode;
static int history_owned;
static _Atomic unsigned queries, closes, bad_expected;

uint64_t android_fdsan_get_owner_tag(int fd) {
    atomic_fetch_add(&queries, 1);
    errno = E2BIG; /* The observer must not leak its errno into close(). */
    if (fd == target && (history_owned || !strcmp(mode, "owned") || !strcmp(mode, "limit") ||
        !strcmp(mode, "fatal") || !strcmp(mode, "closed-stderr") || !strcmp(mode, "broken-pipe")))
        return (UINT64_C(2) << 56) | UINT64_C(0x123456);
    return 0;
}

int android_fdsan_close_with_tag(int fd, uint64_t tag) {
    atomic_fetch_add(&closes, 1);
    if (tag) atomic_fetch_add(&bad_expected, 1);
    if (fd == target && (!strcmp(mode, "fatal") || (history_owned && !strcmp(mode, "history-fatal")))) { raise(SIGABRT); _exit(91); }
    if (fd == target && (history_owned || !strcmp(mode, "owned") || !strcmp(mode, "limit") ||
        !strcmp(mode, "closed-stderr") || !strcmp(mode, "broken-pipe"))) {
        errno = EBADF;
        return -1;
    }
    int result = (int)syscall(SYS_close, fd);
    if (fd == target && !strcmp(mode, "eintr")) { errno = EINTR; return -1; }
    return result;
}

static void *stress(void *unused) {
    (void)unused;
    for (int i = 0; i < 1000; ++i) {
        int fd = open("/dev/null", O_RDONLY);
        if (fd < 0 || close(fd)) return (void *)1;
    }
    return NULL;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    mode = argv[1];
    struct rlimit no_core = {0, 0};
    setrlimit(RLIMIT_CORE, &no_core);
    if (!strcmp(mode, "threads")) {
        pthread_t threads[8];
        for (unsigned i = 0; i < 8; ++i) if (pthread_create(&threads[i], NULL, stress, NULL)) return 3;
        for (unsigned i = 0; i < 8; ++i) {
            void *result;
            if (pthread_join(threads[i], &result) || result) return 4;
        }
        printf("calls=%u queries=%u bad_expected=%u\n", closes, queries, bad_expected);
        return 0;
    }
    target = !strcmp(mode, "invalid") ? -1 : open("/dev/null", O_RDONLY);
    if (target < 0 && strcmp(mode, "invalid")) return 5;
    if (!strncmp(mode, "history-", 8)) {
        if (close(target)) return 7;
        if (!strcmp(mode, "history-depth")) {
            for (int i = 0; i < 20; ++i) {
                int fd = openat(AT_FDCWD, "/dev/null", O_RDONLY);
                if (fd < 0 || close(fd)) return 8;
            }
        }
        FILE *stream;
        if (!strcmp(mode, "history-dup")) {
            int fd = openat(AT_FDCWD, "/dev/null", O_RDONLY);
            int other = dup(fd);
            if (fd < 0 || other < 0 || dup2(other, fd) < 0 || close(other)) return 9;
            stream = fdopen(fd, "r");
        } else {
            stream = fopen("/dev/null", "r");
        }
        if (!stream) return 10;
        target = fileno(stream);
        history_owned = 1;
        errno = ENOTTY;
        int result = close(target), error = errno;
        history_owned = 0;
        if (fclose(stream)) return 11;
        // Reuse once more to expose FCLOSE records without dereferencing a freed FILE.
        stream = fopen("/dev/null", "r");
        if (!stream || fileno(stream) != target) return 12;
        history_owned = 1;
        (void)close(target);
        history_owned = 0;
        if (fclose(stream)) return 13;
        printf("result=%d errno=%d calls=%u queries=%u bad_expected=%u\n",
            result, error, closes, queries, bad_expected);
        return 0;
    }
    if (!strcmp(mode, "closed-stderr")) syscall(SYS_close, STDERR_FILENO);
    if (!strcmp(mode, "broken-pipe")) {
        int pipe_fds[2];
        if (pipe(pipe_fds) || dup2(pipe_fds[1], STDERR_FILENO) < 0) return 6;
        syscall(SYS_close, pipe_fds[0]);
        syscall(SYS_close, pipe_fds[1]);
        signal(SIGPIPE, SIG_DFL);
    }
    int result = 0, error = 0, repeats = !strcmp(mode, "limit") ? 12 : 1;
    for (int i = 0; i < repeats; ++i) {
        errno = ENOTTY;
        result = close(target);
        error = errno;
    }
    printf("result=%d errno=%d calls=%u queries=%u bad_expected=%u\n",
        result, error, closes, queries, bad_expected);
    return 0;
}

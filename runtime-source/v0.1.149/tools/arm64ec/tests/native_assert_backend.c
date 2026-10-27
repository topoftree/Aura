/* Test-only fatal delegate, never included in the Android library. */
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

void android_set_abort_message(const char *message) {
    dprintf(STDOUT_FILENO, "MESSAGE_DELEGATE errno=%d message=%s\n", errno, message);
    errno = EBUSY;
}

__attribute__((noreturn)) void __assert2(const char *file, int line,
        const char *function, const char *expression) {
    int saved = errno;
    dprintf(STDOUT_FILENO, "DELEGATE errno=%d line=%d file=%s function=%s expression=%s\n",
        saved, line, file, function, expression);
    raise(SIGABRT);
    _exit(91);
}

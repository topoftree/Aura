#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

extern void __assert2(const char *, int, const char *, const char *);
extern void android_set_abort_message(const char *);
uint64_t android_fdsan_get_owner_tag(int fd) { (void)fd; return 0; }
int android_fdsan_close_with_tag(int fd, uint64_t tag) {
    (void)tag;
    return syscall(SYS_close, fd);
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    struct rlimit limit = {0, 0};
    setrlimit(RLIMIT_CORE, &limit);
    const char *function = "struct tu_reg_value pack_A6XX_GRAS_A2D_DEST_BR(struct A6XX_GRAS_A2D_DEST_BR)";
    const char *expression = "(fields.x & 0xffffc000) == 0";
    if (!strcmp(argv[1], "other-function")) function = "pack_UNRELATED_REG(struct REG)";
    if (!strcmp(argv[1], "other-expression")) expression = "(fields.y & 0xffffc000) == 0";
    if (!strcmp(argv[1], "closed-stderr")) syscall(SYS_close, STDERR_FILENO);
    if (!strcmp(argv[1], "broken-pipe")) {
        int descriptors[2];
        if (pipe(descriptors) || dup2(descriptors[1], STDERR_FILENO) < 0) return 3;
        syscall(SYS_close, descriptors[0]);
        syscall(SYS_close, descriptors[1]);
        signal(SIGPIPE, SIG_DFL);
    }
    errno = ENOTTY;
    if (!strcmp(argv[1], "compiler-message")) {
        android_set_abort_message("LLVM shader compiler failure /private/secret abc123");
        if (errno != EBUSY) return 7;
        errno = ENOTTY;
    }
    __assert2("/private/build/a6xx-pack.xml.h", 11616, function, expression);
    return 4;
}

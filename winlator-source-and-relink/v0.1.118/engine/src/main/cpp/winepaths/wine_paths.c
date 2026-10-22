/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Wine derives its loader/DLL directories from realpath(ntdll.so); wineserver
 * finds share/wine/nls relative to realpath(/proc/self/exe). Android installs
 * these ELF files under nativeLibraryDir, outside the writable runtime tree.
 * Preserve logical paths ONLY for the three pinned Wine entry files; all other
 * realpath calls retain libc semantics. Code stays in the APK installation.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *(*libc_realpath)(const char *, char *);
static pthread_once_t resolve_once = PTHREAD_ONCE_INIT;
static void resolve_realpath(void) {
    *(void **)(&libc_realpath) = dlsym(RTLD_NEXT, "realpath");
}

char *realpath(const char *path, char *resolved) {
    pthread_once(&resolve_once, resolve_realpath);
    if (!libc_realpath) { errno = ENOSYS; return NULL; }
    char actual[PATH_MAX];
    if (!libc_realpath(path, actual)) return NULL;
    const char *logical = NULL;
    const char *root = getenv("AURA_WINE_UNIX_DIR");
    const char *loader = getenv("AURA_WINE_NATIVE_LOADER");
    const char *ntdll = getenv("AURA_WINE_NATIVE_NTDLL");
    const char *bin = getenv("AURA_WINE_BIN_DIR");
    const char *server = getenv("AURA_WINE_NATIVE_SERVER");
    if (root && root[0] == '/' && loader && !strcmp(actual, loader)) logical = "wine";
    if (root && root[0] == '/' && ntdll && !strcmp(actual, ntdll)) logical = "ntdll.so";
    if (bin && bin[0] == '/' && server && !strcmp(actual, server)) {
        root = bin;
        logical = "wineserver";
    }
    char remapped[PATH_MAX];
    if (logical) {
        int length = snprintf(remapped, sizeof(remapped), "%s/%s", root, logical);
        if (length < 0 || (size_t)length >= sizeof(remapped)) { errno = ENAMETOOLONG; return NULL; }
    }
    const char *result = logical ? remapped : actual;
    if (resolved) { strcpy(resolved, result); return resolved; }
    return strdup(result);
}

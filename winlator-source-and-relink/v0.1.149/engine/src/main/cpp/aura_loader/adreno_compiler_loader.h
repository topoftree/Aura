/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Android selects a dlopen namespace from the original caller's address.
 * Only Adreno's deferred compiler loads bypass our observation chain: none of
 * these libraries is an ntdll/GL/Mesa observation target. All other loads keep
 * the existing chain, including third-party interposers.
 */
#pragma once
#include <dlfcn.h>
#include <errno.h>
#include <string.h>

typedef void *(*aura_system_dlopen_fn)(const char *, int, const void *);
typedef void *(*aura_next_dlopen_fn)(const char *, int);

static inline int aura_system_linker_path(const char *path) {
    if (!path) return 0;
    if (!strcmp(path, "/system/bin/linker64")) return 1;
    const char prefix[] = "/apex/com.android.runtime";
    if (strncmp(path, prefix, sizeof(prefix) - 1)) return 0;
    const char *rest = path + sizeof(prefix) - 1;
    if (*rest == '@') {
        unsigned digits = 0; ++rest;
        while (*rest >= '0' && *rest <= '9' && digits < 20) { ++rest; ++digits; }
        if (!digits) return 0;
    }
    return !strcmp(rest, "/bin/linker64");
}

/* Resolve once in the DSO constructor, never between a caller's failed load
 * and dlerror. Query the unique loader API, then verify its exact linker owner;
 * a public dlopen wrapper must never become this three-argument function.
 */
static inline aura_system_dlopen_fn aura_adreno_loader_resolve(void) {
    int saved = errno;
    void *symbol = dlsym(RTLD_NEXT, "__loader_dlopen");
    Dl_info info = {0};
    aura_system_dlopen_fn result = NULL;
    if (symbol && dladdr(symbol, &info) && info.dli_fbase &&
        info.dli_saddr == symbol && info.dli_sname && !strcmp(info.dli_sname, "__loader_dlopen") &&
        aura_system_linker_path(info.dli_fname)) result = (aura_system_dlopen_fn)symbol;
    errno = saved;
    return result;
}

static inline const char *aura_library_basename(const char *path) {
    if (!path) return NULL;
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static inline int aura_adreno_compiler_name(const char *name) {
    return name && (!strcmp(name, "notllvm-glnext.so") || !strcmp(name, "notllvm-qgl.so") ||
        !strcmp(name, "notllvm-qcom.so") || !strcmp(name, "libllvm-glnext.so") ||
        !strcmp(name, "libllvm-qgl.so") || !strcmp(name, "libllvm-qcom.so"));
}

static inline void *aura_adreno_dlopen(aura_system_dlopen_fn loader, aura_next_dlopen_fn next,
                                     const char *name, int flags, const void *caller) {
    int saved = errno;
    if (loader && caller && aura_adreno_compiler_name(aura_library_basename(name))) {
        Dl_info origin = {0};
        if (dladdr(caller, &origin) && origin.dli_fbase) {
            const char *base = aura_library_basename(origin.dli_fname);
            if (aura_adreno_compiler_name(base) || (base &&
                (!strcmp(base, "vulkan.adreno.so") || !strcmp(base, "vulkan.ad0863.so")))) {
                errno = saved;
                // Original name (including any directory), flags, caller and result.
                // No retries, fallback after a failed load, or consuming dlerror.
                return loader(name, flags, caller);
            }
        }
    }
    errno = saved;
    if (next) return next(name, flags);
    errno = ENOSYS;
    return NULL;
}

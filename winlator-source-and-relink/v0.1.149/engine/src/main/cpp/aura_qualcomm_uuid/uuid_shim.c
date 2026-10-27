/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
// SPDX-License-Identifier: BSD-2-Clause
// Copyright (c) 2026 Aura contributors
// Replacement for the v863.1 bundle's two UUID forwarding functions.
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

typedef int (*uuid_fn)(uint8_t *, uint32_t, uint32_t, uint32_t, uint32_t);
typedef void *(*loader_fn)(const char *, int, const void *);
static pthread_once_t once = PTHREAD_ONCE_INIT;
static _Thread_local int resolving;
static uuid_fn device_uuid;
static uuid_fn driver_uuid;
// Successful handles live with this process. Never close a provider while its
// cached function may be used by another thread.
static void *device_handle;
static void *driver_handle;

static int runtime_path(const char *path, const char *suffix) {
    const char prefix[] = "/apex/com.android.runtime";
    if (!path || strncmp(path, prefix, sizeof(prefix) - 1)) return 0;
    const char *rest = path + sizeof(prefix) - 1;
    if (*rest == '@') {
        ++rest;
        unsigned digits = 0;
        while (*rest >= '0' && *rest <= '9' && digits < 20) { ++rest; ++digits; }
        if (!digits) return 0;
    }
    return !strcmp(rest, suffix);
}

static int system_libdl(const char *path) {
    return path && (!strcmp(path, "/system/lib64/libdl.so") ||
        !strcmp(path, "/system/lib64/bootstrap/libdl.so") ||
        runtime_path(path, "/lib64/bionic/libdl.so"));
}

static int system_linker(const char *path) {
    return path && (!strcmp(path, "/system/bin/linker64") ||
        runtime_path(path, "/bin/linker64"));
}

static int symbol_info(void *address, const char *name, Dl_info *info) {
    memset(info, 0, sizeof(*info));
    return address && dladdr(address, info) && info->dli_fbase && info->dli_fname &&
        info->dli_saddr == address && info->dli_sname && !strcmp(info->dli_sname, name);
}

static void state(const char *message) {
    __android_log_write(ANDROID_LOG_INFO, "AuraQualcommUuid", message);
}

static uuid_fn provider(void *handle, const char *symbol, const char *basename, const void *self_base) {
    if (!handle) return NULL; // NULL means RTLD_DEFAULT to dlsym, NOT failure.
    void *address = dlsym(handle, symbol);
    Dl_info info = {0};
    if (!address || !dladdr(address, &info) || !info.dli_fbase ||
        info.dli_fbase == self_base || !info.dli_fname) return NULL;
    const char *name = strrchr(info.dli_fname, '/');
    name = name ? name + 1 : info.dli_fname;
    return !strcmp(name, basename) ? (uuid_fn)address : NULL;
}

static void initialize(void) {
    resolving = 1;
    const char *status = "AURA_QUALCOMM_UUID_V1 revision=uuid1; state=SYSTEM_LOADER_UNAVAILABLE";
    // This public library is visible in both app and driver namespaces. An
    // explicit handle avoids finding an LD_PRELOAD wrapper for the next step.
    void *libdl = dlopen("libdl.so", RTLD_NOW | RTLD_LOCAL);
    if (!libdl) goto done;
    Dl_info public_info = {0}, loader_info = {0}, self_info = {0};
    void *public_open = dlsym(libdl, "dlopen");
    void *load = dlsym(libdl, "__loader_dlopen");
    if (!symbol_info(public_open, "dlopen", &public_info) || !system_libdl(public_info.dli_fname) ||
        !symbol_info(load, "__loader_dlopen", &loader_info) || !system_linker(loader_info.dli_fname) ||
        public_info.dli_fbase == loader_info.dli_fbase ||
        !dladdr((void *)&initialize, &self_info) || !self_info.dli_fbase) goto close_libdl;

    loader_fn open_in_namespace = (loader_fn)load;
    // The hidden local function supplies the real driver namespace even when
    // regular dlopen is interposed by production graphics or diagnostic code.
    // Only these two fixed provider loads bypass observers; ntdll/GL/Vulkan
    // initialization continues through the existing observation chain.
    device_handle = open_in_namespace("notadreno_utils.so", RTLD_NOW | RTLD_LOCAL, (void *)&initialize);
    driver_handle = open_in_namespace("/vendor/lib64/libadreno_utils.so", RTLD_NOW | RTLD_LOCAL, (void *)&initialize);
    device_uuid = provider(device_handle, "get_device_uuid", "notadreno_utils.so", self_info.dli_fbase);
    driver_uuid = provider(driver_handle, "get_driver_uuid", "libadreno_utils.so", self_info.dli_fbase);
    if (!device_handle) status = "AURA_QUALCOMM_UUID_V1 revision=uuid1; state=DEVICE_OPEN_FAILED";
    else if (!driver_handle) status = "AURA_QUALCOMM_UUID_V1 revision=uuid1; state=VENDOR_OPEN_FAILED";
    else if (!device_uuid || !driver_uuid) status = "AURA_QUALCOMM_UUID_V1 revision=uuid1; state=SYMBOL_REJECTED";
    else status = "AURA_QUALCOMM_UUID_V1 revision=uuid1; state=PROVIDERS_READY";
    if (device_handle && !device_uuid) { dlclose(device_handle); device_handle = NULL; }
    if (driver_handle && !driver_uuid) { dlclose(driver_handle); driver_handle = NULL; }
close_libdl:
    dlclose(libdl); // Android's libdl/linker remain process-lifetime dependencies.
done:
    state(status); // One fixed classification, without paths, UUIDs or dlerror text.
    resolving = 0;
}

static int prepare(uint8_t *uuid, uint32_t size) {
    // The bundled provider returns -1 for size < 16 and 0 on success. Preserve
    // that failure convention, do not invent a UUID or call a missing symbol.
    if (!uuid || size < 16 || size > INT_MAX || resolving) return 0;
    return pthread_once(&once, initialize) == 0;
}

__attribute__((visibility("default")))
int get_override_device_uuid(uint8_t *uuid, uint32_t size, uint32_t a, uint32_t b, uint32_t c) {
    if (!prepare(uuid, size) || !device_uuid) return -1;
    return device_uuid(uuid, size, a, b, c);
}

__attribute__((visibility("default")))
int get_driver_uuid(uint8_t *uuid, uint32_t size, uint32_t a, uint32_t b, uint32_t c) {
    if (!prepare(uuid, size) || !driver_uuid) return -1;
    return driver_uuid(uuid, size, a, b, c);
}

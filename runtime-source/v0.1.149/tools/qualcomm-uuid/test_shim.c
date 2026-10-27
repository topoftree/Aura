#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int get_driver_uuid(uint8_t *, uint32_t, uint32_t, uint32_t, uint32_t);
int get_override_device_uuid(uint8_t *, uint32_t, uint32_t, uint32_t, uint32_t);
static int mode, libdl_token, device_token, vendor_token, self_token, linker_token;
static unsigned public_opens, provider_opens, closes, logs;
static _Atomic unsigned device_calls, vendor_calls;
static const void *driver_caller;
static char last_status[160];

static int backend_device(uint8_t *p, uint32_t size, uint32_t a, uint32_t b, uint32_t c) {
    assert(p && size == 16 && a == 11 && b == 22 && c == 33);
    atomic_fetch_add(&device_calls, 1); memset(p, 0xD1, 16); errno = ERANGE; return 17;
}
static int backend_driver(uint8_t *p, uint32_t size, uint32_t a, uint32_t b, uint32_t c) {
    assert(p && size == 16 && a == 11 && b == 22 && c == 33);
    atomic_fetch_add(&vendor_calls, 1); memset(p, 0xB2, 16); errno = E2BIG; return 29;
}
static void *public_open_marker(const char *name, int flags) { (void)name; (void)flags; abort(); }
static void *trusted_loader(const char *name, int flags, const void *caller) {
    // A NULL or wrapper address would select the wrong namespace. This must be
    // the hidden helper function whose image was checked using dladdr.
    assert(driver_caller && caller == driver_caller);
    assert(flags == (RTLD_NOW | RTLD_LOCAL));
    ++provider_opens;
    if (mode == 10) {
        uint8_t untouched[16]; memset(untouched, 0xA5, 16);
        assert(get_driver_uuid(untouched, 16, 11, 22, 33) == -1);
        for (unsigned i = 0; i < 16; i++) assert(untouched[i] == 0xA5);
    }
    if (!strcmp(name, "notadreno_utils.so")) return mode == 2 ? NULL : &device_token;
    assert(!strcmp(name, "/vendor/lib64/libadreno_utils.so"));
    return mode == 1 ? NULL : &vendor_token;
}

void *aura_test_dlopen(const char *name, int flags) {
    // Simulate either diagnostic or production dlopen wrappers. The only
    // public load allowed is libdl: private vendor loads must bypass this path.
    assert(!strcmp(name, "libdl.so") && flags == (RTLD_NOW | RTLD_LOCAL));
    ++public_opens;
    return mode == 3 ? NULL : &libdl_token;
}
void *aura_test_dlsym(void *handle, const char *symbol) {
    assert(handle); // The original bug would enter RTLD_DEFAULT here.
    if (handle == &libdl_token) {
        if (!strcmp(symbol, "dlopen")) return (void *)&public_open_marker;
        assert(!strcmp(symbol, "__loader_dlopen"));
        return mode == 4 ? NULL : (void *)&trusted_loader;
    }
    if (handle == &device_token) {
        assert(!strcmp(symbol, "get_device_uuid")); return (void *)&backend_device;
    }
    assert(handle == &vendor_token && !strcmp(symbol, "get_driver_uuid"));
    if (mode == 5) return NULL;
    if (mode == 6) return (void *)&get_driver_uuid;
    return (void *)&backend_driver;
}
int aura_test_dladdr(const void *address, Dl_info *info) {
    memset(info, 0, sizeof(*info)); info->dli_saddr = (void *)address;
    if (address == (void *)&public_open_marker) {
        info->dli_fbase = &libdl_token; info->dli_sname = "dlopen";
        info->dli_fname = mode == 7 ? "/private/libdl.so" : "/apex/com.android.runtime@123/lib64/bionic/libdl.so";
    } else if (address == (void *)&trusted_loader) {
        info->dli_fbase = &linker_token; info->dli_sname = "__loader_dlopen";
        info->dli_fname = mode == 8 ? "/private/linker64" : "/apex/com.android.runtime/bin/linker64";
    } else if (address == (void *)&backend_device) {
        info->dli_fbase = &device_token; info->dli_fname = "/driver/notadreno_utils.so";
    } else if (address == (void *)&backend_driver) {
        info->dli_fbase = &vendor_token;
        info->dli_fname = mode == 9 ? "/wrong/provider.so" : "/vendor/lib64/libadreno_utils.so";
    } else {
        info->dli_fbase = &self_token; info->dli_fname = "/driver/adrenoutils_extra.so";
        if (address != (void *)&get_driver_uuid) driver_caller = address;
    }
    return 1;
}
int aura_test_dlclose(void *handle) { assert(handle); ++closes; return 0; }
int __android_log_write(int priority, const char *tag, const char *message) {
    (void)priority; assert(!strcmp(tag, "AuraQualcommUuid"));
    if (mode == 13) {
        uint8_t untouched[16]; memset(untouched, 0xA5, 16);
        assert(get_driver_uuid(untouched, 16, 11, 22, 33) == -1);
        for (unsigned i = 0; i < 16; ++i) assert(untouched[i] == 0xA5);
    }
    ++logs; assert(strlen(message) < sizeof(last_status)); strcpy(last_status, message); return 0;
}

static void *success(void *unused) {
    (void)unused;
    uint8_t uuid[16];
    assert(get_driver_uuid(uuid, 16, 11, 22, 33) == 29 && errno == E2BIG);
    for (unsigned i = 0; i < 16; i++) assert(uuid[i] == 0xB2);
    assert(get_override_device_uuid(uuid, 16, 11, 22, 33) == 17 && errno == ERANGE);
    for (unsigned i = 0; i < 16; i++) assert(uuid[i] == 0xD1);
    return NULL;
}
int main(int argc, char **argv) {
    assert(argc == 2); mode = atoi(argv[1]);
    uint8_t uuid[16]; memset(uuid, 0xA5, sizeof(uuid));
    if (mode == 11) {
        assert(get_driver_uuid(NULL, 16, 11, 22, 33) == -1);
        assert(get_driver_uuid(uuid, 15, 11, 22, 33) == -1);
        assert(get_override_device_uuid(uuid, UINT32_MAX, 11, 22, 33) == -1);
        assert(public_opens == 0 && provider_opens == 0 && logs == 0);
    } else if (mode == 0 || mode == 10 || mode == 12 || mode == 13) {
        unsigned count = mode == 12 ? 24 : 1;
        pthread_t threads[24];
        for (unsigned i = 0; i < count; ++i) assert(!pthread_create(&threads[i], NULL, success, NULL));
        for (unsigned i = 0; i < count; ++i) assert(!pthread_join(threads[i], NULL));
        assert(public_opens == 1 && provider_opens == 2 && closes == 1 && logs == 1);
        assert(device_calls == count && vendor_calls == count);
        assert(strstr(last_status, "state=PROVIDERS_READY"));
    } else {
        // Device-only failure leaves the independent driver provider usable.
        int device_only = mode == 2;
        for (unsigned i = 0; i < 100; ++i) {
            int result = device_only ? get_override_device_uuid(uuid, 16, 11, 22, 33) :
                                       get_driver_uuid(uuid, 16, 11, 22, 33);
            assert(result == -1);
            for (unsigned k = 0; k < 16; ++k) assert(uuid[k] == 0xA5);
        }
        assert(device_calls == 0 && vendor_calls == 0 && public_opens == 1 && logs == 1);
        assert(!strstr(last_status, "state=PROVIDERS_READY"));
        assert(provider_opens <= 2);
    }
    puts("PASS");
    return 0;
}

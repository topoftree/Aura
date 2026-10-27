/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
// SPDX-License-Identifier: BSD-2-Clause
// Copyright (c) 2026 Aura contributors

#include "system_linker.h"
#include <cstring>
#include <dlfcn.h>

namespace linkernsbypass {
namespace {
// APEX may expose either its stable mount or its numeric version mount. Reject
// app/private libraries that happen to use the same SONAME or symbol names.
bool runtimePath(const char *path, const char *suffix) {
    constexpr char prefix[] = "/apex/com.android.runtime";
    if (!path || std::strncmp(path, prefix, sizeof(prefix) - 1)) return false;
    const char *rest = path + sizeof(prefix) - 1;
    if (*rest == '@') {
        ++rest;
        unsigned digits = 0;
        while (*rest >= '0' && *rest <= '9' && digits < 20) { ++rest; ++digits; }
        if (!digits) return false;
    }
    return !std::strcmp(rest, suffix);
}

bool systemLibdl(const char *path) {
    return path && (!std::strcmp(path, "/system/lib64/libdl.so") ||
                    !std::strcmp(path, "/system/lib64/bootstrap/libdl.so") ||
                    runtimePath(path, "/lib64/bionic/libdl.so"));
}

bool systemLinker(const char *path) {
    return path && (!std::strcmp(path, "/system/bin/linker64") ||
                    runtimePath(path, "/bin/linker64"));
}

bool symbolInfo(void *address, const char *name, Dl_info &info) {
    return address && dladdr(address, &info) && info.dli_fbase && info.dli_fname &&
           info.dli_saddr == address && info.dli_sname && !std::strcmp(info.dli_sname, name);
}

struct Handle {
    void *value;
    ~Handle() { if (value) dlclose(value); }
};
}

ResolveStatus resolveSystemLinker(SystemLinker &out) {
    out = {};
    // A handle lookup starts at libdl and its DT_NEEDED dependency ld-android,
    // not the LD_PRELOAD global group. Calling public dlopen normally is safe;
    // treating its first BL as a three-argument loader function was not.
    Handle libdl{dlopen("libdl.so", RTLD_NOW | RTLD_LOCAL)};
    if (!libdl.value) return ResolveStatus::OpenFailed;
    void *caller = dlsym(libdl.value, "dlopen");
    Dl_info info{};
    if (!symbolInfo(caller, "dlopen", info) || !systemLibdl(info.dli_fname))
        return ResolveStatus::LibdlInvalid;
    const void *libdlBase = info.dli_fbase;

    constexpr const char *names[] = {
        "__loader_android_create_namespace", "__loader_android_get_exported_namespace",
        "__loader_android_link_namespaces", "__loader_android_link_namespaces_all_libs"
    };
    void *symbols[4]{};
    const void *linkerBase = nullptr;
    for (unsigned i = 0; i < 4; ++i) {
        symbols[i] = dlsym(libdl.value, names[i]);
        if (!symbols[i]) return ResolveStatus::SymbolMissing;
        info = {};
        if (!symbolInfo(symbols[i], names[i], info) || !systemLinker(info.dli_fname) ||
            info.dli_fbase == libdlBase || (linkerBase && linkerBase != info.dli_fbase))
            return ResolveStatus::LinkerInvalid;
        linkerBase = info.dli_fbase;
    }
    // libdl is also a DT_NEEDED dependency of this library, and the linker lives
    // for the process lifetime. Closing our temporary handle keeps these valid.
    out = {caller, reinterpret_cast<CreateNamespace>(symbols[0]),
           reinterpret_cast<android_get_exported_namespace_t>(symbols[1]),
           reinterpret_cast<android_link_namespaces_t>(symbols[2]),
           reinterpret_cast<android_link_namespaces_all_libs_t>(symbols[3])};
    return ResolveStatus::Ready;
}

const char *resolveStatusName(ResolveStatus status) {
    switch (status) {
        case ResolveStatus::Ready: return "READY";
        case ResolveStatus::OpenFailed: return "LIBDL_OPEN_FAILED";
        case ResolveStatus::LibdlInvalid: return "LIBDL_IDENTITY_REJECTED";
        case ResolveStatus::SymbolMissing: return "NAMESPACE_SYMBOL_MISSING";
        case ResolveStatus::LinkerInvalid: return "LINKER_IDENTITY_REJECTED";
    }
    return "UNAVAILABLE";
}
}

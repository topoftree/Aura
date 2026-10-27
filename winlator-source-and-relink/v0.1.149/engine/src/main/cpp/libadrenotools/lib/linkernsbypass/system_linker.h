/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
// SPDX-License-Identifier: BSD-2-Clause
// Copyright (c) 2026 Aura contributors
#pragma once

#include "android_linker_ns.h"

namespace linkernsbypass {
using CreateNamespace = android_namespace_t *(*)(const char *, const char *, const char *,
                                                uint64_t, const char *, android_namespace_t *, const void *);
struct SystemLinker {
    const void *libdlCaller{};
    CreateNamespace createNamespace{};
    android_get_exported_namespace_t getExportedNamespace{};
    android_link_namespaces_t linkNamespaces{};
    android_link_namespaces_all_libs_t linkAllNamespaces{};
};

enum class ResolveStatus { Ready, OpenFailed, LibdlInvalid, SymbolMissing, LinkerInvalid };

// The caller gets either a complete validated set or an empty set. No instruction
// scanning, code-page protection changes or global __loader_* lookup is used.
__attribute__((visibility("hidden"))) ResolveStatus resolveSystemLinker(SystemLinker &out);
__attribute__((visibility("hidden"))) const char *resolveStatusName(ResolveStatus status);
}

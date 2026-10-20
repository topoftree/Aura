/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Fixed diagnostic profiles; no user-supplied relay expressions or arbitrary export paths. */
enum class RelayTraceProfile(val extra: String, val folder: String, val marker: String,
                             val functions: List<String>, val caller: String? = null) {
    UBISOFT_WAIT("aura_ubisoft_wait_trace", "ubisoft-wait-trace", "UBISOFT_WAIT_TRACE_V1",
        WaitTraceRegistry.functions),
    MIRRORS_EDGE_FLS("aura_mirrors_edge_fls_trace", "mirrors-edge-fls-trace", "MIRRORS_EDGE_FLS_TRACE_V1",
        listOf("kernel32", "kernelbase").flatMap { module ->
            listOf("FlsAlloc", "FlsGetValue", "FlsSetValue", "FlsFree",
                "TlsAlloc", "TlsGetValue", "TlsSetValue", "TlsFree").map { "$module.$it" }
        }, "MirrorsEdge.exe"),
    // The first phone trace found a function pointer in TLS slot 28, but not its writer under the EXE import filter.
    // Include writers from every module; omit frequent reads and retain the original capture separately.
    MIRRORS_EDGE_TLS_INIT("aura_mirrors_edge_tls_init_trace", "mirrors-edge-tls-init-trace", "MIRRORS_EDGE_TLS_INIT_TRACE_V2",
        listOf("kernel32", "kernelbase").flatMap { module ->
            listOf("FlsAlloc", "FlsSetValue", "FlsFree", "TlsAlloc", "TlsSetValue", "TlsFree")
                .map { "$module.$it" }
        });

    val seconds: Int get() = 45
    val channels: String get() = when (this) {
        MIRRORS_EDGE_FLS -> "-all,+timestamp,+pid,err+all,warn+module,+loaddll,+seh,+relay"
        MIRRORS_EDGE_TLS_INIT -> "-all,+timestamp,+pid,err+all,warn+module,+loaddll,+process,+seh,+relay"
        UBISOFT_WAIT -> UbisoftWaitTrace.CHANNELS
    }

    companion object {
        @JvmStatic fun fromIntent(intent: Intent?): RelayTraceProfile? =
            entries.singleOrNull { intent?.getBooleanExtra(it.extra, false) == true }
    }
}

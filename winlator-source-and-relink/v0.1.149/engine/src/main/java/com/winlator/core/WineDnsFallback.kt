/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import java.io.File

/** Native fallback is loaded only by Wine children, never by the Android app. */
object WineDnsFallback {
    const val LIBRARY = "libaura_wine_dns.so"
    const val VARIABLE = "AURA_WINE_DNS_FALLBACK"

    fun configure(context: Context, env: EnvVars) {
        val state = configure(env, File(context.applicationInfo.nativeLibraryDir, LIBRARY))
        RuntimeLaunchDiagnostics.detail(context,
            "WINE_DNS_FALLBACK_V1 state=$state; source=ANDROID_RESOLVER; original_success=PRESERVED; tls=UNCHANGED")
    }

    fun configure(env: EnvVars, library: File): String {
        val retained = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != LIBRARY }
        val enabled = env.get(VARIABLE) != "0" && library.isFile
        val preload = (retained + if (enabled) listOf(library.path) else emptyList()).joinToString(" ")
        if (preload.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", preload)
        return when { env.get(VARIABLE) == "0" -> "DISABLED"; enabled -> "READY"; else -> "UNAVAILABLE" }
    }
}

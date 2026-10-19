/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.File

/** Native ownership faults need ELF caller addresses; Wine's PE debugger cannot provide them. */
object NativeFdTrace {
    const val VARIABLE = "AURA_NATIVE_FD_TRACE"
    const val LIBRARY = "libaura_native_trace.so"

    fun configure(context: Context, env: EnvVars) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val requested = RuntimeDiagnostics.sessionEnabled(context) && preferences.getBoolean("enable_wine_debug", false) &&
            preferences.getBoolean("save_logs_to_file", false) && env.get(VARIABLE) != "0"
        // Do not enable this from an environment variable without an explicit saved-debug session.
        env.put(VARIABLE, "0")
        if (!requested) {
            val preload = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
                .filter { it.isNotBlank() && File(it).name != LIBRARY }.joinToString(" ")
            if (preload.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", preload)
            return
        }
        val library = File(context.applicationInfo.nativeLibraryDir, LIBRARY)
        if (!library.isFile) {
            RuntimeLaunchDiagnostics.detail(context, "NATIVE_FD_TRACE_V1 state=LIBRARY_MISSING")
            return
        }
        val preload = env.get("LD_PRELOAD").orEmpty()
        env.put("LD_PRELOAD", if (preload.isBlank()) library.path else "$preload ${library.path}")
        env.put(VARIABLE, "1")
        RuntimeLaunchDiagnostics.detail(context,
            "NATIVE_FD_TRACE_V1 state=CONFIGURED; max_events=4; max_frames=16; fdsan=UNCHANGED")
    }
}

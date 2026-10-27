/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.File

/** Native faults need ELF callers and entry registers that Wine's PE debugger may not retain. */
object NativeFdTrace {
    const val VARIABLE = "AURA_NATIVE_FD_TRACE"
    const val ASSERT_VARIABLE = "AURA_NATIVE_ASSERT_TRACE"
    const val HISTORY_VARIABLE = "AURA_NATIVE_FD_HISTORY"
    const val LIBRARY = "libaura_native_trace.so"

    fun configure(context: Context, env: EnvVars) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val boundedMirrorTrace = RelayTraceProfile.fromIntent((context as? android.app.Activity)?.intent) ==
            RelayTraceProfile.MIRRORS_EDGE_TLS_INIT
        val savedDebug = preferences.getBoolean("enable_wine_debug", false) && preferences.getBoolean("save_logs_to_file", false)
        val requested = RuntimeDiagnostics.sessionEnabled(context) && (savedDebug || boundedMirrorTrace) && env.get(VARIABLE) != "0"
        val assertRequested = requested && env.get(ASSERT_VARIABLE) != "0"
        val historyRequested = requested && savedDebug && env.get(HISTORY_VARIABLE) != "0"
        // Do not enable this from an environment variable without an explicit saved-debug session.
        env.put(VARIABLE, "0")
        env.put(ASSERT_VARIABLE, "0")
        env.put(HISTORY_VARIABLE, "0")
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
        if (historyRequested) {
            env.put(HISTORY_VARIABLE, "1")
            RuntimeLaunchDiagnostics.detail(context,
                "NATIVE_FD_HISTORY_V1 state=CONFIGURED; slots=256; depth=8; coverage=PARTIAL; fdsan=UNCHANGED")
        }
        if (assertRequested) {
            env.put(ASSERT_VARIABLE, "1")
            RuntimeLaunchDiagnostics.detail(context,
                "NATIVE_ASSERT_TRACE_V1 state=CONFIGURED; target=TURNIP_A2D_DEST_X; max_events=1; max_frames=16; fatal=UNCHANGED")
        }
        RuntimeLaunchDiagnostics.detail(context,
            "NATIVE_FD_TRACE_V1 state=CONFIGURED; max_events=4; max_frames=16; fdsan=UNCHANGED")
    }
}

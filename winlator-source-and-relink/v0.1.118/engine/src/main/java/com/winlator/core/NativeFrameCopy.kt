/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import java.io.File

/** Automatic, bounded Mesa copy helpers; independent of CPU profiles and Debug. */
object NativeFrameCopy {
    const val VARIABLE = "AURA_MESA_FRAME_COPY"

    fun enabled(env: EnvVars) = env.get(VARIABLE) == "1"

    fun configure(context: Context, env: EnvVars) {
        val optedOut = env.get(VARIABLE) == "0"
        val library = File(context.applicationInfo.nativeLibraryDir, NativeGraphicsTrace.LIBRARY)
        val available = !optedOut && library.isFile
        env.put(VARIABLE, if (available) "1" else "0")
        setLibrary(env, library.takeIf { available })
        RuntimeLaunchDiagnostics.detail(context,
            "MESA_FRAME_COPY_V1 state=${if (optedOut) "DISABLED" else if (available) "CONFIGURED" else "LIBRARY_MISSING"}; " +
                "scope=VERIFIED_MESA_RECTANGLES; min_bytes=8388608; max_workers=2; " +
                "completion=SYNCHRONOUS; debug_required=NO; activation=NATIVE_COUNTERS; fps_gain=UNCONFIRMED")
    }

    internal fun setLibrary(env: EnvVars, library: File?) {
        val entries = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != NativeGraphicsTrace.LIBRARY }
        val preload = (entries + listOfNotNull(library?.path)).joinToString(" ")
        if (preload.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", preload)
    }
}

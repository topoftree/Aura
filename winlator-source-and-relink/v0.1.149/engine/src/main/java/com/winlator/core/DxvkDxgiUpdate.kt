/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import java.io.File

/** Failure-safe factory retries; diagnostics do not turn a failed GPU setup into success. */
object DxvkDxgiUpdate {
    const val REVISION = "2.7.1-arm64ec-aura-init3"
    const val TRACE_VARIABLE = "AURA_DXVK_INIT_TRACE"
    const val DISPLAY_TRACE_VARIABLE = "AURA_BF3_DISPLAY_TRACE"

    @JvmStatic fun prepare(context: Context, container: File) = DxvkDllUpdate.prepare(
        context, container, "dxgi", REVISION, "DXVK_DXGI_INIT_RUNTIME_V1",
        "fix=SINGLETON_EXCEPTION_RETRY; gpu_initialization=UNCONFIRMED")

    /** Last launch-time override: stale/custom flags cannot enable automatic collection. */
    fun configureTrace(env: EnvVars, capture: StartupExceptionCapture?, executable: String? = null,
                       runtimeRevision: String = REVISION) {
        val recording = capture?.recording() == true
        env.put(TRACE_VARIABLE, if (recording) "1" else "0")
        val bf3 = executable?.replace('\\', '/')?.substringAfterLast('/')?.equals("bf3.exe", true) == true
        val display = recording && bf3
        env.put(DISPLAY_TRACE_VARIABLE, if (display) "1" else "0")
        if (display) capture?.accept("BF3_DISPLAY_CAPTURE_V1 state=ARMED; runtime=$runtimeRevision; " +
            "limit_rows=384; limit_ms=120000; scope=DXGI_REQUESTS; pixels=UNOBSERVED; " +
            "missing_rows=UNCONFIRMED; native_clock=WINE_TICK_MS")
    }

    /** Native environments cannot be revoked after exec; late rows must not enter any host log. */
    fun isStartupTrace(line: String): Boolean =
        line.contains("AURA_DXGI_MODE_V1 ") || line.contains("AURA_DXVK_INIT_V1 ")

    fun retainOutput(line: String, capture: StartupExceptionCapture?): Boolean =
        !isStartupTrace(line) || capture?.recording() == true
}

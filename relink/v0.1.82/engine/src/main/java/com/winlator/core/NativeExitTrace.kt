/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.app.ApplicationExitInfo

/** Android exposes only this app's native tombstones. Keep a small sanitized summary in memory. */
object NativeExitTrace {
    private val cache = linkedMapOf<String, String>()

    @Synchronized
    fun summary(exit: ApplicationExitInfo): String {
        if (exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return ""
        val key = "${exit.pid}:${exit.timestamp}:${exit.status}"
        cache[key]?.let { return it }
        val summary = try {
            exit.traceInputStream?.use { NativeCrashSummary.summarize(it, exit.pid) }
                ?: "NATIVE_TRACE NOT_PROVIDED"
        } catch (_: Exception) { "NATIVE_TRACE UNREADABLE" }
        // The system may publish a tombstone shortly after its exit record. Allow a later retry.
        if (summary != "NATIVE_TRACE NOT_PROVIDED" && summary != "NATIVE_TRACE UNREADABLE") {
            cache[key] = summary
            while (cache.size > 16) cache.remove(cache.keys.first())
        }
        return summary
    }
}

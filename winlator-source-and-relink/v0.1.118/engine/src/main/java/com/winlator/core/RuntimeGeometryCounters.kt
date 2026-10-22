/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/** Debug-only bounded totals for all X clients. No per-request allocation or file I/O. */
class RuntimeGeometryCounters {
    private class Timing {
        var count = 0L
        var sum = 0L
        var max = 0L
        fun add(ns: Long) { val value = ns.coerceAtLeast(0); count++; sum += value; max = maxOf(max, value) }
        fun take() = RuntimeTiming(count, sum, max).also { count = 0; sum = 0; max = 0 }
    }
    private val windows = Timing()
    private val fallbacks = Timing()
    private val windowLocks = Timing()
    private val drawableLocks = Timing()
    private var errors = 0L

    @Synchronized
    fun record(windowPath: Boolean, failed: Boolean, windowLockNs: Long, drawableLockNs: Long, totalNs: Long) {
        windowLocks.add(windowLockNs)
        if (windowPath) windows.add(totalNs) else {
            fallbacks.add(totalNs)
            drawableLocks.add(drawableLockNs)
        }
        if (failed) errors++
    }

    @Synchronized
    fun snapshot() = RuntimeGeometrySample(windows.take(), fallbacks.take(), windowLocks.take(),
        drawableLocks.take(), errors).also { errors = 0 }
}

data class RuntimeGeometrySample(val windows: RuntimeTiming, val fallbacks: RuntimeTiming,
    val windowLocks: RuntimeTiming, val drawableLocks: RuntimeTiming, val errors: Long) {
    fun report(): String {
        fun timing(value: RuntimeTiming) = if (value.count == 0L) "?/?" else
            String.format(Locale.ROOT, "%.3f/%.3f", value.averageMs, value.maxMs)
        return "X_GEOMETRY_SERVER_V1 scope=ALL_CLIENTS; window_fast=${windows.count}; drawable_fallback=${fallbacks.count}" +
            "; errors=$errors; window_ms=${timing(windows)}; fallback_ms=${timing(fallbacks)}" +
            "; window_lock_ms=${timing(windowLocks)}; drawable_lock_ms=${timing(drawableLocks)}; timing=HOST_REQUEST_AVG_MAX"
    }
}

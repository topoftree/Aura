/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Separate, opt-in cadence counters: never drain diagnostics or optimization/stall observers. */
class GamePowerCounters {
    enum class Path { PRESENT, GLX, SHM_PUT, X_PUT }
    data class Sample(val path: Path, val window: Int, val width: Int, val height: Int,
                      val count: Long, val lastNs: Long)
    private class Slot {
        var path = Path.PRESENT; var window = 0; var width = 0; var height = 0
        var count = 0L; var last = 0L
    }
    private val slots = Array(16) { Slot() }
    @Volatile private var enabled = false

    @Synchronized fun enable(value: Boolean) {
        if (enabled == value) return
        enabled = value
        slots.forEach { it.window = 0; it.count = 0 }
    }

    fun record(path: Path, window: Int, width: Int, height: Int, nowNs: Long) {
        if (!enabled || window == 0) return
        synchronized(this) {
            if (!enabled) return
            var target = slots[0]
            for (slot in slots) {
                if (slot.window == window && slot.path == path) { target = slot; break }
                if (slot.window == 0 || slot.count < target.count) target = slot
            }
            if (target.window != window || target.path != path || target.width != width || target.height != height)
                target.count = 0
            target.window = window; target.path = path; target.width = width; target.height = height
            target.count++; target.last = nowNs
        }
    }

    @Synchronized fun take(): List<Sample> = slots.mapNotNull { slot ->
        if (slot.count == 0L) null else Sample(slot.path, slot.window, slot.width, slot.height,
            slot.count, slot.last).also { slot.count = 0 }
    }
}

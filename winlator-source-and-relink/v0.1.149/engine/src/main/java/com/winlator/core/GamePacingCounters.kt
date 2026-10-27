/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Independent, opt-in counters. Never drains diagnostics or allocates on a Present callback. */
class GamePacingCounters {
    data class Sample(val window: Int, val frames: Int, val hitches: Int, val lastNs: Long, val discontinuity: Boolean)
    private var enabled = false
    private var window = 0
    private var last = 0L
    private var average = 0L
    private var frames = 0
    private var hitches = 0
    private var discontinuity = false

    @Synchronized fun enable(value: Boolean) {
        if (enabled == value) return
        enabled = value
        window = 0; last = 0; average = 0; frames = 0; hitches = 0; discontinuity = false
    }

    @Synchronized fun present(id: Int, nowNs: Long) {
        if (!enabled) return
        if (id != window) {
            window = id; last = 0; average = 0; frames = 0; hitches = 0; discontinuity = true
        }
        if (last > 0) {
            val gap = nowNs - last
            if (gap !in 1..2_000_000_000L) {
                discontinuity = true; average = 0
            } else {
                frames++
                // Compare with the same window's normal cadence, not a hard-coded FPS target.
                if (average > 0 && gap > 80_000_000 && gap > average * 3) hitches++
                else average = if (average == 0L) gap else (average * 7 + gap) / 8
            }
        }
        last = nowNs
    }

    @Synchronized fun take(): Sample = Sample(window, frames, hitches, last, discontinuity).also {
        frames = 0; hitches = 0; discontinuity = false
    }
}

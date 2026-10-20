/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** A suspected Present gap, not a deadlock or a game-ready verdict. No I/O or target writes. */
class GameStallWatch {
    private var previousPoll: Long? = null
    private var epoch: Long? = null
    private var window = 0
    private var began = 0L
    private var firstSerial = 0L
    private var armed = false
    private var used = false

    fun reset() {
        previousPoll = null; epoch = null; window = 0; armed = false
    }

    /** Call every 500ms. Long host scheduling gaps and pause epochs invalidate the warm-up. */
    fun observe(nowNs: Long, frame: PresentHeartbeat, activity: GameWindowDiagnostics.Activity,
                eligible: Boolean): Boolean {
        if (used) return false
        val gap = nowNs - frame.timeNs
        val elapsed = previousPoll?.let { nowNs - it }
        if (!eligible || activity.paused || frame.window == 0 || frame.serial <= 0 || gap < 0) {
            reset(); return false
        }
        if (epoch != activity.epoch || window != frame.window || elapsed == null || elapsed !in 0..1_500_000_000L) {
            reset()
            epoch = activity.epoch; window = frame.window
            began = nowNs; firstSerial = frame.serial
        }
        previousPoll = nowNs
        if (gap < 1_000_000_000L && nowNs - began >= 5_000_000_000L && frame.serial - firstSerial >= 30) armed = true
        // Before arming, slow/unsteady startup must begin a new continuous warm-up.
        if (!armed && gap >= 1_000_000_000L) { began = nowNs; firstSerial = frame.serial }
        return armed && gap >= 2_000_000_000L
    }

    /** Only after the caller verifies current focus, window ownership, epoch and unchanged frame. */
    fun consume() { used = true; reset() }

    companion object {
        /** The automatic capture still relies on Mirror's Edge-specific diagnostics. */
        fun supportsExecutable(executable: String): Boolean {
            val parts = executable.replace('\\', '/').split('/')
            return parts.size >= 2 && parts.none { it == ".." } &&
                parts.last().equals("MirrorsEdge.exe", true) &&
                parts[parts.lastIndex - 1].equals("Binaries", true)
        }

        fun enabled(options: PerGameOptions, executable: String) =
            options.mirrorsEdgeStallCapture && supportsExecutable(executable)
    }
}

data class GameStallTrigger(val gapMs: Long, val window: Int, val serial: Long, val epoch: Long) {
    fun render() = "GAME_STALL_TRIGGER_V1 reason=SUSPECTED_PRESENT_GAP; gap_ms=$gapMs; " +
        "window=$window; serial=$serial; activity_epoch=$epoch; focus=TARGET; scope=SELECTED_EXE; " +
        "budget=ONE_PER_LAUNCH; cause=UNCONFIRMED\n"
}

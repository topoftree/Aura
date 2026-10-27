/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Host timer for the existing copy/Idle Present path, not a physical vblank clock.
 * RandR and Present use the same observed display rate. Explicit game/DXVK FPS
 * limits are independent. No display queries, sleeping or I/O on the frame path.
 */
class PresentTiming @JvmOverloads constructor(startNs: Long = System.nanoTime()) {
    private var anchorNs = startNs
    private var anchorMsc = 0L
    private var refreshMilliHz = 60_000
    private var periodNs = period(refreshMilliHz)
    private var observed = false
    private var changes = 0L

    /** Reject unavailable/broken readings, retaining the last valid rate (initially 60 Hz). */
    @Synchronized
    fun observeRefreshRate(refreshHz: Float, nowNs: Long): Boolean {
        if (!refreshHz.isFinite() || refreshHz < 1f || refreshHz > 1000f) return false
        observed = true
        val nextRate = (refreshHz * 1000).roundToInt()
        if (nextRate == refreshMilliHz) return false
        val elapsed = (nowNs - anchorNs).coerceAtLeast(0)
        val nextPeriod = period(nextRate)
        // Preserve both completed ticks and fractional phase. Dividing uptime by
        // the new period would jump MSC backwards when the display slows down.
        anchorMsc += elapsed / periodNs
        anchorNs = nowNs - (elapsed % periodNs) * nextPeriod / periodNs
        refreshMilliHz = nextRate
        periodNs = nextPeriod
        changes++
        return true
    }

    @Synchronized
    fun mediaStreamCounter(nowNs: Long): Long =
        anchorMsc + (nowNs - anchorNs).coerceAtLeast(0) / periodNs

    /** RandR 1.1 represents rates as integer Hz; Present retains fractional precision. */
    @Synchronized
    fun randrRate(): Int = (refreshMilliHz / 1000.0).roundToInt()

    /**
     * Keep outstanding per-window reservations in order, including a rate change.
     * An idle window starts a fresh interval; dispatch jitter does not accumulate
     * into an extra full interval on each frame. Called only after the copy finishes.
     */
    @Synchronized
    fun nextIdleDeadline(lastDeadlineNs: Long, nowNs: Long): Long =
        if (lastDeadlineNs <= nowNs - periodNs) nowNs + periodNs else lastDeadlineNs + periodNs

    @Synchronized
    fun diagnostic(): String = "PRESENT_TIMING_V1 source=${if (observed) "ANDROID_DISPLAY" else "FALLBACK"}; " +
        "refresh_millihz=$refreshMilliHz; period_ns=$periodNs; changes=$changes; " +
        "clock=HOST_TIMER; scanout_fps=NOT_MEASURED"

    private fun period(milliHz: Int) = (1_000_000_000_000.0 / milliHz).roundToLong()
}

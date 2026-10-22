/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Host wall times, not GPU execution times. No I/O or per-frame collections/allocations. */
class RuntimePerformanceCounters {
    @JvmField val content = RuntimeContentCounters()
    @JvmField val geometry = RuntimeGeometryCounters()
    @JvmField val pacing = GamePacingCounters()
    private class Timing {
        var count = 0L
        var total = 0L
        var max = 0L
        fun add(ns: Long) { val value = ns.coerceAtLeast(0); count++; total += value; max = maxOf(max, value) }
        fun take(): RuntimeTiming = RuntimeTiming(count, total, max).also { count = 0; total = 0; max = 0 }
    }
    private class Window {
        var id = 0
        var last = 0L
        var hasLast = false
        var presents = 0L
        var over50 = 0L
        var over100 = 0L
        val intervals = Timing()
        fun clearCounts() { presents = 0; over50 = 0; over100 = 0; intervals.take() }
    }
    private val windows = Array(8) { Window() }
    private val locks = Timing()
    private val copies = Timing()
    private val draws = Timing()
    private val drawLocks = Timing()
    private var gpuCopies = 0L
    private var cpuCopies = 0L
    private var scheduledMax = 0L
    private var lateMax = 0L
    private var renderRequests = 0L
    private var renderCallbacks = 0L
    private var requestPending = false
    private var firstRequestNs = 0L
    private var hasLastRender = false
    private var lastRenderNs = 0L
    private val renderQueue = Timing()
    private val renderGaps = Timing()
    private var heartbeatEnabled = false
    private var heartbeatWindow = 0
    private var heartbeatTime = 0L
    private var heartbeatSerial = 0L

    /** Opt-in observer. Reading this never drains the ten-second performance counters. */
    @Synchronized fun watchPresents(enabled: Boolean) {
        heartbeatEnabled = enabled
        heartbeatWindow = 0; heartbeatTime = 0; heartbeatSerial = 0
    }
    @Synchronized fun presentHeartbeat() = PresentHeartbeat(heartbeatWindow, heartbeatTime, heartbeatSerial)

    @Synchronized fun renderRequested(nowNs: Long) {
        renderRequests++
        if (!requestPending) { requestPending = true; firstRequestNs = nowNs }
    }
    @Synchronized fun renderStarted(nowNs: Long) {
        renderCallbacks++
        if (requestPending) {
            if (nowNs >= firstRequestNs) renderQueue.add(nowNs - firstRequestNs)
            requestPending = false
        }
        if (hasLastRender && nowNs >= lastRenderNs) renderGaps.add(nowNs - lastRenderNs)
        hasLastRender = true; lastRenderNs = nowNs
    }

    @Synchronized
    fun present(windowId: Int, nowNs: Long, lockNs: Long, copyNs: Long, gpu: Boolean) {
        pacing.present(windowId, nowNs)
        if (heartbeatEnabled) {
            heartbeatWindow = windowId; heartbeatTime = nowNs; heartbeatSerial++
        }
        var target = windows[0]
        for (window in windows) {
            if (window.id == windowId) { target = window; break }
            if (window.id == 0 || window.presents < target.presents) target = window
        }
        if (target.id != windowId) {
            target.id = windowId; target.hasLast = false; target.clearCounts()
        }
        if (target.hasLast) {
            val gap = nowNs - target.last
            if (gap >= 0) {
                target.intervals.add(gap)
                if (gap > 50_000_000) target.over50++
                if (gap > 100_000_000) target.over100++
            }
        }
        target.last = nowNs; target.hasLast = true; target.presents++
        locks.add(lockNs); copies.add(copyNs)
        if (gpu) gpuCopies++ else cpuCopies++
    }

    @Synchronized fun draw(lockNs: Long, workNs: Long) { drawLocks.add(lockNs); draws.add(workNs) }
    @Synchronized fun idleScheduled(delayNs: Long) { scheduledMax = maxOf(scheduledMax, delayNs) }
    @Synchronized fun idleDispatched(lateNs: Long) { lateMax = maxOf(lateMax, lateNs) }

    /** The most frequently presented window avoids counting a launcher and game as double FPS. */
    @Synchronized
    fun snapshot(): RuntimePerformanceSample {
        val window = windows.maxBy { it.presents }
        val sample = RuntimePerformanceSample(window.id, window.presents, window.intervals.take(),
            window.over50, window.over100, gpuCopies, cpuCopies, locks.take(), copies.take(),
            draws.take(), drawLocks.take(), scheduledMax, lateMax,
            renderRequests, renderCallbacks, renderQueue.take(), renderGaps.take())
        for (item in windows) {
            if (item.presents == 0L) item.hasLast = false
            item.clearCounts()
        }
        gpuCopies = 0; cpuCopies = 0; scheduledMax = 0; lateMax = 0
        if (renderCallbacks == 0L) hasLastRender = false
        renderRequests = 0; renderCallbacks = 0
        return sample
    }
}

data class PresentHeartbeat(val window: Int, val timeNs: Long, val serial: Long)

data class RuntimeTiming(val count: Long, val totalNs: Long, val maxNs: Long) {
    val averageMs: Double get() = if (count == 0L) 0.0 else totalNs.toDouble() / count / 1_000_000
    val maxMs: Double get() = maxNs / 1_000_000.0
}

data class RuntimePerformanceSample(
    val windowId: Int, val presents: Long, val intervals: RuntimeTiming,
    val over50ms: Long, val over100ms: Long, val gpuCopies: Long, val cpuCopies: Long,
    val presentLocks: RuntimeTiming, val copies: RuntimeTiming,
    val draws: RuntimeTiming, val drawLocks: RuntimeTiming,
    val idleScheduledMaxNs: Long, val idleLateMaxNs: Long,
    val renderRequests: Long, val renderCallbacks: Long, val renderQueue: RuntimeTiming, val renderGaps: RuntimeTiming,
)

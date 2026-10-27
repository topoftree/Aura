/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Host content updates, not displayed frames. Fixed storage; no I/O or per-update allocations. */
class RuntimeContentCounters(private val powerAdvice: GamePowerCounters? = null) {
    enum class Path { GLX, SHM_PUT, X_PUT, X_COPY, SHARED_GPU }
    private class Timing {
        var count = 0L; var total = 0L; var max = 0L
        fun add(ns: Long) { if (ns >= 0) { count++; total += ns; max = maxOf(max, ns) } }
        fun clear() { count = 0; total = 0; max = 0 }
        fun take() = RuntimeTiming(count, total, max).also { clear() }
    }
    private class Slot {
        var drawable = 0; var width = 0; var height = 0
        var count = 0L; var last = 0L; var hasLast = false; var pixels = 0L
        val work = Timing(); val lock = Timing(); val gaps = Timing()
        fun resetCounts() { count = 0; pixels = 0; work.clear(); lock.clear(); gaps.clear() }
    }
    private val paths = Array(Path.entries.size) { Array(DRAWABLE_LIMIT) { Slot() } }
    private val evicted = LongArray(Path.entries.size)

    @Synchronized
    fun record(path: Path, drawable: Int, width: Int, height: Int, nowNs: Long, workNs: Long, lockNs: Long) {
        if (drawable == 0) return
        val powerPath = when (path) {
            Path.GLX -> GamePowerCounters.Path.GLX
            Path.SHM_PUT -> GamePowerCounters.Path.SHM_PUT
            Path.X_PUT -> GamePowerCounters.Path.X_PUT
            Path.X_COPY -> null // Blits can happen many times per frame.
            Path.SHARED_GPU -> null // Experimental accepts are not calibrated game FPS.
        }
        if (powerPath != null) powerAdvice?.record(powerPath, drawable, width, height, nowNs)
        val slots = paths[path.ordinal]
        var target = slots[0]
        for (slot in slots) {
            if (slot.drawable == drawable) { target = slot; break }
            if (slot.drawable == 0 || slot.count < target.count) target = slot
        }
        if (target.drawable != drawable) {
            if (target.count > 0) evicted[path.ordinal] += target.count
            target.drawable = drawable; target.hasLast = false; target.resetCounts()
        }
        if (target.width != width || target.height != height) target.hasLast = false
        target.width = width; target.height = height
        if (target.hasLast) target.gaps.add(nowNs - target.last)
        target.last = nowNs; target.hasLast = true; target.count++
        if (width > 0 && height > 0) target.pixels += width.toLong() * height
        target.work.add(workNs); target.lock.add(lockNs)
    }

    @Synchronized
    fun snapshot(): List<RuntimeContentSample> = buildList {
        for (path in Path.entries) {
            for (slot in paths[path.ordinal]) {
                if (slot.count > 0) add(RuntimeContentSample(path, slot.drawable, slot.width, slot.height,
                    slot.count, slot.pixels, slot.work.take(), slot.lock.take(), slot.gaps.take(), evicted[path.ordinal]))
                else slot.hasLast = false
                slot.resetCounts()
            }
            evicted[path.ordinal] = 0
        }
    }

    companion object { const val DRAWABLE_LIMIT = 8 }
}

data class RuntimeContentSample(
    val path: RuntimeContentCounters.Path, val drawable: Int, val width: Int, val height: Int,
    val updates: Long, val requestedPixels: Long, val work: RuntimeTiming, val lock: RuntimeTiming,
    val intervals: RuntimeTiming, val evictedUpdates: Long,
)

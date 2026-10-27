/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

/** Bounded sorted, disjoint full-width row ranges. Full damage wins; overflow
 * falls back to one bounding range. Snapshot before GL so later writes survive. */
class TextureDamage {
    private val starts = IntArray(MAX_REGIONS)
    private val ends = IntArray(MAX_REGIONS).apply { this[0] = Int.MAX_VALUE }
    private var count = 1

    @Synchronized fun markAll() { starts[0] = 0; ends[0] = Int.MAX_VALUE; count = 1 }
    @Synchronized fun clear() { count = 0 }
    @Synchronized fun isPending() = count > 0

    @Synchronized fun markRows(top: Int, bottom: Int, height: Int) {
        if (top < 0 || bottom > height || top >= bottom || height <= 0) { markAll(); return }
        var first = 0
        while (first < count && ends[first] < top) first++
        var end = first
        var lo = top
        var hi = bottom
        while (end < count && starts[end] <= hi) {
            lo = minOf(lo, starts[end]); hi = maxOf(hi, ends[end]); end++
        }
        val nextCount = count - (end - first) + 1
        if (nextCount > MAX_REGIONS) {
            starts[0] = minOf(top, starts[0]); ends[0] = maxOf(bottom, ends[count - 1]); count = 1
            return
        }
        if (end == first) {
            for (i in count - 1 downTo first) { starts[i + 1] = starts[i]; ends[i + 1] = ends[i] }
        } else {
            for (i in end until count) { starts[first + 1 + i - end] = starts[i]; ends[first + 1 + i - end] = ends[i] }
        }
        starts[first] = lo; ends[first] = hi; count = nextCount
    }

    /** Legacy bounding snapshot, retained for callers that accept only one range. */
    @Synchronized fun take(height: Int): Long {
        val value = if (count == 0) 0L else pack(starts[0], ends[count - 1], height)
        clear()
        return value
    }

    /** Caller reuses its fixed array. No frame-time region or buffer allocation. */
    @Synchronized fun takeRegions(height: Int, output: LongArray): Int {
        require(output.size >= MAX_REGIONS)
        var written = 0
        for (i in 0 until count) {
            val value = pack(starts[i], ends[i], height)
            if (value != 0L) output[written++] = value
        }
        clear()
        return written
    }

    private fun pack(first: Int, end: Int, height: Int): Long {
        val top = first.coerceIn(0, height.coerceAtLeast(0))
        val bottom = end.coerceIn(0, height.coerceAtLeast(0))
        return if (top < bottom) (top.toLong() shl 32) or bottom.toLong() else 0
    }

    companion object { const val MAX_REGIONS = 4 }
}

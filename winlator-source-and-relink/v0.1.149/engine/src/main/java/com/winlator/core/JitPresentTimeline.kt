/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Host Present arrivals, not display scanout or confirmed game FPS. Manual, 10s, no render-thread waits. */
class JitPresentCapture(val id: String, val owner: Any, private val startNs: Long) {
    private val lock = ReentrantLock()
    private val dropped = AtomicLong()
    private val windowIds = IntArray(32)
    private val lastNs = LongArray(32)
    private var windows = 0
    private val gaps = ArrayList<LongArray>(64)
    private val probes = ArrayList<LongArray>(6)
    private var presents = 0L
    @Volatile private var stopped = false
    private var endNs = startNs
    private var stoppedAtNs: Long? = null

    fun present(window: Int, now: Long) {
        if (stopped || now < startNs || now - startNs >= 10_000_000_000L) return
        if (!lock.tryLock()) { dropped.incrementAndGet(); return }
        try {
            if (stopped) return
            var index = 0
            while (index < windows && windowIds[index] != window) index++
            if (index == windows) index = -1
            if (index < 0) {
                if (windows == 32) { dropped.incrementAndGet(); return }
                index = windows++; windowIds[index] = window
            }
            val before = lastNs[index]
            if (before > 0 && now - before >= 50_000_000L) {
                if (gaps.size < 64) gaps += longArrayOf(window.toLong(), before, now) else dropped.incrementAndGet()
            }
            lastNs[index] = now; presents++; endNs = maxOf(endNs, now)
        } finally { lock.unlock() }
    }

    fun probe(begin: Long, end: Long) = lock.withLock {
        // Retain an in-flight probe interval after stop so overlaps cannot be misclassified.
        if (probes.size < 6 && end >= begin && begin >= startNs && begin - startNs < 10_000_000_000L)
            probes += longArrayOf(begin, end)
    }
    fun stop(now: Long = System.nanoTime()) = lock.withLock {
        if (!stopped) {
            // A Present may finish while stop waits for the lock. Never place the
            // observation endpoint before an arrival already retained here.
            stoppedAtNs = maxOf(now, endNs).coerceIn(startNs, startNs + 10_000_000_000L)
            stopped = true
        }
    }
    fun report(): String = lock.withLock {
        buildString {
            append("JIT_PRESENT_TIMELINE_V1 clock=HOST_MONOTONIC_NS; start_ns=$startNs; last_present_ns=$endNs; presents=$presents; windows=$windows")
            append("; retained_gaps=${gaps.size}; dropped=${dropped.get()}; threshold_ms=50; owner=UNCONFIRMED; fps=UNMEASURED\n")
            append("JIT_PRESENT_END_V1 stopped=${stoppedAtNs != null}; stop_ns=${stoppedAtNs ?: "UNKNOWN"}; trailing=CENSORED_NOT_COMPLETED_FRAME\n")
            probes.forEachIndexed { i, p -> append("JIT_PROBE_INTERVAL_V1 index=$i; begin_ns=${p[0]}; end_ns=${p[1]}; scope=PROBE_OPERATION_NOT_EXACT_STOP_DURATION\n") }
            gaps.forEach { g -> append("JIT_PRESENT_GAP_V1 window=${g[0]}; begin_ns=${g[1]}; end_ns=${g[2]}; duration_ns=${g[2] - g[1]}; probe_overlap=${probes.any { it[0] <= g[2] && it[1] >= g[1] }}\n") }
            stoppedAtNs?.let { stop ->
                for (index in 0 until windows) {
                    val begin = lastNs[index]
                    if (begin > 0 && stop - begin >= 50_000_000L)
                        append("JIT_PRESENT_OPEN_GAP_V1 window=${windowIds[index]}; begin_ns=$begin; observed_until_ns=$stop; minimum_duration_ns=${stop - begin}; censored=true; probe_overlap=${probes.any { it[0] <= stop && it[1] >= begin }}\n")
                }
            }
        }
    }
}

object JitPresentTimeline {
    @Volatile private var current: JitPresentCapture? = null
    @Synchronized fun start(id: String, owner: Any): JitPresentCapture {
        stop()
        return JitPresentCapture(id, owner, System.nanoTime()).also { current = it }
    }
    @JvmStatic fun present(owner: Any, window: Int) {
        val capture = current ?: return
        if (capture.owner === owner) capture.present(window, System.nanoTime())
    }
    @Synchronized fun stop(id: String? = null) {
        current?.takeIf { id == null || it.id == id }?.let { it.stop(); current = null }
    }
}

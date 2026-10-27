/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Android Surface display timestamps, not guest swap calls or synthesized X11 MSC. */
interface DisplayTimestampAccess {
    fun open(): LongArray?
    fun read(frame: Long): LongArray?
    fun same(display: Long, surface: Long): Boolean
    fun close(display: Long, surface: Long, previous: Long)
}
internal object DisplayTimestampNative : DisplayTimestampAccess {
    init { System.loadLibrary("winlator") }
    external override fun open(): LongArray?
    external override fun read(frame: Long): LongArray?
    external override fun same(display: Long, surface: Long): Boolean
    external override fun close(display: Long, surface: Long, previous: Long)
}

class DisplayCadenceCapture(private val access: DisplayTimestampAccess, val start: Long,
                            private val onFrameId: (Long) -> Unit = {},
                            private val clock: () -> Long = System::nanoTime) {
    private val lock = ReentrantLock()
    private var surface: LongArray? = null
    private var attempted = false
    private var status = "NO_HOST_DRAW"
    private var stopped = false
    private var end = start
    private val rows = ArrayList<LongArray>(2400)
    private val pending = ArrayDeque<LongArray>()
    private var lastId = 0L
    private var draws = 0L
    private var dropped = 0L
    private var duplicateIds = 0L
    private var abandoned = 0L
    private var currentRow: LongArray? = null
    fun beforeDraw() {
        if (!lock.tryLock()) return
        try {
            val now = clock()
            if (stopped || now - start !in 0 until 10_000_000_000L) return
            draws++
            if (!attempted) {
                attempted = true; surface = access.open()
                status = if (surface == null) "TIMESTAMPS_UNAVAILABLE" else "OBSERVING"
            }
            val s = surface ?: return
            if (!access.same(s[0], s[1])) { status = "SURFACE_CHANGED"; return }
            // Poll at most eight existing IDs. Pending and invalid timestamps are never zero FPS.
            repeat(minOf(8, pending.size)) {
                val row = pending.removeFirst()
                val t = access.read(row[0])
                if (t != null && t.size == 3) {
                    for (i in 0..2) row[i + 3] = t[i]
                    if (t[0] == -2L) pending.addLast(row)
                }
            }
            val id = access.read(0)?.firstOrNull() ?: -3
            if (id <= 0) { status = "FRAME_ID_UNAVAILABLE"; return }
            if (id == lastId) { duplicateIds++; return }
            if (id < lastId) { status = "FRAME_ID_RESET"; return }
            lastId = id
            onFrameId(id)
            if (rows.size == 2400) { dropped++; return }
            if (pending.size == 64) { pending.removeFirst(); abandoned++ }
            // ID, host callback begin/end, display present, rendering complete, compositor latch.
            val row = longArrayOf(id, now, -1, -2, -2, -2)
            rows.add(row); pending.addLast(row); currentRow = row
        } catch (_: Exception) { status = "QUERY_FAILED" }
        catch (_: LinkageError) { status = "NATIVE_UNAVAILABLE" }
        finally { lock.unlock() }
    }
    fun afterDraw() {
        if (!lock.tryLock()) return
        try { if (!stopped) currentRow?.set(2, clock()); currentRow = null } finally { lock.unlock() }
    }
    fun stop() = lock.withLock { if (!stopped) { stopped = true; end = clock().coerceIn(start, start + 10_000_000_000L) } }
    /** Scheduled on the owning GL thread. No new render request and no blocking swap. */
    fun cleanup() = lock.withLock { surface?.let { access.close(it[0], it[1], it[2]); surface = null } }
    fun report(): String = lock.withLock {
        val presented = rows.map { it[3] }.filter { it > 0 && it in start..end }.distinct().sorted()
        val gaps = presented.zipWithNext { a, b -> b - a }
        val ordered = gaps.sorted()
        fun percentile(p: Int): String = if (ordered.isEmpty()) "UNKNOWN" else ordered[(ordered.lastIndex * p / 100)].toString()
        fun intervalReport(label: String, begin: Int, end: Int): String {
            val values = rows.filter { it[begin] > 0 && it[end] >= it[begin] }.map { it[end] - it[begin] }.sorted()
            fun at(p: Int) = if (values.isEmpty()) "UNKNOWN" else values[values.lastIndex * p / 100].toString()
            return "$label samples=${values.size}; p50=${at(50)}; p95=${at(95)}; max=${values.lastOrNull() ?: "UNKNOWN"}\n"
        }
        buildString {
            append("FINAL_DISPLAY_V1 state=$status; scope=HOST_SURFACE_NOT_UNIQUE_GAME_FRAMES; clock=ANDROID_MONOTONIC_NS; start_ns=$start; end_ns=$end\n")
            append("DISPLAY_COUNTS draws=$draws; retained=${rows.size}; actual_present=${presented.size}; pending=${rows.count { it[3] == -2L }}; invalid=${rows.count { it[3] <= 0 && it[3] != -2L }}; duplicate_ids=$duplicateIds; abandoned=$abandoned; dropped=$dropped\n")
            append("DISPLAY_INTERVAL_NS samples=${gaps.size}; p50=${percentile(50)}; p95=${percentile(95)}; max=${ordered.lastOrNull() ?: "UNKNOWN"}; unique_game_fps=UNMEASURED\n")
            append(intervalReport("HOST_DRAW_WALL_NS", 1, 2))
            append(intervalReport("HOST_DRAW_END_TO_DISPLAY_NS", 2, 3))
            append(intervalReport("RENDER_COMPLETE_TO_DISPLAY_NS", 4, 3))
            append("COLUMNS frame_id,host_draw_begin_ns,host_draw_end_ns,display_present_ns,render_complete_ns,latch_ns; pending=-2; invalid=-1; unavailable=-3\n")
            // 512 evenly sampled rows bound text size; totals/histogram above use all retained frames.
            val stride = maxOf(1, (rows.size + 511) / 512)
            rows.filterIndexed { i, _ -> i % stride == 0 }.forEach { append(it.joinToString(",")).append('\n') }
            append("ROW_SAMPLING stride=$stride; query=NONBLOCKING; final_pending=NOT_DRAINED\n")
        }
    }
    fun reports(): Map<String, String> = lock.withLock {
        linkedMapOf("final-display-cadence.txt" to report()).apply {
            rows.chunked(300).forEachIndexed { index, part ->
                put("frame-pipeline-display-${(index + 1).toString().padStart(2, '0')}.txt",
                    "FRAME_PIPELINE_DISPLAY_V1 clock=ANDROID_MONOTONIC_NS; columns=egl_frame_id,draw_begin,draw_end,present,render_complete,latch; pending=-2; invalid=-1; unavailable=-3\n" +
                        part.joinToString("\n", postfix = "\n") { it.joinToString(",") })
            }
        }
    }
}

object FinalDisplayCadence {
    private data class Binding(val owner: Any, val queue: (Runnable) -> Unit)
    private data class Active(val id: String, val owner: Any, val capture: DisplayCadenceCapture, val queue: (Runnable) -> Unit)
    private var binding: Binding? = null
    @Volatile private var active: Active? = null
    @JvmStatic @Synchronized fun register(owner: Any, queue: java.util.function.Consumer<Runnable>) {
        binding = Binding(owner) { queue.accept(it) }
    }
    @Synchronized fun start(id: String, owner: Any): DisplayCadenceCapture? {
        stop()
        val b = binding?.takeIf { it.owner === owner } ?: return null
        return runCatching { DisplayCadenceCapture(DisplayTimestampNative, System.nanoTime(), onFrameId = {
            FramePipelineTrace.capture(owner)?.eglFrame(it)
        }).also { active = Active(id, owner, it, b.queue) } }.getOrNull()
    }
    @JvmStatic fun beforeDraw(owner: Any) { active?.takeIf { it.owner === owner }?.capture?.beforeDraw() }
    @JvmStatic fun afterDraw(owner: Any) { active?.takeIf { it.owner === owner }?.capture?.afterDraw() }
    @Synchronized fun stop(id: String? = null) {
        val a = active?.takeIf { id == null || it.id == id } ?: return
        active = null; a.capture.stop(); a.queue(Runnable { a.capture.cleanup() })
    }
    @JvmStatic @Synchronized fun unregister(owner: Any) {
        if (binding?.owner === owner) { stop(); binding = null }
    }
}

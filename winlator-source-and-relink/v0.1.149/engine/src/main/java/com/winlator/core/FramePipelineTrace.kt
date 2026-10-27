/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.IdentityHashMap
import com.winlator.xserver.PresentIdleQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Manual host timing only. Tokens are capture-local; no XIDs, pointers or guest frame claims. */
class FramePipelineCapture(private val clock: () -> Long = System::nanoTime) {
    val start = clock()
    val transport = XTransportCapture(start, clock)
    private val lock = ReentrantLock()
    @Volatile private var stopped = false
    private var end = start
    private val skipped = AtomicLong()
    private val objects = IdentityHashMap<Any, Int>()
    private val latest = LongArray(8)
    private val presents = ArrayList<LongArray>(2400)
    private val waits = ArrayList<LongArray>(2400)
    private val waitSteps = ArrayList<LongArray>()
    private val reservations = IdentityHashMap<Any, Long>()
    private var waitStepOverflow = 0L
    private val draws = ArrayList<LongArray>(2400)
    private val reads = ArrayList<LongArray>(4800)
    private val probes = ArrayList<LongArray>(6)
    private var overflow = 0L
    @Volatile private var draw: Draw? = null
    @Volatile private var associationsReliable = true
    private fun live() = !stopped && clock() - start in 0 until 10_000_000_000L
    private inline fun <T> observe(block: () -> T): T? {
        if (!live()) return null
        if (!lock.tryLock()) { skipped.incrementAndGet(); associationsReliable = false; return null }
        return try { if (live()) block() else null } finally { lock.unlock() }
    }
    private fun slot(key: Any): Int? = objects[key] ?: run {
        if (objects.size == 8) { overflow++; null }
        else objects.size.also { objects[key] = it }
    }
    // id, slot, request, locked, access begin/end, copy begin/end, source finish begin/end,
    // ready, complete, idle due/begin/end, outcome (0=incomplete,1=ready,2=failed), source finish kind.
    inner class Frame internal constructor(private val row: LongArray, private val wait: LongArray) {
        fun id(): Long = row[0]
        fun isObserving(): Boolean = live()
        fun mark(column: Int) { observe { if (column in 3..14 && row[column] < 0) row[column] = clock() - start } }
        fun finishKind(imported: Boolean) { observe { row[16] = if (imported) 1 else 2 } }
        fun ready() { observe { row[10] = clock() - start; row[15] = 1; latest[row[1].toInt()] = row[0] } }
        fun failed() { observe { row[15] = 2 } }
        fun idleDue(deadline: Long) { observe { row[12] = deadline - start } }
        fun idleReservation(ticket: Any) { observe { reservations[ticket] = row[0] } }
        fun waitStep(attempt: Int, kind: Int, timeoutNs: Long, reason: Int,
                     eligible: PresentIdleQueue.Eligibility): WaitStep? = observe {
            if (waitSteps.size == 4800) { waitStepOverflow++; return@observe null }
            val step = longArrayOf(row[0], attempt.toLong(), kind.toLong(), clock() - start, -1, -1,
                if (eligible.deadlineNs == Long.MAX_VALUE) -1 else eligible.deadlineNs - start,
                reservations[eligible.ticket] ?: 0, timeoutNs, -1, eligible.pendingCount.toLong(),
                eligible.eligibleCount.toLong(), eligible.aliasCount.toLong(), eligible.unknownCount.toLong(),
                if (eligible.sourceKnown) 1 else 0, reason.toLong())
            waitSteps += step
            WaitStep(step)
        }
        fun idleTimerWake() { observe { if (wait[13] < 0) wait[13] = clock() - start } }
        fun idleTimerLocked() { observe { if (wait[14] < 0) wait[14] = clock() - start } }
        fun idleDispatchSource(source: Int) { observe { wait[15] = source.toLong() } }
        fun targetReadFence(state: Int, probeNs: Long) { observe { wait[11] = state.toLong(); wait[12] = probeNs } }
        fun completionWait(mode: Int, calls: Int, timeouts: Int, waitNs: Long, pumpNs: Long, pumped: Int,
                           reason: Int, finishNs: Long, createNs: Long, deleteNs: Long) { observe {
            wait[1] = mode.toLong(); wait[2] = calls.toLong(); wait[3] = timeouts.toLong()
            wait[4] = waitNs; wait[5] = pumpNs; wait[6] = pumped.toLong(); wait[7] = reason.toLong()
            wait[8] = finishNs; wait[9] = createNs; wait[10] = deleteNs
            row[16] = when (mode) { 2 -> 3; 3 -> 4; else -> 1 }
        } }
    }
    inner class WaitStep internal constructor(private val row: LongArray) {
        fun completed(begin: Long, end: Long, result: Int) { observe {
            row[4] = begin - start; row[5] = end - start; row[9] = result.toLong()
        } }
    }
    inner class Draw internal constructor(private val row: LongArray) {
        fun eglFrame(id: Long) { observe { if (id > 0) row[3] = id } }
        fun end() { observe { row[2] = clock() - start }; if (draw === this) draw = null }
        internal fun read(slot: Int, frame: Long) {
            if (reads.size == 4800) { overflow++; return }
            reads += longArrayOf(row[0], slot.toLong(), frame, clock() - start)
        }
    }
    fun begin(key: Any, requestNs: Long): Frame? = observe {
        if (requestNs < start || presents.size == 2400) { overflow++; associationsReliable = false; return@observe null }
        val slot = slot(key) ?: return@observe null
        val row = LongArray(17) { -1 }
        row[0] = presents.size + 1L; row[1] = slot.toLong(); row[2] = requestNs - start; row[15] = 0
        presents += row
        val wait = LongArray(16) { -1 }.also { it[0] = row[0] }
        waits += wait
        Frame(row, wait)
    }
    fun beginDraw(): Draw? {
        draw = null
        return observe {
        if (draws.size == 2400) { overflow++; draw = null; return@observe null }
        val row = longArrayOf(draws.size + 1L, clock() - start, -1, -1)
        draws += row
        Draw(row).also { draw = it }
        }
    }
    fun drawn(key: Any) { observe { val slot = slot(key) ?: return@observe; draw?.read(slot, if (associationsReliable) latest[slot] else 0) } }
    fun eglFrame(id: Long) { draw?.eglFrame(id) }
    fun probe(begin: Long, end: Long) = lock.withLock {
        if (probes.size < 6 && begin - start in 0 until 10_000_000_000L && end >= begin)
            probes += longArrayOf(begin - start, end - start)
    }
    fun stop() = lock.withLock { if (!stopped) { stopped = true; transport.stop(); end = clock().coerceIn(start, start + 10_000_000_000L); draw = null; objects.clear(); reservations.clear() } }

    /** All retained rows exported, in bounded chunks. No stride hides the shorter intervals. */
    fun reports(): Map<String, String> = lock.withLock {
        fun distribution(name: String, values: List<Long>) = buildString {
            val ordered = values.filter { it >= 0 }.sorted()
            fun p(percent: Int) = if (ordered.isEmpty()) "UNKNOWN" else ordered[ordered.lastIndex * percent / 100].toString()
            val boundaries = longArrayOf(8_333_333, 16_666_667, 25_000_000, 33_333_333, 50_000_000)
            val bins = LongArray(6)
            ordered.forEach { n -> bins[boundaries.indexOfFirst { n <= it }.let { if (it < 0) 5 else it }]++ }
            append("$name samples=${ordered.size}; p50_ns=${p(50)}; p95_ns=${p(95)}; p99_ns=${p(99)}; max_ns=${ordered.lastOrNull() ?: "UNKNOWN"}; bins=${bins.joinToString(",")}\n")
        }
        fun intervals(column: Int) = presents.filter { it[column] >= 0 }.groupBy { it[1] }.values.flatMap { rows ->
            rows.zipWithNext { a, b -> b[column] - a[column] }
        }
        fun spans(a: Int, b: Int) = presents.filter { it[a] >= 0 && it[b] >= it[a] }.map { it[b] - it[a] }
        val summary = buildString {
            append("FRAME_PIPELINE_V1 start_ns=$start; end_ns=$end; clock=HOST_MONOTONIC_NS; relative_unit=NS; frozen=$stopped\n")
            append("LIMITS ms=10000; presents=2400; draws=2400; reads=4800; slots=8; skipped=${skipped.get()}; overflow=$overflow\n")
            append("COUNTS presents=${presents.size}; draws=${draws.size}; reads=${reads.size}; source=HOST_X11_PRESENT; game_fps=UNKNOWN; swap_call=UNOBSERVED; physical_display=SEE_FINAL_DISPLAY\n")
            append("ASSOCIATION reliable=$associationsReliable; after_skipped_observation_or_present_limit=UNKNOWN; intervals=RETAINED_OBSERVATIONS_ONLY\n")
            append("BINS upper_ns=8333333,16666667,25000000,33333333,50000000,INF; host_durations=INCLUSIVE_NOT_GPU_TIME; access_wait=EGL_SERVER_WAIT_ENQUEUE; incomplete=-1; source_finish_kind=1_FINISH_2_FLUSH_3_FENCE_4_FENCE_THEN_FINISH; frame_zero=NO_CAPTURED_PRESENT\n")
            append("WAIT_DETAILS rows=${waits.size}; wait_mode=1_FINISH_2_FENCE_3_FENCE_THEN_FINISH; fallback=0_NONE_1_CREATE_FAILED_2_WAIT_FAILED_3_ATTEMPT_BUDGET_4_UNKNOWN_RESULT_5_NO_ELIGIBLE_IDLE_6_ALIAS_OR_UNKNOWN_BUFFER_7_ELAPSED_BUDGET; target_fence=0_NONE_1_READY_2_NOT_READY_3_PROBE_FAILED; target_probe=ZERO_TIMEOUT_NO_FLUSH_NOT_GPU_TIME; idle_source=1_TIMER_2_BEFORE_COPY_3_DURING_SOURCE_WAIT_4_IMMEDIATE; timer_marks=OWN_SCHEDULED_TASK_NOT_DISPATCH_CAUSE; absent=-1\n")
            append("WAIT_STEPS_V1 rows=${waitSteps.size}; limit=4800; overflow=$waitStepOverflow; kind=1_CLIENT_WAIT_2_GL_FINISH; requested_timeout=NS_CEIL_1MS_CAPPED_BY_REMAINING_50MS_BUDGET; attempts_limit=8; selected_present=CAPTURE_LOCAL_RESERVATION_SOURCE_0_UNKNOWN; eligibility=KNOWN_DISTINCT_AHB_ONLY; result=RAW_GL_ENUM_OR_MINUS1; plan=BEFORE_CALL_OBSERVER; begin_end=ACTUAL_HOST_CALL; not_gpu_time=true; absent=-1\n")
            append(distribution("REQUEST_INTERVAL", intervals(2)))
            append(distribution("READY_INTERVAL", intervals(10)))
            append(distribution("IDLE_INTERVAL", intervals(13)))
            append(distribution("LOCK_WAIT", spans(2, 3)))
            append(distribution("TARGET_ACCESS_ENQUEUE", spans(4, 5)))
            append(distribution("COPY_CALL", spans(6, 7)))
            append(distribution("SOURCE_COMPLETE_WAIT", spans(8, 9)))
            append(distribution("IDLE_LATE", spans(12, 13)))
            append(distribution("SOURCE_CLIENT_WAIT_WALL", waits.map { it[4] }))
            append(distribution("SOURCE_GL_FINISH_WALL", waits.map { it[8] }))
            append(distribution("IDLE_PUMP_WALL", waits.map { it[5] }))
            append(distribution("IDLE_TIMER_LOCK_WAIT", waits.filter { it[13] >= 0 && it[14] >= it[13] }.map { it[14] - it[13] }))
            append(distribution("HOST_DRAW_INTERVAL", draws.zipWithNext { a, b -> b[1] - a[1] }))
            append(distribution("HOST_DRAW_WALL", draws.filter { it[2] >= it[1] }.map { it[2] - it[1] }))
            probes.forEach { append("PROBE begin_ns=${it[0]}; end_ns=${it[1]}; scope=OPERATION_NOT_EXACT_STOP\n") }
        }
        val out = linkedMapOf("frame-pipeline.txt" to summary)
        fun chunks(kind: String, columns: String, rows: List<LongArray>, size: Int) {
            rows.chunked(size).forEachIndexed { index, part ->
                out["frame-pipeline-$kind-${(index + 1).toString().padStart(2, '0')}.txt"] =
                    "FRAME_PIPELINE_ROWS_V1 kind=$kind; start_ns=$start; columns=$columns\n" +
                    part.joinToString("\n", postfix = "\n") { it.joinToString(",") }
            }
        }
        chunks("present", "id,slot,request,locked,access_begin,access_end,copy_begin,copy_end,finish_begin,finish_end,ready,complete,idle_due,idle_begin,idle_end,outcome,finish_kind", presents, 300)
        chunks("wait", "present_id,wait_mode,client_wait_calls,timeouts,client_wait_ns,idle_pump_ns,idle_pumped,fallback_reason,gl_finish_ns,fence_create_ns,fence_delete_ns,target_fence_state,target_probe_ns,timer_wake,timer_locked,idle_dispatch_source", waits, 300)
        chunks("wait-step", "present_id,attempt,kind,plan,begin,end,eligible_deadline,selected_present,requested_timeout,result,pending_count,eligible_count,alias_count,unknown_count,source_known,fallback_reason", waitSteps, 150)
        chunks("draw", "id,begin,end,egl_frame_id", draws, 300)
        chunks("read", "draw_id,slot,present_id,at", reads, 600)
        out
    }
}

object FramePipelineTrace {
    private data class Active(val id: String, val owner: Any, val capture: FramePipelineCapture)
    @Volatile private var active: Active? = null
    @Synchronized fun start(id: String, owner: Any): FramePipelineCapture {
        stop(); return FramePipelineCapture().also { active = Active(id, owner, it) }
    }
    @JvmStatic fun capture(owner: Any): FramePipelineCapture? = active?.takeIf { it.owner === owner && RuntimeDiagnostics.enabled() }?.capture
    @JvmStatic @Synchronized fun stop() { active?.capture?.stop(); active = null }
    @JvmStatic @Synchronized fun stop(id: String) { if (active?.id == id) stop() }
    @JvmStatic @Synchronized fun stopOwner(owner: Any) { if (active?.owner === owner) stop() }
}

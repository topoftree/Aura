/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Numeric /proc observations only. Thread selection is coverage, never proof of game ownership. */
data class GameSchedulingThread(
    val pid: Int, val processStart: Long, val tid: Int, val start: Long, val ticks: Long,
    val state: Char = '?', val wait: GameExecutionThread.Wait = GameExecutionThread.Wait.UNKNOWN,
)
data class GameSchedulingTargets(
    val state: String, val processes: List<GuestProcessInfo> = emptyList(),
    val threads: List<GameSchedulingThread> = emptyList(), val unreadable: Int = 0, val limited: Boolean = false,
)
data class GameSchedulingReading(
    val pid: Int, val processStart: Long, val tid: Int, val threadStart: Long,
    val beginNs: Long, val endNs: Long, val state: Char,
    val wait: GameExecutionThread.Wait, val counters: Map<String, Long>,
)
interface GameSchedulingAccess {
    fun targets(executable: String): GameSchedulingTargets
    fun read(process: GuestProcessInfo, threads: List<GameSchedulingThread>, current: () -> Boolean): List<GameSchedulingReading>
}

/** Manual worker only. No ptrace, signals, stack walks, guest calls or continuously running sampler. */
class GameSchedulingCapture(
    private val executable: String, val startNs: Long,
    private val access: GameSchedulingAccess, private val clock: () -> Long = System::nanoTime,
    private val ticksPerSecond: Long = 0,
) {
    private val lines = ArrayList<String>()
    private var targets = GameSchedulingTargets("NOT_STARTED")
    private var selected = emptyList<GameSchedulingThread>()
    private var previous = emptyMap<List<Long>, Long>()
    private var refreshAt = startNs
    private var samples = 0
    private var readWallNs = 0L
    private var rejected = 0
    private var state = "NOT_STARTED"
    private var stopped = false
    private var endNs = startNs
    private var bytes = 0
    private var outputLimited = false
    private fun key(t: GameSchedulingThread) = listOf(t.pid.toLong(), t.processStart, t.tid.toLong(), t.start)
    private fun live() = !stopped && clock() - startNs in 0 until GamePassiveCapture.WINDOW_NS
    private fun append(line: String) {
        // ASCII numeric/enumerated records. All retained records are exported, never a stride.
        if (bytes + line.length + 1 > MAX_BYTES) { outputLimited = true; return }
        lines += line; bytes += line.length + 1
    }

    fun sample(current: () -> Boolean) {
        if (!current() || !live() || samples >= MAX_SAMPLES || outputLimited) return
        if (readWallNs >= READ_BUDGET_NS) { state = "READ_BUDGET"; return }
        val begin = clock()
        // Spread the finite read budget across the window instead of spending it all at startup.
        if (readWallNs > 50_000_000L + (begin - startNs) / 25) { state = "BUDGET_PACING"; return }
        val local = ArrayList<String>()
        val index = ++samples
        fun allowed() = current() && live() && readWallNs + (clock() - begin).coerceAtLeast(0) < READ_BUDGET_NS
        try {
            if (begin >= refreshAt) {
                targets = access.targets(executable)
                refreshAt = clock() + REFRESH_NS
                val threads = targets.threads.distinctBy(::key)
                // Keep leaders plus the busiest surviving workers. The first selection is only
                // TID order; lifetime CPU ticks must never masquerade as interval load.
                val leaders = threads.filter { it.pid == it.tid }.take(4)
                val workers = threads.filter { it.pid != it.tid }.sortedWith(
                    compareByDescending<GameSchedulingThread> { t ->
                        previous[key(t)]?.takeIf { t.ticks >= it }?.let { t.ticks - it } ?: -1L
                    }.thenBy { it.pid }.thenBy { it.tid })
                selected = leaders + workers.take(MAX_THREADS - leaders.size)
                previous = threads.associate { key(it) to it.ticks }
                local += "R $index $begin ${clock()} ${targets.state} ${targets.processes.size} ${threads.size} ${selected.size} ${targets.unreadable} ${if (targets.limited) 1 else 0}"
                // Reuse the broader census reads for wait/state distributions, including workers
                // outside the detailed eight-thread selection; these are counts, not wait times.
                for (process in targets.processes.take(4)) {
                    val group = threads.filter { it.pid == process.pid && it.processStart == process.startTicks }
                    val knownStates = setOf('R', 'S', 'D', 'T', 't', 'Z', 'X')
                    val states = (knownStates + '?').joinToString(",") { s ->
                        "$s:${group.count { if (s == '?') it.state !in knownStates else it.state == s }}"
                    }
                    val waits = GameExecutionThread.Wait.entries.joinToString(",") { w -> "$w:${group.count { it.wait == w }}" }
                    local += "C $index $begin ${clock()} ${process.pid} ${process.startTicks} ${group.size} $states $waits"
                }
            }
            for (process in targets.processes.take(4)) {
                if (!allowed()) break
                val requested = selected.filter { it.pid == process.pid && it.processStart == process.startTicks }
                val readings = access.read(process, requested, ::allowed)
                for (r in readings) {
                    if (r.pid != process.pid || r.processStart != process.startTicks || r.beginNs < begin ||
                        r.endNs < r.beginNs || r.endNs >= startNs + GamePassiveCapture.WINDOW_NS ||
                        r.tid != 0 && requested.none { it.tid == r.tid && it.start == r.threadStart }) { rejected++; continue }
                    fun n(key: String) = r.counters[key]?.takeIf { it >= 0 }?.toString() ?: "?"
                    local += if (r.tid == 0)
                        "P $index ${r.beginNs} ${r.endNs} ${r.pid} ${r.processStart} ${n("cpu_ticks")} ${n("minor")} ${n("major")} ${n("rss_pages")} ${n("read_bytes")} ${n("write_bytes")} ${n("rchar")} ${n("wchar")} ${n("syscr")} ${n("syscw")}"
                    else "T $index ${r.beginNs} ${r.endNs} ${r.pid} ${r.processStart} ${r.tid} ${r.threadStart} ${r.state} ${r.wait} ${n("cpu_ticks")} ${n("runtime_ns")} ${n("runqueue_ns")} ${n("slices")} ${n("voluntary")} ${n("involuntary")} ${n("minor")} ${n("major")}"
                }
            }
            val end = clock()
            if (!current() || !live()) { rejected += local.size; state = "REVOKED_OR_DEADLINE"; return }
            local.forEach(::append)
            state = if (allowed()) targets.state else "READ_BUDGET"
            append("S $index $begin $end $state ${local.count { it.startsWith("P ") }} ${local.count { it.startsWith("T ") }} ${targets.processes.size} ${selected.size}")
        } catch (_: Exception) {
            state = "READ_ERROR"
            if (current() && live()) append("S $index $begin ${clock()} READ_ERROR 0 0 ${targets.processes.size} ${selected.size}")
        }
        finally { readWallNs += (clock() - begin).coerceAtLeast(0) }
    }

    /** Bracket existing slow snapshot operations so an analyst can see observer overlap. */
    fun operation(kind: String, begin: Long, end: Long) {
        require(kind in setOf("COUNTERS", "INITIAL_COUNTERS"))
        if (!stopped && begin >= startNs && end >= begin && begin - startNs < GamePassiveCapture.WINDOW_NS)
            append("O $kind $begin $end")
    }

    fun stop() {
        if (stopped) return
        endNs = clock().coerceIn(startNs, startNs + GamePassiveCapture.WINDOW_NS)
        stopped = true
    }

    fun reports(): Map<String, String> {
        check(stopped)
        val chunks = ArrayList<String>(); var chunk = StringBuilder()
        for (line in lines) {
            if (chunk.length + line.length + 1 > 60 * 1024) { chunks += chunk.toString(); chunk = StringBuilder() }
            chunk.appendLine(line)
        }
        if (chunk.isNotEmpty()) chunks += chunk.toString()
        check(chunks.size <= 8)
        val report = "GAME_SCHEDULING_V1 start_ns=$startNs; end_ns=$endNs; clock=HOST_MONOTONIC_NS; " +
            "state=$state; samples=$samples; read_wall_ns=$readWallNs; rejected=$rejected; output_limited=$outputLimited; chunks=${chunks.size}\n" +
            "LIMITS samples=$MAX_SAMPLES; interval_ns=$INTERVAL_NS; refresh_ns=$REFRESH_NS; read_budget_ns=$READ_BUDGET_NS; threads=$MAX_THREADS; processes=4; census_threads_per_process=128; bytes=$MAX_BYTES; ticks_per_second=$ticksPerSecond\n" +
            "COVERAGE scope=SESSION_SELECTED_EXE; selection=LEADERS_AND_INTERVAL_CPU_WORKERS; first_selection=BASELINE_TID_ORDER; others=UNOBSERVED; counters=CUMULATIVE; absent=?; snapshots_atomic=NO; ptrace=NO; physical_fps=UNKNOWN\n" +
            "NOTES R=target_refresh; C=census_state_wait_counts; S=read_operation; P=process; T=thread; O=other_observer_operation. Refresh spans are inside S, not additive. Scheduling wait is CPU runqueue delay, not GPU time. State/wchan is an instant, not duration; POLL and SOCKET do not prove Internet/authentication waits. rchar includes cached/file/pipe I/O, not storage bytes. Zero schedstat can mean disabled kernel accounting.\n" +
            "R sample begin_ns end_ns state processes enumerated_threads selected_threads unreadable limited\n" +
            "C sample begin_ns end_ns pid process_start enumerated_threads state_counts wait_counts\n" +
            "S sample begin_ns end_ns state readable_processes readable_threads selected_processes selected_threads\nP sample begin_ns end_ns pid process_start cpu_ticks minor major rss_pages read_bytes write_bytes rchar wchar syscr syscw\n" +
            "T sample begin_ns end_ns pid process_start tid thread_start state wait cpu_ticks runtime_ns runqueue_ns slices voluntary involuntary minor major\n" +
            "O kind begin_ns end_ns\n"
        return linkedMapOf("thread-scheduling.txt" to report).also { result ->
            chunks.forEachIndexed { index, text -> result["thread-scheduling-${(index + 1).toString().padStart(2, '0')}.txt"] = text }
        }
    }

    companion object {
        const val MAX_THREADS = 8
        const val MAX_SAMPLES = 200
        const val MAX_BYTES = 460 * 1024
        const val INTERVAL_NS = 50_000_000L
        const val REFRESH_NS = 500_000_000L
        const val READ_BUDGET_NS = 500_000_000L
    }
}

/** Ten seconds of timing first; intrusive PC observations must be a distinct, later phase. */
object GamePerformanceSequence {
    fun run(startNs: Long, current: () -> Boolean, sample: () -> Unit, slow: (Int) -> Unit,
            clock: () -> Long = System::nanoTime, wait: (Long) -> Unit = Thread::sleep): String {
        var nextSlow = startNs + 1_000_000_000L
        var slowIndex = 0
        repeat(GameSchedulingCapture.MAX_SAMPLES) {
            if (!current()) return "SESSION_CHANGED_OR_PAUSED"
            val begin = clock()
            if (begin < startNs) return "CLOCK_CHANGED"
            if (begin - startNs >= GamePassiveCapture.WINDOW_NS) return "COMPLETE"
            sample()
            if (!current()) return "SESSION_CHANGED_OR_PAUSED"
            if (clock() >= nextSlow && clock() - startNs < GamePassiveCapture.WINDOW_NS) {
                slow(++slowIndex); nextSlow = clock() + 1_000_000_000L
            }
            if (!current()) return "SESSION_CHANGED_OR_PAUSED"
            val remaining = minOf(begin + GameSchedulingCapture.INTERVAL_NS, startNs + GamePassiveCapture.WINDOW_NS) - clock()
            if (remaining > 0) wait(((remaining + 999_999) / 1_000_000).coerceAtMost(50))
        }
        return if (clock() - startNs >= GamePassiveCapture.WINDOW_NS) "COMPLETE" else "CLOCK_STALLED_OR_BUDGET"
    }
}

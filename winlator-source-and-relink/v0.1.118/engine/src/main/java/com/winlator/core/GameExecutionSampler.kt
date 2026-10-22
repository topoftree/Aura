/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/** Read-only observations, deliberately separate from guest signaling and launch decisions. */
interface GameExecutionAccess {
    fun session(): GuestProcessScan?
    fun command(process: GuestProcessInfo): String?
    fun sample(process: GuestProcessInfo): GameExecutionProcess?
}

data class GameExecutionStat(val pid: Int, val startTicks: Long, val cpuTicks: Long, val state: Char) {
    companion object {
        fun parse(text: String): GameExecutionStat? {
            val open = text.indexOf('(')
            val close = text.lastIndexOf(')')
            if (open < 1 || close <= open) return null
            val pid = text.substring(0, open).trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
            val fields = text.substring(close + 1).trim().split(Regex("\\s+"))
            if (fields.size < 20 || fields[0].length != 1) return null
            fun nonnegative(index: Int) = fields[index].toLongOrNull()?.takeIf { it >= 0 }
            val user = nonnegative(11) ?: return null
            val kernel = nonnegative(12) ?: return null
            val start = nonnegative(19) ?: return null
            if (user > Long.MAX_VALUE - kernel) return null
            return GameExecutionStat(pid, start, user + kernel, fields[0][0])
        }
    }
}

data class GameExecutionThread(val state: Char, val wait: Wait, val stat: GameExecutionStat? = null) {
    enum class Wait { FUTEX, PIPE, POLL, SOCKET, TIMER, CHILD, OTHER, UNKNOWN }
    companion object {
        /** Kernel symbols are categorized, never copied into a report. Zero is commonly restricted. */
        fun waitCategory(text: String?): Wait {
            val name = text?.trim()?.lowercase(Locale.ROOT) ?: return Wait.UNKNOWN
            if (name == "0" || !name.matches(Regex("[a-z_][a-z0-9_]{0,95}"))) return Wait.UNKNOWN
            return when {
                "futex" in name -> Wait.FUTEX
                "pipe" in name -> Wait.PIPE
                "poll" in name || "select" in name -> Wait.POLL
                "sock" in name || "tcp" in name || name == "sk_wait_data" -> Wait.SOCKET
                "nanosleep" in name || "hrtimer" in name -> Wait.TIMER
                name == "do_wait" || name == "kernel_wait4" -> Wait.CHILD
                else -> Wait.OTHER
            }
        }
    }
}

data class GameExecutionProcess(
    val stat: GameExecutionStat, val threads: List<GameExecutionThread>,
    val leader: GameExecutionThread?, val complete: Boolean,
)

/** A comparable live worker, ranked by interval CPU time, never by lifetime ticks. */
data class GameHotThread(
    val pid: Int, val processStart: Long, val tid: Int, val threadStart: Long,
    val rank: Int, val cpuMs: Long, val intervalMs: Long, val measuredAtMs: Long, val epoch: Long,
) {
    fun isFresh(nowMs: Long, activity: GameWindowDiagnostics.Activity): Boolean =
        !activity.paused && activity.epoch == epoch && pid > 1 && tid > 1 && tid != pid &&
            processStart > 0 && threadStart > 0 && rank in 1..2 && cpuMs > 0 && intervalMs > 0 &&
            measuredAtMs >= 0 && nowMs >= measuredAtMs && nowMs - measuredAtMs <= 2000
}

/** One bounded sample on the existing ~10s worker, never on the UI/render thread. */
class GameExecutionSampler(
    executable: String, private val access: GameExecutionAccess, private val ticksPerSecond: Long,
) {
    private val selected = basename(executable)
    private data class ThreadKey(val process: Pair<Int, Long>, val tid: Int, val start: Long)
    private data class Previous(val time: Long, val cpu: Map<Pair<Int, Long>, Long>, val threads: Map<ThreadKey, Long>)
    private var previous: Previous? = null
    private var activityEpoch: Long? = null
    private var hotThreads = emptyList<GameHotThread>()

    /** Reuses the counters already read; no additional /proc reads or background sampling. */
    fun hotThread(rank: Int, nowMs: Long, activity: GameWindowDiagnostics.Activity): GameHotThread? =
        hotThreads.firstOrNull { it.rank == rank && it.isFresh(nowMs, activity) }

    fun report(nowMs: Long, activity: () -> GameWindowDiagnostics.Activity): String {
        val before = activity()
        hotThreads = emptyList()
        if (activityEpoch != before.epoch) previous = null
        activityEpoch = before.epoch
        if (before.paused) return unavailable("PAUSED")
        val result = try { capture(nowMs) } catch (_: Exception) { unavailable("UNAVAILABLE") }
        return if (activity() == before) result else unavailable("TRANSITION")
    }

    private fun unavailable(state: String): String {
        previous = null
        hotThreads = emptyList()
        return "GAME_EXECUTION_STATE_V1 source=SESSION_PROC; state=$state; cpu_ms=?; ready=UNCONFIRMED"
    }

    private fun capture(nowMs: Long): String {
        if (selected.isEmpty()) return unavailable("UNAVAILABLE")
        val session = access.session() ?: return unavailable("UNAVAILABLE")
        var partial = session.unreadable > 0 || session.processes.size > MAX_PROCESSES
        val matched = mutableListOf<GuestProcessInfo>()
        for (process in session.processes.take(MAX_PROCESSES)) {
            val command = access.command(process)
            if (command == null) partial = true
            else if (matches(command)) matched += process
        }
        if (matched.isEmpty()) return unavailable(if (partial) "UNAVAILABLE" else "NOT_MATCHED")
        if (matched.size > MAX_MATCHES) partial = true
        val samples = matched.take(MAX_MATCHES).mapNotNull { process ->
            access.sample(process)?.takeIf { it.stat.pid == process.pid && it.stat.startTicks == process.startTicks }
                .also { if (it == null || !it.complete) partial = true }
        }
        if (samples.isEmpty()) return unavailable("UNAVAILABLE")
        val cpu = samples.associate { (it.stat.pid to it.stat.startTicks) to it.stat.cpuTicks }
        val before = previous
        val interval = before?.let { nowMs - it.time }?.takeIf { it > 0 }
        val delta = if (!partial && before != null && interval != null && before.cpu.keys == cpu.keys &&
            cpu.all { (key, value) -> value >= before.cpu.getValue(key) }) {
            cpu.entries.fold(0L as Long?) { sum, (key, value) ->
                val change = value - before.cpu.getValue(key)
                sum?.takeIf { it <= Long.MAX_VALUE - change }?.plus(change)
            }
        } else null
        val cpuMs = delta?.takeIf { ticksPerSecond in 1..1_000_000 && it <= Long.MAX_VALUE / 1000 }
            ?.let { it * 1000 / ticksPerSecond }
        val threads = samples.flatMap { it.threads }
        // Retain counters already read for identity checking: no extra /proc reads, names or stacks.
        val threadCpu = samples.flatMap { process -> process.threads.mapNotNull { thread ->
            thread.stat?.let { stat ->
                ThreadKey(process.stat.pid to process.stat.startTicks, stat.pid, stat.startTicks) to stat.cpuTicks
            }
        } }.toMap()
        val comparable = if (cpuMs != null && before != null && interval != null)
            threadCpu.mapNotNull { (key, value) ->
                before.threads[key]?.takeIf { value >= it }?.let { old ->
                    ticksToMs(value - old)?.let { key to it }
                }
            } else emptyList()
        val threadState = when {
            partial -> "PARTIAL"
            before == null || interval == null || comparable.isEmpty() -> "WARMUP"
            comparable.size != threadCpu.size || threadCpu.keys != before.threads.keys -> "PARTIAL"
            else -> "OBSERVED"
        }
        val busiest = comparable.sortedByDescending { it.second }.take(3)
            .joinToString(",") { (key, ms) -> "${key.tid}:$ms" }.ifEmpty { "?" }
        val threadReport = "GAME_THREAD_CPU_V1 state=$threadState; interval_ms=${if (comparable.isEmpty()) "?" else interval}" +
            "; observed=${threadCpu.size}; compared=${comparable.size}; top_tid_cpu_ms=[$busiest]; ready=UNCONFIRMED"
        previous = if (partial) null else Previous(nowMs, cpu, threadCpu)
        if (!partial && matched.size == 1 && interval != null) {
            hotThreads = comparable.filter { (key, ms) -> key.tid != key.process.first && ms > 0 }
                .sortedWith(compareByDescending<Pair<ThreadKey, Long>> { it.second }.thenBy { it.first.tid })
                .take(2).mapIndexed { index, (key, ms) ->
                    GameHotThread(key.process.first, key.process.second, key.tid, key.start,
                        index + 1, ms, interval, nowMs, activityEpoch ?: -1)
                }
        }
        fun count(vararg states: Char) = threads.count { it.state in states }
        val sleeping = threads.filter { it.state in "SDI" }
        val known = "RSDTtZXIPW"
        val leader = samples.singleOrNull()?.leader
        return "GAME_EXECUTION_STATE_V1 source=SESSION_PROC; state=${if (partial) "PARTIAL" else "OBSERVED"}" +
            "; matched=${matched.size}; sampled=${samples.size}; threads=${threads.size}" +
            "; states(R/S/D/T/Z/other)=${count('R')}/${count('S')}/${count('D')}/${count('T', 't')}/${count('Z', 'X')}/${threads.count { it.state !in "RSDTtZX" }}" +
            "; leader=${leader?.state?.takeIf { it in known } ?: "?"}/${leader?.wait ?: "UNKNOWN"}" +
            "; waits(futex/pipe/poll/other/unknown)=${sleeping.count { it.wait == GameExecutionThread.Wait.FUTEX }}/" +
            "${sleeping.count { it.wait == GameExecutionThread.Wait.PIPE }}/${sleeping.count { it.wait == GameExecutionThread.Wait.POLL }}/" +
            "${sleeping.count { it.wait !in setOf(GameExecutionThread.Wait.FUTEX, GameExecutionThread.Wait.PIPE, GameExecutionThread.Wait.POLL, GameExecutionThread.Wait.UNKNOWN) }}/" +
            "${sleeping.count { it.wait == GameExecutionThread.Wait.UNKNOWN }}" +
            "; interval_ms=${if (cpuMs != null) interval else "?"}; cpu_ms=${cpuMs ?: "?"}; ready=UNCONFIRMED\n$threadReport"
    }

    private fun ticksToMs(ticks: Long): Long? = ticks.takeIf {
        ticksPerSecond in 1..1_000_000 && it >= 0 && it <= Long.MAX_VALUE / 1000
    }?.let { it * 1000 / ticksPerSecond }

    /** Only argv[0], or argv[1] of a recognized Wine loader, may identify the target. */
    private fun matches(command: String) = matchesExecutable(selected, command)

    companion object {
        const val MAX_PROCESSES = 64
        const val MAX_MATCHES = 4
        const val MAX_THREADS = 64
        private val LOADERS = setOf("wine", "wine64", "wine-preloader", "wine64-preloader", "libwine.so")
        private fun basename(value: String) = value.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)

        fun matchesExecutable(executable: String, command: String): Boolean {
            if (!command.endsWith('\u0000')) return false
            val selected = basename(executable).takeIf(String::isNotEmpty) ?: return false
            val arguments = command.split('\u0000')
            val first = basename(arguments.first())
            return first == selected || first in LOADERS && arguments.size > 1 && basename(arguments[1]) == selected
        }
    }
}

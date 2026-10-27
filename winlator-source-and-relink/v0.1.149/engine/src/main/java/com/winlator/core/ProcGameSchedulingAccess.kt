/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.os.Process
import android.system.Os
import java.io.File

/** Each numeric read is identity-bracketed. No cmdline, kernel symbol, path or payload is exported. */
class ProcGameSchedulingAccess(
    private val root: File = File("/proc"), private val uid: Int = Process.myUid(),
    private val hostPid: Int = Process.myPid(), private val owner: (File) -> Int = { Os.stat(it.path).st_uid },
    private val execution: GameExecutionAccess = ProcGameExecutionAccess(threadLimit = 128),
    private val clock: () -> Long = System::nanoTime,
) : GameSchedulingAccess {
    override fun targets(executable: String): GameSchedulingTargets {
        val scan = execution.session() ?: return GameSchedulingTargets("NO_SESSION")
        if (scan.processes.size > 256) return GameSchedulingTargets("PROCESS_LIMIT", limited = true)
        var unreadable = scan.unreadable
        val matches = scan.processes.filter { process ->
            val command = execution.command(process)
            if (command == null) unreadable++
            command != null && GameExecutionSampler.matchesExecutable(executable, command)
        }
        if (matches.size > 4 || matches.distinctBy { it.pid }.size != matches.size)
            return GameSchedulingTargets("MATCH_LIMIT", unreadable = unreadable, limited = true)
        val processes = ArrayList<GuestProcessInfo>()
        val threads = ArrayList<GameSchedulingThread>()
        var limited = false
        for (process in matches.sortedBy { it.pid }) {
            val sample = execution.sample(process)
            if (sample == null || sample.stat.pid != process.pid || sample.stat.startTicks != process.startTicks) {
                unreadable++; continue
            }
            processes += process
            if (!sample.complete) unreadable++
            limited = limited || sample.threadScan?.limited == true
            sample.threads.take(128).forEach { thread -> thread.stat?.takeIf { it.pid > 1 && it.startTicks > 0 }?.let {
                threads += GameSchedulingThread(process.pid, process.startTicks, it.pid, it.startTicks, it.cpuTicks, thread.state, thread.wait)
            } }
        }
        return GameSchedulingTargets(if (unreadable > 0 || limited) "PARTIAL" else if (processes.isEmpty()) "NOT_MATCHED" else "OBSERVED",
            processes, threads, unreadable, limited)
    }

    private fun text(file: File, limit: Int): String? = runCatching {
        file.inputStream().use { input -> input.readNBytes(limit + 1).takeIf { it.size <= limit }?.toString(Charsets.US_ASCII) }
    }.getOrNull()
    private fun stat(directory: File) = text(File(directory, "stat"), 4096)?.let(::numericStat)
    private fun trusted(process: GuestProcessInfo): Pair<GameExecutionStat, Map<String, Long>>? = runCatching {
        if (process.pid <= 1 || process.pid == hostPid || process.uid != uid ||
            owner(File(root, "${process.pid}")) != uid) return null
        stat(File(root, "${process.pid}"))?.takeIf { (s, _) -> s.pid == process.pid && s.startTicks == process.startTicks }
    }.getOrNull()

    override fun read(process: GuestProcessInfo, threads: List<GameSchedulingThread>, current: () -> Boolean): List<GameSchedulingReading> {
        val begin = clock()
        if (!current() || trusted(process) == null) return emptyList()
        val io = numericLines(text(File(root, "${process.pid}/io"), 2048), IO_KEYS)
        val after = trusted(process) ?: return emptyList()
        val result = arrayListOf(GameSchedulingReading(process.pid, process.startTicks, 0, 0,
            begin, clock(), after.first.state, GameExecutionThread.Wait.UNKNOWN, after.second + io))
        for (thread in threads.take(GameSchedulingCapture.MAX_THREADS)) {
            if (!current()) break
            if (thread.pid != process.pid || thread.processStart != process.startTicks) continue
            val tBegin = clock()
            val directory = File(root, "${process.pid}/task/${thread.tid}")
            val first = stat(directory)?.first ?: continue
            if (first.pid != thread.tid || first.startTicks != thread.start) continue
            val sched = schedstat(text(File(directory, "schedstat"), 256))
            val switches = numericLines(text(File(directory, "status"), 16 * 1024), SWITCH_KEYS)
            val wait = GameExecutionThread.waitCategory(text(File(directory, "wchan"), 128))
            val last = stat(directory) ?: continue
            if (last.first.pid != thread.tid || last.first.startTicks != thread.start) continue
            result += GameSchedulingReading(process.pid, process.startTicks, thread.tid, thread.start,
                tBegin, clock(), last.first.state,
                if (first.state == last.first.state) wait else GameExecutionThread.Wait.UNKNOWN,
                last.second + sched + switches.mapKeys { if (it.key == "voluntary_ctxt_switches") "voluntary" else "involuntary" })
        }
        // A recycled/exited process cannot contribute even the earlier successful thread reads.
        return if (current() && trusted(process) != null) result else emptyList()
    }

    companion object {
        val IO_KEYS = setOf("read_bytes", "write_bytes", "rchar", "wchar", "syscr", "syscw")
        val SWITCH_KEYS = setOf("voluntary_ctxt_switches", "nonvoluntary_ctxt_switches")
        fun numericLines(text: String?, keys: Set<String>): Map<String, Long> {
            val result = linkedMapOf<String, Long>()
            val seen = hashSetOf<String>()
            text?.lineSequence()?.forEach { line ->
                val key = line.substringBefore(':').trim()
                if (key in keys) {
                    if (!seen.add(key)) result.remove(key)
                    else line.substringAfter(':', "").trim().toLongOrNull()?.takeIf { it >= 0 }?.let { result[key] = it }
                }
            }
            return result
        }
        fun schedstat(text: String?): Map<String, Long> {
            val fields = text?.trim()?.split(Regex("\\s+")) ?: return emptyMap()
            if (fields.size != 3) return emptyMap()
            val values = fields.map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return emptyMap() }
            // Kernels can expose an all-zero file with CONFIG_SCHEDSTATS disabled.
            if (values.all { it == 0L }) return emptyMap()
            return listOf("runtime_ns", "runqueue_ns", "slices").zip(values).toMap()
        }
        fun numericStat(text: String): Pair<GameExecutionStat, Map<String, Long>>? {
            val stat = GameExecutionStat.parse(text) ?: return null
            val fields = text.substring(text.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
            val counters = linkedMapOf("cpu_ticks" to stat.cpuTicks)
            for ((key, index) in listOf("minor" to 7, "major" to 9, "rss_pages" to 21))
                fields.getOrNull(index)?.toLongOrNull()?.takeIf { it >= 0 }?.let { counters[key] = it }
            return stat to counters
        }
    }
}

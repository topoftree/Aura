/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.io.File

/** Fixed numeric fields only. Missing counters remain unknown, including inaccessible PSI. */
object GamePressureCounters {
    val vmKeys = setOf("pgmajfault", "pgscan_kswapd", "pgscan_direct", "pgsteal_kswapd", "pgsteal_direct",
        "pswpin", "pswpout", "allocstall_dma", "allocstall_dma32", "allocstall_normal", "allocstall_movable",
        "compact_stall", "oom_kill")

    fun vm(text: String): Map<String, Long> = text.lineSequence().mapNotNull { line ->
        val pair = line.trim().split(Regex("\\s+"))
        if (pair.size != 2 || pair[0] !in vmKeys) null
        else pair[1].toLongOrNull()?.takeIf { it >= 0 }?.let { pair[0] to it }
    }.toMap()

    fun psi(text: String): Map<String, Long> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.firstOrNull() !in setOf("some", "full")) null
        else parts.singleOrNull { it.startsWith("total=") }?.substringAfter('=')?.toLongOrNull()
            ?.takeIf { it >= 0 }?.let { parts[0] to it }
    }.toMap()

    data class Faults(val pid: Int, val startTicks: Long, val minor: Long, val major: Long)
    fun faults(text: String): Faults? = runCatching {
        val open = text.indexOf('('); val close = text.lastIndexOf(')')
        require(open > 0 && close > open)
        val pid = text.substring(0, open).trim().toInt()
        val fields = text.substring(close + 1).trim().split(Regex("\\s+"))
        require(pid > 1 && fields.size >= 20 && fields[0].length == 1)
        Faults(pid, fields[19].toLong(), fields[7].toLong(), fields[9].toLong())
            .takeIf { it.startTicks > 0 && it.minor >= 0 && it.major >= 0 }
    }.getOrNull()

    fun difference(current: Map<String, Long>, previous: Map<String, Long>): Map<String, Long> =
        current.mapNotNull { (key, value) -> previous[key]?.takeIf { value >= it }?.let { key to value - it } }.toMap()

    fun format(values: Map<String, Long>): String = values.entries.sortedBy { it.key }
        .joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "UNKNOWN" }
}

/** Manual capture worker only; no new thread, process stop, PSS walk, or GPU query. */
class GamePressureSampler(context: Context, private val executable: String) {
    private val activity = runCatching { context.getSystemService(ActivityManager::class.java) }.getOrNull()
    private val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
    private val access = ProcGameExecutionAccess()
    private val previous = mutableMapOf<String, Map<String, Long>>()
    private var previousNs: Long? = null
    private val cpus = runCatching { File("/sys/devices/system/cpu").listFiles().orEmpty()
        .mapNotNull { dir -> Regex("cpu([0-9]+)").matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()?.let {
            it to File(dir, "cpufreq/scaling_cur_freq")
        } }.sortedBy { it.first }.take(16) }.getOrDefault(emptyList())

    private fun read(file: File, limit: Int): String? = runCatching {
        file.inputStream().use { input ->
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) null else bytes.toString(Charsets.US_ASCII)
        }
    }.getOrNull()

    fun sample(phase: String): String {
        require(phase.matches(Regex("(begin|end|batch_[1-6]|tail_[0-9]{1,2})")))
        val begin = System.nanoTime()
        val elapsed = SystemClock.elapsedRealtime()
        val utc = System.currentTimeMillis()
        val memory = runCatching { ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) } }
            .getOrNull()?.takeIf { it.totalMem > 0 }
        val thermal = runCatching { power?.currentThermalStatus }.getOrNull()?.takeIf { it in 0..6 }
        val frequencies = cpus.joinToString(",") { (id, file) ->
            "$id=${read(file, 64)?.trim()?.toLongOrNull()?.takeIf { it in 1..20_000_000 } ?: "UNKNOWN"}"
        }.ifEmpty { "UNKNOWN" }
        val values = linkedMapOf<String, Map<String, Long>>()
        values["SYSTEM_VM"] = read(File("/proc/vmstat"), 32768)?.let(GamePressureCounters::vm).orEmpty()
        for (kind in listOf("cpu", "memory", "io"))
            values["SYSTEM_PSI_${kind.uppercase()}"] = read(File("/proc/pressure/$kind"), 2048)
                ?.let(GamePressureCounters::psi).orEmpty()
        val scan = runCatching { access.session() }.getOrNull()
        var commandUnreadable = 0
        val matches = scan?.processes?.take(256)?.filter { p ->
            val command = access.command(p)
            if (command == null) commandUnreadable++
            command?.let { GameExecutionSampler.matchesExecutable(executable, it) } == true
        }.orEmpty()
        var readable = 0
        if (matches.size in 1..4) for (process in matches) {
            val file = File("/proc/${process.pid}/stat")
            val first = read(file, 4096)?.let(GamePressureCounters::faults)
            val second = read(file, 4096)?.let(GamePressureCounters::faults)
            if (first != null && second != null && first.pid == process.pid && second.pid == process.pid &&
                first.startTicks == process.startTicks && second.startTicks == process.startTicks &&
                second.minor >= first.minor && second.major >= first.major) {
                values["TARGET_FAULTS_${process.pid}_${process.startTicks}"] =
                    mapOf("minor" to second.minor, "major" to second.major)
                readable++
            }
        }
        val end = System.nanoTime()
        val interval = previousNs?.takeIf { begin >= it }?.let { (begin - it) / 1_000_000 }
        val result = buildString {
            appendLine("GAME_PRESSURE_V1 phase=$phase; monotonic_before_ns=$begin; monotonic_after_ns=$end; " +
                "elapsed_ms=$elapsed; utc_ms=$utc; interval_ms=${interval ?: "UNKNOWN"}; atomic=NO; " +
                "thermal_status=${thermal ?: "UNKNOWN"}; cpu_khz=[$frequencies]")
            appendLine("SYSTEM_MEMORY_V1 available_bytes=${memory?.availMem ?: "UNKNOWN"}; " +
                "total_bytes=${memory?.totalMem ?: "UNKNOWN"}; low=${memory?.lowMemory ?: "UNKNOWN"}; resident_gpu=UNMEASURED")
            appendLine("TARGET_FAULT_COVERAGE_V1 matches=${matches.size}; readable=$readable; " +
                "scan_unreadable=${scan?.unreadable ?: "UNKNOWN"}; command_unreadable=$commandUnreadable; " +
                "scan_limited=${scan?.processes?.let { it.size > 256 } ?: "UNKNOWN"}; process_limit=256; scope=SELECTED_EXE_PROC_STAT")
            for ((name, counters) in values) {
                val old = previous[name].orEmpty()
                val delta = if (interval != null) GamePressureCounters.difference(counters, old) else emptyMap()
                appendLine("$name totals=[${GamePressureCounters.format(counters)}]; delta=[${GamePressureCounters.format(delta)}]; " +
                    "state=${if (counters.isEmpty()) "UNAVAILABLE" else if (old.isEmpty()) "BASELINE" else if (delta.size != counters.size) "PARTIAL_OR_RESET" else "OBSERVED"}")
            }
        }
        previous.clear(); previous.putAll(values); previousNs = begin
        return result
    }
}

/** Fill the existing ten-second budget with passive observations, never additional PC stops. */
object GamePassiveCapture {
    const val WINDOW_NS = 10_000_000_000L
    fun run(startNs: Long, current: () -> Boolean, clock: () -> Long = System::nanoTime,
            wait: (Long) -> Unit = Thread::sleep, sample: (Int) -> Unit): String {
        var index = 0
        while (current()) {
            val elapsed = clock() - startNs
            if (elapsed < 0) return "CLOCK_CHANGED"
            if (elapsed >= WINDOW_NS) return "COMPLETE"
            wait(minOf(250, ((WINDOW_NS - elapsed) / 1_000_000).coerceAtLeast(1)))
            if (!current()) return "SESSION_CHANGED_OR_PAUSED"
            if (++index % 4 == 0 && clock() - startNs < WINDOW_NS) sample(index / 4)
            if (index >= 40) return if (clock() - startNs >= WINDOW_NS) "COMPLETE" else "CLOCK_STALLED_OR_BUDGET"
        }
        return "SESSION_CHANGED_OR_PAUSED"
    }
}

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** FEX 2610 Windows SHMStats v2 / Aura v3. TID is a Wine TID; ticks are CNTVCT, not CPU cycles. */
object FexDetailedStatistics {
    val names = listOf("jit_ticks", "signal_ticks", "sigbus_candidates", "smc_candidates", "float_fallbacks",
        "cache_misses", "cache_read_lock_ticks", "cache_write_lock_ticks", "jit_count", "disk_hits", "disk_misses", "disk_lookup_ticks")
    data class Thread(val slot: Int, val wineTid: Long, val counters: List<Long>, val serial: Long = 0, val audit: List<Long> = emptyList()) {
        val identity get() = Triple(slot, wineTid, serial)
    }
    data class Snapshot(val threads: List<Thread>, val partial: Boolean, val fileIdentity: Any? = null)
    private const val LIMIT = 1024 * 1024

    private fun parse(bytes: ByteArray): Snapshot? {
        if (bytes.size !in 64..LIMIT) return null
        val b = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        val version = b[0].toInt()
        val stride = if (version >= 3) FexJitProtocol.stride(version) else 112
        val header = if (version >= 3) 128 else 64
        if (version !in 2..4 || b[1].toInt() !in 2..3 || b.getShort(2).toInt() != stride ||
            version >= 3 && !FexJitProtocol.header(bytes)) return null
        val length = b.getInt(56)
        if (length !in header..bytes.size) return null
        var offset = b.getInt(52)
        val seen = hashSetOf<Int>()
        val threads = mutableListOf<Thread>()
        while (offset != 0) {
            if (offset < header || offset > length - stride || (offset - header) % stride != 0 || !seen.add(offset)) return null
            if (seen.size > 256) return Snapshot(threads, true)
            val tid = b.getInt(offset + 4).toLong() and 0xffffffffL
            if (tid != 0L) {
                val counters = names.indices.map { b.getLong(offset + 8 + 8 * it) }
                if (counters.any { it < 0 }) return null
                val serial = if (version >= 3) b.getLong(offset + 120) else 0
                if (version >= 3 && serial <= 0) return null
                val audit = if (version == 4) (0 until 8).map { b.getLong(offset + 1024 + it * 8) } else emptyList()
                if (version == 4 && !FexCpuAudit.valid(audit)) return null
                threads += Thread(offset, tid, counters, serial, audit)
            }
            offset = b.getInt(offset)
        }
        if (threads.map { it.wineTid }.distinct().size != threads.size) return null
        return Snapshot(threads, false)
    }

    /** Reject changed header/list identities; keep only monotonic surviving slots. Not an atomic snapshot. */
    fun stable(first: ByteArray, second: ByteArray): Snapshot? {
        val a = parse(first) ?: return null
        val b = parse(second) ?: return null
        if (!first.copyOfRange(0, 64).contentEquals(second.copyOfRange(0, 64))) return null
        val previous = a.threads.associateBy { it.identity }
        val retained = b.threads.filter { next -> previous[next.identity]?.let { old ->
            next.audit == old.audit && next.counters.indices.all { next.counters[it] >= old.counters[it] }
        } == true }
        return Snapshot(retained, a.partial || b.partial || retained.size != a.threads.size || retained.size != b.threads.size)
    }

    fun read(file: File, sinceMs: Long): Snapshot? = runCatching {
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.lastModifiedTime().toMillis() < sinceMs || before.size() !in 64..LIMIT.toLong()) return null
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            fun bytes(): ByteArray {
                check(channel.size() == before.size())
                val b = ByteBuffer.allocate(before.size().toInt())
                channel.position(0)
                while (b.hasRemaining()) check(channel.read(b) > 0)
                return b.array()
            }
            val value = stable(bytes(), bytes()) ?: return null
            val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (before.fileKey() == null || before.fileKey() != after.fileKey() || before.size() != after.size()) return null
            value.copy(fileIdentity = before.fileKey())
        }
    }.getOrNull()

    fun delta(before: Snapshot, after: Snapshot): Snapshot? {
        if (before.fileIdentity != after.fileIdentity) return null
        val old = before.threads.associateBy { it.identity }
        val rows = after.threads.mapNotNull { next ->
            val previous = old[next.identity] ?: return@mapNotNull null
            val values = next.counters.indices.map { next.counters[it] - previous.counters[it] }
            if (values.any { it < 0 }) null else next.copy(counters = values)
        }
        return Snapshot(rows, before.partial || after.partial || rows.size != before.threads.size || rows.size != after.threads.size)
    }

    fun totals(snapshot: Snapshot): List<Long>? = runCatching {
        names.indices.map { field -> snapshot.threads.fold(0L) { sum, row -> Math.addExact(sum, row.counters[field]) } }
    }.getOrNull()

    fun diagnostic(values: List<Long>, frequency: Long?): String = names.indices.joinToString("; ") { field ->
        val ticks = names[field].endsWith("_ticks")
        val converted = if (ticks && frequency != null && frequency > 0)
            "; ${names[field].removeSuffix("_ticks")}_ms=" + String.format(Locale.ROOT, "%.3f", values[field].toDouble() * 1000 / frequency) else ""
        "${names[field]}=${values[field]}$converted"
    }
}

/** Reads on the manual capture worker only; no additional sleeps, tracing or guest stop. */
class FexDiagnosticCapture(private val executable: String) {
    private data class Previous(val time: Long, val snapshot: FexDetailedStatistics.Snapshot)
    private var session: String? = null
    private var previous = emptyMap<Pair<Int, Long>, Previous>()
    private var activityEpoch: Long? = null
    private val frequency by lazy { GameNativePcProbe.counterFrequency() }

    fun sample(phase: String, activity: () -> GameWindowDiagnostics.Activity): String {
        val before = activity()
        if (activityEpoch != before.epoch || before.paused) previous = emptyMap()
        activityEpoch = before.epoch
        if (before.paused) return "FEX_DIAGNOSTIC_V2 phase=$phase; state=PAUSED\n"
        val result = sampleCurrent(phase)
        if (activity() != before) {
            previous = emptyMap()
            return "FEX_DIAGNOSTIC_V2 phase=$phase; state=TRANSITION\n"
        }
        return result
    }

    private fun sampleCurrent(phase: String): String {
        val source = GuestProcessSession.fexDiagnosticFiles()
            ?: return "FEX_DIAGNOSTIC_V2 phase=$phase; state=NOT_CONFIGURED\n"
        if (session != source.session) previous = emptyMap()
        session = source.session
        if (!source.enabled) return "FEX_DIAGNOSTIC_V2 phase=$phase; state=OFF; action=ENABLE_TRANSLATION_STATISTICS_AND_RELAUNCH\n"
        val access = ProcGameExecutionAccess()
        val scan = access.session() ?: return "FEX_DIAGNOSTIC_V2 phase=$phase; state=SESSION_UNAVAILABLE\n"
        var unknown = scan.unreadable
        val matches = scan.processes.take(GameExecutionSampler.MAX_PROCESSES).filter {
            val command = access.command(it)
            if (command == null) unknown++
            command != null && GameExecutionSampler.matchesExecutable(executable, command)
        }
        val now = SystemClock.elapsedRealtime()
        val current = linkedMapOf<Pair<Int, Long>, Previous>()
        val rows = matches.take(4).map { process ->
            val snapshot = FexDetailedStatistics.read(File(source.directory, "fex-${process.pid}-stats"), source.sinceMs)
            val prefix = "FEX_PROCESS_V2 pid=${process.pid}; start_ticks=${process.startTicks}; owner=TARGET_EXE"
            if (snapshot == null) "$prefix; state=UNAVAILABLE\n" else {
                val old = previous[process.identity]
                current[process.identity] = Previous(now, snapshot)
                val delta = old?.let { FexDetailedStatistics.delta(it.snapshot, snapshot) }
                val totals = delta?.let(FexDetailedStatistics::totals)
                buildString {
                    append(prefix).append("; state=").append(when {
                        old == null || delta == null -> "WARMUP"
                        totals == null -> "OVERFLOW"
                        delta.partial -> "PARTIAL"
                        else -> "OBSERVED"
                    }).append("; live_wine_threads=${snapshot.threads.size}; compared=${delta?.threads?.size ?: 0}")
                    append("; interval_ms=${old?.let { now - it.time } ?: "?"}\n")
                    val audits = snapshot.threads.map { it.audit }.distinct()
                    append(FexCpuAudit.report(audits.singleOrNull().orEmpty()))
                    if (totals != null) {
                        append("FEX_INTERVAL_V2 pid=${process.pid}; ").append(FexDetailedStatistics.diagnostic(totals, frequency)).append('\n')
                        delta.threads.sortedByDescending { it.counters[0].toDouble() + it.counters[1] }
                            .take(6).forEach { row ->
                                append("FEX_THREAD_INTERVAL_V2 pid=${process.pid}; wine_tid=${row.wineTid}; slot=${row.slot}; ")
                                append("jit=${row.counters[8]}; jit_ticks=${row.counters[0]}; signal_ticks=${row.counters[1]}; ")
                                append("smc_candidates=${row.counters[3]}; read_lock_ticks=${row.counters[6]}; write_lock_ticks=${row.counters[7]}\n")
                            }
                    }
                }
            }
        }
        val still = access.session()?.processes?.map { it.identity }?.toSet().orEmpty()
        if (GuestProcessSession.fexDiagnosticFiles()?.session != source.session || current.keys.any { it !in still }) {
            previous = emptyMap()
            return "FEX_DIAGNOSTIC_V2 phase=$phase; state=IDENTITY_CHANGED\n"
        }
        previous = current
        return "FEX_DIAGNOSTIC_V2 phase=$phase; elapsed_ms=$now; matched=${matches.size}; unreadable=$unknown; " +
            "limited=${scan.processes.size > GameExecutionSampler.MAX_PROCESSES || matches.size > 4}; counter_hz=${frequency ?: "?"}; " +
            "clock=CNTVCT_ELAPSED; cpu_time=UNMEASURED; timing=INCLUSIVE_OVERLAPPING; smc=EXCEPTION_CANDIDATES_NOT_CONFIRMED; " +
            "thread_identity=WINE_TID_SLOT_AND_V3_SERIAL; v2_reuse_between_reads=UNDETECTABLE\n" + rows.joinToString("")
    }
}

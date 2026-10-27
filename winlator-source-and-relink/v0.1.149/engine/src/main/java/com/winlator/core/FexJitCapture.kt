/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Aura's versioned SHM extension. Only numeric aggregates leave the guest. */
object FexJitProtocol {
    const val MAGIC = 0x3154494a41525541L
    const val HEADER = 128
    const val STRIDE = 1280
    fun stride(version: Int) = if (version == 4) STRIDE else 1024
    const val LIMIT = 1024 * 1024
    val fields = listOf("slot", "serial", "sequence", "epoch", "operations", "flags", "compile_attempts",
        "repeat_attempts", "repeat_after_generation_change", "repeat_unknown", "compile_ticks", "max_compile_ticks",
        "inline_validation_exits", "range_invalidation_requests", "capacity_allocation_failures", "buffer_creations",
        "buffer_adoptions", "clear_requests", "capacity_bytes", "used_bytes", "max_failed_request_bytes", "peak_used_bytes",
        "first_tick", "last_tick", "start_tick", "deadline_tick", "event_count", "contention_drops_lifetime",
        "previous_capacity_bytes", "previous_used_bytes", "generation", "compile_races_or_failures")
    val events = listOf("UNKNOWN", "CAPACITY_ALLOCATION_FAILED", "BUFFER_CREATED", "BUFFER_ADOPTED",
        "CLEAR_REQUESTED", "INLINE_VALIDATION_EXIT", "RANGE_INVALIDATED", "SLOW_COMPILE")
    val cacheFields = listOf("disabled", "anonymous_disabled", "decode_rejected", "range_rejected", "key_missing",
        "no_matching_content", "read_failed", "blob_rejected", "relocation_rejected", "ram_blob_hit", "storage_blob_hit",
        "code_loaded", "load_allocation_failed", "validation_only", "ram_bytes_last", "ram_bytes_peak_observed",
        "pending_write_blob_hit", "resident_buffer_hit")
    data class Row(val tid: Long, val values: List<Long>, val bins: List<List<Long>>, val events: List<List<Long>>,
                   val cache: List<Long> = emptyList())
    data class Snapshot(val rows: List<Row>, val partial: Boolean)

    fun header(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER) return false
        val b = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        val version = b[0].toInt()
        return version in 3..4 && b[1].toInt() in 2..3 && b.getShort(2).toInt() == stride(version) &&
            b.getLong(64) == MAGIC && b.getLong(96) in 1..10_000_000_000L && b.getLong(104) == (if (version == 4) 3L else 1L) &&
            b.getInt(56) in HEADER..LIMIT
    }

    /** Per-slot seqlock plus incarnation; changed or torn slots are excluded, never treated as zero. */
    fun stable(first: ByteArray, second: ByteArray, epoch: Long): Snapshot? {
        if (!header(first) || !header(second) || first.size > LIMIT || second.size > LIMIT) return null
        val a = ByteBuffer.wrap(first).order(LITTLE_ENDIAN)
        val b = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        if (a[0] != b[0] || a.getShort(2) != b.getShort(2)) return null
        val stride = stride(b[0].toInt())
        if (a.getLong(72) != epoch || b.getLong(72) != epoch || a.getLong(96) != b.getLong(96)) return null
        val length = b.getInt(56)
        if (length > second.size || a.getInt(56) > first.size) return null
        var offset = b.getInt(52)
        val seen = hashSetOf<Int>()
        val tids = hashSetOf<Long>()
        val rows = mutableListOf<Row>()
        var partial = a.getInt(52) != offset || a.getInt(56) != length
        while (offset != 0) {
            if (offset < HEADER || offset > length - stride || (offset - HEADER) % stride != 0 || !seen.add(offset)) return null
            if (seen.size > 256) return Snapshot(rows, true)
            val at = offset + 112
            val tid = b.getInt(offset + 4).toLong() and 0xffffffffL
            if (tid != 0L && !tids.add(tid)) return null
            val values = fields.indices.map { b.getLong(at + 8 * it) }
            val stable = offset <= first.size - stride && tid != 0L && a.getInt(offset + 4) == b.getInt(offset + 4) &&
                values[0] == offset.toLong() && values[1] > 0 && values[2] > 0 && values[2] % 2 == 0L &&
                first.copyOfRange(at, offset + stride).contentEquals(second.copyOfRange(at, offset + stride))
            if (values[3] == epoch) {
                if (!stable || values.any { it < 0 } || values[4] > 50000 || values[7] > values[6] ||
                    values[8] > values[7] || values[9] > values[6] || values[26] > 50000 || values[25] <= values[24] ||
                    values[25] - values[24] > b.getLong(96) * 10 ||
                    values[22] !in values[24] until values[25] ||
                    values[23] !in values[22] until values[25]) partial = true
                else {
                    val bins = (0 until 20).map { i ->
                        val p = at + 256 + i * 16
                        listOf(b.getInt(p).toLong() and 0xffffffffL, b.getInt(p + 4).toLong() and 0xffffffffL, b.getLong(p + 8))
                    }
                    val count = values[26]
                    val events = (maxOf(0, count - 8) until count).map { i ->
                        (0 until 5).map { j -> b.getLong(at + 576 + (i % 8).toInt() * 40 + j * 8) }
                    }
                    val cache = if (b[0].toInt() == 4) cacheFields.indices.map { b.getLong(offset + 1088 + it * 8) } else emptyList()
                    if (cache.any { it < 0 } || cache.filterIndexed { i, _ -> i != 14 && i != 15 }.any { it > values[4] } ||
                        cache.isNotEmpty() && (cache[14] > cache[15] || cache[16] + cache[17] != cache[9]) ||
                        bins.any { it[0] > 50000 || it[1] > it[0] || it[2] < 0 } || events.any {
                            it.any { n -> n < 0 } || it[0] !in values[24] until values[25] || it[1] !in 1..7
                        }) partial = true
                    else {
                        rows += Row(tid, values, bins, events, cache)
                        if (values[5] and 6L != 0L) partial = true // budget or incomplete repeat tracking
                    }
                }
            } else if (!stable && values[3] != 0L) partial = true
            offset = b.getInt(offset)
        }
        return Snapshot(rows, partial)
    }

    fun read(channel: FileChannel): ByteArray {
        val size = channel.size()
        require(size in HEADER..LIMIT.toLong())
        val bytes = ByteBuffer.allocate(size.toInt())
        while (bytes.hasRemaining()) check(channel.read(bytes, bytes.position().toLong()) > 0)
        return bytes.array()
    }

    fun writeLong(channel: FileChannel, offset: Long, value: Long) {
        val bytes = ByteBuffer.allocate(8).order(LITTLE_ENDIAN).putLong(value).apply { flip() }
        // The protocol uses aligned 64-bit control words, disarm before changing epoch/start.
        check(channel.write(bytes, offset) == 8)
    }
}

/** A pinned descriptor belongs to one process incarnation and one manual capture only. */
class FexJitLease private constructor(
    private val channel: FileChannel, val pid: Int, val startTicks: Long, private val epoch: Long,
    private val frequency: Long, private val start: Long, private val hostBefore: Long, private val hostAfter: Long,
) : AutoCloseable {
    private var closed = false
    private var report: String? = null
    @Synchronized fun finish(): String {
        report?.let { return it }
        if (closed) return "FEX_JIT_V1 pid=$pid; state=CLOSED\n"
        val result = runCatching {
            val current = FexJitProtocol.read(channel)
            check(ByteBuffer.wrap(current).order(LITTLE_ENDIAN).getLong(72) == epoch)
            FexJitProtocol.writeLong(channel, 88, 0)
            val snapshot = FexJitProtocol.stable(FexJitProtocol.read(channel), FexJitProtocol.read(channel), epoch)
            buildString {
                append("FEX_JIT_V1 pid=$pid; process_start=$startTicks; state=")
                append(if (snapshot == null) "UNSTABLE" else if (snapshot.partial) "PARTIAL" else if (snapshot.rows.isEmpty()) "NOT_OBSERVED" else "OBSERVED")
                append("; counter_hz=$frequency; anchor_tick=$start; anchor_host_ns_before=$hostBefore; anchor_host_ns_after=$hostAfter")
                append("; window_seconds=10; thread_limit=256; coverage=LIVE_SURVIVING_SLOTS; repeat_scope=SAME_THREAD_ENTRY_AND_MAXINST_IN_WINDOW")
                append("; timing=ELAPSED_INCLUSIVE_NOT_CPU; count_scope=COMPLETED_WITHIN_WINDOW; pending=UNMEASURED; terminated_threads=NOT_RETAINED\n")
                if (snapshot != null) {
                    val fields = listOf(6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 31)
                    append("FEX_JIT_TOTAL_V1; threads=${snapshot.rows.size}")
                    for (i in fields) {
                        val sum = runCatching { snapshot.rows.fold(0L) { a, row -> Math.addExact(a, row.values[i]) } }.getOrNull()
                        append("; ${FexJitProtocol.fields[i]}=${sum ?: "OVERFLOW"}")
                    }
                    append('\n')
                    if (snapshot.rows.any { it.cache.isNotEmpty() }) {
                        append("FEX_CACHE_LOOKUP_V1; scope=BOUNDED_MANUAL_WINDOW; storage=FILE_API_OR_MAPPING_NOT_PROVEN_PHYSICAL_IO")
                        for (i in FexJitProtocol.cacheFields.indices.filter { it != 14 && it != 15 }) {
                            val sum = snapshot.rows.sumOf { it.cache.getOrElse(i) { 0L } }
                            append("; ${FexJitProtocol.cacheFields[i]}=$sum")
                        }
                        append('\n')
                    }
                    val retained = snapshot.rows.sortedByDescending { it.values[10] }.take(24)
                    append("FEX_JIT_DETAIL_V1 retained=${retained.size}; omitted=${snapshot.rows.size - retained.size}\n")
                    for (row in retained) {
                        append("FEX_JIT_THREAD_V1 wine_tid=${row.tid}")
                        row.values.forEachIndexed { i, n -> if (i !in listOf(2, 3)) append("; ${FexJitProtocol.fields[i]}=$n") }
                        append("; event_wrap=${row.values[26] > 8}\n")
                        if (row.cache.isNotEmpty()) append("FEX_CACHE_RAM_V1 wine_tid=${row.tid}; last_bytes=${row.cache[14]}; peak_observed_bytes=${row.cache[15]}; scope=PROCESS_GAUGE_DO_NOT_SUM\n")
                        row.bins.forEachIndexed { i, bin -> if (bin[0] > 0) append(
                            "FEX_JIT_BIN_V1 wine_tid=${row.tid}; from_ms=${i * 500}; end_ms=${(i + 1) * 500}; attempts=${bin[0]}; repeats=${bin[1]}; ticks=${bin[2]}\n") }
                        row.events.forEach { e -> append("FEX_JIT_EVENT_V1 wine_tid=${row.tid}; tick=${e[0]}; type=${FexJitProtocol.events[e[1].toInt()]}; a=${e[2]}; b=${e[3]}; c=${e[4]}\n") }
                    }
                }
            }
        }.getOrElse { "FEX_JIT_V1 pid=$pid; state=UNAVAILABLE\n" }
        close()
        report = result
        return result
    }

    @Synchronized override fun close() {
        if (closed) return
        runCatching {
            val bytes = FexJitProtocol.read(channel)
            if (ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).getLong(72) == epoch) FexJitProtocol.writeLong(channel, 88, 0)
        }
        runCatching { channel.close() }; closed = true
    }

    companion object {
        fun open(file: File, sinceMs: Long, process: GuestProcessInfo, stillCurrent: () -> Boolean,
                 frequency: Long, counter: () -> Long?, clock: () -> Long = System::nanoTime): FexJitLease? {
            var channel: FileChannel? = null
            var armedEpoch: Long? = null
            try {
                val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!before.isRegularFile || before.fileKey() == null || before.lastModifiedTime().toMillis() < sinceMs) return null
                val opened = FileChannel.open(file.toPath(), READ, WRITE, NOFOLLOW_LINKS); channel = opened
                val bytes = FexJitProtocol.read(opened)
                if (!FexJitProtocol.header(bytes) || ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).getLong(96) != frequency) return null
                val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (after.fileKey() != before.fileKey() || !stillCurrent()) return null
                val epoch = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
                val hostBefore = clock(); val start = counter()?.takeIf { it > 0 } ?: return null; val hostAfter = clock()
                val end = Math.addExact(start, Math.multiplyExact(frequency, 10))
                FexJitProtocol.writeLong(opened, 88, 0)
                FexJitProtocol.writeLong(opened, 72, epoch); armedEpoch = epoch
                FexJitProtocol.writeLong(opened, 80, start)
                FexJitProtocol.writeLong(opened, 88, end)
                if (!stillCurrent()) return null
                return FexJitLease(opened, process.pid, process.startTicks, epoch, frequency, start, hostBefore, hostAfter).also { channel = null }
            } catch (_: Exception) { return null }
            finally {
                channel?.let { opened ->
                    if (armedEpoch != null) runCatching {
                        val bytes = FexJitProtocol.read(opened)
                        if (ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).getLong(72) == armedEpoch)
                            FexJitProtocol.writeLong(opened, 88, 0)
                    }
                    runCatching { opened.close() }
                }
            }
        }
    }
}

/** No background loop. Stop hooks disarm even when the capture worker is paused or cancelled. */
object FexJitCapture {
    class Capture internal constructor(internal val id: String, internal val leases: List<FexJitLease>, private val state: String) {
        fun report(): String {
            val text = "FEX_JIT_CAPTURE_V1 start_state=$state; processes=${leases.size}\n" + leases.joinToString("") { it.finish() }
            if (text.length <= 60 * 1024) return text
            return text.take(60 * 1024).substringBeforeLast('\n') + "\nFEX_JIT_CAPTURE_V1 state=OUTPUT_LIMIT\n"
        }
    }
    private var current: Capture? = null
    private var generation = 0L
    fun start(id: String, executable: String, allowed: () -> Boolean): Capture {
        val ticket = synchronized(this) { stop(); generation }
        val source = GuestProcessSession.fexDiagnosticFiles()
        val access = ProcGameExecutionAccess()
        val scan = access.session()
        val frequency = GameNativePcProbe.counterFrequency()
        var state = "NOT_CONFIGURED"
        val leases = mutableListOf<FexJitLease>()
        if (source != null && source.enabled && scan != null && frequency != null && allowed()) {
            state = "INCOMPLETE_SCAN"
            if (scan.unreadable == 0 && scan.processes.size <= GameExecutionSampler.MAX_PROCESSES) {
                val commands = scan.processes.map { it to access.command(it) }
                val matches = commands.filter { it.second?.let { command -> GameExecutionSampler.matchesExecutable(executable, command) } == true }
                if (commands.all { it.second != null } && matches.size in 1..4) {
                    for ((process, _) in matches) {
                        fun valid() = synchronized(this) { generation == ticket } && allowed() && GuestProcessSession.fexDiagnosticFiles()?.session == source.session &&
                            access.session()?.processes?.any { it.identity == process.identity } == true
                        FexJitLease.open(File(source.directory, "fex-${process.pid}-stats"), source.sinceMs,
                            process, ::valid, frequency, GameNativePcProbe::counterValue)?.let(leases::add)
                    }
                    state = if (leases.size == matches.size) "ARMED" else "PARTIAL_OR_UNSUPPORTED"
                }
            }
        } else if (source != null && !source.enabled) state = "STATISTICS_OFF_RELAUNCH_REQUIRED"
        return Capture(id, leases, state).also { capture -> synchronized(this) {
            if (generation == ticket) current = capture else capture.leases.forEach { it.finish() }
        } }
    }
    @Synchronized fun timeline(capture: Capture, owner: Any): JitPresentCapture? =
        if (current === capture) JitPresentTimeline.start(capture.id, owner) else null

    @Synchronized fun stop(id: String? = null) {
        if (id != null && current?.id != id) return
        generation++
        current?.takeIf { id == null || it.id == id }?.let { capture ->
            capture.leases.forEach { it.finish() }; current = null
        }
        JitPresentTimeline.stop(id)
    }
}

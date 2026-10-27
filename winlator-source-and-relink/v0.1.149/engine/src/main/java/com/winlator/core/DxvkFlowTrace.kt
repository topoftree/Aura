/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.Activity
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale
import java.util.UUID

/** Matches the fixed shared POD structure in the rebuilt D3D9 DLL, not arbitrary guest data. */
object DxvkFlowProtocol {
    const val BYTES = 36864
    val reasons = listOf("PRESENT", "FLUSH", "FLUSH_WEAK", "FLUSH_STRONG", "FLUSH_SYNC", "FLUSH_EXPLICIT",
        "WAIT_MAP_READ", "WAIT_MAP_WRITE", "MAP_BUSY_NO_WAIT", "COMMAND_THREAD", "QUERY_GET_DATA", "QUERY_FLUSH",
        "STAGING_WAIT", "QUEUE_SUBMIT", "GPU_COMPLETION", "SHADER_MODULE", "PIPELINE_CREATE")
    fun empty(): ByteArray = ByteArray(BYTES).also {
        "AUDFLOW1".toByteArray(Charsets.US_ASCII).copyInto(it)
        ByteBuffer.wrap(it).order(LITTLE_ENDIAN).putInt(8, 1).putInt(12, BYTES)
    }
    fun valid(bytes: ByteArray): Boolean = bytes.size == BYTES &&
        bytes.copyOfRange(0, 8).contentEquals("AUDFLOW1".toByteArray(Charsets.US_ASCII)) &&
        ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).let { it.getInt(8) == 1 && it.getInt(12) == BYTES }

    fun report(first: ByteArray, second: ByteArray, epoch: Int): String {
        require(valid(first) && valid(second) && epoch > 0)
        val a = ByteBuffer.wrap(first).order(LITTLE_ENDIAN)
        val b = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        if (a.getInt(16) != 0 || b.getInt(16) != 0) return "DXVK_FLOW_V1 state=STILL_ACTIVE; completion=UNCONFIRMED\n"
        if (b.getInt(20) != epoch || a.getInt(20) != epoch) return "DXVK_FLOW_V1 state=EPOCH_CHANGED\n"
        if (b.getInt(24) != epoch || a.getInt(24) != epoch) return "DXVK_FLOW_V1 state=NO_NATIVE_EVENTS; loaded=UNCONFIRMED\n"
        require(first.copyOfRange(40, 80).contentEquals(second.copyOfRange(40, 80)))
        val frequency = b.getLong(40)
        val start = b.getLong(48)
        val anchorEnd = b.getLong(56)
        val deadline = b.getLong(72)
        require(frequency in 1..1_000_000_000 && start > 0 && anchorEnd >= start && anchorEnd - start <= frequency)
        require(deadline > start && deadline - start == frequency * 10)
        data class Row(val tid: Long, val reason: Int, val calls: Long, val ticks: Long, val max: Long,
                       val first: Long, val last: Long, val saturated: Long, val bins: List<Pair<Long, Long>>)
        var unstable = 0
        val rows = mutableListOf<Row>()
        for (i in 0 until 128) {
            val at = 256 + i * 256
            if (b.getLong(at) == 0L || b.getInt(at + 12) != epoch) continue
            val sequence = b.getInt(at + 8)
            if (sequence and 1 != 0 || sequence != a.getInt(at + 8) ||
                !first.copyOfRange(at, at + 256).contentEquals(second.copyOfRange(at, at + 256))) { unstable++; continue }
            val key = b.getLong(at)
            val reason = (key and 0xffffffffL).toInt()
            val tid = key ushr 32
            val calls = b.getLong(at + 16); val ticks = b.getLong(at + 24); val max = b.getLong(at + 32)
            val firstTick = b.getLong(at + 40); val lastTick = b.getLong(at + 48); val saturated = b.getLong(at + 56)
            val bins = (0 until 20).map { j ->
                (b.getInt(at + 64 + j * 8).toLong() and 0xffffffffL) to
                    (b.getInt(at + 68 + j * 8).toLong() and 0xffffffffL)
            }
            if (tid == 0L || reason !in 1..reasons.size || calls !in 1..50000 || ticks < 0 || max !in 0..ticks ||
                firstTick !in start until deadline || lastTick !in firstTick until deadline ||
                max > deadline - start || ticks > calls * (deadline - start) ||
                saturated !in 0..calls || bins.sumOf { it.first } != calls) { unstable++; continue }
            rows += Row(tid, reason, calls, ticks, max, firstTick, lastTick, saturated, bins)
        }
        fun ms(ticks: Long) = String.format(Locale.ROOT, "%.3f", ticks * 1000.0 / frequency)
        val drops = b.getInt(36).toLong() and 0xffffffffL
        val budget = b.getInt(84).toLong() and 0xffffffffL
        val crossing = b.getInt(88).toLong() and 0xffffffffL
        val operations = b.getInt(80).toLong() and 0xffffffffL
        val selected = rows.sortedByDescending { it.ticks }.take(24)
        return buildString {
            appendLine("DXVK_FLOW_V1 state=${if (unstable > 0 || drops > 0 || budget > 0 || crossing > 0 || operations >= 50000 || rows.any { it.saturated > 0 }) "PARTIAL" else "OBSERVED"}; " +
                "wine_pid=${b.getInt(32).toLong() and 0xffffffffL}; target=BASENAME_GATE; clock=WINE_QPC; " +
                "frequency=$frequency; anchor_before=$start; anchor_after=$anchorEnd; utc_100ns=${b.getLong(64)}; " +
                "clock_alignment=UTC_BRACKET_NOT_EXACT; row_drops=$drops; contention_or_budget_drops=$budget; " +
                "crossing_or_clock_drops=$crossing; operation_budget_used=$operations; operation_limit=50000; unstable=$unstable; retained=${selected.size}; omitted=${rows.size - selected.size}; " +
                "timing=INCLUSIVE_OVERLAPPING_NOT_GPU; pending=UNMEASURED")
            for ((reason, group) in rows.groupBy { it.reason }.toSortedMap())
                appendLine("DXVK_FLOW_TOTAL_V1 reason=${reasons[reason - 1]}; calls=${group.sumOf { it.calls }}; elapsed_ms=${ms(group.sumOf { it.ticks })}")
            for (row in selected) {
                appendLine("DXVK_FLOW_ROW_V1 wine_tid=${row.tid}; reason=${reasons[row.reason - 1]}; calls=${row.calls}; " +
                    "elapsed_ms=${ms(row.ticks)}; max_ms=${ms(row.max)}; first_tick=${row.first}; last_tick=${row.last}; saturated_bins=${row.saturated}")
                row.bins.forEachIndexed { index, (calls, us) -> if (calls > 0)
                    appendLine("DXVK_FLOW_BIN_V1 wine_tid=${row.tid}; reason=${reasons[row.reason - 1]}; from_ms=${index * 500}; calls=$calls; inclusive_us=$us") }
            }
        }.let { if (it.length <= 60 * 1024) it else it.take(60 * 1024).substringBeforeLast('\n') + "\nDXVK_FLOW_V1 state=OUTPUT_LIMIT\n" }
    }
}

/** Manual control stays on a pinned session inode; later sessions cannot arm an older mapping. */
object DxvkFlowTrace {
    const val PATH = "AURA_DXVK_FLOW_PATH"
    const val TARGET = "AURA_DXVK_FLOW_TARGET"
    private data class Session(val id: String, val file: File, val channel: FileChannel, var epoch: Int = 0,
                               var started: Long = 0, var stopped: Long = 0)
    private var current: Session? = null
    fun silence(env: EnvVars) { DxvkColorTrace.silence(env); env.remove(PATH); env.remove(TARGET) }
    fun configure(context: Context, env: EnvVars) {
        silence(env)
        val activity = context as? Activity ?: return
        if (!RuntimeDiagnostics.sessionEnabled(context)) return
        val id = activity.intent.getStringExtra("aura_launch_id")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return
        val target = activity.intent.getStringExtra("exec_path")?.replace('\\', '/')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it.length < 512 && it.endsWith(".exe", true) } ?: return
        val prefix = env.get("WINEPREFIX")?.takeIf { it.startsWith('/') } ?: return
        val result = runCatching { prepare(id, prefix) }
        if (result.isSuccess) env.put(PATH, "C:\\aura-flow-diagnostics\\$id.bin").put(TARGET, target)
        RuntimeLaunchDiagnostics.detail(context, "DXVK_FLOW_CONFIG_V1 state=${if (result.isSuccess) "PREPARED" else "UNAVAILABLE"}; " +
            "scope=D3D9; activation=MANUAL_CAPTURE; limit_ms=10000; gpu_execution=UNMEASURED")
    }
    // Do not call the launch-report monitor while holding this monitor: stop can arrive in reverse order.
    @Synchronized private fun prepare(id: String, prefix: String) {
        current?.takeIf { it.id == id && it.channel.isOpen }?.let { return }
        discard()
        var opened: FileChannel? = null
        var created: File? = null
        try {
            val drive = File(prefix, "drive_c").canonicalFile
            check(drive.isDirectory)
            val directory = File(drive, "aura-flow-diagnostics")
            check(!Files.isSymbolicLink(directory.toPath()) && (directory.isDirectory || directory.mkdir()))
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                entries.iterator().asSequence().take(256).filter {
                    it.fileName.toString().matches(Regex("[a-f0-9-]{36}\\.bin")) && Files.isRegularFile(it, NOFOLLOW_LINKS)
                }.forEach { old ->
                    val owned = runCatching {
                        FileChannel.open(old, READ, NOFOLLOW_LINKS).use { channel ->
                            check(channel.size() == DxvkFlowProtocol.BYTES.toLong())
                            val bytes = ByteBuffer.allocate(DxvkFlowProtocol.BYTES)
                            while (bytes.hasRemaining()) check(channel.read(bytes) > 0)
                            DxvkFlowProtocol.valid(bytes.array())
                        }
                    }.getOrDefault(false)
                    if (owned) Files.deleteIfExists(old)
                }
            }
            val file = File(directory, "$id.bin")
            check(file.createNewFile()); created = file
            file.writeBytes(DxvkFlowProtocol.empty())
            val channel = FileChannel.open(file.toPath(), READ, WRITE, NOFOLLOW_LINKS); opened = channel
            current = Session(id, file, channel); opened = null; created = null
        } finally {
            runCatching { opened?.close() }
            runCatching { created?.delete() }
        }
    }
    private fun write(s: Session, offset: Long, value: Int) {
        val buffer = ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(value).apply { flip() }
        check(s.channel.write(buffer, offset) == 4)
    }
    @Synchronized fun start(id: String) {
        DxvkColorTrace.start(id)
        val s = current?.takeIf { it.id == id } ?: return
        runCatching {
            write(s, 16, 0)
            s.epoch = when (s.epoch) {
                Int.MAX_VALUE -> 1
                0 -> ((UUID.randomUUID().mostSignificantBits and 0x7fffffff).toInt()).coerceAtLeast(1)
                else -> s.epoch + 1
            }
            write(s, 20, s.epoch)
            s.started = System.nanoTime(); s.stopped = 0
            write(s, 16, 1)
        }.onFailure { stop(id); s.epoch = 0 }
    }
    @Synchronized fun stop(id: String? = null) {
        DxvkColorTrace.stop(id)
        current?.takeIf { id == null || it.id == id }?.let { s ->
            runCatching { write(s, 16, 0) }
            if (s.stopped == 0L) s.stopped = System.nanoTime()
        }
    }
    @Synchronized fun report(id: String): String {
        val s = current?.takeIf { it.id == id && it.epoch > 0 } ?: return "DXVK_FLOW_V1 state=NOT_CONFIGURED\n"
        return runCatching {
            fun read(): ByteArray {
                check(s.channel.size() == DxvkFlowProtocol.BYTES.toLong())
                val b = ByteBuffer.allocate(DxvkFlowProtocol.BYTES)
                while (b.hasRemaining()) check(s.channel.read(b, b.position().toLong()) > 0)
                return b.array()
            }
            "DXVK_FLOW_WINDOW_V1 host_start_ns=${s.started}; host_stop_ns=${s.stopped}; clock=HOST_MONOTONIC_NS\n" +
                DxvkFlowProtocol.report(read(), read(), s.epoch)
        }.getOrDefault("DXVK_FLOW_V1 state=UNREADABLE_OR_UNSTABLE\n")
    }
    @Synchronized fun discard() {
        DxvkColorTrace.discard()
        stop()
        current?.let { s -> runCatching { s.channel.close() }; runCatching { Files.deleteIfExists(s.file.toPath()) } }
        current = null
    }
}

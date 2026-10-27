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

/** Debug installs an inert observer; only an explicit manual capture enables its bounded window. */
object NativeCodeTrace {
    const val LIBRARY = "libaura_code_trace.so"
    const val VARIABLE = "AURA_CODE_TRACE"
    const val DIRECTORY = "AURA_CODE_DIRECTORY"
    private val uuid = Regex("[a-f0-9-]{36}")
    private val names = (1..16).map { "code-${it.toString().padStart(2, '0')}.bin" }
    private data class Session(val id: String, val directory: File, val executable: String, var epoch: Long = 0)
    private var session: Session? = null

    fun silence(env: EnvVars) {
        env.put(VARIABLE, "0"); env.remove(DIRECTORY)
        val kept = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != LIBRARY }.joinToString(" ")
        if (kept.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", kept)
    }

    fun configure(context: Context, env: EnvVars) {
        val disabled = env.get(VARIABLE) == "0"
        silence(env)
        val intent = (context as? Activity)?.intent ?: return
        val id = intent.getStringExtra("aura_launch_id")?.takeIf(uuid::matches) ?: return
        if (disabled || !RuntimeDiagnostics.sessionEnabled(context)) return
        val library = File(context.applicationInfo.nativeLibraryDir, LIBRARY)
        if (!library.isFile) {
            RuntimeLaunchDiagnostics.detail(context, "CODE_PROTECTION_V1 state=LIBRARY_MISSING"); return
        }
        val directory = runCatching { prepare(context, id, intent.getStringExtra("exec_path").orEmpty()) }.getOrNull()
        if (directory == null) {
            RuntimeLaunchDiagnostics.detail(context, "CODE_PROTECTION_V1 state=STORAGE_UNAVAILABLE"); return
        }
        val preload = env.get("LD_PRELOAD").orEmpty().trim()
        env.put("LD_PRELOAD", (listOf(preload).filter { it.isNotBlank() } + library.path).joinToString(" "))
            .put(VARIABLE, "1").put(DIRECTORY, directory.path)
        RuntimeLaunchDiagnostics.detail(context, "CODE_PROTECTION_V1 state=ARMED; collection=MANUAL_ONLY; window_ms=8000; " +
            "scope=VERIFIED_WINE_NTPROTECT; smc_cause=UNCONFIRMED; signal_handlers=UNCHANGED")
    }

    @Synchronized private fun prepare(context: Context, id: String, executable: String): File {
        session?.takeIf { it.id == id }?.let { return it.directory }
        discard()
        val root = File(context.filesDir, "diagnostics/code-protection")
        check(root.isDirectory || root.mkdirs()); check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        root.listFiles().orEmpty().filter { uuid.matches(it.name) }.forEach(::retire)
        val directory = File(root, id); check(directory.mkdir())
        File(directory, "control.bin").writeBytes(ByteBuffer.allocate(64).order(LITTLE_ENDIAN).apply {
            put("AURACT1\u0000".toByteArray(Charsets.US_ASCII)); putInt(1); putInt(64)
        }.array())
        session = Session(id, directory, executable)
        return directory
    }

    internal fun control(file: File, epoch: Long, deadlineNs: Long) {
        require(epoch >= 0 && deadlineNs >= 0)
        FileChannel.open(file.toPath(), READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            check(channel.size() == 64L)
            val header = ByteBuffer.allocate(16).order(LITTLE_ENDIAN)
            while (header.hasRemaining()) check(channel.read(header) > 0)
            check(header.array().copyOfRange(0, 8).contentEquals("AURACT1\u0000".toByteArray(Charsets.US_ASCII)) &&
                header.getInt(8) == 1 && header.getInt(12) == 64)
            fun write(offset: Long, value: Long) {
                val data = ByteBuffer.allocate(8).order(LITTLE_ENDIAN).putLong(value)
                data.flip()
                while (data.hasRemaining()) check(channel.write(data, offset + data.position()) > 0)
            }
            write(24, 0) // Disarm before changing epoch; never extend a running capture by accident.
            write(16, epoch)
            if (deadlineNs > 0) write(24, deadlineNs)
        }
    }

    @Synchronized fun startCapture(id: String): Long? {
        val current = session?.takeIf { it.id == id } ?: return null
        val next = current.epoch + 1
        return runCatching {
            control(File(current.directory, "control.bin"), next, System.nanoTime() + 8_000_000_000L)
            current.epoch = next; next
        }.getOrNull()
    }

    @Synchronized fun stopCapture(id: String? = null) {
        val current = session?.takeIf { id == null || it.id == id } ?: return
        runCatching { control(File(current.directory, "control.bin"), current.epoch, 0) }
    }

    private fun retire(directory: File) {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return
        runCatching { control(File(directory, "control.bin"), 0, 0) }
        (names + "control.bin").forEach { runCatching { Files.deleteIfExists(File(directory, it).toPath()) } }
        runCatching { Files.deleteIfExists(directory.toPath()) }
    }
    @Synchronized fun discard() { session?.let { retire(it.directory) }; session = null }

    data class Snapshot(val summary: String, val files: Map<String, ByteArray>)
    @Synchronized fun snapshot(id: String, phase: String, epoch: Long?): Snapshot {
        require(phase in listOf("before", "after"))
        val current = session?.takeIf { it.id == id }
            ?: return Snapshot("CODE_PROTECTION_V1 state=NOT_CONFIGURED\n", emptyMap())
        val access = ProcGameExecutionAccess()
        val processes = runCatching { access.session()?.processes.orEmpty() }.getOrDefault(emptyList())
        val files = linkedMapOf<String, ByteArray>()
        val summary = StringBuilder("CODE_PROTECTION_V1 phase=$phase; requested_epoch=${epoch ?: "?"}; " +
            "scope=WINE_SYSCALL_TABLE_NTPROTECT; direct_mprotect=UNOBSERVED; smc_cause=UNCONFIRMED; " +
            "ranges=REQUESTED_OR_PREVIOUS_EXECUTABLE; timings=INCLUDE_OBSERVER_AND_PTRACE; full_rows=BINARY\n")
        for (name in names) {
            val file = File(current.directory, name)
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) continue
            val parsed = runCatching {
                FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                    fun bytes(): ByteArray {
                        check(channel.size() == CodeTraceSnapshot.BYTES.toLong())
                        val buffer = ByteBuffer.allocate(CodeTraceSnapshot.BYTES); channel.position(0)
                        while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
                        return buffer.array()
                    }
                    CodeTraceSnapshot.parse(bytes(), bytes())
                }
            }.getOrNull()
            if (parsed == null) { summary.append("file=$name; state=UNREADABLE_OR_CHANGING\n"); continue }
            val process = processes.firstOrNull { it.identity == (parsed.pid to parsed.startTicks) }
            val command = process?.let { runCatching { access.command(it) }.getOrNull() }
            val owner = when {
                command == null -> "UNCONFIRMED"
                GameExecutionSampler.matchesExecutable(current.executable, command) -> "TARGET_EXE"
                else -> "SESSION_OTHER"
            }
            files[name.replace("code-", "code-protection-$phase-")] = parsed.bytes
            summary.append(parsed.report(owner, epoch))
        }
        if (files.isEmpty()) summary.append("state=NO_NATIVE_DATA; calls=UNKNOWN\n")
        return Snapshot(summary.toString(), files)
    }
}

/** Layout v1: one stable snapshot, bounded aggregates and the latest 128 executable-range returns. */
object CodeTraceSnapshot {
    const val BYTES = 16384
    private val states = listOf("WAITING_FOR_NTDLL", "INSTALLED", "ARCH_UNSUPPORTED", "IDENTITY_MISMATCH",
        "LAYOUT_MISMATCH", "TABLE_READ_ONLY", "TABLE_RESTORE_FAILED")
    fun validHeader(bytes: ByteArray): Boolean {
        if (bytes.size != BYTES || !bytes.copyOfRange(0, 8).contentEquals("AURACP1\u0000".toByteArray(Charsets.US_ASCII))) return false
        val b = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        return b.getInt(8) == 1 && b.getInt(12) == BYTES && b.getInt(16) > 1 && b.getLong(24) > 0 &&
            b.getInt(20) in states.indices && b.getLong(40) >= 0 && b.getLong(40) % 2 == 0L
    }
    fun parse(first: ByteArray, second: ByteArray): Result? {
        if (!validHeader(first) || !validHeader(second) || !first.contentEquals(second)) return null
        val b = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        if ((48..112 step 8).any { b.getLong(it) < 0 } || b.getLong(56) > 100000 || b.getLong(112) > b.getLong(56)) return null
        for (i in 0 until 64) {
            val offset = 128 + i * 80
            val count = b.getLong(offset + 16)
            if (count < 0 || count > b.getLong(112)) return null
            if (count > 0 && ((0..48 step 8).any { b.getLong(offset + it) < 0 } ||
                b.getLong(offset) <= 0 || b.getLong(offset + 8) <= 0 || b.getLong(offset + 24) > count ||
                b.getLong(offset + 72) !in 0..count)) return null
        }
        return Result(second.copyOf())
    }
    class Result(val bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        val pid get() = b.getInt(16)
        val startTicks get() = b.getLong(24)
        val epoch get() = b.getLong(48)
        fun report(owner: String, requestedEpoch: Long?): String = buildString {
            append("CODE_PROCESS_V1 pid=$pid; start_ticks=$startTicks; owner=$owner; hook=${states[b.getInt(20)]}; epoch=$epoch")
            if (requestedEpoch == null || requestedEpoch != epoch) {
                append("; state=NO_RETURNS_FOR_REQUESTED_WINDOW; calls=UNKNOWN\n"); return@buildString
            }
            append("; observed_entries=${b.getLong(120)}; returns=${b.getLong(56)}; dropped=${b.getLong(64)}; foreign=${b.getLong(72)}; unreadable=${b.getLong(80)}; ")
            append("nonexec=${b.getLong(88)}; budget_reached=${b.getLong(96) != 0L}; row_overflow=${b.getLong(104)}; events=${b.getLong(112)}\n")
            if (owner != "TARGET_EXE") return@buildString
            (0 until 64).map { 128 + it * 80 }.filter { b.getLong(it + 16) > 0 }
                .sortedByDescending { b.getLong(it + 16) }.take(8).forEach { row ->
                    append("CODE_RANGE_V1 pid=$pid; base=0x${b.getLong(row).toString(16)}; bytes=${b.getLong(row + 8)}; ")
                    append("returns=${b.getLong(row + 16)}; failures=${b.getLong(row + 24)}; wall_ns=${b.getLong(row + 32)}; ")
                    val cpuCalls = b.getLong(row + 72)
                    append("thread_cpu_ns=${if (cpuCalls == 0L) "?" else b.getLong(row + 40)}; cpu_measured_calls=$cpuCalls; ")
                    append("writable_requests=${b.getLong(row + 48)}; ")
                    append("last_status=0x${b.getInt(row + 56).toUInt().toString(16)}; last_requested=0x${b.getInt(row + 60).toUInt().toString(16)}\n")
                }
        }
    }
}

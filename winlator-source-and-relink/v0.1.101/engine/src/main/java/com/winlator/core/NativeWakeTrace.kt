/* Aura integration changes for 0.1.101, 2026-10-20.
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

/** Opt-in, launch-bound call history. No game files, raw stacks or account paths are read. */
object NativeWakeTrace {
    const val LIBRARY = "libaura_wake_trace.so"
    const val VARIABLE = "AURA_WINE_WAKE_TRACE"
    const val DIRECTORY = "AURA_WINE_WAKE_DIRECTORY"
    const val BYTES = 4096 + 8192 * 96
    private data class Session(val id: String, val directory: File)
    private var session: Session? = null
    private val uuid = Regex("[a-f0-9-]{36}")
    private val ringName = Regex("wake-[1-4]\\.bin")

    fun silence(env: EnvVars) {
        env.put(VARIABLE, "0")
        env.remove(DIRECTORY)
        val kept = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != LIBRARY }.joinToString(" ")
        if (kept.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", kept)
    }

    fun configure(context: Context, env: EnvVars) {
        val optedOut = env.get(VARIABLE) == "0"
        silence(env)
        val intent = (context as? Activity)?.intent ?: return
        val id = intent.getStringExtra("aura_launch_id")?.takeIf(uuid::matches) ?: return
        if (optedOut || !RuntimeDiagnostics.sessionEnabled(context) ||
            !GameStallWatch.enabled(PerGameLaunch.read(intent), intent.getStringExtra("exec_path").orEmpty())) return
        val library = File(context.applicationInfo.nativeLibraryDir, LIBRARY)
        if (!library.isFile) {
            RuntimeLaunchDiagnostics.detail(context, "WINE_WAKE_TRACE_V1 state=LIBRARY_MISSING")
            return
        }
        val directory = runCatching { prepare(context, id) }.getOrNull()
        if (directory == null) {
            RuntimeLaunchDiagnostics.detail(context, "WINE_WAKE_TRACE_V1 state=STORAGE_UNAVAILABLE")
            return
        }
        val preload = env.get("LD_PRELOAD").orEmpty()
        env.put("LD_PRELOAD", if (preload.isBlank()) library.path else "$preload ${library.path}")
            .put(VARIABLE, "1").put(DIRECTORY, directory.path)
        RuntimeLaunchDiagnostics.detail(context, "WINE_WAKE_TRACE_V1 state=CONFIGURED; capacity=8192; " +
            "max_processes=4; scope=MIRRORS_EDGE_LEADER; function_results=UNCHANGED; cause=UNCONFIRMED")
    }

    @Synchronized private fun prepare(context: Context, id: String): File {
        session?.takeIf { it.id == id }?.let { return it.directory }
        discard()
        val root = File(context.filesDir, "diagnostics/wine-wake")
        check(root.isDirectory || root.mkdirs())
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        // Recover only our own bounded directories after an interrupted host.
        root.listFiles().orEmpty().filter { uuid.matches(it.name) }.forEach(::retire)
        val directory = File(root, id)
        check(directory.mkdir())
        session = Session(id, directory)
        return directory
    }

    @Synchronized fun discard() {
        session?.let { runCatching { retire(it.directory) } }
        session = null
    }

    private fun retire(directory: File) {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return
        directory.listFiles().orEmpty().filter { ringName.matches(it.name) }.forEach { file ->
            runCatching {
                FileChannel.open(file.toPath(), READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() == BYTES.toLong()) {
                        val header = ByteBuffer.allocate(64).order(LITTLE_ENDIAN)
                        while (header.hasRemaining()) check(channel.read(header) > 0)
                        if (WakeTraceSnapshot.validHeader(header.array(), BYTES)) {
                            val stop = ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(1)
                            stop.flip()
                            while (stop.hasRemaining()) check(channel.write(stop, 48L + stop.position()) > 0)
                        }
                    }
                }
            }
        }
        // Unlink after setting stop. Mapped producers retain their own mapping,
        // see stop=1, and cannot create another file in the removed directory.
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    data class Snapshot(val files: Map<String, ByteArray>, val summary: String)

    @Synchronized fun snapshot(id: String, phase: String): Snapshot {
        require(phase == "before" || phase == "after")
        val current = session?.takeIf { it.id == id } ?: return Snapshot(emptyMap(), "state=NOT_CONFIGURED\n")
        val files = linkedMapOf<String, ByteArray>()
        val notes = StringBuilder("WINE_WAKE_TRACE_V1 phase=$phase; clock=CLOCK_BOOTTIME; " +
            "scope=LEADER_WAIT_AND_TARGET_ALERT; snapshot=DOUBLE_READ_FILTERED; atomic_snapshot=NO\n")
        // Slot names and fixed sizes prevent an arbitrary or stale file from being exported.
        for (slot in 1..4) {
            val file = File(current.directory, "wake-$slot.bin")
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) continue
            val name = "wine-wake-$phase-0$slot.bin"
            runCatching {
                FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                    check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && channel.size() == BYTES.toLong())
                    fun read(): ByteArray {
                        channel.position(0)
                        val data = ByteBuffer.allocate(BYTES)
                        while (data.hasRemaining()) check(channel.read(data) > 0)
                        check(channel.size() == BYTES.toLong())
                        return data.array()
                    }
                    WakeTraceSnapshot.filter(read(), read())
                }
            }.onSuccess {
                files[name] = it.bytes
                notes.appendLine("$name state=CAPTURED; events=${it.retained}; unstable_or_invalid=${it.omitted}")
            }.onFailure { notes.appendLine("$name state=UNAVAILABLE_OR_INVALID") }
        }
        for (index in 1..16) {
            val file = File(current.directory, "setup-${index.toString().padStart(2, '0')}.txt")
            runCatching {
                FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                    check(channel.size() in 1..192)
                    val data = ByteBuffer.allocate(channel.size().toInt())
                    while (data.hasRemaining()) check(channel.read(data) > 0)
                    data.array().toString(Charsets.US_ASCII).trim().takeIf {
                        it.matches(Regex("WINE_WAKE_TRACE_V1 pid=[0-9]+; state=[A-Z_]+; source=NTDLL_77E39422; capacity=8192"))
                    }?.let(notes::appendLine)
                }
            }
        }
        if (files.isEmpty()) notes.appendLine("state=NO_EVENT_FILE; installation_and_main_thread_coverage=UNCONFIRMED")
        notes.appendLine("Overwrite/drop counters and skipped multi-target calls are in the headers. " +
            "Only direct WAIT_RETURN/STATUS_ALERTED records native alert consumption; completion helper returns do not. " +
            "This is call-boundary observation with timing overhead, not an atomic queue history or a stall fix.")
        return Snapshot(files, notes.toString())
    }
}

/** In-flight/changed slots are omitted; no synthetic event is inferred from a torn live read. */
object WakeTraceSnapshot {
    data class Result(val bytes: ByteArray, val retained: Int, val omitted: Int)
    fun validHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length != NativeWakeTrace.BYTES || bytes.size < 64) return false
        val data = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        return bytes.copyOfRange(0, 8).contentEquals("AURAWK1\u0000".toByteArray()) &&
            data.getInt(8) == 1 && data.getInt(12) == 96 && data.getInt(16) == 8192 &&
            data.getInt(20) > 0 && data.getInt(40) > 0 && data.getInt(44) == 1 &&
            data.getInt(52) in 0..32
    }

    fun filter(first: ByteArray, second: ByteArray): Result {
        require(validHeader(first) && validHeader(second))
        val before = ByteBuffer.wrap(first).order(LITTLE_ENDIAN)
        val after = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        require(before.getInt(20) == after.getInt(20) && before.getInt(40) == after.getInt(40) &&
            before.getLong(56) == after.getLong(56))
        val output = second.copyOf()
        // Module discovery is published once; retain metadata only if both copies agree.
        if (!first.copyOfRange(52, 56).contentEquals(second.copyOfRange(52, 56)) ||
            !first.copyOfRange(128, 2176).contentEquals(second.copyOfRange(128, 2176))) {
            ByteBuffer.wrap(output).order(LITTLE_ENDIAN).putInt(52, 0)
            output.fill(0, 128, 2176)
        }
        var retained = 0
        var omitted = 0
        val next = after.getLong(24)
        require(next >= 0)
        for (slot in 0 until 8192) {
            val at = 4096 + slot * 96
            val seq = after.getLong(at)
            val ticket = seq / 2
            val stable = seq > 0 && seq % 2 == 0L && seq == after.getLong(at + 80) &&
                ticket <= next && ticket > (next - 8192).coerceAtLeast(0) &&
                (ticket - 1) % 8192 == slot.toLong() &&
                after.getInt(at + 52) in 1..8 &&
                (at until at + 96).all { first[it] == second[it] }
            if (stable) retained++ else {
                if (seq != 0L || before.getLong(at) != 0L) omitted++
                output.fill(0, at, at + 96)
            }
        }
        return Result(output, retained, omitted)
    }
}

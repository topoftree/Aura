/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A bounded, private, recoverable capture. Never reads account/configuration files or whole memory. */
class GameDiagnosticSource private constructor(
    val entry: String, private val file: File, private val limit: Int,
    private val baseline: Stamp?, private val anchors: ByteArray?, private val fromStart: Boolean,
    private val beforeRead: () -> Boolean,
) {
    private data class Stamp(val size: Long, val modified: Long, val key: Any?)
    fun copyTo(destination: File): String {
        if (runCatching { file.canonicalFile == destination.canonicalFile }.getOrDefault(true))
            return "$entry\tDESTINATION_CONFLICT"
        destination.delete()
        return try {
            if (!beforeRead()) return "$entry\tFLUSH_FAILED"
            val before = stamp(file) ?: return "$entry\tMISSING_OR_NOT_REGULAR"
            if (!fromStart && before == baseline) return "$entry\tUNCHANGED_FROM_LAUNCH"
            FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                val observed = anchor(input, before.size)
                val start = if (!fromStart && baseline != null && before.key == baseline.key &&
                    before.size >= baseline.size && anchors != null && anchor(input, baseline.size).contentEquals(anchors)) baseline.size else 0L
                val size = before.size - start
                if (size == 0L) return "$entry\tNO_NEW_BYTES"
                val retained = minOf(size, limit.toLong())
                val truncated = size > retained
                // Keep the beginning and end of this launch's slice, with exact source offsets.
                val head = if (truncated) retained / 2 else retained
                val tail = retained - head
                destination.outputStream().use { output ->
                    copyRange(input, output, start, head)
                    if (tail > 0) copyRange(input, output, before.size - tail, tail)
                }
                val after = stamp(file)
                if (after == null || after.key != before.key || after.size < before.size ||
                    stampChannelAnchorChanged(input, before, after) || !anchor(input, before.size).contentEquals(observed)) {
                    destination.delete(); return "$entry\tCHANGED_DURING_READ"
                }
                "$entry\tCAPTURED\tfrom=$start\tend=${before.size}\tbytes=$retained\ttruncated=$truncated" +
                    "\thead=$head\ttail=$tail\tsha256=${sha256(destination)}"
            }
        } catch (_: Exception) { destination.delete(); "$entry\tUNREADABLE" }
    }

    private fun stampChannelAnchorChanged(input: FileChannel, before: Stamp, after: Stamp): Boolean =
        before.size == after.size && before.modified != after.modified || input.size() < before.size

    companion object {
        fun watch(entry: String, path: File, limit: Int, fromStart: Boolean = false,
                  beforeRead: () -> Boolean = { true }): GameDiagnosticSource {
            require(entry.matches(Regex("[a-z0-9_.-]+")) && limit in 1024..24 * 1024 * 1024)
            val file = File(requireNotNull(path.parentFile).canonicalFile, path.name)
            val baseline = stamp(file)
            val anchors = if (baseline != null && !fromStart) runCatching {
                FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { anchor(it, baseline.size) }
            }.getOrNull() else null
            return GameDiagnosticSource(entry, file, limit, baseline, anchors, fromStart, beforeRead)
        }
        private fun stamp(file: File): Stamp? = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                .takeIf { it.isRegularFile }?.let { Stamp(it.size(), it.lastModifiedTime().toMillis(), it.fileKey()) }
        }.getOrNull()
        private fun anchor(input: FileChannel, size: Long): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            for (offset in listOf(0L, maxOf(0, size - 512))) {
                val buffer = ByteBuffer.allocate(minOf(size, 512).toInt())
                while (buffer.hasRemaining()) check(input.read(buffer, offset + buffer.position()) > 0)
                digest.update(buffer.array())
            }
            return digest.digest()
        }
        private fun copyRange(input: FileChannel, output: OutputStream, offset: Long, size: Long) {
            var copied = 0L
            val buffer = ByteBuffer.allocate(32 * 1024)
            while (copied < size) {
                buffer.clear().limit(minOf(buffer.capacity().toLong(), size - copied).toInt())
                val n = input.read(buffer, offset + copied)
                check(n > 0)
                output.write(buffer.array(), 0, n); copied += n
            }
        }
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(32 * 1024)
                while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

object GameDiagnosticArchive {
    const val STORE = "diagnostics/game-bundle"
    private const val DIRECTORY = "capture"
    private const val ARCHIVE = "game-debug-latest.zip"
    private val lock = Any()
    private val fixed = setOf("session.txt", "report-before.txt", "report-after.txt", "sources.tsv", "status.txt", "trigger.txt",
        "wine-wake-before.txt", "wine-wake-after.txt")

    @Synchronized fun begin(store: File, session: String, capture: String, started: Long, report: String) = synchronized(lock) {
        require(session.matches(Regex("[a-f0-9-]{36}")) && capture.matches(Regex("[a-f0-9-]{36}")))
        check(store.isDirectory || store.mkdirs())
        val directory = File(store, DIRECTORY)
        if (directory.exists()) { check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)); directory.deleteRecursively() }
        check(directory.mkdir())
        write(store, "session.txt", "GAME_DIAGNOSTIC_BUNDLE_V1\nsession=$session\ncapture=$capture\nstarted_utc_ms=$started\n")
        write(store, "report-before.txt", report)
        write(store, "status.txt", "state=COLLECTING\ncompleted_batches=0\n")
    }

    fun write(store: File, name: String, text: String) = synchronized(lock) {
        require(name in fixed || name.matches(Regex("sample-0[1-6]\\.txt")))
        require(text.length <= 64 * 1024)
        val directory = directory(store)
        val temp = File(directory, "$name.tmp")
        temp.writeText(text)
        Files.move(temp.toPath(), File(directory, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun wakeBytes(store: File, name: String, bytes: ByteArray) = synchronized(lock) {
        require(name.matches(Regex("wine-wake-(before|after)-0[1-4]\\.bin")))
        require(WakeTraceSnapshot.validHeader(bytes))
        val directory = directory(store)
        val temp = File(directory, "$name.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), File(directory, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun logs(store: File, sources: List<GameDiagnosticSource>, current: () -> Boolean = { true }) = synchronized(lock) {
        require(sources.size <= 12 && sources.map { it.entry }.distinct().size == sources.size)
        val directory = directory(store)
        val statuses = sources.map { source ->
            val destination = File(directory, source.entry)
            if (!current()) "${source.entry}\tSESSION_CHANGED" else {
                val status = source.copyTo(destination)
                if (current()) status else { destination.delete(); "${source.entry}\tSESSION_CHANGED" }
            }
        }
        val expected = listOf("guest-debug.log", "steam-bootstrap_log.txt", "steam-cef_log.txt", "steam-webhelper.txt",
            "steam-webhelper_gpu.txt", "ubisoft-launcher_log.txt", "ubisoft-game_starter_log.txt")
        val missing = expected.filter { name -> sources.none { it.entry == name } }.map { "$it\tNOT_CONFIGURED_FOR_LAUNCH" }
        write(store, "sources.tsv", "entry\tstatus\trange_and_hash\n" + (statuses + missing).joinToString("\n") +
            "\nNo old client directories were searched. Logging settings were preserved.\n")
    }

    fun available(store: File): Boolean = synchronized(lock) {
        checkpointAvailable(store) || File(store, ARCHIVE).let { it.isFile && it.length() > 0 }
    }

    fun export(store: File, output: OutputStream) = synchronized(lock) {
        // Recover complete checkpoints even if Android killed the host during collection.
        if (checkpointAvailable(store)) publish(store)
        val file = File(store, ARCHIVE)
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        file.inputStream().use { it.copyTo(output) }
    }

    fun publish(store: File) = synchronized(lock) {
        val directory = directory(store)
        val files = directory.listFiles().orEmpty().filter {
            !it.name.endsWith(".tmp") && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
        }.sortedBy { it.name }
        check(files.size in 3..38)
        check(files.sumOf(File::length) <= 40L * 1024 * 1024)
        val temp = File(store, "$ARCHIVE.tmp")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                fun text(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                text("README.txt", "One capture, one session. Capture times and completion are in session.txt/status.txt.\n" +
                    "trigger.txt identifies an opt-in automatic suspected Present gap; its absence means manual collection. A gap alone does not establish a game freeze.\n" +
                    "WINE_SYNC records fixed-layout read-only observations. The historical critical-section candidate alone does NOT identify the sampled thread's current lock. WINE_CRITICAL_WAIT_FRAME, when CAPTURED, validates the pinned x86 callsite, arguments and Wine TID; MATCH relates that call to the candidate. A cleared node address does not prove alert delivery. Owner is a Wine TID, not a Linux TID; a wake flag is not proof of a missed wakeup.\n" +
                    "wine-wake-*.bin is optional, bounded native call history for the Mirror's Edge Linux leader. See wine-wake-before/after.txt for coverage. The diagnostic layer redirects three verified syscall-table entries and one private completion entry to wrappers that call the original functions; timing is perturbed. No timeout, alert flag or game setting is changed by the observer. Only direct WAIT_RETURN/STATUS_ALERTED identifies native alert consumption; COMPLETION_RETURN can also report an already-completed queue node without consuming an alert. Completion and flag observations are boundaries, not atomic with the original call. Legacy fixed-layout queue/frame snapshots fail closed on the new runtime. Snapshots filter unstable slots, may wrap/drop events, and do not prove a fixed stall. Decode with tools/diagnostics/wine-wake-trace/decode.py.\n" +
                    "COLLECTING means the final checkpoint was not reached; retained samples are still usable.\n" +
                    "Sources are bounded snapshots, not a claim of a complete log or game readiness. Truncated log files join the head and tail; sources.tsv gives both ranges.\n" +
                    "Native batches rotate Linux leader / hottest comparable worker / second worker, at most 18 points. Missing/stale workers are skipped.\n" +
                    "Worker rank uses interval CPU time of surviving threads, not a bottleneck verdict. Thread identity and timing are in each sample.\n" +
                    "Capture briefly stops the selected thread; overlapping frame intervals are perturbed. CODE_REF entries are executable candidates, NOT an unwound call chain.\n" +
                    "Context flags: 1=code unreadable/out of bounds, 2=stack unavailable, 4=maps I/O, 8=maps limit, 16=reference limit.\n" +
                    "No full memory dump, raw stack, raw register dump, credentials/config files or automatic upload. Client logs may contain personal data.\n")
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                text("checksums.sha256", files.joinToString("\n", postfix = "\n") { "${GameDiagnosticSource.sha256(it)}  ${it.name}" })
            }
            Files.move(temp.toPath(), File(store, ARCHIVE).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun directory(store: File): File = File(store, DIRECTORY).also {
        check(Files.isDirectory(it.toPath(), NOFOLLOW_LINKS))
    }

    private fun checkpointAvailable(store: File) = listOf("session.txt", "report-before.txt", "status.txt").all {
        Files.isRegularFile(File(store, "$DIRECTORY/$it").toPath(), NOFOLLOW_LINKS)
    }
}

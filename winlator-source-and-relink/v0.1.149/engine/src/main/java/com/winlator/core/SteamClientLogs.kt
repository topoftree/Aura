/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Locale

/** Session-only, bounded Steam/CEF log classification. Never exports raw lines or account data. */
class SteamClientLogs(executable: File) {
    private val root = requireNotNull(executable.parentFile).canonicalFile.toPath()
    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)
    private val paths = listOf("bootstrap_log.txt", "cef_log.txt", "webhelper.txt", "connection_log.txt")
        .map { root.resolve("logs/$it") }
    private val positions = paths.associateWith { stamp(it) }.toMutableMap()
    private val freshFiles = mutableSetOf<Int>()
    private val signals = linkedSetOf<String>()
    private var truncated = false
    private var bootstrapPhase = "NO_FRESH_PHASE"
    private val connection = SteamConnectionObservation()
    private var connectionCarry = ""
    private var connectionDiscardLine = false

    fun networkReport(): String = connection.networkReport(truncated)

    init { require(executable.name.equals("steam.exe", true)) }

    /** Run off the render/UI thread. Appended bytes cannot resurrect errors from an old session. */
    fun collect(): String {
        for ((index, path) in paths.withIndex()) {
            val current = stamp(path) ?: continue
            val previous = positions[path]
            if (previous == current) continue
            if (current.size == 0L) {
                positions[path] = current
                if (index == 3) { connectionCarry = ""; connectionDiscardLine = false }
                continue
            }
            val sameFile = previous != null && current.key == previous.key
            // Same-size in-place edits cannot reliably be attributed to this session.
            if (sameFile && current.size == previous.size) {
                positions[path] = current
                if (index == 3) { connectionCarry = ""; connectionDiscardLine = false }
                continue
            }
            val offset = if (sameFile && current.size > previous.size) previous.size else 0L
            val start = maxOf(offset, current.size - LIMIT)
            try {
                val text = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                    channel.position(start)
                    val bytes = ByteBuffer.allocate((current.size - start).toInt())
                    while (bytes.hasRemaining() && channel.read(bytes) > 0) { /* Bounded tail. */ }
                    String(bytes.array(), 0, bytes.position(), Charsets.UTF_8)
                }
                positions[path] = current
                freshFiles += index
                truncated = truncated || start > offset
                val errors = GuestOutputSummary()
                // Authentication logs are never passed to the general guest/error parser. Only
                // fixed connection tokens are retained, and only complete new lines are examined.
                if (index == 3) {
                    if (!sameFile || previous?.size != offset || start != offset) {
                        connectionCarry = ""
                        connectionDiscardLine = start > offset
                    }
                    val next = if (connectionDiscardLine) {
                        if ('\n' !in text) continue
                        connectionDiscardLine = false
                        text.substringAfter('\n')
                    } else text
                    val chunk = connectionCarry + next
                    val end = chunk.lastIndexOf('\n')
                    if (end >= 0) chunk.substring(0, end).lineSequence().forEach(connection::accept)
                    val tail = chunk.substring(end + 1)
                    connectionDiscardLine = tail.length > 4096
                    connectionCarry = if (connectionDiscardLine) "" else tail
                    continue
                }
                for (line in text.lineSequence()) {
                    val lower = line.take(4096).lowercase(Locale.ROOT)
                    if (index == 0) bootstrapPhase = when {
                        "update complete, launching steam" in lower -> "UPDATE_COMPLETE"
                        "verification complete" in lower -> "VERIFICATION_COMPLETE"
                        "verifying installation" in lower || "verifying all executable" in lower -> "VERIFYING"
                        "manifest download: waiting for download to finish" in lower -> "MANIFEST_WAITING"
                        "manifest download: finished" in lower || "manifest download: completed" in lower -> "MANIFEST_RECEIVED"
                        "downloading manifest" in lower -> "MANIFEST_REQUESTED"
                        "extracting package" in lower -> "EXTRACTING"
                        "download failed" in lower || "failed to download" in lower -> "DOWNLOAD_FAILED"
                        else -> bootstrapPhase
                    }
                    fun signal(code: String) { if (signals.size < 20) signals += code }
                    // Steam can finish painting its internal error document without creating a
                    // login/library window. Record the event, not an inferred cache/network cause.
                    if (index == 2 && "sp shared js context" in lower && ERROR_PAGE_REQUEST.containsMatchIn(lower))
                        signal("STEAM_UI_ERROR_PAGE")
                    if ("gpu process launch failed" in lower || "gpu process exited unexpectedly" in lower)
                        signal("CEF_GPU_PROCESS_FAILED")
                    if ("gpu process isn't usable" in lower || "gpu process is not usable" in lower)
                        signal("CEF_GPU_UNUSABLE")
                    if ("failed to initialize sandbox" in lower || "sandbox initialization failed" in lower)
                        signal("CEF_SANDBOX_INIT_FAILED")
                    if ("steamwebhelper" in lower && ("failed to launch" in lower || "failed to start" in lower))
                        signal("STEAM_WEBHELPER_LAUNCH_FAILED")
                    if ("fatal error" in lower && index == 0) signal("STEAM_BOOTSTRAP_FATAL")
                    if (index == 0 && ("download failed" in lower || "failed to download" in lower))
                        signal("STEAM_UPDATE_DOWNLOAD_FAILED")
                    errors.accept(line)
                }
                for (code in errors.signalCodes()) if (signals.size < 20) signals += code
            } catch (_: IOException) { /* Update may replace/rotate the file. Retry next sample. */ }
              catch (_: SecurityException) { /* Unreadable logs are not proof of a client crash. */ }
        }
        return SteamCefFiles.report(root) + "\n" + SteamPeArchitecture.report(root) + "\n" +
            "STEAM_LOGS_V1 fresh_files=${freshFiles.size}; phase=$bootstrapPhase; tail_truncated=$truncated; signals=" +
            signals.joinToString(",").ifEmpty { "NO_CLASSIFIED_ERROR" } + "\n" + connection.report(3 in freshFiles)
    }

    private fun stamp(path: Path): Stamp? = try {
        var part = path
        var safe = true
        while (part != root) {
            if (!part.startsWith(root) || Files.isSymbolicLink(part)) { safe = false; break }
            part = part.parent
        }
        if (!safe) null else Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            .takeIf { it.isRegularFile }?.let { Stamp(it.size(), it.lastModifiedTime(), it.fileKey()) }
    } catch (_: IOException) { null } catch (_: SecurityException) { null }

    companion object {
        private const val LIMIT = 64L * 1024
        private val ERROR_PAGE_REQUEST = Regex("""\brequest:\s*http://error/(?:$|[?#\s])""")
    }
}

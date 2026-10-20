/* Aura integration changes for 0.1.101, 2026-10-20.
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

/** Read only newly written, bounded client log tails. Never export raw lines or account data. */
class UbisoftClientLogs(executable: File) {
    private val root = requireNotNull(executable.parentFile).canonicalFile.toPath()
    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)
    private class Log(val path: Path, var stamp: Stamp?, var offset: Long) {
        var fresh = false
    }
    private val logs = listOf("launcher_log.txt", "game_starter_log.txt").map {
        val path = root.resolve("logs/$it")
        val initial = stamp(path)
        Log(path, initial, initial?.size ?: 0)
    }
    private val signals = linkedSetOf<String>()
    private var truncated = false

    init { require(executable.name.lowercase(Locale.ROOT) in CLIENT_NAMES) }

    /** Called on the existing diagnostics IO sampler, including before orderly teardown. */
    fun collect(): String {
        val states = logs.map(::collect)
        return "UBISOFT_LOGS_V1 launcher=${states[0]}; game_starter=${states[1]}; tail_truncated=$truncated; signals=" +
            signals.joinToString(",").ifEmpty { "NO_CLASSIFIED_SIGNAL" }
    }

    private fun collect(log: Log): String {
        val current = stamp(log.path) ?: return if (runCatching { Files.notExists(log.path, NOFOLLOW_LINKS) }.getOrDefault(false))
            "MISSING" else "UNREADABLE"
        val previous = log.stamp
        if (current.size == 0L) { log.stamp = current; log.offset = 0; return "EMPTY" }
        if (current == previous) return if (log.fresh) "FRESH" else "UNCHANGED"
        val sameFile = previous != null && current.key == previous.key
        // Same-size rewrites cannot reliably be attributed to this launch.
        if (sameFile && current.size == previous!!.size) {
            log.stamp = current
            return if (log.fresh) "FRESH" else "UNCHANGED"
        }
        val offset = if (sameFile && current.size > previous!!.size) log.offset else 0L
        val start = maxOf(offset, current.size - LIMIT)
        return try {
            FileChannel.open(log.path, READ, NOFOLLOW_LINKS).use { channel ->
                // If an old/truncated line crosses the boundary, never classify its suffix.
                val preceding = ByteBuffer.allocate(1)
                val skipFirst = start > 0 && (channel.read(preceding, start - 1) != 1 || preceding[0] != '\n'.code.toByte())
                channel.position(start)
                val bytes = ByteBuffer.allocate((current.size - start).toInt())
                while (bytes.hasRemaining() && channel.read(bytes) > 0) { /* Bounded read. */ }
                val data = bytes.array()
                val end = bytes.position()
                var lineStart = 0
                var skipping = skipFirst
                var consumed = 0
                for (index in 0 until end) if (data[index] == '\n'.code.toByte()) {
                    if (!skipping) classify(String(data, lineStart, minOf(index - lineStart, 4096), Charsets.UTF_8))
                    skipping = false
                    lineStart = index + 1
                    consumed = lineStart
                }
                log.offset = start + consumed // A partial new line can finish in the next sample.
            }
            log.stamp = current
            log.fresh = true
            truncated = truncated || start > offset
            "FRESH"
        } catch (_: IOException) { "UNREADABLE" } catch (_: SecurityException) { "UNREADABLE" }
    }

    private fun classify(text: String) {
        val line = text.lowercase(Locale.ROOT)
        // These are observed client log messages, not assertions that UI/login succeeded.
        if ("using cef with native rendering" in line) signals += "CEF_INITIALIZATION_REACHED"
        if ("login type:" in line) signals += "LOGIN_STARTED"
        if (PLAY_SESSION_STARTED.containsMatchIn(line)) signals += "PLAY_SESSION_STARTED"
        if ("gamestarter must be initialized before game can be launched" in line) signals += "GAME_STARTER_NOT_INITIALIZED"
        // Keep both the failed attempt and its retry as historical events. A retry or
        // accepted play session does not establish that the game reached its menu.
        if ("failed to inject suspended process. process terminated" in line) signals += "GAME_INJECTION_FAILED"
        if (INJECTED_ACCESS_VIOLATION.containsMatchIn(line)) signals += "GAME_INJECTION_ACCESS_VIOLATION"
        if (NO_INJECTION_RETRY.containsMatchIn(line)) signals += "GAME_START_NO_INJECTION_RETRY"
        if ("failed to initialize steam api" in line ||
            ("steamapi_init" in line && ("fail" in line || "false" in line))) signals += "STEAM_API_INIT_FAILED"
        if ("steam info is not available" in line) signals += "STEAM_INFO_UNAVAILABLE"
        if ("startreqerror, gamestarter reported errors" in line) signals += "GAME_START_REQUEST_ERROR"
        // The client reports the failed module separately from StartReqError. Retain only
        // the known Steam client basename, never its path or arbitrary provider error text.
        MODULE_LOAD_FAILURE.find(line)?.groupValues?.get(1)?.let { path ->
            if (path.substringAfterLast('\\').substringAfterLast('/') in STEAM_CLIENT_NAMES)
                signals += "STEAM_CLIENT_MODULE_LOAD_FAILED"
        }
        if ("gpu process" in line && ("launch failed" in line || "isn't usable" in line || "is not usable" in line))
            signals += "CEF_GPU_FAILED"
        if ("failed to initialize sandbox" in line || "sandbox initialization failed" in line) signals += "CEF_SANDBOX_FAILED"
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
        const val EXTRA_EXECUTABLE = "aura_ubisoft_client_executable"
        private const val LIMIT = 64L * 1024
        private val CLIENT_NAMES = setOf("ubisoftconnect.exe", "upc.exe", "uplay.exe")
        private val STEAM_CLIENT_NAMES = setOf("steamclient.dll", "steamclient64.dll")
        private val MODULE_LOAD_FAILURE = Regex("failed to load module '([^'\\r\\n]{1,1024})'")
        private val PLAY_SESSION_STARTED = Regex("\\bplay session(?: \\([0-9]{1,10}\\))? successfully started\\b")
        private val INJECTED_ACCESS_VIOLATION = Regex("\\binjected thread has exited\\. code 3221225477\\b")
        // The observed 174.2 client misspells "injection" in its fallback message.
        private val NO_INJECTION_RETRY = Regex("\\bfalling back to no injec(?:t)?ion\\b")
    }
}

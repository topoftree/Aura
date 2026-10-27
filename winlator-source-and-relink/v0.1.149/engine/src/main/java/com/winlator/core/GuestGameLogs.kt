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

/** Bounded, read-only Unity logs next to this launch's EXE. Never read dumps or save raw text. */
class GuestGameLogs(executable: File, private val startedAt: Long = System.currentTimeMillis()) {
    private val root = requireNotNull(executable.parentFile).canonicalFile.toPath()
    private val executableName = executable.name.lowercase(Locale.ROOT)
    private val dataName = executable.nameWithoutExtension + "_Data"
    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)
    private val baseline = candidates().mapNotNull { path -> stamp(path)?.let { path to it } }.toMap()
    private val read = mutableMapOf<Path, Stamp>()

    data class Report(val diagnostic: String, val crashed: Boolean)

    /** Call off the main thread at refresh/cleanup, never once per rendered frame. */
    fun collect(): Report? {
        val signals = linkedSetOf<String>()
        var count = 0
        var crashed = false
        for (path in candidates()) {
            val stamp = stamp(path) ?: continue
            if (stamp.size == 0L || stamp.modified.toMillis() < startedAt - 2_000 ||
                baseline[path] == stamp || read[path] == stamp) continue
            val text = try { readBounded(path, stamp.size) } catch (_: IOException) { continue }
            read[path] = stamp
            count++
            val errors = GuestOutputSummary()
            for (line in text.lineSequence()) {
                errors.accept(line)
                val lower = line.take(4096).lowercase(Locale.ROOT)
                // These are Unity's error.log fields, not a Wine root-process exit code.
                if ("caused an" in lower || "caused a" in lower) {
                    EXCEPTION.find(lower)?.let {
                        signals += "UNITY_EXCEPTION_0x${it.groupValues[1]}"
                        crashed = true
                    }
                }
                if ("unity player [version:" in lower) signals += "UNITY_CRASH_REPORT"
                MODULE.find(lower)?.let {
                    val name = it.groupValues[1]
                    signals += "UNITY_FAULT_MODULE_" + when {
                        name == executableName -> "GAME_EXECUTABLE"
                        name in MODULES -> MODULES.getValue(name)
                        else -> "OTHER"
                    }
                }
            }
            signals += errors.signalCodes()
        }
        if (count == 0) return null
        // A fresh Unity crash report alone is evidence of a guest failure, even if it uses
        // an unfamiliar exception format. Ordinary output_log.txt updates are not crashes.
        crashed = crashed || "UNITY_CRASH_REPORT" in signals
        return Report("GUEST_GAME_LOGS fresh=$count; signals=" +
            signals.take(24).joinToString().ifEmpty { "NO_CLASSIFIED_ERROR" }, crashed)
    }

    private fun candidates(): List<Path> = buildList {
        add(root.resolve(dataName).resolve("output_log.txt"))
        add(root.resolve("output_log.txt"))
        add(root.resolve("Player.log"))
        add(root.resolve("error.log"))
        // No recursive scan of game data or Wine drive mappings. Unity 2017 writes
        // Crash_YYYY-MM-DD_HHMMSS/error.log next to the game executable.
        try {
            Files.newDirectoryStream(root).use { entries ->
                entries.asSequence().take(512)
                    .filter { CRASH_DIRECTORY.matches(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }
                    .sortedByDescending { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() }
                    .take(12).forEach { add(it.resolve("error.log")); add(it.resolve("output_log.txt")) }
            }
        } catch (_: IOException) { /* A removed game or unavailable storage has no readable log. */ }
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
    } catch (_: IOException) { null }

    private fun readBounded(path: Path, size: Long): String {
        val limit = 96 * 1024
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            fun part(position: Long, length: Int): String {
                val bytes = ByteBuffer.allocate(length)
                channel.position(position)
                while (bytes.hasRemaining() && channel.read(bytes) > 0) { /* Bounded by length. */ }
                return String(bytes.array(), 0, bytes.position(), Charsets.UTF_8)
            }
            val head = part(0, minOf(size, limit.toLong()).toInt())
            return if (size <= limit) head else head + "\n" +
                part(maxOf(limit.toLong(), size - limit), minOf(size - limit, limit.toLong()).toInt())
        }
    }

    companion object {
        private val CRASH_DIRECTORY = Regex("Crash_[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{6}", RegexOption.IGNORE_CASE)
        private val EXCEPTION = Regex("\\(0x([0-9a-f]{8})\\)")
        private val MODULE = Regex("\\bin module ([a-z0-9_.-]{1,80})\\s")
        private val MODULES = mapOf("mono.dll" to "UNITY_MONO", "mono-2.0-bdwgc.dll" to "UNITY_MONO",
            "unityplayer.dll" to "UNITY_PLAYER", "ntdll.dll" to "NTDLL", "kernelbase.dll" to "KERNELBASE",
            "d3d11.dll" to "D3D11", "d3d9.dll" to "D3D9", "dxgi.dll" to "DXGI",
            "winevulkan.dll" to "WINE_VULKAN", "libarm64ecfex.dll" to "FEX_ARM64EC", "libwow64fex.dll" to "FEX_WOW64")
    }
}

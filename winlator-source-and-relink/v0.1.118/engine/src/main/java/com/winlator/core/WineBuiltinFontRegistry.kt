/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Run after the four font files are ready, with Wine stopped. */
object WineBuiltinFontRegistry {
    const val NT_FONTS = "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
    const val LEGACY_FONTS = "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts"
    const val EXTERNAL_FONTS = "Software\\Wine\\Fonts\\External Fonts"
    private val fonts = linkedMapOf(
        "Tahoma (TrueType)" to "tahoma.ttf", "Tahoma Bold (TrueType)" to "tahomabd.ttf",
        "Marlett (TrueType)" to "marlett.ttf", "Symbol (TrueType)" to "symbol.ttf",
    )
    data class Repair(val system: String, val user: String, val registered: Int, val retired: Int)

    /** Preserve unrelated text and custom registrations, including unknown value types. */
    fun plan(system: String, user: String): Repair {
        require(system.startsWith("WINE REGISTRY Version 2") && user.startsWith("WINE REGISTRY Version 2"))
        var updatedSystem = system
        var updatedUser = user
        var registered = 0
        var retired = 0
        for ((name, file) in fonts) {
            val external = value(updatedUser, EXTERNAL_FONTS, name)
            // Wine removes HKLM font registrations when an old external path no longer
            // matches the face loaded from Windows/Fonts. Retire only known bundled paths.
            if (external != null && isBundledPath(external.value, file)) {
                updatedUser = updatedUser.removeRange(external.range)
                retired++
            } else if (external != null) continue // A user-managed external font owns this name.
            for (key in listOf(NT_FONTS, LEGACY_FONTS)) {
                val old = value(updatedSystem, key, name)
                val desired = "\"$name\"=\"$file\""
                if (old != null && old.value == desired) continue
                if (old == null || rawString(old.value) == "" || isBundledPath(old.value, file)) {
                    updatedSystem = if (old != null) updatedSystem.replaceRange(old.range, desired)
                        else insert(updatedSystem, key, desired)
                    registered++
                }
            }
        }
        return Repair(updatedSystem, updatedUser, registered, retired)
    }

    fun prepare(prefix: File): Repair {
        require(prefix.isDirectory && !Files.isSymbolicLink(prefix.toPath()))
        val systemFile = File(prefix, "system.reg")
        val userFile = File(prefix, "user.reg")
        val system = read(systemFile)
        val user = read(userFile)
        val result = plan(system, user)
        // Clear stale ownership first. If interrupted between files, the next launch
        // fills missing registrations; never start Wine after an unverified write.
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (result.user != user) write(userFile, user, result.user)
        if (result.system != system) write(systemFile, system, result.system)
        return result
    }

    private fun read(file: File): String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= 16 * 1024 * 1024)
        return file.inputStream().use {
            val bytes = it.readNBytes(16 * 1024 * 1024 + 1)
            require(bytes.size <= 16 * 1024 * 1024)
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun write(file: File, before: String, after: String) {
        val staging = Files.createTempFile(requireNotNull(file.parentFile).toPath(), ".aura-font-reg-", ".tmp").toFile()
        try {
            FileOutputStream(staging).use { it.write(after.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(read(file) == before) { "Font registry changed during preparation" }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            Files.move(staging.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            check(read(file) == after) { "Font registration write was not verified" }
        } finally { staging.delete() }
    }

    private fun body(text: String, key: String): IntRange? {
        val header = "[${key.replace("\\", "\\\\")}]"
        val matches = Regex("(?mi)^${Regex.escape(header)}[^\\r\\n]*(?:\\r?\\n|$)").findAll(text).toList()
        require(matches.size <= 1) { "Ambiguous font registry section" }
        val match = matches.singleOrNull() ?: return null
        val start = match.range.last + 1
        val end = Regex("(?m)^\\[").find(text, start)?.range?.first ?: text.length
        return start until end
    }

    private data class Value(val range: IntRange, val value: String)
    private fun value(text: String, key: String, name: String): Value? {
        val range = body(text, key) ?: return null
        val pattern = Regex("(?mi)^[ \\t]*\"${Regex.escape(name)}\"[ \\t]*=[^\\r\\n]*")
        val matches = pattern.findAll(text.substring(range)).toList()
        require(matches.size <= 1) { "Ambiguous font registration" }
        val match = matches.singleOrNull() ?: return null
        return Value((range.first + match.range.first)..(range.first + match.range.last), match.value)
    }

    private fun insert(text: String, key: String, line: String): String {
        val range = body(text, key)
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        return if (range == null) text + newline + "[${key.replace("\\", "\\\\")}]" + newline + line + newline
            else text.substring(0, range.first) +
                (if (range.first > 0 && text[range.first - 1] != '\n') newline else "") +
                line + newline + text.substring(range.first)
    }

    private fun rawString(line: String): String? {
        val raw = line.substringAfter('=').trim()
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return null
        val result = StringBuilder()
        var i = 1
        while (i < raw.lastIndex) {
            val ch = raw[i++]
            if (ch == '\\') {
                if (i >= raw.lastIndex || raw[i] !in charArrayOf('\\', '"')) return null
                result.append(raw[i++])
            } else if (ch == '"') return null else result.append(ch)
        }
        return result.toString()
    }

    private fun isBundledPath(line: String, file: String): Boolean {
        val path = rawString(line)?.replace('/', '\\') ?: return false
        val directories = listOf("", "C:\\windows\\fonts\\", "Z:\\opt\\wine\\share\\wine\\fonts\\",
            "Z:\\data\\data\\com.termux\\files\\usr\\share\\wine\\fonts\\",
            "Z:\\data\\data\\com.android.aura\\files\\rootfs-arm64ec\\opt\\wine\\share\\wine\\fonts\\",
            "Z:\\data\\user\\0\\com.android.aura\\files\\rootfs-arm64ec\\opt\\wine\\share\\wine\\fonts\\")
        return directories.any { path.equals(it + file, ignoreCase = true) }
    }
}

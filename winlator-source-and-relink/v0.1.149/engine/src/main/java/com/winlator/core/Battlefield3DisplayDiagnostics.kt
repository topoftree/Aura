/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

/** Fixed BF3 display fields only. Never exports the profile, paths, account data or key bindings. */
class Battlefield3DisplayDiagnostics(private val executable: File, private val prefix: File, private val user: File) {
    fun snapshot(): String {
        if (!executable.name.equals("bf3.exe", true)) return "GAME_DISPLAY_SETTINGS_V1 game=OTHER; state=NOT_APPLICABLE"
        return try {
            val roots = listOf(prefix.canonicalFile.toPath(), executable.canonicalFile.parentFile.toPath())
            fun safe(file: File) = file.canonicalFile.toPath().let { path -> roots.any(path::startsWith) }
            fun child(base: File, name: String): File? {
                if (!safe(base) || !base.isDirectory) return null
                val exact = File(base, name)
                val found = if (exact.exists()) exact else Files.newDirectoryStream(base.toPath()).use { entries ->
                    val matches = entries.asSequence().take(257).toList()
                    if (matches.size > 256) throw IllegalStateException()
                    matches.filter { it.fileName.toString().equals(name, true) }.singleOrNull()?.toFile()
                }
                require(found == null || safe(found))
                return found
            }
            fun resolve(base: File, vararg parts: String): File? {
                var current = base
                for (part in parts) current = child(current, part) ?: return null
                return current
            }
            val candidates = listOf(
                "USER_DOCUMENTS" to { resolve(user, "Documents", "Battlefield 3", "settings", "PROF_SAVE_profile") },
                "USER_MY_DOCUMENTS" to { resolve(user, "My Documents", "Battlefield 3", "settings", "PROF_SAVE_profile") },
                "GAME_SETTINGS" to { resolve(executable.parentFile, "settings", "PROF_SAVE_profile") },
                "GAME_ROOT" to { resolve(executable.parentFile, "PROF_SAVE_profile") },
            )
            val seen = mutableSetOf<String>()
            val reports = candidates.map { (label, locate) ->
                val result = try {
                    val file = locate()
                    when {
                        file == null || !file.isFile -> "state=NOT_FOUND"
                        !seen.add(file.canonicalPath) -> "state=ALIAS_ALREADY_REPORTED"
                        else -> read(file)
                    }
                } catch (_: IllegalArgumentException) { "state=PATH_REJECTED" }
                  catch (_: IllegalStateException) { "state=DIRECTORY_LIMIT" }
                  catch (_: Exception) { "state=UNREADABLE" }
                "BF3_DISPLAY_PROFILE_V1 source=$label; $result"
            }
            "GAME_DISPLAY_SETTINGS_V1 game=BF3; state=SAMPLED; selected_profile=UNCONFIRMED; writes=NONE\n" + reports.joinToString("\n")
        } catch (_: Exception) { "GAME_DISPLAY_SETTINGS_V1 game=BF3; state=UNAVAILABLE; writes=NONE" }
    }

    private fun read(file: File): String {
        val size = file.length()
        val modified = file.lastModified()
        if (size !in 1..LIMIT.toLong()) return "state=SIZE_LIMIT"
        val bytes = file.inputStream().use { it.readNBytes(LIMIT + 1) }
        if (bytes.size > LIMIT) return "state=SIZE_LIMIT"
        if (size != bytes.size.toLong() || size != file.length() || modified != file.lastModified()) return "state=CHANGED_DURING_READ"
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { return "state=INVALID_ENCODING" }
        return parse(text)
    }

    companion object {
        private const val LIMIT = 512 * 1024
        private val fields = linkedMapOf(
            "ResolutionWidth" to "width", "ResolutionHeight" to "height",
            "FullscreenEnabled" to "fullscreen", "FullscreenScreen" to "screen",
            "FullscreenRefreshRate" to "refresh_hz", "ResolutionHertz" to "resolution_hz",
            "FullscreenMode" to "fullscreen_mode", "Resolution" to "resolution_index",
        )

        /** Missing and malformed values stay distinct; an absent fullscreen field is never Off. */
        fun parse(text: String): String {
            if (text.length > LIMIT || '\u0000' in text) return "state=INVALID_TEXT"
            val values = fields.keys.associateWith { mutableListOf<String>() }
            for (line in text.lineSequence()) {
                val match = Regex("^[ \\t]*GstRender\\.([A-Za-z]+)[ \\t]+([^\\r\\n]*?)[ \\t]*\\r?$").matchEntire(line) ?: continue
                values[match.groupValues[1]]?.add(match.groupValues[2])
            }
            return "state=OBSERVED; " + fields.entries.joinToString("; ") { (key, label) ->
                val list = values.getValue(key)
                val value = when {
                    list.isEmpty() -> "MISSING"
                    list.size != 1 -> "DUPLICATE"
                    else -> {
                        val raw = list.single()
                        val number = raw.takeIf { it.matches(Regex("[0-9]{1,5}(?:\\.[0-9]{1,6})?")) }?.toDoubleOrNull()
                        val valid = number != null && when (label) {
                            "width", "height" -> number in 1.0..32766.0 && number % 1.0 == 0.0
                            "fullscreen" -> number == 0.0 || number == 1.0
                            "screen", "fullscreen_mode", "resolution_index" -> number in 0.0..65535.0 && number % 1.0 == 0.0
                            else -> number > 0.0 && number <= 1000.0
                        }
                        if (valid) raw else "INVALID"
                    }
                }
                "$label=$value"
            }
        }
    }
}

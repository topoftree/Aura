/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Defaults for Aura's virtual desktop, not an override of the game's resolution. */
object WineVirtualDisplayDefaults {
    const val LIBRARY = "opt/wine/lib/wine/aarch64-unix/win32u.so"
    const val SHA256 = "78f5f104dea687e8d70bb499e799f8ba3382ee166ea5ff32d2719172d40f5d46"
    private const val GPU_MEMORY_SHA256 = "1adc95435684af0cee099d9a0d6faecf13f99902acf76400f9f0e38fbd9d8afc"
    private const val LEGACY_SHA256 = "ff422e00fb0be1505f33e51a4fe38edef0ae0ae684c9bf99313dc7082c482672"
    private const val HEADER = "[Software\\\\Wine\\\\X11 Driver]"
    private val names = listOf("EmulateModelist", "EmulateModeset")
    private val section = Regex("(?im)^${Regex.escape(HEADER)}[^\\r\\n]*(?:\\r?\\n|$)")
    private val setting = Regex("(?im)^\"(EmulateModelist|EmulateModeset)\"[ \\t]*=[^\\r\\n]*(?:\\r?\\n|$)")

    fun prepare(prefix: File, library: File): String = try {
        // These names have inverted polarity in this particular Wine fork. Do not
        // carry our defaults into a different component with unknown semantics.
        val hash = if (library.isFile && library.length() in listOf(2_000_288L, 2_200_976L, 2_201_368L)) {
            val digest = MessageDigest.getInstance("SHA-256")
            library.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } else "UNKNOWN"
        configure(prefix, hash == SHA256 || hash == GPU_MEMORY_SHA256 || hash == LEGACY_SHA256)
    } catch (_: Exception) { "WINE_VIRTUAL_MODES_V1 state=UNAVAILABLE" }

    /** File-only portion, also exercised by registry round-trip tests. Production uses prepare. */
    fun configure(prefix: File, pinned: Boolean): String {
        val registry = AtomicFile(File(prefix, "user.reg"))
        val marker = AtomicFile(File(prefix, ".aura-virtual-display-defaults.json"))
        val text = read(registry)
        check(text.startsWith("WINE REGISTRY Version 2"))
        val sections = section.findAll(text).toList()
        check(sections.size <= 1) { "Ambiguous Wine X11 section" }
        val start = sections.singleOrNull()?.range?.let { it.last + 1 } ?: text.length
        val end = Regex("(?m)^\\[").find(text, start)?.range?.first ?: text.length
        val body = text.substring(start, end)
        val entries = setting.findAll(body).toList()
        val existing = entries.associateBy { match -> names.first { it.equals(match.groupValues[1], true) } }
        check(existing.size == entries.size) { "Ambiguous Wine display defaults" }
        val owned = if (marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists()) {
            val data = JSONObject(read(marker))
            check(data.getInt("version") == 1)
            val array = data.getJSONArray("created")
            (0 until array.length()).map(array::getString).toSet().also { check(names.containsAll(it)) }
        } else emptySet()
        fun value(name: String) = existing[name]?.value?.substringAfter('=')?.trim()
        fun replace(nextBody: String): String = if (sections.isEmpty())
            text.trimEnd() + "\n\n$HEADER\n" + nextBody
        else text.replaceRange(start, end, nextBody)

        if (!pinned) {
            // Only remove values Aura created which are still unchanged. Never erase
            // pre-existing defaults, custom edits, or per-executable AppDefaults.
            val removable = owned.filter { value(it) == "\"Y\"" }.toSet()
            if (removable.isNotEmpty()) write(registry, replace(setting.replace(body) {
                if (removable.any { name -> name.equals(it.groupValues[1], true) }) "" else it.value
            }))
            marker.delete()
            return "WINE_VIRTUAL_MODES_V1 state=RUNTIME_NOT_PINNED; removed_defaults=${removable.size}"
        }

        val missing = names.filter { it !in existing }
        if (missing.isNotEmpty()) {
            // Persist ownership before the registry so an interrupted write is recoverable.
            write(marker, JSONObject().put("version", 1).put("created", JSONArray((owned + missing).toList())).toString())
            write(registry, replace(body.trimEnd() + "\n" + missing.joinToString("") { "\"$it\"=\"Y\"\n" } + "\n"))
        }
        val compatible = names.all { it in missing || value(it)?.let { raw ->
            raw.startsWith('"') && raw.endsWith('"') && raw.getOrNull(1) in listOf('y', 'Y', 't', 'T', '1')
        } == true }
        return "WINE_VIRTUAL_MODES_V1 state=${if (compatible) "DEFAULTS_READY" else "CUSTOM_PRESERVED"}; " +
            "added=${missing.size}; runtime=SHA256_VERIFIED; app_overrides=PRESERVED; game_reset=UNCONFIRMED"
    }

    private fun read(file: AtomicFile): String = file.openRead().use {
        val bytes = it.readNBytes(16 * 1024 * 1024 + 1)
        check(bytes.size <= 16 * 1024 * 1024)
        bytes.toString(Charsets.UTF_8)
    }

    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        check(read(file) == text)
    }
}

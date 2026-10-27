/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import com.winlator.xenvironment.RootFS
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** GDI searches Wine's data directory; DirectWrite resolves registry basenames in Windows/Fonts. */
object WineBuiltinFonts {
    const val SOURCE_DIRECTORY = "opt/wine/share/wine/fonts"
    private val names = setOf("tahoma.ttf", "tahomabd.ttf", "marlett.ttf", "symbol.ttf")

    fun prepare(context: Context, container: File) {
        val files = context.assets.open("arm64ec/manifest.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("files")
        }
        val entries = names.sorted().map { name ->
            val record = files.getJSONObject("$SOURCE_DIRECTORY/$name")
            WineStartupFile(name, record.getLong("bytes"), record.getString("sha256"))
        }
        val repaired = repair(File(RootFS.find(context).rootDir, SOURCE_DIRECTORY), container, entries) { staging, selected ->
            val paths = selected.map { "$SOURCE_DIRECTORY/$it" }.toSet()
            RuntimeAssets.extract(context, "arm64ec/runtime.tzst", staging) { file, _ ->
                val path = file.relativeTo(staging).invariantSeparatorsPath
                if (path in paths) File(staging, file.name) else null
            }
        }
        RuntimeLaunchDiagnostics.detail(context,
            "WINE_BUILTIN_FONTS_V1 checked=${names.size}; repaired=$repaired; source=BUNDLED_SHA256; existing=PRESERVED")
        val registration = WineBuiltinFontRegistry.prepare(File(container, ".wine"))
        RuntimeLaunchDiagnostics.detail(context,
            "WINE_BUILTIN_FONT_REGISTRY_V1 registered=${registration.registered}; retired_external=${registration.retired}; custom=PRESERVED")
    }

    /** Before wineserver starts, repair only absent/broken fonts, never replace a valid user font. */
    @Synchronized
    fun repair(source: File, container: File, entries: List<WineStartupFile>, extract: (File, Set<String>) -> Unit): Int {
        if (entries.size != names.size || entries.map { it.path }.toSet() != names ||
            entries.any { it.bytes <= 0 || !it.sha256.matches(Regex("[0-9a-f]{64}")) })
            throw RuntimeComponentException("WINE_BUILTIN_FONTS", "INVALID_CATALOGUE")
        val windows = directory(container, listOf(".wine", "drive_c", "windows"))
        val fonts = directory(windows, listOf("Fonts"))
        val pending = entries.filter { !isReadableFont(child(fonts, it.path)) }
        if (pending.isEmpty()) return 0
        val staging = Files.createTempDirectory(fonts.toPath(), ".aura-fonts-").toFile()
        try {
            // Use the installed payload when intact; otherwise recover only these data files
            // from the APK, without network access or recreating the user's Wine prefix.
            val invalidSource = WineStartupIntegrity.invalidFiles(source, pending).map { it.path }.toSet()
            for (entry in pending.filter { it.path !in invalidSource })
                File(source, entry.path).copyTo(File(staging, entry.path))
            if (invalidSource.isNotEmpty()) extract(staging, invalidSource)
            if (pending.any { Files.isSymbolicLink(File(staging, it.path).toPath()) } ||
                WineStartupIntegrity.invalidFiles(staging, pending).isNotEmpty() ||
                pending.any { !isReadableFont(File(staging, it.path)) })
                throw RuntimeComponentException("WINE_BUILTIN_FONTS", "REPAIR_VERIFICATION_FAILED")
            var repaired = 0
            for (entry in pending) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val destination = child(directory(container, listOf(".wine", "drive_c", "windows", "Fonts")), entry.path)
                if (isReadableFont(destination)) continue
                if (destination.isDirectory && !Files.isSymbolicLink(destination.toPath()))
                    throw RuntimeComponentException("WINE_BUILTIN_FONTS", "INVALID_FONT_FILE")
                Files.move(File(staging, entry.path).toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                repaired++
            }
            return repaired
        } finally {
            RuntimeTree.delete(staging)
        }
    }

    /** Basic SFNT bounds check, not a rendering test. Preserve readable replacement TrueType/OpenType fonts. */
    fun isReadableFont(file: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        return try {
            RandomAccessFile(file, "r").use { input ->
                val size = input.length()
                if (size < 12 || input.readInt() !in setOf(0x00010000, 0x74727565, 0x4f54544f)) return false
                val count = input.readUnsignedShort()
                val tableEnd = 12L + count * 16L
                if (count !in 1..4096 || tableEnd > size) return false
                val tags = mutableSetOf<Int>()
                input.seek(12)
                repeat(count) {
                    tags += input.readInt()
                    input.readInt() // checksum
                    val offset = input.readInt().toLong() and 0xffffffffL
                    val length = input.readInt().toLong() and 0xffffffffL
                    if (length > 0 && (offset < tableEnd || offset > size || length > size - offset)) return false
                }
                // These tables are required by both TrueType and CFF OpenType fonts.
                tags.containsAll(setOf(0x636d6170, 0x68656164, 0x6d617870, 0x6e616d65))
            }
        } catch (_: IOException) { false }
    }

    private fun child(parent: File, name: String): File {
        val matches = parent.listFiles()?.filter { it.name.equals(name, ignoreCase = true) }.orEmpty()
        if (matches.size > 1) throw RuntimeComponentException("WINE_BUILTIN_FONTS", "AMBIGUOUS_PATH")
        return matches.singleOrNull() ?: File(parent, name)
    }

    private fun directory(root: File, parts: List<String>): File {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath()))
            throw RuntimeComponentException("WINE_BUILTIN_FONTS", "INVALID_DIRECTORY")
        var current = root
        for (part in parts) {
            current = child(current, part)
            if (Files.isSymbolicLink(current.toPath()))
                throw RuntimeComponentException("WINE_BUILTIN_FONTS", "SYMLINK_DIRECTORY")
            if (!current.isDirectory && !current.mkdir())
                throw RuntimeComponentException("WINE_BUILTIN_FONTS", "CREATE_DIRECTORY_FAILED")
        }
        return current
    }
}

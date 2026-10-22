/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

data class WineStartupFile(val path: String, val bytes: Long, val sha256: String)

/** Only the bundled Wine installation is repairable. Prefixes and game files are never targets. */
object WineStartupIntegrity {
    /** The extractor receives only failed catalogue entries; every repair is checked again. */
    fun verifyAndRepair(wineRoot: File, entries: List<WineStartupFile>, extract: (Set<String>) -> Unit): Int {
        val invalid = invalidFiles(wineRoot, entries)
        if (invalid.isEmpty()) return 0
        extract(prepareRepair(wineRoot, invalid))
        if (invalidFiles(wineRoot, entries).isNotEmpty())
            throw RuntimeComponentException("WINE_STARTUP_FILES", "REPAIR_VERIFICATION_FAILED")
        return invalid.size
    }

    fun invalidFiles(wineRoot: File, entries: List<WineStartupFile>): List<WineStartupFile> {
        if (entries.isEmpty() || entries.map { it.path }.distinct().size != entries.size)
            throw RuntimeComponentException("WINE_STARTUP_FILES", "INVALID_CATALOGUE")
        return entries.filter { entry ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val file = target(wineRoot, entry)
            !file.isFile || !file.canRead() || file.length() != entry.bytes ||
                file.canonicalFile.toPath().let { !it.startsWith(wineRoot.canonicalFile.toPath()) } ||
                try { digest(file) != entry.sha256 } catch (_: IOException) { true }
        }
    }

    /** Returns relative file names to let the archive reader extract only damaged builtins. */
    fun prepareRepair(wineRoot: File, invalid: List<WineStartupFile>): Set<String> = invalid.map { entry ->
        val file = target(wineRoot, entry)
        if (Files.isSymbolicLink(file.toPath()) && !file.delete())
            throw RuntimeComponentException("WINE_STARTUP_FILES", "REPAIR_FAILED")
        entry.path
    }.toSet()

    private fun target(wineRoot: File, entry: WineStartupFile): File {
        if (entry.path.startsWith('/') || entry.path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            !entry.path.matches(Regex("[a-zA-Z0-9_./-]+")) || entry.bytes <= 0 ||
            !entry.sha256.matches(Regex("[0-9a-f]{64}")))
            throw RuntimeComponentException("WINE_STARTUP_FILES", "INVALID_CATALOGUE")
        val file = File(wineRoot, entry.path)
        // A symlinked directory could redirect archive writes into a prefix/game/custom install.
        if (!file.parentFile.canonicalFile.toPath().startsWith(wineRoot.canonicalFile.toPath()))
            throw RuntimeComponentException("WINE_STARTUP_FILES", "DIRECTORY_OUTSIDE_WINE")
        return file
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val size = input.read(buffer)
                if (size < 0) break
                sha.update(buffer, 0, size)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Presence only, not PE validity or UI readiness. No file contents or paths enter the report. */
internal object SteamCefFiles {
    fun report(root: Path): String = "STEAM_CEF_FILES_V1 " +
        listOf("win7", "win7x64", "win64").flatMap { variant ->
            listOf("exe" to "steamwebhelper.exe", "dll" to "libcef.dll").map { (key, name) ->
                "${variant}_$key=${state(root, "bin/cef/cef.$variant/$name")}"
            }
        }.joinToString("; ")

    private fun state(root: Path, relative: String): String = try {
        val path = resolve(root, relative)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            !attributes.isRegularFile -> "UNAVAILABLE"
            !Files.isReadable(path) -> "UNAVAILABLE"
            attributes.size() == 0L -> "EMPTY"
            else -> "PRESENT"
        }
    } catch (_: NoSuchFileException) {
        "MISSING"
    } catch (_: IOException) {
        "UNAVAILABLE"
    } catch (_: DirectoryIteratorException) {
        "UNAVAILABLE"
    } catch (_: SecurityException) {
        "UNAVAILABLE"
    }

    internal fun resolve(root: Path, relative: String): Path {
        var path = root
        for (part in relative.split('/')) {
            path = child(path, part)
            if (Files.isSymbolicLink(path)) throw IOException("Symbolic link")
        }
        return path
    }

    /** Match Windows casing, but never enumerate unrelated trees or follow directory links. */
    private fun child(parent: Path, name: String): Path {
        val exact = parent.resolve(name)
        try {
            Files.readAttributes(exact, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return exact
        } catch (_: NoSuchFileException) {
            // Wine accepts differently cased file names on the Linux backing filesystem.
        }
        var match: Path? = null
        Files.newDirectoryStream(parent).use { entries ->
            var count = 0
            for (entry in entries) {
                if (++count > 256) throw IOException("Directory limit")
                if (entry.fileName.toString().equals(name, ignoreCase = true)) {
                    if (match != null) throw IOException("Ambiguous case")
                    match = entry
                }
            }
        }
        return match ?: throw NoSuchFileException(name)
    }
}

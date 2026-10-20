/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** The short files alias lets bundled ELF paths change package without changing string lengths. */
object RuntimeDataPaths {
    @JvmStatic
    @Synchronized
    @Throws(IOException::class)
    fun ensureFilesAlias(dataDirectory: File, filesDirectory: File) {
        val data = dataDirectory.canonicalFile
        val files = filesDirectory.canonicalFile
        if (files.parentFile != data || files.name != "files" || !files.isDirectory) {
            throw IOException("Runtime files directory is outside application data")
        }
        val alias = File(data, "f").toPath()
        if (Files.isSymbolicLink(alias)) {
            if (alias.toFile().canonicalFile == files) return
            // Replace only the link, never its target or an existing directory/file.
            Files.delete(alias)
        } else if (Files.exists(alias, NOFOLLOW_LINKS)) {
            throw IOException("Runtime files alias is occupied")
        }
        Files.createSymbolicLink(alias, files.toPath().fileName)
    }
}

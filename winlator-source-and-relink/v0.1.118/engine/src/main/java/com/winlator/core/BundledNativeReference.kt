/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Trusted APK tar entries refer to code installed by PackageManager, not a second ELF copy. */
object BundledNativeReference {
    const val PREFIX = "aura-native-sha256:"

    @JvmStatic fun isReference(target: String): Boolean = target.startsWith(PREFIX)

    @JvmStatic
    @Throws(IOException::class)
    fun install(root: File, file: File, reference: String, installedDirectory: File, catalogue: Map<String, String>) {
        val digest = reference.removePrefix(PREFIX)
        if (!isReference(reference) || !digest.matches(Regex("[0-9a-f]{64}")))
            throw IOException("Invalid bundled native reference")
        val name = catalogue[digest] ?: throw IOException("Unindexed native reference")
        if (name != "libaura_${digest.take(24)}.so") throw IOException("Unindexed native reference")
        val installed = installedDirectory.toPath().toAbsolutePath().resolve(name)
        if (!Files.isRegularFile(installed, NOFOLLOW_LINKS) || !Files.isReadable(installed) || Files.size(installed) == 0L)
            throw IOException("Missing installed native component: $name")
        val base = root.toPath().toAbsolutePath().normalize()
        val link = file.toPath().toAbsolutePath().normalize()
        if (link == base || !link.startsWith(base) || !Files.isDirectory(base, NOFOLLOW_LINKS))
            throw IOException("Native reference outside extraction directory")
        // Never follow a pre-existing directory link into games, DOS drives or another container.
        var parent = base
        for (part in base.relativize(link.parent)) {
            parent = parent.resolve(part)
            if (!Files.exists(parent, NOFOLLOW_LINKS)) Files.createDirectory(parent)
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Linked native parent directory")
        }
        if (Files.isDirectory(link, NOFOLLOW_LINKS)) throw IOException("Native destination is a directory")
        if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == installed) return
        val temporary = link.resolveSibling(".aura-link-${UUID.randomUUID()}")
        try {
            Files.createSymbolicLink(temporary, installed)
            Files.move(temporary, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** PackageManager changes nativeLibraryDir on APK updates; rootfs must not retain the old path. */
object NativeLibraryLinks {
    @JvmStatic
    fun repair(
        root: File,
        installedDirectory: File,
        bundledNames: Set<String>,
        bundledPaths: Map<String, String> = emptyMap(),
    ): Int {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return 0
        var repaired = 0
        // Files.walk does not follow symlink directories (home links, DOS drives, user files).
        Files.walk(root.toPath()).use { paths ->
            paths.filter { Files.isSymbolicLink(it) }.forEach { link ->
                val previous = Files.readSymbolicLink(link)
                val previousName = previous.fileName?.toString() ?: return@forEach
                // Old rootfs symlinks still point to the previous APK's library prefix.
                val previousBundledName = if (previousName.startsWith(LegacyDataNames.NATIVE_LIBRARY_PREFIX))
                    "libaura_" + previousName.removePrefix(LegacyDataNames.NATIVE_LIBRARY_PREFIX)
                    else previousName
                if (!previous.isAbsolute || !previousBundledName.matches(Regex("libaura_[0-9a-f]{24}\\.so"))) return@forEach
                // A rebuilt library has a NEW content hash. Upgrade only known
                // runtime paths from the manifest; user/container links still
                // require the original hash. Relative aliases remain intact.
                val logicalPath = root.toPath().relativize(link).toString()
                val name = bundledPaths[logicalPath] ?: previousBundledName
                if (name !in bundledNames || !name.matches(Regex("libaura_[0-9a-f]{24}\\.so"))) return@forEach
                val current = File(installedDirectory, name).toPath().toAbsolutePath()
                if (!Files.isRegularFile(current)) throw IOException("Missing installed native component: $name")
                if (previous == current) return@forEach
                val temporary = link.resolveSibling(".aura-link-${UUID.randomUUID()}")
                try {
                    Files.createSymbolicLink(temporary, current)
                    Files.move(temporary, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    repaired++
                } finally { Files.deleteIfExists(temporary) }
            }
        }
        return repaired
    }
}

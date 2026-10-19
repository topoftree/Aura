/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption

/** Never follow DOS drive links when copying, counting or removing a Wine tree. */
object RuntimeTree {
    fun bytes(root: File, include: (String) -> Boolean = { true }): Long {
        if (!root.isDirectory) return 0
        Files.walk(root.toPath()).use { paths ->
            return paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && include(root.toPath().relativize(it).toString()) }
                .mapToLong { Files.size(it) }.sum()
        }
    }

    fun copy(source: File, destination: File, include: (String) -> Boolean = { true }) {
        check(!Files.isSymbolicLink(source.toPath())) { "Runtime source must not be a directory link" }
        Files.walk(source.toPath()).use { paths -> paths.forEach { path ->
            val relative = source.toPath().relativize(path).toString()
            if (relative.isEmpty() || !include(relative)) return@forEach
            val target = destination.toPath().resolve(relative)
            var ancestor = target.parent
            while (ancestor != destination.toPath().parent) {
                check(!Files.isSymbolicLink(ancestor)) { "Runtime destination parent is a link" }
                ancestor = ancestor.parent ?: break
            }
            Files.createDirectories(target.parent)
            when {
                Files.isSymbolicLink(path) -> {
                    Files.deleteIfExists(target)
                    val link = Files.readSymbolicLink(path)
                    // A save link to a location in the old container must refer to the new copy.
                    val relocated = if (link.isAbsolute && link.normalize().startsWith(source.toPath().toAbsolutePath()))
                        target.parent.toAbsolutePath().relativize(destination.toPath().toAbsolutePath().resolve(source.toPath().toAbsolutePath().relativize(link.normalize()))) else link
                    Files.createSymbolicLink(target, relocated)
                }
                Files.isDirectory(path, NOFOLLOW_LINKS) -> Files.createDirectories(target)
                Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                    // Never write through a template symlink into the shared runtime or a user's drive.
                    if (Files.isSymbolicLink(target)) Files.delete(target)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } }
    }

    fun delete(root: File) {
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return
        Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /** A failed copy is never published; the source is never renamed or written. */
    fun migrate(source: File, target: File, prepare: (File) -> Unit, finish: (File) -> Unit) {
        check(!Files.exists(target.toPath(), NOFOLLOW_LINKS)) { "Destination already exists" }
        val staging = File(target.parentFile, ".migrating-${target.name}")
        delete(staging)
        check(staging.mkdirs())
        prepare(staging)
        copy(source, staging, ::migrateEntry)
        finish(staging)
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** Windows binaries come from the new architecture; original files remain in the old container. */
    fun migrateEntry(relative: String): Boolean {
        val path = relative.replace('\\', '/').lowercase()
        return path != ".wine/.update-timestamp" && path != ".wine/dosdevices" &&
            !path.startsWith(".wine/dosdevices/") && path != ".wine/drive_c/windows" &&
            !path.startsWith(".wine/drive_c/windows/") && !path.startsWith(".cache/") &&
            path != ".aura-arm64ec" && path != ".aura-directx"
    }
}

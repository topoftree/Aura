/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Known current-log locations only; never recursively search backups or configuration files. */
object GameEngineLogs {
    const val EXTRA_ROOT = "aura_diagnostic_game_root"
    data class Source(val entry: String, val file: File, val valid: () -> Boolean)

    fun sources(root: File, executable: File): List<Source> = runCatching {
        val fence = root.canonicalFile
        val exe = executable.canonicalFile
        if (!Files.isDirectory(fence.toPath(), NOFOLLOW_LINKS) || !exe.toPath().startsWith(fence.toPath()))
            return emptyList()
        listOf("root" to fence, "executable" to requireNotNull(exe.parentFile)).distinctBy { it.second }
            .flatMap { (location, parent) ->
                listOf("Game.log", "game.log").mapIndexed { index, name ->
                    val file = File(parent, name)
                    Source("game-engine-$location-${index + 1}.log", file) {
                        // Reject a changed parent/symlink instead of following it outside this game.
                        parent.canonicalFile == parent && file.canonicalFile == file &&
                            file.toPath().startsWith(fence.toPath())
                    }
                }
            }
    }.getOrDefault(emptyList())

    fun watch(id: String, root: File, executable: File) {
        sources(root, executable).forEach { source ->
            GameDiagnosticBundle.watch(id, source.entry, source.file, beforeRead = source.valid)
        }
    }
}

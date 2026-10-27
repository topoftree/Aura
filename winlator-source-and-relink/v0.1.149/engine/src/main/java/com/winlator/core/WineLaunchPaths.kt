/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import com.winlator.container.Container
import com.winlator.container.Drive
import java.io.File
import java.io.IOException
import java.nio.file.Path

/** Resolve both sides of Android aliases before choosing a Wine drive. Never guess an unmapped path. */
object WineLaunchPaths {
    @JvmStatic
    fun toDosPath(unixPath: String?, containerRoot: File?, drives: Iterable<Drive>): String {
        val target = canonicalPath(unixPath) ?: return ""
        var bestRoot: Path? = null
        var bestLetter = ""
        fun consider(letter: String, directory: String) {
            if (letter.length != 1 || letter[0].uppercaseChar() !in 'A'..'Z') return
            val root = canonicalPath(directory) ?: return
            // Path.startsWith checks components: games-backup must not match games.
            if (target.startsWith(root) && (bestRoot == null || root.nameCount > bestRoot!!.nameCount)) {
                bestRoot = root
                bestLetter = letter.uppercase(java.util.Locale.ROOT)
            }
        }
        containerRoot?.let { consider("C", File(it, ".wine/drive_c").path) }
        for (drive in drives) consider(drive.letter, drive.path)
        val root = bestRoot ?: return ""
        return "$bestLetter:\\" + root.relativize(target).joinToString("\\")
    }

    /** Add a missing game-library mapping without replacing the user's existing drives. */
    fun withGameDirectory(drives: List<Drive>, directory: File): List<Drive> {
        require(directory.isDirectory) { "未找到游戏目录" }
        val root = requireNotNull(canonicalPath(directory.path)) { "无法读取游戏目录" }
        if (toDosPath(root.toString(), null, drives).isNotEmpty()) return drives
        val used = drives.map { it.letter.uppercase(java.util.Locale.ROOT) }.toSet()
        // C is the Wine prefix; X is the engine's CD-ROM and Z is its root filesystem.
        val letter = (('D'..'W').toList() + 'Y').firstOrNull { it.toString() !in used }
        check(letter != null && drives.size < Container.MAX_DRIVE_LETTERS) {
            "容器没有可用盘符，请在 Winlator 中为游戏目录分配盘符"
        }
        return drives + Drive(letter.toString(), root.toString())
    }

    @JvmStatic
    fun startArguments(unixPath: String?, containerRoot: File?, drives: Iterable<Drive>, arguments: String?): String {
        val path = toDosPath(unixPath, containerRoot, drives)
        require(path.length > 3 && !path.endsWith('\\')) { "无法映射游戏启动路径，请检查容器盘符" }
        require(path.none { it == '"' || it == '\u0000' || it == '\r' || it == '\n' }) { "游戏启动路径含有无效字符" }
        val suffix = arguments?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        if (path.endsWith(".lnk", ignoreCase = true)) return "\"$path\"$suffix"
        // The executable is a file path, not a command line. Keep its name and arguments separate.
        return "/dir ${escapeWorkingDirectory(FileUtils.getDirname(path))} \"${FileUtils.getName(path)}\"$suffix"
    }

    @JvmStatic
    fun escapeWorkingDirectory(parent: String): String {
        // ProcessHelper treats a backslash before a space as an escaped space. D:\. is still
        // the absolute drive root but cannot swallow the separator before the executable.
        val directory = if (parent.endsWith('\\')) "$parent." else parent
        return StringUtils.escapeDOSPath(directory)
    }

    private fun canonicalPath(path: String?): Path? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isAbsolute) return null
        return try {
            file.canonicalFile.toPath()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

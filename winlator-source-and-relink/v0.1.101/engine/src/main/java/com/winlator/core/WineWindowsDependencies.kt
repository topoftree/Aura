/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Versioned Windows PE dependencies; independent of the base runtime/prefix version. */
object WineWindowsDependencies {
    const val VERSION = "icu-68.2"
    private val dlls = setOf("icudt68.dll", "icuin68.dll", "icuuc68.dll")
    private val paths = setOf("system32", "syswow64").flatMap { dir -> dlls.map { "$dir/$it" } }.toSet()

    @JvmStatic
    @Synchronized
    fun prepare(context: Context, container: File) {
        val manifest = context.assets.open("arm64ec/windows-dependencies.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(manifest.getInt("schema") == 1 && manifest.getString("version") == VERSION)
        val files = manifest.getJSONObject("files")
        check(files.keys().asSequence().toSet() == paths + "LICENSE.ICU")
        val entries = paths.sorted().map { path ->
            val record = files.getJSONObject(path)
            WineStartupFile(path, record.getLong("bytes"), record.getString("sha256"))
        }
        val count = repair(container, entries) { staging, selected ->
            RuntimeAssets.extract(context, "arm64ec/windows-dependencies.tzst", staging) { file, _ ->
                file.takeIf { it.relativeTo(staging).invariantSeparatorsPath in selected }
            }
        }
        RuntimeLaunchDiagnostics.detail(context,
            "WINE_WINDOWS_DEPENDENCIES=$VERSION; SHA256_VERIFIED=6; REPAIRED=$count; X64_X86=true")
    }

    /** Run before Wine starts. Stage and verify the entire repair before publishing any DLL. */
    @Synchronized
    fun repair(container: File, entries: List<WineStartupFile>, extract: (File, Set<String>) -> Unit): Int {
        if (entries.size != paths.size || entries.map { it.path }.toSet() != paths)
            throw RuntimeComponentException("WINE_WINDOWS_DEPENDENCIES", "INVALID_CATALOGUE")
        val windows = directory(container, ".wine/drive_c/windows")
        for (dir in listOf("system32", "syswow64")) directory(windows, dir)
        val invalid = (WineStartupIntegrity.invalidFiles(windows, entries) +
            entries.filter { Files.isSymbolicLink(File(windows, it.path).toPath()) }).distinctBy { it.path }
        if (invalid.isEmpty()) return 0
        val staging = Files.createTempDirectory(windows.toPath(), ".aura-dependencies-").toFile()
        try {
            extract(staging, invalid.map { it.path }.toSet())
            for (dir in listOf("system32", "syswow64")) directory(staging, dir)
            if (invalid.any { Files.isSymbolicLink(File(staging, it.path).toPath()) } ||
                WineStartupIntegrity.invalidFiles(staging, invalid).isNotEmpty())
                throw RuntimeComponentException("WINE_WINDOWS_DEPENDENCIES", "REPAIR_VERIFICATION_FAILED")
            for (entry in invalid) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                // Recheck parent directories before committing; replace a leaf symlink itself.
                directory(container, ".wine/drive_c/windows/${entry.path.substringBefore('/')}")
                Files.move(File(staging, entry.path).toPath(), File(windows, entry.path).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        } finally {
            RuntimeTree.delete(staging)
        }
        return invalid.size
    }

    private fun directory(root: File, path: String): File {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath()))
            throw RuntimeComponentException("WINE_WINDOWS_DEPENDENCIES", "INVALID_DIRECTORY")
        var current = root
        for (part in path.split('/')) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath()))
                throw RuntimeComponentException("WINE_WINDOWS_DEPENDENCIES", "SYMLINK_DIRECTORY")
            if (!current.isDirectory && !current.mkdir())
                throw RuntimeComponentException("WINE_WINDOWS_DEPENDENCIES", "CREATE_DIRECTORY_FAILED")
        }
        return current
    }
}

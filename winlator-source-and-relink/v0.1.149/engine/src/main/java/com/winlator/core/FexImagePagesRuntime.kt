/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import com.winlator.xenvironment.RootFS
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Upgrade the bundled translator before verification/launch, including existing installations. */
object FexImagePagesRuntime {
    const val REVISION = "FEX-2610-aura-ip1-smc2-ir1-ca1"
    private const val DIRECTORY = "opt/wine/lib/wine/aarch64-windows"
    val paths = listOf("$DIRECTORY/libarm64ecfex.dll", "$DIRECTORY/libwow64fex.dll")

    fun prepare(context: Context) {
        val manifest = context.assets.open("arm64ec/manifest.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(manifest.getJSONObject("fexImagePages").getString("revision") == REVISION) {
            "FEX 运行库与初始化程序版本不匹配，请安装修复版本"
        }
        val files = manifest.getJSONObject("files")
        val entries = paths.map { path -> files.getJSONObject(path).let {
            WineStartupFile(path, it.getLong("bytes"), it.getString("sha256"))
        } }
        repair(RootFS.find(context).rootDir, entries) { staging ->
            RuntimeAssets.extract(context, "arm64ec/runtime.tzst", staging) { file, _ ->
                file.takeIf { it.relativeTo(staging).invariantSeparatorsPath in paths }
            }
        }
    }

    /** Validate both staged files before replacing either. An incomplete upgrade cannot launch. */
    @Synchronized
    fun repair(root: File, entries: List<WineStartupFile>, extract: (File) -> Unit): Int {
        if (entries.size != paths.size || entries.map { it.path }.toSet() != paths.toSet())
            throw RuntimeComponentException("FEX_IMAGE_PAGES", "INVALID_CATALOGUE")
        requireDirectories(root)
        val invalid = WineStartupIntegrity.invalidFiles(root, entries).map { it.path }.toSet() +
            paths.filter { Files.isSymbolicLink(File(root, it).toPath()) }
        if (invalid.isEmpty()) return 0
        val staging = Files.createTempDirectory(root.toPath(), ".aura-fex-").toFile()
        try {
            extract(staging)
            requireDirectories(staging)
            if (paths.any { Files.isSymbolicLink(File(staging, it).toPath()) } ||
                WineStartupIntegrity.invalidFiles(staging, entries).isNotEmpty())
                throw RuntimeComponentException("FEX_IMAGE_PAGES", "REPAIR_VERIFICATION_FAILED")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            requireDirectories(root)
            for (path in invalid) {
                Files.move(File(staging, path).toPath(), File(root, path).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            if (WineStartupIntegrity.invalidFiles(root, entries).isNotEmpty())
                throw RuntimeComponentException("FEX_IMAGE_PAGES", "REPAIR_VERIFICATION_FAILED")
        } finally {
            RuntimeTree.delete(staging)
        }
        return invalid.size
    }

    private fun requireDirectories(root: File) {
        var directory = root
        for (part in listOf("") + DIRECTORY.split('/')) {
            if (part.isNotEmpty()) directory = File(directory, part)
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()))
                throw RuntimeComponentException("FEX_IMAGE_PAGES", "INVALID_DIRECTORY")
        }
    }
}

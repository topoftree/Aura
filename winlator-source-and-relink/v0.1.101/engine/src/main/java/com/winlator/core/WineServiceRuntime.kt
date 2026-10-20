/* Aura integration changes for 0.1.101, 2026-10-20.
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

/** Update the shared Wine builtin before Wine starts, including existing installs. */
object WineServiceRuntime {
    const val DLL_PATH = "opt/wine/lib/wine/aarch64-windows/sechost.dll"

    fun prepare(context: Context) {
        val record = context.assets.open("arm64ec/manifest.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("files").getJSONObject(DLL_PATH)
        }
        val entry = WineStartupFile(DLL_PATH, record.getLong("bytes"), record.getString("sha256"))
        val updated = repair(RootFS.find(context).rootDir, entry) { staging ->
            RuntimeAssets.extract(context, "arm64ec/runtime.tzst", staging) { file, _ ->
                file.takeIf { it.relativeTo(staging).invariantSeparatorsPath == DLL_PATH }
            }
        }
        RuntimeLaunchDiagnostics.detail(context,
            "WINE_SERVICE_RPC_V1 mode=DIRECT_IMPORT; SHA256_VERIFIED; action=${if (updated) "UPDATED" else "READY"}")
    }

    /** Verify the staged DLL before atomically replacing the managed runtime file. */
    @Synchronized
    fun repair(root: File, entry: WineStartupFile, extract: (File) -> Unit): Boolean {
        if (entry.path != DLL_PATH)
            throw RuntimeComponentException("WINE_SERVICE_RPC", "INVALID_CATALOGUE")
        requireDirectories(root)
        val destination = File(root, DLL_PATH)
        if (!Files.isSymbolicLink(destination.toPath()) &&
            WineStartupIntegrity.invalidFiles(root, listOf(entry)).isEmpty()) return false
        val staging = Files.createTempDirectory(root.toPath(), ".aura-service-").toFile()
        try {
            extract(staging)
            requireDirectories(staging)
            val candidate = File(staging, DLL_PATH)
            if (Files.isSymbolicLink(candidate.toPath()) ||
                WineStartupIntegrity.invalidFiles(staging, listOf(entry)).isNotEmpty())
                throw RuntimeComponentException("WINE_SERVICE_RPC", "REPAIR_VERIFICATION_FAILED")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            requireDirectories(root)
            Files.move(candidate.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            RuntimeTree.delete(staging)
        }
        return true
    }

    private fun requireDirectories(root: File) {
        var directory = root
        for (part in listOf("") + DLL_PATH.substringBeforeLast('/').split('/')) {
            if (part.isNotEmpty()) directory = File(directory, part)
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()))
                throw RuntimeComponentException("WINE_SERVICE_RPC", "INVALID_DIRECTORY")
        }
    }
}

/* Aura integration changes for 0.1.149, 2026-10-27.
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
import java.security.MessageDigest

data class DxvkDllFile(val current: WineStartupFile, val previousSha256: Set<String>)
data class DxvkDllResult(val updated: Int, val verified: Int, val customPreserved: Int)

/** Upgrade a pair of hash-owned DXVK DLLs; preserve unknown and linked user files. */
object DxvkDllUpdate {
    private fun paths(dll: String): Set<String> {
        require(dll in setOf("d3d9", "d3d11", "dxgi"))
        return setOf("system32/$dll.dll", "syswow64/$dll.dll")
    }

    @JvmStatic
    fun prepare(context: Context, container: File, dll: String, revision: String, marker: String, detail: String) {
        val paths = paths(dll)
        val document = context.assets.open("arm64ec/dxvk-$dll.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(document.getInt("schema") == 1 && document.getString("revision") == revision)
        val files = document.getJSONObject("files")
        check(files.keys().asSequence().toSet() == paths)
        val entries = paths.sorted().map { path ->
            val entry = files.getJSONObject(path)
            val previous = entry.getJSONArray("previousSha256")
            DxvkDllFile(WineStartupFile(path, entry.getLong("bytes"), entry.getString("sha256")),
                (0 until previous.length()).map { previous.getString(it) }.toSet())
        }
        val result = repair(container, dll, entries) { staging, selected ->
            RuntimeAssets.extract(context, "arm64ec/directx.tzst", staging) { file, _ ->
                file.takeIf { it.relativeTo(staging).invariantSeparatorsPath in selected }
            }
        }
        RuntimeLaunchDiagnostics.detail(context,
            "$marker revision=$revision; $detail; " +
                "SHA256_VERIFIED=${result.verified}; UPDATED=${result.updated}; CUSTOM_PRESERVED=${result.customPreserved}")
    }

    @Synchronized
    fun repair(container: File, dll: String, entries: List<DxvkDllFile>,
               extract: (File, Set<String>) -> Unit): DxvkDllResult {
        val paths = paths(dll)
        val component = "DXVK_${dll.uppercase(java.util.Locale.ROOT)}_UPDATE"
        val digestPattern = Regex("[0-9a-f]{64}")
        if (entries.size != 2 || entries.map { it.current.path }.toSet() != paths ||
            entries.any { it.current.bytes <= 0 || !it.current.sha256.matches(digestPattern) ||
                it.previousSha256.isEmpty() || it.previousSha256.any { hash -> !hash.matches(digestPattern) } })
            throw RuntimeComponentException(component, "INVALID_CATALOGUE")
        val windows = directory(container, ".wine/drive_c/windows", component)
        for (path in paths) directory(windows, path.substringBefore('/'), component)
        var verified = 0
        var custom = 0
        val pending = entries.filter { entry ->
            val target = File(windows, entry.current.path)
            when {
                Files.isSymbolicLink(target.toPath()) -> { custom++; false }
                !target.exists() -> true
                !target.isFile -> throw RuntimeComponentException(component, "INVALID_TARGET")
                target.length() == 0L -> true
                else -> {
                    val hash = digest(target)
                    when {
                        hash == entry.current.sha256 && target.length() == entry.current.bytes -> { verified++; false }
                        hash in entry.previousSha256 -> true
                        else -> { custom++; false }
                    }
                }
            }
        }
        if (pending.isEmpty()) return DxvkDllResult(0, verified, custom)
        val staging = Files.createTempDirectory(windows.toPath(), ".aura-$dll-").toFile()
        try {
            extract(staging, pending.map { it.current.path }.toSet())
            for (path in paths) directory(staging, path.substringBefore('/'), component)
            val wanted = pending.map { it.current }
            if (wanted.any { Files.isSymbolicLink(File(staging, it.path).toPath()) } ||
                WineStartupIntegrity.invalidFiles(staging, wanted).isNotEmpty())
                throw RuntimeComponentException(component, "REPAIR_VERIFICATION_FAILED")
            for (entry in pending) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                directory(container, ".wine/drive_c/windows/${entry.current.path.substringBefore('/')}", component)
                val target = File(windows, entry.current.path)
                // A concurrent manual change must not be overwritten by a stale selection.
                if (Files.isSymbolicLink(target.toPath()) || (target.exists() &&
                        (!target.isFile || (target.length() > 0 && digest(target) !in entry.previousSha256))))
                    throw RuntimeComponentException(component, "TARGET_CHANGED")
                Files.move(File(staging, entry.current.path).toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        } finally {
            RuntimeTree.delete(staging)
        }
        return DxvkDllResult(pending.size, verified + pending.size, custom)
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun directory(root: File, path: String, component: String): File {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath()))
            throw RuntimeComponentException(component, "INVALID_DIRECTORY")
        var current = root
        for (part in path.split('/')) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath()))
                throw RuntimeComponentException(component, "SYMLINK_DIRECTORY")
            if (!current.isDirectory && !current.mkdir())
                throw RuntimeComponentException(component, "CREATE_DIRECTORY_FAILED")
        }
        return current
    }
}

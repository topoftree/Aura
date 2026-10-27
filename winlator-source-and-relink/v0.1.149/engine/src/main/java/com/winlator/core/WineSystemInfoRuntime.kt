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
import java.security.MessageDigest

data class WineSystemInfoFile(val current: WineStartupFile, val previousSha256: Set<String>)
data class WineSystemInfoResult(val updated: Int, val runtimeVerified: Boolean, val prefixVerified: Boolean, val customPreserved: Int)

/** Apply Wine's architecture-reporting fix before guest startup, including existing prefixes. */
object WineSystemInfoRuntime {
    const val REVISION = "11.0-2-aura-system-info1"
    const val DLL_PATH = "opt/wine/lib/wine/aarch64-windows/kernelbase.dll"
    const val PREFIX_PATH = ".wine/drive_c/windows/system32/kernelbase.dll"
    private const val COMPONENT = "WINE_SYSTEM_INFO"

    fun prepare(context: Context, container: File) {
        val document = context.assets.open("arm64ec/wine-system-info.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(document.getInt("schema") == 1 && document.getString("revision") == REVISION)
        val record = document.getJSONObject("file")
        val previous = record.getJSONArray("previousSha256")
        val entry = WineSystemInfoFile(
            WineStartupFile(record.getString("path"), record.getLong("bytes"), record.getString("sha256")),
            (0 until previous.length()).map { previous.getString(it) }.toSet())
        val result = repair(RootFS.find(context).rootDir, container, entry) { staging ->
            RuntimeAssets.extract(context, "arm64ec/runtime.tzst", staging) { file, _ ->
                file.takeIf { it.relativeTo(staging).invariantSeparatorsPath == DLL_PATH }
            }
        }
        RuntimeLaunchDiagnostics.detail(context, "WINE_SYSTEM_INFO_V1 revision=$REVISION; " +
            "RUNTIME_VERIFIED=${result.runtimeVerified}; PREFIX_VERIFIED=${result.prefixVerified}; " +
            "UPDATED=${result.updated}; CUSTOM_PRESERVED=${result.customPreserved}; mod_compatibility=UNCONFIRMED")
    }

    /** Replace only known builtins or missing files; all other user DLLs remain intact. */
    @Synchronized
    fun repair(root: File, container: File, entry: WineSystemInfoFile, extract: (File) -> Unit): WineSystemInfoResult {
        val digestPattern = Regex("[0-9a-f]{64}")
        if (entry.current.path != DLL_PATH || entry.current.bytes !in 1..16L * 1024 * 1024 ||
            !entry.current.sha256.matches(digestPattern) || entry.previousSha256.isEmpty() ||
            entry.previousSha256.any { !it.matches(digestPattern) })
            throw RuntimeComponentException(COMPONENT, "INVALID_CATALOGUE")
        data class Target(val base: File, val path: String)
        val targets = listOf(Target(root, DLL_PATH), Target(container, PREFIX_PATH))
        fun file(target: Target): File {
            directory(target.base, target.path.substringBeforeLast('/'))
            return File(target.base, target.path)
        }
        fun replaceable(target: Target): Boolean {
            val destination = file(target)
            return !Files.isSymbolicLink(destination.toPath()) && (!destination.exists() ||
                (destination.isFile && (destination.length() == 0L || digest(destination) in entry.previousSha256)))
        }
        val verified = mutableSetOf<Target>()
        var custom = 0
        val pending = targets.filter { target ->
            val destination = file(target)
            when {
                Files.isSymbolicLink(destination.toPath()) -> { custom++; false }
                !destination.exists() -> true
                !destination.isFile -> throw RuntimeComponentException(COMPONENT, "INVALID_TARGET")
                destination.length() == 0L -> true
                else -> when (digest(destination)) {
                    entry.current.sha256 -> {
                        check(destination.length() == entry.current.bytes)
                        verified += target
                        false
                    }
                    in entry.previousSha256 -> true
                    else -> { custom++; false }
                }
            }
        }
        if (pending.isNotEmpty()) {
            val staging = Files.createTempDirectory(root.toPath(), ".aura-system-info-").toFile()
            try {
                extract(staging)
                directory(staging, DLL_PATH.substringBeforeLast('/'))
                val candidate = File(staging, DLL_PATH)
                if (Files.isSymbolicLink(candidate.toPath()) ||
                    WineStartupIntegrity.invalidFiles(staging, listOf(entry.current)).isNotEmpty())
                    throw RuntimeComponentException(COMPONENT, "REPAIR_VERIFICATION_FAILED")
                // Validate both destinations before publishing either one. A partially
                // completed upgrade can be retried after interruption.
                if (pending.any { !replaceable(it) }) throw RuntimeComponentException(COMPONENT, "TARGET_CHANGED")
                for (target in pending) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val destination = file(target)
                    val temporary = Files.createTempFile(destination.parentFile!!.toPath(), ".aura-kernelbase-", ".tmp")
                    try {
                        Files.copy(candidate.toPath(), temporary, REPLACE_EXISTING)
                        if (!replaceable(target)) throw RuntimeComponentException(COMPONENT, "TARGET_CHANGED")
                        Files.move(temporary, destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                        verified += target
                    } finally { Files.deleteIfExists(temporary) }
                }
            } finally { RuntimeTree.delete(staging) }
        }
        return WineSystemInfoResult(pending.size, targets[0] in verified, targets[1] in verified, custom)
    }

    private fun directory(root: File, path: String) {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath()))
            throw RuntimeComponentException(COMPONENT, "INVALID_DIRECTORY")
        var current = root
        for (part in path.split('/')) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) throw RuntimeComponentException(COMPONENT, "SYMLINK_DIRECTORY")
            if (!current.isDirectory && !current.mkdir())
                throw RuntimeComponentException(COMPONENT, "CREATE_DIRECTORY_FAILED")
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/* Aura integration changes for 0.1.118, 2026-10-22.
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

data class WineWaitQueueFile(val current: WineStartupFile, val previousSha256: Set<String>)
data class WineWaitQueueResult(val updated: Int, val runtimeVerified: Int, val prefixVerified: Int, val customPreserved: Int)

/** Update owned PE builtins before Wine starts, preserving unknown replacements and user data. */
object WineWaitQueueRuntime {
    const val REVISION = "11.0-2-aura-wait-completion1"
    const val NATIVE_PATH = "opt/wine/lib/wine/aarch64-unix/ntdll.so"
    private val paths = mapOf(
        "opt/wine/lib/wine/aarch64-windows/ntdll.dll" to "system32/ntdll.dll",
        "opt/wine/lib/wine/i386-windows/ntdll.dll" to "syswow64/ntdll.dll")

    fun prepare(context: Context, container: File) {
        val document = context.assets.open("arm64ec/wine-wait-queue.json").bufferedReader().use { JSONObject(it.readText()) }
        check(document.getInt("schema") == 2 && document.getString("revision") == REVISION)
        val companion = document.getJSONObject("nativeCompanion").let {
            WineStartupFile(it.getString("path"), it.getLong("bytes"), it.getString("sha256"))
        }
        // The i386 DLL uses a new private Unix opcode. Verify the installed APK
        // companion before publishing any PE replacement or starting Wine.
        check(companion.path == NATIVE_PATH)
        NativeRuntime.ensureBundledLibrary(NATIVE_PATH)
        val files = document.getJSONObject("files")
        check(files.keys().asSequence().toSet() == paths.keys)
        val entries = paths.keys.sorted().map { path ->
            val entry = files.getJSONObject(path)
            val previous = entry.getJSONArray("previousSha256")
            WineWaitQueueFile(WineStartupFile(path, entry.getLong("bytes"), entry.getString("sha256")),
                (0 until previous.length()).map { previous.getString(it) }.toSet())
        }
        val root = RootFS.find(context).rootDir
        requireNativeCompanion(root, companion)
        val result = repair(root, container, entries) { staging, selected ->
            RuntimeAssets.extract(context, "arm64ec/runtime.tzst", staging) { file, _ ->
                file.takeIf { it.relativeTo(staging).invariantSeparatorsPath in selected }
            }
        }
        RuntimeLaunchDiagnostics.detail(context, "WINE_WAIT_QUEUE_V1 revision=$REVISION; " +
            "RUNTIME_VERIFIED=${result.runtimeVerified}; PREFIX_VERIFIED=${result.prefixVerified}; " +
            "UPDATED=${result.updated}; CUSTOM_PRESERVED=${result.customPreserved}; game_stall_fix=UNCONFIRMED")
        RuntimeLaunchDiagnostics.detail(context, "WINE_WAIT_COMPLETION_V1 native=SHA256_VERIFIED; " +
            "private_opcode=14; scope=I386_RTL_WAIT_ON_ADDRESS; timeout=UNCHANGED; game_stall_fix=UNCONFIRMED")
        val adapter = document.optJSONObject("mgsTickThunk")
        val breakpoint = document.optJSONObject("arm64ecBreakpoint")
        if (adapter != null || breakpoint != null) {
            val entry = entries.single { it.current.path.endsWith("aarch64-windows/ntdll.dll") }.current
            fun state(file: File) = if (file.isFile && file.length() == entry.bytes &&
                digest(file) == entry.sha256) "SHA256_VERIFIED" else "CUSTOM_OR_UNVERIFIED"
            val runtimeState = state(File(root, entry.path))
            val prefixState = state(File(container, ".wine/drive_c/windows/system32/ntdll.dll"))
            adapter?.let {
                RuntimeLaunchDiagnostics.detail(context, "WINE_MGS_TICK_THUNK_V1 revision=${it.getString("revision")}; " +
                    "runtime=$runtimeState; prefix=$prefixState; " +
                    "activation=PER_GAME_OPT_IN; game_ready=UNCONFIRMED")
            }
            breakpoint?.let {
                RuntimeLaunchDiagnostics.detail(context, "WINE_ARM64EC_BREAKPOINT_V1 revision=${it.getString("revision")}; " +
                    "runtime=$runtimeState; prefix=$prefixState; context_pc_offset=0xf8; " +
                    "scope=LOADER_PROCESS_BREAKPOINT; game_ready=UNCONFIRMED")
            }
        }
    }

    fun requireNativeCompanion(root: File, entry: WineStartupFile) {
        if (entry.path != NATIVE_PATH || entry.bytes <= 0 || !entry.sha256.matches(Regex("[0-9a-f]{64}")))
            throw RuntimeComponentException("WINE_WAIT_QUEUE", "INVALID_NATIVE_COMPANION")
        // Bundled native files intentionally resolve to PackageManager's read-only
        // nativeLibraryDir through the verified NativeRuntime index.
        val file = File(root, entry.path)
        if (!file.isFile || file.length() != entry.bytes || digest(file) != entry.sha256)
            throw RuntimeComponentException("WINE_WAIT_QUEUE", "NATIVE_COMPANION_MISMATCH")
    }

    @Synchronized
    fun repair(root: File, container: File, entries: List<WineWaitQueueFile>,
               extract: (File, Set<String>) -> Unit): WineWaitQueueResult {
        val digestPattern = Regex("[0-9a-f]{64}")
        if (entries.size != 2 || entries.map { it.current.path }.toSet() != paths.keys || entries.any {
                it.current.bytes <= 0 || !it.current.sha256.matches(digestPattern) || it.previousSha256.isEmpty() ||
                    it.previousSha256.any { hash -> !hash.matches(digestPattern) }
            }) throw RuntimeComponentException("WINE_WAIT_QUEUE", "INVALID_CATALOGUE")
        val windows = directory(container, ".wine/drive_c/windows")
        data class Target(val base: File, val path: String, val entry: WineWaitQueueFile, val runtime: Boolean)
        val targets = entries.flatMap { entry -> listOf(
            Target(root, entry.current.path, entry, true), Target(windows, paths.getValue(entry.current.path), entry, false)) }
        fun file(target: Target): File {
            directory(target.base, target.path.substringBeforeLast('/'))
            return File(target.base, target.path)
        }
        fun oldOrMissing(target: Target): Boolean {
            val dest = file(target)
            return !Files.isSymbolicLink(dest.toPath()) && (!dest.exists() ||
                (dest.isFile && (dest.length() == 0L || digest(dest) in target.entry.previousSha256)))
        }
        var runtimeVerified = 0
        var prefixVerified = 0
        var custom = 0
        val pending = targets.filter { target ->
            val dest = file(target)
            when {
                Files.isSymbolicLink(dest.toPath()) -> { custom++; false }
                !dest.exists() -> true
                !dest.isFile -> throw RuntimeComponentException("WINE_WAIT_QUEUE", "INVALID_TARGET")
                dest.length() == 0L -> true
                else -> {
                    val hash = digest(dest)
                    when {
                        hash == target.entry.current.sha256 && dest.length() == target.entry.current.bytes -> {
                            if (target.runtime) runtimeVerified++ else prefixVerified++
                            false
                        }
                        hash in target.entry.previousSha256 -> true
                        else -> { custom++; false }
                    }
                }
            }
        }
        if (pending.isEmpty()) return WineWaitQueueResult(0, runtimeVerified, prefixVerified, custom)
        val staging = Files.createTempDirectory(root.toPath(), ".aura-wait-queue-").toFile()
        try {
            val selected = pending.map { it.entry.current }.distinctBy { it.path }
            extract(staging, selected.map { it.path }.toSet())
            for (entry in selected) {
                directory(staging, entry.path.substringBeforeLast('/'))
                if (Files.isSymbolicLink(File(staging, entry.path).toPath()))
                    throw RuntimeComponentException("WINE_WAIT_QUEUE", "STAGED_SYMLINK")
            }
            if (WineStartupIntegrity.invalidFiles(staging, selected).isNotEmpty())
                throw RuntimeComponentException("WINE_WAIT_QUEUE", "REPAIR_VERIFICATION_FAILED")
            // Validate the entire group before publishing; interrupted upgrades remain retryable.
            if (pending.any { !oldOrMissing(it) }) throw RuntimeComponentException("WINE_WAIT_QUEUE", "TARGET_CHANGED")
            for (target in pending) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val dest = file(target)
                val temporary = Files.createTempFile(dest.parentFile!!.toPath(), ".aura-ntdll-", ".tmp")
                try {
                    Files.copy(File(staging, target.entry.current.path).toPath(), temporary, REPLACE_EXISTING)
                    if (!oldOrMissing(target)) throw RuntimeComponentException("WINE_WAIT_QUEUE", "TARGET_CHANGED")
                    Files.move(temporary, dest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
                if (target.runtime) runtimeVerified++ else prefixVerified++
            }
        } finally { RuntimeTree.delete(staging) }
        return WineWaitQueueResult(pending.size, runtimeVerified, prefixVerified, custom)
    }

    private fun directory(root: File, path: String): File {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath()))
            throw RuntimeComponentException("WINE_WAIT_QUEUE", "INVALID_DIRECTORY")
        var current = root
        for (part in path.split('/')) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) throw RuntimeComponentException("WINE_WAIT_QUEUE", "SYMLINK_DIRECTORY")
            if (!current.isDirectory && !current.mkdir()) throw RuntimeComponentException("WINE_WAIT_QUEUE", "CREATE_DIRECTORY_FAILED")
        }
        return current
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val size = input.read(buffer)
                if (size < 0) break
                md.update(buffer, 0, size)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

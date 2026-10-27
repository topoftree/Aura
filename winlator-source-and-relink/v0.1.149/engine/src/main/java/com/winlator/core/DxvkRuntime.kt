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

/** Switch an entire owned DXVK cohort only after the previous Wine session stopped. */
object DxvkRuntime {
    const val COMPAT_REVISION = "2.6.2-arm64ec-aura-qcom3"
    const val STANDARD_REVISION = "2.7.1-arm64ec-aura-flow1-batch1-init3"
    enum class Profile(val id: String) { STANDARD("standard"), QUALCOMM("qualcomm") }
    val paths: Set<String> = listOf("system32", "syswow64").flatMap { arch ->
        listOf("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi").map { "$arch/$it.dll" }
    }.toSet()
    data class Result(val updated: Int, val verified: Int, val customPreserved: Int)

    @JvmStatic fun select(vulkan: String, driver: String, adrenoModel: Int): Profile =
        if (vulkan == "wrapper" && (driver == LegacyDataNames.QUALCOMM_COMPONENT ||
                (driver.isEmpty() || driver == "System") && adrenoModel > 0)) Profile.QUALCOMM
        else Profile.STANDARD // R8/PROFILED also use Wrapper, but are Mesa drivers.

    fun prepare(context: Context, container: File, profile: Profile, previousGuestsStopped: Boolean): String {
        val document = context.assets.open("arm64ec/dxvk-profiles.json").bufferedReader().use { JSONObject(it.readText()) }
        check(document.getInt("schema") == 1)
        check(document.getString("compatRevision") == COMPAT_REVISION &&
            document.getString("standardRevision") == STANDARD_REVISION)
        val selected = document.getJSONObject("profiles").getJSONObject(profile.id)
        val archive = if (profile == Profile.QUALCOMM) "arm64ec/dxvk-qualcomm.tzst" else "arm64ec/directx.tzst"
        check(selected.getString("archive") == archive)
        val files = selected.getJSONObject("files")
        check(files.keys().asSequence().toSet() == paths)
        val entries = paths.sorted().map { path ->
            val file = files.getJSONObject(path)
            val old = file.getJSONArray("previousSha256")
            DxvkDllFile(WineStartupFile(path, file.getLong("bytes"), file.getString("sha256")),
                (0 until old.length()).map { old.getString(it) }.toSet())
        }
        val result = repair(container, entries, previousGuestsStopped) { staging, wanted ->
            RuntimeAssets.extract(context, archive, staging) { file, _ ->
                file.takeIf { it.relativeTo(staging).invariantSeparatorsPath in wanted }
            }
        }
        val revision = if (result.customPreserved > 0) "CUSTOM_PRESERVED" else
            if (profile == Profile.QUALCOMM) COMPAT_REVISION else STANDARD_REVISION
        RuntimeLaunchDiagnostics.detail(context, "DXVK_RUNTIME_PROFILE_V1 requested=${profile.id}; revision=$revision; " +
            "UPDATED=${result.updated}; SHA256_VERIFIED=${result.verified}; CUSTOM_PRESERVED=${result.customPreserved}; " +
            "cohort=10; gpu_initialization=UNCONFIRMED")
        return revision
    }

    /** All inputs and staged DLLs are checked before any replacement. A failed publication
     * aborts launch; the next attempt recognizes both cohorts and repairs the remainder. */
    @Synchronized
    fun repair(container: File, entries: List<DxvkDllFile>, previousGuestsStopped: Boolean,
               extract: (File, Set<String>) -> Unit): Result {
        val hashPattern = Regex("[0-9a-f]{64}")
        if (entries.size != paths.size || entries.map { it.current.path }.toSet() != paths ||
            entries.any { it.current.bytes <= 0 || !it.current.sha256.matches(hashPattern) ||
                it.previousSha256.isEmpty() || it.previousSha256.any { hash -> !hash.matches(hashPattern) } })
            fail("INVALID_CATALOGUE")
        val windows = directory(container, ".wine/drive_c/windows")
        paths.map { it.substringBefore('/') }.toSet().forEach { directory(windows, it) }
        val before = entries.associate { it.current.path to fingerprint(File(windows, it.current.path)) }
        val custom = entries.count { entry ->
            val old = before.getValue(entry.current.path)
            old != "MISSING" && old != "EMPTY" && old != entry.current.sha256 && old !in entry.previousSha256
        }
        val verified = entries.count { before[it.current.path] == it.current.sha256 &&
            File(windows, it.current.path).length() == it.current.bytes }
        // Never upgrade half a user-customized DXGI/D3D cohort into a different internal ABI.
        if (custom > 0) return Result(0, verified, custom)
        val pending = entries.filter { before[it.current.path] != it.current.sha256 ||
            File(windows, it.current.path).length() != it.current.bytes }
        if (pending.isEmpty()) return Result(0, verified, 0)
        if (!previousGuestsStopped) fail("PREVIOUS_SESSION_ACTIVE")
        val staging = Files.createTempDirectory(windows.toPath(), ".aura-dxvk-profile-").toFile()
        try {
            extract(staging, pending.map { it.current.path }.toSet())
            paths.map { it.substringBefore('/') }.toSet().forEach { directory(staging, it) }
            if (pending.any { Files.isSymbolicLink(File(staging, it.current.path).toPath()) } ||
                WineStartupIntegrity.invalidFiles(staging, pending.map { it.current }).isNotEmpty())
                fail("REPAIR_VERIFICATION_FAILED")
            // Check the whole cohort again, including files that did not need extraction.
            for (entry in entries) {
                directory(container, ".wine/drive_c/windows/${entry.current.path.substringBefore('/')}")
                if (fingerprint(File(windows, entry.current.path)) != before[entry.current.path]) fail("TARGET_CHANGED")
            }
            for (entry in pending) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                directory(container, ".wine/drive_c/windows/${entry.current.path.substringBefore('/')}")
                val target = File(windows, entry.current.path)
                if (fingerprint(target) != before[entry.current.path]) fail("TARGET_CHANGED")
                Files.move(File(staging, entry.current.path).toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            if (WineStartupIntegrity.invalidFiles(windows, entries.map { it.current }).isNotEmpty())
                fail("COHORT_VERIFICATION_FAILED")
        } finally { RuntimeTree.delete(staging) }
        return Result(pending.size, entries.size, 0)
    }

    private fun fingerprint(file: File): String {
        if (Files.isSymbolicLink(file.toPath())) return "SYMLINK"
        if (!file.exists()) return "MISSING"
        if (!file.isFile) fail("INVALID_TARGET")
        if (file.length() == 0L) return "EMPTY"
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

    private fun directory(root: File, path: String): File {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) fail("INVALID_DIRECTORY")
        var current = root
        for (part in path.split('/')) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) fail("SYMLINK_DIRECTORY")
            if (!current.isDirectory && !current.mkdir()) fail("CREATE_DIRECTORY_FAILED")
        }
        return current
    }
    private fun fail(reason: String): Nothing = throw RuntimeComponentException("DXVK_PROFILE", reason)
}

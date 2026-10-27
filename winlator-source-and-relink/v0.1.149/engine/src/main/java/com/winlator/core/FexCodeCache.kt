/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

data class FexCacheResult(val status: String, val directory: File? = null, val dataFileLimitBytes: Long? = null) {
    fun diagnostic() = "FEX_CODE_CACHE=$status" + if (directory == null) "" else
        "; DATA_FILE_LIMIT_BYTES=${dataFileLimitBytes ?: "CUSTOM"}; NAMESPACE_SOFT_LIMIT_MIB=384; TOTAL_SOFT_LIMIT_MIB=768"
}

/** Called after previous guest cleanup and before spawning Wine; never prunes a running cache. */
object FexCodeCache {
    private const val MIB = 1024L * 1024
    private val NAMESPACE = Regex("g[1-9][0-9]*-c[1-9][0-9]*-r[0-9a-f]{16}")
    private const val RELATIVE = ".aura-fex-cache"

    fun runtimeKey(arm64ecHash: String, wow64Hash: String, bridgeHash: String? = null): String {
        val hashes = listOfNotNull(arm64ecHash, wow64Hash, bridgeHash)
        require(hashes.all { it.matches(Regex("[0-9a-f]{64}")) })
        return MessageDigest.getInstance("SHA-256").digest(hashes.joinToString(":").toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun configure(root: File, gameId: Int, containerId: Int, runtimeKey: String, enabled: Boolean,
                  env: EnvVars, previousGuestsStopped: Boolean = true,
                  usableSpace: (File) -> Long = { it.usableSpace }): FexCacheResult {
        // Respect explicit container/shortcut settings, including a user-chosen cache directory.
        if (env.has("FEX_DISKCACHE") || env.has("FEX_DISKCACHEPATH")) return FexCacheResult("CUSTOM")
        if (!enabled) { env.put("FEX_DISKCACHE", "0"); return FexCacheResult("OFF") }
        fun fallback(reason: String): FexCacheResult { env.put("FEX_DISKCACHE", "0"); return FexCacheResult(reason) }
        if (!previousGuestsStopped) return fallback("PREVIOUS_SESSION_ACTIVE")
        if (gameId <= 0 || containerId <= 0 || !runtimeKey.matches(Regex("[0-9a-f]{16}"))) return fallback("INVALID_SCOPE")
        return try {
            // Z: in this runtime must map to the private root, not Android's filesystem root.
            val z = File(root, "home/xuser/.wine/dosdevices/z:")
            if (!Files.isSymbolicLink(z.toPath()) || z.canonicalFile != root.canonicalFile) return fallback("PATH_UNAVAILABLE")
            val base = File(root, RELATIVE)
            if (!safeDirectory(root, base)) return fallback("PATH_UNAVAILABLE")
            val selected = File(base, "g$gameId-c$containerId-r$runtimeKey")
            val siblings = base.listFiles().orEmpty().filter { NAMESPACE.matches(it.name) &&
                Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }.toMutableList()
            if (siblings.size > 256) return fallback("LIMIT_REACHED")
            val sizes = siblings.associateWith { boundedSize(it) }.toMutableMap()
            // A namespace can contain several process buckets and index files in addition to data.
            if ((sizes[selected] ?: 0) > 384 * MIB) {
                removeTree(selected); sizes.remove(selected)
            }
            var total = sizes.values.sum()
            for (candidate in sizes.keys.filter { it != selected }.sortedBy { it.lastModified() }) {
                if (total <= 768 * MIB && usableSpace(base) >= 512 * MIB) break
                removeTree(candidate); total -= sizes.getValue(candidate)
            }
            if (usableSpace(base) < 512 * MIB) return fallback("LOW_SPACE")
            if (!safeDirectory(base, selected)) return fallback("PATH_UNAVAILABLE")
            Files.createTempFile(selected.toPath(), ".probe-", ".tmp").let(Files::delete)
            selected.setLastModified(System.currentTimeMillis())
            val dos = "Z:\\" + selected.relativeTo(root).invariantSeparatorsPath.replace('/', '\\') + "\\"
            env.put("FEX_DISKCACHE", "1").put("FEX_DISKCACHEPATH", dos)
            if (!env.has("FEX_DISKCACHEMAXFILESIZE")) env.put("FEX_DISKCACHEMAXFILESIZE", (128 * MIB).toString())
            // Leave upstream validation, CPU/config hashing, relocation and file mapping intact.
            FexCacheResult("CONFIGURED_NOT_YET_VERIFIED", selected,
                env.get("FEX_DISKCACHEMAXFILESIZE").toLongOrNull()?.takeIf { it > 0 })
        } catch (_: Exception) { fallback("STORAGE_UNAVAILABLE") }
    }

    private fun safeDirectory(ancestor: File, target: File): Boolean {
        var current = ancestor
        for (part in target.relativeTo(ancestor).invariantSeparatorsPath.split('/')) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) return false
            if (!current.isDirectory && !current.mkdir()) return false
        }
        return true
    }

    fun boundedSize(directory: File): Long = Files.walk(directory.toPath()).use { paths ->
        var count = 0
        var bytes = 0L
        paths.forEach { path ->
            check(++count <= 4096)
            if (Files.isRegularFile(path, NOFOLLOW_LINKS)) bytes = Math.addExact(bytes, Files.size(path))
        }
        bytes
    }

    private fun removeTree(directory: File) {
        // Files.walk does not follow symbolic links; deletion affects only this owned cache namespace.
        Files.walk(directory.toPath()).use { paths ->
            val all = paths.limit(4097).toList()
            check(all.size <= 4096)
            all.sortedByDescending { it.nameCount }.forEach(Files::delete)
        }
    }
}

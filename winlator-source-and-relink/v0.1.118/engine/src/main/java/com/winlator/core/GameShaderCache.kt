/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

data class GameShaderCacheResult(val state: String, val directory: File? = null, val existingBytes: Long = 0) {
    fun diagnostic() = "GAME_SHADER_CACHE_V1 state=$state; existing_bytes=$existingBytes; hit_rate=UNCONFIRMED"
}

/** Mesa driver caches and VKD3D shader caches. DXVK 2.7 delegates caching to the Vulkan driver. */
object GameShaderCache {
    private const val MIB = 1024L * 1024
    private val namespace = Regex("g[1-9][0-9]*-c[1-9][0-9]*-[0-9a-f]{32}")

    fun key(runtime: ByteArray, driver: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(runtime)
        return digest.digest(driver.toByteArray()).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun configure(root: File, gameId: Int, containerId: Int, key: String, enabled: Boolean,
                  env: EnvVars, defaultDosCache: String, previousGuestsStopped: Boolean,
                  usableSpace: (File) -> Long = { it.usableSpace }): GameShaderCacheResult {
        if (!enabled) return GameShaderCacheResult("DEFAULT_POLICY")
        // Keep user-selected locations, limits and an explicit cache disable. Never migrate their files.
        if (env.has("MESA_SHADER_CACHE_DIR") || env.get("MESA_SHADER_CACHE_DISABLE").equals("true", true) ||
            env.get("MESA_SHADER_CACHE_DISABLE") == "1" ||
            env.has("VKD3D_SHADER_CACHE_PATH") && env.get("VKD3D_SHADER_CACHE_PATH") != defaultDosCache)
            return GameShaderCacheResult("CUSTOM_PRESERVED")
        if (!previousGuestsStopped) return GameShaderCacheResult("PREVIOUS_SESSION_ACTIVE")
        if (gameId <= 0 || containerId <= 0 || !key.matches(Regex("[0-9a-f]{32}")))
            return GameShaderCacheResult("INVALID_SCOPE")
        return try {
            val z = File(root, "home/xuser/.wine/dosdevices/z:")
            if (!Files.isSymbolicLink(z.toPath()) || z.canonicalFile != root.canonicalFile)
                return GameShaderCacheResult("PATH_UNAVAILABLE")
            val base = File(root, ".aura-shader-cache")
            if (!safeDirectory(base)) return GameShaderCacheResult("PATH_UNAVAILABLE")
            val selected = File(base, "g$gameId-c$containerId-$key")
            val owned = base.listFiles().orEmpty().filter {
                namespace.matches(it.name) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS)
            }
            if (owned.size > 128) return GameShaderCacheResult("LIMIT_REACHED")
            val sizes = owned.associateWith(::bytes).toMutableMap()
            if ((sizes[selected] ?: 0) > 512 * MIB) {
                deleteOwned(selected); sizes.remove(selected)
            }
            var total = sizes.values.sum()
            for (old in sizes.keys.filter { it != selected }.sortedBy { it.lastModified() }) {
                if (total <= 1024 * MIB && usableSpace(base) >= 512 * MIB) break
                deleteOwned(old); total -= sizes.getValue(old)
            }
            if (usableSpace(base) < 512 * MIB) return GameShaderCacheResult("LOW_SPACE_DEFAULT_POLICY")
            if (!safeDirectory(selected)) return GameShaderCacheResult("PATH_UNAVAILABLE")
            val mesa = File(selected, "mesa")
            val vkd3d = File(selected, "vkd3d")
            if (!safeDirectory(mesa) || !safeDirectory(vkd3d)) return GameShaderCacheResult("PATH_UNAVAILABLE")
            Files.createTempFile(selected.toPath(), ".probe", ".tmp").let(Files::delete)
            selected.setLastModified(System.currentTimeMillis())
            val dos = "Z:\\" + vkd3d.relativeTo(root).invariantSeparatorsPath.replace('/', '\\')
            env.put("MESA_SHADER_CACHE_DIR", mesa.path).put("MESA_SHADER_CACHE_DISABLE", "false")
                .put("VKD3D_SHADER_CACHE_PATH", dos)
            if (!env.has("MESA_SHADER_CACHE_MAX_SIZE")) env.put("MESA_SHADER_CACHE_MAX_SIZE", "512MB")
            GameShaderCacheResult("CONFIGURED", selected, sizes[selected] ?: 0)
        } catch (_: Exception) { GameShaderCacheResult("STORAGE_UNAVAILABLE") }
    }

    private fun safeDirectory(file: File): Boolean = !Files.isSymbolicLink(file.toPath()) &&
        (file.isDirectory || file.mkdir())

    fun bytes(directory: File): Long = Files.walk(directory.toPath()).use { paths ->
        var count = 0
        var total = 0L
        paths.forEach {
            check(++count <= 32768)
            if (Files.isRegularFile(it, NOFOLLOW_LINKS)) total = Math.addExact(total, Files.size(it))
        }
        total
    }

    private fun deleteOwned(directory: File) {
        check(namespace.matches(directory.name))
        Files.walk(directory.toPath()).use { paths ->
            val all = paths.limit(32769).toList()
            check(all.size <= 32768)
            all.sortedByDescending { it.nameCount }.forEach(Files::delete)
        }
    }
}

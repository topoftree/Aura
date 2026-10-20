/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Launch-only defaults. Never rewrite a container, save game or custom launch arguments. */
class GameRuntimeProfile private constructor(private val gtfoWorkarounds: Boolean) {
    val id = if (gtfoWorkarounds) "GTFO_LOW_MEMORY_V1" else "STABILITY_V1"

    fun screenSize(configured: String, arguments: String?): String =
        if (configured == "1280x720" && !hasScreenOptions(arguments)) "960x540" else configured

    fun arguments(configured: String?, screenSize: String): String {
        val original = configured.orEmpty()
        // Unity's screen switches are only verified for GTFO. Other engines may reject them.
        if (!gtfoWorkarounds || hasScreenOptions(original)) return original
        val size = Regex("([1-9][0-9]{2,3})x([1-9][0-9]{2,3})").matchEntire(screenSize) ?: return original
        return listOf(original, "-screen-width ${size.groupValues[1]} -screen-height ${size.groupValues[2]}")
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    /** These options are present in our bundled ARM64EC DXVK 2.7.1. Custom versions are left alone. */
    fun applyDxvkDefaults(config: KeyValueSet, version: String): Boolean {
        if (version != Arm64EcRuntime.DXVK) return false
        val defaults = mapOf("framerate" to "30",
            "maxFrameLatency" to "1", "numCompilerThreads" to "2", "trackPipelineLifetime" to "True")
        defaults.forEach { (key, value) -> if (config.get(key).isEmpty()) config.put(key, value) }
        // A smaller advertised VRAM size can make other games fail their minimum requirements.
        if (gtfoWorkarounds && config.get("maxDeviceMemory").isEmpty()) config.put("maxDeviceMemory", "2048")
        return true
    }

    fun diagnostic(screenSize: String, config: KeyValueSet, dxvkDefaults: Boolean): String = buildString {
        append("游戏配置：$id; 屏幕=$screenSize; DXVK 默认配置=$dxvkDefaults")
        if (dxvkDefaults) {
            // Do not log arbitrary configuration or command-line values.
            for (key in listOf("framerate", "maxDeviceMemory", "maxFrameLatency", "numCompilerThreads"))
                append("; $key=${config.get(key).toIntOrNull() ?: "custom"}")
            append("; trackPipelineLifetime=${config.get("trackPipelineLifetime").equals("True", true)}")
        }
    }

    companion object {
        @JvmStatic
        fun forLaunch(gameId: Int, executable: String?, enabled: Boolean): GameRuntimeProfile? =
            if (enabled && gameId > 0 && executable?.endsWith(".exe", ignoreCase = true) == true)
                GameRuntimeProfile(requiresMemoryProtection(gameId, executable)) else null

        /** The early-exit guard remains GTFO-only and independent of the stability switch. */
        @JvmStatic
        fun requiresMemoryProtection(gameId: Int, executable: String?): Boolean =
            gameId == 493520 && executable?.replace('\\', '/')?.substringAfterLast('/')
                .equals("GTFO.exe", ignoreCase = true)

        private fun hasScreenOptions(arguments: String?): Boolean =
            Regex("(?i)(?:^|\\s|\")-screen-(?:width|height|fullscreen)(?:\\s|=|\"|$)")
                .containsMatchIn(arguments.orEmpty())
    }
}

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File

/** A helper-local observer. Never add this preload or Network handle to the game's environment. */
object NetworkNativeProbe {
    const val LIBRARY = "libaura_network_probe.so"
    private const val ENABLED = "AURA_NETWORK_PROBE"
    private const val HANDLE = "AURA_NETWORK_HANDLE"

    fun silence(env: EnvVars) {
        env.remove(ENABLED); env.remove(HANDLE)
        val kept = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != LIBRARY }.joinToString(" ")
        if (kept.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", kept)
    }

    fun environment(original: EnvVars, nativeDirectory: File, networkHandle: Long?): EnvVars =
        EnvVars().also { env ->
            env.putAll(original)
            silence(env)
            val library = File(nativeDirectory, LIBRARY)
            check(library.isFile)
            env.put("LD_PRELOAD", (listOf(library.path) + env.get("LD_PRELOAD").orEmpty()).filter { it.isNotBlank() }.joinToString(" "))
            env.put(ENABLED, "1")
            env.put(HANDLE, networkHandle?.let(java.lang.Long::toUnsignedString) ?: "0")
        }
}

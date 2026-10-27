/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent

/** Optional cache-of-cache; never enables the disk cache or changes generated code. */
object FexCacheBuffer {
    const val REQUEST = "AURA_FEX_CACHE_BUFFER_BYTES"
    const val TARGET = "AURA_FEX_CACHE_BUFFER_EXE"
    data class Memory(val available: Long, val threshold: Long, val low: Boolean)

    fun configure(context: Context, launch: Intent?, cache: FexCacheResult, env: EnvVars): String {
        val options = launch?.let(PerGameLaunch::read) ?: PerGameOptions()
        val memory = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo).let { Memory(it.availMem, it.threshold, it.lowMemory) }
        }.getOrNull()
        return configure(options.codeCacheMemoryMiB,
            launch?.getStringExtra("exec_path")?.replace('\\', '/')?.substringAfterLast('/'),
            cache.status, memory, env)
    }

    fun configure(requestMiB: Int, executable: String?, cacheStatus: String, memory: Memory?, env: EnvVars): String {
        env.remove(REQUEST); env.remove(TARGET)
        val requested = requestMiB.takeIf { it == 32 || it == 64 } ?: 0
        val bytes = requested.toLong() * 1024 * 1024
        val state = when {
            requested == 0 -> "DEFAULT"
            env.has("FEX_DISKCACHEMEMORYSIZE") -> "CUSTOM_PRESERVED"
            cacheStatus != "CONFIGURED_NOT_YET_VERIFIED" -> "CACHE_NOT_MANAGED_OR_OFF"
            executable == null || !executable.endsWith(".exe", true) || executable.length !in 5..1024 ||
                executable.any { it == '/' || it == '\\' || it.isISOControl() } -> "TARGET_UNAVAILABLE"
            memory == null || memory.available < 0 || memory.threshold < 0 -> "MEMORY_UNKNOWN"
            memory.low || memory.available - bytes * 4 < maxOf(512L * 1024 * 1024, memory.threshold) -> "LOW_MEMORY"
            else -> {
                env.put(REQUEST, bytes.toString()); env.put(TARGET, executable)
                "REQUESTED_TARGET_ONLY"
            }
        }
        return "FEX_CACHE_BUFFER_V1 state=$state; requested_mib=$requested; applied=CHECK_NATIVE_AUDIT; restart_required=true"
    }
}

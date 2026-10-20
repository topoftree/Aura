/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Launch-only choices. Do not mutate a container or replace per-game overrides. */
data class RuntimeTuningOptions(
    val codeCache: Boolean = false,
    val compilerThreads: Int = 0,
    val translationStats: Boolean = false,
    val detailedLogs: Boolean = false,
)

object RuntimeTuning {
    const val EXTRA_CACHE = "aura_fex_code_cache"
    const val EXTRA_THREADS = "aura_shader_threads"
    const val EXTRA_STATS = "aura_fex_statistics"
    const val EXTRA_LOGS = "aura_detailed_guest_logs"
    // Keep startup/module/exception errors without enabling every rendering and sync channel.
    const val NORMAL_LOGS = "-all,err+module,err+seh,err+winediag,err+mscoree,err+wineboot,err+environ"
    const val DETAILED_LOGS = "-all,err+all"
    val compilerChoices = listOf(0, 2, 4, 6)

    fun normalized(options: RuntimeTuningOptions) = options.copy(
        compilerThreads = options.compilerThreads.takeIf { it in compilerChoices } ?: 0)

    @JvmStatic
    fun logs(env: EnvVars, detailed: Boolean): String {
        if (!env.has("WINEDEBUG") || env.get("WINEDEBUG") == "-all")
            env.put("WINEDEBUG", if (detailed) DETAILED_LOGS else NORMAL_LOGS)
        return when (env.get("WINEDEBUG")) {
            NORMAL_LOGS -> "STARTUP_ERRORS"
            DETAILED_LOGS -> "ALL_ERRORS"
            else -> "CUSTOM"
        }
    }

    @JvmStatic
    fun dxvk(config: KeyValueSet, threads: Int) {
        if (config.get("version") == Arm64EcRuntime.DXVK && config.get("numCompilerThreads").isEmpty() &&
            threads in compilerChoices && threads > 0) config.put("numCompilerThreads", threads.toString())
    }
}

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

object RuntimeTuning {
    const val EXTRA_STATS = "aura_fex_statistics"
    const val EXTRA_LOGS = "aura_detailed_guest_logs"
    // Keep startup/module/exception errors without enabling every rendering and sync channel.
    const val NORMAL_LOGS = "-all,err+module,err+seh,err+winediag,err+mscoree,err+wineboot,err+environ"
    const val DETAILED_LOGS = "-all,err+all"

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
}

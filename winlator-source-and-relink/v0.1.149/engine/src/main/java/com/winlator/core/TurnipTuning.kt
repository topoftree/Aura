/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Launch copy only. A saved choice is not enough: check the resolved driver after fallback. */
object TurnipTuning {
    const val ALGORITHM = "TU_AUTOTUNE_ALGO"
    const val FLAGS = "TU_AUTOTUNE_FLAGS"

    private fun eligible(intent: Intent?) = intent != null && PerGameLaunch.hasSnapshot(intent) &&
        PerGameLaunch.automaticShaderCache(intent) && intent.getStringExtra(GameGraphicsOverride.DRIVER) == "turnip-26.2.5"

    fun configure(intent: Intent?, env: EnvVars): String {
        val options = intent?.let(PerGameLaunch::read) ?: PerGameOptions()
        if (!eligible(intent)) return "TURNIP_TUNING_V1 state=INACTIVE; reason=OTHER_DRIVER_OR_NON_GAME; saved=UNCHANGED"
        // Control only the exposed choices. Preserve other manual driver flags,
        // and never change the container, TU_DEBUG, synchronization or driver selection.
        if (options.turnipProfiled) env.put(ALGORITHM, "profiled")
        else if (env.get(ALGORITHM) in setOf("profiled", "profiled_imm")) env.remove(ALGORITHM)
        val flags = buildList {
            env.get(FLAGS).split(Regex("[, \\n]+")).filter { it.isNotEmpty() }.forEach {
                // Mesa 26.2.5 accepts these prefixes as 'all'. Its only other flag is big_gmem.
                if (it in setOf("a", "al", "all")) add("big_gmem")
                else if (it !in setOf("preempt_optimize", "tune_small")) add(it)
            }
            if (options.turnipPreempt) add("preempt_optimize")
            if (options.turnipSmallPasses) add("tune_small")
        }.distinct().joinToString(",")
        if (flags.isEmpty()) env.remove(FLAGS) else env.put(FLAGS, flags)
        return "TURNIP_TUNING_V1 state=CONFIGURED; profiled=${options.turnipProfiled}; " +
            "preempt=${options.turnipPreempt}; tune_small=${options.turnipSmallPasses}; " +
            "scope=LAUNCH_ENVIRONMENT; driver_acceptance=UNCONFIRMED; performance=UNCONFIRMED"
    }

    fun cacheIdentity(intent: Intent): String {
        if (!eligible(intent)) return ""
        val options = PerGameLaunch.read(intent)
        return "|turnipTuning1=${if (options.turnipProfiled) 1 else 0}" +
            "${if (options.turnipPreempt) 1 else 0}${if (options.turnipSmallPasses) 1 else 0}"
    }
}

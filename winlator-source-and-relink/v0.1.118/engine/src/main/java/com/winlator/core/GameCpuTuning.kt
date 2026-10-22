/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Per-game Wine/FEX options; availability does not imply a performance gain for every game. */
enum class GameCpuMode(val fastYield: Boolean, val reducedX87: Boolean) {
    // DEFAULT remains neutral for installers and already captured legacy sessions.
    DEFAULT(false, false), WAIT(true, false), X87(false, true), WAIT_AND_X87(true, true), OFF(false, false);

    /** Old inherited preferences adopt the new game default on the next full launch. */
    fun gameChoice() = if (this == DEFAULT) OFF else this

    companion object {
        val choices = listOf(OFF, WAIT, X87, WAIT_AND_X87)
        fun decode(value: String?) = entries.find { it.name == value } ?: DEFAULT
        fun fromFlags(fastYield: Boolean, reducedX87: Boolean) = when {
            fastYield && reducedX87 -> WAIT_AND_X87
            fastYield -> WAIT
            reducedX87 -> X87
            else -> OFF
        }
    }
}

object GameCpuTuning {
    private const val YIELD = "WINE_FAST_YIELD"
    private const val X87 = "FEX_X87REDUCEDPRECISION"

    /** Copy the container environment so subsequent games and resumed sessions stay independent. */
    fun environment(intent: Intent, input: EnvVars): EnvVars = EnvVars().apply {
        putAll(input)
        if (!PerGameLaunch.hasSnapshot(intent)) return@apply
        val mode = PerGameLaunch.read(intent).cpuMode
        if (mode != GameCpuMode.DEFAULT) {
            // OFF must override container values with zero, not inherit enabled switches.
            put(YIELD, if (mode.fastYield) "1" else "0")
            put(X87, if (mode.reducedX87) "1" else "0")
        }
    }

    fun diagnostic(intent: Intent, prepared: EnvVars): String? {
        if (!PerGameLaunch.hasSnapshot(intent)) return null
        val mode = PerGameLaunch.read(intent).cpuMode
        val state = if (mode == GameCpuMode.DEFAULT) "DEFAULTS_PRESERVED" else "CONFIGURED"
        fun value(key: String) = if (!prepared.has(key)) "UNSET" else when (prepared.get(key)) {
            "0" -> "0"
            "1" -> "1"
            else -> "CUSTOM"
        }
        return "GAME_CPU_V1 state=$state; mode=${mode.name}; " +
            "$YIELD=${value(YIELD)}; $X87=${value(X87)}; " +
            "scope=LAUNCH_ENV; experimental=true; performance=UNCONFIRMED"
    }
}

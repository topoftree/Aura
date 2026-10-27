/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Launch-scoped workarounds, independently restricted by the actual executable in FEX/Wine. */
object MgsGroundZeroesCompatibility {
    const val ENVIRONMENT = "FEX_WINDOWSIMAGEPAGES"
    const val TICK_ENVIRONMENT = "AURA_MGS_TICK_THUNK"
    internal const val OEM_TILER_CONFIG = "auraMgsOemTilerMode"
    internal const val OEM_EXECUTABLE_CONFIG = "auraMgsOemExecutable"

    /** A launch-only compatibility trial. The ordinary primary path still uses barriers,
     * fences and real device-loss handling. MSAA can still use secondary commands. */
    @JvmStatic
    fun configureDxvk(intent: Intent, graphics: String, driver: String, adreno: Int,
                      config: KeyValueSet): String? {
        val applicable = enabled(intent) && graphics == "wrapper" && adreno > 0 &&
            (driver.isEmpty() || driver == "System")
        val basename = intent.getStringExtra("exec_path").orEmpty().replace('\\', '/').substringAfterLast('/')
        config.put(OEM_EXECUTABLE_CONFIG, if (applicable) basename else "")
        // Reused containers/launch builders must not carry this into another game or driver.
        config.put(OEM_TILER_CONFIG, if (applicable) "false" else "")
        if (!PerGameLaunch.hasSnapshot(intent) || !supports(intent.getStringExtra("exec_path").orEmpty())) return null
        return "MGS_GZ_OEM_RENDER_PATH_V1 state=${if (applicable) "CONFIGURED" else "OFF"}; " +
            "tiler_mode=${if (applicable) "FALSE" else "DEFAULT"}; scope=PHONE_ADRENO; " +
            "secondary_commands=STILL_POSSIBLE_FOR_MSAA; effective=UNCONFIRMED; game_ready=UNCONFIRMED"
    }

    fun supports(executable: String): Boolean {
        val parts = executable.replace('\\', '/').split('/')
        return parts.none { it == ".." } && parts.last().equals("MgsGroundZeroes.exe", true)
    }

    fun enabled(intent: Intent) = PerGameLaunch.hasSnapshot(intent) &&
        PerGameLaunch.read(intent).metalGearStartup && supports(intent.getStringExtra("exec_path").orEmpty())

    fun environment(intent: Intent, input: EnvVars) = EnvVars().apply {
        putAll(input)
        // A stale container value must not enable a per-game workaround for another launch.
        put(ENVIRONMENT, if (enabled(intent)) "1" else "0")
        put(TICK_ENVIRONMENT, if (enabled(intent)) "1" else "0")
    }

    fun diagnostic(intent: Intent): String? {
        if (!PerGameLaunch.hasSnapshot(intent) || !supports(intent.getStringExtra("exec_path").orEmpty())) return null
        return "MGS_GZ_IMAGE_PAGES_V1 state=${if (enabled(intent)) "CONFIGURED" else "OFF"}; " +
            "scope=MGS_GROUND_ZEROES; protection=COMMITTED_IMAGE_PAGES; game_ready=UNCONFIRMED\n" +
            "MGS_GZ_TICK_THUNK_V1 state=${if (enabled(intent)) "CONFIGURED" else "OFF"}; " +
            "scope=MGS_GROUND_ZEROES; clock=ORIGINAL_GET_TICK_COUNT; activation=GUEST_GATED; game_ready=UNCONFIRMED"
    }
}

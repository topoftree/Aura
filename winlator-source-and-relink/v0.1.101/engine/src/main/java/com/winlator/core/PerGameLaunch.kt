/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Null inherits global/container choices; zero is an explicit automatic/unlimited override. */
data class PerGameOptions(
    val stability: Boolean? = null,
    val screenSize: String? = null,
    val frameRate: Int? = null,
    val esync: Boolean? = null,
    val compilerThreads: Int? = null,
    val singlePlayer: Boolean = false,
    val skipSteamClient: Boolean = false,
    val southParkLowVa: Boolean = false,
    val mirrorsEdgeTime: Boolean = false,
    val stretchDisplay: Boolean = false,
    val mirrorsEdgeSkipStartupMovies: Boolean = false,
    val cpuMode: GameCpuMode = GameCpuMode.DEFAULT,
    val mirrorsEdgeStallCapture: Boolean = false,
)

/** Snapshot helpers only; applying file compatibility belongs to launch preparation. */
object PerGameLaunch {
    private const val OWNER = "aura_options_game_id"
    private const val STABILITY = "aura_options_stability"
    private const val SCREEN = "aura_options_screen"
    private const val FRAME_RATE = "aura_options_frame_rate"
    private const val ESYNC = "aura_options_esync"
    private const val THREADS = "aura_options_threads"
    private const val SINGLE_PLAYER = "aura_options_single_player"
    private const val SKIP_STEAM_CLIENT = "aura_options_skip_steam_client"
    private const val SOUTHPARK_LOW_VA = "aura_options_southpark_low_va"
    private const val MIRRORS_EDGE_TIME = "aura_options_mirrors_edge_time"
    private const val STRETCH_DISPLAY = "aura_options_stretch_display"
    private const val MIRRORS_EDGE_SKIP_STARTUP_MOVIES = "aura_options_mirrors_edge_skip_startup_movies"
    private const val CPU = "aura_options_cpu"
    private const val LEGACY_MIRRORS_EDGE_CPU = "aura_options_mirrors_edge_cpu"
    private const val MIRRORS_EDGE_STALL = "aura_options_mirrors_edge_stall_capture"
    private val keys = listOf(OWNER, STABILITY, SCREEN, FRAME_RATE, ESYNC, THREADS, SINGLE_PLAYER, SKIP_STEAM_CLIENT, SOUTHPARK_LOW_VA, MIRRORS_EDGE_TIME, STRETCH_DISPLAY, MIRRORS_EDGE_SKIP_STARTUP_MOVIES, CPU, LEGACY_MIRRORS_EDGE_CPU, MIRRORS_EDGE_STALL)
    val screenChoices = listOf("960x540", "1280x720", "1600x900", "1920x1080")
    val frameRateChoices = listOf(0, 30, 45, 60, 90, 120)
    val threadChoices = listOf(0, 2, 4, 6)

    fun normalized(value: PerGameOptions) = value.copy(
        screenSize = value.screenSize?.takeIf { it in screenChoices },
        frameRate = value.frameRate?.takeIf { it in frameRateChoices },
        compilerThreads = value.compilerThreads?.takeIf { it in threadChoices },
    )

    fun snapshot(intent: Intent, gameId: Int, value: PerGameOptions) {
        keys.forEach(intent::removeExtra)
        require(gameId > 0 && intent.getIntExtra("aura_game_id", 0) == gameId)
        val options = normalized(value)
        intent.putExtra(OWNER, gameId).putExtra(SINGLE_PLAYER, options.singlePlayer)
            .putExtra(SKIP_STEAM_CLIENT, options.skipSteamClient)
            .putExtra(SOUTHPARK_LOW_VA, options.southParkLowVa)
            .putExtra(MIRRORS_EDGE_TIME, options.mirrorsEdgeTime)
            .putExtra(STRETCH_DISPLAY, options.stretchDisplay)
            .putExtra(MIRRORS_EDGE_SKIP_STARTUP_MOVIES, options.mirrorsEdgeSkipStartupMovies)
            .putExtra(CPU, options.cpuMode.name)
            .putExtra(MIRRORS_EDGE_STALL, options.mirrorsEdgeStallCapture)
        options.stability?.let { intent.putExtra(STABILITY, it) }
        options.screenSize?.let { intent.putExtra(SCREEN, it) }
        options.frameRate?.let { intent.putExtra(FRAME_RATE, it) }
        options.esync?.let { intent.putExtra(ESYNC, it) }
        options.compilerThreads?.let { intent.putExtra(THREADS, it) }
    }

    fun hasSnapshot(intent: Intent): Boolean {
        val owner = intent.getIntExtra(OWNER, 0)
        return owner > 0 && owner == intent.getIntExtra("aura_game_id", 0)
    }

    fun read(intent: Intent): PerGameOptions {
        if (!hasSnapshot(intent)) return PerGameOptions()
        return normalized(PerGameOptions(
            if (intent.hasExtra(STABILITY)) intent.getBooleanExtra(STABILITY, false) else null,
            intent.getStringExtra(SCREEN),
            if (intent.hasExtra(FRAME_RATE)) intent.getIntExtra(FRAME_RATE, -1) else null,
            if (intent.hasExtra(ESYNC)) intent.getBooleanExtra(ESYNC, false) else null,
            if (intent.hasExtra(THREADS)) intent.getIntExtra(THREADS, -1) else null,
            intent.getBooleanExtra(SINGLE_PLAYER, false),
            intent.getBooleanExtra(SKIP_STEAM_CLIENT, false),
            intent.getBooleanExtra(SOUTHPARK_LOW_VA, false),
            intent.getBooleanExtra(MIRRORS_EDGE_TIME, false),
            intent.getBooleanExtra(STRETCH_DISPLAY, false),
            intent.getBooleanExtra(MIRRORS_EDGE_SKIP_STARTUP_MOVIES, false),
            // An explicit new default or invalid value must not revive an old optimization.
            GameCpuMode.decode(intent.getStringExtra(if (intent.hasExtra(CPU)) CPU else LEGACY_MIRRORS_EDGE_CPU)),
            intent.getBooleanExtra(MIRRORS_EDGE_STALL, false),
        ))
    }

    @JvmStatic fun screenSize(intent: Intent): String? = read(intent).screenSize
    @JvmStatic fun stretchDisplay(intent: Intent): Boolean = read(intent).stretchDisplay

    /** Explicit game choices override stability/global defaults in this launch's parsed copy. */
    @JvmStatic fun dxvk(intent: Intent, config: KeyValueSet) {
        if (config.get("version") != Arm64EcRuntime.DXVK) return
        val options = read(intent)
        options.frameRate?.let { config.put("framerate", it.toString()) }
        options.compilerThreads?.let { config.put("numCompilerThreads", it.toString()) }
    }

    fun environment(intent: Intent, input: EnvVars): EnvVars {
        val options = read(intent)
        val prepared = SinglePlayerCompatibility.environment(options.singlePlayer, input).apply {
            options.esync?.let { put("WINEESYNC", if (it) "1" else "0") }
        }
        return GameCpuTuning.environment(intent, prepared)
    }

    fun diagnostic(options: PerGameOptions) = "GAME_OPTIONS_V1 stability=${options.stability ?: "INHERIT"}; " +
        "screen=${options.screenSize ?: "INHERIT"}; fps=${options.frameRate ?: "INHERIT"}; " +
        "esync=${options.esync ?: "INHERIT"}; threads=${options.compilerThreads ?: "INHERIT"}; " +
        "single_player=${options.singlePlayer}; optional_pb_client=${if (options.singlePlayer) "DISABLED" else "UNCHANGED"}; " +
        "skip_steam_client=${options.skipSteamClient}; southpark_low_va=${options.southParkLowVa}; " +
        "mirrors_edge_time=${options.mirrorsEdgeTime}; stretch_display=${options.stretchDisplay}; " +
        "mirrors_edge_skip_startup_movies=${options.mirrorsEdgeSkipStartupMovies}; " +
        "cpu_mode=${options.cpuMode.name}; mirrors_edge_stall_capture=${options.mirrorsEdgeStallCapture}"
}

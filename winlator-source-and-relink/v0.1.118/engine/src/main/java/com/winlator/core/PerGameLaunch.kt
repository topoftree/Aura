/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Null uses the game/container default (code cache defaults off); zero is automatic/unlimited. */
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
    val skipStartupMovies: Boolean = false,
    val cpuMode: GameCpuMode = GameCpuMode.OFF,
    val mirrorsEdgeStallCapture: Boolean = false,
    val metalGearStartup: Boolean = false,
    val managedShaderCache: Boolean = false,
    val codeCache: Boolean? = null,
    val smartPacing: Boolean = true,
    val pauseBackground: Boolean = false,
    val lowLatency: Boolean? = null,
    val vulkanDriver: String? = null,
    val d3d11BatchSubmissions: Boolean = false,
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
    // Purge retired snapshots; a previously saved stretch choice must never affect rendering.
    private const val LEGACY_STRETCH_DISPLAY = "aura_options_stretch_display"
    private const val SKIP_STARTUP_MOVIES = "aura_options_skip_startup_movies"
    private const val LEGACY_MIRRORS_EDGE_SKIP_STARTUP_MOVIES = "aura_options_mirrors_edge_skip_startup_movies"
    private const val CPU = "aura_options_cpu"
    private const val LEGACY_MIRRORS_EDGE_CPU = "aura_options_mirrors_edge_cpu"
    private const val MIRRORS_EDGE_STALL = "aura_options_mirrors_edge_stall_capture"
    private const val METAL_GEAR_STARTUP = "aura_options_metal_gear_startup"
    private const val SHADER_CACHE = "aura_options_shader_cache"
    private const val CODE_CACHE = "aura_options_code_cache"
    private const val SMART_PACING = "aura_options_smart_pacing"
    private const val PAUSE_BACKGROUND = "aura_options_pause_background"
    private const val LOW_LATENCY = "aura_options_low_latency"
    private const val VULKAN_DRIVER = "aura_options_vulkan_driver"
    private const val D3D11_BATCH = "aura_options_d3d11_batch"
    private val keys = listOf(OWNER, STABILITY, SCREEN, FRAME_RATE, ESYNC, THREADS, SINGLE_PLAYER, SKIP_STEAM_CLIENT, SOUTHPARK_LOW_VA, MIRRORS_EDGE_TIME, LEGACY_STRETCH_DISPLAY, SKIP_STARTUP_MOVIES, LEGACY_MIRRORS_EDGE_SKIP_STARTUP_MOVIES, CPU, LEGACY_MIRRORS_EDGE_CPU, MIRRORS_EDGE_STALL, METAL_GEAR_STARTUP,
        SHADER_CACHE, CODE_CACHE, SMART_PACING, PAUSE_BACKGROUND, LOW_LATENCY, VULKAN_DRIVER, D3D11_BATCH,
        GameGraphicsOverride.GRAPHICS, GameGraphicsOverride.CONFIG, GameGraphicsOverride.CACHE_KEY)
    val screenChoices = WineScreenMode.explicitValues
    /** Also retain valid smaller container rectangles captured by an initial game assessment. */
    fun validScreenSize(value: String): Boolean {
        if (value in screenChoices) return true
        val dimensions = value.split('x')
        if (dimensions.size != 2) return false
        val width = dimensions[0].toIntOrNull() ?: return false
        val height = dimensions[1].toIntOrNull() ?: return false
        return width in 320..32766 && height in 160..32766
    }
    val frameRateChoices = listOf(0, 30, 40, 45, 60, 90, 120)
    val threadChoices = listOf(0, 2, 4, 6)
    val vulkanChoices = setOf("auto", "turnip", "panvk-rc3", "turnip-26-r8", "turnip-25-profiled", "qualcomm-v863.1", "system")

    fun normalized(value: PerGameOptions) = value.copy(
        screenSize = value.screenSize?.takeIf(::validScreenSize),
        frameRate = value.frameRate?.takeIf { it in frameRateChoices },
        compilerThreads = value.compilerThreads?.takeIf { it in threadChoices },
        vulkanDriver = value.vulkanDriver?.takeIf { it in vulkanChoices },
    )

    fun snapshot(intent: Intent, gameId: Int, value: PerGameOptions) {
        keys.forEach(intent::removeExtra)
        require(gameId > 0 && intent.getIntExtra("aura_game_id", 0) == gameId)
        val options = normalized(value)
        intent.putExtra(OWNER, gameId).putExtra(SINGLE_PLAYER, options.singlePlayer)
            .putExtra(SKIP_STEAM_CLIENT, options.skipSteamClient)
            .putExtra(SOUTHPARK_LOW_VA, options.southParkLowVa)
            .putExtra(MIRRORS_EDGE_TIME, options.mirrorsEdgeTime)
            .putExtra(SKIP_STARTUP_MOVIES, options.skipStartupMovies)
            .putExtra(CPU, options.cpuMode.name)
            .putExtra(MIRRORS_EDGE_STALL, options.mirrorsEdgeStallCapture)
            .putExtra(METAL_GEAR_STARTUP, options.metalGearStartup)
            .putExtra(SHADER_CACHE, options.managedShaderCache)
            .putExtra(SMART_PACING, options.smartPacing)
            .putExtra(PAUSE_BACKGROUND, options.pauseBackground)
            .putExtra(D3D11_BATCH, options.d3d11BatchSubmissions)
        options.stability?.let { intent.putExtra(STABILITY, it) }
        options.screenSize?.let { intent.putExtra(SCREEN, it) }
        options.frameRate?.let { intent.putExtra(FRAME_RATE, it) }
        options.esync?.let { intent.putExtra(ESYNC, it) }
        options.compilerThreads?.let { intent.putExtra(THREADS, it) }
        options.codeCache?.let { intent.putExtra(CODE_CACHE, it) }
        options.lowLatency?.let { intent.putExtra(LOW_LATENCY, it) }
        options.vulkanDriver?.let { intent.putExtra(VULKAN_DRIVER, it) }
    }

    fun hasSnapshot(intent: Intent): Boolean {
        val owner = intent.getIntExtra(OWNER, 0)
        return owner > 0 && owner == intent.getIntExtra("aura_game_id", 0)
    }

    fun read(intent: Intent): PerGameOptions {
        if (!hasSnapshot(intent)) return PerGameOptions(cpuMode = GameCpuMode.DEFAULT)
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
            intent.getBooleanExtra(if (intent.hasExtra(SKIP_STARTUP_MOVIES)) SKIP_STARTUP_MOVIES
                else LEGACY_MIRRORS_EDGE_SKIP_STARTUP_MOVIES, false),
            // Restore the captured choice, including legacy neutral sessions; do not migrate here.
            GameCpuMode.decode(intent.getStringExtra(if (intent.hasExtra(CPU)) CPU else LEGACY_MIRRORS_EDGE_CPU)),
            intent.getBooleanExtra(MIRRORS_EDGE_STALL, false),
            intent.getBooleanExtra(METAL_GEAR_STARTUP, false),
            intent.getBooleanExtra(SHADER_CACHE, false),
            if (intent.hasExtra(CODE_CACHE)) intent.getBooleanExtra(CODE_CACHE, false) else null,
            intent.getBooleanExtra(SMART_PACING, true),
            intent.getBooleanExtra(PAUSE_BACKGROUND, false),
            if (intent.hasExtra(LOW_LATENCY)) intent.getBooleanExtra(LOW_LATENCY, false) else null,
            intent.getStringExtra(VULKAN_DRIVER),
            intent.getBooleanExtra(D3D11_BATCH, false),
        ))
    }

    @JvmStatic fun screenSize(intent: Intent): String? = read(intent).screenSize

    /** Explicit game choices override stability/global defaults in this launch's parsed copy. */
    @JvmStatic fun dxvk(intent: Intent, config: KeyValueSet) {
        if (config.get("version") != Arm64EcRuntime.DXVK) return
        val options = read(intent)
        options.frameRate?.let { config.put("framerate", it.toString()) }
        options.compilerThreads?.let { config.put("numCompilerThreads", it.toString()) }
        options.lowLatency?.let { config.put("maxFrameLatency", if (it) "1" else "0") }
        if (options.d3d11BatchSubmissions) config.put("auraBatchSubmissions", "true")
        else if (config.get("auraBatchSubmissions").isNotEmpty()) config.put("auraBatchSubmissions", "")
    }

    fun environment(intent: Intent, input: EnvVars): EnvVars {
        val options = read(intent)
        val prepared = SinglePlayerCompatibility.environment(options.singlePlayer, input).apply {
            options.esync?.let { put("WINEESYNC", if (it) "1" else "0") }
        }
        return MgsGroundZeroesCompatibility.environment(intent, GameCpuTuning.environment(intent, prepared))
    }

    fun diagnostic(options: PerGameOptions) = "GAME_OPTIONS_V1 stability=${options.stability ?: "INHERIT"}; " +
        "screen=${options.screenSize ?: "INHERIT"}; fps=${options.frameRate ?: "INHERIT"}; " +
        "esync=${options.esync ?: "INHERIT"}; threads=${options.compilerThreads ?: "INHERIT"}; " +
        "single_player=${options.singlePlayer}; optional_pb_client=${if (options.singlePlayer) "DISABLED" else "UNCHANGED"}; " +
        "skip_steam_client=${options.skipSteamClient}; southpark_low_va=${options.southParkLowVa}; " +
        "mirrors_edge_time=${options.mirrorsEdgeTime}; " +
        "skip_startup_movies=${options.skipStartupMovies}; " +
        "cpu_mode=${options.cpuMode.name}; mirrors_edge_stall_capture=${options.mirrorsEdgeStallCapture}; " +
        "metal_gear_startup=${options.metalGearStartup}; shader_cache=${options.managedShaderCache}; " +
        "code_cache=${options.codeCache == true}; smart_pacing=${options.smartPacing}; " +
        "pause_background=${options.pauseBackground}; low_latency=${options.lowLatency ?: "INHERIT"}; " +
        "vulkan=${options.vulkanDriver ?: "INHERIT"}; d3d11_batch=${options.d3d11BatchSubmissions}"
}

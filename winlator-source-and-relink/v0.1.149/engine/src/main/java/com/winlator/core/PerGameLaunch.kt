/* Aura integration changes for 0.1.149, 2026-10-27.
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
    val metalGearStartup: Boolean = false,
    val codeCache: Boolean? = null,
    val smartPacing: Boolean = false,
    val pauseBackground: Boolean = false,
    val lowLatency: Boolean? = null,
    val vulkanDriver: String? = null,
    val d3d11BatchSubmissions: Boolean = false,
    val powerAdvice: Boolean = false,
    val hardwareTso: Boolean = false,
    val sharedGpu: Boolean = false,
    val crysisFastSmc: Boolean = false,
    val compactTranslation: Boolean = true,
    val turnipProfiled: Boolean = false,
    val turnipPreempt: Boolean = false,
    val turnipSmallPasses: Boolean = false,
    val codeCacheMemoryMiB: Int = 0,
)

/** Snapshot helpers only; applying file compatibility belongs to launch preparation. */
object PerGameLaunch {
    /** Transient launch request, deliberately separate from the saved advice preference. */
    const val AUTO_OPTIMIZE = "aura_auto_optimize"
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
    private const val LEGACY_MIRRORS_EDGE_STALL = "aura_options_mirrors_edge_stall_capture"
    private const val METAL_GEAR_STARTUP = "aura_options_metal_gear_startup"
    private const val LEGACY_SHADER_CACHE = "aura_options_shader_cache"
    private const val AUTOMATIC_SHADER_CACHE = "aura_automatic_shader_cache"
    private const val CODE_CACHE = "aura_options_code_cache"
    private const val SMART_PACING = "aura_options_smart_pacing"
    private const val PAUSE_BACKGROUND = "aura_options_pause_background"
    private const val LOW_LATENCY = "aura_options_low_latency"
    private const val VULKAN_DRIVER = "aura_options_vulkan_driver"
    private const val D3D11_BATCH = "aura_options_d3d11_batch"
    private const val POWER_ADVICE = "aura_options_power_advice"
    private const val HARDWARE_TSO = "aura_options_hardware_tso"
    private const val SHARED_GPU = "aura_options_shared_gpu"
    private const val CRYSIS_FAST_SMC = "aura_options_crysis_fast_smc"
    private const val COMPACT_TRANSLATION = "aura_options_compact_translation"
    private const val TURNIP_PROFILED = "aura_options_turnip_profiled"
    private const val TURNIP_PREEMPT = "aura_options_turnip_preempt"
    private const val TURNIP_SMALL = "aura_options_turnip_small"
    private const val CACHE_MEMORY = "aura_options_cache_memory_mib"
    private val keys = listOf(OWNER, AUTO_OPTIMIZE, STABILITY, SCREEN, FRAME_RATE, ESYNC, THREADS, SINGLE_PLAYER, SKIP_STEAM_CLIENT, SOUTHPARK_LOW_VA, MIRRORS_EDGE_TIME, LEGACY_STRETCH_DISPLAY, SKIP_STARTUP_MOVIES, LEGACY_MIRRORS_EDGE_SKIP_STARTUP_MOVIES, CPU, LEGACY_MIRRORS_EDGE_CPU, LEGACY_MIRRORS_EDGE_STALL, METAL_GEAR_STARTUP,
        LEGACY_SHADER_CACHE, AUTOMATIC_SHADER_CACHE, CODE_CACHE, SMART_PACING, PAUSE_BACKGROUND, LOW_LATENCY, VULKAN_DRIVER, D3D11_BATCH, POWER_ADVICE, HARDWARE_TSO, SHARED_GPU,
        CRYSIS_FAST_SMC, COMPACT_TRANSLATION, TURNIP_PROFILED, TURNIP_PREEMPT, TURNIP_SMALL, CACHE_MEMORY,
        GameGraphicsOverride.GRAPHICS, GameGraphicsOverride.CONFIG, GameGraphicsOverride.CACHE_KEY, GameGraphicsOverride.DRIVER)
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
    val vulkanChoices = setOf("auto", "turnip", "panvk-rc3", "turnip-26.2.5", "turnip-26-r8", "turnip-25-profiled", "qualcomm-v863.1", "system")

    fun normalized(value: PerGameOptions) = value.copy(
        screenSize = value.screenSize?.takeIf(::validScreenSize),
        frameRate = value.frameRate?.takeIf { it in frameRateChoices },
        compilerThreads = value.compilerThreads?.takeIf { it in threadChoices },
        vulkanDriver = value.vulkanDriver?.takeIf { it in vulkanChoices },
        codeCacheMemoryMiB = value.codeCacheMemoryMiB.takeIf { it == 32 || it == 64 } ?: 0,
    )

    fun snapshot(intent: Intent, gameId: Int, value: PerGameOptions, automaticShaderCache: Boolean = true) {
        keys.forEach(intent::removeExtra)
        intent.removeExtra("aura_patch_winmm_native")
        intent.removeExtra(Crysis2SmcCompatibility.EXTRA)
        require(gameId > 0 && intent.getIntExtra("aura_game_id", 0) == gameId)
        val options = normalized(value)
        intent.putExtra(OWNER, gameId).putExtra(SINGLE_PLAYER, options.singlePlayer)
            .putExtra(SKIP_STEAM_CLIENT, options.skipSteamClient)
            .putExtra(SOUTHPARK_LOW_VA, options.southParkLowVa)
            .putExtra(MIRRORS_EDGE_TIME, options.mirrorsEdgeTime)
            .putExtra(SKIP_STARTUP_MOVIES, options.skipStartupMovies)
            .putExtra(CPU, options.cpuMode.name)
            .putExtra(METAL_GEAR_STARTUP, options.metalGearStartup)
            .putExtra(AUTOMATIC_SHADER_CACHE, automaticShaderCache)
            .putExtra(SMART_PACING, options.smartPacing)
            .putExtra(PAUSE_BACKGROUND, options.pauseBackground)
            .putExtra(D3D11_BATCH, options.d3d11BatchSubmissions)
            .putExtra(POWER_ADVICE, options.powerAdvice)
            .putExtra(HARDWARE_TSO, options.hardwareTso)
            .putExtra(SHARED_GPU, options.sharedGpu)
            .putExtra(CRYSIS_FAST_SMC, options.crysisFastSmc)
            .putExtra(COMPACT_TRANSLATION, options.compactTranslation)
            .putExtra(TURNIP_PROFILED, options.turnipProfiled)
            .putExtra(TURNIP_PREEMPT, options.turnipPreempt)
            .putExtra(TURNIP_SMALL, options.turnipSmallPasses)
            .putExtra(CACHE_MEMORY, options.codeCacheMemoryMiB)
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

    /** Only fresh game launches opt into automatic management; installers and old live sessions do not. */
    fun automaticShaderCache(intent: Intent): Boolean = hasSnapshot(intent) &&
        intent.getBooleanExtra(AUTOMATIC_SHADER_CACHE, false)

    /** Only these host policies are mutable in a live session. Preserve every launch/patch extra. */
    fun updateHostPolicies(intent: Intent, smartPacing: Boolean, pauseBackground: Boolean): Boolean {
        if (!hasSnapshot(intent)) return false
        intent.putExtra(SMART_PACING, smartPacing).putExtra(PAUSE_BACKGROUND, pauseBackground)
        return true
    }

    fun read(intent: Intent): PerGameOptions {
        if (!hasSnapshot(intent)) return PerGameOptions(cpuMode = GameCpuMode.DEFAULT, compactTranslation = false)
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
            intent.getBooleanExtra(METAL_GEAR_STARTUP, false),
            if (intent.hasExtra(CODE_CACHE)) intent.getBooleanExtra(CODE_CACHE, false) else null,
            intent.getBooleanExtra(SMART_PACING, false),
            intent.getBooleanExtra(PAUSE_BACKGROUND, false),
            if (intent.hasExtra(LOW_LATENCY)) intent.getBooleanExtra(LOW_LATENCY, false) else null,
            intent.getStringExtra(VULKAN_DRIVER),
            intent.getBooleanExtra(D3D11_BATCH, false),
            intent.getBooleanExtra(POWER_ADVICE, false),
            intent.getBooleanExtra(HARDWARE_TSO, false),
            intent.getBooleanExtra(SHARED_GPU, false),
            intent.getBooleanExtra(CRYSIS_FAST_SMC, false),
            // Old live snapshots retain their original behavior until a full launch.
            intent.getBooleanExtra(COMPACT_TRANSLATION, false),
            intent.getBooleanExtra(TURNIP_PROFILED, false),
            intent.getBooleanExtra(TURNIP_PREEMPT, false),
            intent.getBooleanExtra(TURNIP_SMALL, false),
            intent.getIntExtra(CACHE_MEMORY, 0),
        ))
    }

    @JvmStatic fun screenSize(intent: Intent): String? = read(intent).screenSize
    @JvmStatic fun powerAdvice(intent: Intent): Boolean = read(intent).powerAdvice
    @JvmStatic fun powerMonitoring(intent: Intent): Boolean = hasSnapshot(intent) &&
        (powerAdvice(intent) || intent.getBooleanExtra(AUTO_OPTIMIZE, false))

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
        val prepared = SinglePlayerCompatibility.environment(options.singlePlayer &&
            SinglePlayerCompatibility.supports(intent.getStringExtra("exec_path").orEmpty()), input).apply {
            options.esync?.let { put("WINEESYNC", if (it) "1" else "0") }
            if (hasSnapshot(intent) && intent.getBooleanExtra("aura_patch_winmm_native", false) &&
                MgsGroundZeroesCompatibility.supports(intent.getStringExtra("exec_path").orEmpty())) {
                put("WINEDLLOVERRIDES", mergeWinmmOverride(get("WINEDLLOVERRIDES")))
            }
        }
        return FexSharedExits.environment(intent, Crysis2SmcCompatibility.environment(intent,
            MgsGroundZeroesCompatibility.environment(intent, GameCpuTuning.environment(intent, prepared))))
    }

    private fun mergeWinmmOverride(input: String): String = (input.split(';').mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator < 0) return@mapNotNull entry.takeIf { it.isNotBlank() }
        val modules = entry.substring(0, separator).split(Regex("[, \\t]+"))
            .filter { it.isNotEmpty() && !it.replace('\\', '/').substringAfterLast('/').removePrefix("*")
                .lowercase(java.util.Locale.ROOT).removeSuffix(".dll").equals("winmm") }
        modules.takeIf { it.isNotEmpty() }?.joinToString(",")?.plus(entry.substring(separator))
    } + "winmm,*winmm=n,b").joinToString(";")

    fun diagnostic(options: PerGameOptions) = "GAME_OPTIONS_V1 stability=${options.stability ?: "INHERIT"}; " +
        "screen=${options.screenSize ?: "INHERIT"}; fps=${options.frameRate ?: "INHERIT"}; " +
        "esync=${options.esync ?: "INHERIT"}; threads=${options.compilerThreads ?: "INHERIT"}; " +
        "single_player=${options.singlePlayer}; optional_pb_client=${if (options.singlePlayer) "DISABLED" else "UNCHANGED"}; " +
        "skip_steam_client=${options.skipSteamClient}; southpark_low_va=${options.southParkLowVa}; " +
        "mirrors_edge_time=${options.mirrorsEdgeTime}; " +
        "skip_startup_movies=${options.skipStartupMovies}; " +
        "cpu_mode=${options.cpuMode.name}; " +
        "metal_gear_startup=${options.metalGearStartup}; " +
        "code_cache=${options.codeCache == true}; code_cache_memory_mib=${options.codeCacheMemoryMiB}; smart_pacing=${options.smartPacing}; " +
        "pause_background=${options.pauseBackground}; low_latency=${options.lowLatency ?: "INHERIT"}; " +
        "vulkan=${options.vulkanDriver ?: "INHERIT"}; d3d11_batch=${options.d3d11BatchSubmissions}; power_advice=${options.powerAdvice}; hardware_tso=${options.hardwareTso}; shared_gpu=${options.sharedGpu}; crysis_fast_smc=${options.crysisFastSmc}; compact_translation=${options.compactTranslation}; " +
        "turnip_profiled=${options.turnipProfiled}; turnip_preempt=${options.turnipPreempt}; turnip_small=${options.turnipSmallPasses}"
}

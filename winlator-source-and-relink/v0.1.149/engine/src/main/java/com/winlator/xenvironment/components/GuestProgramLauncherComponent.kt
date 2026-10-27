/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xenvironment.components

import com.winlator.core.*
import com.winlator.xenvironment.EnvironmentComponent
import com.winlator.xenvironment.RootFS
import java.io.File

/** Wine runs as native ARM64; FEX is loaded by Wine's ARM64EC/WOW64 registry, never by Box64. */
class GuestProgramLauncherComponent : EnvironmentComponent() {
    var guestExecutable: String = ""
    var envVars: EnvVars? = null
    var terminationCallback: Callback<Int>? = null
    var beforeGuestStart: Callback<Boolean>? = null
    var dxvkProfile: DxvkRuntime.Profile? = null
    private var waitTrace: UbisoftWaitTrace? = null
    private var startupCapture: StartupExceptionCapture? = null
    private var hardwareTsoSession: HardwareTso.Session? = null
    // Kept only for source compatibility with upstream's prefix-generation helpers.
    var box64Preset: String = "FEXCore"

    override fun start() = synchronized(lock) {
        stop()
        val context = environment.context
        val root = environment.rootFS.rootDir
        val variables = Arm64EcRuntime.environment(context, envVars ?: EnvVars())
        RuntimeLaunchDiagnostics.detail(context, Arm64EcRuntime.verify(context))
        RuntimeComponentFiles.checkBase(root, environment.rootFS.winePath)
        RuntimeLaunchDiagnostics.detail(context, "WINE_PATH_LAYOUT=APK_NATIVE_LOGICAL_V2; NLS=SHA256_VERIFIED")
        val diagnostic = RuntimeLaunchDiagnostics.isCollecting(context)
        val debug = RuntimeDiagnostics.sessionEnabled(context)
        if (diagnostic) {
            // Record the effective sync choice without logging arbitrary environment values.
            val esync = when (variables.get("WINEESYNC")) { "0" -> "0"; "1" -> "1"; else -> "CUSTOM" }
            RuntimeLaunchDiagnostics.detail(context, "GUEST_SYNC WINEESYNC=$esync; WINEFSYNC=0; WINENTSYNC=0")
            for ((name, program) in listOf("PROTON_WINE_ARM64EC" to "wine", "WINESERVER_ARM64" to "wineserver")) {
                RuntimeLaunchDiagnostics.stage(context, "PROBE_$name")
                val result = RuntimeProcessProbe.run(arrayOf(File(root, "opt/wine/bin/$program").path, "--version"), variables, root, 8_000)
                RuntimeLaunchDiagnostics.detail(context, "$name $result")
                if (!result.startsWith("status=0;")) throw RuntimeComponentException(name, "PROBE_FAILED")
            }
        }
        val detailed = (context as? android.app.Activity)?.intent?.getBooleanExtra(RuntimeTuning.EXTRA_LOGS, false) == true
        if (debug) RuntimeLaunchDiagnostics.detail(context, "GUEST_LOGGING=${RuntimeTuning.logs(variables, detailed)}")
        File(root, "tmp/shm").mkdirs()
        val command = if (guestExecutable.startsWith("wine ")) "${root}/opt/wine/bin/$guestExecutable" else guestExecutable
        check(command.isNotBlank()) { "Missing Wine program" }
        val output = if (diagnostic) RuntimeLaunchDiagnostics.startGuestOutput(context) else null
        val session = GuestProcessSession.start(context).also { processes = it }
        val prefix = File(variables.get("WINEPREFIX")).canonicalFile
        val dxvkRevision = dxvkProfile?.let {
            check(prefix.name == ".wine") { "Unexpected Wine prefix" }
            DxvkRuntime.prepare(context, requireNotNull(prefix.parentFile), it, session.previousCleanupComplete)
        } ?: DxvkDxgiUpdate.REVISION
        // Host-managed client metadata must not race an older Wine/Steam session.
        beforeGuestStart?.call(session.previousCleanupComplete)
        val launch = (context as? android.app.Activity)?.intent
        RuntimeLaunchDiagnostics.detail(context, TurnipTuning.configure(launch, variables))
        val hardwareTso = HardwareTso.begin(context, launch, variables).also { hardwareTsoSession = it }
        RuntimeLaunchDiagnostics.detail(context, "GAME_HARDWARE_TSO_V1 state=" +
            (if (hardwareTso != null) "REQUESTED" else "OFF") + "; scope=LAUNCH_REQUEST; performance=UNCONFIRMED")
        val traceProfile = if (debug) RelayTraceProfile.fromIntent(launch) else null
        if (traceProfile != null || WaitTraceRegistry.pending(prefix)) {
            check(session.previousCleanupComplete) { "Previous Wine session must finish before trace configuration" }
            if (WaitTraceRegistry.restore(prefix))
                RuntimeLaunchDiagnostics.detail(context, "WINE_RELAY_TRACE_V1 filters=RECOVERED")
            if (traceProfile != null) {
                waitTrace = UbisoftWaitTrace.start(context, prefix,
                    launch?.getStringExtra(UbisoftClientLogs.EXTRA_EXECUTABLE)?.let(::File),
                    launch?.getIntExtra("container_id", 0) ?: 0, traceProfile)
                // Last override, after all container/custom settings: no inherited unbounded +sync.
                variables.put("WINEDEBUG", traceProfile.channels)
                RuntimeLaunchDiagnostics.detail(context, "${traceProfile.marker} state=CAPTURING; filters=INSTALLED; bounded=true")
            }
        }
        val capture = waitTrace
        val cacheEnabled = launch?.let(PerGameLaunch::read)?.codeCache == true
        val fingerprint = if (cacheEnabled) runCatching {
            val files = context.assets.open("arm64ec/manifest.json").bufferedReader().use {
                org.json.JSONObject(it.readText()).getJSONObject("files") }
            FexCodeCache.runtimeKey(files.getJSONObject("opt/wine/lib/wine/aarch64-windows/libarm64ecfex.dll").getString("sha256"),
                files.getJSONObject("opt/wine/lib/wine/aarch64-windows/libwow64fex.dll").getString("sha256"),
                files.getJSONObject("opt/wine/lib/wine/aarch64-unix/libarm64ecfex.so").getString("sha256"))
        }.getOrDefault("") else ""
        val cache = if (hardwareTso != null) FexCacheResult("OFF_HARDWARE_TSO_EXPERIMENT") else FexCodeCache.configure(root, launch?.getIntExtra("aura_game_id", 0) ?: 0,
            launch?.getIntExtra("container_id", 0) ?: 0, fingerprint, cacheEnabled, variables, session.previousCleanupComplete)
        if (!variables.has("FEX_PROFILESTATS")) variables.put("FEX_PROFILESTATS",
            if (launch?.getBooleanExtra(RuntimeTuning.EXTRA_STATS, false) == true) "1" else "0")
        if (!debug) RuntimeDiagnostics.silence(variables)
        if (diagnostic) session.configureFex(File(root, "tmp"), variables.get("FEX_PROFILESTATS") == "1", cache.directory)
        RuntimeLaunchDiagnostics.detail(context, cache.diagnostic())
        RuntimeLaunchDiagnostics.detail(context, FexCacheBuffer.configure(context, launch, cache, variables))
        val shaderEnabled = launch?.let(PerGameLaunch::automaticShaderCache) == true
        GameShaderCacheMaintenance.beforeLaunch(context, root, launch?.getIntExtra("aura_game_id", 0) ?: 0,
            shaderEnabled, session.previousCleanupComplete)?.let { RuntimeLaunchDiagnostics.detail(context, it.diagnostic()) }
        val shaderKey = if (shaderEnabled) runCatching {
            val driver = requireNotNull(launch?.getStringExtra(GameGraphicsOverride.CACHE_KEY)?.takeIf { it.isNotBlank() })
            val manifest = context.assets.open("arm64ec/manifest.json").use { it.readBytes() }
            GameShaderCache.key(manifest, "$driver;dxvk=$dxvkRevision")
        }.getOrDefault("") else ""
        val shaderCache = GameShaderCache.configure(root, launch?.getIntExtra("aura_game_id", 0) ?: 0,
            launch?.getIntExtra("container_id", 0) ?: 0, shaderKey, shaderEnabled, variables,
            RootFS.getDosUserCachePath(), session.previousCleanupComplete)
        RuntimeLaunchDiagnostics.detail(context, shaderCache.diagnostic())
        RuntimeLaunchDiagnostics.detail(context, "FEX_STATISTICS=" + when (variables.get("FEX_PROFILESTATS")) {
            "1" -> "REQUESTED"; "0" -> "OFF"; else -> "CUSTOM" })
        variables.put("AURA_GUEST_SESSION", session.token)
        val started = ++generation
        RuntimeLaunchDiagnostics.stage(context, "START_GUEST")
        if (debug && traceProfile == null) {
            startupCapture = runCatching {
                StartupExceptionCapture.start(context, launch, prefix,
                    launch?.getStringExtra("aura_launch_id")?.let {
                        RuntimeLaunchDiagnostics.snapshotForBundle(context, it)
                    }.orEmpty())
            }.onFailure {
                RuntimeLaunchDiagnostics.detail(context, "STARTUP_EXCEPTION_CAPTURE_V1 state=PREPARE_FAILED; request=PRESERVED")
            }.getOrNull()
            if (startupCapture != null) {
                // Set before exec, not after a game window or a manual PC probe.
                variables.put("WINEDEBUG", StartupExceptionCapture.CHANNELS)
                RuntimeLaunchDiagnostics.detail(context, "STARTUP_EXCEPTION_CAPTURE_V1 state=RECORDING; " +
                    "scope=NEXT_GAME_LAUNCH; limit_ms=${StartupExceptionCapture.MAX_MILLIS}; relay=OFF; cause=UNCONFIRMED")
            }
        }
        val startup = startupCapture
        StartupCrashTrace.configure(context, variables, startup)
        DxvkDxgiUpdate.configureTrace(variables, startup, launch?.getStringExtra("exec_path"), dxvkRevision)
        val sharedGpu = com.winlator.core.SharedGpuRuntime.observe(variables)
        if (diagnostic) runCatching {
            val id = launch?.getStringExtra("aura_launch_id") ?: return@runCatching
            val gameRoot = launch.getStringExtra(GameEngineLogs.EXTRA_ROOT) ?: return@runCatching
            val executable = launch.getStringExtra("exec_path") ?: return@runCatching
            GameEngineLogs.watch(id, File(gameRoot), File(executable))
        }
        val pid = try { ProcessHelper.exec(command, variables, root, { status ->
            val current = synchronized(lock) { started == generation }
            if (current) {
                shaderCache.directory?.let { directory ->
                    val size = runCatching { GameShaderCache.bytes(directory) }.getOrNull()
                    RuntimeLaunchDiagnostics.detail(context, "GAME_SHADER_CACHE_V1 state=SESSION_ENDED; bytes=${size ?: "UNKNOWN"}; hit_rate=UNCONFIRMED")
                }
                if (diagnostic) RuntimeLaunchDiagnostics.saveGuestOutput(context)
                terminationCallback?.call(status)
            }
        }, if (diagnostic || capture != null || startup != null || hardwareTso != null || sharedGpu != null) Callback { line ->
            if (startup?.crashOutput(line, RuntimeDiagnostics.enabled()) == true) return@Callback
            // Keep native startup rows only in the owning bounded Startup writer. A
            // second generic guest log must not retain rows after it closes.
            if (DxvkDxgiUpdate.isStartupTrace(line)) {
                if (RuntimeDiagnostics.enabled() && DxvkDxgiUpdate.retainOutput(line, startup)) startup?.accept(line)
                return@Callback
            }
            if (synchronized(lock) { started == generation })
                sharedGpu?.accept(line)?.let { RuntimeLaunchDiagnostics.detail(context, it) }
            hardwareTso?.accept(line)?.let { RuntimeLaunchDiagnostics.detail(context, it) }
            if (RuntimeDiagnostics.enabled()) { capture?.accept(line); startup?.accept(line) }
            if (output != null) RuntimeLaunchDiagnostics.acceptGuestOutput(context, output, line)
        } else null,
            session::attachProcess)
        } catch (error: Exception) { startup?.finish("LAUNCH_FAILED"); throw error }
        if (pid > 0) {
            session.attachRoot(pid)
            if (debug) launch?.getStringExtra("aura_launch_id")?.let { id ->
                NetworkDiagnosticCapture.configure(id, root, variables)
                startup?.startNetwork(context, id)
            }
        }
        else startup?.finish("LAUNCH_FAILED")
        Unit
    }

    override fun stop() = synchronized(lock) {
        NetworkDiagnosticCapture.discard()
        hardwareTsoSession?.close()
        hardwareTsoSession = null
        RuntimeLaunchDiagnostics.saveGuestOutput(environment.context)
        generation++
        startupCapture?.finish("SESSION_CLOSED")
        val cleanup = processes?.stop()
        cleanup?.let { RuntimeLaunchDiagnostics.guestCleanup(environment.context, it) }
        processes = null
        val launch = (environment.context as? android.app.Activity)?.intent
        val report = launch?.getStringExtra("aura_launch_id")?.let {
            RuntimeLaunchDiagnostics.snapshotForBundle(environment.context, it)
        }
        startupCapture?.finish("SESSION_CLOSED", report)
        startupCapture = null
        waitTrace?.finish(environment.context, cleanup != null && cleanup.remaining == 0 && cleanup.unreadable == 0)
        waitTrace = null
    }
    override fun onPause() = synchronized(lock) {
        // Retire startup requests before pausing Wine; buffered native rows must not
        // be attached to a resumed capture or a different foreground session.
        startupCapture?.finish("PAUSED")
        processes?.pause()?.let { RuntimeLaunchDiagnostics.detail(environment.context, it.report("PAUSE")) }
        Unit
    }
    override fun onResume() = synchronized(lock) {
        processes?.resume()?.let { RuntimeLaunchDiagnostics.detail(environment.context, it.report("RESUME")) }
        Unit
    }
    companion object {
        private val lock = Any()
        private var generation = 0L
        private var processes: GuestProcessSession? = null
    }
}

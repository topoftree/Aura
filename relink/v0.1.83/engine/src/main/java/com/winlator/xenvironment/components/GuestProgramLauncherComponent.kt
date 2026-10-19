/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.xenvironment.components

import com.winlator.core.*
import com.winlator.xenvironment.EnvironmentComponent
import java.io.File

/** Wine runs as native ARM64; FEX is loaded by Wine's ARM64EC/WOW64 registry, never by Box64. */
class GuestProgramLauncherComponent : EnvironmentComponent() {
    var guestExecutable: String = ""
    var envVars: EnvVars? = null
    var terminationCallback: Callback<Int>? = null
    private var waitTrace: UbisoftWaitTrace? = null
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
        val launch = (context as? android.app.Activity)?.intent
        val tracing = debug && launch?.getBooleanExtra(UbisoftWaitTrace.EXTRA, false) == true
        val prefix = File(variables.get("WINEPREFIX")).canonicalFile
        if (tracing || WaitTraceRegistry.pending(prefix)) {
            check(session.previousCleanupComplete) { "Previous Wine session must finish before trace configuration" }
            if (WaitTraceRegistry.restore(prefix))
                RuntimeLaunchDiagnostics.detail(context, "UBISOFT_WAIT_TRACE_V1 filters=RECOVERED")
            if (tracing) {
                waitTrace = UbisoftWaitTrace.start(context, prefix,
                    launch.getStringExtra(UbisoftClientLogs.EXTRA_EXECUTABLE)?.let(::File),
                    launch.getIntExtra("container_id", 0))
                // Last override, after all container/custom settings: no inherited unbounded +sync.
                variables.put("WINEDEBUG", UbisoftWaitTrace.CHANNELS)
                RuntimeLaunchDiagnostics.detail(context, "UBISOFT_WAIT_TRACE_V1 state=CAPTURING; filters=INSTALLED; bounded=true")
            }
        }
        val capture = waitTrace
        val cacheEnabled = launch?.getBooleanExtra(RuntimeTuning.EXTRA_CACHE, false) == true
        val fingerprint = if (cacheEnabled) runCatching {
            val files = context.assets.open("arm64ec/manifest.json").bufferedReader().use {
                org.json.JSONObject(it.readText()).getJSONObject("files") }
            FexCodeCache.runtimeKey(files.getJSONObject("opt/wine/lib/wine/aarch64-windows/libarm64ecfex.dll").getString("sha256"),
                files.getJSONObject("opt/wine/lib/wine/aarch64-windows/libwow64fex.dll").getString("sha256"))
        }.getOrDefault("") else ""
        val cache = FexCodeCache.configure(root, launch?.getIntExtra("aura_game_id", 0) ?: 0,
            launch?.getIntExtra("container_id", 0) ?: 0, fingerprint, cacheEnabled, variables, session.previousCleanupComplete)
        if (!variables.has("FEX_PROFILESTATS")) variables.put("FEX_PROFILESTATS",
            if (launch?.getBooleanExtra(RuntimeTuning.EXTRA_STATS, false) == true) "1" else "0")
        if (!debug) RuntimeDiagnostics.silence(variables)
        if (diagnostic) session.configureFex(File(root, "tmp"), variables.get("FEX_PROFILESTATS") == "1", cache.directory)
        RuntimeLaunchDiagnostics.detail(context, cache.diagnostic())
        RuntimeLaunchDiagnostics.detail(context, "FEX_STATISTICS=" + when (variables.get("FEX_PROFILESTATS")) {
            "1" -> "REQUESTED"; "0" -> "OFF"; else -> "CUSTOM" })
        variables.put("AURA_GUEST_SESSION", session.token)
        val started = ++generation
        RuntimeLaunchDiagnostics.stage(context, "START_GUEST")
        val pid = ProcessHelper.exec(command, variables, root, { status ->
            val current = synchronized(lock) { started == generation }
            if (current) {
                if (diagnostic) RuntimeLaunchDiagnostics.saveGuestOutput(context)
                terminationCallback?.call(status)
            }
        }, if (diagnostic || capture != null) Callback { line ->
            if (output != null) RuntimeLaunchDiagnostics.acceptGuestOutput(context, output, line)
            if (RuntimeDiagnostics.enabled()) capture?.accept(line)
        } else null,
            session::attachProcess)
        if (pid > 0) session.attachRoot(pid)
    }

    override fun stop() = synchronized(lock) {
        RuntimeLaunchDiagnostics.saveGuestOutput(environment.context)
        generation++
        val cleanup = processes?.stop()
        cleanup?.let { RuntimeLaunchDiagnostics.guestCleanup(environment.context, it) }
        processes = null
        waitTrace?.finish(environment.context, cleanup != null && cleanup.remaining == 0 && cleanup.unreadable == 0)
        waitTrace = null
    }
    override fun onPause() = synchronized(lock) {
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

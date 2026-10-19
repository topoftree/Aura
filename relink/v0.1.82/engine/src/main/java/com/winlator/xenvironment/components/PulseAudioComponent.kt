/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.xenvironment.components

import android.util.Log
import com.winlator.core.EnvVars
import com.winlator.core.ProcessHelper
import com.winlator.core.RuntimeAssets
import com.winlator.core.RuntimeComponentFiles
import com.winlator.core.RuntimeLaunchDiagnostics
import com.winlator.core.RuntimeDiagnostics
import com.winlator.core.Callback
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.EnvironmentComponent
import java.io.File

/** The server's Pulse 13 libraries stay in their own process; Wine uses Pulse 17's protocol client. */
class PulseAudioComponent(private val socketConfig: UnixSocketConfig) : EnvironmentComponent() {
    @Volatile private var process: Process? = null
    var volume: Float = 1f
    var performanceMode: Int = 1

    override fun start() {
        stop()
        val context = environment.context
        val native = context.applicationInfo.nativeLibraryDir
        val working = File(context.filesDir, "pulseaudio")
        check(working.isDirectory || working.mkdirs()) { "Cannot create PulseAudio directory" }
        RuntimeAssets.extract(context, "pulseaudio.tzst", working)
        RuntimeComponentFiles.requireFiles(working, "PULSEAUDIO", "modules/module-native-protocol-unix.so",
            "modules/module-aaudio-sink.so", "modules/libprotocol-native.so")
        val modules = File(working, "modules")
        val socket = File(socketConfig.path)
        check(!socket.exists() || socket.delete()) { "Cannot remove stale PulseAudio socket" }
        // Open the client socket only after the sink loaded successfully. A socket
        // created before a failing sink is not a valid readiness signal.
        File(working, "default.pa").writeText("""
            load-module module-aaudio-sink volume=${volume.coerceIn(0f, 1f)} performance_mode=${performanceMode.coerceIn(0, 2)}
            set-default-sink AAudioSink
            load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket="${socket.path}"
        """.trimIndent() + "\n")
        val variables = EnvVars().put("LD_LIBRARY_PATH", "$native:${modules.path}:/system/lib64")
            .put("HOME", working).put("TMPDIR", environment.tmpDir).put("PULSE_MODDIR", modules)
        try {
            val command = "$native/libpulseaudio.so --system=false --disable-shm=true --fail=true " +
                "--dl-search-path=${modules.path} -n --file=default.pa --daemonize=false " +
                "--use-pid-file=false --exit-idle-time=-1"
            val output = if (RuntimeDiagnostics.sessionEnabled(context))
                Callback<String> { line -> Log.i("AuraPulseAudio", line.take(512)) } else null
            val pid = ProcessHelper.exec(command, variables, working, null, output, { started -> process = started })
            check(pid > 0 && process != null) { "PulseAudio process could not start" }
            val deadline = System.nanoTime() + 3_000_000_000L
            while (process?.isAlive == true && !socket.exists() && System.nanoTime() < deadline) Thread.sleep(20)
            check(process?.isAlive == true && socket.exists()) { "PulseAudio server did not become ready" }
            RuntimeLaunchDiagnostics.detail(context, "PULSEAUDIO_SERVER=READY; OUTPUT=AAUDIO")
        } catch (error: Exception) {
            stop()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw IllegalStateException("PulseAudio startup failed", error)
        }
    }

    override fun stop() {
        // Keep the Process handle: a recycled numeric PID must never kill a later process.
        val owned = process
        process = null
        owned?.destroyForcibly()
        if (owned != null) {
            try { owned.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            File(socketConfig.path).delete()
        }
    }
}

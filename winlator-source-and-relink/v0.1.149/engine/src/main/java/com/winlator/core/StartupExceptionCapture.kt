/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in, next-launch capture. The writer exists before Wine starts, including before any X window. */
class StartupExceptionCapture private constructor(
    private val directory: File, private val metadata: JSONObject,
    private val clock: () -> Long, private val maxBytes: Int,
) {
    private val output = File(directory, "guest-startup.log").bufferedWriter()
    private val startedElapsed = clock()
    private var bytes = 0
    private val nativeFaults = StartupNativeFaults()
    private var crashTrace: StartupCrashTrace? = null
    private var pipelineTrace: StartupVulkanTrace? = null
    private var closed = false
    private var announced = false
    private var observer: AutoCloseable? = null
    private var timeoutHandler: Handler? = null
    private val timeout = Runnable { finish("TIME_LIMIT") }

    val id: String get() = metadata.getString("id")

    fun configureCrashTrace(library: File, env: EnvVars, wineRoot: File? = null): Unit = synchronized(lock) {
        if (closed || active !== this || !recording() || crashTrace != null) return@synchronized
        pipelineTrace = StartupVulkanTrace(wineRoot, id, startedElapsed + MAX_MILLIS).also { it.configure(env) }
        crashTrace = StartupCrashTrace(directory, startedElapsed + MAX_MILLIS).also { it.configure(library, env) }
    }

    /** DXVK error output is classified here and must never bypass into another log. */
    fun crashOutput(line: String, collect: Boolean = true): Boolean = synchronized(lock) {
        if (crashTrace == null || !StartupCrashTrace.isGraphicsOutput(line)) return@synchronized false
        if (collect && recording()) crashTrace?.observe(line, clock())
        true
    }

    private fun saveCrashTrace() {
        if (runCatching { atomic(File(directory, "vulkan-fault.txt"), NativeGraphicsTrace.faultReport(metadata.getString("session"))) }.isFailure)
            metadata.put("vulkan_fault_save_failed", true)
        pipelineTrace?.let {
            if (runCatching { atomic(File(directory, "vulkan-pipeline.txt"), it.report()) }.isFailure)
                metadata.put("pipeline_context_save_failed", true)
        }
        crashTrace?.let {
            // Auxiliary failure must not prevent saving the original startup log/metadata.
            if (runCatching { atomic(File(directory, "startup-crash.txt"), it.report()) }.isFailure)
                metadata.put("native_context_save_failed", true)
        }
    }

    fun recording(): Boolean = synchronized(lock) {
        if (!closed && clock() - startedElapsed >= MAX_MILLIS) finish("TIME_LIMIT")
        !closed && active === this
    }

    /** Strictly session-owned fixed-field diagnostics; never attach a later capture's results. */
    fun networkCheckpoint(report: String): Unit = synchronized(lock) {
        if (closed || active !== this) return@synchronized
        require(report.length <= 32 * 1024 && report.startsWith("NETWORK_CONTROL_V1 "))
        atomic(File(directory, "network-control.txt"), report)
    }

    fun startNetwork(context: Context, session: String) {
        Thread({
            runCatching { NetworkDiagnosticCapture.collect(context.applicationContext, session, ::recording, ::networkCheckpoint) }
        }, "aura-startup-network").apply { isDaemon = true; start() }
    }

    fun accept(line: String): Unit = synchronized(lock) {
        if (closed) return@synchronized
        val received = clock()
        if (received - startedElapsed >= MAX_MILLIS) { finish("TIME_LIMIT"); return@synchronized }
        val limited = line.take(MAX_LINE_CHARS)
        crashTrace?.observe(limited, received)
        if (line.length > MAX_LINE_CHARS) metadata.put("line_truncated", true)
        // Early graphics faults may have no game window or hot thread to sample.
        // Record only bounded candidate module names/offsets, never full maps/stack.
        // Same Android elapsed clock as display history, but receipt can lag the
        // guest call. Never label this an exact Wine/Android clock conversion.
        val receipt = if (limited.startsWith("AURA_DXGI_MODE_V1 "))
            "; host_receive_elapsed_ms=$received; host_capture_elapsed_ms=${received - startedElapsed}; clock_relation=RECEIVE_ONLY" else ""
        val text = limited + receipt + "\n" + nativeFaults.observe(limited).orEmpty()
        val count = text.toByteArray(Charsets.UTF_8).size
        if (bytes + count > maxBytes) { finish("BYTE_LIMIT"); return@synchronized }
        try {
            output.write(text)
            bytes += count
            // Startup exceptions can terminate the guest immediately. Flush while it still exists.
            output.flush()
            if (line.contains(":seh:") && line.contains("code=")) metadata.put("seh_observed", true)
            if (line.contains(":module:") && line.contains("failed")) {
                metadata.put("loader_failure_observed", true)
                saveMetadata()
            }
        } catch (_: Exception) { finish("WRITE_FAILED") }
    }

    fun finish(reason: String, report: String? = null): Unit = synchronized(lock) {
        if (closed) {
            report?.let { runCatching { atomic(File(directory, "launch-after.txt"), it.take(64 * 1024)) } }
            return@synchronized
        }
        closed = true
        crashTrace?.close()
        pipelineTrace?.close()
        val network = File(directory, "network-control.txt")
        runCatching {
            if (network.isFile && network.length() <= 32 * 1024) {
                val text = network.readText()
                if (text.startsWith("NETWORK_CONTROL_V1 state=RUNNING;"))
                    atomic(network, text.replaceFirst("state=RUNNING;", "state=CANCELLED;"))
            }
        }
        timeoutHandler?.removeCallbacks(timeout)
        timeoutHandler = null
        observer?.close(); observer = null
        val flushed = runCatching { output.close() }.isSuccess
        val state = if (flushed) reason else "WRITE_FAILED"
        metadata.put("state", state).put("finished_ms", System.currentTimeMillis())
        val saved = runCatching {
            saveCrashTrace()
            report?.let { atomic(File(directory, "launch-after.txt"), it.take(64 * 1024)) }
            saveMetadata()
        }.isSuccess && flushed && state !in setOf("WRITE_FAILED", "PREPARE_FAILED", "ARM_CONSUME_FAILED")
        if (active === this) active = null
        if (announced) StartupCaptureEvents.publish(StartupCaptureEvents.Event(id,
            metadata.optString("game"), state, saved))
    }

    private fun saveMetadata() {
        metadata.put("bytes", bytes)
        atomic(File(directory, "capture.json"), metadata.toString(2))
    }

    data class Snapshot(val id: String, val started: Long, val game: String, val state: String) {
        val filename: String get() = "Aura-Startup-Debug-$started.zip"
    }

    companion object {
        const val EXTRA = "aura_startup_exception_capture"
        const val STORE = "diagnostics/startup-exception-capture"
        const val CHANNELS = "-all,+timestamp,+pid,err+all,warn+module,trace+seh,trace+loaddll"
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_MILLIS = 120_000L
        private const val MAX_LINE_CHARS = 16 * 1024
        private const val ARMED = "captureNextGameStartup"
        private val lock = Any()
        private var active: StartupExceptionCapture? = null
        private val names = listOf("capture.json", "guest-startup.log", "modules.tsv", "launch-before.txt", "launch-after.txt", "network-control.txt", "startup-crash.txt", "vulkan-pipeline.txt", "vulkan-fault.txt")

        private fun preferences(context: Context) = context.applicationContext
            .getSharedPreferences(LegacyDataNames.RUNTIME_PREFERENCES, Context.MODE_PRIVATE)

        fun armed(context: Context): Boolean = RuntimeDiagnostics.enabled(context) && preferences(context).getBoolean(ARMED, false)

        fun arm(context: Context, value: Boolean) {
            check(!value || RuntimeDiagnostics.enabled(context)) { "Enable debug mode before arming startup capture" }
            check(preferences(context).edit().putBoolean(ARMED, value).commit())
        }

        /** Dependency installers and resumed sessions must not consume the pending request. */
        fun prepare(context: Context, intent: Intent, eligible: Boolean) {
            intent.putExtra(EXTRA, eligible && armed(context))
        }

        fun disable(context: Context) {
            preferences(context).edit().remove(ARMED).apply()
            synchronized(lock) { active?.finish("DEBUG_DISABLED") }
        }

        fun start(context: Context, intent: Intent?, prefix: File, report: String): StartupExceptionCapture? {
            // RuntimeDiagnostics.install can notify observers: never acquire its monitor under ours.
            if (intent?.getBooleanExtra(EXTRA, false) != true || !RuntimeDiagnostics.sessionEnabled(context)) return null
            return synchronized(lock) {
                if (!RuntimeDiagnostics.enabled() || !preferences(context).getBoolean(ARMED, false))
                    return@synchronized null
                val session = intent.getStringExtra("aura_launch_id")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                    ?: return@synchronized null
                val store = File(context.filesDir, STORE)
                val capture = create(store, session, intent.getStringExtra("aura_game_name").orEmpty(), report,
                    moduleIdentities(prefix, intent.getStringExtra("exec_path")?.let(::File)))
                if (!preferences(context).edit().remove(ARMED).commit()) {
                    capture.finish("ARM_CONSUME_FAILED")
                    error("Could not consume startup capture request")
                }
                capture.observer = RuntimeDiagnostics.observe { enabled -> if (!enabled) capture.finish("DEBUG_DISABLED") }
                if (capture.closed) { capture.observer?.close(); capture.observer = null }
                else capture.timeoutHandler = Handler(Looper.getMainLooper()).also { it.postDelayed(capture.timeout, MAX_MILLIS) }
                capture
            }
        }

        /** File-backed core is also used by host tests; no Wine process or API behavior is simulated. */
        fun create(store: File, session: String, game: String, report: String, modules: String,
                   clock: () -> Long = SystemClock::elapsedRealtime, maxBytes: Int = MAX_BYTES): StartupExceptionCapture = synchronized(lock) {
            require(session.matches(Regex("[a-f0-9-]{36}")) && maxBytes in 1..MAX_BYTES)
            active?.finish("REPLACED")
            check(store.isDirectory || store.mkdirs())
            val id = UUID.randomUUID().toString()
            val directory = File(store, id)
            check(directory.mkdir())
            val metadata = JSONObject().put("format", "AURA_STARTUP_CAPTURE_V1").put("id", id)
                .put("session", session).put("started_ms", System.currentTimeMillis()).put("game", game.take(200))
                .put("state", "RECORDING").put("channels", CHANNELS).put("limit_bytes", maxBytes)
                .put("limit_ms", MAX_MILLIS).put("seh_observed", false).put("loader_failure_observed", false)
                .put("trace_channels_last_until_session_exit", true)
            var capture: StartupExceptionCapture? = null
            try {
                atomic(File(directory, "launch-before.txt"), report.take(64 * 1024))
                atomic(File(directory, "modules.tsv"), modules.take(64 * 1024))
                capture = StartupExceptionCapture(directory, metadata, clock, maxBytes)
                capture.saveMetadata()
                atomic(File(store, "latest"), id)
                active = capture
                capture.announced = true
                StartupCaptureEvents.publish(StartupCaptureEvents.Event(id, game.take(200), "RECORDING", false))
                // Keep just the previous capture for recovery while replacing the current pointer.
                runCatching {
                    store.listFiles().orEmpty().filter { it.isDirectory && it != directory }
                        .sortedByDescending(File::lastModified).drop(1).forEach { it.deleteRecursively() }
                }
                capture
            } catch (error: Exception) {
                capture?.finish("PREPARE_FAILED")
                directory.deleteRecursively()
                throw error
            }
        }

        fun snapshot(store: File): Snapshot? = synchronized(lock) {
            runCatching {
                val directory = latestDirectory(store)
                val file = File(directory, "capture.json")
                check(file.length() in 1..(16 * 1024))
                val value = JSONObject(file.readText())
                check(value.getString("id") == directory.name)
                val state = value.getString("state").let {
                    if (it == "RECORDING" && active?.id != directory.name) "INTERRUPTED" else it
                }
                Snapshot(directory.name, value.getLong("started_ms"), value.getString("game"), state)
            }.getOrNull()
        }

        /** An accepted picker must export the capture the user selected, never a newer session. */
        fun export(store: File, expectedId: String, destination: OutputStream) {
            // Snapshot bounded bytes under the writer lock, then release it before slow SAF I/O.
            val (snapshot, contents) = synchronized(lock) {
                val snapshot = checkNotNull(snapshot(store)) { "No startup capture" }
                check(snapshot.id == expectedId) { "Startup capture changed; select the current capture" }
                active?.takeIf { it.id == expectedId }?.let { it.output.flush(); it.saveCrashTrace(); it.saveMetadata() }
                val directory = latestDirectory(store)
                val files = names.map { File(directory, it) }.filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
                check(files.any { it.name == "guest-startup.log" } && files.any { it.name == "capture.json" })
                // Three bounded UTF-8 text attachments plus metadata can exceed their character counts.
                check(files.sumOf(File::length) <= MAX_BYTES + 1024 * 1024L)
                snapshot to files.map { it.name to it.readBytes() }
            }
            ZipOutputStream(destination).use { zip ->
                val hashes = StringBuilder()
                for ((name, data) in contents) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                    val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                    hashes.append(digest).append("  ").append(name).append('\n')
                }
                zip.putNextEntry(ZipEntry("checksums.sha256")); zip.write(hashes.toString().toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(("Startup logging was enabled before launching Wine. This is not a live-thread PC sample.\n" +
                    "Capture: ${snapshot.id}; started_ms=${snapshot.started}; export_state=${snapshot.state}.\n" +
                    "SEH events may be handled normally; an exception record alone is not a crash verdict.\n" +
                    "Wine module/SEH logs can include local paths and register values; no relay or process/environment dump is enabled.\n" +
                    "The byte/time limit bounds retained output; this launch's Wine trace channels last until the session exits.\n" +
                    "modules.tsv describes files on disk, not proof that a particular file was mapped.\n").toByteArray())
                zip.closeEntry()
            }
        }

        fun moduleIdentities(prefix: File, executable: File?): String {
            val candidates = listOf("kernel32.dll", "kernelbase.dll", "ntdll.dll", "d3d11.dll", "dxgi.dll").map {
                "system32/$it" to File(prefix, "drive_c/windows/system32/$it")
            } + listOf("dxgi.dll", "d3d11.dll").map {
                "syswow64/$it" to File(prefix, "drive_c/windows/syswow64/$it")
            } + listOfNotNull(executable?.let { "selected-executable" to it }) +
                listOf("steam_api64.dll", "steam_api.dll", "3dm64.dll").mapNotNull { name ->
                    executable?.parentFile?.let { "selected-directory/$name" to File(it, name) }
                }
            return buildString {
                appendLine("label\tbytes\tsha256_or_status")
                for ((label, file) in candidates) {
                    val size = file.length()
                    val status = when {
                        !file.isFile -> "NOT_FOUND"
                        size > 64L * 1024 * 1024 -> "SIZE_LIMIT"
                        else -> runCatching {
                            val digest = MessageDigest.getInstance("SHA-256")
                            file.inputStream().use { input ->
                                val data = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val n = input.read(data)
                                    if (n < 0) break
                                    total += n; check(total <= 64L * 1024 * 1024)
                                    digest.update(data, 0, n)
                                }
                                check(total == size && file.length() == size)
                            }
                            digest.digest().joinToString("") { "%02x".format(it) }
                        }.getOrDefault("UNREADABLE_OR_CHANGED")
                    }
                    appendLine("$label\t$size\t$status")
                }
            }
        }

        private fun latestDirectory(store: File): File {
            val pointer = File(store, "latest")
            check(pointer.length() == 36L)
            val id = pointer.readText()
            check(id.matches(Regex("[a-f0-9-]{36}")))
            return File(store, id).also { check(Files.isDirectory(it.toPath(), NOFOLLOW_LINKS)) }
        }

        private fun atomic(file: File, text: String) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(text)
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}

/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded, separate capture: ordinary guest logs and saved WINEDEBUG preferences are untouched. */
class UbisoftWaitTrace private constructor(private val directory: File, private val prefix: File,
                                          private val logSources: List<Pair<File, Long>>) : AutoCloseable {
    private val output = File(directory, "guest-debug.log").bufferedWriter()
    private var bytes = 0
    private var closed = false
    private var truncated = false
    private var writeFailed = false
    private var modeObserver: AutoCloseable? = null

    @Synchronized fun accept(line: String) {
        if (!RuntimeDiagnostics.enabled() || closed || truncated || writeFailed) return
        val text = line + "\n"
        try { if (bytes + text.toByteArray().size > MAX_BYTES) {
            output.write("\nAURA_WAIT_TRACE_TRUNCATED limit=$MAX_BYTES\n")
            truncated = true
        } else { output.write(text); bytes += text.toByteArray().size }
        output.flush()
        } catch (_: java.io.IOException) { writeFailed = true }
    }

    fun finish(context: Context, cleanupComplete: Boolean) {
        close()
        val restored = cleanupComplete && runCatching { WaitTraceRegistry.restore(prefix); true }.getOrDefault(false)
        RuntimeLaunchDiagnostics.detail(context, "UBISOFT_WAIT_TRACE_V1 state=FINISHED; filters=" +
            if (restored) "RESTORED" else "RECOVERY_PENDING")
        if (!RuntimeDiagnostics.enabled(context)) return
        runCatching {
            File(directory, "capture.txt").appendText("\nfilters=${if (restored) "RESTORED" else "RECOVERY_PENDING"}; truncated=$truncated; write_failed=$writeFailed\n")
            for ((file, offset) in logSources) if (file.isFile) {
                val size = file.length()
                val start = if (size < offset) 0 else offset
                file.inputStream().use { source ->
                    source.skipNBytes(maxOf(start, size - 1024 * 1024))
                    File(directory, file.name).outputStream().use { it.write(source.readNBytes(1024 * 1024)) }
                }
            }
            RuntimeLaunchDiagnostics.latestLaunch(context)?.let { File(directory, "launch-diagnostics.txt").writeText(it.report) }
        }.onFailure { RuntimeLaunchDiagnostics.detail(context, "UBISOFT_WAIT_TRACE_V1 supplemental_logs=UNAVAILABLE") }
    }

    @Synchronized override fun close() {
        modeObserver?.close(); modeObserver = null
        if (!closed) { closed = true; active = false; runCatching { output.close() } }
    }

    companion object {
        const val EXTRA = "aura_ubisoft_wait_trace"
        const val GAME_ID = -2
        const val SECONDS = 45
        const val MAX_BYTES = 24 * 1024 * 1024
        const val CHANNELS = "-all,+timestamp,+pid,err+all,warn+module,+loaddll,+process,+seh,+thread,+relay"
        @Volatile var active = false
            private set
        fun directory(context: Context) = File(context.filesDir, "diagnostics/ubisoft-wait-trace")
        fun available(context: Context) = File(directory(context), "guest-debug.log").isFile

        fun start(context: Context, prefix: File, client: File?, containerId: Int): UbisoftWaitTrace {
            check(RuntimeDiagnostics.sessionEnabled(context)) { "Debug mode is disabled" }
            val dir = directory(context).apply { mkdirs() }
            // Preserve a completed previous capture until the new configuration is safely prepared.
            WaitTraceRegistry.install(prefix)
            try {
                dir.listFiles()?.forEach { check(it.delete()) { "Cannot replace previous trace" } }
                File(dir, "capture.txt").writeText("AURA_UBISOFT_WAIT_TRACE_V1\ncontainer=$containerId\n" +
                    "started_ms=${System.currentTimeMillis()}\nlimit_bytes=$MAX_BYTES\nchannels=$CHANNELS\n")
                val logs = client?.parentFile?.let { parent ->
                    listOf("launcher_log.txt", "game_starter_log.txt").map { File(parent, "logs/$it") }
                        .filter { it.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath()) }
                        .map { it to it.length() }
                }.orEmpty()
                return UbisoftWaitTrace(dir, prefix, logs).also { trace ->
                    active = true
                    trace.modeObserver = RuntimeDiagnostics.observe { enabled -> if (!enabled) trace.close() }
                    if (trace.closed) { trace.modeObserver?.close(); trace.modeObserver = null }
                }
            } catch (error: Exception) { runCatching { WaitTraceRegistry.restore(prefix) }; throw error }
        }

        /** Only fixed capture files, never registry backups or account data. Call after stopping Wine. */
        fun export(context: Context, destination: OutputStream) = export(directory(context), destination)
        fun export(directory: File, destination: OutputStream) {
            check(File(directory, "guest-debug.log").isFile) { "No trace recorded" }
            ZipOutputStream(destination).use { zip ->
                for (name in listOf("capture.txt", "guest-debug.log", "launch-diagnostics.txt", "launcher_log.txt", "game_starter_log.txt")) {
                    val file = File(directory, name)
                    if (!file.isFile) continue
                    check(file.length() <= MAX_BYTES + 4096L) { "Unexpected capture size" }
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}

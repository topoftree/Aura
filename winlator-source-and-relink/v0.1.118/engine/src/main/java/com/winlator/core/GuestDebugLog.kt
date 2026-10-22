/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.winlator.R
import com.winlator.xenvironment.RootFS
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

/** Explicitly enabled raw logs stay local; diagnostics contain only fixed file status codes. */
class GuestDebugLog private constructor(
    val file: File?,
    writer: BufferedWriter?,
    private val changed: (String) -> Unit,
) : AutoCloseable {
    private val output = writer?.let { GuestLogBuffer(it) { changed("WRITE_FAILED") } }
    private var modeObserver: AutoCloseable? = null
    val isOpen: Boolean get() = output?.isOpen == true
    fun append(text: String): Boolean = output?.append(text) ?: false
    fun flush(): Boolean = output?.flush() ?: false
    override fun close() { modeObserver?.close(); modeObserver = null; output?.close() }

    companion object {
        const val DEFAULT_NAME = "guest-debug.log"

        @JvmStatic fun captureEnabled(context: Context): Boolean =
            RuntimeDiagnostics.sessionEnabled(context) && PreferenceManager.getDefaultSharedPreferences(context).let {
                it.getBoolean("enable_wine_debug", false) || it.getBoolean("save_logs_to_file", false)
            }

        @JvmStatic fun defaultFile(context: Context) = File(RootFS.find(context).rootDir, DEFAULT_NAME)

        @JvmStatic fun open(context: Context): GuestDebugLog? {
            if (!RuntimeDiagnostics.sessionEnabled(context)) return null
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (!preferences.getBoolean("save_logs_to_file", false)) return null
            val configuredPath = preferences.getString("log_file", null)
            val requested = configuredPath?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
            val noticeContext = context.applicationContext
            return open(requested, defaultFile(context)) { status ->
                RuntimeLaunchDiagnostics.detail(context, "GUEST_DEBUG_FILE=$status")
                // Only persist a new destination after its writer has opened successfully.
                // Keep file capture enabled, and leave any newer user selection untouched.
                val resetPath = status == "FALLBACK_PRIVATE" && configuredPath != null &&
                    preferences.getString("log_file", null) == configuredPath &&
                    preferences.edit().remove("log_file").commit()
                if (resetPath) RuntimeLaunchDiagnostics.detail(context, "GUEST_DEBUG_PATH_RESET=PRIVATE_DEFAULT")
                val message = when (status) {
                    "FALLBACK_PRIVATE" -> noticeContext.getString(
                        if (resetPath) R.string.guest_debug_log_path_reset else R.string.guest_debug_log_fallback,
                        DEFAULT_NAME,
                    )
                    "OPEN_FAILED" -> noticeContext.getString(R.string.guest_debug_log_open_failed)
                    "WRITE_FAILED" -> noticeContext.getString(R.string.guest_debug_log_write_failed)
                    else -> null
                }
                if (message != null) Handler(Looper.getMainLooper()).post {
                    Toast.makeText(noticeContext, message, Toast.LENGTH_LONG).show()
                }
            }.also { log ->
                log.modeObserver = RuntimeDiagnostics.observe { enabled -> if (!enabled) log.close() }
                // observe invokes immediately; release its handle if it closed the writer before assignment.
                if (!log.isOpen) { log.modeObserver?.close(); log.modeObserver = null }
                log.file?.let { file ->
                    RuntimeLaunchDiagnostics.watchBundleLog(context, "guest-debug.log", file, guest = true, beforeRead = log::flush)
                }
            }
        }

        fun open(requested: File?, fallback: File, changed: (String) -> Unit): GuestDebugLog {
            fun create(file: File): BufferedWriter? = try {
                if (!file.isAbsolute) null else {
                    val parent = file.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) null
                    else file.bufferedWriter(Charsets.UTF_8, GuestLogBuffer.BUFFER_CHARS)
                }
            } catch (_: IOException) { null } catch (_: SecurityException) { null }

            val primary = requested ?: fallback
            create(primary)?.let {
                changed(if (primary == fallback) "PRIVATE_DEFAULT" else "CONFIGURED")
                return GuestDebugLog(primary, it, changed)
            }
            if (primary != fallback) create(fallback)?.let {
                changed("FALLBACK_PRIVATE")
                return GuestDebugLog(fallback, it, changed)
            }
            changed("OPEN_FAILED")
            return GuestDebugLog(null, null, changed)
        }
    }
}

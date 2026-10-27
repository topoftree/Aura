/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.IOException
import java.io.Writer
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Coalesce verbose Wine traces without queuing an unbounded copy of guest output. */
class GuestLogBuffer(
    writer: Writer,
    private val scheduler: ScheduledExecutorService = flusher,
    private val onFailure: () -> Unit = {},
) : AutoCloseable {
    private var writer: Writer? = writer
    private var closed = false
    private var writeFailed = false
    private var started = false
    private var pendingChars = 0
    private var flushTask: ScheduledFuture<*>? = null
    val isOpen: Boolean @Synchronized get() = writer != null

    @Synchronized fun append(text: String): Boolean {
        if (closed) return true // A late callback must not enter a newer session's file.
        val current = writer ?: return false
        return try {
            current.write(text)
            pendingChars += minOf(text.length, BUFFER_CHARS)
            // Keep short startup failures and all non-trace output immediately readable.
            if (!started || pendingChars >= BUFFER_CHARS || !TRACE_PREFIX.containsMatchIn(text)) {
                flushPending()
            } else if (flushTask == null) {
                // One pending timer per writer, even when millions of lines arrive.
                // An idle trace tail is flushed without waiting for another line.
                flushTask = scheduler.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
            }
            started = true
            true
        } catch (_: IOException) { failed(); false }
    }

    /** Called before an explicit export/snapshot as well as by the idle-tail timer. */
    @Synchronized fun flush(): Boolean {
        if (closed) return !writeFailed
        if (writer == null) return false
        return try { flushPending(); true } catch (_: IOException) { failed(); false }
    }

    private fun flushPending() {
        if (pendingChars > 0) writer?.flush()
        pendingChars = 0
        cancelFlush()
    }

    private fun cancelFlush() {
        flushTask?.cancel(false)
        flushTask = null
    }

    private fun failed() {
        writeFailed = true
        cancelFlush()
        runCatching { writer?.close() }
        writer = null
        pendingChars = 0
        onFailure()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        cancelFlush()
        try { writer?.close() }
        catch (_: IOException) { writeFailed = true; onFailure() }
        finally { writer = null; pendingChars = 0 }
    }

    companion object {
        const val BUFFER_CHARS = 32 * 1024
        const val FLUSH_DELAY_MS = 250L
        private val TRACE_PREFIX = Regex("^\\s{0,16}(?:[0-9]{1,12}\\.[0-9]{1,6}:)?(?:[0-9a-fA-F]{4,8}:){0,2}trace:")
        private val flusher = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "aura-guest-log-flush").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

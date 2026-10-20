/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.concurrent.Executor
import java.util.function.Consumer

/** Combines host-menu and lifecycle pauses without doing process scans on the UI thread.
 * The executor must be serial and shared with guest startup/teardown. Queued work reads the
 * latest intent, so dismissing a menu in the background can never resume the guest.
 */
class GuestPauseController(private val executor: Executor, private val applyPaused: Consumer<Boolean>) {
    private var foreground = false
    private var hostPaused = false
    private var started = false
    private var closed = false
    private var queued = false
    private var applied: Boolean? = null

    @get:Synchronized
    val isPaused: Boolean get() = !foreground || hostPaused || closed

    @Synchronized fun setForeground(value: Boolean) {
        if (closed || foreground == value) return
        foreground = value
        schedule()
    }

    @Synchronized fun setHostPaused(value: Boolean) {
        if (closed || hostPaused == value) return
        hostPaused = value
        schedule()
    }

    @Synchronized fun started() {
        if (closed || started) return
        started = true
        schedule()
    }

    @Synchronized fun close() { closed = true }

    private fun schedule() {
        if (!started || closed || queued) return
        queued = true
        executor.execute(::applyLatest)
    }

    private fun applyLatest() {
        val pause = synchronized(this) {
            queued = false
            if (closed || !started) return
            (!foreground || hostPaused).also { if (it == applied) return }
        }
        // Do not hold the state lock while /proc or Wine work runs: lifecycle and menu callbacks
        // must stay responsive even if the guest is slow. New requests queue behind this one.
        applyPaused.accept(pause)
        synchronized(this) { applied = pause }
    }
}

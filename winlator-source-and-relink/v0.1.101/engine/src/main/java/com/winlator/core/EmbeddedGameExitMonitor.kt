/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.winlator.winhandler.OnGetProcessInfoListener
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.Window

/** One process stream for exit checks, temporary Steam window ownership and optional 10s diagnostics. */
class EmbeddedGameExitMonitor(
    private val winHandler: WinHandler,
    executable: String,
    private val onExit: Runnable,
    private val steamGameSession: Boolean = false,
    private var diagnostics: GameWindowDiagnostics? = null,
    autoMinimizeSteam: Boolean = false,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = GameExitTracker(executable, graceMillis = if (steamGameSession) 10_000 else 2_000,
        backgroundProcesses = if (steamGameSession)
            setOf("steam.exe", "steamwebhelper.exe", "steamservice.exe", "gameoverlayui.exe", "aura-steam-session.exe") else emptySet())
    private val snapshot = WineProcessSnapshot()
    private val steamWindows = if (steamGameSession && autoMinimizeSteam) SteamWindowMinimizer(executable) else null
    private var resumed = false
    private var polling = false
    private var completedReply = true
    private var pollInterval = 1_000L
    private var startupEvidence: GameStartupEvidence? = null
    @Volatile private var closed = false
    private val listener = OnGetProcessInfoListener { index, count, info ->
        val row = info?.let { WineProcess(it.pid, it.name) }
        if (!closed) main.post {
            if (!closed && polling && resumed) {
                if (info == null || count !in 1..4096) {
                    diagnostics?.unknownProcesses()
                    startupEvidence?.processes(null, SystemClock.elapsedRealtime())
                    tracker.unknownProcesses()
                    steamWindows?.unknownProcesses()
                }
                snapshot.accept(index, count, row)?.let {
                    completedReply = true
                    val now = SystemClock.elapsedRealtime()
                    diagnostics?.processes(it, now)
                    startupEvidence?.processes(it, now)
                    steamWindows?.processes(it)
                    minimizeSteam()
                    if (tracker.processes(it, now)) {
                        close()
                        onExit.run()
                    } else updatePolling()
                }
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !polling) return
            if (!completedReply) {
                tracker.unknownProcesses()
                diagnostics?.unknownProcesses()
                startupEvidence?.processes(null, SystemClock.elapsedRealtime())
                steamWindows?.unknownProcesses()
            }
            completedReply = false
            snapshot.reset()
            winHandler.listProcesses()
            main.postDelayed(this, pollInterval)
        }
    }

    init { winHandler.addOnGetProcessInfoListener(listener) }

    fun windowChanged(window: Window) {
        // Copy X11-owned fields on its callback thread, never dereference Window from the main loop.
        val visible = window.isRenderable && window.attributes.isViewable &&
            !window.isDesktopWindow && window.className.isNotBlank() &&
            (!steamGameSession || window.className.substringBefore('\u0000').lowercase(java.util.Locale.ROOT) !in
                setOf("steam.exe", "steamwebhelper.exe", "gameoverlayui.exe"))
        val view = steamWindows?.let { SteamWindowMinimizer.View(window.id, window.handle, window.processId,
            window.className, window.isRenderable && window.attributes.isViewable && !window.isDesktopWindow,
            window.width >= 160 && window.height >= 100, window.transientFor == 0 && !window.isDialogBox) }
        val id = window.id
        if (!closed) main.post {
            if (!closed) {
                tracker.windowChanged(id, visible)
                if (!visible) startupEvidence?.removed(id)
                view?.let { steamWindows.window(it) }
                minimizeSteam()
                updatePolling()
            }
        }
    }

    fun windowRemoved(id: Int) {
        if (!closed) main.post {
            if (!closed) {
                steamWindows?.removed(id)
                startupEvidence?.removed(id)
                tracker.windowChanged(id, false)
                updatePolling()
            }
        }
    }

    fun applicationStarted() {
        if (!closed) main.post {
            if (!closed) { tracker.applicationStarted(); steamWindows?.started(); minimizeSteam(); updatePolling() }
        }
    }

    private fun minimizeSteam() {
        if (!closed && resumed) steamWindows?.takeMinimizeHandles()?.forEach {
            winHandler.showWindow(it, com.winlator.winhandler.WinEnums.SW_MINIMIZE.toInt())
        }
    }

    fun stopDiagnostics() {
        diagnostics?.setPaused(true)
        diagnostics = null
        updatePolling()
    }

    fun setResumed(value: Boolean) {
        resumed = value
        startupEvidence?.setActive(value)
        diagnostics?.setPaused(!value)
        if (!value) tracker.unknownProcesses()
        if (!value) steamWindows?.unknownProcesses() else minimizeSteam()
        updatePolling()
    }

    private fun updatePolling() {
        val shouldPoll = !closed && resumed && (tracker.needsProcessCheck || diagnostics != null || startupEvidence != null || steamWindows?.needsProcessCheck == true)
        val interval = if (tracker.needsProcessCheck || startupEvidence != null) 1_000L else if (steamWindows?.needsProcessCheck == true) 2_000L else 10_000L
        if (polling == shouldPoll && pollInterval == interval) return
        pollInterval = interval
        polling = shouldPoll
        main.removeCallbacks(poll)
        snapshot.reset()
        completedReply = true
        if (polling) main.post(poll)
    }

    override fun close() {
        closed = true
        startupEvidence?.setActive(false)
        startupEvidence = null
        diagnostics?.setPaused(true)
        main.removeCallbacksAndMessages(null)
        winHandler.removeOnGetProcessInfoListener(listener)
        tracker.close()
        steamWindows?.close()
        snapshot.reset()
    }

    fun observeStartup(evidence: GameStartupEvidence?) {
        startupEvidence?.setActive(false)
        startupEvidence = evidence
        evidence?.setActive(resumed)
        updatePolling()
    }
}

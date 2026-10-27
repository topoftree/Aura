/* Aura integration changes for 0.1.149, 2026-10-27.
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
class EmbeddedGameExitMonitor @JvmOverloads constructor(
    private val winHandler: WinHandler,
    executable: String,
    private val onExit: Runnable,
    private val steamGameSession: Boolean = false,
    private var diagnostics: GameWindowDiagnostics? = null,
    autoMinimizeSteam: Boolean = false,
    private val xServer: com.winlator.xserver.XServer? = null,
    additionalBackgroundProcesses: List<String> = emptyList(),
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = GameExitTracker(executable, graceMillis = if (steamGameSession) 10_000 else 2_000,
        backgroundProcesses = (if (steamGameSession)
            setOf("steam.exe", "steamwebhelper.exe", "steamservice.exe", "gameoverlayui.exe", "aura-steam-session.exe") else emptySet()) +
            additionalBackgroundProcesses,
        requireApplicationStart = steamGameSession)
    private val snapshot = WineProcessSnapshot()
    private val steamWindows = if (steamGameSession && autoMinimizeSteam) SteamWindowMinimizer(executable) else null
    @Volatile private var resumed = false
    private var polling = false
    private var completedReply = true
    private var pollInterval = 1_000L
    private var startupEvidence = emptyList<GameStartupEvidence>()
    @Volatile private var closed = false
    @Volatile private var steamActionsAllowed = false
    @Volatile private var steamActionEpoch = 0L
    private var windowWorker: java.util.concurrent.ExecutorService? = null
    private val listener = OnGetProcessInfoListener { index, count, info ->
        val row = info?.let { WineProcess(it.pid, it.name) }
        if (!closed) main.post {
            if (!closed && polling && resumed) {
                if (info == null || count !in 1..4096) {
                    diagnostics?.unknownProcesses()
                    startupEvidence.forEach { it.processes(null, SystemClock.elapsedRealtime()) }
                    tracker.unknownProcesses()
                    steamWindows?.unknownProcesses()
                }
                snapshot.accept(index, count, row)?.let {
                    completedReply = true
                    val now = SystemClock.elapsedRealtime()
                    diagnostics?.processes(it, now)
                    startupEvidence.forEach { evidence -> evidence.processes(it, now) }
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
                startupEvidence.forEach { it.processes(null, SystemClock.elapsedRealtime()) }
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
        // Wine's top-level HWNDs live under either the X root or its virtual desktop.
        // CEF/GL child surfaces belong to their containing window, not a separate application.
        val parent = window.parent
        val topLevel = parent == null || parent.isDesktopWindow || parent === xServer?.windowManager?.rootWindow
        val visible = window.isRenderable && window.attributes.isViewable &&
            !window.isDesktopWindow && window.className.isNotBlank()
        // Steam and games can use the same CEF/SDL classes. Only PID ownership can exclude
        // a remaining client window from game-exit detection; unknown owners stay conservative.
        val pid = window.processId
        val view = steamWindows?.let { SteamWindowMinimizer.View(window.id, window.handle, window.processId,
            window.className, topLevel && window.isRenderable && window.attributes.isViewable && !window.isDesktopWindow,
            window.width >= 160 && window.height >= 100, window.transientFor == 0 && !window.isDialogBox, window.name) }
        val id = window.id
        if (!closed) main.post {
            if (!closed) {
                tracker.windowChanged(id, visible && (!steamGameSession || topLevel), pid)
                if (!visible) startupEvidence.forEach { it.removed(id) }
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
                startupEvidence.forEach { it.removed(id) }
                tracker.windowChanged(id, false)
                updatePolling()
            }
        }
    }

    fun applicationStarted() {
        if (!closed) main.post {
            if (!closed) { tracker.applicationStarted(); minimizeSteam(); updatePolling() }
        }
    }

    private fun minimizeSteam() {
        if (!closed && resumed && steamActionsAllowed) steamWindows?.takeActions()?.forEach { action ->
            if (action.kind == SteamWindowMinimizer.Kind.MINIMIZE)
                winHandler.showWindow(action.view.handle, com.winlator.winhandler.WinEnums.SW_MINIMIZE.toInt())
            else {
                val worker = windowWorker ?: java.util.concurrent.Executors.newSingleThreadExecutor().also { windowWorker = it }
                val epoch = steamActionEpoch
                worker.execute {
                    if (!closed && resumed && steamActionsAllowed && epoch == steamActionEpoch) {
                        val result = xServer?.let { runCatching { SteamWindowDismissal.request(it, action.view) }.getOrNull() }
                        // Clients without WM_DELETE_WINDOW can still dismiss their popup from view.
                        if (result == SteamWindowDismissal.Result.HIDE_ONLY && !closed && resumed &&
                            steamActionsAllowed && epoch == steamActionEpoch)
                            winHandler.showWindow(action.view.handle, com.winlator.winhandler.WinEnums.SW_HIDE.toInt())
                    } else if (!closed) main.post {
                        if (!closed) {
                            steamWindows.retry(action.view)
                            updatePolling()
                        }
                    }
                }
            }
        }
    }

    fun steamClientPresentation(authenticated: Boolean, foregroundRequested: Boolean) {
        if (closed) return
        val allowed = authenticated && !foregroundRequested
        if (allowed != steamActionsAllowed) steamActionEpoch++
        steamActionsAllowed = allowed
        steamWindows?.presentation(authenticated, foregroundRequested)
        minimizeSteam()
        updatePolling()
    }

    fun stopDiagnostics() {
        diagnostics?.setPaused(true)
        diagnostics = null
        updatePolling()
    }

    fun setResumed(value: Boolean) {
        if (resumed != value) steamActionEpoch++
        resumed = value
        startupEvidence.forEach { it.setActive(value) }
        diagnostics?.setPaused(!value)
        if (!value) tracker.unknownProcesses()
        if (!value) steamWindows?.unknownProcesses() else minimizeSteam()
        updatePolling()
    }

    private fun updatePolling() {
        val shouldPoll = !closed && resumed && (tracker.needsProcessCheck || tracker.needsWindowOwnershipCheck ||
            diagnostics != null || startupEvidence.isNotEmpty() || steamWindows?.needsProcessCheck == true)
        val interval = if (tracker.needsProcessCheck || startupEvidence.isNotEmpty()) 1_000L
            else if (tracker.needsWindowOwnershipCheck || steamWindows?.needsProcessCheck == true) 2_000L else 10_000L
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
        startupEvidence.forEach { it.setActive(false) }
        startupEvidence = emptyList()
        diagnostics?.setPaused(true)
        main.removeCallbacksAndMessages(null)
        winHandler.removeOnGetProcessInfoListener(listener)
        tracker.close()
        steamWindows?.close()
        steamActionsAllowed = false
        windowWorker?.shutdownNow()
        windowWorker = null
        snapshot.reset()
    }

    fun observeStartup(evidence: GameStartupEvidence?) = observeStartupEvidence(listOfNotNull(evidence))

    /** Cover readiness and first-install confirmation share the existing process stream. */
    fun observeStartupEvidence(evidence: List<GameStartupEvidence>) {
        val next = if (closed) emptyList() else evidence.distinct()
        startupEvidence.filter { it !in next }.forEach { it.setActive(false) }
        next.filter { it !in startupEvidence }.forEach { it.setActive(resumed) }
        startupEvidence = next
        updatePolling()
    }
}

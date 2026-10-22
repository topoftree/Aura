/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

data class WineProcess(val pid: Int, val name: String)

/** LIST_PROCESSES has no request ID. Require a complete, ordered reply, never infer exit from loss. */
class WineProcessSnapshot {
    private val rows = ArrayList<WineProcess>()
    private var expected = 0

    fun reset() { rows.clear(); expected = 0 }

    fun accept(index: Int, count: Int, process: WineProcess?): List<WineProcess>? {
        if (index == 0) { reset(); expected = count }
        if (count !in 1..4096 || count != expected || index != rows.size ||
            process == null || process.pid <= 0 || process.name.isBlank() || rows.any { it.pid == process.pid }) {
            reset()
            return null
        }
        rows += process
        return if (rows.size == expected) rows.toList().also { reset() } else null
    }
}

/** Window loss alone can be minimization or a resolution change; remaining app processes veto exit. */
class GameExitTracker(executable: String, private val graceMillis: Long = 2_000,
                      private val backgroundProcesses: Set<String> = emptySet(),
                      private val requireApplicationStart: Boolean = false) {
    private val executableName = processName(executable)
    private data class View(val pid: Int, val background: Boolean = false)
    private val windows = mutableMapOf<Int, View>()
    private val pendingOwnership = mutableSetOf<Int>()
    private var seenWindow = false
    private var emptySince: Long? = null
    private var closed = false
    val needsProcessCheck: Boolean get() = !closed && seenWindow && windows.values.all { it.background }
    val needsWindowOwnershipCheck: Boolean get() = !closed && pendingOwnership.isNotEmpty()

    /** A supervised CreateProcess succeeded, so even a pre-window DLL failure can be observed. */
    fun applicationStarted() { if (!closed) { seenWindow = true; emptySince = null } }

    fun windowChanged(id: Int, visible: Boolean, pid: Int = 0) {
        if (closed) return
        if (visible) {
            // A Steam login/updater window must not arm game-exit detection before GAME_STARTED.
            if (!requireApplicationStart) seenWindow = true
            val previous = windows[id]
            if (previous == null || previous.pid != pid) {
                windows[id] = View(pid)
                if (backgroundProcesses.isNotEmpty() && pid > 0) pendingOwnership += id
                emptySince = null
            }
        } else {
            if (windows.remove(id)?.background == false) emptySince = null
            pendingOwnership -= id
        }
    }

    fun unknownProcesses() {
        emptySince = null
        if (backgroundProcesses.isNotEmpty()) {
            windows.replaceAll { _, view -> view.copy(background = false) }
            pendingOwnership.addAll(windows.filterValues { it.pid > 0 }.keys)
        }
    }

    fun processes(snapshot: List<WineProcess>, nowMillis: Long): Boolean {
        if (closed) return false
        // An empty reply isn't authoritative: the responder winhandler.exe should still exist.
        if (snapshot.isEmpty()) { unknownProcesses(); return false }
        if (backgroundProcesses.isNotEmpty()) {
            val owners = snapshot.associate { it.pid to processName(it.name) }
            windows.replaceAll { _, view ->
                val owner = owners[view.pid]
                val background = owner != executableName && owner in backgroundProcesses
                if (view.background != background) emptySince = null
                view.copy(background = background)
            }
            // Recheck on a new map/PID or an unavailable snapshot, not on every rendered frame.
            pendingOwnership.clear()
        }
        if (!needsProcessCheck || snapshot.any {
                val name = processName(it.name)
                name == executableName || name !in WINE_SERVICES && name !in backgroundProcesses
            }) {
            emptySince = null
            return false
        }
        val since = emptySince
        if (since == null || nowMillis < since) { emptySince = nowMillis; return false }
        if (nowMillis - since < graceMillis) return false
        closed = true
        return true
    }

    fun close() { closed = true; windows.clear(); pendingOwnership.clear(); emptySince = null }

    private companion object {
        fun processName(value: String) = value.substringBefore('\u0000').replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        // Only Wine's known infrastructure may remain. Unknown launchers / child executables
        // keep the session alive, including while a new game window is still loading.
        val WINE_SERVICES = setOf("explorer.exe", "winhandler.exe", "services.exe", "winedevice.exe",
            "rpcss.exe", "svchost.exe", "plugplay.exe", "conhost.exe", "dllhost.exe", "ctfmon.exe",
            "tabtip.exe", "winemenubuilder.exe", "start.exe")
    }
}

/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
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
                      private val backgroundProcesses: Set<String> = emptySet()) {
    private val executableName = processName(executable)
    private val windows = mutableSetOf<Int>()
    private var seenWindow = false
    private var emptySince: Long? = null
    private var closed = false
    val needsProcessCheck: Boolean get() = !closed && seenWindow && windows.isEmpty()

    /** A supervised CreateProcess succeeded, so even a pre-window DLL failure can be observed. */
    fun applicationStarted() { if (!closed) { seenWindow = true; emptySince = null } }

    fun windowChanged(id: Int, visible: Boolean) {
        if (closed) return
        if (visible) {
            seenWindow = true
            windows += id
            emptySince = null
        } else if (windows.remove(id)) emptySince = null
    }

    fun unknownProcesses() { emptySince = null }

    fun processes(snapshot: List<WineProcess>, nowMillis: Long): Boolean {
        // An empty reply isn't authoritative: the responder winhandler.exe should still exist.
        if (!needsProcessCheck || snapshot.isEmpty() || snapshot.any {
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

    fun close() { closed = true; windows.clear(); emptySince = null }

    private companion object {
        fun processName(value: String) = value.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        // Only Wine's known infrastructure may remain. Unknown launchers / child executables
        // keep the session alive, including while a new game window is still loading.
        val WINE_SERVICES = setOf("explorer.exe", "winhandler.exe", "services.exe", "winedevice.exe",
            "rpcss.exe", "svchost.exe", "plugplay.exe", "conhost.exe", "dllhost.exe", "ctfmon.exe",
            "tabtip.exe", "winemenubuilder.exe", "start.exe")
    }
}

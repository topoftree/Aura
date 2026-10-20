/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/** Operational window policy. Never closes the client, kills webhelpers, or collects diagnostics. */
class SteamWindowMinimizer(executable: String) {
    data class View(val id: Int, val handle: Long, val pid: Int, val className: String,
                    val visible: Boolean, val gameSized: Boolean, val mainWindow: Boolean)
    private val target = name(executable)
    private val windows = mutableMapOf<Int, View>()
    private val minimized = mutableSetOf<Long>()
    private var roles = emptyMap<Int, String>()
    private var started = false
    private var gameVisible = false
    private var finished = false
    val needsProcessCheck: Boolean get() = started && !finished && windows.isNotEmpty()

    fun started() { started = true }
    fun processes(rows: List<WineProcess>) { if (!finished) roles = rows.associate { it.pid to name(it.name) } }
    fun unknownProcesses() { roles = emptyMap() }
    fun window(view: View) { if (!finished) windows[view.id] = view }
    fun removed(id: Int) { windows.remove(id) }
    private fun owner(view: View) = roles[view.pid] ?: name(view.className)

    fun takeMinimizeHandles(): List<Long> {
        if (!started || finished) return emptyList()
        // A successful CreateProcess or Ubisoft's splash is not the selected game's window.
        if (!gameVisible) gameVisible = target !in CLIENTS && windows.values.any {
            it.visible && it.gameSized && it.mainWindow && owner(it) == target
        }
        if (!gameVisible) return emptyList()
        val clients = windows.values.filter { it.mainWindow && owner(it) in CLIENTS }
        val handles = clients.filter { it.visible && it.handle != 0L }
            .map { it.handle }.distinct().filter { minimized.add(it) }
        // The user may already have minimized Steam. Do not keep ownership polling active for
        // the entire game, but await a complete snapshot before treating generic toolkit windows.
        val alreadyHidden = clients.isNotEmpty() && clients.none { it.visible } && roles.isNotEmpty() &&
            windows.values.none { it.visible && it.mainWindow && it.pid > 0 && it.pid !in roles }
        if (handles.isNotEmpty() || alreadyHidden) {
            // One handoff per session. User-restored Steam windows must remain usable afterwards.
            finished = true
            windows.clear(); roles = emptyMap()
        }
        return handles
    }
    fun close() { finished = true; windows.clear(); roles = emptyMap(); minimized.clear() }

    private companion object {
        val CLIENTS = setOf("steam.exe", "steamwebhelper.exe")
        fun name(value: String) = value.substringBefore('\u0000').replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
    }
}

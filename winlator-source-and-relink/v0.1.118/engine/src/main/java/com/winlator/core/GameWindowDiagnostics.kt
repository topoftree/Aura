/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/** Diagnostic observations only. Never used to focus, resume, terminate, or declare a game ready. */
class GameWindowDiagnostics(executable: String) {
    enum class Owner { TARGET, STEAM, UBISOFT, DESKTOP, OTHER, UNKNOWN, NONE }
    enum class Scan { COMPLETE, LIMIT, UNAVAILABLE }
    data class View(val owner: Owner, val mapped: Boolean, val drawEligible: Boolean)
    private data class Processes(val time: Long, val roles: Map<Int, Owner>)
    private val selected = basename(executable)
    @Volatile private var processes: Processes? = null
    data class Activity(val paused: Boolean, val epoch: Long)
    @Volatile private var activity = Activity(true, 0)

    @Synchronized fun setPaused(value: Boolean) {
        if (activity.paused != value) activity = Activity(value, activity.epoch + 1)
    }
    fun activity() = activity
    fun unknownProcesses() { processes = null }

    /** Only complete WinHandler replies are passed here; retain roles, never names or paths. */
    fun processes(rows: List<WineProcess>, nowMs: Long) {
        if (rows.isEmpty() || rows.size > 4096 || rows.any { it.pid <= 0 || it.name.isBlank() } ||
            rows.map { it.pid }.toSet().size != rows.size) {
            unknownProcesses()
            return
        }
        processes = Processes(nowMs, rows.associate { it.pid to nameOwner(it.name) })
    }

    /** Immutable view shared by one X-window scan, so a concurrent process reply cannot mix owners. */
    fun observation(nowMs: Long): Observation {
        val current = processes
        val age = current?.let { nowMs - it.time }?.takeIf { it >= 0 }
        val fresh = current != null && age != null && age <= 15_000
        return Observation(if (fresh) current!!.roles else null, age, activity.paused)
    }

    inner class Observation internal constructor(
        private val roles: Map<Int, Owner>?, private val age: Long?, private val paused: Boolean,
    ) {
        fun owner(pid: Int, className: String): Owner = roles?.get(pid) ?: nameOwner(className).let {
            // Arbitrary toolkit class names do not identify an executable; try the parent window.
            if (it == Owner.OTHER && !basename(className).endsWith(".exe")) Owner.UNKNOWN else it
        }

        fun report(views: List<View>, scan: Scan, focus: Owner, presented: Owner): String {
            val count = roles?.values?.count { it == Owner.TARGET }
            val state = when { count == null -> "UNKNOWN"; count > 0 -> "SEEN"; else -> "NOT_SEEN" }
            val targets = views.filter { it.owner == Owner.TARGET }
            fun bounded(value: Int) = when (scan) {
                Scan.COMPLETE -> "$value"
                Scan.LIMIT -> ">=$value"
                Scan.UNAVAILABLE -> "?"
            }
            return "GAME_WINDOW_STATE_V1 scope=SELECTED_EXE; process=$state; matches=${count ?: "?"}" +
                "; process_age_ms=${age ?: "?"}; windows=${bounded(targets.size)}" +
                "; mapped=${bounded(targets.count { it.mapped })}; draw_eligible=${bounded(targets.count { it.drawEligible })}" +
                "; unattributed=${bounded(views.count { it.owner == Owner.UNKNOWN })}" +
                "; focus=$focus; sampled_present_owner=$presented; scan=$scan" +
                "; guest=${if (paused) "PAUSED" else "ACTIVE"}; ready=UNCONFIRMED"
        }
    }

    private fun nameOwner(value: String): Owner {
        val name = basename(value)
        return when {
            selected.isNotEmpty() && name == selected -> Owner.TARGET
            name in STEAM -> Owner.STEAM
            name in UBISOFT -> Owner.UBISOFT
            name == "explorer.exe" -> Owner.DESKTOP
            name.isEmpty() -> Owner.UNKNOWN
            else -> Owner.OTHER
        }
    }

    companion object {
        private fun basename(value: String) = value.substringBefore('\u0000').replace('\\', '/')
            .substringAfterLast('/').lowercase(Locale.ROOT)
        private val STEAM = setOf("steam.exe", "steamwebhelper.exe", "gameoverlayui.exe", "steamservice.exe")
        private val UBISOFT = setOf("upc.exe", "uplay.exe", "ubisoftconnect.exe", "uplaywebcore.exe",
            "ubisoftgamelauncher.exe", "ubisoftgamelauncher64.exe")
    }
}

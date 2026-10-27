/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/** Event-driven policy for the authenticated client; process ownership is never inferred from a title. */
class SteamWindowMinimizer(executable: String) {
    data class View(val id: Int, val handle: Long, val pid: Int, val className: String,
                    val visible: Boolean, val gameSized: Boolean, val mainWindow: Boolean, val title: String = "")
    enum class Kind { MINIMIZE, CLOSE_POPUP }
    data class Action(val view: View, val kind: Kind)
    private val target = name(executable)
    private val windows = mutableMapOf<Int, View>()
    private val pendingOwnership = mutableSetOf<Int>()
    private val handled = mutableSetOf<Int>()
    private var roles = emptyMap<Int, String>()
    private var authenticated = false
    private var foregroundRequested = false
    private var closed = false
    val needsProcessCheck: Boolean get() = !closed && authenticated && !foregroundRequested &&
        target !in CLIENTS && pendingOwnership.isNotEmpty()

    fun presentation(authenticated: Boolean, foregroundRequested: Boolean) {
        if (closed) return
        if (this.authenticated != authenticated || this.foregroundRequested != foregroundRequested) {
            handled.clear()
            pendingOwnership.addAll(windows.values.filter { it.visible }.map { it.id })
        }
        this.authenticated = authenticated
        this.foregroundRequested = foregroundRequested
    }

    fun processes(rows: List<WineProcess>) {
        if (closed) return
        roles = rows.associate { it.pid to name(it.name) }
        pendingOwnership.clear()
    }

    fun unknownProcesses() {
        roles = emptyMap()
        pendingOwnership.addAll(windows.values.filter { it.visible }.map { it.id })
    }

    fun window(view: View) {
        if (closed) return
        val old = windows.put(view.id, view)
        if (!view.visible) {
            pendingOwnership.remove(view.id)
            handled.remove(view.id)
        } else if (old == null || !old.visible || old.pid != view.pid || old.handle != view.handle ||
            old.className != view.className || roles[view.pid] in CLIENTS &&
            (old.mainWindow != view.mainWindow || old.title != view.title)) {
            // Resolve even a familiar PID again for a newly mapped/replaced window.
            pendingOwnership.add(view.id)
            handled.remove(view.id)
        }
    }

    fun removed(id: Int) { windows.remove(id); pendingOwnership.remove(id); handled.remove(id) }

    fun retry(view: View) {
        if (!closed && windows[view.id] == view) {
            handled.remove(view.id)
            if (view.visible) pendingOwnership.add(view.id)
        }
    }

    fun takeActions(): List<Action> {
        if (closed || !authenticated || foregroundRequested || target in CLIENTS) return emptyList()
        return windows.values.mapNotNull { view ->
            if (!view.visible || view.handle == 0L || view.pid <= 0 || view.id in pendingOwnership ||
                view.id in handled || roles[view.pid] !in CLIENTS) return@mapNotNull null
            handled.add(view.id)
            // Do not cancel an authentication/confirmation dialog if Steam needs attention again.
            val title = view.title.lowercase(Locale.ROOT)
            if (listOf("steam guard", "sign in", "log in", "登录", "登入", "verification", "验证",
                    "cloud", "conflict", "purchase", "activation", "error", "云", "冲突", "购买", "激活", "错误")
                    .any { it in title }) return@mapNotNull null
            val popup = !view.mainWindow || listOf("special offers", "what's new", "steam news", "优惠", "更新消息")
                .any { it in title }
            Action(view, if (popup) Kind.CLOSE_POPUP else Kind.MINIMIZE)
        }
    }

    fun close() { closed = true; windows.clear(); roles = emptyMap(); pendingOwnership.clear(); handled.clear() }

    private companion object {
        val CLIENTS = setOf("steam.exe", "steamwebhelper.exe")
        fun name(value: String) = value.substringBefore('\u0000').replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
    }
}

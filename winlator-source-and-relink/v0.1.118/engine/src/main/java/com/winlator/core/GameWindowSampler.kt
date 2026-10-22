/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import com.winlator.xserver.Atom
import com.winlator.xserver.Property
import com.winlator.xserver.Window
import com.winlator.xserver.XServer

/** Runs on the existing ~10-second worker. No title strings, file I/O, or per-frame callbacks. */
object GameWindowSampler {
    private const val LIMIT = 256
    data class Sample @JvmOverloads constructor(val report: String, val contentOwners: Map<Int, GameWindowDiagnostics.Owner> = emptyMap(),
                      val focusedPresentedTarget: Boolean = false)

    fun report(server: XServer, diagnostics: GameWindowDiagnostics, presentedId: Int, nowMs: Long): String =
        sample(server, diagnostics, presentedId, emptyList(), nowMs).report

    fun sample(server: XServer, diagnostics: GameWindowDiagnostics, presentedId: Int,
               contentIds: List<Int>, nowMs: Long): Sample {
        val observation = diagnostics.observation(nowMs)
        return try { capture(server, observation, presentedId, contentIds.take(32)) } catch (_: Exception) {
            Sample(observation.report(emptyList(), GameWindowDiagnostics.Scan.UNAVAILABLE,
                GameWindowDiagnostics.Owner.UNKNOWN, GameWindowDiagnostics.Owner.UNKNOWN))
        }
    }

    private fun capture(server: XServer, observation: GameWindowDiagnostics.Observation,
                        presentedId: Int, contentIds: List<Int>): Sample {
        server.lock(XServer.Lockable.WINDOW_MANAGER).use {
            val root = server.windowManager.rootWindow
            fun owner(window: Window?): GameWindowDiagnostics.Owner {
                var current = window
                if (current == null) return GameWindowDiagnostics.Owner.NONE
                if (current === root) return GameWindowDiagnostics.Owner.DESKTOP
                repeat(32) {
                    val item = current ?: return GameWindowDiagnostics.Owner.UNKNOWN
                    if (item === root) return GameWindowDiagnostics.Owner.UNKNOWN
                    val pid = item.getProperty(Atom._NET_WM_PID.toInt())?.takeIf {
                        it.format == Property.Format.INT_ARRAY && it.data.capacity() >= 4
                    }?.getInt(0) ?: 0
                    val name = item.getProperty(Atom.WM_CLASS.toInt())?.takeIf {
                        it.format == Property.Format.BYTE_ARRAY && it.data.capacity() <= 1024
                    }
                        ?.let { item.className }.orEmpty()
                    val role = observation.owner(pid, name)
                    if (role != GameWindowDiagnostics.Owner.UNKNOWN) return role
                    current = item.parent
                }
                return GameWindowDiagnostics.Owner.UNKNOWN
            }
            // Walk even unmapped subtrees, but cap both retained and visited nodes.
            data class Node(val window: Window, val renderBranch: Boolean)
            val pending = ArrayDeque<Node>()
            val views = ArrayList<GameWindowDiagnostics.View>()
            pending.add(Node(root, true))
            var visited = 0
            var complete = true
            var presentedDrawEligible = false
            while (pending.isNotEmpty() && visited < LIMIT) {
                val (window, renderBranch) = pending.removeLast()
                visited++
                if (window.id == presentedId && window !== root)
                    presentedDrawEligible = renderBranch && window.isRenderable && window.attributes.isViewable && window.attributes.isMapped
                if (window !== root) views += GameWindowDiagnostics.View(owner(window), window.attributes.isMapped,
                    renderBranch && window.isRenderable && window.attributes.isViewable)
                val children = window.children
                for (index in children.indices) {
                    if (visited + pending.size >= LIMIT) { complete = false; break }
                    pending.add(Node(children[index], renderBranch && window.isRenderable && window.attributes.isRenderSubwindows))
                }
            }
            val presented = if (presentedId == 0) GameWindowDiagnostics.Owner.NONE
                else server.windowManager.getWindow(presentedId)?.let(::owner) ?: GameWindowDiagnostics.Owner.UNKNOWN
            val report = observation.report(views, if (complete && pending.isEmpty()) GameWindowDiagnostics.Scan.COMPLETE
                else GameWindowDiagnostics.Scan.LIMIT,
                owner(server.windowManager.focusedWindow), presented)
            // Pixmaps do not identify an executable. Never borrow focus or a stale Present owner.
            val contentOwners = contentIds.associateWith { id ->
                server.windowManager.getWindow(id)?.let(::owner) ?: GameWindowDiagnostics.Owner.UNKNOWN
            }
            return Sample(report, contentOwners, presented == GameWindowDiagnostics.Owner.TARGET &&
                owner(server.windowManager.focusedWindow) == GameWindowDiagnostics.Owner.TARGET &&
                presentedDrawEligible)
        }
    }
}

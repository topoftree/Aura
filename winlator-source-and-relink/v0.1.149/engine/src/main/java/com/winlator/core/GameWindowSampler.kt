/* Aura integration changes for 0.1.149, 2026-10-27.
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
    private const val GEOMETRY_LIMIT = 12
    data class Sample @JvmOverloads constructor(val report: String, val contentOwners: Map<Int, GameWindowDiagnostics.Owner> = emptyMap(),
                      val focusedPresentedTarget: Boolean = false,
                      val focusedContentTargets: Set<Int> = emptySet())

    fun report(server: XServer, diagnostics: GameWindowDiagnostics, presentedId: Int, nowMs: Long): String =
        sample(server, diagnostics, presentedId, emptyList(), nowMs).report

    /** Explicit Debug capture only. Keep geometry out of the periodic performance/CPU summary. */
    fun diagnosticReport(server: XServer, diagnostics: GameWindowDiagnostics, presentedId: Int, nowMs: Long): String =
        sampleWindows(server, diagnostics, presentedId, emptyList(), nowMs, true).report

    fun sample(server: XServer, diagnostics: GameWindowDiagnostics, presentedId: Int,
               contentIds: List<Int>, nowMs: Long): Sample =
        sampleWindows(server, diagnostics, presentedId, contentIds, nowMs, false)

    private fun sampleWindows(server: XServer, diagnostics: GameWindowDiagnostics, presentedId: Int,
                              contentIds: List<Int>, nowMs: Long, geometry: Boolean): Sample {
        val observation = diagnostics.observation(nowMs)
        return try { capture(server, observation, presentedId, contentIds.take(32), geometry) } catch (_: Exception) {
            Sample(observation.report(emptyList(), GameWindowDiagnostics.Scan.UNAVAILABLE,
                GameWindowDiagnostics.Owner.UNKNOWN, GameWindowDiagnostics.Owner.UNKNOWN) +
                if (geometry) "\nGAME_DISPLAY_GEOMETRY_V1 state=UNAVAILABLE; ready=UNCONFIRMED" else "")
        }
    }

    private fun capture(server: XServer, observation: GameWindowDiagnostics.Observation,
                        presentedId: Int, contentIds: List<Int>, geometry: Boolean): Sample {
        server.lock(XServer.Lockable.WINDOW_MANAGER).use {
            val root = server.windowManager.rootWindow
            val focus = server.windowManager.focusedWindow
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
            data class Node(val window: Window, val renderBranch: Boolean, val x: Int, val y: Int)
            val pending = ArrayDeque<Node>()
            val views = ArrayList<GameWindowDiagnostics.View>()
            val rectangles = if (geometry) ArrayList<Geometry>() else null
            pending.add(Node(root, true, root.x.toInt(), root.y.toInt()))
            var visited = 0
            var complete = true
            var presentedDrawEligible = false
            val eligibleContent = mutableSetOf<Int>()
            while (pending.isNotEmpty() && visited < LIMIT) {
                val (window, renderBranch, x, y) = pending.removeLast()
                visited++
                val role = owner(window)
                val eligible = renderBranch && window.isRenderable && window.attributes.isViewable
                if (window.id in contentIds && window !== root && renderBranch && window.isRenderable &&
                    window.attributes.isViewable && window.attributes.isMapped && role == GameWindowDiagnostics.Owner.TARGET)
                    eligibleContent += window.id
                if (window.id == presentedId && window !== root)
                    presentedDrawEligible = eligible && window.attributes.isMapped
                if (window !== root) {
                    views += GameWindowDiagnostics.View(role, window.attributes.isMapped, eligible)
                    if (rectangles != null && (role == GameWindowDiagnostics.Owner.TARGET ||
                            role == GameWindowDiagnostics.Owner.DESKTOP || window === focus || window.id == presentedId)) {
                        val surface = window.getProperty(Atom._NET_WM_SURFACE.toInt())
                        val surfaceFlag = when {
                            surface == null -> "NO"
                            surface.format != Property.Format.BYTE_ARRAY || surface.data.capacity() < 1 -> "UNKNOWN"
                            surface.data.get(0).toInt() == 1 -> "YES"
                            else -> "NO"
                        }
                        rectangles += Geometry(window.id, window.parent?.id ?: 0, role,
                            window.x.toInt(), window.y.toInt(), x, y, window.width.toInt(), window.height.toInt(),
                            window.attributes.isMapped, eligible, surfaceFlag, window === focus, window.id == presentedId)
                    }
                }
                val children = window.children
                for (index in children.indices) {
                    if (visited + pending.size >= LIMIT) { complete = false; break }
                    val child = children[index]
                    pending.add(Node(child, renderBranch && window.isRenderable && window.attributes.isRenderSubwindows,
                        x + child.x, y + child.y))
                }
            }
            val presented = if (presentedId == 0) GameWindowDiagnostics.Owner.NONE
                else server.windowManager.getWindow(presentedId)?.let(::owner) ?: GameWindowDiagnostics.Owner.UNKNOWN
            val scan = if (complete && pending.isEmpty()) GameWindowDiagnostics.Scan.COMPLETE else GameWindowDiagnostics.Scan.LIMIT
            val report = observation.report(views, scan, owner(focus), presented) +
                if (rectangles != null) geometryReport(server, rectangles, scan) else ""
            // Pixmaps do not identify an executable. Never borrow focus or a stale Present owner.
            val contentOwners = contentIds.associateWith { id ->
                server.windowManager.getWindow(id)?.let(::owner) ?: GameWindowDiagnostics.Owner.UNKNOWN
            }
            return Sample(report, contentOwners, presented == GameWindowDiagnostics.Owner.TARGET &&
                owner(server.windowManager.focusedWindow) == GameWindowDiagnostics.Owner.TARGET &&
                presentedDrawEligible,
                if (owner(server.windowManager.focusedWindow) == GameWindowDiagnostics.Owner.TARGET)
                    eligibleContent else emptySet())
        }
    }

    private data class Geometry(val id: Int, val parent: Int, val owner: GameWindowDiagnostics.Owner,
                                val x: Int, val y: Int, val rootX: Int, val rootY: Int, val width: Int, val height: Int,
                                val mapped: Boolean, val drawEligible: Boolean, val surface: String,
                                val focused: Boolean, val presented: Boolean)

    private fun geometryReport(server: XServer, rectangles: List<Geometry>, scan: GameWindowDiagnostics.Scan): String {
        val viewport = server.viewportInfo
        val monitor = server.screenInfo
        val selected = rectangles.sortedBy {
            when { it.focused || it.presented -> 0; it.owner == GameWindowDiagnostics.Owner.DESKTOP -> 1
                it.drawEligible -> 2; else -> 3 }
        }.take(GEOMETRY_LIMIT)
        return buildString {
            append("\nGAME_DISPLAY_GEOMETRY_V1 state=OBSERVED; monitor=${monitor.width}x${monitor.height}; ")
            append("viewport=${viewport.width}x${viewport.height}; coordinates=X11; scope=TARGET_DESKTOP_FOCUS_PRESENT; ")
            append("rows=${selected.size}; omitted=${rectangles.size - selected.size}; scan=$scan; ready=UNCONFIRMED")
            for (rect in selected) {
                val overlapWidth = (minOf(viewport.width.toInt(), rect.rootX + rect.width) - maxOf(0, rect.rootX)).coerceAtLeast(0)
                val overlapHeight = (minOf(viewport.height.toInt(), rect.rootY + rect.height) - maxOf(0, rect.rootY)).coerceAtLeast(0)
                append("\nGAME_WINDOW_GEOMETRY_V1 id=${rect.id}; parent=${rect.parent}; owner=${rect.owner}; ")
                append("local=${rect.x},${rect.y}; root=${rect.rootX},${rect.rootY}; size=${rect.width}x${rect.height}; ")
                append("mapped=${rect.mapped}; draw_eligible=${rect.drawEligible}; surface=${rect.surface}; ")
                append("focus=${rect.focused}; presented=${rect.presented}; viewport_overlap=${overlapWidth}x$overlapHeight")
            }
        }
    }
}

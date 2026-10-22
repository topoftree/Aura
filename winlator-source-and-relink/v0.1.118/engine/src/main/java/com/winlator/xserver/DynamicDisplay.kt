/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver

import com.winlator.xserver.XServer.Lockable
import java.util.concurrent.CopyOnWriteArrayList

/** Separate the X11 monitor from the Wine desktop viewport. Both belong only to this session. */
class DynamicDisplay(private val server: XServer) : WindowManager.OnWindowModificationListener {
    fun interface Listener {
        fun onDisplaySizeChanged(previous: ScreenInfo, current: ScreenInfo, source: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val viewportListeners = CopyOnWriteArrayList<Listener>()
    private var wineDesktop: Window? = null
    private var followWineDesktop = false

    init {
        server.windowManager.addOnWindowModificationListener(this)
    }

    fun addListener(listener: Listener) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }
    fun addViewportListener(listener: Listener) { viewportListeners.addIfAbsent(listener) }
    fun removeViewportListener(listener: Listener) { viewportListeners.remove(listener) }

    fun setFollowWineDesktop(enabled: Boolean) {
        server.lock(Lockable.WINDOW_MANAGER, Lockable.DRAWABLE_MANAGER, Lockable.INPUT_DEVICE).use {
            followWineDesktop = enabled
            if (!enabled) {
                wineDesktop = null
                updateViewport(server.screenInfo, "WINE_DESKTOP_DISABLED")
            }
        }
    }

    /** Serialize root allocation, geometry, pointer bounds and notifications in X lock order. */
    fun resize(width: Int, height: Int, source: String): Boolean {
        if (!isSupported(width, height)) return false
        server.lock(Lockable.WINDOW_MANAGER, Lockable.DRAWABLE_MANAGER, Lockable.INPUT_DEVICE).use {
            val old = server.screenInfo
            if (old.width.toInt() == width && old.height.toInt() == height) return true
            val next = ScreenInfo(width, height)
            try {
                server.windowManager.resizeRoot(next.width, next.height)
            } catch (_: OutOfMemoryError) {
                // Retain the old drawable/geometry and let RandR report failure.
                return false
            }
            server.screenInfo = next
            if (wineDesktop == null) updateViewport(next, source)
            server.windowManager.notifyRootSizeChanged()
            listeners.forEach { it.onDisplaySizeChanged(old, next, source) }
            return true
        }
    }

    override fun onMapWindow(window: Window) { follow(window) }
    override fun onModifyWindowProperty(window: Window, property: Property) {
        if (property.name == Atom.WM_CLASS.toInt()) follow(window)
    }
    override fun onUpdateWindowGeometry(window: Window, resized: Boolean) {
        if (resized) follow(window)
    }
    override fun onUnmapWindow(window: Window) {
        server.lock(Lockable.WINDOW_MANAGER, Lockable.DRAWABLE_MANAGER, Lockable.INPUT_DEVICE).use {
            if (window === wineDesktop) {
                wineDesktop = null
                updateViewport(server.screenInfo, "WINE_DESKTOP_CLOSED")
            }
        }
    }

    private fun follow(window: Window) {
        server.lock(Lockable.WINDOW_MANAGER, Lockable.DRAWABLE_MANAGER, Lockable.INPUT_DEVICE).use {
            if (!followWineDesktop || window === server.windowManager.rootWindow || !window.attributes.isMapped) return
            // Wine explorer /desktop owns a top-level X window at (0,0), with explorer.exe WM_CLASS.
            // Game, installer and error windows are children; never infer a mode from them.
            if (wineDesktop == null && window.parent === server.windowManager.rootWindow &&
                window.x.toInt() == 0 && window.y.toInt() == 0 && window.isInputOutput &&
                window.className.equals("explorer.exe", ignoreCase = true) &&
                isSupported(window.width.toInt(), window.height.toInt())) {
                wineDesktop = window
            }
            if (window === wineDesktop && isSupported(window.width.toInt(), window.height.toInt())) {
                // Do not resize the X root or announce a RandR mode change here. Wine rebuilds its
                // virtual mode list from that monitor, so shrinking it would remove larger modes
                // that games already enumerated (e.g. Mirror's Edge: 1404x648 -> 800x600 -> BADMODE).
                updateViewport(ScreenInfo(window.width.toInt(), window.height.toInt()), "WINE_DESKTOP")
            }
        }
    }

    /** Caller holds window/drawable/input locks, matching renderer and geometry updates. */
    private fun updateViewport(next: ScreenInfo, source: String) {
        val old = server.viewportInfo
        if (old.width == next.width && old.height == next.height) return
        server.viewportInfo = next
        server.pointer.setPosition(server.pointer.clampedX.toInt(), server.pointer.clampedY.toInt())
        viewportListeners.forEach { it.onDisplaySizeChanged(old, next, source) }
    }

    companion object {
        // Bound CPU framebuffer allocation to 64 MiB, with enough room for a 4K desktop.
        @JvmStatic fun isSupported(width: Int, height: Int): Boolean =
            width in ScreenInfo.MIN_WIDTH.toInt()..8192 && height in ScreenInfo.MIN_HEIGHT.toInt()..8192 &&
                width.toLong() * height <= 16_777_216L
    }
}

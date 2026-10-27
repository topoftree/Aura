/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.widget

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import com.winlator.xserver.XServer

/** Follows the display hosting the game view; never requests a faster display mode. */
class HostDisplayRefresh(private val view: View, private val server: XServer) : DisplayManager.DisplayListener {
    private val manager = view.context.getSystemService(DisplayManager::class.java)
    private var listening = false

    fun start() {
        if (!listening && manager != null) {
            manager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
            listening = true
        }
        refresh()
    }

    fun stop() {
        if (listening) manager?.unregisterDisplayListener(this)
        listening = false
    }

    fun refresh() {
        if (!view.isAttachedToWindow) return
        view.display?.let { server.observeDisplayRefreshRate(it.refreshRate) }
    }

    override fun onDisplayAdded(displayId: Int) { if (listening) refresh() }
    override fun onDisplayRemoved(displayId: Int) { if (listening) refresh() }
    override fun onDisplayChanged(displayId: Int) {
        if (listening && view.display?.displayId == displayId) refresh()
    }
}

/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver.requests

import com.winlator.core.RuntimeDiagnostics
import com.winlator.xconnector.XInputStream
import com.winlator.xconnector.XOutputStream
import com.winlator.xserver.Drawable
import com.winlator.xserver.Window
import com.winlator.xserver.XClient
import com.winlator.xserver.XServer.Lockable
import com.winlator.xserver.errors.BadDrawable
import com.winlator.xserver.errors.XRequestError
import java.io.IOException

/** Read current geometry without making window queries wait for pixel uploads. */
object GeometryRequests {
    @JvmStatic
    @Throws(IOException::class, XRequestError::class)
    fun getGeometry(client: XClient, input: XInputStream, output: XOutputStream) {
        val id = input.readInt()
        val server = client.xServer
        val counters = server.performance?.geometry?.takeIf { RuntimeDiagnostics.enabled() }
        val start = if (counters != null) System.nanoTime() else 0L
        var windowWait = 0L
        var drawableWait = 0L
        var windowPath = false
        var completed = false
        try {
            server.lock(Lockable.WINDOW_MANAGER).use {
                if (counters != null) windowWait = System.nanoTime() - start
                val window = server.windowManager.getWindow(id)
                val content = window?.content
                if (content != null && content.id == id) {
                    // Window creation, resize/replacement and destruction all hold WINDOW_MANAGER.
                    // Only read immutable dimensions/depth, never pixels, texture or SHM storage.
                    windowPath = true
                    writeReply(client, output, content, window)
                } else {
                    // Pixmaps and unknown IDs still use the drawable registry under its lock,
                    // including BadDrawable semantics and the established lock order.
                    val lockStart = if (counters != null) System.nanoTime() else 0L
                    server.lock(Lockable.DRAWABLE_MANAGER).use {
                        if (counters != null) drawableWait = System.nanoTime() - lockStart
                        val drawable = server.drawableManager.getDrawable(id) ?: throw BadDrawable(id)
                        writeReply(client, output, drawable, window)
                    }
                }
                completed = true
            }
        } finally {
            counters?.record(windowPath, !completed, windowWait, drawableWait, System.nanoTime() - start)
        }
    }

    private fun writeReply(client: XClient, output: XOutputStream, drawable: Drawable, window: Window?) {
        output.lock().use {
            output.writeGeometryReply(drawable.visual.depth, client.sequenceNumber,
                client.xServer.windowManager.rootWindow.id, window?.x ?: 0, window?.y ?: 0,
                drawable.width, drawable.height, window?.borderWidth ?: 0)
        }
    }
}

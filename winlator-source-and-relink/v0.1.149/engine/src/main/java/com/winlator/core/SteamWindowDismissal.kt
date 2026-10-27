/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import com.winlator.xconnector.XOutputStream
import com.winlator.xserver.Atom
import com.winlator.xserver.Property
import com.winlator.xserver.XServer
import com.winlator.xserver.events.Event

/** ICCCM polite close, delivered to the owning client only. Never destroys an X resource/process. */
object SteamWindowDismissal {
    enum class Result { STALE, REQUESTED, HIDE_ONLY }
    fun request(server: XServer, view: SteamWindowMinimizer.View): Result {
        val target = server.lock(XServer.Lockable.WINDOW_MANAGER).use {
            val window = server.windowManager.getWindow(view.id) ?: return Result.STALE
            if (window.processId != view.pid || window.handle != view.handle ||
                !window.attributes.isViewable) return Result.STALE
            val protocols = Atom.getId("WM_PROTOCOLS")
            val delete = Atom.getId("WM_DELETE_WINDOW")
            if (protocols <= 0 || delete <= 0) return Result.HIDE_ONLY
            val property = window.getProperty(protocols) ?: return Result.HIDE_ONLY
            if (property.type != Atom.ATOM.toInt() || property.format != Property.Format.INT_ARRAY ||
                property.data.capacity() % 4 != 0 || property.data.capacity() > 4096 ||
                (0 until property.data.capacity() / 4).none { property.getInt(it) == delete }) return Result.HIDE_ONLY
            val client = window.originClient ?: return Result.STALE
            client to DeleteWindowEvent(window.id, protocols, delete)
        }
        target.first.sendEvent(target.second)
        return Result.REQUESTED
    }

    private class DeleteWindowEvent(private val window: Int, private val protocols: Int, private val delete: Int) : Event(33 or 0x80) {
        override fun send(sequenceNumber: Short, outputStream: XOutputStream) {
            outputStream.lock().use {
                outputStream.writeByte(code)
                outputStream.writeByte(32.toByte())
                outputStream.writeShort(sequenceNumber)
                outputStream.writeInt(window)
                outputStream.writeInt(protocols)
                outputStream.writeInt(delete)
                // X11 CurrentTime; Android wall time is not an X server timestamp.
                outputStream.writeInt(0)
                outputStream.writePad(12)
            }
        }
    }
}

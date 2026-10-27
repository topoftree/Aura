/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xenvironment.components

import com.winlator.renderer.SharedGpuBridge
import com.winlator.renderer.SharedGpuTexture
import com.winlator.xenvironment.EnvironmentComponent
import com.winlator.xserver.Drawable
import com.winlator.xserver.XServer
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Session-owned, same-UID Unix endpoint. At most one pair of shared images is retained. */
class SharedGpuPresentComponent(private val server: XServer, private val path: String) : EnvironmentComponent() {
    private val running = AtomicBoolean()
    private val fdLock = Any()
    private var listener = -1
    private var client = -1

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        SharedGpuTexture.resetCounters()
        synchronized(fdLock) { listener = SharedGpuBridge.listen(path) }
        if (listener < 0) { running.set(false); return }
        Thread({
            try {
                while (running.get()) {
                    val fd = SharedGpuBridge.accept(listener)
                    if (fd < 0) continue
                    synchronized(fdLock) {
                        client = fd
                        if (!running.get()) SharedGpuBridge.shutdown(fd)
                    }
                    try { serve(fd) } catch (_: RuntimeException) {
                        // Disconnect means the guest uses its original X11 path.
                    } finally {
                        synchronized(fdLock) { SharedGpuBridge.close(fd); client = -1 }
                    }
                }
            } finally {
                synchronized(fdLock) { SharedGpuBridge.close(listener); listener = -1 }
                File(path).delete()
            }
        }, "AuraSharedGpu").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running.set(false)
        // Only the owning worker closes descriptors; shutdown wakes blocking I/O
        // without a close/reuse race against the next Android file descriptor.
        synchronized(fdLock) { SharedGpuBridge.shutdown(client); SharedGpuBridge.shutdown(listener) }
    }

    private fun gl(action: () -> Boolean): Boolean {
        val view = server.renderer?.xServerView ?: return false
        val work = FutureTask { action() }
        view.queueEvent(work)
        view.requestRender()
        return try { work.get(2, TimeUnit.SECONDS) } catch (_: Exception) { work.cancel(false); false }
    }

    private fun serve(fd: Int) {
        val hello = SharedGpuBridge.hello(fd) ?: return
        val buffers = LongArray(2)
        val live = AtomicBoolean(true)
        var texture: SharedGpuTexture? = null
        var drawable: Drawable? = null
        try {
            for (i in 0..1) {
                buffers[i] = SharedGpuBridge.receiveBuffer(fd, hello[1], hello[2])
                if (buffers[i] == 0L) return
            }
            var generation = 0L
            server.lock(XServer.Lockable.DRAWABLE_MANAGER).use {
                val d = server.drawableManager.getDrawable(hello[0]) ?: return
                synchronized(d.renderLock) {
                    if (d.width.toInt() != hello[1] || d.height.toInt() != hello[2] ||
                        d.isOffscreenStorage || d.isUseSharedData || d.texture.javaClass != com.winlator.renderer.Texture::class.java) return
                    drawable = d
                    generation = d.contentGeneration
                    texture = SharedGpuTexture(d, buffers[0], buffers[1])
                }
            }
            val d = drawable ?: return
            val t = texture ?: return
            if (!gl { running.get() && live.get() && t.prepare() } || !SharedGpuBridge.reply(fd, MAGIC)) return
            var serial = 0
            while (running.get()) {
                val p = SharedGpuBridge.present(fd) ?: break
                if (p.isEmpty()) continue
                val next = if (serial == -1) 1 else serial + 1
                if (p[1] != next) break
                val began = System.nanoTime()
                if (!gl {
                    server.lock(XServer.Lockable.DRAWABLE_MANAGER).use {
                        if (!live.get() || !running.get() || server.drawableManager.getDrawable(d.id) !== d) false
                        else synchronized(d.renderLock) {
                            if (serial == 0 && (d.contentGeneration != generation || d.texture.javaClass != com.winlator.renderer.Texture::class.java)) false
                            else if (serial != 0 && d.texture !== t) false
                            else if (!t.present(p[0])) false
                            else {
                                if (serial == 0) { d.texture.destroy(); d.texture = t }
                                d.onDrawListener?.run()
                                val now = System.nanoTime()
                                server.performance?.content?.record(com.winlator.core.RuntimeContentCounters.Path.SHARED_GPU,
                                    d.id, d.width.toInt(), d.height.toInt(), now, now - began, -1)
                                true
                            }
                        }
                    }
                }) break
                serial = p[1]
                if (!SharedGpuBridge.reply(fd, serial)) break
            }
        } finally {
            live.set(false)
            buffers.forEach(SharedGpuBridge::release)
            val t = texture
            val d = drawable
            if (t != null && d != null) {
                // Queued after any timed-out job; the independent AHB references
                // remain owned by t until GL has finished reading them.
                val view = server.renderer?.xServerView
                if (view == null) t.destroy() else view.queueEvent {
                    synchronized(d.renderLock) {
                        if (d.texture === t) {
                            t.snapshotTo(d.data)
                            t.setNeedsUpdate(true)
                        } else t.destroy()
                    }
                }
                view?.requestRender()
            }
        }
    }

    companion object { const val MAGIC = 0x41554731 }
}

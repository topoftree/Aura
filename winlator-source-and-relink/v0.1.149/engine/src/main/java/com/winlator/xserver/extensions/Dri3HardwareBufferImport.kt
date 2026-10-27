/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver.extensions

import com.winlator.renderer.GPUImage
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xserver.Drawable
import com.winlator.xserver.XClient
import com.winlator.xserver.XServer
import com.winlator.xserver.errors.*
import java.util.concurrent.atomic.AtomicLong

/** The bundled Android Vulkan Wrapper sends modifier 1255 with an AHB socket.
 * This is its ordinary WSI transport, independent of the optional shared-image experiment.
 * Called with the X11 window, pixmap and drawable locks held.
 */
class Dri3HardwareBufferImport(private val server: XServer) {
    private val requested = AtomicLong()
    private val imported = AtomicLong()
    private val rejected = AtomicLong()

    @Throws(XRequestError::class)
    fun receive(client: XClient, pixmapId: Int, windowId: Int, width: Short, height: Short,
                depth: Byte, bpp: Int, planes: Int, offset: Int, unusedPlanes: Boolean, fd: Int) {
        requested.incrementAndGet()
        var drawable: Drawable? = null
        var image: GPUImage? = null
        var accepted = false
        try {
            if (server.windowManager.getWindow(windowId) == null) throw BadWindow(windowId)
            if (!client.isValidResourceId(pixmapId) || server.pixmapManager.getPixmap(pixmapId) != null ||
                server.drawableManager.getDrawable(pixmapId) != null) throw BadIdChoice(pixmapId)
            if (fd < 0 || planes != 1 || bpp != 32 || depth.toInt() !in setOf(24, 32) ||
                server.pixmapManager.getVisualForDepth(depth) == null ||
                offset != 0 || unusedPlanes || width !in 1..8192 || height !in 1..8192 ||
                width.toLong() * height > 33_554_432) throw BadMatch()
            drawable = server.drawableManager.createGpuDrawable(pixmapId, width, height, depth)
                ?: throw BadIdChoice(pixmapId)
            image = GPUImage.importFromSocket(drawable, fd) ?: throw BadAlloc()
            drawable.texture = image
            val pixmap = server.pixmapManager.createPixmap(drawable) ?: throw BadIdChoice(pixmapId)
            client.registerAsOwnerOfResource(pixmap)
            imported.incrementAndGet()
            accepted = true
        } finally {
            if (!accepted) {
                rejected.incrementAndGet()
                // No GL work has used this just-created pixmap. Detach before removing it,
                // then release the received reference once, without queueing a second owner.
                drawable?.let { it.texture = null; server.drawableManager.removeDrawable(it.id) }
                image?.destroy()
                GPUImage.abortHardwareBufferImport(fd)
            }
            XConnectorEpoll.closeFd(fd)
        }
    }

    fun diagnostic() = "DRI3_AHB_V1 transport=WRAPPER_SOCKET_1255; requested=${requested.get()}; " +
        "imported=${imported.get()}; rejected=${rejected.get()}; game_frame=UNCONFIRMED"

    companion object { const val MODIFIER = 1255L }
}

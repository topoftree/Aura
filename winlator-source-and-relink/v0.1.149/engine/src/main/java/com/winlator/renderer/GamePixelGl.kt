/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import android.opengl.GLES30.*
import com.winlator.core.GameVisualTrace
import com.winlator.core.PixelRead
import com.winlator.core.HardwareBufferPixelRead
import com.winlator.xserver.Drawable
import java.nio.ByteBuffer
import kotlin.math.floor

/** Bounded manual probes. Never clears GL errors, changes pixels or exports an image. */
object GamePixelGl {
    private fun integer(name: Int) = IntArray(1).also { glGetIntegerv(name, it, 0) }[0]
    private fun points(width: Int, height: Int) = IntArray(18).also { values ->
        for (y in 0..2) for (x in 0..2) {
            val at = (y * 3 + x) * 2
            values[at] = ((x + 1) * width / 4).coerceIn(0, width - 1)
            values[at + 1] = ((y + 1) * height / 4).coerceIn(0, height - 1)
        }
    }
    /** Saves all state affecting destination memory, including callers with a pack buffer bound. */
    private fun read(framebuffer: Int, texture: Int?, at: IntArray): PixelRead {
        val previous = integer(GL_READ_FRAMEBUFFER_BINDING)
        val pack = integer(GL_PIXEL_PACK_BUFFER_BINDING)
        val keys = intArrayOf(GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS)
        val old = keys.map(::integer)
        val scratch = IntArray(1)
        try {
            if (texture != null) {
                glGenFramebuffers(1, scratch, 0)
                if (scratch[0] == 0) return PixelRead(null, "NO_FRAMEBUFFER")
                glBindFramebuffer(GL_READ_FRAMEBUFFER, scratch[0])
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
                glReadBuffer(GL_COLOR_ATTACHMENT0)
            } else glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer)
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                return PixelRead(null, "INCOMPLETE_FRAMEBUFFER")
            if (integer(GL_READ_BUFFER) == GL_NONE) return PixelRead(null, "READ_BUFFER_DISABLED")
            if (integer(GL_IMPLEMENTATION_COLOR_READ_TYPE) != GL_UNSIGNED_BYTE)
                return PixelRead(null, "UNSUPPORTED_READ_TYPE")
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
            keys.forEachIndexed { index, key -> glPixelStorei(key, if (index == 0) 1 else 0) }
            val bytes = ByteBuffer.allocateDirect(4)
            val values = IntArray(36)
            for (i in 0..8) {
                if (at[2 * i] < 0 || at[2 * i + 1] < 0) return PixelRead(null, "OUTSIDE_VIEWPORT")
                // An untouched sentinel is unknown, not a black pixel. RGBA/UBYTE is the ES normalized-color read pair.
                repeat(4) { bytes.put(it, 0xA5.toByte()) }; bytes.position(0)
                glReadPixels(at[2 * i], at[2 * i + 1], 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
                if ((0..3).all { bytes.get(it) == 0xA5.toByte() }) return PixelRead(null, "UNCHANGED_SENTINEL")
                repeat(4) { values[4 * i + it] = bytes.get(it).toInt() and 255 }
            }
            return PixelRead(values, "OBSERVED")
        } finally {
            keys.forEachIndexed { index, key -> glPixelStorei(key, old[index]) }
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pack)
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previous)
            if (scratch[0] != 0) glDeleteFramebuffers(1, scratch, 0)
        }
    }
    @JvmStatic fun beginFrame(owner: Any) { GameVisualTrace.pixels(owner)?.beginDrawFrame() }
    @JvmStatic fun beforePresent(owner: Any, drawable: Drawable) { GameVisualTrace.pixels(owner)?.invalidate(drawable) }
    @JvmStatic fun copy(owner: Any, drawable: Drawable, source: Texture, target: Texture) {
        val capture = GameVisualTrace.pixels(owner) ?: return
        val ticket = capture.copied(drawable, target, drawable.width.toInt(), drawable.height.toInt(), target.textureId) ?: return
        ticket.sample("COPY") {
            if (source.owner?.width?.toInt() != ticket.width || source.owner?.height?.toInt() != ticket.height)
                return@sample "state=SOURCE_DIMENSIONS_CHANGED"
            if (!source.isAllocated || !target.isAllocated) return@sample "state=TEXTURE_UNAVAILABLE"
            val at = points(ticket.width, ticket.height)
            // The production copy has completed, and Present has not returned its source yet.
            // This reads the same allocation without changing usage, pixels or GPU synchronization.
            val bufferBegin = System.nanoTime()
            val buffer = HardwareBufferPixelRead.decode((source as? GPUImage)?.diagnosticHardwareBufferPixels(
                ticket.width, ticket.height), ticket.width, ticket.height)
            val bufferEnd = System.nanoTime()
            if (!ticket.current()) return@sample "state=CANCELLED"
            val a = read(0, source.textureId, at)
            if (!ticket.current()) return@sample "state=CANCELLED"
            val b = read(0, target.textureId, at)
            ticket.pair(a, b) + "; buffer_read_begin_ns=$bufferBegin; buffer_read_end_ns=$bufferEnd; " + buffer.compare(a)
        }
    }
    @JvmStatic fun drawn(owner: Any, drawable: Drawable, texture: Texture, transform: FloatArray,
                        viewWidth: Int, viewHeight: Int) {
        val ticket = GameVisualTrace.pixels(owner)?.drawing(drawable, texture, texture.textureId) ?: return
        ticket.sample("DRAW") {
            if (drawable.data != null || drawable.width.toInt() != ticket.width || drawable.height.toInt() != ticket.height)
                return@sample "state=DRAW_CONTENT_CHANGED"
            if (!texture.isAllocated) return@sample "state=TEXTURE_UNAVAILABLE"
            val sourcePoints = points(ticket.width, ticket.height)
            val viewport = IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it, 0) }
            val at = project(sourcePoints, ticket.width, ticket.height,
                texture.isFlipY, transform, viewWidth, viewHeight, viewport)
                ?: return@sample "state=UNSUPPORTED_TRANSFORM"
            val framebuffer = integer(GL_DRAW_FRAMEBUFFER_BINDING)
            val source = read(0, texture.textureId, sourcePoints)
            if (!ticket.current()) return@sample "state=CANCELLED"
            val output = read(framebuffer, null, at)
            // Intermediate effect targets may use another scale; do not reuse those coordinates as final output.
            if (framebuffer == 0) ticket.expectOutput(at, viewport, output)
            "comparison=CURRENT_TEXTURE_TO_DRAW; framebuffer=${if (framebuffer == 0) "DEFAULT" else "INTERMEDIATE"}; " +
                source.summary("texture") + "; " + output.summary("draw") + "; " + source.difference(output)
        }
    }
    @JvmStatic fun output(owner: Any) {
        val ticket = GameVisualTrace.pixels(owner)?.output() ?: return
        val at = ticket.points ?: return
        ticket.sample("OUTPUT") {
            val viewport = IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it, 0) }
            if (integer(GL_DRAW_FRAMEBUFFER_BINDING) != 0 || !viewport.contentEquals(ticket.viewport))
                "state=OUTPUT_TRANSFORM_CHANGED"
            else {
                val output = read(0, null, at)
                "comparison=DRAW_TO_SAME_HOST_FRAME_OUTPUT; " + output.summary("output") + "; " +
                    (ticket.drawn ?: PixelRead(null, "DRAW_UNAVAILABLE")).difference(output)
            }
        }
    }
    /** Mirrors WindowMaterial's actual texture coordinates and viewport transform. */
    fun project(points: IntArray, width: Int, height: Int, flip: Boolean, transform: FloatArray,
                viewWidth: Int, viewHeight: Int, viewport: IntArray): IntArray? {
        if (width <= 0 || height <= 0 || viewWidth <= 0 || viewHeight <= 0 ||
            viewport.size != 4 || viewport[2] <= 0 || viewport[3] <= 0 ||
            transform.size != 6 || transform.any { !it.isFinite() }) return null
        val result = IntArray(points.size)
        for (i in points.indices step 2) {
            val u = (points[i] + .5) / width
            val v = ((points[i + 1] + .5) / height).let { if (flip) 1.0 - it else it }
            val x = (transform[0] * u + transform[2] * v + transform[4]) / viewWidth
            val y = (transform[1] * u + transform[3] * v + transform[5]) / viewHeight
            if (!x.isFinite() || !y.isFinite() || x !in 0.0..<1.0 || y !in 0.0..<1.0) return null
            result[i] = viewport[0] + floor(x * viewport[2]).toInt()
            result[i + 1] = viewport[1] + floor((1.0 - y) * viewport[3]).toInt()
            if (result[i] < 0 || result[i + 1] < 0) return null
        }
        return result
    }
}

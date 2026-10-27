/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import android.opengl.GLES20
import com.winlator.xserver.Drawable
import java.nio.ByteBuffer

/** Two slots, with the current slot held until the next successful present.
 * All slot changes/destruction run on GL, under the drawable's renderLock.
 * CPU access first snapshots the current slot and writes retire the transport.
 */
class SharedGpuTexture internal constructor(owner: Drawable, first: Long, second: Long) : Texture(owner) {
    private val sessionEpoch = synchronized(countLock) { epoch }
    private val buffers = longArrayOf(first, second)
    private val images = LongArray(2)
    private val textures = IntArray(2)
    private var current = -1
    private var mirrored = true
    private var retired = false
    private var context = 0L

    init { buffers.forEach(SharedGpuBridge::acquire) }

    internal fun prepare(): Boolean {
        context = SharedGpuBridge.context()
        if (context == 0L) return false
        GLES20.glGenTextures(2, textures, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
            setTextureParameters()
            images[i] = SharedGpuBridge.image(buffers[i], textures[i])
            if (images[i] == 0L) return false
        }
        return true
    }

    internal fun present(slot: Int): Boolean {
        if (retired || slot !in 0..1 || slot == current || images[slot] == 0L || context != SharedGpuBridge.context()) return false
        // ACK permits the producer to overwrite the PREVIOUS slot. Finish every
        // previous GL read before changing ownership; never ACK the current slot as free.
        GLES20.glFinish()
        current = slot
        textureId = textures[slot]
        mirrored = false
        damage.clear()
        count(0)
        return true
    }

    /** Called under renderLock. No copies occur on the ordinary GPU present path. */
    fun snapshotTo(target: ByteBuffer?) {
        if (mirrored || current < 0 || buffers[current] == 0L) return
        if (target == null || !SharedGpuBridge.snapshot(buffers[current], target)) {
            // A gralloc failure must not kill the X11/GL thread or leave stale
            // pixels masquerading as the current image. Retire; the next guest
            // present is rejected and its normal full CPU frame replaces this.
            target?.duplicate()?.apply { clear(); while (hasRemaining()) put(0.toByte()) }
            retired = true
            mirrored = true
            damage.markAll()
            count(2)
            return
        }
        mirrored = true
        count(1)
    }

    override fun setNeedsUpdate(needsUpdate: Boolean) {
        if (needsUpdate) retired = true
        super.setNeedsUpdate(needsUpdate)
    }

    fun replacingCpuData() { mirrored = true; retired = true; damage.markAll() }

    override fun updateFromDrawable() {
        if (!retired && context != SharedGpuBridge.context()) {
            snapshotTo(owner?.data)
            retired = true
            damage.markAll()
        }
        if (!retired) return
        releaseGpu()
        super.updateFromDrawable()
    }

    private fun releaseGpu() {
        if (buffers.all { it == 0L }) return
        val sameContext = context != 0L && context == SharedGpuBridge.context()
        if (sameContext) GLES20.glFinish()
        textureId = 0 // the slot IDs below are deleted exactly once
        images.forEach(SharedGpuBridge::destroyImage)
        // Old numeric GL names may belong to unrelated textures after context loss.
        if (sameContext) GLES20.glDeleteTextures(2, textures, 0)
        buffers.forEach(SharedGpuBridge::release)
        images.fill(0); textures.fill(0); buffers.fill(0)
    }

    override fun destroy() {
        retired = true
        releaseGpu()
        super.destroy()
    }

    private fun count(index: Int) = synchronized(countLock) {
        if (sessionEpoch == epoch) counts[index]++
    }

    companion object {
        private val countLock = Any()
        private val counts = LongArray(3)
        private var epoch = 0L
        @JvmStatic fun counters() = synchronized(countLock) { counts.copyOf() }
        @JvmStatic fun resetCounters() = synchronized(countLock) { ++epoch; counts.fill(0) }
    }
}

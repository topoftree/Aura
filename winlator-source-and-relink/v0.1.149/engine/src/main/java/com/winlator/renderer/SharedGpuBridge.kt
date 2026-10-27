/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** Private, versioned transport. Handles carry all AHB FDs and metadata, never just the first FD. */
@Keep
internal object SharedGpuBridge {
    init { System.loadLibrary("winlator") }
    @JvmStatic external fun listen(path: String): Int
    @JvmStatic external fun accept(server: Int): Int
    @JvmStatic external fun hello(fd: Int): IntArray?
    @JvmStatic external fun receiveBuffer(fd: Int, width: Int, height: Int): Long
    @JvmStatic external fun present(fd: Int): IntArray?
    @JvmStatic external fun reply(fd: Int, serial: Int): Boolean
    @JvmStatic external fun shutdown(fd: Int)
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun acquire(buffer: Long)
    @JvmStatic external fun release(buffer: Long)
    @JvmStatic external fun context(): Long
    @JvmStatic external fun image(buffer: Long, texture: Int): Long
    @JvmStatic external fun destroyImage(image: Long)
    @JvmStatic external fun snapshot(buffer: Long, target: ByteBuffer): Boolean
}

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.sysvshm

import java.nio.ByteBuffer
import java.util.IdentityHashMap

/** Owned descriptors and mappings have independent lifetimes, as with IPC_RMID/shmdt. */
class SharedMemoryRegistry(private val memory: Memory) {
    interface Memory {
        fun create(id: Int, size: Long): Int
        fun map(fd: Int, size: Long): ByteBuffer?
        fun unmap(data: ByteBuffer, size: Long)
        fun close(fd: Int)
    }

    private class Segment(val id: Int, var fd: Int, val size: Long) {
        var data: ByteBuffer? = null
        var references = 0
    }
    private val segments = mutableMapOf<Int, Segment>()
    // ByteBuffer equality/hash depend on its mutable contents/position. Ownership must use identity.
    private val mappings = IdentityHashMap<ByteBuffer, Segment>()
    private var nextId = 0
    private var closed = false

    @Synchronized fun get(size: Long): Int {
        if (closed || size <= 0 || size > Int.MAX_VALUE || nextId == Int.MAX_VALUE) return -1
        val id = ++nextId
        val fd = memory.create(id, size)
        if (fd < 0) return -1
        segments[id] = Segment(id, fd, size)
        return id
    }

    @Synchronized fun getFd(id: Int): Int = segments[id]?.fd ?: -1

    @Synchronized fun attach(id: Int): ByteBuffer? {
        val segment = segments[id]?.takeIf { it.fd >= 0 } ?: return null
        val data = segment.data ?: memory.map(segment.fd, segment.size)?.also {
            segment.data = it
            mappings[it] = segment
        } ?: return null
        segment.references++
        return data
    }

    /** A drawable using SHM directly pins it independently of the X11 attachment. */
    @Synchronized fun retain(data: ByteBuffer): Boolean {
        val segment = mappings[data] ?: return false
        segment.references++
        return true
    }

    @Synchronized fun detach(data: ByteBuffer) {
        val segment = mappings[data] ?: return
        check(segment.references > 0)
        if (--segment.references == 0) {
            memory.unmap(data, segment.size)
            mappings.remove(data)
            segment.data = null
            if (segment.fd < 0) segments.remove(segment.id)
        }
    }

    @Synchronized fun delete(id: Int) {
        val segment = segments[id] ?: return
        if (segment.fd >= 0) {
            memory.close(segment.fd)
            segment.fd = -1
        }
        // Keep mapped bytes findable until the last XID/drawable releases them.
        if (segment.references == 0) segments.remove(id)
    }

    /** Terminal shutdown, after X requests and render-side references have been stopped. */
    @Synchronized fun close() {
        if (closed) return
        closed = true
        for (segment in segments.values) {
            segment.data?.let { memory.unmap(it, segment.size) }
            if (segment.fd >= 0) memory.close(segment.fd)
        }
        mappings.clear()
        segments.clear()
    }

    @Synchronized fun diagnostic(): String = "SHM_MEMORY_V1 segments=${segments.size}; " +
        "mapped=${mappings.size}; mapped_bytes=${mappings.values.sumOf { it.size }}; " +
        "delete_pending=${segments.values.count { it.fd < 0 }}; scope=HOST_SYSV_MAPPINGS; resident=UNMEASURED"
}

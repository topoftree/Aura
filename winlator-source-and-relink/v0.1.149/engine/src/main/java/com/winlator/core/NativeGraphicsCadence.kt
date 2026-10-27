/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** A retained inode owns each manual lease. New generations never accept old returns. */
class NativeGraphicsCadence private constructor(private val leases: List<Lease>, private val attempted: Int) : AutoCloseable {
    private data class Lease(val slot: Int, val channel: FileChannel, val generation: Long, val pid: Int, val ticks: Long)
    data class Snapshot(val files: Map<String, ByteArray>, val report: String)
    private var revoked = false
    private var frozen: Snapshot? = null

    @Synchronized fun revoke() {
        if (revoked) return
        revoked = true
        leases.forEach { runCatching { put(it.channel, 20, 0, true) } }
    }

    @Synchronized fun snapshot(): Snapshot {
        frozen?.let { return it }
        revoke()
        val files = linkedMapOf<String, ByteArray>()
        val report = StringBuilder("GRAPHICS_CADENCE_V1 attempted=$attempted; armed=${leases.size}; max_processes=4; " +
            "rows_per_process=8192; window_ms=10000; clock=HOST_MONOTONIC_NS; cpu=CALLING_THREAD; " +
            "scope=VULKAN_WRAPPER_INCLUSIVE; gpu_execution=UNKNOWN; physical_fps=UNKNOWN\n" +
            "Full accepted call entries/returns are in graphics-cadence-NN.bin. ENTERED means return unobserved, not a proven hung call. " +
            "Other processes, missing wrappers, observer skips and budget exhaustion remain unknown. No Vulkan handles or arguments are stored.\n")
        for (lease in leases) {
            runCatching {
                val parsed = GraphicsCadenceSnapshot.filter(read(lease.channel), read(lease.channel), lease.generation, lease.pid, lease.ticks)
                files["graphics-cadence-${lease.slot.toString().padStart(2, '0')}.bin"] = parsed.bytes
                report.append("PROCESS slot=${lease.slot}; ").append(parsed.report()).append('\n')
            }.onFailure { report.append("PROCESS slot=${lease.slot}; state=UNREADABLE_OR_CHANGED\n") }
        }
        leases.forEach { runCatching { it.channel.close() } }
        return Snapshot(files, report.toString()).also { frozen = it }
    }

    @Synchronized override fun close() {
        revoke()
        leases.forEach { runCatching { it.channel.close() } }
    }

    companion object {
        const val WINDOW = 10_000_000_000L
        /** Candidates have already been associated with this session's target EXE. */
        fun arm(directory: File, candidates: List<Pair<String, GraphicsTraceSnapshot.Result>>, now: Long): NativeGraphicsCadence {
            val leases = mutableListOf<Lease>()
            if (now <= 0 || now > Long.MAX_VALUE - WINDOW) return NativeGraphicsCadence(emptyList(), candidates.size)
            for ((name, identity) in candidates.take(4)) {
                var channel: FileChannel? = null
                var ownsLease = false
                runCatching {
                    require(name.matches(Regex("graphics-(0[1-9]|1[0-6])\\.bin")))
                    val file = File(directory, name.replace("graphics-", "cadence-"))
                    channel = FileChannel.open(file.toPath(), READ, WRITE, NOFOLLOW_LINKS)
                    val ch = requireNotNull(channel)
                    check(ch.size() == GraphicsCadenceSnapshot.BYTES.toLong())
                    val b = ByteBuffer.wrap(read(ch)).order(LITTLE_ENDIAN)
                    check(GraphicsCadenceSnapshot.validHeader(b.array(), armed = false))
                    check(b.getInt(16) == identity.pid && b.getLong(24) == identity.startTicks && identity.activity % 2 == 0L)
                    val previous = b.getLong(32)
                    check(previous >= 0 && previous % 2 == 0L && previous < Long.MAX_VALUE - 2)
                    val generation = previous + 2
                    ownsLease = true
                    // Revoke before changing the lease. Never reset native cursors or row locks.
                    put(ch, 20, 0, true); put(ch, 32, generation - 1)
                    put(ch, 40, identity.activity); put(ch, 48, now); put(ch, 56, now + WINDOW)
                    val next = ByteBuffer.allocate(8).order(LITTLE_ENDIAN)
                    while (next.hasRemaining()) check(ch.read(next, 72L + next.position()) > 0)
                    check(next.getLong(0) >= 0); put(ch, 64, next.getLong(0))
                    put(ch, 32, generation); put(ch, 20, 1, true)
                    leases += Lease(leases.size + 1, ch, generation, identity.pid, identity.startTicks)
                    channel = null
                }.onFailure { channel?.let { if (ownsLease) runCatching { put(it, 20, 0, true) }; runCatching { it.close() } } }
            }
            return NativeGraphicsCadence(leases, candidates.size)
        }
        private fun put(ch: FileChannel, offset: Long, value: Long, int: Boolean = false) {
            val b = ByteBuffer.allocate(if (int) 4 else 8).order(LITTLE_ENDIAN)
            if (int) b.putInt(value.toInt()) else b.putLong(value)
            b.flip()
            while (b.hasRemaining()) check(ch.write(b, offset + b.position()) > 0)
        }
        private fun read(ch: FileChannel): ByteArray {
            check(ch.size() == GraphicsCadenceSnapshot.BYTES.toLong())
            val b = ByteBuffer.allocate(GraphicsCadenceSnapshot.BYTES)
            while (b.hasRemaining()) check(ch.read(b, b.position().toLong()) > 0)
            check(ch.size() == GraphicsCadenceSnapshot.BYTES.toLong())
            return b.array()
        }
    }
}

object GraphicsCadenceSnapshot {
    const val BYTES = 524416
    const val CAPACITY = 8192
    data class Event(val call: Long, val begin: Long, val end: Long, val cpu: Long, val tid: Int,
                     val api: Int, val result: Int, val state: Int)
    data class Result(val bytes: ByteArray, val events: List<Event>, val rejected: Int, val begun: Long, val next: Long,
                      val start: Long, val deadline: Long, val drops: Long, val late: Long) {
        fun report(): String = buildString {
            append("state=FILTERED; start_ns=$start; deadline_ns=$deadline; reserved=${next - begun}; retained=${events.size}; " +
                "entered_return_unobserved=${events.count { it.state == 1 }}; rejected=$rejected; " +
                "budget_reached=${next - begun >= CAPACITY}; lifetime_dropped=$drops; lifetime_return_contention=$late\n")
            events.filter { it.state == 2 }.sortedByDescending { it.end - it.begin }.take(12).forEach {
                append("SLOW call=${it.call}; api=${it.api}; tid=${it.tid}; begin_ns=${it.begin}; end_ns=${it.end}; " +
                    "wall_ns=${it.end - it.begin}; cpu_ns=${it.cpu}; result=${it.result}\n")
            }
            events.filter { it.state == 1 }.take(12).forEach {
                append("ENTERED call=${it.call}; api=${it.api}; tid=${it.tid}; begin_ns=${it.begin}; return=UNOBSERVED\n")
            }
        }
    }
    fun validHeader(bytes: ByteArray, armed: Boolean = true): Boolean = runCatching {
        if (bytes.size != BYTES || !bytes.copyOfRange(0, 8).contentEquals("AURAGC1\u0000".toByteArray())) return false
        val b = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        b.getInt(8) == 1 && b.getInt(12) == CAPACITY && b.getInt(16) > 0 && b.getInt(20) in 0..1 && b.getLong(24) > 0 &&
            b.getLong(32) >= (if (armed) 2 else 0) && b.getLong(32) % 2 == 0L && b.getLong(40) >= 0 && b.getLong(40) % 2 == 0L &&
            b.getLong(64) >= 0 && b.getLong(72) >= b.getLong(64) && b.getLong(80) >= 0 && b.getLong(88) >= 0 &&
            (!armed || b.getLong(48) > 0 && b.getLong(56) > b.getLong(48) && b.getLong(56) - b.getLong(48) <= NativeGraphicsCadence.WINDOW)
    }.getOrDefault(false)

    fun filter(first: ByteArray, second: ByteArray, generation: Long, pid: Int, ticks: Long): Result {
        require(validHeader(first) && validHeader(second))
        val a = ByteBuffer.wrap(first).order(LITTLE_ENDIAN); val b = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        require(first.copyOfRange(0, 72).contentEquals(second.copyOfRange(0, 72)))
        require(b.getInt(20) == 0 && b.getLong(32) == generation && b.getInt(16) == pid && b.getLong(24) == ticks)
        val clean = ByteArray(BYTES); second.copyInto(clean, 0, 0, 104)
        val base = b.getLong(64); val next = b.getLong(72); val start = b.getLong(48); val deadline = b.getLong(56)
        require(next - base in 0..CAPACITY.toLong())
        val events = mutableListOf<Event>(); var rejected = 0
        for (index in 0 until CAPACITY) {
            val o = 128 + index * 64
            if (b.getLong(o + 8) != generation) continue
            val seq = b.getLong(o); val call = b.getLong(o + 16); val begin = b.getLong(o + 24); val end = b.getLong(o + 32)
            val cpu = b.getLong(o + 40); val tid = b.getInt(o + 48); val api = b.getInt(o + 52)
            val result = b.getInt(o + 56); val state = b.getInt(o + 60)
            val good = seq > 0 && seq % 2 == 0L && a.getLong(o) == seq &&
                first.copyOfRange(o, o + 64).contentEquals(second.copyOfRange(o, o + 64)) &&
                call > base && call <= next && (call - 1) % CAPACITY == index.toLong() && begin in start until deadline &&
                tid > 0 && api in 1..13 && (state == 1 && end == 0L && cpu == 0L && result == 0 ||
                state == 2 && end in begin until deadline && (cpu >= 0 || cpu == -1L))
            if (good) {
                second.copyInto(clean, o, o, o + 64); events += Event(call, begin, end, cpu, tid, api, result, state)
            } else rejected++
        }
        return Result(clean, events.sortedBy { it.call }, rejected, base, next, start, deadline, b.getLong(80), b.getLong(88))
    }
}

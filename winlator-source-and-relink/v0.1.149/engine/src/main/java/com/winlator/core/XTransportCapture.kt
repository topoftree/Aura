/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** Socket-read and decoded-dispatch boundaries only, never a network arrival timestamp. */
class XTransportCapture(private val start: Long, private val clock: () -> Long) {
    private data class Client(val slot: Int, var read: Long = 0)
    private val clients = IdentityHashMap<Any, Client>()
    private val rows = ArrayList<LongArray>()
    private val lock = ReentrantLock()
    private val skipped = AtomicLong()
    private var overflow = 0L
    @Volatile private var stopped = false
    @Volatile private var reliable = true
    private fun live() = !stopped && clock() - start in 0 until 10_000_000_000L
    private fun <T> observe(block: () -> T): T? {
        if (!live()) return null
        if (!lock.tryLock()) { skipped.incrementAndGet(); reliable = false; return null }
        return try { if (live()) block() else null } finally { lock.unlock() }
    }
    // id, kind, client, begin, end, most recent read id, bytes, buffered before,
    // buffered after, linked host Present id, result (-1=return unobserved).
    private fun reserve(client: Any, kind: Long, bytes: Long, buffered: Long): LongArray? = observe {
        if (rows.size >= 4800) { overflow++; reliable = false; return@observe null }
        val state = clients[client] ?: run {
            if (clients.size == 8) { overflow++; return@observe null }
            Client(clients.size).also { clients[client] = it }
        }
        val r = longArrayOf(rows.size + 1L, kind, state.slot.toLong(), clock() - start, -1,
            if (reliable) state.read else 0, bytes, buffered, -1, 0, -1)
        rows += r
        if (kind == 1L) { state.read = r[0]; r[5] = 0 }
        r
    }
    inner class Read internal constructor(private val row: LongArray) {
        fun returned(bytes: Int, buffered: Int) { observe {
            row[4] = clock() - start; row[6] = bytes.toLong(); row[8] = buffered.toLong(); row[10] = 1
        } }
    }
    inner class Dispatch internal constructor(private val row: LongArray) {
        fun linked(capture: XTransportCapture, present: Long) {
            if (capture !== this@XTransportCapture || row[1] != 2L) return
            observe { if (present > 0) row[9] = present }
        }
        fun returned(success: Boolean, buffered: Int) { observe {
            row[4] = clock() - start; row[8] = buffered.toLong(); row[10] = if (success) 1 else 0
        } }
    }
    fun beginRead(client: Any, buffered: Int): Read? = reserve(client, 1, -1, buffered.toLong())?.let(::Read)
    fun dispatch(client: Any, present: Boolean, bytes: Int, buffered: Int): Dispatch? =
        reserve(client, if (present) 2 else 3, bytes.toLong(), buffered.toLong())?.let(::Dispatch)
    fun stop() {
        lock.lock()
        try { stopped = true; clients.clear() } finally { lock.unlock() }
    }
    fun reports(): Map<String, String> {
        lock.lock()
        return try {
            val result = linkedMapOf("x11-transport.txt" to
                "X11_TRANSPORT_V1 start_ns=$start; clock=HOST_MONOTONIC_NS; rows=${rows.size}; limit=4800; " +
                "clients=8; skipped=${skipped.get()}; overflow=$overflow; read_association_reliable=$reliable\n" +
                "kind=1_SOCKET_READ_2_PRESENT_DISPATCH_3_OTHER_DISPATCH; timestamps=RELATIVE_NS; " +
                "entry=JAVA_CALLBACK_NOT_KERNEL_WAKE; read=HOST_RECV_CALL_NOT_GUEST_SEND; bytes=COUNTS_ONLY; " +
                "read_id=MOST_RECENT_READ_NOT_COMPLETE_PACKET_PROVENANCE; partial_requests_may_span_reads=true; " +
                "present_id=CAPTURE_LOCAL_HOST_PRESENT_0_UNKNOWN; result=-1_RETURN_UNOBSERVED_0_FAILED_1_RETURNED; " +
                "no_opcodes_payload_paths_fds_or_addresses=true; dispatch_includes_child_copy_and_wait=true\n")
            rows.chunked(150).forEachIndexed { index, chunk ->
                result["x11-transport-${(index + 1).toString().padStart(2, '0')}.txt"] =
                    "X11_TRANSPORT_ROWS_V1 start_ns=$start; columns=id,kind,client,begin,end,read_id,bytes,buffered_before,buffered_after,present_id,result\n" +
                        chunk.joinToString("\n", postfix = "\n") { it.joinToString(",") }
            }
            result
        } finally { lock.unlock() }
    }
}

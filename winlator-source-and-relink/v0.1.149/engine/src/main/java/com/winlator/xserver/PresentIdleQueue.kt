/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver

import java.util.PriorityQueue
import java.util.concurrent.ScheduledFuture
import java.util.function.LongSupplier
import java.util.function.IntConsumer

/**
 * Pending Idle notifications for copies that have ALREADY completed.
 * Enqueue, drain and lifecycle cancellation run under WINDOW_MANAGER, so a
 * timer and a new Present cannot return the same image twice. The small internal
 * monitor also permits the existing diagnostics worker to read counters without
 * taking the window lock. Callbacks run outside that monitor, under the caller's
 * window lock. The copy thread may drain older, completed copies while awaiting
 * its own completion fence; the current unfinished copy is never in this queue.
 */
class PresentIdleQueue @JvmOverloads constructor(private val clock: LongSupplier = LongSupplier(System::nanoTime)) {
    class Ticket internal constructor(
        internal val owner: Any,
        internal val bufferId: Long,
        internal val deadlineNs: Long,
        internal val sequence: Long,
        internal val action: IntConsumer,
    ) {
        internal var pending = true
        internal var future: ScheduledFuture<*>? = null
    }

    private val pending = PriorityQueue(compareBy<Ticket> { it.deadlineNs }.thenBy { it.sequence })
    private var enqueued = 0L
    private var beforeCopy = 0L
    private var timer = 0L
    private var duringSourceWait = 0L
    private var cancelled = 0L
    private var maximumPending = 0

    fun nowNs(): Long = clock.asLong

    /** Manual diagnostics only. Ticket identity stays in-process; no buffer ID is exported. */
    data class Eligibility internal constructor(val ticket: Ticket?, val pendingCount: Int,
        val eligibleCount: Int, val aliasCount: Int, val unknownCount: Int, val sourceKnown: Boolean) {
        val deadlineNs: Long get() = ticket?.deadlineNs ?: Long.MAX_VALUE
    }

    @Synchronized
    fun inspectEligibility(protectedBufferId: Long): Eligibility = Eligibility(
        firstEligible(protectedBufferId), pending.size,
        pending.count { protectedBufferId != 0L && it.bufferId != 0L && it.bufferId != protectedBufferId },
        pending.count { protectedBufferId != 0L && it.bufferId == protectedBufferId },
        pending.count { it.bufferId == 0L }, protectedBufferId != 0L)

    @Synchronized
    fun enqueue(owner: Any, bufferId: Long, deadlineNs: Long, action: IntConsumer): Ticket =
        Ticket(owner, bufferId, deadlineNs, enqueued++, action).also {
            pending.add(it)
            maximumPending = maxOf(maximumPending, pending.size)
        }

    @Synchronized
    fun attachFuture(ticket: Ticket, future: ScheduledFuture<*>) {
        if (ticket.pending) ticket.future = future else future.cancel(false)
    }

    /** A fixed cutoff never consumes an unexpired reservation to make room. */
    fun dispatchDue(beforeNextCopy: Boolean): Int = dispatchDue(if (beforeNextCopy) BEFORE_COPY else TIMER)

    @JvmOverloads fun dispatchDue(source: Int, protectedBufferId: Long? = null): Int {
        require(source in TIMER..DURING_SOURCE_WAIT)
        val cutoff = nowNs()
        var count = 0
        while (true) {
            val ticket = takeDue(cutoff, source, protectedBufferId) ?: return count
            ticket.action.accept(source)
            count++
        }
    }

    @Synchronized
    @JvmOverloads fun nextDeadlineNs(protectedBufferId: Long? = null): Long =
        firstEligible(protectedBufferId)?.deadlineNs ?: Long.MAX_VALUE

    /** A different wrapper/pixmap may alias the same allocation. Unknown IDs
     * cannot establish distinct buffers and must wait until the current copy ends. */
    private fun firstEligible(protectedBufferId: Long?): Ticket? {
        if (protectedBufferId == null) return pending.peek()
        if (protectedBufferId == 0L) return null
        var first: Ticket? = null
        for (candidate in pending) {
            if (candidate.bufferId == 0L || candidate.bufferId == protectedBufferId) continue
            val current = first
            if (current == null || candidate.deadlineNs < current.deadlineNs ||
                candidate.deadlineNs == current.deadlineNs && candidate.sequence < current.sequence) first = candidate
        }
        return first
    }

    @Synchronized
    private fun takeDue(cutoff: Long, source: Int, protectedBufferId: Long?): Ticket? {
        val ticket = firstEligible(protectedBufferId) ?: return null
        if (ticket.deadlineNs > cutoff) return null
        pending.remove(ticket)
        retire(ticket)
        when (source) {
            BEFORE_COPY -> beforeCopy++
            DURING_SOURCE_WAIT -> duringSourceWait++
            else -> timer++
        }
        return ticket
    }

    @Synchronized
    fun cancelOwner(owner: Any) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val ticket = iterator.next()
            // A replacement window with the same XID is a different owner.
            if (ticket.owner === owner) {
                iterator.remove()
                retire(ticket)
                cancelled++
            }
        }
    }

    @Synchronized
    fun clear() {
        for (ticket in pending) retire(ticket)
        cancelled += pending.size
        pending.clear()
    }

    private fun retire(ticket: Ticket) {
        ticket.pending = false
        ticket.future?.cancel(false) // never interrupt a running callback
        ticket.future = null
    }

    @Synchronized
    fun diagnostic(): String = "PRESENT_IDLE_V1 scope=HOST_SESSION; queued=$enqueued; " +
        "dequeued_before_copy=$beforeCopy; dequeued_timer=$timer; cancelled=$cancelled; " +
        "dequeued_source_wait=$duringSourceWait; " +
        "pending=${pending.size}; max_pending=$maximumPending; " +
        "deadline=HOST_TIMER; gpu_completion=REQUIRED_BEFORE_QUEUE; scanout=UNMEASURED"

    companion object {
        const val TIMER = 1
        const val BEFORE_COPY = 2
        const val DURING_SOURCE_WAIT = 3
    }
}

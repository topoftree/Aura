/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import android.opengl.GLES20
import android.opengl.GLES30
import com.winlator.core.FramePipelineCapture
import com.winlator.xserver.PresentIdleQueue

/**
 * Same-context completion of an imported-source copy. Caller retains the window,
 * pixmap and drawable locks. Only ALREADY-completed, expired older copies may be
 * returned while this copy waits; its own Complete/Idle still follow completion.
 * No polling thread, early source reuse, or destination read-fence bypass.
 */
object PresentCompletionWait {
    private const val MAX_WAITS = 8
    private const val MAX_WAIT_NS = 50_000_000L
    private const val WAIT_QUANTUM_NS = 1_000_000L

    @JvmStatic fun await(queue: PresentIdleQueue, sourceBufferId: Long, trace: FramePipelineCapture.Frame?) {
        var mode = 1 // original glFinish, no older pending reservation
        var calls = 0
        var timeouts = 0
        var waitNs = 0L
        var pumpNs = 0L
        var pumped = 0
        var reason = 0 // 1=create, 2=wait, 3=attempt budget, 4=unknown, 5=no eligible Idle, 6=alias/unknown, 7=elapsed budget
        var finishNs = 0L
        var createNs = 0L
        var deleteNs = 0L
        var sync = 0L
        var selection: PresentIdleQueue.Eligibility? = null
        fun now() = if (trace != null) System.nanoTime() else 0L
        fun nextDeadline(): Long {
            selection = if (trace?.isObserving() == true) queue.inspectEligibility(sourceBufferId) else null
            return selection?.deadlineNs ?: queue.nextDeadlineNs(sourceBufferId)
        }
        fun step(kind: Int, timeout: Long): FramePipelineCapture.WaitStep? =
            selection?.let { trace?.waitStep(calls + 1, kind, timeout, reason, it) }
        fun drain() {
            val begin = now()
            pumped += queue.dispatchDue(PresentIdleQueue.DURING_SOURCE_WAIT, sourceBufferId)
            pumpNs += now() - begin
        }
        fun finish() {
            // Re-read qualification at fallback, not the previous wait's selection.
            nextDeadline()
            val record = step(2, -1)
            val actualBegin = if (record != null) queue.nowNs() else 0L
            val begin = now()
            GLES20.glFinish()
            finishNs += now() - begin
            if (record != null) record.completed(actualBegin, queue.nowNs(), -1)
        }
        try {
            drain()
            if (nextDeadline() == Long.MAX_VALUE) {
                if (queue.nextDeadlineNs() != Long.MAX_VALUE) reason = 6
                finish()
                return
            }
            val createBegin = now()
            sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            createNs = now() - createBegin
            if (sync == 0L) {
                reason = 1
                finish()
                return
            }
            mode = 2
            val waitStart = queue.nowNs()
            while (true) {
                drain()
                val deadline = nextDeadline()
                val current = queue.nowNs()
                val remainingBudget = MAX_WAIT_NS - (current - waitStart).coerceAtLeast(0L)
                if (deadline == Long.MAX_VALUE || calls == MAX_WAITS || remainingBudget <= 0) {
                    mode = 3
                    reason = when { deadline == Long.MAX_VALUE -> 5; remainingBudget <= 0 -> 7; else -> 3 }
                    finish()
                    return
                }
                // Sub-ms waits can return before the deadline on coarse driver
                // timers. Round UP, never return the buffer early, and keep both
                // wall-time and attempt caps. A signalled fence still returns early.
                val remaining = (deadline - current).coerceIn(1L, MAX_WAIT_NS)
                val timeout = (((remaining + WAIT_QUANTUM_NS - 1) / WAIT_QUANTUM_NS) * WAIT_QUANTUM_NS)
                    .coerceAtMost(remainingBudget)
                val record = step(1, timeout)
                val actualBegin = if (record != null) queue.nowNs() else 0L
                val begin = now()
                val result = GLES30.glClientWaitSync(sync,
                    if (calls == 0) GLES30.GL_SYNC_FLUSH_COMMANDS_BIT else 0, timeout)
                waitNs += now() - begin
                if (record != null) record.completed(actualBegin, queue.nowNs(), result)
                calls++
                when (result) {
                    GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> return
                    GLES30.GL_TIMEOUT_EXPIRED -> timeouts++
                    else -> {
                        mode = 3
                        reason = if (result == GLES30.GL_WAIT_FAILED) 2 else 4
                        // A failed observer fence must not become a successful
                        // copy. Preserve the previous blocking completion path.
                        finish()
                        return
                    }
                }
            }
        } finally {
            if (sync != 0L) {
                val begin = now()
                GLES30.glDeleteSync(sync)
                deleteNs = now() - begin
            }
            trace?.completionWait(mode, calls, timeouts, waitNs, pumpNs, pumped, reason, finishNs, createNs, deleteNs)
        }
    }
}

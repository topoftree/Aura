/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

interface ManualGameObservation {
    fun start(capture: String)
    fun pulse()
    fun stop(): String
}

/** Owns the short guest lease. Polling never resumes a paused guest or changes its timing. */
class GameCadenceObservation(private val observer: ManualGameObservation?, capture: String,
                             private val current: () -> Boolean, private val onStop: () -> Unit = {}) {
    @Volatile private var running = true
    @Volatile private var error = false
    @Volatile private var result = "SMAPI_LOOP_V1 state=OBSERVER_WORKER_PENDING\n"
    private val started = System.nanoTime()
    private val thread = Thread({
        try {
            if (current()) observer?.start(capture)
            while (running && current() && System.nanoTime() - started < 10_000_000_000L) {
                observer?.pulse(); Thread.sleep(250)
            }
        } catch (_: InterruptedException) { /* stop requested */ }
        catch (_: Exception) { error = true }
        finally {
            running = false
            runCatching(onStop)
            result = try { (if (error) "SMAPI_OBSERVER_V1 state=CONTROL_ERROR\n" else "") +
                (observer?.stop() ?: "SMAPI_LOOP_V1 state=NOT_APPLICABLE_OR_UNAVAILABLE\n") }
            catch (_: Exception) { "SMAPI_LOOP_V1 state=OBSERVER_READ_FAILED\n" }
        }
    }, "aura-game-cadence").apply { isDaemon = true; start() }
    fun requestStop() { running = false }
    fun stop(): String {
        requestStop(); thread.join(1100)
        if (thread.isAlive) return "SMAPI_LOOP_V1 state=OBSERVER_WORKER_PENDING\n"
        return result
    }
}

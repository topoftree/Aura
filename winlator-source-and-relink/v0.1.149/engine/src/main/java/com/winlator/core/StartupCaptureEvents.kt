/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Process-local, bounded lifecycle notices survive Activity handoffs, without retaining diagnostics. */
object StartupCaptureEvents {
    data class Event(val captureId: String, val game: String, val state: String, val saved: Boolean) {
        val id: String get() = "startup-capture:$captureId:$state"
        val started: Boolean get() = state == "RECORDING"
    }
    private val lock = Any()
    private var pending = emptyList<Event>()
    private val listeners = mutableSetOf<(List<Event>) -> Unit>()

    fun publish(event: Event) = synchronized(lock) {
        if (pending.none { it.id == event.id }) {
            pending = (pending + event).takeLast(16)
            notifyListeners()
        }
    }

    fun claim(id: String): Boolean = synchronized(lock) {
        if (pending.none { it.id == id }) return false
        pending = pending.filterNot { it.id == id }
        notifyListeners()
        true
    }

    fun observe(listener: (List<Event>) -> Unit): AutoCloseable = synchronized(lock) {
        listeners += listener
        listener(pending)
        AutoCloseable { synchronized(lock) { listeners -= listener } }
    }

    // Consumers only copy this list to their observable state; no capture/writer calls under this lock.
    private fun notifyListeners() { listeners.toList().forEach { runCatching { it(pending) } } }
}

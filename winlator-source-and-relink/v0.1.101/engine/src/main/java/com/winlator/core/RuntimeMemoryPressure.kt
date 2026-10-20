/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** A conservative GTFO-only early exit, not a prediction of Android's LMK policy or a RAM cap. */
class RuntimeMemoryPressure {
    private var since: Long? = null
    private var previous: Long? = null
    private var triggered = false

    fun observe(elapsedMillis: Long, available: Long?, threshold: Long?, lowMemory: Boolean?): Boolean {
        if (triggered) return false
        val validAvailable = available?.takeIf { it >= 0 }
        val reserve = reserveBytes(threshold)
        val underPressure = lowMemory == true || validAvailable != null && validAvailable <= reserve
        if (!underPressure || previous?.let { elapsedMillis - it !in 0..6000 } == true) since = null
        previous = elapsedMillis
        if (!underPressure) return false
        if (since == null) since = elapsedMillis
        if (elapsedMillis - since!! < 4000) return false
        triggered = true
        return true
    }

    companion object {
        private const val MIB = 1024L * 1024
        fun reserveBytes(threshold: Long?): Long = maxOf(1024 * MIB,
            threshold?.takeIf { it in 1..(Long.MAX_VALUE - 512 * MIB) }?.plus(512 * MIB) ?: 0)
    }
}

/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.alsaserver

/** A bounded virtual PCM queue. Hardware route loss must not stall Wine's audio clock. */
class ReflectorClock(private val sampleRate: Int, latencyFrames: Int) {
    private val budget = latencyFrames.toLong() * 1_000_000_000L / sampleRate
    private var deadline = Long.MIN_VALUE

    fun reset() { deadline = Long.MIN_VALUE }

    /** Returns the delay before accepting this block; a producer cannot run ahead forever. */
    fun accept(frames: Int, nowNanos: Long): Long {
        require(frames >= 0)
        deadline = maxOf(deadline, nowNanos) + frames.toLong() * 1_000_000_000L / sampleRate
        return (deadline - nowNanos - budget).coerceAtLeast(0)
    }
}

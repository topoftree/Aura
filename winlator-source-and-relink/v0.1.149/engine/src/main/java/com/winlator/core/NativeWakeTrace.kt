/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

/** Retired stall tracing: sanitize legacy launch variables; retain decoding for archived bundles. */
object NativeWakeTrace {
    const val LIBRARY = "libaura_wake_trace.so"
    const val VARIABLE = "AURA_WINE_WAKE_TRACE"
    const val DIRECTORY = "AURA_WINE_WAKE_DIRECTORY"
    const val BYTES = 4096 + 8192 * 96

    fun silence(env: EnvVars) {
        env.put(VARIABLE, "0")
        env.remove(DIRECTORY)
        val kept = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != LIBRARY }.joinToString(" ")
        if (kept.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", kept)
    }

}

/** In-flight/changed slots are omitted; no synthetic event is inferred from a torn live read. */
object WakeTraceSnapshot {
    data class Result(val bytes: ByteArray, val retained: Int, val omitted: Int)
    fun validHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length != NativeWakeTrace.BYTES || bytes.size < 64) return false
        val data = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        return bytes.copyOfRange(0, 8).contentEquals("AURAWK1\u0000".toByteArray()) &&
            data.getInt(8) == 1 && data.getInt(12) == 96 && data.getInt(16) == 8192 &&
            data.getInt(20) > 0 && data.getInt(40) > 0 && data.getInt(44) == 1 &&
            data.getInt(52) in 0..32
    }

    fun filter(first: ByteArray, second: ByteArray): Result {
        require(validHeader(first) && validHeader(second))
        val before = ByteBuffer.wrap(first).order(LITTLE_ENDIAN)
        val after = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        require(before.getInt(20) == after.getInt(20) && before.getInt(40) == after.getInt(40) &&
            before.getLong(56) == after.getLong(56))
        val output = second.copyOf()
        // Module discovery is published once; retain metadata only if both copies agree.
        if (!first.copyOfRange(52, 56).contentEquals(second.copyOfRange(52, 56)) ||
            !first.copyOfRange(128, 2176).contentEquals(second.copyOfRange(128, 2176))) {
            ByteBuffer.wrap(output).order(LITTLE_ENDIAN).putInt(52, 0)
            output.fill(0, 128, 2176)
        }
        var retained = 0
        var omitted = 0
        val next = after.getLong(24)
        require(next >= 0)
        for (slot in 0 until 8192) {
            val at = 4096 + slot * 96
            val seq = after.getLong(at)
            val ticket = seq / 2
            val stable = seq > 0 && seq % 2 == 0L && seq == after.getLong(at + 80) &&
                ticket <= next && ticket > (next - 8192).coerceAtLeast(0) &&
                (ticket - 1) % 8192 == slot.toLong() &&
                after.getInt(at + 52) in 1..8 &&
                (at until at + 96).all { first[it] == second[it] }
            if (stable) retained++ else {
                if (seq != 0L || before.getLong(at) != 0L) omitted++
                output.fill(0, at, at + 96)
            }
        }
        return Result(output, retained, omitted)
    }
}

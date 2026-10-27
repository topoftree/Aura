/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Fixed capability vocabulary. Missing registry/auxv data is unknown, not unsupported. */
object FexCpuAudit {
    private val names = listOf("LSE", "RCPC", "RCPC2", "SVE2", "SVE", "I8MM", "DOTPROD")
    fun valid(a: List<Long>) = a.size == 8 && a.all { it >= 0 } && a.take(5).all { it <= 127 } &&
        a[5] <= 2048 && a[5] % 128 == 0L && a[6] <= 15 && a[7] <= 0xffffffffL

    fun report(a: List<Long>): String {
        if (!valid(a)) return "FEX_CPU_AUDIT_V1 state=UNAVAILABLE\n"
        fun selection(known: Long, bits: Long) = names.mapIndexed { i, name ->
            "$name=" + if (known and (1L shl i) == 0L) "UNKNOWN" else if (bits and (1L shl i) != 0L) "1" else "0"
        }.joinToString(",")
        fun differences(known: Long, before: Long, after: Long) = names.filterIndexed { i, _ ->
            val bit = 1L shl i
            known and bit != 0L && (before xor after) and bit != 0L
        }.joinToString(",").ifEmpty { "NONE_OBSERVED" }
        return "FEX_CPU_AUDIT_V1 state=OBSERVED; android=${selection(a[0], a[1])}; wine_registry=${selection(a[2], a[3])}; " +
            "fex_effective=${selection(127, a[4])}; android_wine_difference=${differences(a[0] and a[2], a[1], a[3])}; " +
            "wine_effective_difference=${differences(a[2], a[3], a[4])}; difference_is_not_automatically_error=true; " +
            "sve_context_policy=DISABLED; startup_thread_sve_bits=${a[5]}; cpu_override_and_errata=PRESERVED; cache_key=EFFECTIVE_HOST_FEATURES_AND_CONFIG\n" +
            "FEX_CACHE_NATIVE_V1 enabled=${a[6] and 1 != 0L}; reading=${a[6] and 2 != 0L}; writing=${a[6] and 4 != 0L}; " +
            "validation_only=${a[6] and 8 != 0L}; memory_budget_bytes=${a[7]}; budget=SOFT_PER_PROCESS; actual_hit=SEE_COUNTERS\n"
    }
}

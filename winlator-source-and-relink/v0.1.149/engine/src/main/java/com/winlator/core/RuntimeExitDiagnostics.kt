/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Small pure helpers so cleanup and later system-exit records cannot erase the first failure. */
object RuntimeExitDiagnostics {
    /** A long startup log must not push the actual exception/native trace beyond the report cap. */
    @JvmOverloads
    fun failureReport(header: String, stage: String, reason: String, details: String, output: String, memory: String,
                      performance: String = "", steam: String = "", nativePc: String = ""): String =
        buildString {
            appendLine(header.take(800))
            appendLine("阶段：${stage.take(80)}")
            if (nativePc.isNotEmpty()) appendLine(nativePc.take(1_400))
            appendLine(if (reason.length <= 4_000) reason
                else reason.take(1_500) + "\n[中间异常帧已省略]\n" + reason.takeLast(2_400))
            appendLine("客体输出摘要：\n${output.take(1_200)}")
            if (steam.isNotEmpty()) appendLine(steam.take(1_400))
            appendLine(memory.take(1_800))
            if (performance.isNotEmpty()) appendLine(recentPerformance(performance,
                minOf(2_000, (8_000 - length - 1).coerceAtLeast(0))))
            append(details.take(3_000))
        }.take(8_000)

    /** Keep whole newest samples: cutting a prefix can lose the final game/window observation. */
    private fun recentPerformance(value: String, limit: Int): String {
        if (limit <= 0) return ""
        if (value.length <= limit) return value
        val starts = Regex("(?m)^t=\\d+s window=").findAll(value).map { it.range.first }.toList()
        if (starts.isEmpty()) return value.take(limit) // Older/unstructured summaries retain their format.
        val header = value.substring(0, starts.first()).take(350)
        val omitted = "[较早性能采样已省略]\n"
        val available = limit - header.length - omitted.length
        val start = starts.firstOrNull { value.length - it <= available }
            ?: return "[性能采样超出剩余报告容量，未展示]".take(limit)
        return header + omitted + value.substring(start)
    }

    /** Keep the initial configuration and recent exit events instead of silently dropping the tail. */
    fun appendDetails(original: String, next: String): String {
        val combined = original + next + "\n"
        return if (combined.length <= 3_000) combined
        else combined.take(1_400) + "\n[中间记录已省略]\n" + combined.takeLast(1_500)
    }

    fun matchesExit(savedPid: Int, startedAt: Long, active: Boolean, finishedAt: Long, pid: Int, timestamp: Long): Boolean =
        savedPid > 0 && savedPid == pid && startedAt > 0 && timestamp >= startedAt &&
            (active || finishedAt >= startedAt && timestamp >= finishedAt && timestamp - finishedAt <= 30_000)

    fun append(original: String?, next: String): String {
        if (original.isNullOrEmpty()) return next.take(8_000)
        val separator = "\n后续诊断：\n"
        // Header + stage + complete native-PC field + exception can reach 6,287 chars.
        // Reserve that primary evidence even for a large follow-up. Short cleanup
        // records should not discard another 2,000 chars from the original report.
        val suffix = separator + next.take(8_000 - 6_400 - separator.length)
        return original.take(8_000 - suffix.length) + suffix
    }
}

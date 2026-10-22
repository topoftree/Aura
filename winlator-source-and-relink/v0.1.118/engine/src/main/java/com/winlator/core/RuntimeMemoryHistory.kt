/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

data class RuntimeMemorySnapshot(
    val elapsedMillis: Long,
    val totalBytes: Long?,
    val availableBytes: Long?,
    val thresholdBytes: Long?,
    val lowMemory: Boolean?,
    val hostRssBytes: Long?,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val guest: GuestMemorySample? = null,
)

/** Numeric, bounded telemetry. RSS, Java and native heaps overlap and must not be added together. */
class RuntimeMemoryHistory {
    private var baseline: RuntimeMemorySnapshot? = null
    private val recent = ArrayDeque<RuntimeMemorySnapshot>()
    private var minimumAvailable: Long? = null
    private var peakHostRss: Long? = null
    private var peakGuestRss: Long? = null
    private var samples = 0

    fun add(sample: RuntimeMemorySnapshot) {
        if (baseline == null) baseline = sample
        if (recent.size == 3) recent.removeFirst()
        recent.addLast(sample)
        sample.availableBytes?.let { minimumAvailable = minOf(minimumAvailable ?: it, it) }
        sample.hostRssBytes?.let { peakHostRss = maxOf(peakHostRss ?: it, it) }
        sample.guest?.rssBytes?.let { peakGuestRss = maxOf(peakGuestRss ?: it, it) }
        samples++
    }

    fun report(): String = buildString {
        appendLine("运行内存（MiB；约每 10 秒、窗口出现及保护触发时记录）：样本=$samples")
        appendLine("系统最低可用=${mib(minimumAvailable)}; 宿主最高 RSS=${mib(peakHostRss)}")
        appendLine("客体 RSS 合计最高=${mib(peakGuestRss)}")
        baseline?.takeIf { it != recent.firstOrNull() }?.let { appendLine("起始 ${line(it)}") }
        recent.forEach { appendLine(line(it)) }
        append("客体仅含已识别的本次会话；RSS 合计重复计算共享页，非 PSS/实际总占用。Java/native 与宿主 RSS 重叠；?=无法读取。")
    }

    private fun line(sample: RuntimeMemorySnapshot): String = with(sample) {
        "t=${elapsedMillis.coerceAtLeast(0) / 1000}s 系统可用/总量=${mib(availableBytes)}/${mib(totalBytes)}; " +
            "阈值=${mib(thresholdBytes)}; low=${lowMemory ?: "?"}; " +
            "宿主 RSS=${mib(hostRssBytes)}; Java=${mib(javaHeapBytes)}; native=${mib(nativeHeapBytes)}; " +
            "客体 RSS=${mib(guest?.rssBytes)}(测得/识别=${guest?.measured ?: "?"}/${guest?.processes ?: "?"}; 不可读=${guest?.unreadable ?: "?"})"
    }

    companion object {
        private fun mib(bytes: Long?): String = bytes?.takeIf { it >= 0 }?.div(1024 * 1024)?.toString() ?: "?"

        fun rssBytes(status: String): Long? {
            val fields = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+")) ?: return null
            if (fields.size != 2 || !fields[1].equals("kB", true)) return null
            return fields[0].toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1024 }?.times(1024)
        }

        fun exitSample(pssKiB: Long, rssKiB: Long, importance: Int): String =
            "系统退出记录内存：PSS=${mib(pssKiB.takeIf { it in 1..Long.MAX_VALUE / 1024 }?.times(1024))} MiB; " +
                "RSS=${mib(rssKiB.takeIf { it in 1..Long.MAX_VALUE / 1024 }?.times(1024))} MiB; importance=$importance\n" +
                "PSS/RSS 是系统历史采样，非退出瞬间；? 表示未提供。宿主 status 不是游戏退出码。"
    }
}

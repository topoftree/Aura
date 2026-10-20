/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/** One representative drawable per observed path, preferring a verified target window. */
object RuntimeContentReport {
    fun format(samples: List<RuntimeContentSample>, owners: Map<Int, GameWindowDiagnostics.Owner>): String {
        if (samples.isEmpty()) return "CONTENT_UPDATE_V1 state=NOT_OBSERVED; coverage=GLX_SHM_XPUT_XCOPY; fps=UNKNOWN"
        fun timing(value: RuntimeTiming) = if (value.count == 0L) "?/?" else
            String.format(Locale.ROOT, "%.2f/%.2f", value.averageMs, value.maxMs)
        return RuntimeContentCounters.Path.entries.mapNotNull { path ->
            val candidates = samples.filter { it.path == path }
            val selected = candidates.filter { owners[it.drawable] == GameWindowDiagnostics.Owner.TARGET }
                .ifEmpty { candidates }.maxByOrNull { it.updates } ?: return@mapNotNull null
            "CONTENT_UPDATE_V1 path=$path; drawable=${selected.drawable}; owner=${owners[selected.drawable] ?: GameWindowDiagnostics.Owner.UNKNOWN}" +
                "; updates=${selected.updates}; last_size=${selected.width}x${selected.height}; requested_pixels=${selected.requestedPixels}" +
                "; gap_ms=${timing(selected.intervals)}; work_ms=${timing(selected.work)}; lock_ms=${timing(selected.lock)}" +
                "; evicted_updates=${selected.evictedUpdates}; fps=UNKNOWN"
        }.joinToString("\n")
    }
}

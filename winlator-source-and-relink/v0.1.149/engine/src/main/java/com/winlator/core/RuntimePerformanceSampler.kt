/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.os.PowerManager
import java.io.File
import java.util.Locale

/** Runs only on the existing low-frequency memory worker. No game paths or raw guest text. */
internal class RuntimePerformanceSampler(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val history = ArrayDeque<String>()
    private val cpuFiles by lazy {
        runCatching { File("/sys/devices/system/cpu").listFiles().orEmpty()
            .mapNotNull { dir -> Regex("cpu([0-9]+)").matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()?.let {
                it to File(dir, "cpufreq/scaling_cur_freq")
            } }.sortedBy { it.first }.take(16) }.getOrDefault(emptyList())
    }

    fun report(elapsedMs: Long, counters: RuntimePerformanceCounters?,
               gameState: ((Int, List<Int>) -> GameWindowSampler.Sample)? = null): String {
        if (counters == null) return ""
        val sample = counters.snapshot()
        val content = counters.content.snapshot()
        val geometry = counters.geometry.snapshot()
        fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
        fun timing(value: RuntimeTiming) = if (value.count == 0L) "?/?" else "${number(value.averageMs)}/${number(value.maxMs)}"
        val frequencies = cpuFiles.joinToString(",") { (id, file) ->
            val value = runCatching { file.readText().trim().toLongOrNull()?.takeIf { it in 1..20_000_000 } }.getOrNull()
            "$id=${value ?: "?"}"
        }.ifEmpty { "?" }
        val thermal = runCatching { power?.currentThermalStatus }.getOrNull()
        val fex = runCatching { GuestProcessSession.fexPerformance() }.getOrNull()
        // A stale cached window ID with zero presents is not evidence that it rendered this interval.
        val observation = runCatching { gameState?.invoke(if (sample.presents > 0) sample.windowId else 0,
            content.map { it.drawable }.distinct()) }.getOrNull()
        val state = observation?.report
        val contentReport = RuntimeContentReport.format(content, observation?.contentOwners.orEmpty())
        history.addLast(buildString {
            append("t=${elapsedMs / 1000}s window=${sample.windowId} presents=${sample.presents}")
            append(" interval_ms(avg/max)=${timing(sample.intervals)} >50ms=${sample.over50ms} >100ms=${sample.over100ms}\n")
            append("copy(gpu/cpu)=${sample.gpuCopies}/${sample.cpuCopies} submit_ms=${timing(sample.copies)}")
            append(" present_lock_ms=${timing(sample.presentLocks)} draw_count=${sample.draws.count}")
            append(" draw_ms=${timing(sample.draws)} draw_lock_ms=${timing(sample.drawLocks)}\n")
            append("render_requests=${sample.renderRequests}; callbacks=${sample.renderCallbacks}; queue_ms=${timing(sample.renderQueue)}; callback_gap_ms=${timing(sample.renderGaps)}\n")
            append("idle_ms(planned_max/late_max)=${number(sample.idleScheduledMaxNs / 1_000_000.0)}/${number(sample.idleLateMaxNs / 1_000_000.0)}")
            append(" cpu_khz=[$frequencies] thermal_status=${thermal ?: "?"}")
            append("\n$contentReport")
            append("\n${geometry.report()}")
            NativeFrameCopy.hostReport()?.let { append("\n$it") }
            NativeFrameCopy.damageReport()?.let { append("\n$it") }
            val sharedGpu = com.winlator.renderer.SharedGpuTexture.counters()
            append("\nSHARED_GPU_V1 scope=HOST_SESSION; accepted_presents=${sharedGpu[0]}; cpu_snapshots=${sharedGpu[1]}; snapshot_failures=${sharedGpu[2]}; " +
                "fps=NOT_MEASURED; zero_cpu_copy=ACCEPTED_PRESENTS_ONLY")
            if (!state.isNullOrEmpty()) append("\n$state")
            if (fex != null) append("\n$fex")
        })
        val header = "PERFORMANCE_V1：约10秒采样；presents/interval 仅 X11 Present 路径，0 不表示游戏无画面；submit/draw 为宿主CPU墙钟耗时，非GPU执行时间。\n" +
            "cpu_khz 为采样瞬间频率；thermal_status 为系统温控级别（0正常，1–6逐渐严重），非温度；?=不可读。\n" +
            (if (state?.contains("GAME_EXECUTION_STATE_V1") == true)
                "GAME_EXECUTION 的 cpu_ms 为采样区间内所选进程各线程 CPU 用时合计；线程／等待是瞬时观察，leader 为 Linux 线程组首线程，不是游戏进度或死锁结论。\n" else "") +
            "GAME_THREAD_CPU 为连续存活线程用时，非整机CPU%；CONTENT_UPDATE 为内容更新而非游戏FPS，work 不含上游socket/外部锁/GPU执行或宿主EGL swap。\n" +
            "queue 为最早待处理重绘请求到回调开始的时间（请求可合并），非精确EGL／GPU耗时。\n" +
            (if (fex != null) "FEX 为可读存活线程累计值，线程退出可下降；jit_ticks 为原始计数器单位，非毫秒／CPU利用率。\n" else "")
        // Leave room for the explanatory header in RuntimeLaunchDiagnostics' 3500-character limit.
        // Drop whole old samples so the latest thread/content record is not cut in half on save.
        while (history.size > 4 || history.size > 1 &&
            header.length + history.sumOf { it.length + 1 } - 1 > 3500) history.removeFirst()
        return header + history.joinToString("\n")
    }
}

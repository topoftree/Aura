/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A launch snapshot is available even when a guest hangs or exits without a host crash. */
data class RuntimeLaunchSnapshot(
    val startedAt: Long,
    val finishedAt: Long,
    val active: Boolean,
    val header: String,
    val stage: String,
    val details: String,
    val memory: String,
    val guestOutput: String,
    val failure: String?,
    val performance: String = "",
    val steam: String = "",
    val nativePc: String = "",
) {
    val timeLabel: String get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(startedAt))

    val report: String get() = buildString {
        appendLine(header.take(800))
        appendLine("启动时间：$timeLabel")
        appendLine("会话状态：${if (active) "仍在运行" else if (finishedAt > 0) "已结束" else "宿主已停止"}")
        appendLine("阶段：${stage.take(80)}")
        appendLine("此记录用于排查加载卡住、掉帧或提前退出；会话仍在运行不代表游戏画面已就绪。")
        if (nativePc.isNotEmpty()) appendLine(nativePc.take(1_400))
        appendLine(details.take(3_000))
        appendLine("客体输出摘要：")
        appendLine(guestOutput.take(2_000).ifEmpty { "尚未启动输出采集" })
        if (steam.isNotEmpty()) appendLine(steam.take(1_400))
        appendLine(memory.take(1_800))
        appendLine(performance.take(3_500))
        failure?.let {
            appendLine("本次会话已记录的异常：")
            append(it.take(8_000))
        }
    }
}

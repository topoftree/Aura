/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.os.Process
import android.system.Os
import java.io.File
import java.nio.file.Files

/** Reads only session-owned processes; start-time rechecks reject PID/TID recycling. No signals. */
class ProcGameExecutionAccess(
    private val root: File = File("/proc"), private val uid: Int = Process.myUid(),
    private val hostPid: Int = Process.myPid(),
    private val owner: (File) -> Int = { Os.stat(it.path).st_uid },
    private val snapshot: () -> GuestProcessScan? = { GuestProcessSession.snapshot() },
) : GameExecutionAccess {
    override fun session() = snapshot()

    private fun text(file: File, limit: Int): String? = runCatching {
        file.inputStream().use { input ->
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) null else String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun stat(file: File) = text(file, 4096)?.let(GameExecutionStat::parse)

    private fun identity(process: GuestProcessInfo): GameExecutionStat? = runCatching {
        if (process.pid <= 1 || process.pid == hostPid || process.uid != uid ||
            owner(File(root, "${process.pid}")) != process.uid) return null
        stat(File(root, "${process.pid}/stat"))?.takeIf { it.pid == process.pid && it.startTicks == process.startTicks }
    }.getOrNull()

    override fun command(process: GuestProcessInfo): String? {
        if (identity(process) == null) return null
        val command = text(File(root, "${process.pid}/cmdline"), 16 * 1024)
        return command.takeIf { identity(process) != null }
    }

    override fun sample(process: GuestProcessInfo): GameExecutionProcess? {
        if (identity(process) == null) return null
        val threads = mutableListOf<GameExecutionThread>()
        var leader: GameExecutionThread? = null
        var complete = true
        fun readThread(tid: Int): GameExecutionThread? {
            val directory = File(root, "${process.pid}/task/$tid")
            val before = stat(File(directory, "stat"))
            val wait = GameExecutionThread.waitCategory(text(File(directory, "wchan"), 128))
            val after = stat(File(directory, "stat"))
            if (before == null || after == null || before.pid != tid || after.pid != tid ||
                before.startTicks != after.startTicks) return null
            return GameExecutionThread(after.state,
                if (before.state == after.state) wait else GameExecutionThread.Wait.UNKNOWN, after)
        }
        leader = readThread(process.pid)
        if (leader != null) threads += leader else complete = false
        try {
            Files.newDirectoryStream(File(root, "${process.pid}/task").toPath()).use { entries ->
                val iterator = entries.iterator()
                var visited = 0
                while (iterator.hasNext()) {
                    if (visited++ >= GameExecutionSampler.MAX_THREADS) { complete = false; break }
                    val directory = iterator.next().toFile()
                    val tid = directory.name.toIntOrNull()?.takeIf { it > 0 } ?: continue
                    if (tid == process.pid) continue
                    if (threads.size >= GameExecutionSampler.MAX_THREADS) { complete = false; break }
                    val thread = readThread(tid)
                    if (thread == null) complete = false else threads += thread
                }
            }
        } catch (_: Exception) { complete = false }
        val final = identity(process) ?: return null
        return GameExecutionProcess(final, threads, leader, complete && leader != null)
    }
}

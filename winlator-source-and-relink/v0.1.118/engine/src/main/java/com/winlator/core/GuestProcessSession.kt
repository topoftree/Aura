/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import java.util.UUID

/** The private marker survives fork/exec and reparenting. It is never included in a report. */
class GuestProcessSession private constructor(val token: String, val previousCleanupComplete: Boolean = false) {
    private val supervisor = GuestProcessSupervisor(Process.myUid(), Process.myPid(), token, AndroidGuestProcesses())
    @Volatile private var root: java.lang.Process? = null
    private data class FexSampling(val tmp: File, val statistics: Boolean, val cache: File?, val sinceMs: Long)
    @Volatile private var fexSampling: FexSampling? = null

    fun configureFex(tmp: File, statistics: Boolean, cache: File?) {
        fexSampling = FexSampling(tmp, statistics, cache, System.currentTimeMillis())
    }

    private fun fexPerformanceReport(): String? {
        val config = fexSampling ?: return null
        if (!config.statistics && config.cache == null) return null
        val size = config.cache?.let { runCatching { FexCodeCache.boundedSize(it) / 1024 }.getOrNull() }
        if (!config.statistics) return "FEX_V1 cache_kib=${size ?: "?"}; statistics=OFF"
        var read = 0
        var unavailable = 0
        var threads = 0
        val sums = LongArray(7)
        val processes = supervisor.snapshot().processes.take(16)
        for (process in processes) {
            val stats = FexStatisticsReader.read(File(config.tmp, "fex-${process.pid}-stats"), config.sinceMs)
            if (stats == null) { unavailable++; continue }
            read++; threads += stats.threads
            val values = longArrayOf(stats.jitCount, stats.jitTicks, stats.diskHits, stats.diskMisses,
                stats.smc, stats.unaligned, stats.floatFallbacks)
            for (i in sums.indices) sums[i] = if (sums[i] > Long.MAX_VALUE - values[i]) Long.MAX_VALUE else sums[i] + values[i]
        }
        // Missing files are normal for native Wine processes without a translated guest.
        return "FEX_V1 read=$read unavailable=$unavailable cache_kib=${size ?: "?"}; " +
            if (read == 0) "statistics=PENDING_OR_UNAVAILABLE" else
                FexStatistics(threads, sums[0], sums[1], sums[2], sums[3], sums[4], sums[5], sums[6]).diagnostic()
    }

    fun attachRoot(pid: Int) = supervisor.attachRoot(pid)
    fun attachProcess(process: java.lang.Process) { root = process }
    fun stop(): GuestCleanupResult {
        var rootHandleRequested = false
        val result = try { supervisor.terminate() } finally {
            // Retain the actual Process handle as a fallback when /proc is unreadable. Process
            // owns its exit state; do not fall back to signaling an unverified numeric PID.
            root?.let { process -> runCatching {
                if (process.isAlive) { process.destroyForcibly(); rootHandleRequested = true }
            } }
            root = null
            detach(this)
        }
        return result.copy(rootHandleRequested = rootHandleRequested)
    }
    fun pause(): GuestSignalResult {
        runCatching { NativeGraphicsTrace.activity(true) }
        return supervisor.signalAll(GuestProcessSupervisor.STOP)
    }
    fun resume(): GuestSignalResult = supervisor.signalAll(GuestProcessSupervisor.CONTINUE).also {
        runCatching { NativeGraphicsTrace.activity(false) }
    }

    companion object {
        @Volatile private var current: GuestProcessSession? = null

        @Synchronized private fun detach(session: GuestProcessSession) { if (current === session) current = null }

        @JvmStatic
        @Synchronized
        fun start(context: Context): GuestProcessSession {
            val preferences = context.getSharedPreferences(LegacyDataNames.GUEST_PROCESS_PREFERENCES, Context.MODE_PRIVATE)
            val pending = preferences.getStringSet("sessions", emptySet()).orEmpty()
                .filter { it.matches(Regex("[0-9a-f-]{36}")) }.take(4)
            // A killed Android host cannot run finally/cleanup. On the next launch look for exact
            // inherited markers from its saved sessions, not arbitrary Wine or system processes.
            val remaining = mutableSetOf<String>()
            for (token in pending) {
                val result = GuestProcessSession(token).stop()
                RuntimeLaunchDiagnostics.detail(context, "PREVIOUS_" + result.report())
                if (result.remaining > 0 || result.unreadable > 0) remaining += token
            }
            if (remaining.size == 4) throw RuntimeComponentException("GUEST_PROCESSES", "PREVIOUS_CLEANUP_INCOMPLETE")
            val session = GuestProcessSession(UUID.randomUUID().toString(), remaining.isEmpty())
            // Persist before spawning; a failed write must not create untracked descendants.
            if (!preferences.edit().putStringSet("sessions", remaining + session.token).commit())
                throw RuntimeComponentException("GUEST_PROCESSES", "SESSION_TRACKING_UNAVAILABLE")
            current = session
            return session
        }

        @JvmStatic fun memory(): GuestMemorySample? = current?.supervisor?.memory()
        internal fun snapshot(): GuestProcessScan? = current?.supervisor?.snapshot()
        @JvmStatic fun processes(): List<GuestProcessInfo> = current?.supervisor?.snapshot()?.processes.orEmpty()
        @JvmStatic fun fexPerformance(): String? = current?.fexPerformanceReport()
        @JvmStatic fun killTracked(pid: Int, startTicks: Long) =
            current?.supervisor?.signalTracked(pid, startTicks, GuestProcessSupervisor.KILL) ?: false
    }
}

private class AndroidGuestProcesses : GuestProcessAccess {
    private val uid = Process.myUid()

    override fun scan(): GuestProcessScan {
        val processes = mutableListOf<GuestProcessInfo>()
        var unreadable = 0
        val entries = File("/proc").list() ?: return GuestProcessScan(emptyList(), 1)
        for (entry in entries) {
            val pid = entry.toIntOrNull()?.takeIf { it > 1 && it != Process.myPid() } ?: continue
            if (!owned(pid)) continue
            val process = read(pid)
            if (process != null) processes += process
            else if (owned(pid)) unreadable++ // A process disappearing mid-scan is normal.
        }
        return GuestProcessScan(processes, unreadable)
    }

    private fun owned(pid: Int) = runCatching { Os.stat("/proc/$pid").st_uid == uid }.getOrDefault(false)

    override fun read(pid: Int): GuestProcessInfo? = runCatching {
        if (!owned(pid)) return null
        val stat = boundedText(File("/proc/$pid/stat"), 4096)
        val rss = runCatching { RuntimeMemoryHistory.rssBytes(boundedText(File("/proc/$pid/status"), 16384)) }.getOrNull()
        GuestProcessInfo.parse(stat, uid, rss)
    }.getOrNull()

    override fun matchesSession(process: GuestProcessInfo, token: String): Boolean = runCatching {
        if (!owned(process.pid)) return false
        val matched = GuestProcessInfo.hasSession(boundedText(File("/proc/${process.pid}/environ"), 128 * 1024), token)
        matched && read(process.pid)?.identity == process.identity
    }.getOrDefault(false)

    override fun sendSignal(pid: Int, signal: Int): Boolean = runCatching {
        // The supervisor rechecks UID and start time immediately before this call. The kernel
        // additionally enforces UID permissions; /proc reads may be denied by device policy.
        Os.kill(pid, signal)
        true
    }.getOrDefault(false)

    private fun boundedText(file: File, limit: Int): String = file.inputStream().use {
        String(it.readNBytes(limit), Charsets.UTF_8)
    }
}

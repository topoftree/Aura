/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.concurrent.atomic.AtomicBoolean

/** Explicit diagnostic, separate from the read-only periodic monitor and all launch decisions. */
class GameNativePcCapture(
    private val access: GameExecutionAccess,
    private val capture: (Int, Long, ByteArray) -> String,
) {
    fun collect(
        executable: String, activity: () -> GameWindowDiagnostics.Activity, detailed: Boolean = false,
        thread: GameHotThread? = null, clock: () -> Long = { 0 },
        leaderOrdinal: Int? = null,
        captureThread: ((GameHotThread, ByteArray) -> String)? = null,
    ): String {
        fun result(state: String) = "GAME_NATIVE_PC_V1 state=$state; samples=0; ready=UNCONFIRMED"
        val before = activity()
        if (before.paused) return result("PAUSED")
        if (leaderOrdinal != null && (!detailed || thread != null || leaderOrdinal !in 0..1))
            return result("INVALID_SELECTION")
        if (thread != null && (!detailed || captureThread == null || !thread.isFresh(clock(), before)))
            return result("STALE_THREAD")
        val name = executable.replace('\\', '/').substringAfterLast('/')
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..255 || name.any { it.code < 32 }) return result("UNAVAILABLE")
        val session = access.session() ?: return result("UNAVAILABLE")
        if (session.unreadable != 0 || session.processes.size > GameExecutionSampler.MAX_PROCESSES)
            return result("INCOMPLETE_SCAN")
        val matched = mutableListOf<GuestProcessInfo>()
        for (process in session.processes) {
            val command = access.command(process) ?: return result("INCOMPLETE_SCAN")
            if (GameExecutionSampler.matchesExecutable(name, command)) matched += process
        }
        if (matched.isEmpty()) return result("NOT_MATCHED")
        if (matched.size > GameExecutionSampler.MAX_MATCHES || matched.distinctBy { it.pid }.size != matched.size)
            return result("INCOMPLETE_SCAN")
        val ordered = matched.sortedWith(compareBy<GuestProcessInfo> { it.startTicks }.thenBy { it.pid })
        val process = when {
            thread != null -> ordered.singleOrNull { it.pid == thread.pid && it.startTicks == thread.processStart }
                ?: return result("IDENTITY_CHANGED")
            leaderOrdinal != null -> ordered[leaderOrdinal % ordered.size]
            else -> ordered.singleOrNull() ?: return result("AMBIGUOUS")
        }
        val sample = access.sample(process) ?: return result("UNAVAILABLE")
        if (sample.stat.pid != process.pid || sample.stat.startTicks != process.startTicks)
            return result("IDENTITY_CHANGED")
        if (thread != null) {
            if (thread.pid != process.pid || thread.processStart != process.startTicks)
                return result("IDENTITY_CHANGED")
            if (!sample.complete) return result("INCOMPLETE_SCAN")
            val worker = sample.threads.singleOrNull {
                it.stat?.pid == thread.tid && it.stat.startTicks == thread.threadStart
            } ?: return result("THREAD_CHANGED")
            if (worker.state !in "RS") return result("THREAD_STATE")
            if (!thread.isFresh(clock(), before)) return result("STALE_THREAD")
        }
        if (activity() != before) return result("TRANSITION")
        // Native code rechecks UID, start time, executable and stopped state around the attach.
        val native = if (thread == null) capture(process.pid, process.startTicks, bytes)
            else captureThread!!(thread, bytes)
        if (activity() != before) return result("TRANSITION")
        if (native.length !in 1..(if (detailed) 24576 else 1400) || !native.startsWith("state=") || '\u0000' in native)
            return result("UNAVAILABLE")
        if (!detailed) return "GAME_NATIVE_PC_V1 $native"
        val target = if (thread == null) "GAME_NATIVE_TARGET_V1 pid=${process.pid}; start_ticks=${process.startTicks}"
            else "GAME_NATIVE_TARGET_V2 pid=${thread.pid}; start_ticks=${thread.processStart}; " +
                "tid=${thread.tid}; thread_start_ticks=${thread.threadStart}; role=HOT_CPU; rank=${thread.rank}; " +
                "cpu_ms=${thread.cpuMs}; interval_ms=${thread.intervalMs}; ranked=COMPARABLE_WORKERS"
        val mode = when {
            thread != null -> "WORKER_IDENTITY"
            leaderOrdinal != null -> "ROTATING_MATCH"
            else -> "UNIQUE_MATCH"
        }
        // Rotation is bounded coverage, not evidence that a selected leader owns gameplay.
        return "GAME_NATIVE_PC_V1 $native\n$target\nGAME_NATIVE_SELECTION_V1 mode=$mode; " +
            "matched=${ordered.size}; selected=${ordered.indexOf(process) + 1}; gameplay_owner=UNCONFIRMED\n"
    }
}

object GameNativePcProbe {
    private val busy = AtomicBoolean(false)
    private val available by lazy { runCatching { System.loadLibrary("aura_game_probe") }.isSuccess }
    private external fun counterValueNative(): Long
    fun counterValue(): Long? = if (available) runCatching { counterValueNative().takeIf { it > 0 } }.getOrNull() else null
    private external fun counterFrequencyNative(): Long
    fun counterFrequency(): Long? = if (available) runCatching { counterFrequencyNative().takeIf { it > 0 } }.getOrNull() else null
    private external fun captureNative(pid: Int, startTicks: Long, executable: ByteArray): String
    private external fun captureDetailedNative(pid: Int, startTicks: Long, executable: ByteArray): String
    private external fun captureThreadDetailedNative(
        pid: Int, startTicks: Long, tid: Int, threadStartTicks: Long, executable: ByteArray,
    ): String

    /** Must be called on a background worker. The native tracer has independent child/parent deadlines. */
    fun collect(executable: String, activity: () -> GameWindowDiagnostics.Activity): String {
        return collectInternal(executable, activity, false)
    }

    fun collectDetailed(executable: String, activity: () -> GameWindowDiagnostics.Activity): String =
        collectInternal(executable, activity, true)

    /** The manual six-batch sequence has exactly two leader slots; never adds native stops. */
    fun collectRotatingLeaderDetailed(
        executable: String, activity: () -> GameWindowDiagnostics.Activity, ordinal: Int,
    ): String = collectInternal(executable, activity, true, leaderOrdinal = ordinal)

    fun collectHotThreadDetailed(
        executable: String, activity: () -> GameWindowDiagnostics.Activity, thread: GameHotThread?,
    ): String = if (thread == null) "GAME_NATIVE_PC_V1 state=NO_HOT_THREAD; samples=0; scope=LINUX_THREAD"
        else collectInternal(executable, activity, true, thread)

    private fun collectInternal(executable: String, activity: () -> GameWindowDiagnostics.Activity,
                                detailed: Boolean, thread: GameHotThread? = null, leaderOrdinal: Int? = null): String {
        if (!RuntimeDiagnostics.enabled()) return "GAME_NATIVE_PC_V1 state=DISABLED; samples=0"
        if (!busy.compareAndSet(false, true)) return "GAME_NATIVE_PC_V1 state=BUSY; samples=0"
        return try {
            if (!available) "GAME_NATIVE_PC_V1 state=LIBRARY_UNAVAILABLE; samples=0"
            else GameNativePcCapture(ProcGameExecutionAccess(threadLimit = if (detailed)
                GameExecutionSampler.DIAGNOSTIC_MAX_THREADS else GameExecutionSampler.MAX_THREADS),
                if (detailed) ::captureDetailedNative else ::captureNative)
                .collect(executable, activity, detailed, thread, android.os.SystemClock::elapsedRealtime, leaderOrdinal) { target, bytes ->
                    captureThreadDetailedNative(target.pid, target.processStart, target.tid, target.threadStart, bytes)
                }
        } catch (_: Exception) {
            "GAME_NATIVE_PC_V1 state=UNAVAILABLE; samples=0"
        } catch (_: LinkageError) {
            "GAME_NATIVE_PC_V1 state=LIBRARY_UNAVAILABLE; samples=0"
        } finally { busy.set(false) }
    }
}

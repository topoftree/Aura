/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import java.util.concurrent.atomic.AtomicBoolean

/** Explicit diagnostic, separate from the read-only periodic monitor and all launch decisions. */
class GameNativePcCapture(
    private val access: GameExecutionAccess,
    private val capture: (Int, Long, ByteArray) -> String,
) {
    fun collect(executable: String, activity: () -> GameWindowDiagnostics.Activity, detailed: Boolean = false): String {
        fun result(state: String) = "GAME_NATIVE_PC_V1 state=$state; samples=0; ready=UNCONFIRMED"
        val before = activity()
        if (before.paused) return result("PAUSED")
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
        val process = matched.singleOrNull() ?: return result("AMBIGUOUS")
        val sample = access.sample(process) ?: return result("UNAVAILABLE")
        if (sample.stat.pid != process.pid || sample.stat.startTicks != process.startTicks)
            return result("IDENTITY_CHANGED")
        if (activity() != before) return result("TRANSITION")
        // Native code rechecks UID, start time, executable and stopped state around the attach.
        val native = capture(process.pid, process.startTicks, bytes)
        if (activity() != before) return result("TRANSITION")
        return if (native.length in 1..(if (detailed) 24576 else 1400) && native.startsWith("state=") && '\u0000' !in native)
            "GAME_NATIVE_PC_V1 $native" + if (detailed)
                "\nGAME_NATIVE_TARGET_V1 pid=${process.pid}; start_ticks=${process.startTicks}\n" else ""
        else result("UNAVAILABLE")
    }
}

object GameNativePcProbe {
    private val busy = AtomicBoolean(false)
    private val available by lazy { runCatching { System.loadLibrary("aura_game_probe") }.isSuccess }
    private external fun captureNative(pid: Int, startTicks: Long, executable: ByteArray): String
    private external fun captureDetailedNative(pid: Int, startTicks: Long, executable: ByteArray): String

    /** Must be called on a background worker. The native tracer has independent child/parent deadlines. */
    fun collect(executable: String, activity: () -> GameWindowDiagnostics.Activity): String {
        return collectInternal(executable, activity, false)
    }

    fun collectDetailed(executable: String, activity: () -> GameWindowDiagnostics.Activity): String =
        collectInternal(executable, activity, true)

    private fun collectInternal(executable: String, activity: () -> GameWindowDiagnostics.Activity, detailed: Boolean): String {
        if (!RuntimeDiagnostics.enabled()) return "GAME_NATIVE_PC_V1 state=DISABLED; samples=0"
        if (!busy.compareAndSet(false, true)) return "GAME_NATIVE_PC_V1 state=BUSY; samples=0"
        return try {
            if (!available) "GAME_NATIVE_PC_V1 state=LIBRARY_UNAVAILABLE; samples=0"
            else GameNativePcCapture(ProcGameExecutionAccess(), if (detailed) ::captureDetailedNative else ::captureNative)
                .collect(executable, activity, detailed)
        } catch (_: Exception) {
            "GAME_NATIVE_PC_V1 state=UNAVAILABLE; samples=0"
        } catch (_: LinkageError) {
            "GAME_NATIVE_PC_V1 state=LIBRARY_UNAVAILABLE; samples=0"
        } finally { busy.set(false) }
    }
}

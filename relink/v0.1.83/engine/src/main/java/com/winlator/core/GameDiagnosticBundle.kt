/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.winlator.xserver.XServer
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Source paths stay in process memory and are bound before that launch starts its clients. */
object GameDiagnosticBundle {
    private data class Session(val id: String, val sources: MutableMap<String, GameDiagnosticSource> = linkedMapOf())
    private var session: Session? = null
    private val busy = AtomicBoolean(false)

    @Synchronized fun discard() { session = null }

    @Synchronized fun begin(id: String) { session = Session(id) }

    @Synchronized fun watch(id: String, entry: String, file: File, fromStart: Boolean = false, guest: Boolean = false,
                            beforeRead: () -> Boolean = { true }) {
        val current = session?.takeIf { it.id == id } ?: return
        if (entry in current.sources || current.sources.size >= 12) return
        runCatching { GameDiagnosticSource.watch(entry, file, if (guest) 24 * 1024 * 1024 else 1024 * 1024, fromStart, beforeRead) }
            .getOrNull()?.let { current.sources[entry] = it }
    }

    @Synchronized private fun sources(id: String): List<GameDiagnosticSource>? =
        session?.takeIf { it.id == id }?.sources?.values?.toList()

    /** Worker only. A pause, new launch or Activity close ends the sequence without resuming anything. */
    fun collect(context: Context, id: String, executable: String, server: XServer,
                active: () -> Boolean, saveSummary: (String) -> Unit): Boolean {
        if (!RuntimeLaunchDiagnostics.isCollecting(context) || !busy.compareAndSet(false, true)) return false
        val store = File(context.filesDir, GameDiagnosticArchive.STORE)
        val diagnostics = server.gameDiagnostics
        val summary = GameDiagnosticSummary()
        var begun = false
        var completed = 0
        var state = "INTERRUPTED"
        try {
            val watched = sources(id) ?: return false
            if (diagnostics == null || !active()) return false
            val capture = UUID.randomUUID().toString()
            val report = RuntimeLaunchDiagnostics.snapshotForBundle(context, id) ?: return false
            GameDiagnosticArchive.begin(store, id, capture, System.currentTimeMillis(), report)
            begun = true
            saveSummary(summary.render("COLLECTING"))
            GameDiagnosticArchive.logs(store, watched) { sources(id) != null }
            val sampler = GameExecutionSampler(executable, ProcGameExecutionAccess(),
                runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(0))
            state = GameDiagnosticSequence.run(
                current = { active() && sources(id) != null && !diagnostics.activity().paused },
                sample = { index ->
                    val before = SystemClock.elapsedRealtime()
                    // Do not drain the performance monitor's counters; present ownership is unknown here.
                    val windows = GameWindowSampler.report(server, diagnostics, -1, before)
                    val execution = sampler.report(before, diagnostics::activity)
                    val native = GameNativePcProbe.collectDetailed(executable, diagnostics::activity)
                    // A bounded native read already in flight must finish detaching safely, but
                    // its result must not be saved after the user disables collection.
                    if (!RuntimeLaunchDiagnostics.isCollecting(context) || sources(id) == null)
                        throw java.util.concurrent.CancellationException()
                    val after = SystemClock.elapsedRealtime()
                    val text = "batch=$index\nutc_ms=${System.currentTimeMillis()}\nelapsed_start_ms=$before\n" +
                        "elapsed_end_ms=$after\n$windows\n$execution\n$native\n"
                    GameDiagnosticArchive.write(store, "sample-${index.toString().padStart(2, '0')}.txt", text)
                    completed = index
                    GameDiagnosticArchive.write(store, "status.txt", "state=COLLECTING\ncompleted_batches=$completed\n")
                    // A late pause must not replace earlier valid samples with samples=0.
                    saveSummary(summary.record(index, native))
                    native
                },
                wait = { Thread.sleep(it) },
            )
        } catch (_: java.util.concurrent.CancellationException) {
            state = "INTERRUPTED"
        } catch (_: InterruptedException) {
            state = "INTERRUPTED"
            Thread.currentThread().interrupt()
        } catch (_: Exception) { state = "CAPTURE_ERROR" }
        finally {
            if (begun && RuntimeLaunchDiagnostics.isCollecting(context) && sources(id) != null) runCatching {
                saveSummary(summary.render(state))
                // Sources from a newer launch must never enter this capture.
                sources(id)?.let { GameDiagnosticArchive.logs(store, it) { sources(id) != null } }
                RuntimeLaunchDiagnostics.snapshotForBundle(context, id)?.let {
                    GameDiagnosticArchive.write(store, "report-after.txt", it)
                }
                GameDiagnosticArchive.write(store, "status.txt", "state=$state\ncompleted_batches=$completed\n" +
                    "finished_utc_ms=${System.currentTimeMillis()}\nrequested_batches=6\npoints_per_batch=3\n")
                GameDiagnosticArchive.publish(store)
            }.onFailure { state = "SAVE_ERROR" }
            busy.set(false)
        }
        return begun && state != "SAVE_ERROR" && RuntimeLaunchDiagnostics.isCollecting(context)
    }
}

/** One capture only; keep the last usable batch, totals and the most recent outcome. */
class GameDiagnosticSummary {
    private var attempted = 0
    private var captured = 0
    private var points = 0
    private var selected = 0
    private var lastState = "NOT_ATTEMPTED"
    private var retained = emptyList<String>()

    fun record(batch: Int, native: String): String {
        attempted = batch
        val lines = native.substringBefore("\nCODE_CONTEXT_V1").lineSequence().toList()
        val header = lines.firstOrNull().orEmpty()
        val fields = Regex("^GAME_NATIVE_PC_V1 state=([A-Z_]+); samples=([0-3])(?:;|$)").find(header)
        lastState = fields?.groupValues?.get(1) ?: "INVALID_OUTPUT"
        val count = fields?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val samples = lines.filter { it.startsWith("GAME_NATIVE_PC_SAMPLE_V1 ") }
        if (count > 0 && samples.size == count) {
            captured++
            points += count
            selected = batch
            retained = listOf(header) + samples
        } else if (captured == 0) {
            selected = 0
            retained = listOf("GAME_NATIVE_PC_V1 state=$lastState; samples=0; ready=UNCONFIRMED")
        }
        return render("COLLECTING")
    }

    fun render(state: String): String {
        val overview = "GAME_DIAGNOSTIC_BUNDLE_V1 state=$state; attempted_batches=$attempted; " +
            "captured_batches=$captured; captured_samples=$points; selected_batch=$selected; last_batch_state=$lastState"
        val omitted = "GAME_NATIVE_PC_DETAILS_V1 compact=TRUNCATED; full_batches=DIAGNOSTIC_ZIP"
        val full = (listOf(overview) + retained).joinToString("\n")
        if (full.length <= 1400) return full
        // Keep complete lines and state explicitly when the ZIP contains additional sample detail.
        return buildString {
            append(overview)
            for (line in retained) {
                if (length + line.length + omitted.length + 2 > 1400) break
                append('\n').append(line)
            }
            append('\n').append(omitted)
        }
    }
}

/** Uneven spacing reduces repeated phase-aligned observations; no continuous tracing. */
object GameDiagnosticSequence {
    fun run(current: () -> Boolean, sample: (Int) -> String, wait: (Long) -> Unit): String {
        val gaps = longArrayOf(0, 419, 733, 1061, 547, 887)
        for (index in gaps.indices) {
            if (index > 0) wait(gaps[index])
            if (!current()) return "SESSION_CHANGED_OR_PAUSED"
            val result = sample(index + 1)
            if (!current()) return "SESSION_CHANGED_OR_PAUSED"
            // Do not repeatedly attach when the previous native tracer is still recovering.
            if (listOf("RECOVERY_PENDING", "DENIED", "LIBRARY_UNAVAILABLE", "IDENTITY_CHANGED", "TARGET_CHANGED", "TRANSITION")
                    .any { result.startsWith("GAME_NATIVE_PC_V1 state=$it;") }) return "PROBE_UNAVAILABLE"
        }
        return "COMPLETE"
    }
}

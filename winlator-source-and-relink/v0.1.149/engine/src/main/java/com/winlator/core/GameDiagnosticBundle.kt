/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
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
    private data class Session(val id: String, val sources: MutableMap<String, GameDiagnosticSource> = linkedMapOf(),
                              var displaySettings: (() -> String)? = null,
                              var gameObservation: ManualGameObservation? = null)
    private var session: Session? = null
    private val busy = AtomicBoolean(false)

    @Synchronized fun discard() { session = null; FinalDisplayCadence.stop(); FramePipelineTrace.stop(); GameVisualTrace.stop(); NativeGraphicsTrace.discard(); DxvkFlowTrace.discard(); FexJitCapture.stop(); NativeCodeTrace.discard(); NetworkDiagnosticCapture.discard() }

    @Synchronized fun begin(id: String) { FinalDisplayCadence.stop(); FramePipelineTrace.stop(); GameVisualTrace.stop(); NativeGraphicsTrace.discard(); DxvkFlowTrace.discard(); FexJitCapture.stop(); NativeCodeTrace.discard(); NetworkDiagnosticCapture.discard(); session = Session(id) }

    private fun codeSnapshot(context: Context, id: String, store: File, phase: String, epoch: Long?) {
        val snapshot = NativeCodeTrace.snapshot(id, phase, epoch)
        if (!RuntimeLaunchDiagnostics.isCollecting(context) || sources(id) == null) return
        for ((name, bytes) in snapshot.files) GameDiagnosticArchive.codeBytes(store, name, bytes)
        GameDiagnosticArchive.write(store, "code-protection-$phase.txt", snapshot.summary)
    }

    private fun graphicsSnapshot(context: Context, id: String, store: File, phase: String) {
        val snapshot = NativeGraphicsTrace.snapshot(id, phase)
        if (!RuntimeLaunchDiagnostics.isCollecting(context) || sources(id) == null) return
        for ((name, bytes) in snapshot.files) GameDiagnosticArchive.graphicsBytes(store, name, bytes)
        GameDiagnosticArchive.write(store, "graphics-$phase.txt", snapshot.summary)
        GameDiagnosticArchive.write(store, "graphics-history.txt", snapshot.history)
        GameDiagnosticArchive.write(store, "vulkan-fault-$phase.txt", snapshot.faults)
    }

    @Synchronized fun watch(id: String, entry: String, file: File, fromStart: Boolean = false, guest: Boolean = false,
                            beforeRead: () -> Boolean = { true }) {
        val current = session?.takeIf { it.id == id } ?: return
        if (entry in current.sources || current.sources.size >= 12) return
        runCatching { GameDiagnosticSource.watch(entry, file, if (guest) 24 * 1024 * 1024 else 1024 * 1024, fromStart, beforeRead) }
            .getOrNull()?.let { current.sources[entry] = it }
    }

    @Synchronized private fun sources(id: String): List<GameDiagnosticSource>? =
        session?.takeIf { it.id == id }?.sources?.values?.toList()

    @Synchronized fun watchDisplaySettings(id: String, read: () -> String) {
        session?.takeIf { it.id == id }?.displaySettings = read
    }

    @Synchronized fun watchGameObservation(id: String, observer: ManualGameObservation) {
        session?.takeIf { it.id == id }?.gameObservation = observer
    }

    /** Existing worker or manual capture only; reads are outside the session monitor. */
    fun displaySnapshot(context: Context, id: String, server: XServer?): String? {
        if (!RuntimeLaunchDiagnostics.isCollecting(context)) return null
        val current = synchronized(this) { session?.takeIf { it.id == id } } ?: return null
        val read = synchronized(this) { current.displaySettings }
        val profile = try { read?.invoke() ?: "GAME_DISPLAY_SETTINGS_V1 state=NOT_REGISTERED" }
            catch (_: Exception) { "GAME_DISPLAY_SETTINGS_V1 state=UNAVAILABLE" }
        val viewport = server?.viewportInfo
        val monitor = server?.screenInfo
        val text = "GAME_DISPLAY_SNAPSHOT_V1 elapsed_ms=${SystemClock.elapsedRealtime()}; " +
            "monitor=${monitor ?: "UNKNOWN"}; viewport=${viewport ?: "UNKNOWN"}; atomic=NO\n" +
            (server?.displayTrace?.report(SystemClock.elapsedRealtime(), viewport!!.width.toInt(), viewport.height.toInt())
                ?: "GAME_HOST_VIEW_V1 state=UNAVAILABLE") + "\n" + profile
        if (!RuntimeLaunchDiagnostics.isCollecting(context) || synchronized(this) { session !== current }) return null
        return text.take(8000)
    }

    /** Worker only. A pause, new launch or Activity close ends the sequence without resuming anything. */
    fun collect(context: Context, id: String, executable: String, server: XServer,
                active: () -> Boolean, saveSummary: (String) -> Unit,
                visualUi: ((GameVisualCapture.Ticket) -> Unit)? = null): Boolean {
        if (!RuntimeLaunchDiagnostics.isCollecting(context) || !busy.compareAndSet(false, true)) return false
        val store = File(context.filesDir, GameDiagnosticArchive.STORE)
        val diagnostics = server.gameDiagnostics
        val summary = GameDiagnosticSummary()
        var begun = false
        var completed = 0
        var state = "INTERRUPTED"
        var codeEpoch: Long? = null
        var jitCapture: FexJitCapture.Capture? = null
        var presentCapture: JitPresentCapture? = null
        var finalDisplay: DisplayCadenceCapture? = null
        var pipeline: FramePipelineCapture? = null
        var gameCadence: GameCadenceObservation? = null
        var visualCapture: GameVisualCapture? = null
        var scheduling: GameSchedulingCapture? = null
        var performanceStartedNs: Long? = null
        var performanceFrozen = false
        var performanceState = "NOT_STARTED"
        val pressure = GamePressureSampler(context, executable)
        val pressureHistory = StringBuilder("GAME_PRESSURE_HISTORY_V1 clock=HOST_MONOTONIC_NS; vm_psi_scope=SYSTEM; " +
            "psi_total_unit=MICROSECONDS; fault_scope=SELECTED_EXE; missing=UNKNOWN; gpu_time=UNMEASURED\n")
        val fex = FexDiagnosticCapture(executable)
        val fexHistory = StringBuilder()
        fun fexSnapshot(phase: String) {
            val text = if (diagnostics != null) fex.sample(phase, diagnostics::activity)
                else "FEX_DIAGNOSTIC_V2 phase=$phase; state=UNAVAILABLE\n"
            if (!RuntimeLaunchDiagnostics.isCollecting(context) || sources(id) == null) return
            if (fexHistory.length + text.length <= 60 * 1024) fexHistory.append(text)
            else if (!fexHistory.endsWith("FEX_DIAGNOSTIC_V2 state=OUTPUT_LIMIT\n"))
                fexHistory.append("FEX_DIAGNOSTIC_V2 state=OUTPUT_LIMIT\n")
            GameDiagnosticArchive.write(store, "fex-statistics.txt", fexHistory.toString())
        }
        fun pressureSnapshot(phase: String) {
            if (active() && RuntimeLaunchDiagnostics.isCollecting(context) && sources(id) != null && !diagnostics.activity().paused)
                visualCapture?.reserve(VisualStage.UI)?.let { ticket -> visualUi?.invoke(ticket) }
            val observation = pressure.sample(phase)
            if (!RuntimeLaunchDiagnostics.isCollecting(context) || sources(id) == null) return
            if (pressureHistory.length + observation.length <= 60 * 1024) pressureHistory.append(observation)
            GameDiagnosticArchive.write(store, "system-pressure.txt", pressureHistory.toString())
        }
        fun freezePerformance() {
            if (performanceFrozen) return
            performanceFrozen = true
            val stopBegin = System.nanoTime()
            FinalDisplayCadence.stop(id)
            FramePipelineTrace.stop(id)
            NativeGraphicsTrace.stopCadence(id)
            GameVisualTrace.stop(id)
            FexJitCapture.stop(id)
            NativeCodeTrace.stopCapture(id)
            DxvkFlowTrace.stop(id)
            scheduling?.stop()
            gameCadence?.requestStop()
            if (!begun || !RuntimeLaunchDiagnostics.isCollecting(context) || sources(id) == null) { gameCadence?.stop(); return }
            // Freeze all end snapshots before the anonymous network helper is started.
            fexSnapshot("performance_end")
            pressureSnapshot("end")
            graphicsSnapshot(context, id, store, "after")
            val nativeCadence = NativeGraphicsTrace.cadenceSnapshot(id)
            nativeCadence.files.forEach { (name, bytes) -> GameDiagnosticArchive.cadenceBytes(store, name, bytes) }
            GameDiagnosticArchive.write(store, "graphics-cadence.txt", nativeCadence.report)
            codeSnapshot(context, id, store, "after", codeEpoch)
            GameDiagnosticArchive.write(store, "dxvk-flow.txt", DxvkFlowTrace.report(id))
            GameDiagnosticArchive.write(store, "dxvk-color.txt", DxvkColorTrace.report(id))
            GameDiagnosticArchive.write(store, "fex-jit-capture.txt", jitCapture?.report().orEmpty())
            GameDiagnosticArchive.write(store, "jit-present-timeline.txt", presentCapture?.report().orEmpty())
            val cadenceReport = gameCadence?.stop() ?: "SMAPI_LOOP_V1 state=NOT_STARTED\n"
            if (!RuntimeLaunchDiagnostics.isCollecting(context) || sources(id) == null) return
            GameDiagnosticArchive.write(store, "visual-state.txt", visualCapture?.report() ?: "GAME_VISUAL_V1 state=NOT_STARTED\n")
            GameDiagnosticArchive.write(store, "game-loop-command.txt", cadenceReport)
            GameDiagnosticArchive.write(store, "final-display-cadence.txt", finalDisplay?.report()
                ?: "FINAL_DISPLAY_V1 state=UNAVAILABLE; actual_display_fps=UNKNOWN\n")
            pipeline?.reports()?.forEach { (name, text) -> GameDiagnosticArchive.write(store, name, text) }
            pipeline?.transport?.reports()?.forEach { (name, text) -> GameDiagnosticArchive.write(store, name, text) }
            finalDisplay?.reports()?.forEach { (name, text) -> GameDiagnosticArchive.write(store, name, text) }
            scheduling?.reports()?.forEach { (name, text) -> GameDiagnosticArchive.write(store, name, text) }
            GameDiagnosticArchive.write(store, "performance-window.txt", "GAME_PERFORMANCE_WINDOW_V2 " +
                "start_ns=${performanceStartedNs ?: "UNKNOWN"}; stop_begin_ns=$stopBegin; " +
                "snapshots_end_ns=${System.nanoTime()}; clock=HOST_MONOTONIC_NS; native_limit_ms=10000; " +
                "network_control=AFTER_PERFORMANCE_SNAPSHOTS; snapshots_atomic=NO; pc_batches=$completed; " +
                "performance_state=$performanceState; pc_phase=AFTER_PERFORMANCE_SNAPSHOTS; clean_no_debug=NO\n")
        }
        try {
            val watched = sources(id) ?: return false
            if (diagnostics == null || !active()) return false
            val activityEpoch = diagnostics.activity()
            fun current() = active() && RuntimeLaunchDiagnostics.isCollecting(context) && sources(id) != null &&
                !activityEpoch.paused && diagnostics.activity() == activityEpoch
            val capture = UUID.randomUUID().toString()
            val report = RuntimeLaunchDiagnostics.snapshotForBundle(context, id) ?: return false
            GameDiagnosticArchive.begin(store, id, capture, System.currentTimeMillis(), report)
            begun = true
            displaySnapshot(context, id, server)?.let { GameDiagnosticArchive.write(store, "display-state-before.txt", it) }
            saveSummary(summary.render("COLLECTING"))
            graphicsSnapshot(context, id, store, "before")
            fexSnapshot("before")
            codeSnapshot(context, id, store, "before", null)
            GameDiagnosticArchive.logs(store, watched) { sources(id) != null }
            val sampler = GameExecutionSampler(executable,
                ProcGameExecutionAccess(threadLimit = GameExecutionSampler.DIAGNOSTIC_MAX_THREADS),
                runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(0))
            codeEpoch = NativeCodeTrace.startCapture(id)
            NativeGraphicsTrace.armFaultWindow(id)
            performanceStartedNs = System.nanoTime()
            NativeGraphicsTrace.startCadence(id, requireNotNull(performanceStartedNs))
            pipeline = FramePipelineTrace.start(id, server)
            finalDisplay = FinalDisplayCadence.start(id, server)
            visualCapture = GameVisualTrace.start(id, server)
            gameCadence = GameCadenceObservation(synchronized(this) { session?.takeIf { it.id == id }?.gameObservation }, capture, current = ::current,
                onStop = { FinalDisplayCadence.stop(id); FramePipelineTrace.stop(id); GameVisualTrace.stop(id); NativeGraphicsTrace.stopCadence(id) })
            DxvkFlowTrace.start(id)
            jitCapture = FexJitCapture.start(id, executable, ::current)
            presentCapture = FexJitCapture.timeline(jitCapture, server)
            scheduling = GameSchedulingCapture(executable, requireNotNull(performanceStartedNs), ProcGameSchedulingAccess(),
                ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(0))
            val initialCountersBegin = System.nanoTime()
            pressureSnapshot("begin")
            scheduling?.operation("INITIAL_COUNTERS", initialCountersBegin, System.nanoTime())
            performanceState = "COLLECTING"
            performanceState = GamePerformanceSequence.run(requireNotNull(performanceStartedNs), ::current,
                sample = { scheduling?.sample(::current) },
                slow = { index ->
                    val begin = System.nanoTime()
                    fexSnapshot("tail_$index"); pressureSnapshot("tail_$index")
                    scheduling?.operation("COUNTERS", begin, System.nanoTime())
                })
            // Never suspend a guest thread inside the cadence interval. These later PC samples
            // describe later execution, not the location of an earlier captured long frame.
            freezePerformance()
            state = if (performanceState != "COMPLETE") performanceState else GameDiagnosticSequence.run(
                current = ::current,
                sample = { index ->
                    val before = SystemClock.elapsedRealtime()
                    // Do not drain the performance monitor's counters; present ownership is unknown here.
                    val windows = GameWindowSampler.diagnosticReport(server, diagnostics, -1, before)
                    val execution = sampler.report(before, diagnostics::activity)
                    val rank = GameDiagnosticSequence.hotThreadRank(index)
                    val probeBegin = System.nanoTime()
                    val native = run {
                        if (rank == 0) GameNativePcProbe.collectRotatingLeaderDetailed(executable, diagnostics::activity,
                            GameDiagnosticSequence.leaderOrdinal(index))
                        else GameNativePcProbe.collectHotThreadDetailed(executable, diagnostics::activity,
                            sampler.hotThread(rank, SystemClock.elapsedRealtime(), diagnostics.activity()))
                    }
                    val probeEnd = System.nanoTime()
                    // A bounded native read already in flight must finish detaching safely, but
                    // its result must not be saved after the user disables collection.
                    if (!current())
                        throw java.util.concurrent.CancellationException()
                    val after = SystemClock.elapsedRealtime()
                    val host = server.displayTrace.report(after, server.viewportInfo.width.toInt(), server.viewportInfo.height.toInt())
                    val text = "batch=$index\nutc_ms=${System.currentTimeMillis()}\nelapsed_start_ms=$before\n" +
                        "phase=AFTER_PERFORMANCE_SNAPSHOTS\nprobe_begin_ns=$probeBegin\nprobe_end_ns=$probeEnd\n" +
                        "elapsed_end_ms=$after\nrequested_hot_thread_rank=$rank\n$windows\n$execution\n$native\n$host\n"
                    GameDiagnosticArchive.write(store, "sample-${index.toString().padStart(2, '0')}.txt", text)
                    completed = index
                    GameDiagnosticArchive.write(store, "status.txt", "state=COLLECTING\nphase=NATIVE_CONTEXT\ncompleted_batches=$completed\n")
                    // A late pause must not replace earlier valid samples with samples=0.
                    saveSummary(summary.record(index, native))
                    native
                },
                wait = { Thread.sleep(it) },
            )
            // Run control traffic after PC/CPU samples so it cannot inflate the sampled game work.
            freezePerformance()
            NetworkDiagnosticCapture.collect(context, id,
                ::current) { network ->
                if (RuntimeLaunchDiagnostics.isCollecting(context) && sources(id) != null)
                    GameDiagnosticArchive.write(store, "network-control.txt", network)
            }.let { network ->
                if (RuntimeLaunchDiagnostics.isCollecting(context) && sources(id) != null)
                    GameDiagnosticArchive.write(store, "network-control.txt", network)
            }
        } catch (_: java.util.concurrent.CancellationException) {
            state = "INTERRUPTED"
        } catch (_: InterruptedException) {
            state = "INTERRUPTED"
            Thread.currentThread().interrupt()
        } catch (_: Exception) { state = "CAPTURE_ERROR" }
        finally {
            if (performanceState == "COLLECTING") performanceState = state
            FinalDisplayCadence.stop(id)
            FramePipelineTrace.stop(id)
            GameVisualTrace.stop(id)
            if (!performanceFrozen) runCatching { freezePerformance() }.onFailure { state = "SAVE_ERROR" }
            FexJitCapture.stop(id)
            NativeCodeTrace.stopCapture(id)
            DxvkFlowTrace.stop(id)
            if (begun && RuntimeLaunchDiagnostics.isCollecting(context) && sources(id) != null) runCatching {
                freezePerformance()
                saveSummary(summary.render(state))
                displaySnapshot(context, id, server)?.let { GameDiagnosticArchive.write(store, "display-state-after.txt", it) }
                // Sources from a newer launch must never enter this capture.
                sources(id)?.let { GameDiagnosticArchive.logs(store, it) { sources(id) != null } }
                RuntimeLaunchDiagnostics.snapshotForBundle(context, id)?.let {
                    GameDiagnosticArchive.write(store, "report-after.txt", it)
                }
                GameDiagnosticArchive.write(store, "status.txt", "state=$state\ncompleted_batches=$completed\n" +
                    "finished_utc_ms=${System.currentTimeMillis()}\nrequested_batches=6\npoints_per_batch=3\n" +
                    "performance_state=$performanceState\npc_phase=AFTER_PERFORMANCE_SNAPSHOTS\n")
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
    private var signalBatches = 0
    private var missingWorkerBatches = 0
    private var retained = emptyList<String>()

    fun record(batch: Int, native: String): String {
        attempted = batch
        val lines = native.substringBefore("\nCODE_CONTEXT_V1").lineSequence().toList()
        val header = lines.firstOrNull().orEmpty()
        val fields = Regex("^GAME_NATIVE_PC_V1 state=([A-Z_]+); samples=([0-3])(?:;|$)").find(header)
        lastState = fields?.groupValues?.get(1) ?: "INVALID_OUTPUT"
        if (lastState == "SIGNAL_INTERRUPTED") signalBatches++
        if (lastState == "NO_HOT_THREAD") missingWorkerBatches++
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
            "captured_batches=$captured; captured_samples=$points; selected_batch=$selected; last_batch_state=$lastState" +
            "; signal_batches=$signalBatches; missing_worker_batches=$missingWorkerBatches"
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
    /** Keep the existing 6 x 3 point budget; a missing worker is skipped, not replaced by the leader. */
    fun hotThreadRank(batch: Int): Int {
        require(batch in 1..6)
        return (batch - 1) % 3
    }

    fun leaderOrdinal(batch: Int): Int {
        require(batch == 1 || batch == 4)
        return (batch - 1) / 3
    }

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

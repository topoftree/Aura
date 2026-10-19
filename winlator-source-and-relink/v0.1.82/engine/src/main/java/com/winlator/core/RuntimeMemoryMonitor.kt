/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cheap, low-frequency samples off the UI thread; no process dumps or continuous PSS scans. */
class RuntimeMemoryMonitor private constructor(
    private val context: Context, private val launchId: String, private val protect: Boolean, private val onPressure: Runnable,
    private val server: () -> com.winlator.xserver.XServer?,
    executable: String?,
) : AutoCloseable {
    private class Collection(context: Context, executable: String?) {
        val history = RuntimeMemoryHistory()
        val performance = RuntimePerformanceSampler(context)
        val execution = executable?.takeIf { it.isNotBlank() }?.let {
            GameExecutionSampler(it, ProcGameExecutionAccess(),
                runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(0))
        }
    }
    @Volatile private var collection = if (RuntimeLaunchDiagnostics.isCollecting(context)) Collection(context, executable) else null
    private var modeObserver: AutoCloseable? = null
    private val pressure = RuntimeMemoryPressure()
    private val started = SystemClock.elapsedRealtime()
    private var lastRecorded: Long? = null
    private val closed = AtomicBoolean(false)
    private val extraSamplePending = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "aura-runtime-memory").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    init {
        modeObserver = RuntimeDiagnostics.observe { enabled ->
            if (!enabled) {
                collection = null
                if (!protect) close()
            }
        }
        if (closed.get()) modeObserver?.close()
        else executor.scheduleWithFixedDelay({ sample(false) }, 0, 2, TimeUnit.SECONDS)
    }

    fun sampleSoon() {
        if (!extraSamplePending.compareAndSet(false, true)) return
        try {
            executor.execute { try { sample(true) } finally { extraSamplePending.set(false) } }
        } catch (_: RejectedExecutionException) { extraSamplePending.set(false) }
    }

    private fun sample(force: Boolean) {
        try {
            if (closed.get()) return
            val memory = runCatching {
                ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            }.getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - started
            val stop = protect && pressure.observe(elapsed, memory?.availMem, memory?.threshold, memory?.lowMemory)
            val capture = collection?.takeIf { RuntimeLaunchDiagnostics.isCollecting(context) }
            if (capture == null) {
                if (stop && !closed.get() && RuntimeLaunchDiagnostics.memoryPressure(context, launchId)) onPressure.run()
                return
            }
            if (!force && !stop && lastRecorded?.let { elapsed - it < 10_000 } == true) return
            val rss = runCatching { RuntimeMemoryHistory.rssBytes(File("/proc/self/status").readText()) }.getOrNull()
            val runtime = Runtime.getRuntime()
            capture.history.add(RuntimeMemorySnapshot(elapsed,
                memory?.totalMem, memory?.availMem, memory?.threshold, memory?.lowMemory, rss,
                runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(),
                runCatching { GuestProcessSession.memory() }.getOrNull()))
            lastRecorded = elapsed
            val performanceReport = runCatching {
                val current = server()
                capture.performance.report(elapsed, current?.performance) { windowId, contentIds ->
                    current?.gameDiagnostics?.let {
                        val now = SystemClock.elapsedRealtime()
                        val windows = GameWindowSampler.sample(current, it, windowId, contentIds, now)
                        // Window-manager lock is released before any /proc reads.
                        val execution = capture.execution?.report(now, it::activity)
                        windows.copy(report = windows.report + if (execution != null) "\n$execution" else "")
                    } ?: GameWindowSampler.Sample("")
                }
            }.getOrDefault("")
            RuntimeLaunchDiagnostics.memorySnapshot(context, launchId, capture.history.report(), performanceReport)
            if (stop && !closed.get() && RuntimeLaunchDiagnostics.memoryPressure(context, launchId)) onPressure.run()
        } catch (_: Exception) {
            // Diagnostics must not fail a game launch or stop future samples after a transient denial.
        }
    }

    override fun close() {
        closed.set(true)
        collection = null
        modeObserver?.close(); modeObserver = null
        executor.shutdownNow()
    }

    companion object {
        @JvmStatic
        fun start(activity: Activity, onPressure: Runnable): RuntimeMemoryMonitor? {
            val launchId = activity.intent.getStringExtra("aura_launch_id") ?: return null
            if (!RuntimeLaunchDiagnostics.isActive(activity)) return null
            val protect = GameRuntimeProfile.requiresMemoryProtection(activity.intent.getIntExtra("aura_game_id", 0),
                activity.intent.getStringExtra("exec_path"))
            if (!protect && !RuntimeLaunchDiagnostics.isCollecting(activity)) return null
            val executable = activity.intent.getStringExtra("exec_path").takeIf { activity.intent.getIntExtra("aura_game_id", 0) > 0 }
            return runCatching { RuntimeMemoryMonitor(activity.applicationContext, launchId, protect, onPressure, {
                (activity as? com.winlator.XServerDisplayActivity)?.xServer
            }, executable) }.getOrNull()
        }
    }
}

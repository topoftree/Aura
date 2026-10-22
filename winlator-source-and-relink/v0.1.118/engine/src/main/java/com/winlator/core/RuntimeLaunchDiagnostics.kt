/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.ActivityManager
import android.app.Activity
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants

/** Last launch; fixed component/error summaries only, never raw guest output or credentials. */
object RuntimeLaunchDiagnostics {
    private var applicationContext: Context? = null
    private var modeObserver: AutoCloseable? = null
    private var guestOutput: Pair<String, GuestOutputSummary>? = null
    private var guestLogs: Pair<String, GuestGameLogs>? = null
    private var steamLogs: Pair<String, SteamClientLogs>? = null
    private var ubisoftLogs: Pair<String, UbisoftClientLogs>? = null
    private fun prefs(context: Context) = context.getSharedPreferences(LegacyDataNames.LAUNCH_PREFERENCES, Context.MODE_PRIVATE)
    private fun active(context: Context): Boolean = prefs(context).let {
        val launchId = (context as? Activity)?.intent?.getStringExtra("aura_launch_id")
        it.getBoolean("active", false) && it.getInt("pid", -1) == Process.myPid() &&
            (context !is Activity || launchId != null && launchId == it.getString("launchId", null))
    }

    @JvmStatic
    fun install(application: Application) {
        applicationContext = application.applicationContext
        RuntimeDiagnostics.install(application)
        modeObserver?.close()
        modeObserver = RuntimeDiagnostics.observe { enabled ->
            if (!enabled) synchronized(this) {
                guestOutput = null
                guestLogs = null
                steamLogs = null
                ubisoftLogs = null
                GameDiagnosticBundle.discard()
                if (prefs(application).contains("launchId"))
                    prefs(application).edit().putBoolean("diagnostics", false).apply()
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun currentContext(context: Context): String? {
        if (!isCollecting(context)) return null
        saveGuestOutput(context)
        collectClientLogs(context)
        return storedContext(context)
    }

    @JvmStatic
    @Synchronized
    fun contextForExit(context: Context, pid: Int, timestamp: Long): String? = prefs(context).let {
        if (!RuntimeDiagnostics.enabled(context) || !it.getBoolean("diagnostics", false)) return null
        if (RuntimeExitDiagnostics.matchesExit(it.getInt("pid", -1), it.getLong("started", 0),
                it.getBoolean("active", false), it.getLong("finished", 0), pid, timestamp)) {
            storedContext(context)
        } else null
    }

    private fun storedContext(context: Context): String = prefs(context).let {
        buildString {
            appendLine("${it.getString("header", "Aura 游戏运行")}\n游戏阶段：${it.getString("stage", "UNKNOWN")}")
            val failure = it.getString("report", null)
            val nativePc = it.getString("nativePc", "").orEmpty()
            if (nativePc.isNotEmpty() && !failure.orEmpty().contains(nativePc)) appendLine(nativePc.take(1_400))
            if (failure != null) appendLine("此前游戏报告：\n${failure.take(6_000)}")
            else {
                appendLine(it.getString("details", "").orEmpty().take(3_000))
                appendLine("客体输出摘要：\n${it.getString("guestOutput", "尚未启动输出采集").orEmpty().take(1_200)}")
            }
            append(it.getString("memory", "").orEmpty().take(1_800))
            appendLine()
            appendLine(clientSummary(context))
            append(it.getString("performance", "").orEmpty().take(3_500))
        }.take(8_000)
    }

    @JvmStatic
    @Synchronized
    fun begin(context: Context, gameId: Int, containerId: Int): String {
        val diagnostics = RuntimeDiagnostics.enabled(context)
        val header = if (diagnostics) buildString {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            appendLine("Aura 游戏启动诊断")
            appendLine("版本：$version")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("页面大小：${runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)}")
            append("游戏：$gameId / 容器：$containerId")
        } else null
        val launchId = java.util.UUID.randomUUID().toString()
        guestOutput = null
        guestLogs = null
        steamLogs = null
        ubisoftLogs = null
        prefs(context).edit().clear().putString("launchId", launchId).putBoolean("active", true).putInt("pid", Process.myPid())
            .putBoolean("diagnostics", diagnostics).apply {
                if (diagnostics) putLong("started", System.currentTimeMillis()).putString("header", header)
                    .putString("stage", "LAUNCH_ACTIVITY")
            }.commit()
        AppCrashDiagnostics.stage("Winlator · LAUNCH_ACTIVITY")
        if (diagnostics) GameDiagnosticBundle.begin(launchId) else GameDiagnosticBundle.discard()
        return launchId
    }

    @JvmStatic
    @Synchronized
    fun stage(context: Context, stage: String) {
        if (isCollecting(context)) {
            prefs(context).edit().putString("stage", stage).commit()
            AppCrashDiagnostics.stage("Winlator · $stage")
        }
    }

    @JvmStatic
    @Synchronized
    fun failed(context: Context, error: Throwable) {
        if (isCollecting(context)) record(context, "JAVA_OR_LINKAGE_FAILURE\n" +
            (if (error is RuntimeComponentException) "COMPONENT_CHECK ${error.component}: ${error.reason}\n" else "") +
            runtimeFailureFrames(error))
    }

    @JvmStatic fun isActive(context: Context): Boolean = active(context)

    /** Session ownership remains available even when every diagnostic collector is disabled. */
    @JvmStatic fun isCollecting(context: Context): Boolean =
        RuntimeDiagnostics.enabled(context) && prefs(context).getBoolean("diagnostics", false) && active(context)

    /** Capture old log metadata before Wine starts so earlier crashes cannot become this launch's failure. */
    @JvmStatic
    @Synchronized
    fun watchGuestLogs(context: Context, executable: String?) {
        if (!isCollecting(context) || executable.isNullOrBlank()) return
        val file = java.io.File(executable)
        if (!file.isAbsolute || !file.isFile) return
        val launchId = prefs(context).getString("launchId", null) ?: return
        guestLogs = try { launchId to GuestGameLogs(file) }
            catch (_: java.io.IOException) { null }
            catch (_: SecurityException) { null }
    }

    private fun collectGuestLogs(context: Context) {
        if (!isCollecting(context)) return
        val collector = guestLogs?.takeIf { it.first == prefs(context).getString("launchId", null) }?.second ?: return
        val report = try { collector.collect() }
            catch (_: java.io.IOException) { null }
            catch (_: SecurityException) { null }
        if (report == null) return
        detail(context, report.diagnostic)
        if (report.crashed) record(context, "UNITY_GUEST_CRASH\n${report.diagnostic}")
    }

    /** Capture the Steam log offsets before Wine or the installer starts. */
    @JvmStatic
    @Synchronized
    fun watchSteamLogs(context: Context, executable: String?) {
        if (!isCollecting(context) || executable.isNullOrBlank()) return
        val file = java.io.File(executable)
        if (!file.isAbsolute || !file.name.equals("steam.exe", true)) return
        val launchId = prefs(context).getString("launchId", null) ?: return
        steamLogs = try { launchId to SteamClientLogs(file) }
            catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
        for (name in listOf("bootstrap_log.txt", "cef_log.txt", "webhelper.txt", "webhelper_gpu.txt"))
            GameDiagnosticBundle.watch(launchId, "steam-$name", java.io.File(file.parentFile, "logs/$name"))
    }

    private fun collectClientLogs(context: Context) {
        if (!isCollecting(context)) return
        val launchId = prefs(context).getString("launchId", null)
        steamLogs?.takeIf { it.first == launchId }?.second?.let {
            prefs(context).edit().putString("steamLogs", it.collect().take(1_000)).commit()
        }
        ubisoftLogs?.takeIf { it.first == launchId }?.second?.let {
            prefs(context).edit().putString("ubisoftLogs", it.collect().take(600)).commit()
        }
    }

    /** Snapshot the installed client's logs before the game can spawn it, including Debug mode. */
    @JvmStatic
    @Synchronized
    fun watchUbisoftLogs(context: Context, executable: String?) {
        if (!isCollecting(context) || executable.isNullOrBlank()) return
        val file = java.io.File(executable)
        if (!file.isAbsolute || !file.isFile) return
        val launchId = prefs(context).getString("launchId", null) ?: return
        ubisoftLogs = try { launchId to UbisoftClientLogs(file) }
            catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
            catch (_: IllegalArgumentException) { null }
        for (name in listOf("launcher_log.txt", "game_starter_log.txt"))
            GameDiagnosticBundle.watch(launchId, "ubisoft-$name", java.io.File(file.parentFile, "logs/$name"))
        collectClientLogs(context)
    }

    private fun clientSummary(context: Context): String = prefs(context).let {
        listOf(it.getString("ubisoftLogs", "").orEmpty(),
            it.getString("steamStartup", "").orEmpty(), it.getString("steamLogs", "").orEmpty())
            .filter(String::isNotEmpty).joinToString("\n").take(1_400)
    }

    /** Fixed counters only; replacing the previous sample avoids filling the configuration log. */
    @JvmStatic
    @Synchronized
    fun steamStartupSnapshot(context: Context, summary: String) {
        if (isCollecting(context)) prefs(context).edit().putString("steamStartup", summary.take(400)).commit()
    }

    /** Each launch owns its collector; late output from an old guest cannot enter a new report. */
    @JvmStatic
    @Synchronized
    fun startGuestOutput(context: Context): GuestOutputSummary {
        val output = GuestOutputSummary()
        if (isCollecting(context)) {
            prefs(context).getString("launchId", null)?.let { guestOutput = it to output }
            saveGuestOutput(context)
        }
        return output
    }

    @JvmStatic
    fun acceptGuestOutput(context: Context, output: GuestOutputSummary, line: String) {
        if (!isCollecting(context) || !output.accept(line)) return
        // Do not hold the collector lock while taking the diagnostics lock. Persist each new
        // classified signal/module once (at most 24 + 4), so an early native abort cannot lose it.
        synchronized(this) {
            if (guestOutput?.second === output) saveGuestOutput(context)
        }
    }

    @JvmStatic
    @Synchronized
    fun saveGuestOutput(context: Context) {
        if (!isCollecting(context)) return
        val preferences = prefs(context)
        guestOutput?.takeIf { it.first == preferences.getString("launchId", null) }?.let {
            preferences.edit().putString("guestOutput", it.second.report().take(2_000)).commit()
        }
    }

    /** Independent of the crash archive, including running, cleanly exited and cancelled games. */
    @JvmStatic
    @Synchronized
    fun latestLaunch(context: Context): RuntimeLaunchSnapshot? {
        collectGuestLogs(context)
        collectClientLogs(context)
        saveGuestOutput(context)
        val preferences = prefs(context)
        val header = preferences.getString("header", null) ?: return null
        return RuntimeLaunchSnapshot(
            preferences.getLong("started", 0), preferences.getLong("finished", 0), active(context),
            header, preferences.getString("stage", "UNKNOWN").orEmpty(),
            preferences.getString("details", "").orEmpty(), preferences.getString("memory", "").orEmpty(),
            preferences.getString("guestOutput", "").orEmpty(), preferences.getString("report", null),
            preferences.getString("performance", "").orEmpty(),
            clientSummary(context),
            preferences.getString("nativePc", "").orEmpty(),
        )
    }

    /** Callers pass only locally constructed component IDs, probe statuses and classified signals. */
    @JvmStatic
    @Synchronized
    fun detail(context: Context, value: String) {
        if (isCollecting(context)) {
            val previous = prefs(context).getString("details", "").orEmpty()
            prefs(context).edit().putString("details", RuntimeExitDiagnostics.appendDetails(previous, value)).commit()
        }
    }

    /** The latest explicit capture survives repetitive pause/cleanup events. No raw proc text. */
    @JvmStatic
    @Synchronized
    fun nativePcSnapshot(context: Activity, summary: String): Boolean {
        if (!isCollecting(context)) return false
        return prefs(context).edit().putString("nativePc", summary.take(1_400)).commit()
    }

    @Synchronized
    fun watchBundleLog(context: Context, name: String, file: java.io.File, guest: Boolean = false,
                       beforeRead: () -> Boolean = { true }) {
        if (!isCollecting(context)) return
        prefs(context).getString("launchId", null)?.let {
            GameDiagnosticBundle.watch(it, name, file, fromStart = true, guest = guest, beforeRead = beforeRead)
        }
    }

    /** Same-session fixed fields in separate sections, unaffected by the share/report UI's trimming. */
    @Synchronized
    fun snapshotForBundle(context: Context, id: String): String? {
        if (!isCollecting(context)) return null
        if (prefs(context).getString("launchId", null) != id) return null
        val snapshot = latestLaunch(context.applicationContext) ?: return null
        val preferences = prefs(context)
        return buildString {
            appendLine(snapshot.report)
            for (key in listOf("details", "memory", "performance", "guestOutput", "steamLogs", "ubisoftLogs", "unityLogs", "report")) {
                appendLine("\n[$key]"); appendLine(preferences.getString(key, "").orEmpty())
            }
        }.take(64 * 1024)
    }

    @JvmStatic fun processFailure(error: Throwable) { applicationContext?.let { failed(it, error) } }

    /** A late sample from a closing Activity must never overwrite the next game's telemetry. */
    @JvmStatic
    @Synchronized
    fun memorySnapshot(context: Context, launchId: String, summary: String, performance: String = "") {
        if (isCollecting(context) && prefs(context).getString("launchId", null) == launchId) {
            prefs(context).edit().putString("memory", summary.take(1800))
                .putString("performance", performance.take(3500)).commit()
            // The memory sampler runs off the UI thread about every ten seconds. Keep the
            // latest bounded output available after a native crash, not only after Wine exits.
            saveGuestOutput(context)
            collectClientLogs(context)
        }
    }

    /** The app's proactive stop must remain distinct from a system LOW_MEMORY kill. */
    @JvmStatic
    @Synchronized
    fun memoryPressure(context: Context, launchId: String): Boolean {
        if (!active(context) || prefs(context).getString("launchId", null) != launchId) return false
        record(context, "MEMORY_PRESSURE_STOP\n系统可用内存持续偏低，Aura 主动结束本次游戏以减轻内存压力。")
        return true
    }

    @JvmStatic
    @Synchronized
    fun guestCleanup(context: Context, result: GuestCleanupResult) {
        if (!isCollecting(context)) return
        val value = result.report()
        detail(context, value)
        prefs(context).getString("report", null)?.let {
            prefs(context).edit().putString("report", RuntimeExitDiagnostics.append(it, value)).commit()
        }
    }

    @JvmStatic
    @Synchronized
    fun exited(context: Context, status: Int, displayedWindow: Boolean) {
        if (!isCollecting(context)) return
        saveGuestOutput(context)
        // Keep the guest's exit visible if native cleanup later crashes, including status 0.
        detail(context, "GUEST_EXIT status=$status; displayedWindow=$displayedWindow")
        if (status != 0 || !displayedWindow) record(context, "GUEST_EXIT status=$status; displayedWindow=$displayedWindow")
        // Keep monitoring through Activity teardown: a guest failure can be followed by a host
        // native error while display/audio/IPC services are stopped.
    }

    @JvmStatic
    @Synchronized
    fun finished(context: Context) {
        if (!active(context)) return
        if (!isCollecting(context)) {
            prefs(context).edit().putBoolean("active", false).commit()
            guestOutput = null; guestLogs = null; steamLogs = null; ubisoftLogs = null
            GameDiagnosticBundle.discard()
            return
        }
        collectGuestLogs(context)
        collectClientLogs(context)
        saveGuestOutput(context)
        val preferences = prefs(context)
        val report = preferences.getString("report", null)?.let {
            RuntimeExitDiagnostics.append(it, "SESSION_CLEANUP_COMPLETE")
        }
        preferences.edit().putBoolean("active", false).putLong("finished", System.currentTimeMillis())
            .putString("stage", "SESSION_CLOSED").putString("report", report).commit()
        guestOutput = null
        guestLogs = null
        steamLogs = null
        ubisoftLogs = null
        AppCrashDiagnostics.stage("Winlator · SESSION_CLOSED")
        if (report != null) AppCrashDiagnostics.recordGame(report)
    }

    /** Called on IO when Aura returns to the foreground, including after Android killed it. */
    @JvmStatic
    @Synchronized
    fun pendingReport(context: Context): String? {
        if (!RuntimeDiagnostics.enabled(context) || !prefs(context).getBoolean("diagnostics", false)) return null
        val preferences = prefs(context)
        if (preferences.getInt("pid", -1) > 0 && preferences.getInt("pid", -1) != Process.myPid()) {
            val exit = runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, preferences.getInt("pid", -1), 4)
                    .firstOrNull { RuntimeExitDiagnostics.matchesExit(preferences.getInt("pid", -1),
                        preferences.getLong("started", 0), preferences.getBoolean("active", false),
                        preferences.getLong("finished", 0), it.pid, it.timestamp) }
            }.getOrNull()
            val reason = when (exit?.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNAL"
                ApplicationExitInfo.REASON_EXIT_SELF -> "SELF_EXIT"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_STOPPED"
                null -> "PROCESS_ENDED_WITHOUT_EXIT_RECORD"
                else -> "PROCESS_EXIT_REASON_${exit.reason}"
            }
            val exitId = exit?.let { "${it.pid}:${it.timestamp}:${it.reason}" } ?: "unknown"
            if ((preferences.getBoolean("active", false) || exit != null && preferences.contains("report")) &&
                preferences.getString("hostExit", null) != exitId) {
                val memory = exit?.let { "\n" + RuntimeMemoryHistory.exitSample(it.pss, it.rss, it.importance) }.orEmpty()
                val native = exit?.let { NativeExitTrace.summary(it) }.orEmpty()
                record(context, "HOST_PROCESS_EXIT $reason; status=${exit?.status ?: "unknown"}$memory" +
                    if (native.isEmpty()) "" else "\n$native", archive = false)
                preferences.edit().putBoolean("active", false).putString("hostExit", exitId).commit()
            }
        }
        return preferences.getString("report", null).takeUnless { preferences.getBoolean("acknowledged", false) }
    }

    @JvmStatic fun acknowledge(context: Context) { prefs(context).edit().putBoolean("acknowledged", true).apply() }

    private fun record(context: Context, reason: String, archive: Boolean = true) {
        if (!RuntimeDiagnostics.enabled(context) || !prefs(context).getBoolean("diagnostics", false)) return
        saveGuestOutput(context)
        val preferences = prefs(context)
        val first = preferences.getString("report", null)
        val text = RuntimeExitDiagnostics.append(first,
            if (first == null) RuntimeExitDiagnostics.failureReport(
                preferences.getString("header", "Aura 游戏启动诊断").orEmpty(),
                preferences.getString("stage", "UNKNOWN").orEmpty(), reason,
                preferences.getString("details", "").orEmpty(),
                preferences.getString("guestOutput", "尚未启动输出采集").orEmpty(),
                preferences.getString("memory", "").orEmpty(), preferences.getString("performance", "").orEmpty(),
                clientSummary(context), preferences.getString("nativePc", "").orEmpty())
            else "阶段：${preferences.getString("stage", "UNKNOWN")}\n$reason")
        preferences.edit().putString("report", text)
            .putBoolean("acknowledged", false).commit()
        if (archive) AppCrashDiagnostics.recordGame(text)
    }
}

/** Exception messages can contain credentials or arguments; diagnostic frames deliberately omit them. */
fun runtimeFailureFrames(error: Throwable): String = buildString {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth++ < 4 && seen.add(cause)) {
        appendLine(cause.javaClass.name)
        cause.stackTrace.take(16).forEach { appendLine("  at $it") }
        cause = cause.cause
    }
}.take(6_000)

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.UUID

/** Captures the host app as well as embedded games. All data stays in app-private storage. */
object AppCrashDiagnostics {
    @Volatile private var archive: CrashArchive? = null
    private var previousSession: CrashSession? = null
    @Volatile private var session: CrashSession? = null
    private var installed = false
    private var modeObserver: AutoCloseable? = null

    @JvmStatic
    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        RuntimeDiagnostics.install(application)
        modeObserver = RuntimeDiagnostics.observe { enabled -> configure(application, enabled) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Logging failures must not prevent the original Android uncaught-exception handler.
            if (RuntimeDiagnostics.enabled()) {
                val game = runCatching { RuntimeLaunchDiagnostics.currentContext(application) }.getOrNull()
                runCatching { RuntimeLaunchDiagnostics.failed(application, error) }
                runCatching {
                    record(CrashKind.JAVA_CRASH, "JAVA", buildString {
                        appendLine(contextDetails())
                        appendLine("线程：${if (thread == android.os.Looper.getMainLooper().thread) "主线程" else "后台线程"}")
                        appendLine(runtimeFailureFrames(error))
                        if (game != null) append(game)
                    })
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    private fun configure(application: Application, enabled: Boolean) {
        if (!enabled) {
            runCatching { archive?.endSession() }
            archive = null; session = null; previousSession = null
            return
        }
        if (session != null) return
        val current = CrashSession(UUID.randomUUID().toString(), Process.myPid(), System.currentTimeMillis(), header(application), "App 启动")
        session = current
        archive = runCatching { CrashArchive(File(application.filesDir, "diagnostics/last-crash.properties")) }.getOrNull()
        previousSession = archive?.session()
        runCatching { archive?.begin(current) }
    }

    private fun saved(context: Context): CrashArchive? = runCatching {
        val file = File(context.filesDir, "diagnostics/last-crash.properties")
        if (file.isFile) CrashArchive(file) else null
    }.getOrNull()

    @JvmStatic fun stage(stage: String) { runCatching { archive?.stage(stage) } }

    @JvmStatic fun recordGame(details: String) {
        runCatching { record(CrashKind.GAME_FAILURE, "GAME", details) }
    }

    /** Persist summaries only; native traces are filtered to signal category and module/offset frames. */
    @JvmStatic
    @Synchronized
    fun refresh(context: Context): CrashRecord? {
        if (!RuntimeDiagnostics.enabled(context)) return saved(context)?.last()
        val store = archive ?: return null
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
        }.getOrDefault(emptyList())
        val records = exits.filter { it.processName == context.packageName }.mapNotNull { exit ->
            val old = previousSession?.takeIf { it.pid == exit.pid && exit.timestamp >= it.startedAt && exit.timestamp <= (session?.startedAt ?: Long.MAX_VALUE) }
                ?: return@mapNotNull null // Never import exits from an uninstrumented run.
            val gameContext = RuntimeLaunchDiagnostics.contextForExit(context, exit.pid, exit.timestamp)
            val kind = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH -> CrashKind.JAVA_CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashKind.NATIVE_CRASH
                ApplicationExitInfo.REASON_ANR -> CrashKind.ANR
                ApplicationExitInfo.REASON_LOW_MEMORY -> CrashKind.LOW_MEMORY
                ApplicationExitInfo.REASON_SIGNALED -> CrashKind.SIGNAL
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> CrashKind.RESOURCE_LIMIT
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> CrashKind.INITIALIZATION
                // Native renderer code may call exit() instead of raising a signal. Only treat
                // that as diagnostic while a game is active or immediately after its cleanup.
                ApplicationExitInfo.REASON_EXIT_SELF -> if (gameContext != null) CrashKind.SELF_EXIT else return@mapNotNull null
                else -> return@mapNotNull null // Normal exits/force-stop are not crashes.
            }
            CrashRecord("system:${exit.pid}:${exit.timestamp}:${exit.reason}", exit.timestamp, kind,
                buildString {
                    appendLine(old.header)
                    appendLine("最后阶段：${old.stage}")
                    appendLine("进程 ID：${exit.pid}")
                    appendLine("系统原因：${kind.name} (${exit.reason}) / 状态：${exit.status}")
                    append("系统采样内存：PSS ${exit.pss} KB / RSS ${exit.rss} KB（0 表示未提供）")
                    NativeExitTrace.summary(exit).takeIf { it.isNotEmpty() }?.let { append("\n$it") }
                    gameContext?.let { append("\n$it") }
                }, exit.pid, old.id, "SYSTEM")
        }
        store.importSystem(records)
        return store.last()
    }

    @JvmStatic
    @Synchronized
    fun clear(context: Context) {
        refresh(context) // Remember currently available system records before clearing.
        (archive ?: saved(context))?.clear()
    }

    private fun record(kind: CrashKind, source: String, details: String) {
        val current = session ?: return
        archive?.record(CrashRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), kind,
            details, current.pid, current.id, source))
    }

    private fun contextDetails(): String {
        val current = archive?.session() ?: session ?: return "App 上下文尚未准备完成"
        return "${current.header}\n最后阶段：${current.stage}\n进程 ID：${current.pid}"
    }

    private fun header(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("未提供")
        return buildString {
            appendLine("版本：$version")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            append("ABI：${Build.SUPPORTED_ABIS.joinToString()}; 页面大小：${runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)}")
        }
    }
}

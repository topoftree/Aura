/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/** Advanced Debug mode owns diagnostic collection; launch presentation is a separate choice. */
object RuntimeDiagnostics {
    const val PREFERENCE = "startupDebugMode"
    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var collecting = false
    private val observers = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == PREFERENCE || key == null) update(prefs.getBoolean(PREFERENCE, false))
    }

    @JvmStatic
    @Synchronized
    fun install(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(LegacyDataNames.RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences === prefs) return
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(listener)
        update(prefs.getBoolean(PREFERENCE, false))
    }

    @JvmStatic fun enabled(context: Context): Boolean { install(context); return collecting }
    @JvmStatic fun enabled(): Boolean = collecting

    @JvmStatic fun sessionEnabled(context: Context): Boolean = enabled(context) &&
        ((context as? android.app.Activity)?.intent?.hasExtra("aura_launch_id") != true ||
            RuntimeLaunchDiagnostics.isCollecting(context))

    fun setEnabled(context: Context, value: Boolean) {
        install(context)
        update(value)
        preferences!!.edit().putBoolean(PREFERENCE, value).apply()
        if (!value) StartupExceptionCapture.disable(context)
    }

    @JvmStatic fun observe(observer: (Boolean) -> Unit): AutoCloseable {
        observers += observer
        observer(collecting)
        return AutoCloseable { observers -= observer }
    }

    private fun update(value: Boolean) {
        if (collecting == value) return
        collecting = value
        if (!value) { GameVisualTrace.stop(); FramePipelineTrace.stop(); NativeGraphicsTrace.stopCadence(); DxvkColorTrace.stop() }
        observers.forEach { it(value) }
    }

    /** Last launch-time override: stored trace choices remain available for the next debug run. */
    fun silence(env: EnvVars) {
        StartupCrashTrace.silence(env)
        NetworkNativeProbe.silence(env)
        NativeWakeTrace.silence(env)
        NativeGraphicsTrace.silence(env)
        NativeCodeTrace.silence(env)
        DxvkFlowTrace.silence(env)
        env.put("WINEDEBUG", "-all")
            .put("FEX_SILENTLOG", "1").put("FEX_PROFILESTATS", "0")
            .put("DXVK_LOG_LEVEL", "none").put("VKD3D_DEBUG", "none")
            .put(DxvkDxgiUpdate.TRACE_VARIABLE, "0")
            .put("VKD3D_SHADER_DEBUG", "none").put(NativeFdTrace.VARIABLE, "0")
            .put(NativeFdTrace.ASSERT_VARIABLE, "0")
            .put(NativeFdTrace.HISTORY_VARIABLE, "0")
        env.remove("FEX_OUTPUTLOG")
        env.remove("DXVK_LOG_PATH")
        env.remove("X11_WND_GPU_INFO")
    }
}

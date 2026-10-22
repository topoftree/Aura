/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.inputcontrols

import android.content.Context
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One session owns rumble, so several players sharing the phone cannot cancel each other. */
class GamepadRumbleController @JvmOverloads constructor(
    context: Context,
    private val preferences: SharedPreferences,
    private val devices: RumbleDevices = AndroidRumbleDevices(context),
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val changed: () -> Unit = {},
) : AutoCloseable {
    private data class Source(val descriptor: String?, val connected: Boolean)
    private data class Pulse(val left: Int, val right: Int, val until: Long)
    private data class Playing(val output: RumbleOutput, val pulse: Pulse)
    private val handler = Handler(Looper.getMainLooper())
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val sources = Array(4) { Source(null, false) }
    private val requests = mutableMapOf<Int, Pulse>()
    private val playing = mutableMapOf<String, Playing>()
    private val unavailable = mutableSetOf<String>()
    private var active = false
    private var closed = false
    private val expire = Runnable { synchronized(this) { if (!closed) render() } }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (GamepadVibrationSettings.relevant(key)) synchronized(this) {
            if (!closed) { stop(); unavailable.clear(); changed() }
        }
    }
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        inputManager?.registerInputDeviceListener(deviceListener, handler)
    }

    @Synchronized private fun refreshDevices() {
        if (closed) return
        stop()
        unavailable.clear()
        devices.invalidate()
        changed()
    }

    @Synchronized fun setActive(value: Boolean) {
        if (closed) return
        active = value
        if (!value) stop()
    }

    @Synchronized fun setSource(slot: Int, descriptor: String?, connected: Boolean) {
        if (closed || slot !in sources.indices) return
        val source = Source(descriptor, connected)
        if (sources[slot] != source) {
            sources[slot] = source
            requests.remove(slot)
            render()
        }
    }

    @Synchronized fun selectedOutput(descriptor: String?): RumbleOutput? {
        val mode = GamepadVibrationSettings.read(preferences).output
        val output = runCatching { devices.select(mode, descriptor) }.getOrNull()
        if (output != null && output.key !in unavailable) return output
        return if (mode == VibrationOutput.AUTO) runCatching { devices.phone() }.getOrNull()
            ?.takeIf { it.key !in unavailable } else null
    }

    @Synchronized fun vibrate(slot: Int, left: Int, right: Int, durationMillis: Int) {
        if (closed || slot !in sources.indices) return
        if (!active || !sources[slot].connected || !GamepadVibrationSettings.playerEnabled(preferences, slot)) {
            requests.remove(slot)
            render()
            return
        }
        pulse(slot, left, right, durationMillis)
    }

    /** The settings preview uses the same routing and strength as guest rumble. */
    @Synchronized fun test(descriptor: String?): Boolean {
        val options = GamepadVibrationSettings.read(preferences)
        if (closed || !active || !options.enabled || options.strength == 0 || selectedOutput(descriptor) == null) return false
        sources[0] = Source(descriptor, true)
        pulse(0, 65535, 65535, 250)
        return playing.isNotEmpty()
    }

    private fun pulse(slot: Int, left: Int, right: Int, durationMillis: Int) {
        val strength = GamepadVibrationSettings.read(preferences).strength
        val low = GamepadVibrationSettings.amplitude(left, strength)
        val high = GamepadVibrationSettings.amplitude(right, strength)
        if (durationMillis <= 0 || maxOf(low, high) == 0) requests.remove(slot)
        else requests[slot] = Pulse(low, high, now() + durationMillis.coerceAtMost(60_000))
        render()
    }

    private fun render() {
        handler.removeCallbacks(expire)
        val time = now()
        requests.entries.removeAll { it.value.until <= time }
        val desired = mutableMapOf<String, Playing>()
        if (active) for ((slot, pulse) in requests) {
            val output = selectedOutput(sources[slot].descriptor) ?: continue
            val previous = desired[output.key]?.pulse
            val merged = if (previous == null) pulse else Pulse(maxOf(previous.left, pulse.left),
                maxOf(previous.right, pulse.right), maxOf(previous.until, pulse.until))
            desired[output.key] = Playing(output, merged)
        }
        for ((key, old) in playing) if (key !in desired) runCatching { old.output.cancel() }
        var retry = false
        for ((key, value) in desired.toMap()) {
            if (playing[key]?.pulse != value.pulse) {
                val pulse = value.pulse
                if (runCatching { value.output.play(pulse.left, pulse.right, (pulse.until - time).toInt()) }.isFailure) {
                    runCatching { value.output.cancel() }
                    desired.remove(key)
                    unavailable.add(key)
                    retry = true
                }
            }
        }
        playing.clear()
        playing.putAll(desired)
        if (retry) { render(); return }
        requests.values.minOfOrNull { it.until }?.let { handler.postDelayed(expire, (it - time).coerceAtLeast(1)) }
    }

    @Synchronized fun stop() {
        handler.removeCallbacks(expire)
        requests.clear()
        playing.values.forEach { runCatching { it.output.cancel() } }
        playing.clear()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        active = false
        stop()
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        inputManager?.unregisterInputDeviceListener(deviceListener)
    }
}

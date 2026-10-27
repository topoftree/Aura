/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.inputcontrols

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.winlator.winhandler.GamepadPlayerConfig

enum class VibrationOutput { AUTO, CONTROLLER, PHONE }

data class GamepadVibrationOptions(val enabled: Boolean, val strength: Int, val output: VibrationOutput)

/** The Winlator player flags remain the source of truth, including edits in its own Settings. */
object GamepadVibrationSettings {
    const val STRENGTH = "aura_game_vibration_strength"
    const val OUTPUT = "aura_game_vibration_output"
    private const val PLAYER = "gamepad_player"

    @JvmStatic fun preferences(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    @JvmStatic fun playerEnabled(preferences: SharedPreferences, slot: Int): Boolean =
        slot in 0..3 && GamepadPlayerConfig(preferences.getString("$PLAYER$slot", null)).vibration

    fun read(preferences: SharedPreferences) = GamepadVibrationOptions(
        enabled = (0..3).any { playerEnabled(preferences, it) },
        strength = preferences.getInt(STRENGTH, 100).coerceIn(0, 100),
        output = VibrationOutput.entries.find { it.name == preferences.getString(OUTPUT, null) } ?: VibrationOutput.AUTO,
    )

    fun setEnabled(preferences: SharedPreferences, enabled: Boolean) {
        val editor = preferences.edit()
        for (slot in 0..3) {
            val old = GamepadPlayerConfig(preferences.getString("$PLAYER$slot", null))
            editor.putString("$PLAYER$slot", GamepadPlayerConfig(old.mode, old.name, enabled).toString())
        }
        editor.apply()
    }

    fun setStrength(preferences: SharedPreferences, percent: Int) {
        preferences.edit().putInt(STRENGTH, percent.coerceIn(0, 100)).apply()
    }

    fun setOutput(preferences: SharedPreferences, output: VibrationOutput) {
        preferences.edit().putString(OUTPUT, output.name).apply()
    }

    fun relevant(key: String?) = key == null || key == STRENGTH || key == OUTPUT || key.startsWith(PLAYER)

    fun amplitude(speed: Int, strength: Int): Int =
        ((speed.coerceIn(0, 65535).toLong() * strength.coerceIn(0, 100) * 255 + 3_276_750) / 6_553_500).toInt()
}

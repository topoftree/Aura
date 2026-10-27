/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.content.Intent
import java.util.UUID

/** A request is not a capability result. Only the FEX unix bridge reports a kernel outcome. */
enum class HardwareTsoState { UNTESTED, OFF, UNCONFIRMED, ENABLED, UNSUPPORTED, DENIED, FAILED, MIXED }

object HardwareTso {
    const val PREFERENCES = "aura_hardware_tso_results"
    const val ENVIRONMENT = "AURA_FEX_HARDWARE_TSO"
    private val lock = Any()
    private val marker = Regex("AURA_FEX_HW_TSO_V1 state=(ENABLED|UNSUPPORTED|DENIED|FAILED); scope=FEX_PROCESS")

    /** Last writer wins only within the same launch; late output cannot replace a newer session. */
    fun begin(context: Context, intent: Intent?, env: EnvVars): Session? {
        val gameId = intent?.takeIf(PerGameLaunch::hasSnapshot)?.getIntExtra("aura_game_id", 0) ?: 0
        val enabled = gameId > 0 && intent != null && PerGameLaunch.read(intent).hardwareTso
        // Override inherited variables for installers and games with the experiment off.
        env.put(ENVIRONMENT, if (enabled) "1" else "0")
        // Hardware rejection must leave ordinary memory-order emulation enabled.
        if (enabled) {
            env.put("FEX_TSOENABLED", "1")
            // This FEX version hashes static TSO configuration, not the runtime
            // kernel outcome. Never reuse hardware-generated code after fallback.
            env.put("FEX_DISKCACHE", "0")
        }
        if (gameId <= 0) return null
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val token = UUID.randomUUID().toString()
        val state = if (enabled) HardwareTsoState.UNCONFIRMED else HardwareTsoState.OFF
        synchronized(lock) {
            prefs.edit().putString("$gameId.token", token).putString("$gameId.state", state.name).apply()
        }
        return if (enabled) Session(context.applicationContext, gameId, token) else null
    }

    fun state(context: Context, gameId: Int): HardwareTsoState {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("$gameId.state", null)
        return HardwareTsoState.entries.find { it.name == value } ?: HardwareTsoState.UNTESTED
    }

    class Session internal constructor(private val context: Context, private val gameId: Int, private val token: String) {
        private val outcomes = mutableSetOf<HardwareTsoState>()
        private var closed = false

        /** Fixed vocabulary only. Scope is observed FEX processes, not proof of a game's speed or readiness. */
        fun accept(line: String): String? {
            if (line.length > 128) return null
            val result = marker.matchEntire(line)?.groupValues?.get(1)?.let(HardwareTsoState::valueOf) ?: return null
            synchronized(lock) {
                val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                if (closed || prefs.getString("$gameId.token", null) != token || !outcomes.add(result)) return null
                val state = if (outcomes.size == 1) result else HardwareTsoState.MIXED
                prefs.edit().putString("$gameId.state", state.name).apply()
                return "GAME_HARDWARE_TSO_V1 state=${state.name}; scope=OBSERVED_FEX_PROCESSES; performance=UNCONFIRMED"
            }
        }

        fun close() = synchronized(lock) { closed = true }
    }
}

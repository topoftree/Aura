/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID

/** A clear request survives navigation/process death, but never deletes a live game's cache. */
object GameShaderCacheMaintenance {
    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("aura_shader_cache_maintenance", Context.MODE_PRIVATE)

    fun pending(context: Context, gameId: Int): String? = if (gameId > 0)
        preferences(context).all["game.$gameId"] as? String else null

    @Synchronized fun request(context: Context, gameId: Int) {
        require(gameId > 0)
        preferences(context).edit().putString("game.$gameId", UUID.randomUUID().toString()).apply()
    }

    @Synchronized fun cancel(context: Context, gameId: Int) {
        require(gameId > 0)
        preferences(context).edit().remove("game.$gameId").apply()
    }

    fun observe(context: Context, gameId: Int, changed: () -> Unit): () -> Unit {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "game.$gameId" || key == null) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun beforeLaunch(context: Context, root: File, gameId: Int, eligible: Boolean,
                     previousGuestsStopped: Boolean): GameShaderCacheClearResult? {
        if (!eligible) return null
        val token = pending(context, gameId) ?: return null
        val result = GameShaderCache.clearGame(root, gameId, previousGuestsStopped)
        if (result.complete) synchronized(this) {
            // A later request must survive completion of this earlier clear operation.
            if (pending(context, gameId) == token) preferences(context).edit().remove("game.$gameId").apply()
        }
        return result
    }
}

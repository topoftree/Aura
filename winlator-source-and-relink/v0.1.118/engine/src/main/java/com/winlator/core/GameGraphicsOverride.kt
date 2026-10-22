/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Only parsed launch values change. Never mutate or save the shared Container. */
object GameGraphicsOverride {
    const val GRAPHICS = "aura_game_graphics"
    const val CONFIG = "aura_game_graphics_config"
    const val CACHE_KEY = "aura_game_graphics_cache_key"

    @JvmStatic fun graphics(intent: Intent, fallback: String): String =
        if (PerGameLaunch.hasSnapshot(intent)) intent.getStringExtra(GRAPHICS) ?: fallback else fallback

    @JvmStatic fun config(intent: Intent, fallback: String): String =
        if (PerGameLaunch.hasSnapshot(intent)) intent.getStringExtra(CONFIG) ?: fallback else fallback
}

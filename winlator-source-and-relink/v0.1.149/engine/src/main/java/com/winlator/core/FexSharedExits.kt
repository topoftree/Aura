/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent

/** Only the selected game can opt in; Wine rechecks the actual executable in both frontends. */
object FexSharedExits {
    const val ENABLED = "AURA_FEX_SHARED_SMC_EXITS"
    const val EXECUTABLE = "AURA_FEX_SHARED_SMC_EXITS_EXE"

    fun environment(intent: Intent, input: EnvVars) = EnvVars().apply {
        putAll(input)
        val name = intent.getStringExtra("exec_path").orEmpty().replace('\\', '/').substringAfterLast('/')
        val enabled = PerGameLaunch.hasSnapshot(intent) && PerGameLaunch.read(intent).compactTranslation &&
            name.length in 5..255 && name.endsWith(".exe", true) && name.none { it.code < 32 || it == '=' }
        // Explicit zero/empty prevents a previous game's request leaking into installers or clients.
        put(ENABLED, if (enabled) "1" else "0")
        put(EXECUTABLE, if (enabled) name else "")
    }
}

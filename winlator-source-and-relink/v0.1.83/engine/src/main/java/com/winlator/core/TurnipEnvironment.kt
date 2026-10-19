/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

object TurnipEnvironment {
    @JvmStatic fun debugFlags(adrenoModel: Int, current: String): String {
        val flags = current.split(',').filter { it.isNotBlank() }.toMutableSet()
        when {
            adrenoModel in setOf(710, 720, 732) -> flags += "gmem"
            adrenoModel >= 700 -> flags += "sysmem"
        }
        flags += "deck_emu"
        return flags.joinToString(",")
    }
}

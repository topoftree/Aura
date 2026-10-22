/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
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

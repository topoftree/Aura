/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Persist a resolution tier, not the dimensions of a previously connected display. */
enum class WineScreenMode(val value: String, val shortEdge: Int = 0) {
    AUTOMATIC("auto"),
    P540("height:540", 540),
    P720("height:720", 720),
    P900("height:900", 900),
    P1080("height:1080", 1080),
    P1440("height:1440", 1440),
    P2160("height:2160", 2160),
    NATIVE("native");

    companion object {
        fun decode(value: String?) = entries.find { it.value == value } ?: AUTOMATIC
        val explicitValues = entries.filter { it != AUTOMATIC }.map { it.value }
    }
}

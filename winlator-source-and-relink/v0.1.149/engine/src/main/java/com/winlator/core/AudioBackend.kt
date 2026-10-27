/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Stable container IDs; Reflector and ALSA use the same Wine endpoint. */
enum class AudioBackend(val id: String, val title: String, val wineDriver: String) {
    DIRECT_AUDIO("directaudio", "DirectAudio", "directaudio"),
    ALSA("alsa", "ALSA", "alsa"),
    ALSA_REFLECTOR("alsa-reflector", "ALSA-Reflector", "alsa"),
    PULSE_AUDIO("pulseaudio", "PulseAudio", "pulse");

    companion object {
        @JvmStatic fun fromId(id: String?): AudioBackend = entries.firstOrNull { it.id == id } ?: DIRECT_AUDIO
        @JvmStatic fun normalize(id: String?): String = fromId(id).id
    }
}

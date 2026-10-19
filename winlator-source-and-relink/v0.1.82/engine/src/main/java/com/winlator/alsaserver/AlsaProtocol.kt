/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.alsaserver

import java.io.IOException

object AlsaProtocol {
    const val MAX_BUFFER_BYTES = 1024 * 1024
    @JvmStatic fun validateHeader(code: Int, length: Int) {
        val valid = when (code) {
            0, 1, 2, 3, 6, 7 -> length == 0
            4 -> length == 10
            5 -> length in 0..MAX_BUFFER_BYTES
            8 -> length == 6
            else -> false
        }
        if (!valid) throw IOException("Invalid ALSA request $code/$length")
    }
    @JvmStatic fun validateFormat(channels: Int, type: Int, rate: Int) {
        if (channels !in 1..2 || type !in listOf(0, 1, 3) || rate !in 8000..96000)
            throw IOException("Unsupported ALSA PCM format")
    }
    @JvmStatic fun validateBuffer(channels: Int, type: Int, rate: Int, frames: Int) {
        validateFormat(channels, type, rate)
        val width = when (type) { 0 -> 1; 1 -> 2; else -> 4 }
        if (frames !in 1..(rate * 2) || frames.toLong() * channels * width > MAX_BUFFER_BYTES)
            throw IOException("Invalid ALSA buffer")
    }
}

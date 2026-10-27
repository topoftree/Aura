/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Native pixels stay in memory; only fixed metadata, aggregates and paired differences are exported. */
data class HardwareBufferPixelRead(val pixels: PixelRead, val metadata: String) {
    fun compare(imported: PixelRead) = metadata + "; " + pixels.summary("buffer") + "; " +
        pixels.difference(imported).replace("rgb_max_difference", "buffer_to_import_rgb_max_difference")
            .replace("alpha_max_difference", "buffer_to_import_alpha_max_difference")

    companion object {
        private val states = listOf("OBSERVED", "NO_BUFFER", "CPU_READ_NOT_ALLOWED", "UNSUPPORTED_FORMAT",
            "DIMENSIONS_CHANGED", "PROTECTED_BUFFER", "LOCK_FAILED", "UNSUPPORTED_PLANES", "UNLOCK_FAILED")
        fun decode(values: IntArray?, width: Int, height: Int): HardwareBufferPixelRead {
            fun unknown(state: String) = HardwareBufferPixelRead(PixelRead(null, state), "buffer_metadata=UNAVAILABLE")
            if (values == null) return unknown("NOT_AVAILABLE")
            if (values.size != 46 || values[0] != 1 || values[1] !in states.indices) return unknown("INVALID_NATIVE_RECORD")
            val observed = values[1] == 0
            if (observed && (values[2] != width || values[3] != height || width !in 128..32767 || height !in 128..32767 ||
                values[4] !in listOf(1, 5) || values[5] !in 2..3 || values[6] !in (width * 4)..1048576 ||
                values[7] != 4 || values[8] != 0 || values[9] != 0 || values.sliceArray(10..45).any { it !in 0..255 }))
                return unknown("INVALID_NATIVE_RECORD")
            fun result(at: Int) = if (values[at] == Int.MAX_VALUE) "NOT_CALLED" else values[at].toString()
            val metadata = "buffer_cpu_width=${values[2]}; buffer_cpu_height=${values[3]}; buffer_cpu_format=${values[4]}; " +
                "buffer_cpu_read_usage=${values[5]}; buffer_cpu_row_stride=${values[6]}; buffer_cpu_pixel_stride=${values[7]}; " +
                "buffer_lock_result=${result(8)}; buffer_unlock_result=${result(9)}"
            return HardwareBufferPixelRead(PixelRead(if (observed) values.sliceArray(10..45) else null, states[values[1]]), metadata)
        }
    }
}

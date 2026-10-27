/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** Contiguous Gaussian taps, combined only across adjacent texels for linear sampling. */
object MenuBlurKernel {
    const val MAX_PAIRS = 48 // radius <= 64 px, sigma <= 32 texels, three-sigma support
    data class Axis(val centerWeight: Float, val pairs: FloatArray) {
        val pairCount get() = pairs.size / 2
    }
    data class Plan(val surfaceWidth: Int, val surfaceHeight: Int, val radius: Float,
                    val width: Int, val height: Int, val horizontal: Axis, val vertical: Axis)

    fun plan(surfaceWidth: Int, surfaceHeight: Int, radius: Float): Plan {
        require(surfaceWidth > 0 && surfaceHeight > 0 && radius.isFinite() && radius in 0f..64f)
        val scale = minOf(1f, 512f / max(surfaceWidth, surfaceHeight))
        val width = max(1, (surfaceWidth * scale).roundToInt())
        val height = max(1, (surfaceHeight * scale).roundToInt())
        // Keep approximately the old kernel's spread (sigma ~= radius / 2),
        // using every intervening texel instead of moving five taps far apart.
        return Plan(surfaceWidth, surfaceHeight, radius, width, height,
            axis(radius * 0.5 * width / surfaceWidth), axis(radius * 0.5 * height / surfaceHeight))
    }

    private fun axis(sigma: Double): Axis {
        if (sigma == 0.0) return Axis(1f, floatArrayOf())
        val support = ceil(3 * sigma).toInt().coerceIn(1, MAX_PAIRS * 2)
        val weights = DoubleArray(support + 1) { exp(-0.5 * (it / sigma) * (it / sigma)) }
        val total = weights[0] + 2 * weights.drop(1).sum()
        val pairs = ArrayList<Float>(support + 1)
        for (i in 1..support step 2) {
            val a = weights[i]
            val b = weights.getOrElse(i + 1) { 0.0 }
            if (a + b == 0.0) continue
            pairs += ((i * a + (i + 1) * b) / (a + b)).toFloat()
            pairs += ((a + b) / total).toFloat()
        }
        return Axis((weights[0] / total).toFloat(), pairs.toFloatArray())
    }
}

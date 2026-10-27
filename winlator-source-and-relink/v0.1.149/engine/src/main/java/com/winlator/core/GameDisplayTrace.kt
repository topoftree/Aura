/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Numeric display transitions and immutable renderer transforms. No frame reads or GPU calls. */
class GameDisplayTrace {
    private data class Host(val at: Long, val width: Int, val height: Int, val guestWidth: Int, val guestHeight: Int,
                            val x: Int, val y: Int, val viewWidth: Int, val viewHeight: Int)
    @Volatile private var host: Host? = null
    private val modes = ArrayDeque<String>()
    private var omitted = 0

    fun host(now: Long, width: Int, height: Int, guestWidth: Int, guestHeight: Int,
             x: Int, y: Int, viewWidth: Int, viewHeight: Int) {
        if (width <= 0 || height <= 0 || guestWidth <= 0 || guestHeight <= 0) return
        host = Host(now, width, height, guestWidth, guestHeight, x, y, viewWidth, viewHeight)
    }

    @Synchronized fun mode(now: Long, source: String, previousWidth: Int, previousHeight: Int,
                           width: Int, height: Int, monitorWidth: Int, monitorHeight: Int) {
        val origin = source.takeIf { it in setOf("INITIAL", "WINE_DESKTOP", "WINE_DESKTOP_CLOSED", "WINE_DESKTOP_DISABLED", "RANDR") } ?: "OTHER"
        if (modes.size == 16) { modes.removeFirst(); omitted = (omitted + 1).coerceAtMost(1_000_000) }
        modes.addLast("GAME_DISPLAY_MODE_V1 elapsed_ms=$now; source=$origin; previous=${previousWidth}x$previousHeight; " +
            "viewport=${width}x$height; monitor=${monitorWidth}x$monitorHeight")
    }

    @Synchronized fun report(now: Long, guestWidth: Int, guestHeight: Int): String {
        val current = host
        val geometry = if (current == null) "GAME_HOST_VIEW_V1 state=UNAVAILABLE" else with(current) {
            "GAME_HOST_VIEW_V1 state=OBSERVED; scope=TRANSFORM_COMPUTED; age_ms=${(now - at).coerceAtLeast(0)}; " +
                "surface=${width}x$height; source=${this.guestWidth}x${this.guestHeight}; " +
                "rect=$x,$y,${viewWidth}x$viewHeight; " +
                "source_matches_viewport=${this.guestWidth == guestWidth && this.guestHeight == guestHeight}; pixels=UNOBSERVED"
        }
        return geometry + "\nGAME_DISPLAY_HISTORY_V1 retained=${modes.size}; omitted=$omitted; scope=THIS_SESSION\n" + modes.joinToString("\n")
    }
}

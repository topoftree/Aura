/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Nine sparse RGBA samples in memory, aggregate values only in the diagnostic bundle. */
data class PixelRead(val rgba: IntArray?, val state: String) {
    init { require(rgba == null || rgba.size == 36 && rgba.all { it in 0..255 }) }
    fun summary(prefix: String): String = buildString {
        append("${prefix}_state=$state")
        rgba?.let { values ->
            for ((channel, name) in listOf("r", "g", "b", "a").withIndex()) {
                val samples = (0..8).map { values[it * 4 + channel] }
                append("; ${prefix}_${name}_min=${samples.min()}; ${prefix}_${name}_max=${samples.max()}; " +
                    "${prefix}_${name}_sum=${samples.sum()}")
            }
        }
    }
    fun difference(other: PixelRead): String {
        val a = rgba; val b = other.rgba
        if (a == null || b == null) return "rgb_max_difference=UNKNOWN; alpha_max_difference=UNKNOWN"
        return "rgb_max_difference=${a.indices.filter { it % 4 != 3 }.maxOf { kotlin.math.abs(a[it] - b[it]) }}; " +
            "alpha_max_difference=${(0..8).maxOf { kotlin.math.abs(a[it * 4 + 3] - b[it * 4 + 3]) }}"
    }
}

/** One drawable; COPY and actual DRAW have independent 0/4/8-second budgets. */
class GamePixelCapture(private val clock: () -> Long = System::nanoTime) {
    private val start = clock()
    private var stopped = false
    private var owner: Any? = null
    private val copyBuckets = mutableSetOf<Int>()
    private val drawBuckets = mutableSetOf<Int>()
    private val rows = mutableListOf<String>()
    private var present = 0L
    private var frame = 0L
    private data class Content(val texture: Any, val generation: Int, val width: Int, val height: Int,
                               val present: Long, val copyProbe: Int)
    private var content: Content? = null
    private var output: Ticket? = null
    private fun live() = !stopped && clock() - start in 0 until 10_000_000_000L
    private fun bucket() = ((clock() - start) / 4_000_000_000L).coerceIn(0, 2).toInt()

    inner class Ticket internal constructor(val id: Int, val width: Int, val height: Int,
        val present: Long, val frame: Long, val copyProbe: Int) {
        internal var drawn: PixelRead? = null
        internal var points: IntArray? = null
        internal var viewport: IntArray? = null
        private val used = mutableSetOf<String>()
        fun current() = synchronized(this@GamePixelCapture) { live() && (frame == 0L || frame == this@GamePixelCapture.frame) }
        /** Caller owns the drawable render lock. Never hold the collector monitor while reading GL. */
        fun sample(phase: String, read: () -> String) {
            require(if (frame == 0L) phase == "COPY" else phase in setOf("DRAW", "OUTPUT"))
            synchronized(this@GamePixelCapture) { if (!current() || !used.add(phase)) return }
            val begin = clock()
            val value = try { read() } catch (_: Exception) { "state=QUERY_FAILED" }
                catch (_: LinkageError) { "state=QUERY_UNAVAILABLE" }
            synchronized(this@GamePixelCapture) {
                if (current()) rows += "PIXEL_SAMPLE probe=$id; phase=$phase; present=$present; host_frame=$frame; copy_probe=$copyProbe; " +
                    "width=$width; height=$height; begin_ns=$begin; end_ns=${clock()}; $value"
            }
        }
        fun pair(source: PixelRead, target: PixelRead): String =
            "comparison=IMPORTED_SOURCE_TO_COPY; " + source.summary("source") + "; " + target.summary("target") + "; " + source.difference(target)
        fun expectOutput(at: IntArray, view: IntArray, pixels: PixelRead) = synchronized(this@GamePixelCapture) {
            if (frame != 0L && current()) { points = at.copyOf(); viewport = view.copyOf(); drawn = pixels; output = this }
        }
    }
    @Synchronized fun copied(drawable: Any, texture: Any, width: Int, height: Int, generation: Int = 0): Ticket? {
        if (!live()) return null
        if (owner === drawable) content = null
        if (width < 128 || height < 128) return null
        if (owner == null) owner = drawable
        if (owner !== drawable) return null
        val bucket = bucket()
        val probe = if (copyBuckets.add(bucket)) bucket + 1 else 0
        content = Content(texture, generation, width, height, ++present, probe)
        return if (probe == 0) null else Ticket(probe, width, height, present, 0, probe)
    }
    /** The descriptor tracks unsampled copies too. Read the texture used by this draw, not a previous copy. */
    @Synchronized fun drawing(drawable: Any, texture: Any, generation: Int = 0): Ticket? {
        if (!live() || owner !== drawable || frame == 0L) return null
        val current = content ?: return null
        if (current.texture !== texture || current.generation != generation) { content = null; return null }
        val bucket = bucket()
        if (!drawBuckets.add(bucket)) return null
        return Ticket(bucket + 4, current.width, current.height, current.present, frame, current.copyProbe)
    }
    /** A Present changes the texture, but cannot revoke the framebuffer already drawn in this host frame. */
    @Synchronized fun invalidate(drawable: Any) { if (owner === drawable) content = null }
    @Synchronized fun beginDrawFrame() { output = null; if (live()) frame++ }
    @Synchronized fun output(): Ticket? { val ticket = output; output = null; return ticket?.takeIf { it.current() } }
    @Synchronized fun stop() { stopped = true; content = null; output = null; owner = null }
    @Synchronized fun report() = buildString {
        appendLine("GAME_PIXEL_V3 start_ns=$start; points=9; max_copy_probes=3; max_draw_probes=3; " +
            "copy_reserved=${copyBuckets.size}; draw_reserved=${drawBuckets.size}; rows=${rows.size}; " +
            "scope=AHB_CPU_IMPORTED_TEXTURE_COPY_DRAW_OUTPUT; target=FIRST_PRESENT_DRAWABLE_MIN_128; raw_pixels_exported=NO; units=RGBA8_AGGREGATE; mean=SUM_DIVIDED_BY_9")
        appendLine("PIXEL_LIMITS readback=MANUAL_ONLY_SYNCHRONIZING; timings=INCLUDE_DIAGNOSTIC_COST; max_pixel_reads=135; " +
            "guest_pixels_before_import=UNKNOWN; compositor_pixels=UNMEASURED; physical_display=UNMEASURED; " +
            "draw_comparison=CURRENT_TEXTURE_TO_DRAW_FILTERING_OR_BLENDING_MAY_DIFFER; " +
            "output_comparison=SAME_HOST_FRAME_OCCLUSION_OR_EFFECTS_MAY_DIFFER; copy_probe_zero=UNSAMPLED_PRESENT; missing_phase=UNKNOWN")
        appendLine("AHB_PIXEL_LIMITS mappings=MAX_3_MANUAL_ONLY; points_per_mapping=9; access=CPU_READ_ONLY_AFTER_COPY_COMPLETION_BEFORE_PRESENT_IDLE; " +
            "requires=IMPORTED_NON_PROTECTED_CPU_READABLE_RGBA_OR_BGRA_SINGLE_PLANE; usage_changed=NO; " +
            "comparison=SAME_ALLOCATION_MEMORY_ROW_TO_GL_TEXEL; origin_or_color_conversion=UNPROVEN; " +
            "producer_vulkan_pixels_before_export=UNKNOWN; failures=UNKNOWN_NOT_BLACK; cost=INCLUDED_IN_COPY_PROBE")
        rows.forEach { appendLine(it) }
    }
}

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Numeric metadata only. No pixels, handles, paths, shader text or device-controlled strings. */
class VisualValues {
    private val values = linkedMapOf<String, Number?>()
    fun put(name: String, value: Number?): VisualValues = apply {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,47}")))
        require(values.size < 80 || name in values)
        values[name] = value?.takeIf { it.toDouble().isFinite() }
    }
    fun flag(name: String, value: Boolean?) = put(name, value?.let { if (it) 1 else 0 })
    internal fun render() = values.entries.joinToString("; ") { "${it.key}=${it.value ?: "UNKNOWN"}" }
}

enum class VisualStage { COPY, DRAW, OUTPUT, UI }

/** Three snapshots per kind/window, eight windows maximum, within the manual ten-second window. */
class GameVisualCapture(private val clock: () -> Long = System::nanoTime) {
    val pixels = GamePixelCapture(clock)
    private val start = clock()
    private var stopped = false
    private var end = start
    private val windows = linkedMapOf<Int, Int>()
    private val reserved = mutableSetOf<Triple<VisualStage, Int, Int>>()
    private val rows = mutableListOf<String>()
    private fun live() = !stopped && clock() - start in 0 until 10_000_000_000L
    inner class Ticket internal constructor(private val stage: VisualStage, private val slot: Int, private val bucket: Int) {
        private var consumed = false
        private var started = false
        fun current(): Boolean = synchronized(this@GameVisualCapture) { !started && !consumed && live() }
        /** Queries run on the owning UI/GL thread, outside the collector monitor. */
        fun sample(read: () -> VisualValues) {
            synchronized(this@GameVisualCapture) {
                if (!current()) return
                started = true
            }
            val begin = clock()
            var failed = false
            val text = try { read().render() } catch (_: Exception) { failed = true; "" }
                catch (_: LinkageError) { failed = true; "" }
            synchronized(this@GameVisualCapture) {
                if (consumed || !live()) return
                consumed = true
                rows += "VISUAL_SAMPLE stage=$stage; slot=$slot; bucket=$bucket; begin_ns=$begin; end_ns=${clock()}; " +
                    "state=${if (failed) "QUERY_FAILED" else "OBSERVED"}; $text"
            }
        }
    }
    @Synchronized fun reserve(stage: VisualStage, window: Int = 0): Ticket? {
        if (!live()) return null
        val slot = if (stage == VisualStage.UI || stage == VisualStage.OUTPUT) 0 else
            windows[window] ?: run { if (windows.size == 8) return null; windows.size.also { windows[window] = it } }
        val bucket = ( (clock() - start) / 4_000_000_000L).toInt()
        if (!reserved.add(Triple(stage, slot, bucket))) return null
        return Ticket(stage, slot, bucket)
    }
    @Synchronized fun stop() { if (!stopped) { stopped = true; end = clock().coerceIn(start, start + 10_000_000_000L); pixels.stop() } }
    @Synchronized fun report(): String = buildString {
        append("GAME_VISUAL_V1 start_ns=$start; end_ns=$end; clock=HOST_MONOTONIC_NS; snapshots_atomic=NO; max_rows=54; rows=${rows.size}; reserved=${reserved.size}\n")
        append("SCOPE host_copy_draw_egl_ui_metadata; pixel_rgb_alpha=SEE_GAME_PIXEL_V3; compositor_dataspace=UNKNOWN; panel_hdr=UNKNOWN; guest_swapchain_colorspace=UNKNOWN\n")
        append("LIMITS manual_ms=10000; buckets_ms=0,4000,8000; max_windows=8; query_cost=included_in_host_timings; absence=UNKNOWN; slot=CAPTURE_LOCAL_NOT_GAME_ID\n")
        for (stage in VisualStage.entries) append("STAGE $stage state=${if (rows.any { it.startsWith("VISUAL_SAMPLE stage=$stage;") }) "HAS_SAMPLES" else "NOT_OBSERVED"}\n")
        rows.forEach { append(it).append('\n') }
        append(pixels.report())
    }
}

object GameVisualTrace {
    private data class Active(val id: String, val owner: Any, val capture: GameVisualCapture)
    @Volatile private var active: Active? = null
    @Synchronized fun start(id: String, owner: Any): GameVisualCapture {
        stop()
        return GameVisualCapture().also { active = Active(id, owner, it) }
    }
    @JvmStatic fun ticket(owner: Any, stage: VisualStage, window: Int): GameVisualCapture.Ticket? =
        active?.takeIf { it.owner === owner && RuntimeDiagnostics.enabled() }?.capture?.reserve(stage, window)
    @JvmStatic fun pixels(owner: Any): GamePixelCapture? =
        active?.takeIf { it.owner === owner && RuntimeDiagnostics.enabled() }?.capture?.pixels
    @JvmStatic @Synchronized fun stopOwner(owner: Any) { if (active?.owner === owner) stop() }
    @JvmStatic @Synchronized fun stop() { active?.capture?.stop(); active = null }
    @JvmStatic @Synchronized fun stop(id: String) { if (active?.id == id) stop() }
}

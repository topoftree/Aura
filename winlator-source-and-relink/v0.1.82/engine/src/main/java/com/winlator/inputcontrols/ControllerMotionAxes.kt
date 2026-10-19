/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.inputcontrols

import android.view.MotionEvent
import kotlin.math.abs

/** Android layouts shared by native gamepad input and host keyboard/mouse emulation. */
class ControllerMotionAxes(private val event: MotionEvent) {
    private val device = event.device
    fun range(axis: Int) = device?.getMotionRange(axis, event.source) ?: device?.getMotionRange(axis)
    val separateRight = range(MotionEvent.AXIS_RX) != null && range(MotionEvent.AXIS_RY) != null
    val rightX = if (separateRight) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
    val rightY = if (separateRight) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ

    fun centered(axis: Int, history: Int): Float {
        val flat = range(axis)?.flat ?: return 0f
        val value = if (history < 0) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, history)
        return if (value.isFinite() && abs(value) > flat) value else 0f
    }

    private fun trigger(axis: Int): Float {
        val r = range(axis)
        val value = event.getAxisValue(axis)
        val normalized = if (r != null && r.min < 0f && r.range > 0f) (value - r.min) / r.range else value
        return normalized.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    }

    fun leftTrigger() = maxOf(trigger(MotionEvent.AXIS_LTRIGGER), trigger(MotionEvent.AXIS_BRAKE),
        if (separateRight) trigger(MotionEvent.AXIS_Z) else 0f)
    fun rightTrigger() = maxOf(trigger(MotionEvent.AXIS_RTRIGGER), trigger(MotionEvent.AXIS_GAS),
        if (separateRight) trigger(MotionEvent.AXIS_RZ) else 0f)
}

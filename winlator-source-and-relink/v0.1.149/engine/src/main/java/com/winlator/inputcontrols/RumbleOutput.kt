/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.inputcontrols

import android.content.Context
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice

interface RumbleOutput {
    val key: String
    val isController: Boolean
    val amplitudeControl: Boolean
    fun play(left: Int, right: Int, durationMillis: Int)
    fun cancel()
}

interface RumbleDevices {
    fun controller(descriptor: String): RumbleOutput?
    fun phone(): RumbleOutput?
    fun invalidate() {}

    fun select(output: VibrationOutput, descriptor: String?): RumbleOutput? = when (output) {
        VibrationOutput.AUTO -> descriptor?.let(::controller) ?: phone()
        VibrationOutput.CONTROLLER -> descriptor?.let(::controller)
        VibrationOutput.PHONE -> phone()
    }
}

/** Capabilities are supplied by Android, never inferred from a controller brand or USB ID. */
class AndroidRumbleDevices(context: Context) : RumbleDevices {
    private val context = context.applicationContext
    private val controllers = mutableMapOf<String, RumbleOutput?>()
    private val handset by lazy {
        context.getSystemService(VibratorManager::class.java)?.let { AndroidRumbleOutput.create("phone", false, it) }
    }
    override fun phone() = handset
    override fun invalidate() { controllers.clear() }
    override fun controller(descriptor: String): RumbleOutput? {
        if (controllers.containsKey(descriptor)) return controllers[descriptor]
        val device = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice)
            .firstOrNull { it.descriptor == descriptor && !it.isVirtual }
        val output = device?.let {
            runCatching { AndroidRumbleOutput.create("controller:${it.id}:$descriptor", true, it.vibratorManager) }.getOrNull()
        }
        controllers[descriptor] = output
        return output
    }
}

class AndroidRumbleOutput private constructor(
    override val key: String,
    override val isController: Boolean,
    private val manager: VibratorManager,
    private val motors: List<Pair<Int, Vibrator>>,
) : RumbleOutput {
    override val amplitudeControl = motors.all { it.second.hasAmplitudeControl() }
    private val attributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build()

    override fun play(left: Int, right: Int, durationMillis: Int) {
        if (durationMillis <= 0 || maxOf(left, right) <= 0) { cancel(); return }
        fun effect(vibrator: Vibrator, strength: Int) = VibrationEffect.createOneShot(durationMillis.toLong(),
            if (vibrator.hasAmplitudeControl()) strength.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE)
        if (motors.size == 1) {
            manager.vibrate(CombinedVibration.createParallel(effect(motors[0].second, maxOf(left, right))), attributes)
        } else {
            val combined = CombinedVibration.startParallel()
            // IDs need not be 0 and 1. Use the actual IDs returned by VibratorManager.
            for ((index, motor) in motors.take(2).withIndex()) {
                val strength = if (index == 0) left else right
                if (strength > 0) combined.addVibrator(motor.first, effect(motor.second, strength))
            }
            manager.vibrate(combined.combine(), attributes)
        }
    }

    override fun cancel() = manager.cancel()

    companion object {
        fun create(key: String, isController: Boolean, manager: VibratorManager): RumbleOutput? {
            val motors = manager.vibratorIds.map { it to manager.getVibrator(it) }.filter { it.second.hasVibrator() }
            val available = motors.ifEmpty {
                manager.defaultVibrator.takeIf { it.hasVibrator() }?.let { listOf(-1 to it) } ?: emptyList()
            }
            return available.takeIf { it.isNotEmpty() }?.let { AndroidRumbleOutput(key, isController, manager, it) }
        }
    }
}

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.inputcontrols

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.function.BooleanSupplier

/** Consumed guest input must never fall through to Android's B -> Back fallback. */
object GuestInputRouting {
    @JvmStatic
    fun isControllerInput(source: Int, controllerDevice: Boolean, keyCode: Int = -1): Boolean =
        controllerDevice || source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
            keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16

    @JvmStatic
    fun isControllerKey(event: KeyEvent): Boolean = isControllerInput(
        event.source, ExternalController.isGameController(event.device), event.keyCode,
    )

    @JvmStatic
    fun isControllerMotion(event: MotionEvent): Boolean =
        isControllerInput(event.source, ExternalController.isGameController(event.device))

    @JvmStatic
    fun guestKeyCode(keyCode: Int, controller: Boolean): Int =
        if (controller && keyCode == KeyEvent.KEYCODE_BACK) KeyEvent.KEYCODE_BUTTON_B else keyCode

    @JvmStatic
    fun guestKey(event: KeyEvent, controller: Boolean): KeyEvent {
        val code = guestKeyCode(event.keyCode, controller)
        if (code == event.keyCode) return event
        // Some controller layouts label their B button BACK. Preserve both edges and device ID.
        return KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
    }

    @JvmStatic
    fun dispatch(controller: Boolean, guest: BooleanSupplier, android: BooleanSupplier): Boolean {
        if (guest.asBoolean) return true
        // Includes initialization, repeat events and unmapped controller buttons/axes.
        return controller || android.asBoolean
    }
}

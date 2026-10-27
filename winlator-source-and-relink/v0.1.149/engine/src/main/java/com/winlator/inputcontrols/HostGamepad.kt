/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.inputcontrols

/** A host touch pad publishes complete snapshots; the UDP thread never reads a half-updated stick. */
class HostGamepad : GamepadSlot {
    @Volatile private var state = GamepadState()
    override fun getName() = "Aura Virtual Gamepad"
    override fun getVendorId(): Short = 0x045e
    override fun getProductId(): Short = 0x028e
    override fun getGamepadState() = GamepadState().apply { copy(state) }
    fun update(value: GamepadState) { state = GamepadState().apply { copy(value) } }
    fun reset() { state = GamepadState() }
}

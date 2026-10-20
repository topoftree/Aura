/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.inputcontrols;

public interface GamepadSlot {
    String getName();

    short getVendorId();

    short getProductId();

    GamepadState getGamepadState();

}

/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.winhandler

import org.json.JSONException
import org.json.JSONObject

/** A controller/profile name is opaque text, including commas, equals signs and Unicode. */
class GamepadPlayerConfig(
    @JvmField val mode: Byte,
    @JvmField val name: String,
    @JvmField val vibration: Boolean,
) {
    constructor(values: String?) : this(decode(values))

    private constructor(fields: Fields) : this(fields.mode, fields.name, fields.vibration)

    override fun toString(): String = JSONObject()
        .put("mode", mode.toInt())
        .put("name", name)
        .put("vibration", vibration)
        .toString()

    private data class Fields(
        val mode: Byte = MODE_EXTERNAL_CONTROLLER,
        val name: String = "",
        val vibration: Boolean = false,
    )

    companion object {
        const val MODE_EXTERNAL_CONTROLLER: Byte = 0
        const val MODE_CONTROLS_PROFILE: Byte = 1

        // The old dialog always wrote these fields in this order. Match the final vibration
        // suffix, not every comma: old controller/profile names may themselves contain commas.
        private val legacy = Regex(
            "mode=([01]),name=(.*),vibration=(0|1|t|true|f|false)",
            RegexOption.DOT_MATCHES_ALL,
        )

        private fun decode(values: String?): Fields {
            val text = values?.trim().orEmpty()
            if (text.isEmpty()) return Fields()
            if (text.startsWith('{')) {
                val json = try { JSONObject(text) } catch (_: JSONException) { return Fields() }
                return Fields(
                    mode = if (json.optInt("mode") == MODE_CONTROLS_PROFILE.toInt())
                        MODE_CONTROLS_PROFILE else MODE_EXTERNAL_CONTROLLER,
                    name = json.opt("name") as? String ?: "",
                    vibration = json.optBoolean("vibration"),
                )
            }
            val match = legacy.matchEntire(text) ?: return Fields()
            return Fields(
                mode = match.groupValues[1].toByte(),
                name = match.groupValues[2],
                vibration = match.groupValues[3] in setOf("1", "t", "true"),
            )
        }
    }
}

/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wine otherwise reports automatic proxy discovery for an unconfigured prefix. */
object WineProxyDefaults {
    const val INTERNET_SETTINGS = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    const val CONNECTIONS = "$INTERNET_SETTINGS\\Connections"
    const val DEFAULT_CONNECTION = "DefaultConnectionSettings"
    private val explicitOptions = setOf("ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect")

    /** WinINet format: version, serial, DIRECT flag, then three empty string lengths. */
    fun directConnectionSettings(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x46).putInt(0).putInt(1).putInt(0).putInt(0).putInt(0).array()

    fun prepare(userRegistry: File): String = prepare(userRegistry) { data ->
        WineRegistryEditor(userRegistry).use { registry ->
            registry.setHexValues(CONNECTIONS, DEFAULT_CONNECTION, data)
        }
        // Reopen after close/rename: a modified clone alone is not a persisted setting.
        WineRegistryEditor(userRegistry).use { registry ->
            registry.getHexValues(CONNECTIONS, DEFAULT_CONNECTION)?.contentEquals(data) == true
        }
    }

    fun prepare(userRegistry: File, writeDefault: (ByteArray) -> Boolean): String = try {
        if (!userRegistry.isFile || !userRegistry.canRead() || userRegistry.length() > 16 * 1024 * 1024)
            "REGISTRY_UNAVAILABLE"
        else if (userRegistry.bufferedReader().useLines(::hasExistingSettings)) "EXISTING_SETTINGS_PRESERVED"
        else if (writeDefault(directConnectionSettings())) "DIRECT_DEFAULT" else "DEFAULT_WRITE_FAILED"
    } catch (_: IOException) { "REGISTRY_UNAVAILABLE" }
      catch (_: SecurityException) { "REGISTRY_UNAVAILABLE" }

    /** Read only names, case-insensitively; malformed/empty values still represent explicit choices. */
    private fun hasExistingSettings(lines: Sequence<String>): Boolean {
        val internet = "[${INTERNET_SETTINGS.replace("\\", "\\\\")}]"
        val connections = "[${CONNECTIONS.replace("\\", "\\\\")}]"
        var section = ""
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("[")) section = trimmed.substringBefore(']') + "]"
            else if (trimmed.startsWith('"')) {
                val closing = trimmed.indexOf('"', 1)
                if (closing < 0 || !trimmed.substring(closing + 1).trimStart().startsWith('=')) continue
                val name = trimmed.substring(1, closing)
                if (section.equals(connections, true) && name.equals(DEFAULT_CONNECTION, true)) return true
                if (section.equals(internet, true) && explicitOptions.any { it.equals(name, true) }) return true
            }
        }
        return false
    }
}

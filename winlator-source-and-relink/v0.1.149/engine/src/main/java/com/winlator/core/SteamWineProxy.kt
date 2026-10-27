/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** The observed Steam request stopped in synchronous WPAD on the Wine/Android notification path.
 * Disable ONLY automatic discovery before Wine starts; keep explicit proxy and PAC bytes intact.
 * Unlike the generic new-prefix default, an existing value is inspected, not assumed to be fixed.
 */
object SteamWineProxy {
    data class Plan(val bytes: ByteArray, val mode: String, val hadAutoDetect: Boolean)

    fun plan(blob: ByteArray?, proxyEnabled: Boolean, proxy: String, bypass: String, pac: String): Plan? {
        val data = if (blob != null) {
            if (blob.size !in 24..16_384) return null
            val view = ByteBuffer.wrap(blob).order(LITTLE_ENDIAN)
            if (view.int != 0x46) return null
            view.position(12)
            repeat(3) {
                if (view.remaining() < 4) return null
                val size = view.int
                if (size < 0 || size > view.remaining()) return null
                view.position(view.position() + size)
            }
            blob.copyOf()
        } else {
            val values = listOf(if (proxyEnabled) proxy else "", bypass, pac)
            if (proxyEnabled && proxy.isBlank()) return null
            if (values.any { it.length > 4096 || it.any { ch -> ch.code !in 32..126 } }) return null
            val flags = 1 or (if (proxyEnabled) 2 else 0) or (if (pac.isNotBlank()) 4 else 0)
            ByteBuffer.allocate(24 + values.sumOf(String::length)).order(LITTLE_ENDIAN)
                .putInt(0x46).putInt(0).putInt(flags).apply {
                    values.forEach { putInt(it.length); put(it.toByteArray(Charsets.US_ASCII)) }
                }.array()
        }
        val view = ByteBuffer.wrap(data).order(LITTLE_ENDIAN)
        val old = view.getInt(8)
        if (old and 15 != old) return null
        val flags = (old and 8.inv()).let { if (it == 0) 1 else it }
        view.putInt(8, flags)
        val mode = when {
            flags and 6 == 6 -> "MANUAL_AND_PAC"
            flags and 4 != 0 -> "PAC"
            flags and 2 != 0 -> "MANUAL"
            else -> "DIRECT"
        }
        return Plan(data, mode, old and 8 != 0)
    }

    fun prepare(userRegistry: File): String { return try {
        val prefix = "WINE_STEAM_PROXY_V1 "
        if (!userRegistry.isFile || !userRegistry.canRead() || userRegistry.length() > 16L * 1024 * 1024 ||
            Files.isSymbolicLink(userRegistry.toPath())) return prefix + "action=REGISTRY_UNAVAILABLE; autodetect=UNKNOWN"
        val internet = WineProxyDefaults.INTERNET_SETTINGS
        val connections = WineProxyDefaults.CONNECTIONS
        val name = WineProxyDefaults.DEFAULT_CONNECTION
        val old = WineRegistryEditor(userRegistry).use { editor ->
            listOf(editor.getRawValue(connections, name), editor.getRawValue(internet, "AutoDetect"),
                editor.getRawValue(internet, "ProxyEnable"), editor.getRawValue(internet, "ProxyServer"),
                editor.getRawValue(internet, "ProxyOverride"), editor.getRawValue(internet, "AutoConfigURL"))
        }
        val blob = old[0]?.let { parseHex(it) ?: return prefix + "action=UNRECOGNIZED_SETTINGS; autodetect=UNKNOWN" }
        fun string(index: Int): String? = old[index]?.let {
            if (!it.startsWith('"') || !it.endsWith('"')) null
            else it.substring(1, it.length - 1) // WineRegistryEditor already unescapes values.
        } ?: if (old[index] == null) "" else null
        val enabled = old[2]?.takeIf { it.matches(Regex("dword:[0-9a-fA-F]{8}")) }
            ?.substring(6)?.toLong(16)?.let { it != 0L }
        if (blob == null && old[2] != null && enabled == null) return prefix + "action=UNRECOGNIZED_SETTINGS; autodetect=UNKNOWN"
        val plan = plan(blob, enabled == true, string(3) ?: return prefix + "action=UNRECOGNIZED_SETTINGS; autodetect=UNKNOWN",
            string(4) ?: return prefix + "action=UNRECOGNIZED_SETTINGS; autodetect=UNKNOWN",
            string(5) ?: return prefix + "action=UNRECOGNIZED_SETTINGS; autodetect=UNKNOWN")
            ?: return prefix + "action=UNRECOGNIZED_SETTINGS; autodetect=UNKNOWN"
        if (blob?.contentEquals(plan.bytes) == true && old[1] == "dword:00000000")
            return prefix + "action=ALREADY_CONFIGURED; mode=${plan.mode}; autodetect=OFF"
        val backup = File(userRegistry.parentFile, ".aura-steam-proxy-before-v1.txt")
        check(!Files.isSymbolicLink(backup.toPath()))
        if (!backup.exists()) Files.writeString(backup.toPath(),
            listOf("DefaultConnectionSettings", "AutoDetect", "ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL")
                .mapIndexed { index, key -> "$key=${old[index] ?: "<absent>"}" }.joinToString("\n"), CREATE_NEW, WRITE)
        WineRegistryEditor(userRegistry).use {
            it.setHexValues(connections, name, plan.bytes)
            it.setDwordValue(internet, "AutoDetect", 0)
        }
        val verified = WineRegistryEditor(userRegistry).use {
            it.getHexValues(connections, name)?.contentEquals(plan.bytes) == true &&
                it.getRawValue(internet, "AutoDetect") == "dword:00000000"
        }
        if (verified) prefix + "action=CONFIGURED; mode=${plan.mode}; autodetect=OFF; previous_autodetect=${plan.hadAutoDetect}"
        else prefix + "action=WRITE_NOT_VERIFIED; autodetect=UNKNOWN"
    } catch (_: Exception) { "WINE_STEAM_PROXY_V1 action=UNAVAILABLE; autodetect=UNKNOWN" } }

    internal fun parseHex(raw: String): ByteArray? {
        if (!raw.startsWith("hex:")) return null
        val items = raw.substring(4).replace("\\\r\n", "").replace("\\\n", "").split(',').map(String::trim)
        if (items.size > 16_384 || items.any { !it.matches(Regex("[0-9a-fA-F]{2}")) }) return null
        return items.map { it.toInt(16).toByte() }.toByteArray()
    }
}

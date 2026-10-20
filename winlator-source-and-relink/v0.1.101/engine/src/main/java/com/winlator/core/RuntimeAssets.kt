/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import java.io.File
import org.json.JSONObject

object RuntimeAssets {
    @JvmStatic fun hasWindowsComponents(context: Context, windows: File, selection: String): Boolean {
        val manifest = context.assets.open("wincomponents/file-index.json").bufferedReader().use { JSONObject(it.readText()) }
        return KeyValueSet(selection).all { (name, mode) ->
            if (mode != "1") true else {
                val paths = manifest.optJSONArray(name) ?: throw RuntimeComponentException("WINDOWS_COMPONENT", "INVALID_CATALOGUE")
                RuntimeComponentFiles.hasFiles(windows, *Array(paths.length()) { paths.getString(it) })
            }
        }
    }

    @JvmStatic @JvmOverloads
    fun extract(context: Context, asset: String, destination: File, listener: TarCompressorUtils.OnExtractFileListener? = null) {
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, asset, destination, listener)) {
            val component = when (asset.substringBefore('/')) {
                "graphics_driver" -> "GRAPHICS_DRIVER"
                "dxwrapper" -> "DIRECTX_WRAPPER"
                "wincomponents" -> "WINDOWS_COMPONENT"
                "rootfs_patches.tzst" -> "WINE_PATCHES"
                "pulseaudio.tzst" -> "PULSEAUDIO"
                else -> "RUNTIME_ASSET"
            }
            throw RuntimeComponentException(component, "EXTRACTION_FAILED")
        }
    }
}
